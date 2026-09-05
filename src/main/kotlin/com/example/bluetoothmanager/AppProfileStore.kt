package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

object AppProfileStore {
    private const val PREFS = "bitchat_profile"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_AVATAR_PATH = "avatar_path"
    private const val KEY_PROFILE_COMPLETE = "profile_complete"

    fun displayName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "")
            .orEmpty()

    fun setDisplayName(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, value.trim().take(24))
            .apply()
    }

    fun hasCompletedProfile(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PROFILE_COMPLETE, false) &&
            prefs.getString(KEY_NICKNAME, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_DISPLAY_NAME, "").orEmpty().isNotBlank()
    }

    fun markProfileComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROFILE_COMPLETE, true)
            .apply()
    }

    fun avatarPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AVATAR_PATH, "")
            .orEmpty()

    fun setAvatarFromUri(context: Context, uri: Uri): String? {
        val directory = File(context.filesDir, "profile").apply { mkdirs() }
        val target = File(directory, "avatar_${System.currentTimeMillis()}.jpg")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val oldPath = avatarPath(context)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AVATAR_PATH, target.absolutePath)
                .apply()
            oldPath.takeIf { it.isNotBlank() && it != target.absolutePath }?.let {
                runCatching { File(it).delete() }
            }
            target.absolutePath
        }.getOrNull()
    }

    fun setAvatarBitmap(context: Context, bitmap: Bitmap): String? {
        val directory = File(context.filesDir, "profile").apply { mkdirs() }
        val target = File(directory, "avatar_${System.currentTimeMillis()}.jpg")
        return runCatching {
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            val oldPath = avatarPath(context)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AVATAR_PATH, target.absolutePath)
                .apply()
            oldPath.takeIf { it.isNotBlank() && it != target.absolutePath }?.let {
                runCatching { File(it).delete() }
            }
            target.absolutePath
        }.getOrNull()
    }
}
