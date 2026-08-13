package com.example.bluetoothmanager

import java.util.UUID

enum class PageTab {
    NEW_CONTACTS,
    CONTACTS,
    PROFILE,
    SETTINGS
}

enum class AppLanguage(val code: String, val label: String) {
    EN("en", "EN"),
    PL("pl", "PL"),
    ES("es", "ES"),
    RU("ru", "RU");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: EN
    }
}

enum class SettingsGroup(
    val titleKey: String,
    val descriptionKey: String
) {
    ACCOUNT("settings_account", "settings_account_desc"),
    APPEARANCE("settings_appearance", "settings_appearance_desc"),
    PRIVACY("settings_security", "settings_security_desc"),
    MESH("settings_mesh_section", "settings_mesh_desc"),
    NOTIFICATIONS("settings_notifications", "settings_notifications_desc"),
    MEDIA("settings_media", "settings_media_desc"),
    CHAT("settings_chat_behavior", "settings_chat_behavior_desc"),
    REGION("settings_region", "settings_region_desc"),
    DATA("settings_data_storage", "settings_data_storage_desc")
}

data class StyledActionItem(
    val label: String,
    val destructive: Boolean = false,
    val action: () -> Unit
)

data class ChatMessage(
    var author: String,
    var body: String,
    val mine: Boolean,
    val imagePath: String? = null,
    val audioPath: String? = null,
    var reaction: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var messageId: UUID? = null,
    var status: MessageStatus? = null,
    var localId: Long = 0L
)

enum class MessageStatus {
    SENDING,
    FAILED,
    DELIVERED,
    READ
}

data class PreparedImage(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class IncomingSender(
    val label: String,
    val nodeId: UUID?,
    val isBroadcast: Boolean
)

data class IncomingMeta(
    val senderRaw: String,
    val timestamp: Long,
    val payload: String
)
