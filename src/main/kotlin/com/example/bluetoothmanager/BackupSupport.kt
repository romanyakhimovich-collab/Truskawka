package com.example.bluetoothmanager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupSupport {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), '2'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_SIZE_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000

    fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return java.lang.Long.toHexString(crc.value).padStart(8, '0')
    }

    fun readUriBytesWithLimit(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Int
    ): ByteArray? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray? {
        if (password.isEmpty()) return null
        return runCatching {
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            ByteBuffer.allocate(MAGIC.size + 1 + 4 + 1 + salt.size + 1 + iv.size + encrypted.size)
                .put(MAGIC)
                .put(VERSION)
                .putInt(PBKDF2_ITERATIONS)
                .put(salt.size.toByte())
                .put(salt)
                .put(iv.size.toByte())
                .put(iv)
                .put(encrypted)
                .array()
        }.getOrNull()
    }

    fun decrypt(payload: ByteArray, password: CharArray): ByteArray? {
        if (!isPasswordBackup(payload) || password.isEmpty()) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(payload)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            require(magic.contentEquals(MAGIC))
            val version = buffer.get()
            require(version == VERSION)
            val iterations = buffer.int
            require(iterations in 100_000..1_000_000)
            val saltLength = buffer.get().toInt() and 0xFF
            require(saltLength in 8..64 && buffer.remaining() > saltLength)
            val salt = ByteArray(saltLength).also { buffer.get(it) }
            val ivLength = buffer.get().toInt() and 0xFF
            require(ivLength in 8..32 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also { buffer.get(it) }
            val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, passwordKey(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted)
        }.getOrNull()
    }

    fun decrypt(context: Context, payload: ByteArray, password: CharArray): ByteArray? =
        decrypt(payload, password) ?: decryptLegacy(context, payload)

    fun isPasswordBackup(payload: ByteArray): Boolean =
        payload.size >= MAGIC.size && payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(context: Context, plain: ByteArray): ByteArray? {
        return runCatching {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, backupKey(context), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain)
            iv + encrypted
        }.getOrNull()
    }

    fun decrypt(context: Context, payload: ByteArray): ByteArray? {
        return decryptLegacy(context, payload)
    }

    private fun decryptLegacy(context: Context, payload: ByteArray): ByteArray? {
        if (payload.size <= 12) return null
        return runCatching {
            val iv = payload.copyOfRange(0, 12)
            val data = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, backupKey(context), GCMParameterSpec(128, iv))
            cipher.doFinal(data)
        }.getOrNull()
    }

    private fun passwordKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun backupKey(context: Context): SecretKeySpec {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val seed = "${context.packageName}|$androidId|truskawka_backup_v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest.copyOf(16), "AES")
    }
}
