package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Typeface

/**
 * App-wide Open Sans typefaces. Views are built in code and set their typeface explicitly,
 * so they use these instead of the system default faces. Falls back to the system font
 * until [init] runs (or if the font can't be loaded).
 */
object AppFonts {
    var regular: Typeface = Typeface.DEFAULT
        private set
    var bold: Typeface = Typeface.DEFAULT_BOLD
        private set

    fun init(context: Context) {
        val resources = context.applicationContext.resources
        runCatching { resources.getFont(R.font.open_sans_regular) }.onSuccess { regular = it }
        runCatching { resources.getFont(R.font.open_sans_bold) }.onSuccess { bold = it }
    }
}
