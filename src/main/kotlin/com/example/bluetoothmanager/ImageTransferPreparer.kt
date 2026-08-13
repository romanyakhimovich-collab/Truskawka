package com.example.bluetoothmanager

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

object ImageTransferPreparer {
    fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "image.jpg"
    }

    fun copyImageToLocalFile(filesDir: File, fileName: String, bytes: ByteArray): File {
        val directory = File(filesDir, "sent_images").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image.jpg" }
        return File(directory, "${System.currentTimeMillis()}_$safeName").also { it.writeBytes(bytes) }
    }

    fun prepareForTransfer(
        contentResolver: ContentResolver,
        uri: Uri,
        originalName: String
    ): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return decodeImageWithImageDecoder(contentResolver, uri)?.let { bitmap ->
                val scaled = scaleBitmapIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
                val compressed = compressBitmap(scaled)
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
                PreparedImage("${originalName.substringBeforeLast('.', "image")}.jpg", "image/jpeg", compressed)
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, MAX_IMAGE_DIMENSION)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: decodeImageWithImageDecoder(contentResolver, uri) ?: return null

        val scaled = scaleBitmapIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
        val compressed = compressBitmap(scaled)
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        val safeName = originalName
            .substringBeforeLast('.', originalName)
            .ifBlank { "image" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
        return PreparedImage("$safeName.jpg", "image/jpeg", compressed)
    }

    private fun decodeImageWithImageDecoder(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxDimension: Int): Int {
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / 2 >= maxDimension || height / 2 >= maxDimension) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        var quality = 82
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 8
        } while (bytes.size > TARGET_IMAGE_BYTES && quality >= 50)
        return bytes
    }
}
