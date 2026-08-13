package com.example.bluetoothmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import mesh.crypto.SecureKeyStorage
import mesh.crypto.TrustInfo
import java.security.KeyStore
import java.security.KeyFactory
import java.security.KeyPair
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AndroidSecureKeyStorage(context: Context) : SecureKeyStorage {
    private val prefs = context.getSharedPreferences("mesh_crypto", Context.MODE_PRIVATE)

    override fun saveIdentityKey(keyPair: KeyPair) {
        saveKeyPair("identity", keyPair)
    }

    override fun loadIdentityKey(): KeyPair? = loadKeyPair("identity", "Ed25519")

    override fun saveExchangeKey(keyPair: KeyPair) {
        saveKeyPair("exchange", keyPair)
    }

    override fun loadExchangeKey(): KeyPair? = loadKeyPair("exchange", "X25519")

    override fun saveTrustedPeers(peers: List<TrustInfo>) {
        val encoded = peers.joinToString("\n") { peer ->
            listOf(
                peer.fingerprint,
                encode(peer.identityKey),
                peer.firstSeen.toString(),
                peer.verified.toString()
            ).joinToString("|")
        }
        prefs.edit().putString("trusted_peers", encoded).apply()
    }

    override fun loadTrustedPeers(): List<TrustInfo> {
        val encoded = prefs.getString("trusted_peers", null).orEmpty()
        if (encoded.isBlank()) return emptyList()

        return encoded.lineSequence().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 4) return@mapNotNull null
            TrustInfo(
                fingerprint = parts[0],
                identityKey = decode(parts[1]),
                firstSeen = parts[2].toLongOrNull() ?: return@mapNotNull null,
                verified = parts[3].toBoolean()
            )
        }.toList()
    }

    private fun saveKeyPair(prefix: String, keyPair: KeyPair) {
        prefs.edit()
            .putString("${prefix}_public", encode(keyPair.public.encoded))
            .putString("${prefix}_private_wrapped", encryptForStorage(keyPair.private.encoded))
            .remove("${prefix}_private")
            .apply()
    }

    private fun loadKeyPair(prefix: String, algorithm: String): KeyPair? {
        val publicEncoded = prefs.getString("${prefix}_public", null) ?: return null
        val privateBytes = prefs.getString("${prefix}_private_wrapped", null)
            ?.let(::decryptFromStorage)
            ?: prefs.getString("${prefix}_private", null)?.let(::decode)
            ?: return null
        return try {
            val factory = KeyFactory.getInstance(algorithm)
            val keyPair = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(decode(publicEncoded))),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            )
            if (prefs.contains("${prefix}_private")) {
                saveKeyPair(prefix, keyPair)
            }
            keyPair
        } catch (e: Exception) {
            null
        }
    }

    private fun encryptForStorage(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.doFinal(bytes)
        return encode(cipher.iv + encrypted)
    }

    private fun decryptFromStorage(value: String): ByteArray? {
        val payload = decode(value)
        if (payload.size <= STORAGE_NONCE_BYTES) return null
        return try {
            val nonce = payload.copyOfRange(0, STORAGE_NONCE_BYTES)
            val ciphertext = payload.copyOfRange(STORAGE_NONCE_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "truskawka_mesh_key_wrapper"
        private const val STORAGE_NONCE_BYTES = 12
    }
}
