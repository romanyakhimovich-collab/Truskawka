package com.example.bluetoothmanager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createMessages(db)
        createChats(db)
        createPeers(db)
        createTransfers(db)
        seedBaseChats(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createChats(db)
            createPeers(db)
            createTransfers(db)
            seedBaseChats(db)
            db.execSQL("UPDATE messages SET chat_key = ? WHERE chat_key = ?", arrayOf(CHAT_EVERYONE, LEGACY_CHAT_MESH))
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "chats", "unread_count", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun ensureChat(chat: StoredChat) {
        writableDatabase.insertWithOnConflict(
            "chats",
            null,
            chat.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun ensureBaseChats() {
        writableDatabase.run {
            ensureChat(StoredChat(CHAT_EVERYONE, "Everyone", ChatKind.EVERYONE.name))
            ensureChat(StoredChat(CHAT_SAVED, "Saved messages", ChatKind.SAVED.name))
        }
    }

    fun upsertPeer(peer: StoredPeer) {
        writableDatabase.insertWithOnConflict(
            "peers",
            null,
            peer.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        ensureChat(
            StoredChat(
                chatKey = peer.chatKey,
                title = peer.alias,
                kind = ChatKind.PEER.name,
                peerId = peer.nodeId,
                verified = peer.verified,
                updatedAt = peer.lastSeen
            )
        )
    }

    fun getPeer(nodeId: String): StoredPeer? {
        readableDatabase.query(
            "peers",
            null,
            "node_id = ?",
            arrayOf(nodeId),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toPeer() else null
        }
    }

    fun setPeerVerified(nodeId: String, verified: Boolean) {
        val now = System.currentTimeMillis()
        writableDatabase.update(
            "peers",
            ContentValues().apply {
                put("verified", if (verified) 1 else 0)
                put("last_seen", now)
            },
            "node_id = ?",
            arrayOf(nodeId)
        )
        writableDatabase.update(
            "chats",
            ContentValues().apply {
                put("verified", if (verified) 1 else 0)
                put("updated_at", now)
            },
            "peer_id = ?",
            arrayOf(nodeId)
        )
    }

    fun listChats(): List<ChatSummary> {
        val rows = mutableListOf<ChatSummary>()
        readableDatabase.rawQuery(
            """
            SELECT
                c.chat_key,
                c.title,
                c.kind,
                c.peer_id,
                c.verified,
                c.unread_count,
                COALESCE(m.body, '') AS last_body,
                m.image_path AS last_image_path,
                COALESCE(m.timestamp, c.updated_at) AS last_timestamp,
                (
                    SELECT COUNT(*)
                    FROM messages x
                    WHERE x.chat_key = c.chat_key
                ) AS message_count
            FROM chats c
            LEFT JOIN messages m ON m.id = (
                SELECT id
                FROM messages lm
                WHERE lm.chat_key = c.chat_key
                ORDER BY lm.timestamp DESC, lm.id DESC
                LIMIT 1
            )
            ORDER BY
                CASE c.kind
                    WHEN '${ChatKind.SAVED.name}' THEN 0
                    WHEN '${ChatKind.EVERYONE.name}' THEN 1
                    ELSE 2
                END,
                last_timestamp DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ChatSummary(
                    chatKey = cursor.getString(cursor.getColumnIndexOrThrow("chat_key")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                    peerId = cursor.getString(cursor.getColumnIndexOrThrow("peer_id")),
                    verified = cursor.getInt(cursor.getColumnIndexOrThrow("verified")) == 1,
                    unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread_count")),
                    lastBody = cursor.getString(cursor.getColumnIndexOrThrow("last_body")),
                    lastImagePath = cursor.getString(cursor.getColumnIndexOrThrow("last_image_path")),
                    lastTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_timestamp")),
                    messageCount = cursor.getInt(cursor.getColumnIndexOrThrow("message_count"))
                )
            }
        }
        return rows
    }

    fun loadMessages(chatKey: String): List<StoredMessage> {
        val rows = mutableListOf<StoredMessage>()
        readableDatabase.query(
            "messages",
            null,
            "chat_key = ?",
            arrayOf(chatKey),
            null,
            null,
            "timestamp ASC, id ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val authorIndex = cursor.getColumnIndexOrThrow("author")
            val bodyIndex = cursor.getColumnIndexOrThrow("body")
            val mineIndex = cursor.getColumnIndexOrThrow("mine")
            val imageIndex = cursor.getColumnIndexOrThrow("image_path")
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp")
            val meshIdIndex = cursor.getColumnIndexOrThrow("mesh_message_id")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            while (cursor.moveToNext()) {
                rows += StoredMessage(
                    id = cursor.getLong(idIndex),
                    author = cursor.getString(authorIndex),
                    body = cursor.getString(bodyIndex),
                    mine = cursor.getInt(mineIndex) == 1,
                    imagePath = cursor.getString(imageIndex),
                    timestamp = cursor.getLong(timestampIndex),
                    meshMessageId = cursor.getString(meshIdIndex),
                    status = cursor.getString(statusIndex)
                )
            }
        }
        return rows
    }

    fun insertMessage(chatKey: String, message: StoredMessage): Long {
        touchChat(chatKey, message.timestamp)
        return writableDatabase.insert("messages", null, message.toValues(chatKey))
    }

    fun updateMessageIdentity(localId: Long, meshMessageId: String?, status: String?) {
        val values = ContentValues().apply {
            put("mesh_message_id", meshMessageId)
            put("status", status)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(localId.toString()))
    }

    fun updateStatusByMeshMessageId(meshMessageId: String, status: String) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update("messages", values, "mesh_message_id = ?", arrayOf(meshMessageId))
    }

    fun updateMineAuthor(author: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("author", author) },
            "mine = ?",
            arrayOf("1")
        )
    }

    fun incrementUnread(chatKey: String) {
        writableDatabase.execSQL(
            "UPDATE chats SET unread_count = unread_count + 1, updated_at = ? WHERE chat_key = ?",
            arrayOf<Any>(System.currentTimeMillis(), chatKey)
        )
    }

    fun clearUnread(chatKey: String) {
        writableDatabase.update(
            "chats",
            ContentValues().apply { put("unread_count", 0) },
            "chat_key = ?",
            arrayOf(chatKey)
        )
    }

    fun countMessages(chatKey: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE chat_key = ?",
            arrayOf(chatKey)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clearChat(chatKey: String) {
        writableDatabase.delete("messages", "chat_key = ?", arrayOf(chatKey))
        touchChat(chatKey, System.currentTimeMillis())
    }

    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_key TEXT NOT NULL,
                author TEXT NOT NULL,
                body TEXT NOT NULL,
                mine INTEGER NOT NULL,
                image_path TEXT,
                timestamp INTEGER NOT NULL,
                mesh_message_id TEXT,
                status TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_key, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_mesh_id ON messages(mesh_message_id)")
    }

    private fun createChats(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chats (
                chat_key TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                peer_id TEXT,
                verified INTEGER NOT NULL DEFAULT 0,
                unread_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_updated ON chats(updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_peer ON chats(peer_id)")
    }

    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS peers (
                node_id TEXT PRIMARY KEY,
                alias TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createTransfers(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transfers (
                transfer_id TEXT PRIMARY KEY,
                chat_key TEXT NOT NULL,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                total_bytes INTEGER NOT NULL,
                received_bytes INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transfers_chat ON transfers(chat_key)")
    }

    private fun seedBaseChats(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            "chats",
            null,
            StoredChat(CHAT_SAVED, "Saved messages", ChatKind.SAVED.name, createdAt = now, updatedAt = now).toValues(),
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.insertWithOnConflict(
            "chats",
            null,
            StoredChat(CHAT_EVERYONE, "Everyone", ChatKind.EVERYONE.name, createdAt = now, updatedAt = now).toValues(),
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun touchChat(chatKey: String, timestamp: Long) {
        writableDatabase.update(
            "chats",
            ContentValues().apply { put("updated_at", timestamp) },
            "chat_key = ?",
            arrayOf(chatKey)
        )
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return
            }
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun StoredMessage.toValues(chatKey: String): ContentValues =
        ContentValues().apply {
            put("chat_key", chatKey)
            put("author", author)
            put("body", body)
            put("mine", if (mine) 1 else 0)
            put("image_path", imagePath)
            put("timestamp", timestamp)
            put("mesh_message_id", meshMessageId)
            put("status", status)
        }

    private fun StoredChat.toValues(): ContentValues =
        ContentValues().apply {
            put("chat_key", chatKey)
            put("title", title)
            put("kind", kind)
            put("peer_id", peerId)
            put("verified", if (verified) 1 else 0)
            put("unread_count", unreadCount)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }

    private fun StoredPeer.toValues(): ContentValues =
        ContentValues().apply {
            put("node_id", nodeId)
            put("alias", alias)
            put("fingerprint", fingerprint)
            put("verified", if (verified) 1 else 0)
            put("last_seen", lastSeen)
        }

    private fun android.database.Cursor.toPeer(): StoredPeer =
        StoredPeer(
            nodeId = getString(getColumnIndexOrThrow("node_id")),
            alias = getString(getColumnIndexOrThrow("alias")),
            fingerprint = getString(getColumnIndexOrThrow("fingerprint")),
            verified = getInt(getColumnIndexOrThrow("verified")) == 1,
            lastSeen = getLong(getColumnIndexOrThrow("last_seen"))
        )

    companion object {
        private const val DB_NAME = "truskawka_chats.db"
        private const val DB_VERSION = 3
        const val CHAT_EVERYONE = "everyone"
        const val CHAT_SAVED = "saved"
        private const val LEGACY_CHAT_MESH = "mesh"
    }
}

enum class ChatKind {
    SAVED,
    EVERYONE,
    PEER
}

data class StoredChat(
    val chatKey: String,
    val title: String,
    val kind: String,
    val peerId: String? = null,
    val verified: Boolean = false,
    val unreadCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

data class StoredPeer(
    val nodeId: String,
    val alias: String,
    val fingerprint: String,
    val verified: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val chatKey: String
        get() = "peer:$nodeId"
}

data class StoredTransfer(
    val transferId: String,
    val chatKey: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val receivedBytes: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatSummary(
    val chatKey: String,
    val title: String,
    val kind: String,
    val peerId: String?,
    val verified: Boolean,
    val unreadCount: Int,
    val lastBody: String,
    val lastImagePath: String?,
    val lastTimestamp: Long,
    val messageCount: Int
)

data class StoredMessage(
    val id: Long = 0L,
    val author: String,
    val body: String,
    val mine: Boolean,
    val imagePath: String?,
    val timestamp: Long,
    val meshMessageId: String?,
    val status: String?
)
