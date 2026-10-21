package com.example.bluetoothmanager

import java.util.Base64
import java.util.UUID

/**
 * Group chats on top of private messages: a group message is sent to each member over the
 * normal end-to-end encrypted channel, wrapped in an envelope carrying the group id, name
 * and member list, so the group appears (and stays in sync) on every member's phone.
 *
 * Text:  __truskawka_grp__:<groupId>|<base64url name>|<member,member,...>|<body>
 * Files: g~<groupId>~<original file name>
 * An empty body is an invite ("you were added to the group").
 */
object GroupProtocol {
    const val TEXT_PREFIX = "__truskawka_grp__:"
    const val FILE_PREFIX = "g~"
    const val CHAT_KEY_PREFIX = "group:"
    const val MAX_MEMBERS = 20
    const val MAX_NAME_LENGTH = 40

    data class Envelope(
        val groupId: UUID,
        val name: String,
        val members: List<UUID>,
        val body: String
    )

    fun chatKey(groupId: UUID): String = "$CHAT_KEY_PREFIX$groupId"

    fun groupIdFromChatKey(chatKey: String?): UUID? =
        chatKey?.takeIf { it.startsWith(CHAT_KEY_PREFIX) }
            ?.removePrefix(CHAT_KEY_PREFIX)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun encodeText(groupId: UUID, name: String, members: List<UUID>, body: String): String {
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.take(MAX_NAME_LENGTH).toByteArray(Charsets.UTF_8))
        val memberList = members.distinct().take(MAX_MEMBERS).joinToString(",")
        return "$TEXT_PREFIX$groupId|$encodedName|$memberList|$body"
    }

    fun decodeText(raw: String): Envelope? {
        if (!raw.startsWith(TEXT_PREFIX)) return null
        val parts = raw.removePrefix(TEXT_PREFIX).split("|", limit = 4)
        if (parts.size != 4) return null
        val groupId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
        val name = runCatching {
            String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        }.getOrNull()?.take(MAX_NAME_LENGTH)?.ifBlank { null } ?: return null
        val members = parts[2].split(",")
            .mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            .distinct()
            .take(MAX_MEMBERS)
        return Envelope(groupId, name, members, parts[3])
    }

    fun encodeFileName(groupId: UUID, fileName: String): String = "$FILE_PREFIX$groupId~$fileName"

    /** Returns the group id and the original file name, or null for a non-group file. */
    fun decodeFileName(fileName: String): Pair<UUID, String>? {
        if (!fileName.startsWith(FILE_PREFIX)) return null
        val rest = fileName.removePrefix(FILE_PREFIX)
        val groupId = runCatching { UUID.fromString(rest.substringBefore('~')) }.getOrNull() ?: return null
        return groupId to rest.substringAfter('~', "").ifBlank { "file" }
    }
}
