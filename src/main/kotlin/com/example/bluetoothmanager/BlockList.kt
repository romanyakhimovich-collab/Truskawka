package com.example.bluetoothmanager

import android.content.Context
import java.util.UUID

/**
 * Users the person blocked. Messages, photos, voice notes and profile photos from them are
 * dropped by the service before any notification or chat entry is created. Blocking is
 * local: the blocked person is not told and can still relay other people's traffic.
 */
object BlockList {
    private fun prefs(context: Context) =
        context.getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)

    fun blockedIds(context: Context): Set<String> =
        prefs(context).getStringSet(UI_SETTINGS_BLOCKED_PEERS, emptySet()).orEmpty()

    fun isBlocked(context: Context, nodeId: UUID?): Boolean =
        nodeId != null && nodeId.toString() in blockedIds(context)

    fun setBlocked(context: Context, nodeId: UUID, blocked: Boolean) {
        val updated = blockedIds(context).toMutableSet().apply {
            if (blocked) add(nodeId.toString()) else remove(nodeId.toString())
        }
        prefs(context).edit().putStringSet(UI_SETTINGS_BLOCKED_PEERS, updated).apply()
    }
}
