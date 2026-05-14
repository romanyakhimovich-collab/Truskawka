package com.example.bluetoothmanager

import android.content.Context
import android.util.Base64
import mesh.crypto.SecureKeyStorage
import mesh.crypto.TrustInfo
import java.security.KeyFactory
import java.security.KeyPair
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
            .putString("${prefix}_private", encode(keyPair.private.encoded))
            .apply()
    }

    private fun loadKeyPair(prefix: String, algorithm: String): KeyPair? {
        val publicEncoded = prefs.getString("${prefix}_public", null) ?: return null
        val privateEncoded = prefs.getString("${prefix}_private", null) ?: return null
        return try {
            val factory = KeyFactory.getInstance(algorithm)
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(decode(publicEncoded))),
                factory.generatePrivate(PKCS8EncodedKeySpec(decode(privateEncoded)))
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}
