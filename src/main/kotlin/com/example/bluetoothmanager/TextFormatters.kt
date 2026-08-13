package com.example.bluetoothmanager

fun String.wrapForChatBubble(): String {
    if (length <= BUBBLE_WRAP_CHARS) return this
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
        if (word.length > BUBBLE_WRAP_CHARS) {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
            }
            word.chunked(BUBBLE_WRAP_CHARS).forEach { lines += it }
        } else if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= BUBBLE_WRAP_CHARS) {
            current.append(' ').append(word)
        } else {
            lines += current.toString()
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines.joinToString("\n")
}
