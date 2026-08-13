package mesh.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption for Mesh Messaging (E2EE without central server)
 *
 * Key Exchange Protocol (Offline First Handshake):
 * ================================================
 *
 * Trust Model: TOFU (Trust On First Use) - similar to SSH/Signal
 *
 * Step 1: Identity Key Generation (One-time, on app install)
 *   - Generate Ed25519 keypair for signing (identity)
 *   - Generate X25519 keypair for key exchange
 *   - Store private keys in secure storage (Android Keystore / iOS Keychain)
 *
 * Step 2: Discovery & Handshake (When two devices meet)
 *
 *   Device A                                    Device B
 *   --------                                    --------
 *   Generate ephemeral X25519 keypair
 *   HANDSHAKE: {
 *     identity_pubkey_A,
 *     ephemeral_pubkey_A,
 *     signature(ephemeral_pubkey_A)
 *   }
 *                      ───────────────────────────►
 *                                                  Verify signature
 *                                                  Generate ephemeral keypair
 *                                                  Compute shared_secret = ECDH(
 *                                                    ephemeral_priv_B,
 *                                                    ephemeral_pub_A
 *                                                  )
 *                      ◄───────────────────────────
 *   HANDSHAKE_ACK: {
 *     identity_pubkey_B,
 *     ephemeral_pubkey_B,
 *     signature(ephemeral_pubkey_B),
 *     encrypted_verification  // Proves B has shared secret
 *   }
 *
 *   Verify signature
 *   Compute shared_secret = ECDH(ephemeral_priv_A, ephemeral_pub_B)
 *   Derive session_key = HKDF(shared_secret, salt, info)
 *   Store (identity_B, session_key) in trusted peers
 *
 * Step 3: Message Encryption
 *   - Use ChaCha20-Poly1305 (or AES-GCM) with session_key
 *   - Include nonce/IV (12 bytes, incremented per message)
 *   - Sign encrypted message with identity key
 *
 * Key Verification (Prevent MITM):
 *   - Display "Safety Number" = hash(identity_A + identity_B)
 *   - Users can verify in person (QR code or number comparison)
 */
class MeshCrypto(private val secureStorage: SecureKeyStorage) {

    // Identity keys (Ed25519 for signing)
    private lateinit var identityKeyPair: KeyPair

    // Key exchange keys (X25519 for ECDH)
    private lateinit var exchangeKeyPair: KeyPair

    // Session keys with peers: nodeId -> SessionInfo
    private val sessions = ConcurrentHashMap<UUID, SessionInfo>()

    // Trusted peers (TOFU): identity fingerprint -> trust info
    private val trustedPeers = ConcurrentHashMap<String, TrustInfo>()

    // Pending handshakes
    private val pendingHandshakes = ConcurrentHashMap<UUID, HandshakeState>()

    companion object {
        const val NONCE_SIZE = 12
        const val KEY_SIZE = 32
        const val TAG_SIZE = 16
        const val SIGNATURE_SIZE = 64
    }

    /**
     * Initialize crypto system - generate or load identity keys
     */
    fun initialize() {
        // Try to load existing keys from secure storage
        val storedIdentity = secureStorage.loadIdentityKey()
        val storedExchange = secureStorage.loadExchangeKey()

        if (storedIdentity != null && storedExchange != null) {
            identityKeyPair = storedIdentity
            exchangeKeyPair = storedExchange
        } else {
            // First run - generate new identity
            generateIdentityKeys()
        }

        // Load trusted peers from storage
        loadTrustedPeers()
    }

    private fun generateIdentityKeys() {
        // Ed25519 for signing
        val identityGen = KeyPairGenerator.getInstance("Ed25519")
        identityKeyPair = identityGen.generateKeyPair()

        // X25519 for key exchange
        val exchangeGen = KeyPairGenerator.getInstance("X25519")
        exchangeKeyPair = exchangeGen.generateKeyPair()

        // Persist to secure storage
        secureStorage.saveIdentityKey(identityKeyPair)
        secureStorage.saveExchangeKey(exchangeKeyPair)
    }

    /**
     * Get our identity public key (share with others)
     */
    fun getIdentityPublicKey(): ByteArray {
        return identityKeyPair.public.encoded
    }

