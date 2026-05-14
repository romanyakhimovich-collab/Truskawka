package mesh.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object X3dhHandshake {
    data class PrivateBundle(
        val identityDh: KeyPair,
        val identitySigning: KeyPair,
        val signedPreKey: KeyPair,
        val signedPreKeySignature: ByteArray,
        val oneTimePreKey: KeyPair?
    )

    data class PublicBundle(
        val identityDhPublic: ByteArray,
        val identitySigningPublic: ByteArray,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
        val oneTimePreKeyPublic: ByteArray?
    )

    data class InitiatorResult(
        val ephemeralPublic: ByteArray,
        val sessionKey: ByteArray
    )

    fun generatePrivateBundle(includeOneTimePreKey: Boolean = true): PrivateBundle {
        val identityDh = generateX25519()
        val identitySigning = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signedPreKey = generateX25519()
        val signature = sign(identitySigning.private, signedPreKey.public.encoded)

        return PrivateBundle(
            identityDh = identityDh,
            identitySigning = identitySigning,
            signedPreKey = signedPreKey,
            signedPreKeySignature = signature,
            oneTimePreKey = if (includeOneTimePreKey) generateX25519() else null
        )
    }

    fun publicBundle(privateBundle: PrivateBundle): PublicBundle =
        PublicBundle(
            identityDhPublic = privateBundle.identityDh.public.encoded,
            identitySigningPublic = privateBundle.identitySigning.public.encoded,
            signedPreKeyPublic = privateBundle.signedPreKey.public.encoded,
            signedPreKeySignature = privateBundle.signedPreKeySignature,
            oneTimePreKeyPublic = privateBundle.oneTimePreKey?.public?.encoded
        )

    fun deriveAsInitiator(
        initiatorIdentityDh: KeyPair,
        recipientBundle: PublicBundle
    ): InitiatorResult? {
        if (!verify(
                recipientBundle.identitySigningPublic,
                recipientBundle.signedPreKeyPublic,
                recipientBundle.signedPreKeySignature
            )
        ) {
            return null
        }

        val ephemeral = generateX25519()
        val dhParts = mutableListOf<ByteArray>()
        dhParts += ecdh(initiatorIdentityDh.private, recipientBundle.signedPreKeyPublic)
        dhParts += ecdh(ephemeral.private, recipientBundle.identityDhPublic)
        dhParts += ecdh(ephemeral.private, recipientBundle.signedPreKeyPublic)
        recipientBundle.oneTimePreKeyPublic?.let {
            dhParts += ecdh(ephemeral.private, it)
        }

        return InitiatorResult(
            ephemeralPublic = ephemeral.public.encoded,
            sessionKey = deriveSessionKey(dhParts)
        )
    }

    fun deriveAsRecipient(
        recipientBundle: PrivateBundle,
        initiatorIdentityDhPublic: ByteArray,
        initiatorEphemeralPublic: ByteArray
    ): ByteArray {
        val dhParts = mutableListOf<ByteArray>()
        dhParts += ecdh(recipientBundle.signedPreKey.private, initiatorIdentityDhPublic)
        dhParts += ecdh(recipientBundle.identityDh.private, initiatorEphemeralPublic)
        dhParts += ecdh(recipientBundle.signedPreKey.private, initiatorEphemeralPublic)
        recipientBundle.oneTimePreKey?.let {
            dhParts += ecdh(it.private, initiatorEphemeralPublic)
        }
        return deriveSessionKey(dhParts)
    }

    private fun generateX25519(): KeyPair =
        KeyPairGenerator.getInstance("X25519").generateKeyPair()

    private fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    private fun verify(publicKey: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(publicKey))
            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(key)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    private fun ecdh(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPublicKey = decodeX25519(peerPublicKeyBytes)
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    private fun decodeX25519(publicKeyBytes: ByteArray): PublicKey =
        KeyFactory.getInstance("X25519")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))

    private fun deriveSessionKey(parts: List<ByteArray>): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("bitchat-x3dh-v1".toByteArray(), "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal().take(32).toByteArray()
    }
}
