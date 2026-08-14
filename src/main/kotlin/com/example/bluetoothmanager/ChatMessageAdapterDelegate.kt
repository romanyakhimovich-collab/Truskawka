package com.example.bluetoothmanager

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import java.util.UUID

interface ChatMessageAdapterDelegate {
    val messageTextScale: Float
    val selectedRecipientId: UUID?
    val activePlayingPath: String?
    val cropChatImages: Boolean

    fun dp(value: Int): Int
    fun tr(key: String): String
    fun terminalText(value: String): TextView
    fun terminalAction(value: String): TextView
    fun roundedDrawable(color: Int, cornerRadius: Int, strokeColor: Int? = null): GradientDrawable
    fun calculateChatImageSize(bitmap: Bitmap?): Pair<Int, Int>
    fun calculateChatImageSize(width: Int, height: Int): Pair<Int, Int>
    fun displayAuthor(message: ChatMessage): String
    fun displayTime(message: ChatMessage): String
    fun displayDate(message: ChatMessage): String
    fun showImagePreview(imagePath: String)
    fun showMessageActions(message: ChatMessage)
    fun attachReplySwipe(view: View, message: ChatMessage)
    fun toggleAudioPlayback(path: String?, button: TextView)
    fun audioDurationLabel(path: String?): String
}
