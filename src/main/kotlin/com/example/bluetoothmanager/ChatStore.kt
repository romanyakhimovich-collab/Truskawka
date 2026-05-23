package com.example.bluetoothmanager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
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
        db.execSQL("CREATE INDEX idx_messages_chat_time ON messages(chat_key, timestamp)")
        db.execSQL("CREATE INDEX idx_messages_mesh_id ON messages(mesh_message_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
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

    companion object {
        private const val DB_NAME = "truskawka_chats.db"
        private const val DB_VERSION = 1
    }
}

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