    /**
     * Get fingerprint of our identity (for verification display)
     */
    fun getIdentityFingerprint(): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identityKeyPair.public.encoded)
        return hash.take(16).joinToString(":") { "%02X".format(it) }
    }

    // ==================== HANDSHAKE PROTOCOL ====================

    /**
     * Step 1: Initiate handshake - create HANDSHAKE packet payload
     */
    fun createHandshakePayload(targetNodeId: UUID): ByteArray {
        // Generate ephemeral X25519 keypair for this session
        val ephemeralGen = KeyPairGenerator.getInstance("X25519")
        val ephemeralKeyPair = ephemeralGen.generateKeyPair()

        // Store for completing handshake
        pendingHandshakes[targetNodeId] = HandshakeState(
            ephemeralKeyPair = ephemeralKeyPair,
            startedAt = System.currentTimeMillis()
        )

        // Sign the ephemeral public key with our identity
        val signature = sign(ephemeralKeyPair.public.encoded)

        // Payload: len-prefixed [identity_pubkey | ephemeral_pubkey | signature].
        // JVM public keys are X.509 encoded and are not fixed 32-byte arrays.
        return encodeFields(
            identityKeyPair.public.encoded,
            ephemeralKeyPair.public.encoded,
            signature
        )
    }

    /**
     * Step 2: Handle received HANDSHAKE, create HANDSHAKE_ACK
     */
    fun handleHandshakeAndCreateAck(senderNodeId: UUID, payload: ByteArray): HandshakeResult {
        val fields = decodeFields(payload, expectedCount = 3)
        if (fields == null) {
            return HandshakeResult.Failed("Malformed handshake")
        }

        val identityPubKey = fields[0]
        val ephemeralPubKey = fields[1]
        val signature = fields[2]

        // Verify signature
        if (!verifySignature(ephemeralPubKey, signature, identityPubKey)) {
            return HandshakeResult.Failed("Invalid signature")
        }

        // Check TOFU - is this a known peer?
        val fingerprint = computeFingerprint(identityPubKey)
        val trustInfo = trustedPeers[fingerprint]
        val trustStatus = when {
            trustInfo == null -> TrustStatus.NEW_PEER
            trustInfo.verified -> TrustStatus.TRUSTED
            else -> TrustStatus.KNOWN_UNVERIFIED
        }

        // Generate our ephemeral keypair
        val ephemeralGen = KeyPairGenerator.getInstance("X25519")
        val ourEphemeral = ephemeralGen.generateKeyPair()

        // Compute shared secret via ECDH
        val sharedSecret = performECDH(ourEphemeral.private, ephemeralPubKey)

        // Derive session key using HKDF
        val sessionKey = deriveSessionKey(sharedSecret, identityPubKey, identityKeyPair.public.encoded)

        // Store session
        sessions[senderNodeId] = SessionInfo(
            sessionKey = sessionKey,
            peerIdentity = identityPubKey,
            peerFingerprint = fingerprint,
            establishedAt = System.currentTimeMillis(),
            messageCounter = AtomicLong(0)
        )

        // Update trusted peers (TOFU)
        if (trustInfo == null) {
            trustedPeers[fingerprint] = TrustInfo(
                fingerprint = fingerprint,
                identityKey = identityPubKey,
                firstSeen = System.currentTimeMillis(),
                verified = false
            )
            saveTrustedPeers()
        }

        // Create verification token (proves we have the shared secret)
        val verificationPlaintext = "MESH_HANDSHAKE_VERIFY".toByteArray()
        val encryptedVerification = encryptWithKey(verificationPlaintext, sessionKey)

        // Sign our ephemeral key
        val ourSignature = sign(ourEphemeral.public.encoded)

        val ackPayload = encodeFields(
            identityKeyPair.public.encoded,
            ourEphemeral.public.encoded,
            ourSignature,
            encryptedVerification
        )

        return HandshakeResult.Success(
            ackPayload = ackPayload,
            trustStatus = trustStatus,
            safetyNumber = computeSafetyNumber(identityPubKey, identityKeyPair.public.encoded)
        )
    }

    /**
     * Step 3: Handle HANDSHAKE_ACK, complete session establishment
     */
    fun handleHandshakeAck(senderNodeId: UUID, payload: ByteArray): HandshakeResult {
        val pending = pendingHandshakes.remove(senderNodeId)
            ?: return HandshakeResult.Failed("No pending handshake")

        val fields = decodeFields(payload, expectedCount = 4)
            ?: return HandshakeResult.Failed("Malformed handshake ack")

        val identityPubKey = fields[0]
        val ephemeralPubKey = fields[1]
        val signature = fields[2]
        val encryptedVerification = fields[3]

        // Verify signature
        if (!verifySignature(ephemeralPubKey, signature, identityPubKey)) {
            return HandshakeResult.Failed("Invalid signature")
        }

        // Compute shared secret
        val sharedSecret = performECDH(pending.ephemeralKeyPair.private, ephemeralPubKey)
        val sessionKey = deriveSessionKey(sharedSecret, identityKeyPair.public.encoded, identityPubKey)

        // Verify the verification token
        val decryptedVerification = decryptWithKey(encryptedVerification, sessionKey)
        if (decryptedVerification == null ||
            !decryptedVerification.contentEquals("MESH_HANDSHAKE_VERIFY".toByteArray())) {
            return HandshakeResult.Failed("Verification failed")
        }

        // Store session
        val fingerprint = computeFingerprint(identityPubKey)
        sessions[senderNodeId] = SessionInfo(
            sessionKey = sessionKey,
            peerIdentity = identityPubKey,
            peerFingerprint = fingerprint,
            establishedAt = System.currentTimeMillis(),
            messageCounter = AtomicLong(0)
        )

        // TOFU update
        val trustInfo = trustedPeers[fingerprint]
        val trustStatus = when {
            trustInfo == null -> TrustStatus.NEW_PEER
            trustInfo.verified -> TrustStatus.TRUSTED
            else -> TrustStatus.KNOWN_UNVERIFIED
        }

        if (trustInfo == null) {
            trustedPeers[fingerprint] = TrustInfo(
                fingerprint = fingerprint,
                identityKey = identityPubKey,
                firstSeen = System.currentTimeMillis(),
                verified = false
            )
            saveTrustedPeers()
        }

        return HandshakeResult.Success(
            ackPayload = ByteArray(0), // No further payload needed
            trustStatus = trustStatus,
            safetyNumber = computeSafetyNumber(identityKeyPair.public.encoded, identityPubKey)
        )
    }

    // ==================== MESSAGE ENCRYPTION ====================

    /**
     * Encrypt a message for a peer with established session
     */
    fun encryptMessage(recipientNodeId: UUID, plaintext: ByteArray): EncryptedMessage? {
        val session = sessions[recipientNodeId] ?: return null

        // Increment nonce counter
        val nonce = generateNonce(session.messageCounter.getAndIncrement())

        // Encrypt with session key
        val ciphertext = encryptWithKey(plaintext, session.sessionKey, nonce)

        return EncryptedMessage(
            ciphertext = ciphertext,
            nonce = nonce
        )
    }

    /**
     * Decrypt a message from a peer
     */
    fun decryptMessage(senderNodeId: UUID, encrypted: EncryptedMessage): ByteArray? {
        val session = sessions[senderNodeId] ?: return null
        return decryptWithKey(encrypted.ciphertext, session.sessionKey, encrypted.nonce)
    }

    /**
     * Sign data with our identity key
     */
    fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(identityKeyPair.private)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Verify signature from a peer
     */
    fun verifySignature(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val pubKeySpec = java.security.spec.X509EncodedKeySpec(publicKey)
            val pubKey = keyFactory.generatePublic(pubKeySpec)

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== HELPER METHODS ====================

    private fun performECDH(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519")
        val pubKeySpec = java.security.spec.X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = keyFactory.generatePublic(pubKeySpec)

        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    private fun deriveSessionKey(sharedSecret: ByteArray, pubA: ByteArray, pubB: ByteArray): ByteArray {
        // Simple HKDF-like derivation
        val info = "MESH_SESSION_KEY".toByteArray()
        val salt = pubA + pubB // Deterministic salt from both public keys

        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(salt, "HmacSHA256"))
        return hmac.doFinal(sharedSecret + info).take(32).toByteArray()
    }

    private fun encryptWithKey(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray? = null
    ): ByteArray {
        val actualNonce = nonce ?: generateNonce(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, actualNonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encrypted = cipher.doFinal(plaintext)
        return if (nonce == null) actualNonce + encrypted else encrypted
    }

    private fun decryptWithKey(
        ciphertext: ByteArray,
        key: ByteArray,
        nonce: ByteArray? = null
    ): ByteArray? {
        return try {
            val actualNonce = nonce ?: ciphertext.take(NONCE_SIZE).toByteArray()
            val actualCiphertext = if (nonce == null)
                ciphertext.drop(NONCE_SIZE).toByteArray()
            else
                ciphertext

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, actualNonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(actualCiphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun generateNonce(counter: Long): ByteArray {
        return ByteBuffer.allocate(NONCE_SIZE)
            .putLong(counter)
            .putInt(System.currentTimeMillis().toInt())
            .array()
    }

    private fun computeFingerprint(publicKey: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Safety number for manual verification (like Signal)
     */
    private fun computeSafetyNumber(pubA: ByteArray, pubB: ByteArray): String {
        // Sort to make deterministic regardless of who initiates
        val sorted = listOf(pubA, pubB).sortedBy { it.contentToString() }
        val combined = sorted[0] + sorted[1]
        val hash = MessageDigest.getInstance("SHA-256").digest(combined)

        // Convert to 6 groups of 5 digits
        return hash.take(15).chunked(5).joinToString(" ") { chunk ->
            chunk.fold(0L) { acc, b -> (acc shl 8) + (b.toInt() and 0xFF) }
                .mod(100000L)
                .toString()
                .padStart(5, '0')
        }
    }

    private fun encodeFields(vararg fields: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dataOut ->
            fields.forEach { field ->
                require(field.size <= UShort.MAX_VALUE.toInt()) {
                    "Handshake field too large: ${field.size} bytes"
                }
                dataOut.writeShort(field.size)
                dataOut.write(field)
            }
        }
        return out.toByteArray()
    }

    private fun decodeFields(payload: ByteArray, expectedCount: Int): List<ByteArray>? {
        return try {
            val fields = mutableListOf<ByteArray>()
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                repeat(expectedCount) {
                    val size = input.readUnsignedShort()
                    val field = ByteArray(size)
                    input.readFully(field)
                    fields += field
                }
                if (input.available() == 0) fields else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasSessionWith(nodeId: UUID): Boolean = sessions.containsKey(nodeId)

    fun getSessionInfo(nodeId: UUID): SessionInfo? = sessions[nodeId]

    private fun loadTrustedPeers() {
        secureStorage.loadTrustedPeers()?.forEach { trustedPeers[it.fingerprint] = it }
    }

    private fun saveTrustedPeers() {
        secureStorage.saveTrustedPeers(trustedPeers.values.toList())
    }

    /**
     * Mark a peer as verified (after manual verification)
     */
    fun markPeerAsVerified(fingerprint: String) {
        trustedPeers[fingerprint]?.let {
            trustedPeers[fingerprint] = it.copy(verified = true)
            saveTrustedPeers()
        }
    }
}

// ==================== DATA CLASSES ====================

data class SessionInfo(
    val sessionKey: ByteArray,
    val peerIdentity: ByteArray,
    val peerFingerprint: String,
    val establishedAt: Long,
    val messageCounter: AtomicLong
)

data class TrustInfo(
    val fingerprint: String,
    val identityKey: ByteArray,
    val firstSeen: Long,
    val verified: Boolean
)

data class HandshakeState(
    val ephemeralKeyPair: KeyPair,
    val startedAt: Long
)

data class EncryptedMessage(
    val ciphertext: ByteArray,
    val nonce: ByteArray
)

sealed class HandshakeResult {
    data class Success(
        val ackPayload: ByteArray,
        val trustStatus: TrustStatus,
        val safetyNumber: String
    ) : HandshakeResult()

    data class Failed(val reason: String) : HandshakeResult()
}

enum class TrustStatus {
    NEW_PEER,          // First time seeing this identity
    KNOWN_UNVERIFIED,  // Seen before, but not manually verified
    TRUSTED            // Manually verified (safety number confirmed)
}

/**
 * Interface for platform-specific secure storage
 */
interface SecureKeyStorage {
    fun saveIdentityKey(keyPair: KeyPair)
    fun loadIdentityKey(): KeyPair?
    fun saveExchangeKey(keyPair: KeyPair)
    fun loadExchangeKey(): KeyPair?
    fun saveTrustedPeers(peers: List<TrustInfo>)
    fun loadTrustedPeers(): List<TrustInfo>?
}
