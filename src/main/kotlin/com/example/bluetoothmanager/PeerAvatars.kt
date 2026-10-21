package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Profile photos travel between peers as an ordinary encrypted file transfer whose name
 * starts with [FILE_PREFIX]; the receiver stores it per node id instead of showing a message.
 */
object PeerAvatars {
    const val FILE_PREFIX = "avatar~"
    private const val MAX_DIMENSION = 256
    private const val MAX_INCOMING_BYTES = 256 * 1024

    private fun file(context: Context, nodeId: String): File =
        File(File(context.filesDir, "peer_avatars"), "$nodeId.jpg")

    /** Stored photo of a peer, or null when they haven't shared one. */
    fun pathFor(context: Context, nodeId: String?): String? {
        if (nodeId.isNullOrBlank()) return null
        return file(context, nodeId).takeIf { it.isFile }?.absolutePath
    }

    fun isAvatarFileName(fileName: String): Boolean = fileName.startsWith(FILE_PREFIX)

    /** Validates that the bytes really are an image before storing them. */
    fun saveIncoming(context: Context, nodeId: UUID, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_INCOMING_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val target = file(context, nodeId.toString())
        target.parentFile?.mkdirs()
        return runCatching { target.writeBytes(bytes) }.isSuccess
    }

    /** Small JPEG of our own photo plus a short content hash, or null without a photo. */
    fun prepareOwn(context: Context): Pair<String, ByteArray>? {
        val path = AppProfileStore.avatarPath(context).takeIf { it.isNotBlank() && File(it).isFile } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }
        return hash to bytes
    }
}
