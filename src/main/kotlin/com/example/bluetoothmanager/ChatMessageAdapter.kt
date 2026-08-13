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
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = when {
                    isServiceLog -> Gravity.CENTER_HORIZONTAL
                    item.mine -> Gravity.END
                    else -> Gravity.START
                }
                setPadding(delegate.dp(8), delegate.dp(5), delegate.dp(8), delegate.dp(5))
                if (shouldShowDateHeader(position)) {
                    addView(dateHeader(item), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = delegate.dp(8)
                    })
                }
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
                    val bitmap = BitmapFactory.decodeFile(item.imagePath)
                    val imageSize = delegate.calculateChatImageSize(bitmap)
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
                setPadding(delegate.dp(16), delegate.dp(10), delegate.dp(14), delegate.dp(8))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val action = delegate.terminalAction(
                        if (delegate.activePlayingPath == item.audioPath) delegate.tr("stop") else delegate.tr("play")
                    ).apply {
                        textSize = 12f
                        setTextColor(if (item.mine) Color.WHITE else INCOMING_TEXT)
                        background = delegate.roundedDrawable(
                            if (item.mine) 0x40FFFFFF else 0x22FF4359,
                            delegate.dp(10),
                            if (item.mine) 0x55FFFFFF else SOFT_PINK_STROKE
                        )
                        setPadding(delegate.dp(10), delegate.dp(6), delegate.dp(10), delegate.dp(6))
                        setOnClickListener {
                            delegate.toggleAudioPlayback(item.audioPath, this)
                        }
                    }
                    addView(action)
                    addView(delegate.terminalText(delegate.audioDurationLabel(item.audioPath)).apply {
                        textSize = 12f
                        setTextColor(if (item.mine) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM)
                        setPadding(delegate.dp(10), 0, 0, 0)
                    })
                })
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

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
