package com.example.bluetoothmanager

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

    class ChatMessageAdapter(private val context: Context, private val items: List<ChatMessage>, private val delegate: ChatMessageAdapterDelegate) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ChatMessage = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = getItem(position)
            val isServiceLog = item.author == "system" || item.author == "mesh"
            val content = buildMessageContent(position, item, isServiceLog)
            val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // Date header stays outside the selectable row, like in the reference design.
            if (shouldShowDateHeader(position)) {
                outer.addView(dateHeader(item), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = delegate.dp(5)
                    bottomMargin = delegate.dp(3)
                })
            }
            if (!delegate.selectionMode || isServiceLog) {
                outer.addView(content, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                return outer
            }
            val selected = delegate.isMessageSelected(item)
            outer.addView(SelectableRowLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (selected) setBackgroundColor((OUTGOING_BUBBLE and 0x00FFFFFF) or 0x2E000000)
                addView(SelectionCheckView(
                    context,
                    checked = selected,
                    ringColor = BERRY_TEXT_DIM,
                    checkedColor = LEAF_GREEN
                ), LinearLayout.LayoutParams(delegate.dp(26), delegate.dp(26)).apply {
                    marginStart = delegate.dp(8)
                })
                addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { delegate.toggleMessageSelection(item) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return outer
        }

        private fun buildMessageContent(position: Int, item: ChatMessage, isServiceLog: Boolean): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = when {
                    isServiceLog -> Gravity.CENTER_HORIZONTAL
                    item.mine -> Gravity.END
                    else -> Gravity.START
                }
                setPadding(delegate.dp(8), delegate.dp(5), delegate.dp(8), delegate.dp(5))
                if (shouldShowSenderLabel(position, item, isServiceLog)) {
                    addView(senderLabel(item), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.START
                        bottomMargin = delegate.dp(4)
                    })
                }
                if (item.imagePath != null) {
                    val bounds = imageBounds(item.imagePath)
                    val imageSize = bounds
                        ?.let { delegate.calculateChatImageSize(it.first, it.second) }
                        ?: delegate.calculateChatImageSize(null)
                    val bitmap = decodeSampledFile(item.imagePath, imageSize.first, imageSize.second)
                    val image = BorderedImageView(context, IMAGE_BORDER).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = false
                        scaleType = if (delegate.cropChatImages) {
                            ImageView.ScaleType.CENTER_CROP
                        } else {
                            ImageView.ScaleType.FIT_CENTER
                        }
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { delegate.showImagePreview(item.imagePath) }
                        setOnLongClickListener {
                            delegate.showMessageActions(item)
                            true
                        }
                    }
                    addView(image, LinearLayout.LayoutParams(
                        imageSize.first,
                        imageSize.second
                    ))
                    addView(messageTimeView(item, overOutgoing = item.mine).apply {
                        setPadding(0, delegate.dp(4), delegate.dp(4), 0)
                    }, LinearLayout.LayoutParams(
                        imageSize.first,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    reactionBadge(item)?.let { badge ->
                        addView(badge, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = if (item.mine) Gravity.END else Gravity.START
                            topMargin = delegate.dp(3)
                        })
                    }
                    delegate.attachReplySwipe(image, item)
                    return@apply
                }
                if (item.audioPath != null) {
                    val bubble = voiceBubble(item)
                    addView(bubble, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    delegate.attachReplySwipe(bubble, item)
                    return@apply
                }

                val bubble = if (isServiceLog) {
                    serviceBubble(item)
                } else {
                    messageBubble(item)
                }
                addView(bubble, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                reactionBadge(item)?.let { badge ->
                    addView(badge, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                            gravity = if (item.mine) Gravity.END else Gravity.START
                        topMargin = delegate.dp(3)
                    })
                }
                if (!isServiceLog) {
                    bubble.setOnLongClickListener {
                        delegate.showMessageActions(item)
                        true
                    }
                    delegate.attachReplySwipe(bubble, item)
                }
            }
        }

        private fun shouldShowSenderLabel(position: Int, item: ChatMessage, isServiceLog: Boolean): Boolean {
            if (isServiceLog || item.mine) return false
            if (position == 0) return true
            val previous = getItem(position - 1)
            if (previous.author == "system" || previous.author == "mesh") return true
            if (previous.mine) return true
            return previous.author != item.author
        }

        private fun senderLabel(item: ChatMessage): TextView =
            delegate.terminalText(delegate.displayAuthor(item)).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT_DIM)
                setPadding(delegate.dp(6), 0, delegate.dp(6), 0)
            }

        private fun shouldShowDateHeader(position: Int): Boolean {
            if (position == 0) return true
            val current = Calendar.getInstance().apply { timeInMillis = getItem(position).timestamp }
            val previous = Calendar.getInstance().apply { timeInMillis = getItem(position - 1).timestamp }
            return !current.isSameDay(previous)
        }

        private fun dateHeader(item: ChatMessage): TextView =
            TextView(context).apply {
                text = delegate.displayDate(item)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                background = delegate.roundedDrawable(SERVICE_BUBBLE, delegate.dp(12), SERVICE_BUBBLE_STROKE)
                setPadding(delegate.dp(12), delegate.dp(6), delegate.dp(12), delegate.dp(6))
            }

        private fun serviceBubble(item: ChatMessage): TextView =
            TextView(context).apply {
                text = item.body
                typeface = Typeface.DEFAULT
                textSize = 13f
                setLineSpacing(delegate.dp(2).toFloat(), 1f)
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                setTextColor(MUTED_CORAL)
                background = delegate.roundedDrawable(SERVICE_BUBBLE, delegate.dp(12), SERVICE_BUBBLE_STROKE)
                setPadding(delegate.dp(16), delegate.dp(9), delegate.dp(16), delegate.dp(9))
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
            }

        private fun messageBubble(item: ChatMessage): LinearLayout =
            LinearLayout(context).apply {
                val scaledText = 15f * delegate.messageTextScale
                val scaledTime = 11f * delegate.messageTextScale
                orientation = LinearLayout.VERTICAL
                minimumWidth = delegate.dp(74)
                minimumHeight = delegate.dp(46)
                background = if (item.mine) {
                    delegate.roundedDrawable(OUTGOING_BUBBLE, delegate.dp(18), OUTGOING_BUBBLE_STROKE)
                } else {
                    delegate.roundedDrawable(INCOMING_BUBBLE, delegate.dp(18), INCOMING_BUBBLE_STROKE)
                }
                setPadding(delegate.dp(16), delegate.dp(10), delegate.dp(14), delegate.dp(8))

                addView(TextView(context).apply {
                    text = item.body.wrapForChatBubble()
                    typeface = Typeface.DEFAULT
                    textSize = scaledText
                    setLineSpacing(delegate.dp(2).toFloat(), 1f)
                    setTextColor(if (item.mine) Color.WHITE else INCOMING_TEXT)
                    maxWidth = (context.resources.displayMetrics.widthPixels * 0.70f).toInt()
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

                addView(messageTimeView(item, overOutgoing = item.mine, textSize = scaledTime), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    topMargin = delegate.dp(2)
                })
            }

        private fun voiceBubble(item: ChatMessage): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = if (item.mine) {
                    delegate.roundedDrawable(OUTGOING_BUBBLE, delegate.dp(18), OUTGOING_BUBBLE_STROKE)
                } else {
                    delegate.roundedDrawable(INCOMING_BUBBLE, delegate.dp(18), INCOMING_BUBBLE_STROKE)
                }
                setPadding(delegate.dp(10), delegate.dp(10), delegate.dp(14), delegate.dp(8))
                val path = item.audioPath.orEmpty()
                // Outgoing: white button/bars on the accent bubble; incoming: accent on light bubble.
                val accent = if (item.mine) Color.WHITE else STRAWBERRY_RED
                val playButton = VoicePlayButtonView(
                    context,
                    circleColor = accent,
                    iconColor = if (item.mine) OUTGOING_BUBBLE else Color.WHITE
                ).apply {
                    contentDescription = delegate.tr("play")
                    isClickable = true
                    setOnClickListener { delegate.toggleAudioPlayback(item.audioPath) }
                }
                val waveform = VoiceWaveformView(
                    context,
                    activeColor = accent,
                    idleColor = (accent and 0x00FFFFFF) or 0x66000000
                ).apply {
                    peaks = VoiceWaveforms.cached(path)
                    if (peaks == null && path.isNotBlank()) {
                        VoiceWaveforms.load(path) { peaks = it }
                    }
                    onSeek = { fraction -> delegate.seekAudio(item.audioPath, fraction) }
                }
                val timeLabel = delegate.terminalText(delegate.audioDurationLabel(item.audioPath)).apply {
                    textSize = 13f
                    setTextColor(if (item.mine) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM)
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(playButton, LinearLayout.LayoutParams(delegate.dp(48), delegate.dp(48)).apply {
                        marginEnd = delegate.dp(10)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(waveform, LinearLayout.LayoutParams(delegate.dp(170), delegate.dp(30)))
                        addView(timeLabel, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = delegate.dp(2)
                        })
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }, LinearLayout.LayoutParams(
                    // Explicit WRAP_CONTENT: the default (MATCH_PARENT) inside this WRAP_CONTENT
                    // bubble is ignored when sizing it, so the bubble shrank to the time row.
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                delegate.bindVoicePlayback(path, VoicePlaybackViews(playButton, waveform, timeLabel))
                addView(messageTimeView(item, overOutgoing = item.mine), LinearLayout.LayoutParams(
                    // keep shared time style with message scaling
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    topMargin = delegate.dp(2)
                })
                setOnLongClickListener {
                    delegate.showMessageActions(item)
                    true
                }
            }

        private fun reactionBadge(item: ChatMessage): TextView? {
            val reaction = item.reaction ?: return null
            return delegate.terminalText(reaction).apply {
                textSize = 14f
                background = delegate.roundedDrawable(INPUT_SURFACE, delegate.dp(10), SOFT_PINK_STROKE)
                setPadding(delegate.dp(8), delegate.dp(3), delegate.dp(8), delegate.dp(3))
            }
        }

        private fun messageTimeView(item: ChatMessage, overOutgoing: Boolean, textSize: Float = 11f * delegate.messageTextScale): View {
            val tint = if (overOutgoing) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM
            val statusLabel = when (item.status) {
                MessageStatus.SENDING -> delegate.tr("status_sprouting")
                MessageStatus.DELIVERED -> delegate.tr("status_ripe")
                MessageStatus.READ -> delegate.tr("status_ripe")
                MessageStatus.FAILED -> delegate.tr("status_failed")
                null -> if (delegate.selectedRecipientId == null) delegate.tr("broadcast") else ""
            }
            if (!item.mine || item.status == null) {
                return TextView(context).apply {
                    text = listOf(statusLabel, delegate.displayTime(item))
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    this.textSize = textSize
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.END
                    setTextColor(tint)
                }
            }

            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = listOf(statusLabel, delegate.displayTime(item))
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    this.textSize = textSize
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.END
                    setTextColor(tint)
                })
                addView(CheckMarksView(context, item.status ?: MessageStatus.DELIVERED, tint), LinearLayout.LayoutParams(
                    delegate.dp(18),
                    delegate.dp(12)
                ).apply {
                    marginStart = delegate.dp(4)
                })
            }
        }
    }

private fun imageBounds(path: String): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
    } else {
        null
    }
}

private fun decodeSampledFile(path: String, targetWidth: Int, targetHeight: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return BitmapFactory.decodeFile(path)

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
    })
}

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
