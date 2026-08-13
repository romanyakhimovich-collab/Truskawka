package com.example.bluetoothmanager

import java.io.File

object MediaCacheCleaner {
    private val cacheFolders = listOf("sent_images", "incoming_images", "incoming_audio", "voice_notes")

    fun cleanup(filesDir: File, maxBytes: Long) {
        val folders = cacheFolders
            .map { File(filesDir, it) }
            .filter { it.exists() && it.isDirectory }
        val files = folders.flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var total = files.sumOf { it.length() }
        while (total > maxBytes && files.isNotEmpty()) {
            val first = files.removeAt(0)
            val size = first.length()
            if (first.delete()) total -= size
        }
    }
}
