package com.example.bluetoothmanager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mesh.SendResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.text.DateFormat
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var meshService: MeshNetworkService? = null
    private var serviceBound = false
    private var selectedRecipientId: UUID? = null
    private var selectedRecipientLabel: String = "everyone"
    private var savedMessagesSelected = false
    private var normalizingNickname = false
    private var nicknameDialogShowing = false
    private var currentNickname = "@your name"
    private var chatListShownAtStartup = false
    private var darkThemeEnabled = false
    private var selectedLanguage = AppLanguage.EN
    private var appLockEnabled = false
    private var appLockPin = ""
    private var appLockTimeoutMinutes = 5
    private var lastUnlockAt = 0L
    private var appLockDialogVisible = false
    private var appWentBackgroundAt = 0L
    private var notificationEnabled = true
    private var notificationPreviewEnabled = true
    private var notificationBroadcastEnabled = true
    private var compactChatListEnabled = false
    private var messageTextScale = 1.0f
    private var cropChatImagesEnabled = true
    private var use24HourFormat = true
    private var shortDateFormatEnabled = false
    private var meshAggressiveMode = true
    private var meshMaxHops = 8
    private var isRecordingVoice = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt: Long = 0L
    private var activePlayer: MediaPlayer? = null
    private var activePlayingPath: String? = null
    private val audioDurationCache = mutableMapOf<String, String>()
    private var replyTarget: ChatMessage? = null
    private lateinit var replyBar: LinearLayout
    private lateinit var replyTextView: TextView
    private var sentCounter = 0
    private var deliveredCounter = 0
    private var readCounter = 0
    private var failedCounter = 0
    private var lastRelayInfo = "-"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf(
        ChatMessage("system", "offline mesh ready", false),
        ChatMessage("@relay", "waiting for nearby nodes", false)
    )
    private val meshMessages = messages.toMutableList()
    private val savedMessages = mutableListOf<ChatMessage>()
    private val peerMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private lateinit var chatAdapter: ChatMessageAdapter
    private val chatAdapterDelegate = object : ChatMessageAdapterDelegate {
        override val messageTextScale: Float
            get() = this@MainActivity.messageTextScale
        override val selectedRecipientId: UUID?
            get() = this@MainActivity.selectedRecipientId
        override val activePlayingPath: String?
            get() = this@MainActivity.activePlayingPath
        override val cropChatImages: Boolean
            get() = this@MainActivity.cropChatImagesEnabled

        override fun dp(value: Int): Int = this@MainActivity.dp(value)
        override fun tr(key: String): String = this@MainActivity.tr(key)
        override fun terminalText(value: String): TextView = this@MainActivity.terminalText(value)
        override fun terminalAction(value: String): TextView = this@MainActivity.terminalAction(value)
        override fun roundedDrawable(color: Int, cornerRadius: Int, strokeColor: Int?): GradientDrawable =
            this@MainActivity.roundedDrawable(color, cornerRadius, strokeColor)
        override fun calculateChatImageSize(bitmap: Bitmap?): Pair<Int, Int> =
            this@MainActivity.calculateChatImageSize(bitmap)
        override fun displayAuthor(message: ChatMessage): String = message.displayAuthor()
        override fun displayTime(message: ChatMessage): String = message.displayTime()
        override fun displayDate(message: ChatMessage): String = message.displayDate()
        override fun showImagePreview(imagePath: String) = this@MainActivity.showImagePreview(imagePath)
        override fun showMessageActions(message: ChatMessage) = this@MainActivity.showMessageActions(message)
        override fun attachReplySwipe(view: View, message: ChatMessage) =
            this@MainActivity.attachReplySwipe(view, message)
        override fun toggleAudioPlayback(path: String?, button: TextView) =
            this@MainActivity.toggleAudioPlayback(path, button)
        override fun audioDurationLabel(path: String?): String = this@MainActivity.audioDurationLabel(path)
    }

    private lateinit var usernameField: EditText
    private lateinit var statusGroup: LinearLayout
    private lateinit var counterView: TextView
    private lateinit var chatTitleView: TextView
    private lateinit var networkStatusView: TextView
    private lateinit var chatList: ListView
    private lateinit var transferStatusView: TextView
    private lateinit var messageInput: EditText
    private lateinit var actionButton: TextView
    private var backupProgressDialog: Dialog? = null
    private var backupProgressText: TextView? = null
    private var backupOperationRunning = false
    private var backupRestoreErrorMessage: String? = null
    private val pendingSendTimeouts = mutableMapOf<Long, Runnable>()
    private val pendingTextRetries = mutableMapOf<Long, Runnable>()
    private lateinit var chatStore: ChatStore
    private var messageTimeFormat: java.text.DateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var messageDateFormat: java.text.DateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    private val voiceStopRunnable = Runnable {
        if (isRecordingVoice) {
            stopVoiceRecordingAndSend(forceSend = true)
        }
    }
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isRecordingVoice) return
            val elapsed = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
            showImageProgress("${tr("recording_voice")} ${formatDuration(elapsed)}")
            mainHandler.postDelayed(this, 220L)
        }
    }

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            updateNetworkStatusFromLog(line)
            if (line.startsWith("image from ")) {
                addReceivedImage(line)
            } else if (line.startsWith("audio from ")) {
                addReceivedAudio(line)
            } else if (line.startsWith("image progress:")) {
                updateImageProgress(line.substringAfter("image progress:").trim())
            } else if (line.startsWith("message from ")) {
                addReceivedText(line)
            } else if (line.startsWith("message delivered:")) {
                deliveredCounter += 1
                updateMessageStatus(line.substringAfter(":").trim(), MessageStatus.DELIVERED)
            } else if (line.startsWith("message read:")) {
                readCounter += 1
                updateMessageStatus(line.substringAfter(":").trim(), MessageStatus.READ)
            } else if (line.startsWith("message failed:")) {
                failedCounter += 1
                updateMessageStatus(line.substringAfter(":").trim(), MessageStatus.FAILED)
            } else if (!line.isScanNoise()) {
                if (line.contains("relay", ignoreCase = true) || line.contains("wifi-direct", ignoreCase = true)) {
                    lastRelayInfo = line
                }
                addMessage("mesh", line, false)
            }
            refreshHeader()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            meshService = (service as MeshNetworkService.LocalBinder).service()
            meshService?.addLogListener(logListener)
            meshService?.configureNotificationSettings(
                enabled = notificationEnabled,
                showPreview = notificationPreviewEnabled,
                includeBroadcast = notificationBroadcastEnabled
            )
            meshService?.configureDiscovery(meshAggressiveMode)
            meshService?.configureMaxRelayHops(meshMaxHops)
            currentNickname = meshService?.getNickname()?.take(MAX_NICKNAME_LENGTH) ?: "@your name"
            usernameField.setText(contactDisplayName())
            meshService?.startNearbyDiscovery(silent = true)
            syncKnownPeers()
            refreshHeader()
            addMessage("system", "service connected", false)
            if (meshService?.hasStoredNickname() == false) {
                showInitialNicknameDialog()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService?.removeLogListener(logListener)
            meshService = null
            serviceBound = false
            addMessage("system", "mesh service disconnected", false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadUiSettings()
        applyDateTimeFormat()
        applyThemePalette()
        chatStore = ChatStore(this)
        loadStoredMessages()
        cleanupMediaCache()
        buildUi()
        if (!chatListShownAtStartup) {
            chatListShownAtStartup = true
            mainHandler.post { showChatList() }
        }

        if (hasRequiredPermissions()) {
            startAppAfterPermissions()
        } else {
            showPermissionIntro()
        }
    }

    override fun onResume() {
        super.onResume()
        ensureAppUnlocked()
        if (hasRequiredPermissions() && !serviceBound) {
            startAppAfterPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        appWentBackgroundAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        meshService?.removeLogListener(logListener)
        stopVoiceRecording(cleanupOnly = true)
        releaseAudioPlayer()
        pendingSendTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
        pendingSendTimeouts.clear()
        pendingTextRetries.values.forEach { mainHandler.removeCallbacks(it) }
        pendingTextRetries.clear()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = CREAM_BACKGROUND
        window.navigationBarColor = CREAM_BACKGROUND

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(44), dp(12), dp(8))
            setBackgroundColor(CREAM_BACKGROUND)
        }

        root.addView(buildHeader())

        chatAdapter = ChatMessageAdapter(this, messages, chatAdapterDelegate)
        chatList = ListView(this).apply {
            divider = null
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(CREAM_BACKGROUND)
            setSelector(android.R.color.transparent)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = false
            adapter = chatAdapter
        }
        root.addView(chatList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        transferStatusView = buildTransferStatus()
        root.addView(transferStatusView)

        replyBar = buildReplyBar()
        root.addView(replyBar)

        val inputBar = buildInputBar()
        root.addView(inputBar)
        applySafeArea(root, inputBar)
        setContentView(root)
    }

    private fun buildReplyBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(SERVICE_BUBBLE, dp(12), SERVICE_BUBBLE_STROKE)
            visibility = View.GONE
            replyTextView = terminalText("").apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                maxLines = 2
            }
            addView(replyTextView, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(terminalAction("X").apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { clearReplyTarget() }
            })
        }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))

            val topRow = FrameLayout(this@MainActivity)
            val leftGroup = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(terminalAction("< ${tr("back_to_chats")}").apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT)
                    background = roundedDrawable(INPUT_SURFACE, dp(10), SOFT_PINK_STROKE)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { showChatList() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                })
            }
            topRow.addView(leftGroup, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ))

            statusGroup = LinearLayout(this@MainActivity).apply { visibility = View.GONE }
            counterView = terminalText("0")
            statusGroup.addView(counterView)
            addView(topRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            ))

            usernameField = EditText(this@MainActivity).apply {
                setText(contactDisplayName())
                setSingleLine(true)
                typeface = Typeface.DEFAULT
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                setHintTextColor(BERRY_TEXT_DIM)
                setBackgroundColor(Color.TRANSPARENT)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                filters = arrayOf(InputFilter.LengthFilter(MAX_NICKNAME_LENGTH))
                minWidth = dp(130)
                setPadding(dp(2), dp(4), dp(4), dp(4))
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textCursorDrawable = cursorDrawable()
                }
            }
            addView(usernameField, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            chatTitleView = terminalText(selectedRecipientLabel.toDisplayTitle()).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                gravity = Gravity.START
                maxLines = 1
                setPadding(dp(2), dp(10), dp(2), 0)
                setOnClickListener { showCurrentChatProfile() }
            }
            addView(chatTitleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            networkStatusView = terminalText(tr("offline")).apply {
                textSize = 13f
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.START
                maxLines = 1
                setPadding(dp(2), dp(4), dp(2), 0)
            }
            addView(networkStatusView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun buildInputBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))

            messageInput = EditText(this@MainActivity).apply {
                hint = tr("hint_type_message")
                setSingleLine(false)
                maxLines = 4
                setHorizontallyScrolling(false)
                typeface = Typeface.DEFAULT
                textSize = 15f
                setTextColor(BERRY_TEXT)
                setHintTextColor(BERRY_TEXT_DIM)
                background = roundedDrawable(INPUT_SURFACE, dp(18), PINK_SHADOW_STROKE)
                elevation = dp(2).toFloat()
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateActionButton()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(messageInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })

            addView(terminalAction("\uD83D\uDCCE").apply {
                textSize = 18f
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(44)
                background = roundedDrawable(ACCENT_PINK, dp(16), OUTGOING_BUBBLE_STROKE)
                setPadding(dp(10), dp(8), dp(10), dp(10))
                setOnClickListener { openImagePicker() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            })

            actionButton = terminalAction(">").apply {
                background = circleDrawable(STRAWBERRY_RED)
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                minWidth = dp(46)
                minHeight = dp(46)
                setOnClickListener { handleInputAction() }
                var downX = 0f
                var canceledBySwipe = false
                setOnTouchListener { v, event ->
                    if (messageInput.text.toString().isNotBlank()) return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            canceledBySwipe = false
                            startVoiceRecording()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isRecordingVoice && event.x < downX - dp(64)) {
                                canceledBySwipe = true
                                stopVoiceRecording(cleanupOnly = true)
                                showImageProgress(tr("voice_canceled"))
                                mainHandler.postDelayed({ hideImageProgress() }, 900L)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.performClick()
                            if (isRecordingVoice && !canceledBySwipe) {
                                stopVoiceRecordingAndSend(forceSend = false)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            addView(actionButton)
            updateActionButton()
        }
    }

    private fun buildTransferStatus(): TextView =
        terminalText("").apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(BERRY_TEXT_DIM)
            setPadding(dp(12), dp(4), dp(12), dp(2))
            background = roundedDrawable(SERVICE_BUBBLE, dp(14), SERVICE_BUBBLE_STROKE)
        }

    private fun showInitialNicknameDialog() {
        val service = meshService ?: return
        if (nicknameDialogShowing) return
        nicknameDialogShowing = true

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }

        val input = EditText(this).apply {
            setText("@")
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            hint = "@nickname"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(MAX_NICKNAME_LENGTH))
            background = roundedDrawable(INPUT_SURFACE, dp(22), PINK_SHADOW_STROKE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        var normalizing = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (normalizing) return
                val current = s?.toString().orEmpty()
                if (current.startsWith("@") && current.length <= MAX_NICKNAME_LENGTH) return
                normalizing = true
                val normalized = "@${current.removePrefix("@")}".take(MAX_NICKNAME_LENGTH)
                input.setText(normalized)
                input.setSelection(normalized.length.coerceAtLeast(1))
                normalizing = false
            }
        })

        val saveButton = terminalAction(tr("start_chatting")).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(STRAWBERRY_RED, dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                val requested = input.text.toString().trim().prefixAt().take(MAX_NICKNAME_LENGTH)
                if (requested.length < 2) {
                    Toast.makeText(this@MainActivity, tr("choose_nickname"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val previous = currentNickname
                val display = service.setNickname(requested).take(MAX_NICKNAME_LENGTH)
                currentNickname = display
                usernameField.setText(display)
                usernameField.setSelection(usernameField.text.length)
                if (previous != display) {
                    renameLocalMessages(previous, display)
                }
                nicknameDialogShowing = false
                dialog.dismiss()
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = roundedDrawable(SOFT_PINK_PANEL, dp(28), SOFT_PINK_STROKE)

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.truskawka_logo)
                background = roundedDrawable(Color.WHITE, dp(18), PINK_SHADOW_STROKE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(72), dp(72)))

            addView(terminalText(tr("welcome_truskawka")).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(BERRY_TEXT)
                setPadding(0, dp(16), 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(terminalText(tr("choose_mesh_nickname")).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(BERRY_TEXT_DIM)
                setPadding(dp(6), 0, dp(6), dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(terminalText(tr("nickname_rules")).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(8), 0, dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val root = FrameLayout(this).apply {
            setPadding(dp(22), dp(44), dp(22), dp(28))
            setBackgroundColor(0x66FFB7C5)
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener { nicknameDialogShowing = false }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        input.post {
            input.requestFocus()
            input.setSelection(input.text.length)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun triggerMeshScan() {
        val count = meshService?.searchPeople() ?: 0
        counterView.text = count.toString()

        mainHandler.postDelayed({
            counterView.text = (meshService?.peerCount() ?: count).toString()
        }, 3_000)
    }

    private fun saveUsername() {
        keepNicknamePrefix()
        val raw = usernameField.text.toString().trim()
        val previous = currentNickname
        val requested = raw.ifBlank { "@jachimowicz" }.prefixAt().take(MAX_NICKNAME_LENGTH)
        val service = meshService
        if (service == null) {
            usernameField.setText(previous)
            usernameField.setSelection(usernameField.text.length)
            Toast.makeText(this, tr("nickname_change_online_only"), Toast.LENGTH_SHORT).show()
            return
        }

        val display = service.setNickname(requested).take(MAX_NICKNAME_LENGTH)
        if (usernameField.text.toString() != display) {
            usernameField.setText(display)
            usernameField.setSelection(usernameField.text.length)
        }
        if (requested != display) {
            Toast.makeText(this, tr("nickname_change_once_week"), Toast.LENGTH_SHORT).show()
        }
        if (previous != display) {
            currentNickname = display
            renameLocalMessages(previous, display)
        }
    }

    private fun handleInputAction() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            return
        }
        messageInput.text.clear()
        sendTextMessage(text)
    }

    private fun openImagePicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }
        startActivityForResult(intent, IMAGE_PICK_REQUEST)
    }

    private fun openProfileAvatarPicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }
        startActivityForResult(intent, PROFILE_AVATAR_PICK_REQUEST)
    }

    @Deprecated("Deprecated Android callback is enough for this minimal Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        takePersistableUriPermissionIfPossible(uri, data.flags)
        when (requestCode) {
            IMAGE_PICK_REQUEST -> showSelectedImageComposer(uri)
            PROFILE_AVATAR_PICK_REQUEST -> showAvatarCropper(uri)
            BACKUP_EXPORT_REQUEST -> exportEncryptedBackupTo(uri)
            BACKUP_IMPORT_REQUEST -> importEncryptedBackup(uri)
        }
    }

    private fun showAvatarCropper(uri: Uri) {
        val bitmap = decodeBitmapForAvatar(uri) ?: run {
            Toast.makeText(this, tr("could_not_read_image"), Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        lateinit var cropView: AvatarCropView
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(44), dp(16), dp(18))
            setBackgroundColor(CREAM_BACKGROUND)
            addView(terminalText(tr("adjust_profile_photo")).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })
            addView(terminalText(tr("adjust_profile_photo_desc")).apply {
                textSize = 13f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(6), 0, dp(12))
            })
            cropView = AvatarCropView(this@MainActivity, bitmap)
            addView(cropView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(terminalAction(tr("save")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(STRAWBERRY_RED, dp(18))
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener {
                    val cropped = cropView.crop(512)
                    AppProfileStore.setAvatarBitmap(this@MainActivity, cropped)
                    cropped.recycle()
                    runCatching { chatAdapter.notifyDataSetChanged() }
                    dialog.dismiss()
                    showOwnProfilePage()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            })
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun decodeBitmapForAvatar(uri: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }

    private fun showSelectedImageComposer(uri: Uri) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val captionInput = EditText(this).apply {
            hint = tr("add_caption")
            setSingleLine(false)
            maxLines = 3
            typeface = Typeface.DEFAULT
            textSize = 15f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            background = roundedDrawable(INPUT_SURFACE, dp(22), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CREAM_BACKGROUND)
            setPadding(dp(12), dp(18), dp(12), dp(12))
            addView(terminalText(tr("preview_image")).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })
            addView(ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(uri)
                background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(captionInput, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(8)
                })
                addView(terminalAction(tr("send")).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(STRAWBERRY_RED, dp(20))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener {
                        val caption = captionInput.text?.toString().orEmpty().trim()
                        dialog.dismiss()
                        sendSelectedImage(uri, caption)
                    }
                })
            })
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun sendSelectedImage(uri: Uri, caption: String) {
        if (!isRecipientWithinHopLimit(selectedRecipientId)) {
            addMessage("mesh", tr("mesh_hop_limit_reached"), false)
            return
        }
        val fileName = ImageTransferPreparer.queryDisplayName(contentResolver, uri)
        showImageProgress(tr("preparing_image"))

        thread(name = "image-compress-send") {
            val prepared = ImageTransferPreparer.prepareForTransfer(contentResolver, uri, fileName)
            if (prepared == null) {
                runOnUiThread {
                    hideImageProgress()
                    Toast.makeText(this, tr("could_not_read_image"), Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            if (prepared.bytes.size > MAX_IMAGE_BYTES) {
                runOnUiThread {
                    hideImageProgress()
                    Toast.makeText(this, tr("image_too_large"), Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            val localPath = ImageTransferPreparer.copyImageToLocalFile(filesDir, prepared.fileName, prepared.bytes).absolutePath
            val author = usernameField.text.toString().prefixAt()
            val localImageHolder = arrayOfNulls<ChatMessage>(1)

            runOnUiThread {
                if (savedMessagesSelected) {
                    saveLocalMessage(ChatMessage(author, "", true, localPath, status = MessageStatus.READ))
                    if (caption.isNotBlank()) {
                        saveLocalMessage(ChatMessage(author, caption, true, status = MessageStatus.READ))
                    }
                    hideImageProgress()
                } else {
                    localImageHolder[0] = addImageMessage(
                        author = author,
                        imagePath = localPath,
                        mine = true,
                        status = if (selectedRecipientId == null) null else MessageStatus.SENDING
                    )
                    showImageProgress(tr("sending_image"))
                }
            }

            if (savedMessagesSelected) return@thread

            sentCounter += 1
            val result = meshService?.sendImage(
                selectedRecipientId?.toString(),
                prepared.fileName,
                prepared.mimeType,
                prepared.bytes
            ) ?: SendResult.Failed("service offline")
            runOnUiThread {
                if (result is SendResult.Failed) {
                    localImageHolder[0]?.let {
                        it.status = null
                        persistChatMessageIdentity(it)
                    }
                    failedCounter += 1
                    hideImageProgress()
                    addMessage("mesh", result.toUiText(), false)
                } else {
                    localImageHolder[0]?.let {
                        it.status = if (selectedRecipientId == null) null else MessageStatus.DELIVERED
                        persistChatMessageIdentity(it)
                    }
                    if (caption.isNotBlank()) {
                        sendTextMessage(caption)
                    }
                    showImageProgress(tr("image_sent"))
                    mainHandler.postDelayed({ hideImageProgress() }, 1_200)
                }
            }
        }
    }

    private fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val payloadText = applyReplyToBody(text)
        val author = usernameField.text.toString().prefixAt()
        val targetId = selectedRecipientId
        if (!isRecipientWithinHopLimit(targetId)) {
            addMessage("mesh", tr("mesh_hop_limit_reached"), false)
            return
        }
        if (savedMessagesSelected) {
            saveLocalMessage(ChatMessage(author, payloadText, true, status = MessageStatus.READ))
            return
        }
        val localMessage = addMessage(
            author = author,
            body = payloadText,
            mine = true,
            status = if (targetId == null) null else MessageStatus.SENDING
        )
        if (targetId != null) {
            scheduleSendTimeout(localMessage)
        }
        sentCounter += 1
        val result = attemptTextSend(targetId, payloadText)
        when (result) {
            is SendResult.Sent -> {
                if (targetId != null) {
                    localMessage.messageId = result.messageId
                    persistChatMessageIdentity(localMessage)
                    clearTextRetry(localMessage.localId)
                }
            }
            is SendResult.Failed -> {
                if (targetId != null) {
                    scheduleTextRetry(localMessage, targetId, payloadText)
                } else {
                    localMessage.status = MessageStatus.FAILED
                    persistChatMessageIdentity(localMessage)
                    clearSendTimeout(localMessage.localId)
                    failedCounter += 1
                    addMessage("mesh", result.toUiText(), false)
                }
            }
            is SendResult.Queued -> {
                if (targetId != null) {
                    scheduleTextRetry(localMessage, targetId, payloadText)
                }
            }
        }
    }

    private fun startVoiceRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
            Toast.makeText(this, tr("mic_permission_needed"), Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecordingVoice) return
        val file = File(filesDir, "voice_notes").apply { mkdirs() }
            .resolve("voice_${System.currentTimeMillis()}.m4a")
        val nextRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            nextRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(22_050)
                setMaxDuration(VOICE_MAX_DURATION_MS.toInt())
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.onFailure {
            nextRecorder.release()
            Toast.makeText(this, tr("voice_record_failed"), Toast.LENGTH_SHORT).show()
            return
        }
        recorder = nextRecorder
        recordingFile = file
        recordingStartedAt = System.currentTimeMillis()
        isRecordingVoice = true
        updateActionButton()
        showImageProgress("${tr("recording_voice")} 00:00")
        mainHandler.post(recordingTicker)
        mainHandler.postDelayed(voiceStopRunnable, VOICE_MAX_DURATION_MS)
    }

    private fun stopVoiceRecordingAndSend(forceSend: Boolean) {
        val file = recordingFile
        val duration = System.currentTimeMillis() - recordingStartedAt
        stopVoiceRecording(cleanupOnly = false)
        if (file == null || !file.exists() || file.length() <= 0L) return
        if (!forceSend && duration < VOICE_MIN_DURATION_MS) {
            file.delete()
            Toast.makeText(this, tr("voice_too_short"), Toast.LENGTH_SHORT).show()
            return
        }
        if (file.length() > MAX_VOICE_BYTES) {
            addMessage("mesh", tr("voice_too_large"), false)
            file.delete()
            return
        }
        sendPreparedVoice(file)
    }

    private fun stopVoiceRecording(cleanupOnly: Boolean) {
        mainHandler.removeCallbacks(voiceStopRunnable)
        mainHandler.removeCallbacks(recordingTicker)
        val pendingFile = recordingFile
        val current = recorder
        recorder = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.reset() }
            current.release()
        }
        isRecordingVoice = false
        updateActionButton()
        if (cleanupOnly) {
            recordingFile = null
            recordingStartedAt = 0L
            if (pendingFile != null && pendingFile.exists()) {
                runCatching { pendingFile.delete() }
            }
            hideImageProgress()
            return
        }
        recordingStartedAt = 0L
        hideImageProgress()
    }

    private fun isRecipientWithinHopLimit(targetId: UUID?): Boolean {
        if (targetId == null || savedMessagesSelected) return true
        val peer = meshService?.knownPeers().orEmpty().firstOrNull { it.nodeId == targetId } ?: return true
        return peer.hopCount <= meshMaxHops
    }

    private fun sendPreparedVoice(file: File) {
        val targetId = selectedRecipientId
        if (!isRecipientWithinHopLimit(targetId)) {
            addMessage("mesh", tr("mesh_hop_limit_reached"), false)
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            addMessage("mesh", tr("voice_read_failed"), false)
            return
        }
        val author = usernameField.text.toString().prefixAt()
        val timestamp = System.currentTimeMillis()
        if (savedMessagesSelected) {
            addAudioMessage(
                author = author,
                audioPath = file.absolutePath,
                mine = true,
                timestamp = timestamp,
                status = MessageStatus.READ
            )
            return
        }
        targetId?.let { meshService?.prepareChatWith(it.toString()) }
        val localMessage = addAudioMessage(
            author = author,
            audioPath = file.absolutePath,
            mine = true,
            timestamp = timestamp,
            status = if (targetId == null) null else MessageStatus.SENDING
        )
        showImageProgress(tr("sending_voice"))
        sentCounter += 1
        thread(name = "voice-send") {
            var result: SendResult = SendResult.Failed("send not attempted")
            val attempts = if (targetId == null) 1 else VOICE_SEND_ATTEMPTS
            for (attempt in 0 until attempts) {
                if (targetId != null) {
                    meshService?.prepareChatWith(targetId.toString())
                }
                result = meshService?.sendImage(
                    targetId?.toString(),
                    file.name,
                    "audio/mp4",
                    bytes
                ) ?: SendResult.Failed("service offline")
                if (result !is SendResult.Failed) {
                    break
                }
                if (targetId == null || !result.error.contains("session", ignoreCase = true)) {
                    break
                }
                Thread.sleep(VOICE_SEND_RETRY_DELAY_MS * (attempt + 1L))
            }
            runOnUiThread {
                if (result is SendResult.Failed) {
                    localMessage.status = null
                    persistChatMessageIdentity(localMessage)
                    failedCounter += 1
                    addMessage("mesh", result.toUiText(), false)
                } else if (result is SendResult.Sent && targetId != null) {
                    localMessage.messageId = result.messageId
                    persistChatMessageIdentity(localMessage)
                }
                showImageProgress(if (result is SendResult.Failed) tr("voice_send_failed") else tr("voice_sent"))
                mainHandler.postDelayed({ hideImageProgress() }, 1_200)
            }
        }
    }

    private fun updateActionButton() {
        actionButton.setCompoundDrawables(null, null, null, null)
        when {
            isRecordingVoice -> {
                actionButton.text = tr("recording_short")
                actionButton.background = circleDrawable(STRAWBERRY_RED)
            }
            messageInput.text.toString().isBlank() -> {
                actionButton.text = ""
                actionButton.background = microphoneButtonDrawable()
            }
            else -> {
                actionButton.text = ">"
                actionButton.background = circleDrawable(STRAWBERRY_RED)
            }
        }
        actionButton.textSize = if (messageInput.text.toString().isBlank() || isRecordingVoice) 16f else 22f
    }

    private fun showChatList() {
        syncKnownPeers()
        chatStore.ensureBaseChats()

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(44), dp(16), dp(16))
            setBackgroundColor(CREAM_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(terminalText(tr("chats")).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(header)

        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(12), 0, 0)
        }
        val listContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listScroll.addView(listContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(listScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(buildPageBottomNav(
            selected = PageTab.CONTACTS,
            onNewContacts = {
                dialog.dismiss()
                showMeshPanel(scanFirst = true)
            },
            onContacts = { },
            onProfile = {
                dialog.dismiss()
                showOwnProfilePage()
            },
            onSettings = {
                dialog.dismiss()
                showSettingsPage()
            }
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })

        val summaries = chatStore.listChats()
            .filter { it.kind != ChatKind.PEER.name || it.peerId != null }
        val hasPrivateChats = summaries.any {
            it.kind == ChatKind.PEER.name && it.messageCount > 0
        }
        val uiPrefs = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val quickStartHidden = uiPrefs.getBoolean(UI_SETTINGS_QUICK_START_HIDDEN, false)
        if (!quickStartHidden) {
            listContent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(SERVICE_BUBBLE, dp(12), SERVICE_BUBBLE_STROKE)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(terminalText(tr("quick_start_title")).apply {
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                })
                addView(terminalText("1. ${tr("quick_start_step_1")}").apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(6), 0, 0)
                })
                addView(terminalText("2. ${tr("quick_start_step_2")}").apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(terminalText("3. ${tr("quick_start_step_3")}").apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(terminalAction(tr("got_it")).apply {
                    textSize = 13f
                    setTextColor(BERRY_TEXT_DIM)
                    gravity = Gravity.CENTER
                    background = roundedDrawable(Color.TRANSPARENT, dp(10), SOFT_PINK_STROKE)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        uiPrefs.edit().putBoolean(UI_SETTINGS_QUICK_START_HIDDEN, true).apply()
                        dialog.dismiss()
                        showChatList()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                    gravity = Gravity.END
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            })
        }
        if (!hasPrivateChats) {
            listContent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = roundedDrawable(INPUT_SURFACE, dp(14), SOFT_PINK_STROKE)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                addView(terminalText(tr("empty_chats_title")).apply {
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                    gravity = Gravity.CENTER
                })
                addView(terminalText(tr("empty_chats_desc")).apply {
                    textSize = 13f
                    setTextColor(BERRY_TEXT_DIM)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(14))
                })
                addView(terminalAction(tr("find_people_nearby")).apply {
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(STRAWBERRY_RED, dp(18))
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener {
                        dialog.dismiss()
                        showMeshPanel(scanFirst = true)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            })
        }
        if (summaries.isEmpty()) {
            listContent.addView(terminalText(tr("no_chats_yet")).apply {
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(24), 0, 0)
            })
        } else {
            summaries.forEach { summary ->
                listContent.addView(chatSummaryRow(
                    summary = summary,
                    onClick = {
                        val peerId = summary.peerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        selectChat(summary.chatKey, summary.title, peerId)
                        dialog.dismiss()
                    },
                    onLongClick = {
                        showChatRowActions(summary) {
                            dialog.dismiss()
                            showChatList()
                        }
                    }
                ), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                })
            }
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun chatListBottomItem(title: String, selected: Boolean, onClick: () -> Unit): TextView =
        terminalText(title).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.WHITE else BERRY_TEXT_DIM)
            background = roundedDrawable(if (selected) STRAWBERRY_RED else Color.TRANSPARENT, dp(12))
            setOnClickListener { onClick() }
        }

    private fun buildPageBottomNav(
        selected: PageTab,
        onNewContacts: () -> Unit,
        onContacts: () -> Unit,
        onProfile: () -> Unit,
        onSettings: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = roundedDrawable(INPUT_SURFACE, dp(14), SOFT_PINK_STROKE)

            addView(chatListBottomItem(tr("nav_nearby"), selected == PageTab.NEW_CONTACTS, onNewContacts), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(4)
            })
            addView(chatListBottomItem(tr("chats"), selected == PageTab.CONTACTS, onContacts), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            addView(chatListBottomItem(tr("profile"), selected == PageTab.PROFILE, onProfile), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            addView(chatListBottomItem(tr("settings"), selected == PageTab.SETTINGS, onSettings), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
            })
        }

    private fun chatSummaryRow(
        summary: ChatSummary,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ): LinearLayout {
        val compact = compactChatListEnabled
        val presence = summaryPresence(summary)
        val displayTitle = summaryDisplayTitle(summary)
        val preview = when {
            summary.lastImagePath != null -> tr("photo")
            summary.lastAudioPath != null -> tr("voice_message")
            summary.lastBody.isNotBlank() -> summary.lastBody
            summary.kind == ChatKind.SAVED.name -> tr("private_notes")
            summary.kind == ChatKind.EVERYONE.name -> tr("nearby_public_mesh")
            summary.verified -> tr("verified_contact")
            else -> tr("tap_open_chat")
        }
        val selected = summary.chatKey == currentChatKey()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), if (compact) dp(9) else dp(12), dp(14), if (compact) dp(9) else dp(12))
            background = roundedDrawable(
                if (selected) ACCENT_PINK else INPUT_SURFACE,
                dp(14),
                if (selected) OUTGOING_BUBBLE_STROKE else SOFT_PINK_STROKE
            )
            val avatarView = if (summary.kind == ChatKind.SAVED.name) {
                SavedNotesAvatarView(this@MainActivity)
            } else {
                AvatarView(
                    context = this@MainActivity,
                    label = displayTitle,
                    imagePath = null,
                    accentColor = avatarAccent(displayTitle),
                    verified = summary.verified
                )
            }
            addView(avatarView, LinearLayout.LayoutParams(if (compact) dp(34) else dp(40), if (compact) dp(34) else dp(40)).apply {
                marginEnd = dp(12)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(terminalText(displayTitle).apply {
                    textSize = if (compact) 15f else 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                    maxLines = 1
                })
                if (summary.pinned) {
                    addView(terminalText(tr("pinned")).apply {
                        textSize = if (compact) 10f else 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(STRAWBERRY_RED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                addView(terminalText(preview).apply {
                    textSize = if (compact) 11f else 12f
                    setTextColor(BERRY_TEXT_DIM)
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(terminalText(presence.first).apply {
                textSize = if (compact) 10f else 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (presence.second) LEAF_GREEN else BERRY_TEXT_DIM)
                background = roundedDrawable(if (presence.second) 0x1F14947A else SERVICE_BUBBLE, dp(10), SERVICE_BUBBLE_STROKE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = if (summary.unreadCount > 0) dp(8) else 0
            })
            if (summary.unreadCount > 0) {
                addView(View(this@MainActivity).apply {
                    background = circleDrawable(STRAWBERRY_RED)
                }, LinearLayout.LayoutParams(if (compact) dp(8) else dp(10), if (compact) dp(8) else dp(10)))
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
    }

    private fun nearbyCountCell(label: String, count: String, accent: Int): TextView =
        terminalText("$label\n$count").apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(accent)
            minHeight = dp(58)
            background = roundedDrawable(SERVICE_BUBBLE, dp(14), SERVICE_BUBBLE_STROKE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

    private fun avatarAccent(label: String): Int {
        val palette = intArrayOf(
            STRAWBERRY_RED,
            LEAF_GREEN,
            0xFF5B7CFA.toInt(),
            0xFFB85CC9.toInt(),
            0xFF2F9DA6.toInt(),
            0xFFE07A3F.toInt()
        )
        val index = kotlin.math.abs(label.hashCode()) % palette.size
        return palette[index]
    }

    private fun showMeshPanel(scanFirst: Boolean) {
        val count = if (scanFirst) {
            meshService?.searchPeople() ?: 0
        } else {
            meshService?.peerCount() ?: 0
        }
        syncKnownPeers()
        counterView.text = count.toString()

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(SOFT_PINK_PANEL)
        }

        val nearbyTitle = terminalText(tr("nearby_people")).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BERRY_TEXT)
        }
        val transportStatus = terminalText(meshService?.meshTransportStatus() ?: tr("mesh_starting")).apply {
            textSize = 12f
            setTextColor(BERRY_TEXT_DIM)
            setPadding(0, 0, 0, dp(10))
        }
        lateinit var directCountView: TextView
        lateinit var relayCountView: TextView
        val nearbyStatusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                directCountView = nearbyCountCell(tr("nearby_direct_count"), "0", LEAF_GREEN)
                relayCountView = nearbyCountCell(tr("nearby_relay_count"), "0", STRAWBERRY_RED)
                addView(directCountView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
                addView(relayCountView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                })
            })
            addView(terminalText(tr("nearby_status_desc")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(10), 0, 0)
            })
        }
        val peopleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchInput = EditText(this).apply {
            hint = tr("search_in_patch")
            setSingleLine(true)
            typeface = Typeface.DEFAULT
            textSize = 14f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            background = roundedDrawable(INPUT_SURFACE, dp(20), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        fun refreshPeopleRows() {
            val peerCount = meshService?.peerCount() ?: 0
            transportStatus.text = meshService?.meshTransportStatus() ?: tr("mesh_starting")
            counterView.text = peerCount.toString()
            peopleContainer.removeAllViews()

            val query = searchInput.text?.toString().orEmpty().trim().lowercase(Locale.getDefault())
            val existingPeerIds = chatStore.listChats()
                .filter { it.kind == ChatKind.PEER.name && it.messageCount > 0 }
                .mapNotNull { it.peerId?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() } }
                .toSet()
            val peers = meshService?.knownPeers().orEmpty().filter { peer ->
                val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                (query.isBlank() || label.lowercase(Locale.getDefault()).contains(query)) &&
                    !existingPeerIds.contains(peer.nodeId)
            }
            val directPeers = peers.filter { it.isDirect || it.hopCount <= 1 }
            val meshPeers = peers.filterNot { it.isDirect || it.hopCount <= 1 }
                .filter { it.hopCount <= meshMaxHops }
            directCountView.text = "${tr("nearby_direct_count")}\n${directPeers.size}"
            relayCountView.text = "${tr("nearby_relay_count")}\n${meshPeers.size}"

            peopleContainer.addView(terminalText("${tr("direct_in_range")} (${directPeers.size})").apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                setPadding(0, 0, 0, dp(8))
            })
            if (directPeers.isEmpty()) {
                peopleContainer.addView(terminalText(if (query.isBlank()) tr("no_direct_peers") else tr("no_direct_matches")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, 0, 0, dp(8))
                })
            } else {
                directPeers.forEach { peer ->
                    val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                    val stored = chatStore.getPeer(peer.nodeId.toString())
                    val verified = stored?.verified == true
                    val subtitle = listOfNotNull(
                        tr("direct_ble"),
                        if (verified) tr("verified") else null,
                        stored?.let { formatPeerPresence(it.lastSeen) } ?: tr("online_now")
                    ).joinToString(" / ")
                    peopleContainer.addView(patchPeerRow(label, subtitle, !savedMessagesSelected && selectedRecipientId == peer.nodeId) {
                        rememberPeer(peer.nodeId, label)
                        meshService?.prepareChatWith(peer.nodeId.toString())
                        selectChat(peerChatKey(peer.nodeId), label, peer.nodeId)
                        dialog.dismiss()
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) })
                }
            }

            peopleContainer.addView(terminalText("${tr("reachable_via_hops")} (${meshPeers.size})").apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                setPadding(0, dp(6), 0, dp(8))
            })
            if (meshPeers.isEmpty()) {
                peopleContainer.addView(terminalText(if (query.isBlank()) tr("no_multihop_routes") else tr("no_hop_matches")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                })
            } else {
                meshPeers.forEach { peer ->
                    val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                    val hops = peer.hopCount.coerceAtLeast(2)
                    val subtitle = "${tr("via")} $hops ${tr("hops")} / ${formatPeerPresence(peer.lastSeen)}"
                    peopleContainer.addView(patchPeerRow(label, subtitle, !savedMessagesSelected && selectedRecipientId == peer.nodeId) {
                        rememberPeer(peer.nodeId, label)
                        meshService?.prepareChatWith(peer.nodeId.toString())
                        selectChat(peerChatKey(peer.nodeId), label, peer.nodeId)
                        dialog.dismiss()
                    })
                }
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nearbyTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(header)
        root.addView(transportStatus)
        root.addView(nearbyStatusPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        })

        val contentScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            })
            addView(peopleContainer)
        }
        contentScroll.addView(contentContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(contentScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPeopleRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(buildPageBottomNav(
            selected = PageTab.NEW_CONTACTS,
            onNewContacts = { },
            onContacts = {
                dialog.dismiss()
                showChatList()
            },
            onProfile = {
                dialog.dismiss()
                showOwnProfilePage()
            },
            onSettings = {
                dialog.dismiss()
                showSettingsPage()
            }
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })
        refreshPeopleRows()

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.END
            }
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        val panelRefresh = object : Runnable {
            override fun run() {
                if (dialog.isShowing) {
                    syncKnownPeers()
                    refreshPeopleRows()
                    mainHandler.postDelayed(this, 1_500)
                } else {
                    counterView.text = (meshService?.peerCount() ?: count).toString()
                }
            }
        }
        mainHandler.postDelayed(panelRefresh, 1_200)
    }

    private fun patchPeerRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        return networkActionRow(title, subtitle, selected, onClick).apply {
            background = roundedDrawable(
                if (selected) 0x22FF4359 else INPUT_SURFACE,
                dp(18),
                if (selected) STRAWBERRY_RED else SOFT_PINK_STROKE
            )
        }
    }

    private fun showGrowScreen() {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = roundedDrawable(SOFT_PINK_PANEL, dp(24), SOFT_PINK_STROKE)
            addView(terminalText(tr("grow")).apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })
            addView(terminalText(tr("grow_desc")).apply {
                textSize = 13f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(8), 0, dp(14))
            })
            addView(patchPeerRow(tr("start_discovery"), tr("scan_nearby_ble"), false) {
                triggerMeshScan()
                Toast.makeText(this@MainActivity, tr("searching_nearby_patch"), Toast.LENGTH_SHORT).show()
            })
            addView(patchPeerRow(tr("open_patch"), tr("show_direct_hop"), false) {
                dialog.dismiss()
                showMeshPanel(scanFirst = true)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun networkActionRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(
                if (selected) ACCENT_PINK else INPUT_SURFACE,
                dp(18),
                if (selected) STRAWBERRY_RED else SOFT_PINK_STROKE
            )
            elevation = dp(1).toFloat()
            addView(terminalText(if (selected) ">" else " ").apply {
                textSize = 16f
                setTextColor(STRAWBERRY_RED)
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(terminalText(title).apply {
                    textSize = 15f
                    setTextColor(BERRY_TEXT)
                })
                addView(terminalText(subtitle).apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }
    }

    private fun showImagePreview(imagePath: String) {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(18))
            setBackgroundColor(CREAM_BACKGROUND)
        }
        root.addView(ZoomableImageView(this) { dialog.dismiss() }.apply {
            setImageBitmap(BitmapFactory.decodeFile(imagePath))
            setBackgroundColor(Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun addMessage(
        author: String,
        body: String,
        mine: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        status: MessageStatus? = null,
        chatKey: String = currentChatKey()
    ): ChatMessage {
        val message = ChatMessage(author, body, mine, timestamp = timestamp, status = status)
        val isServiceMessage = author == "system" || author == "mesh"
        val actualChatKey = if (isServiceMessage && savedMessagesSelected) CHAT_EVERYONE else chatKey
        val buffer = messagesForChat(actualChatKey)
        if (buffer.isNotEmpty() && buffer.size == 1 && buffer.first().author == "system") {
            buffer.clear()
        }
        buffer += message
        persistChatMessage(actualChatKey, message)
        if (currentChatKey() == actualChatKey && !savedMessagesSelected) {
            if (messages.size == 1 && messages.firstOrNull()?.author == "system") {
                messages.clear()
            }
            messages += message
            chatList.post { chatList.setSelection(chatAdapter.count - 1) }
        }
        chatAdapter.notifyDataSetChanged()
        return message
    }

    private fun addImageMessage(
        author: String,
        imagePath: String,
        mine: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        chatKey: String = currentChatKey(),
        status: MessageStatus? = null
    ): ChatMessage {
        val message = ChatMessage(author, "", mine, imagePath = imagePath, timestamp = timestamp, status = status)
        val actualChatKey = if (author == "system" || author == "mesh") CHAT_EVERYONE else chatKey
        val buffer = messagesForChat(actualChatKey)
        if (buffer.isNotEmpty() && buffer.size == 1 && buffer.first().author == "system") {
            buffer.clear()
        }
        buffer += message
        persistChatMessage(actualChatKey, message)
        if (currentChatKey() == actualChatKey && !savedMessagesSelected) {
            if (messages.size == 1 && messages.firstOrNull()?.author == "system") {
                messages.clear()
            }
            messages += message
            chatList.post { chatList.setSelection(chatAdapter.count - 1) }
        }
        chatAdapter.notifyDataSetChanged()
        return message
    }

    private fun addAudioMessage(
        author: String,
        audioPath: String,
        mine: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        chatKey: String = currentChatKey(),
        status: MessageStatus? = null
    ): ChatMessage {
        val message = ChatMessage(
            author = author,
            body = tr("voice_message"),
            mine = mine,
            audioPath = audioPath,
            timestamp = timestamp,
            status = status
        )
        val actualChatKey = if (author == "system" || author == "mesh") CHAT_EVERYONE else chatKey
        val buffer = messagesForChat(actualChatKey)
        if (buffer.isNotEmpty() && buffer.size == 1 && buffer.first().author == "system") {
            buffer.clear()
        }
        buffer += message
        persistChatMessage(actualChatKey, message)
        if (currentChatKey() == actualChatKey && !savedMessagesSelected) {
            if (messages.size == 1 && messages.firstOrNull()?.author == "system") {
                messages.clear()
            }
            messages += message
            chatList.post { chatList.setSelection(chatAdapter.count - 1) }
        }
        chatAdapter.notifyDataSetChanged()
        return message
    }

    private fun addReceivedText(line: String) {
        val meta = line.substringAfter("message from ", "")
        val incoming = parseIncomingMeta(meta)
        val sender = parseIncomingSender(incoming.senderRaw)
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestamp = incoming.timestamp
        val body = incoming.payload
        if (body.startsWith(CONTROL_PREFIX)) {
            handleIncomingControl(sender, body)
            return
        }
        sender.nodeId?.let { rememberPeer(it, author) }
        val chatKey = incomingChatKey(sender)
        if (chatKey != currentChatKey()) chatStore.incrementUnread(chatKey)
        addMessage(author, body, mine = false, timestamp = timestamp, chatKey = chatKey)
    }

    private fun addReceivedImage(line: String) {
        val meta = line.substringAfter("image from ", "")
        val incoming = parseIncomingMeta(meta)
        val sender = parseIncomingSender(incoming.senderRaw)
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestamp = incoming.timestamp
        val payload = incoming.payload
        val imagePath = payload.substringBefore("|")
        if (imagePath.isBlank()) return
        if (!File(imagePath).exists()) {
            addMessage("mesh", tr("incoming_image_unavailable"), false)
            return
        }
        sender.nodeId?.let { rememberPeer(it, author) }
        val chatKey = incomingChatKey(sender)
        if (chatKey != currentChatKey()) chatStore.incrementUnread(chatKey)
        addImageMessage(author, imagePath, mine = false, timestamp = timestamp, chatKey = chatKey)
        showImageProgress(tr("image_received"))
        mainHandler.postDelayed({ hideImageProgress() }, 1_200)
    }

    private fun addReceivedAudio(line: String) {
        val meta = line.substringAfter("audio from ", "")
        val incoming = parseIncomingMeta(meta)
        val sender = parseIncomingSender(incoming.senderRaw)
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestamp = incoming.timestamp
        val payload = incoming.payload
        val audioPath = payload.substringBefore("|")
        if (audioPath.isBlank()) return
        if (!File(audioPath).exists()) {
            addMessage("mesh", tr("incoming_audio_unavailable"), false)
            return
        }
        sender.nodeId?.let { rememberPeer(it, author) }
        val chatKey = incomingChatKey(sender)
        if (chatKey != currentChatKey()) chatStore.incrementUnread(chatKey)
        addAudioMessage(author, audioPath, mine = false, timestamp = timestamp, chatKey = chatKey)
        showImageProgress(tr("voice_received"))
        mainHandler.postDelayed({ hideImageProgress() }, 1_200)
    }

    private fun updateImageProgress(value: String) {
        val sent = value.substringBefore("/").toIntOrNull()
        val total = value.substringAfter("/", "").toIntOrNull()
        if (sent == null || total == null || total <= 0) {
            showImageProgress(tr("sending_image"))
            return
        }
        val percent = ((sent * 100f) / total).toInt().coerceIn(0, 100)
        showImageProgress("${tr("sending_image")} $percent% ($sent/$total)")
    }

    private fun updateMessageStatus(messageIdText: String, status: MessageStatus) {
        val messageId = runCatching { UUID.fromString(messageIdText) }.getOrNull() ?: return
        var changed = false
        (listOf(meshMessages, savedMessages, messages) + peerMessages.values)
            .forEach { buffer ->
                buffer.filter { it.mine && it.messageId == messageId }
                    .forEach { message ->
                        if (shouldApplyMessageStatus(message.status, status)) {
                            message.status = status
                            clearSendTimeout(message.localId)
                            clearTextRetry(message.localId)
                            changed = true
                        }
                    }
                }
        if (changed) {
            chatStore.updateStatusByMeshMessageId(messageId.toString(), status.name)
            chatAdapter.notifyDataSetChanged()
        }
    }

    private fun shouldApplyMessageStatus(current: MessageStatus?, next: MessageStatus): Boolean {
        if (current == MessageStatus.READ) return false
        if (next == MessageStatus.FAILED && current == MessageStatus.DELIVERED) return false
        return current != next
    }

    private fun attemptTextSend(targetId: UUID?, payload: String): SendResult {
        if (targetId != null) {
            meshService?.startNearbyDiscovery(silent = true)
            meshService?.prepareChatWith(targetId.toString())
        }
        return if (targetId == null) {
            meshService?.broadcastMessage(payload)
        } else {
            meshService?.sendMessage(targetId.toString(), payload)
        } ?: SendResult.Failed("service offline")
    }

    private fun scheduleTextRetry(message: ChatMessage, targetId: UUID, payload: String) {
        val localId = message.localId
        if (localId <= 0L) return
        clearTextRetry(localId)
        var attempts = 0
        lateinit var task: Runnable
        task = Runnable {
            if (message.status != MessageStatus.SENDING) {
                clearTextRetry(localId)
                return@Runnable
            }
            attempts += 1
            val result = attemptTextSend(targetId, payload)
            when (result) {
                is SendResult.Sent -> {
                    message.messageId = result.messageId
                    persistChatMessageIdentity(message)
                    clearTextRetry(localId)
                }
                is SendResult.Failed, is SendResult.Queued -> {
                    if (attempts >= MESSAGE_RETRY_ATTEMPTS) {
                        clearTextRetry(localId)
                    } else {
                        mainHandler.postDelayed(task, MESSAGE_RETRY_INTERVAL_MS)
                    }
                }
            }
        }
        pendingTextRetries[localId] = task
        mainHandler.postDelayed(task, MESSAGE_RETRY_INTERVAL_MS)
    }

    private fun clearTextRetry(localId: Long) {
        if (localId <= 0L) return
        pendingTextRetries.remove(localId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun scheduleSendTimeout(message: ChatMessage) {
        val localId = message.localId
        if (localId <= 0L) return
        clearSendTimeout(localId)
        val task = Runnable {
            pendingSendTimeouts.remove(localId)
            if (message.status != MessageStatus.SENDING) return@Runnable
            message.status = MessageStatus.FAILED
            persistChatMessageIdentity(message)
            clearTextRetry(localId)
            failedCounter += 1
            chatAdapter.notifyDataSetChanged()
        }
        pendingSendTimeouts[localId] = task
        mainHandler.postDelayed(task, MESSAGE_SEND_TIMEOUT_MS)
    }

    private fun clearSendTimeout(localId: Long) {
        if (localId <= 0L) return
        pendingSendTimeouts.remove(localId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun saveLocalMessage(message: ChatMessage) {
        savedMessages += message
        persistChatMessage(CHAT_SAVED, message)
        if (messages.size == 1 && messages.firstOrNull()?.body == tr("saved_empty")) {
            messages.clear()
        }
        messages += message
        chatAdapter.notifyDataSetChanged()
        chatList.post { chatList.setSelection(chatAdapter.count - 1) }
    }

    private fun showSavedMessages() {
        messages.clear()
        if (savedMessages.isEmpty()) {
            messages += ChatMessage("system", tr("saved_empty"), false)
        } else {
            messages += savedMessages
        }
        chatAdapter.notifyDataSetChanged()
    }

    private fun showMeshMessages() {
        showChatMessages(CHAT_EVERYONE)
    }

    private fun selectChat(chatKey: String, title: String, peerId: UUID?) {
        savedMessagesSelected = chatKey == CHAT_SAVED
        selectedRecipientId = peerId
        clearReplyTarget()
        selectedRecipientLabel = when {
            chatKey == CHAT_SAVED -> "saved"
            chatKey == CHAT_EVERYONE -> "everyone"
            else -> title
        }
        if (savedMessagesSelected) {
            showSavedMessages()
        } else {
            showChatMessages(chatKey)
        }
        chatStore.clearUnread(chatKey)
        updateRecipientHint()
        updateChatTitle()
    }

    private fun showChatMessages(chatKey: String) {
        messages.clear()
        messages += messagesForChat(chatKey)
        if (messages.isEmpty()) {
            messages += ChatMessage("system", if (chatKey == CHAT_EVERYONE) tr("broadcast_empty") else tr("chat_empty"), false)
        }
        chatAdapter.notifyDataSetChanged()
        chatList.post { chatList.setSelection(chatAdapter.count - 1) }
    }

    private fun currentChatKey(): String = when {
        savedMessagesSelected -> CHAT_SAVED
        selectedRecipientId != null -> peerChatKey(selectedRecipientId!!)
        else -> CHAT_EVERYONE
    }

    private fun peerChatKey(peerId: UUID): String = "peer:$peerId"

    private fun messagesForChat(chatKey: String): MutableList<ChatMessage> =
        when (chatKey) {
            CHAT_SAVED -> savedMessages
            CHAT_EVERYONE -> meshMessages
            else -> peerMessages.getOrPut(chatKey) {
                chatStore.loadMessages(chatKey)
                    .map { it.toChatMessage(defaultStatus = null) }
                    .toMutableList()
            }
        }

    private fun rememberPeer(peerId: UUID, label: String) {
        val existing = chatStore.getPeer(peerId.toString())
        chatStore.upsertPeer(
            StoredPeer(
                nodeId = peerId.toString(),
                alias = label,
                fingerprint = peerFingerprint(peerId),
                verified = existing?.verified == true,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    private fun syncKnownPeers() {
        meshService?.knownPeers().orEmpty().forEach { peer ->
            rememberPeer(peer.nodeId, peer.displayName ?: "@${peer.nodeId.toString().take(8)}")
        }
    }

    private fun parseIncomingSender(raw: String): IncomingSender {
        val parts = raw.split("|")
        val label = parts.firstOrNull().orEmpty().ifBlank { "@peer" }
        val nodeId = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val isBroadcast = parts.getOrNull(2) == "broadcast"
        return IncomingSender(label, nodeId, isBroadcast)
    }

    private fun parseIncomingMeta(meta: String): IncomingMeta {
        val atIndex = meta.lastIndexOf(" at ")
        if (atIndex <= 0) {
            return IncomingMeta("@peer", System.currentTimeMillis(), meta)
        }
        val senderRaw = meta.substring(0, atIndex)
        val tail = meta.substring(atIndex + 4)
        val splitIndex = tail.indexOf(": ")
        if (splitIndex <= 0) {
            return IncomingMeta(senderRaw, System.currentTimeMillis(), tail)
        }
        val timestamp = tail.substring(0, splitIndex).toLongOrNull() ?: System.currentTimeMillis()
        val payload = tail.substring(splitIndex + 2)
        return IncomingMeta(senderRaw, timestamp, payload)
    }

    private fun isSelfSender(nodeId: UUID?): Boolean =
        nodeId != null && nodeId == meshService?.nodeId

    private fun incomingChatKey(sender: IncomingSender): String =
        if (sender.isBroadcast || sender.nodeId == null) CHAT_EVERYONE else peerChatKey(sender.nodeId)

    private fun loadStoredMessages() {
        chatStore.ensureBaseChats()
        migrateSavedMessagesIfNeeded()

        val storedMesh = chatStore.loadMessages(CHAT_EVERYONE).map { it.toChatMessage(defaultStatus = null) }
        if (storedMesh.isNotEmpty()) {
            meshMessages.clear()
            meshMessages += storedMesh
            messages.clear()
            messages += meshMessages
        }

        savedMessages.clear()
        savedMessages += chatStore.loadMessages(CHAT_SAVED)
            .map { it.toChatMessage(defaultStatus = MessageStatus.READ) }
    }

    private fun migrateSavedMessagesIfNeeded() {
        if (chatStore.countMessages(CHAT_SAVED) > 0) return
        val prefs = getSharedPreferences(SAVED_MESSAGES_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(SAVED_MESSAGES_KEY, "").orEmpty()
        if (stored.isBlank()) return

        stored.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 7)
                val author = parts.firstOrNull().orEmpty().ifBlank { "@me" }
                val body = parts.getOrNull(1)?.decodeStoredText() ?: return@mapNotNull null
                val imagePath = parts.getOrNull(2)?.decodeStoredText()?.ifBlank { null }
                val audioPath = parts.getOrNull(5)?.decodeStoredText()?.ifBlank { null }
                val reaction = parts.getOrNull(6)?.decodeStoredText()?.ifBlank { null }
                val timestamp = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val status = parts.getOrNull(4)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() }
                    ?: MessageStatus.READ
                ChatMessage(
                    author = author,
                    body = body,
                    mine = true,
                    imagePath = imagePath,
                    audioPath = audioPath,
                    reaction = reaction,
                    timestamp = timestamp.takeIf { it > 0L } ?: estimateLegacyTimestamp(),
                    status = status
                )
            }
            .forEach { persistChatMessage(CHAT_SAVED, it) }

        prefs.edit().remove(SAVED_MESSAGES_KEY).apply()
    }

    private fun renameLocalMessages(previous: String, next: String) {
        (meshMessages + savedMessages + messages)
            .filter { it.mine }
            .forEach { it.author = next }
        peerMessages.values.flatten()
            .filter { it.mine }
            .forEach { it.author = next }
        chatStore.updateMineAuthor(next)
        chatAdapter.notifyDataSetChanged()
    }

    private fun persistChatMessage(chatKey: String, message: ChatMessage) {
        if (message.author == "system" || message.author == "mesh") return
        if (message.localId != 0L) return
        message.localId = chatStore.insertMessage(chatKey, message.toStoredMessage())
    }

    private fun persistChatMessageIdentity(message: ChatMessage) {
        if (message.localId == 0L) return
        chatStore.updateMessageIdentity(
            localId = message.localId,
            meshMessageId = message.messageId?.toString(),
            status = message.status?.name
        )
    }

    private fun rebuildStoredChat(chatKey: String, items: List<ChatMessage>) {
        chatStore.clearChat(chatKey)
        items.filterNot { it.author == "system" || it.author == "mesh" }
            .forEach { message ->
                message.localId = 0L
                persistChatMessage(chatKey, message)
            }
    }

    private fun ChatMessage.toStoredMessage(): StoredMessage =
        StoredMessage(
            author = displayAuthor(),
            body = body,
            mine = mine,
            imagePath = imagePath,
            audioPath = audioPath,
            reaction = reaction,
            timestamp = timestamp,
            meshMessageId = messageId?.toString(),
            status = status?.name
        )

    private fun StoredMessage.toChatMessage(defaultStatus: MessageStatus?): ChatMessage =
        ChatMessage(
            author = author,
            body = body,
            mine = mine,
            imagePath = imagePath,
            audioPath = audioPath,
            reaction = reaction,
            timestamp = timestamp,
            messageId = meshMessageId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            status = status?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() } ?: defaultStatus,
            localId = id
        )

    private fun estimateLegacyTimestamp(): Long =
        File(applicationInfo.dataDir, "shared_prefs/$SAVED_MESSAGES_PREFS.xml")
            .lastModified()
            .takeIf { it > 0L }
            ?: System.currentTimeMillis()

    private fun ChatMessage.displayAuthor(): String =
        if (mine) contactDisplayName() else author.removePrefix("@")

    private fun ChatMessage.displayTime(): String =
        messageTimeFormat.format(Date(timestamp))

    private fun ChatMessage.displayDate(): String {
        val messageDay = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            messageDay.isSameDay(today) -> tr("today")
            messageDay.isSameDay(yesterday) -> tr("yesterday")
            else -> messageDateFormat.format(Date(timestamp))
        }
    }

    private fun Calendar.isSameDay(other: Calendar): Boolean =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

    private fun updateRecipientHint() {
        messageInput.hint = when {
            savedMessagesSelected -> tr("hint_save_message")
            selectedRecipientId == null -> tr("hint_type_message")
            else -> "${tr("hint_message")} $selectedRecipientLabel..."
        }
    }

    private fun setReplyTarget(message: ChatMessage) {
        if (message.author == "system" || message.author == "mesh") return
        replyTarget = message
        val preview = when {
            message.imagePath != null -> tr("photo")
            message.audioPath != null -> tr("voice_message")
            else -> message.body.take(42)
        }
        replyTextView.text = "${tr("replying_to")} ${message.displayAuthor()}: $preview"
        replyBar.visibility = View.VISIBLE
    }

    private fun clearReplyTarget() {
        replyTarget = null
        if (::replyBar.isInitialized) {
            replyBar.visibility = View.GONE
        }
    }

    private fun applyReplyToBody(raw: String): String {
        val target = replyTarget ?: return raw
        val preview = when {
            target.imagePath != null -> tr("photo")
            target.audioPath != null -> tr("voice_message")
            else -> target.body.take(40)
        }
        clearReplyTarget()
        return "↪ ${target.displayAuthor()}: $preview\n$raw"
    }

    private fun updateChatTitle() {
        if (::chatTitleView.isInitialized) {
            chatTitleView.text = selectedRecipientLabel.toDisplayTitle()
        }
        if (::networkStatusView.isInitialized) {
            networkStatusView.text = when {
                savedMessagesSelected -> tr("saved_messages")
                selectedRecipientId != null -> {
                    if (isPeerOnline(selectedRecipientId)) {
                        tr("online_now")
                    } else {
                        val peer = chatStore.getPeer(selectedRecipientId.toString())
                        "${tr("last_seen_prefix")} ${formatPeerPresence(peer?.lastSeen ?: 0L)}"
                    }
                }
                else -> networkStatusView.text
            }
        }
    }

    private fun updateNetworkStatusFromLog(line: String) {
        if (selectedRecipientId != null && !savedMessagesSelected) {
            if (::networkStatusView.isInitialized) {
                networkStatusView.text = if (isPeerOnline(selectedRecipientId)) {
                    tr("online_now")
                } else {
                    val peer = chatStore.getPeer(selectedRecipientId.toString())
                    "${tr("last_seen_prefix")} ${formatPeerPresence(peer?.lastSeen ?: 0L)}"
                }
            }
            return
        }
        val status = when {
            line.startsWith("search people:") -> tr("status_searching_nearby")
            line.startsWith("mesh started") || line.startsWith("service connected") -> tr("status_mesh_online")
            line.startsWith("discovered:") -> tr("status_person_found")
            line.startsWith("secure session:") -> tr("status_secure_ready")
            line.startsWith("message delivered:") -> tr("delivered")
            line.startsWith("message read:") -> tr("read")
            line.startsWith("mesh service disconnected") -> tr("offline")
            line.contains("permission missing", ignoreCase = true) -> tr("status_permissions_needed")
            line.contains("bluetooth disabled", ignoreCase = true) -> tr("status_bt_disabled")
            else -> null
        } ?: return
        if (::networkStatusView.isInitialized) {
            networkStatusView.text = status
        }
    }

    private fun showCurrentChatProfile() {
        val title = if (selectedRecipientId != null) {
            selectedRecipientLabel.toDisplayTitle().removePrefix("@")
        } else {
            selectedRecipientLabel.toDisplayTitle()
        }
        val peerId = selectedRecipientId
        val storedPeer = peerId?.let { chatStore.getPeer(it.toString()) }
        val fingerprint = peerId?.let { storedPeer?.fingerprint ?: peerFingerprint(it) }
        val verified = storedPeer?.verified == true
        val subtitle = when {
            savedMessagesSelected -> tr("private_local_chat_desc")
            selectedRecipientId == null -> tr("broadcast_chat_desc")
            else -> "${tr("last_seen_prefix")} ${formatPeerPresence(storedPeer?.lastSeen ?: 0L)} / ${tr("peer_id")}: ${peerId.toString().take(8)}...${peerId.toString().takeLast(6)}"
        }
        val action = when {
            savedMessagesSelected -> tr("saved_auto_read")
            selectedRecipientId == null -> tr("messages_public_mesh")
            verified -> tr("contact_verified_local")
            else -> tr("compare_code_verify")
        }

        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = roundedDrawable(SOFT_PINK_PANEL, dp(24), SOFT_PINK_STROKE)
            addView(terminalText(title).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalText(subtitle).apply {
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalText(action).apply {
                textSize = 13f
                setTextColor(MUTED_CORAL)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalAction(tr("search_messages")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                setOnClickListener {
                    dialog.dismiss()
                    showMessageSearchDialog(currentChatKey())
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            if (peerId != null && fingerprint != null) {
                addView(terminalText(fingerprint.chunked(4).joinToString(" ")).apply {
                    textSize = 18f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(BERRY_TEXT)
                    background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
                addView(terminalAction(if (verified) tr("verified") else tr("mark_verified")).apply {
                    textSize = 16f
                    gravity = Gravity.CENTER
                    background = roundedDrawable(if (verified) 0x33FF4D6D else ACCENT_PINK, dp(18), SOFT_PINK_STROKE)
                    setOnClickListener {
                        rememberPeer(peerId, selectedRecipientLabel)
                        chatStore.setPeerVerified(peerId.toString(), true)
                        Toast.makeText(this@MainActivity, tr("contact_verified"), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        updateChatTitle()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
            }
        }
        dialog.setContentView(FrameLayout(this).apply {
            setPadding(dp(22), dp(44), dp(22), dp(28))
            setBackgroundColor(0x22E94F64)
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun showOwnProfilePage() {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val originalName = meshService?.getNickname()?.take(MAX_NICKNAME_LENGTH) ?: currentNickname
        val avatarPath = AppProfileStore.avatarPath(this)
        val originalInput = EditText(this).apply {
            setText(originalName)
            setSingleLine(true)
            typeface = Typeface.DEFAULT
            textSize = 16f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            hint = "@nickname"
            filters = arrayOf(InputFilter.LengthFilter(MAX_NICKNAME_LENGTH))
            background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        var normalizingOriginal = false
        originalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (normalizingOriginal) return
                val current = s?.toString().orEmpty()
                if (current.startsWith("@") && current.length <= MAX_NICKNAME_LENGTH) return
                normalizingOriginal = true
                val normalized = "@${current.removePrefix("@")}".take(MAX_NICKNAME_LENGTH)
                originalInput.setText(normalized)
                originalInput.setSelection(normalized.length.coerceAtLeast(1))
                normalizingOriginal = false
            }
        })
        val displayInput = EditText(this).apply {
            setText(AppProfileStore.displayName(this@MainActivity))
            setSingleLine(true)
            typeface = Typeface.DEFAULT
            textSize = 16f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            hint = tr("display_name")
            filters = arrayOf(InputFilter.LengthFilter(24))
            background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(20))
            setBackgroundColor(CREAM_BACKGROUND)

            addView(AvatarView(
                context = this@MainActivity,
                label = contactDisplayName(),
                imagePath = avatarPath,
                accentColor = avatarAccent(contactDisplayName()),
                verified = false
            ).apply {
                setOnClickListener {
                    dialog.dismiss()
                    openProfileAvatarPicker()
                }
            }, LinearLayout.LayoutParams(dp(86), dp(86)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            })

            addView(terminalAction(tr("change_photo")).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(STRAWBERRY_RED)
                background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener {
                    dialog.dismiss()
                    openProfileAvatarPicker()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            })

            addView(terminalText(tr("profile")).apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })

            addView(terminalText(tr("original_name_editable")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(16), 0, dp(6))
            })
            addView(originalInput)

            addView(terminalText(tr("display_name_editable")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(displayInput)

            addView(terminalAction(tr("save")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(STRAWBERRY_RED, dp(18))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    val previousNickname = currentNickname
                    val requestedNickname = originalInput.text?.toString()
                        .orEmpty()
                        .trim()
                        .ifBlank { previousNickname }
                        .prefixAt()
                        .take(MAX_NICKNAME_LENGTH)
                    val service = meshService
                    val appliedNickname = if (service == null) {
                        Toast.makeText(this@MainActivity, tr("nickname_change_online_only"), Toast.LENGTH_SHORT).show()
                        previousNickname
                    } else {
                        service.setNickname(requestedNickname).take(MAX_NICKNAME_LENGTH).also { display ->
                            if (display != requestedNickname) {
                                Toast.makeText(this@MainActivity, tr("nickname_change_once_week"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (appliedNickname != previousNickname) {
                        currentNickname = appliedNickname
                        renameLocalMessages(previousNickname, appliedNickname)
                    }
                    AppProfileStore.setDisplayName(this@MainActivity, displayInput.text?.toString().orEmpty())
                    usernameField.setText(contactDisplayName())
                    Toast.makeText(this@MainActivity, tr("profile_updated"), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showChatList()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(buildPageBottomNav(
                selected = PageTab.PROFILE,
                onNewContacts = {
                    dialog.dismiss()
                    showMeshPanel(scanFirst = true)
                },
            onContacts = {
                dialog.dismiss()
                showChatList()
            },
            onProfile = { },
            onSettings = {
                dialog.dismiss()
                showSettingsPage()
            }
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            })
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun showSettingsPage(group: SettingsGroup? = null) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        fun applySettingsNow(refreshMessages: Boolean = false, recreateUi: Boolean = false) {
            applyDateTimeFormat()
            saveUiSettings()
            meshService?.configureNotificationSettings(
                enabled = notificationEnabled,
                showPreview = notificationPreviewEnabled,
                includeBroadcast = notificationBroadcastEnabled
            )
            meshService?.configureDiscovery(meshAggressiveMode)
            meshService?.configureMaxRelayHops(meshMaxHops)
            if (refreshMessages) {
                runCatching { chatAdapter.notifyDataSetChanged() }
            }
            refreshHeader()
            if (recreateUi) {
                applyThemePalette()
                window.statusBarColor = CREAM_BACKGROUND
                window.navigationBarColor = CREAM_BACKGROUND
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(20))
            setBackgroundColor(CREAM_BACKGROUND)

            if (group != null) {
                addView(terminalAction("< ${tr("settings")}").apply {
                    textSize = 13f
                    setTextColor(BERRY_TEXT)
                    background = roundedDrawable(INPUT_SURFACE, dp(10), SOFT_PINK_STROKE)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        dialog.dismiss()
                        showSettingsPage()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(14)
                })
            }
            addView(terminalText(tr(group?.titleKey ?: "settings")).apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })
            addView(terminalText(tr(group?.descriptionKey ?: "settings_subtitle")).apply {
                textSize = 13f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(4), 0, dp(10))
            })
        }

        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(settingsContent, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        var currentSectionCard: LinearLayout? = null

        fun sectionHost(): LinearLayout = currentSectionCard ?: settingsContent

        fun addCardSpacing(host: LinearLayout) {
            if (host.childCount > 0) {
                host.addView(View(this), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(8)
                ))
            }
        }

        fun addSection(titleKey: String, descriptionKey: String) {
            settingsContent.addView(terminalText(tr(titleKey)).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(16), 0, dp(4))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (settingsContent.childCount > 0) topMargin = dp(8)
            })
            settingsContent.addView(terminalText(tr(descriptionKey)).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, 0, 0, dp(10))
            })
            currentSectionCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            settingsContent.addView(currentSectionCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun addToolAction(
            titleKey: String,
            descriptionKey: String,
            onClick: () -> Unit
        ) {
            val host = sectionHost()
            addCardSpacing(host)
            host.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(Color.TRANSPARENT, dp(14))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(terminalText(tr(titleKey)).apply {
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                })
                addView(terminalText(tr(descriptionKey)).apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(2), 0, 0)
                })
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun addToggle(
            titleKey: String,
            descriptionKey: String,
            checked: Boolean,
            onChanged: (Boolean) -> Unit
        ): CheckBox {
            lateinit var checkbox: CheckBox
            val host = sectionHost()
            addCardSpacing(host)
            host.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(Color.TRANSPARENT, dp(14))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(terminalText(tr(titleKey)).apply {
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(BERRY_TEXT)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    checkbox = CheckBox(this@MainActivity).apply {
                        isChecked = checked
                        setOnCheckedChangeListener { _, value -> onChanged(value) }
                    }
                    addView(checkbox)
                })
                addView(terminalText(tr(descriptionKey)).apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return checkbox
        }

        fun addChoiceChips(
            titleKey: String,
            values: List<Pair<String, String>>,
            selectedKey: String,
            onSelected: (String) -> Unit
        ) {
            val host = sectionHost()
            addCardSpacing(host)
            host.addView(terminalText(tr(titleKey)).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(dp(8), 0, dp(8), dp(6))
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val map = linkedMapOf<String, TextView>()
            values.forEachIndexed { index, pair ->
                val button = chatListBottomItem(pair.second, pair.first == selectedKey) { }
                button.setOnClickListener {
                    onSelected(pair.first)
                    map.forEach { (key, view) ->
                        val isSelected = key == pair.first
                        view.background = roundedDrawable(if (isSelected) STRAWBERRY_RED else Color.TRANSPARENT, dp(20))
                        view.setTextColor(if (isSelected) Color.WHITE else BERRY_TEXT_DIM)
                    }
                }
                map[pair.first] = button
                row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) marginStart = dp(4)
                    if (index < values.lastIndex) marginEnd = dp(4)
                })
            }
            host.addView(row)
        }

        fun addSettingsGroupRow(settingsGroup: SettingsGroup) {
            addCardSpacing(settingsContent)
            settingsContent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(INPUT_SURFACE, dp(14), SOFT_PINK_STROKE)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(terminalText(tr(settingsGroup.titleKey)).apply {
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(BERRY_TEXT)
                    })
                    addView(terminalText(tr(settingsGroup.descriptionKey)).apply {
                        textSize = 12f
                        setTextColor(BERRY_TEXT_DIM)
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(terminalText(">").apply {
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(BERRY_TEXT_DIM)
                }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener {
                    dialog.dismiss()
                    showSettingsPage(settingsGroup)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        if (group == null) {
            SettingsGroup.entries.forEach(::addSettingsGroupRow)
        }

        if (group == SettingsGroup.ACCOUNT) {
            addSection("settings_account", "settings_account_desc")
            addToolAction("settings_edit_profile", "settings_edit_profile_desc") {
                dialog.dismiss()
                showOwnProfilePage()
            }
            addToolAction("settings_change_photo", "settings_change_photo_desc") {
                dialog.dismiss()
                openProfileAvatarPicker()
            }
            addToolAction("settings_identity_code", "settings_identity_code_desc") {
                Toast.makeText(this, meshService?.getLocalFingerprint().orEmpty().chunked(4).joinToString(" "), Toast.LENGTH_LONG).show()
            }
        }

        if (group == SettingsGroup.APPEARANCE) {
            addSection("settings_appearance", "settings_appearance_desc")

            val themeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val lightButton = chatListBottomItem(tr("theme_light"), !darkThemeEnabled) { }
            val darkButton = chatListBottomItem(tr("theme_dark"), darkThemeEnabled) { }
            lightButton.setOnClickListener {
                darkThemeEnabled = false
                lightButton.background = roundedDrawable(STRAWBERRY_RED, dp(20))
                lightButton.setTextColor(Color.WHITE)
                darkButton.background = roundedDrawable(Color.TRANSPARENT, dp(20))
                darkButton.setTextColor(BERRY_TEXT_DIM)
                applySettingsNow(recreateUi = true)
            }
            darkButton.setOnClickListener {
                darkThemeEnabled = true
                darkButton.background = roundedDrawable(STRAWBERRY_RED, dp(20))
                darkButton.setTextColor(Color.WHITE)
                lightButton.background = roundedDrawable(Color.TRANSPARENT, dp(20))
                lightButton.setTextColor(BERRY_TEXT_DIM)
                applySettingsNow(recreateUi = true)
            }
            themeRow.addView(lightButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            themeRow.addView(darkButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
            sectionHost().addView(themeRow)

            addSection("language", "settings_language_desc")

            val langButtons = linkedMapOf<AppLanguage, TextView>()
            val langRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            AppLanguage.entries.forEachIndexed { index, lang ->
                val button = chatListBottomItem(lang.label, selectedLanguage == lang) { }
                button.setOnClickListener {
                    selectedLanguage = lang
                    langButtons.forEach { (candidate, view) ->
                        if (candidate == lang) {
                            view.background = roundedDrawable(STRAWBERRY_RED, dp(20))
                            view.setTextColor(Color.WHITE)
                        } else {
                            view.background = roundedDrawable(Color.TRANSPARENT, dp(20))
                            view.setTextColor(BERRY_TEXT_DIM)
                        }
                    }
                    applySettingsNow(recreateUi = true)
                }
                langButtons[lang] = button
                langRow.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (index > 0) marginStart = dp(4)
                    if (index < AppLanguage.entries.lastIndex) marginEnd = dp(4)
                })
            }
            sectionHost().addView(langRow)
        }

        if (group == SettingsGroup.PRIVACY) {
            addSection("settings_security", "settings_security_desc")
            addToggle("settings_lock_enable", "settings_lock_enable_desc", appLockEnabled) {
                appLockEnabled = it
                applySettingsNow()
            }
            addToolAction("settings_lock_pin", "settings_lock_pin_desc") {
                showSetAppLockPinDialog {
                    applySettingsNow()
                }
            }
            addChoiceChips(
                "settings_lock_timeout",
                listOf("1" to "1 min", "5" to "5 min", "15" to "15 min", "60" to "1 h"),
                appLockTimeoutMinutes.toString()
            ) { selected ->
                appLockTimeoutMinutes = selected.toIntOrNull()?.coerceIn(1, 60) ?: 5
                applySettingsNow()
            }
        }

        if (group == SettingsGroup.MESH) {
            addSection("settings_mesh_section", "settings_mesh_desc")
            addChoiceChips(
                "settings_mesh_mode",
                listOf("balanced" to tr("mesh_mode_balanced"), "aggressive" to tr("mesh_mode_aggressive")),
                if (meshAggressiveMode) "aggressive" else "balanced"
            ) { selected ->
                meshAggressiveMode = selected == "aggressive"
                applySettingsNow()
            }
            addChoiceChips(
                "settings_max_hops",
                listOf("2" to "2", "4" to "4", "6" to "6", "8" to "8"),
                meshMaxHops.toString()
            ) { selected ->
                meshMaxHops = selected.toIntOrNull()?.coerceIn(1, 8) ?: 8
                applySettingsNow()
            }
            addToolAction("settings_restart_mesh", "settings_restart_mesh_desc") {
                meshService?.startNearbyDiscovery(silent = false)
                Toast.makeText(this, tr("status_searching_nearby"), Toast.LENGTH_SHORT).show()
            }
        }

        if (group == SettingsGroup.NOTIFICATIONS) {
            addSection("settings_notifications", "settings_notifications_desc")
            addToggle("settings_notify_enable", "settings_notify_enable_desc", notificationEnabled) {
                notificationEnabled = it
                applySettingsNow()
            }
            addToggle("settings_notify_preview", "settings_notify_preview_desc", notificationPreviewEnabled) {
                notificationPreviewEnabled = it
                applySettingsNow()
            }
            addToggle("settings_notify_broadcast", "settings_notify_broadcast_desc", notificationBroadcastEnabled) {
                notificationBroadcastEnabled = it
                applySettingsNow()
            }
        }

        if (group == SettingsGroup.MEDIA) {
            addSection("settings_media", "settings_media_desc")
            addToggle("settings_crop_images", "settings_crop_images_desc", cropChatImagesEnabled) {
                cropChatImagesEnabled = it
                applySettingsNow(refreshMessages = true)
            }
            addToolAction("settings_cleanup_cache", "settings_cleanup_cache_desc") {
                cleanupMediaCache()
                Toast.makeText(this@MainActivity, tr("cleanup_done"), Toast.LENGTH_SHORT).show()
            }
        }

        if (group == SettingsGroup.CHAT) {
            addSection("settings_chat_behavior", "settings_chat_behavior_desc")
            addToggle("settings_chat_compact", "settings_chat_compact_desc", compactChatListEnabled) {
                compactChatListEnabled = it
                applySettingsNow(refreshMessages = true)
            }
            addChoiceChips(
                "settings_chat_font",
                listOf("0.9" to tr("small"), "1.0" to tr("normal"), "1.15" to tr("large")),
                "%.1f".format(Locale.US, messageTextScale)
            ) { selected ->
                messageTextScale = selected.toFloatOrNull()?.coerceIn(0.9f, 1.3f) ?: 1.0f
                applySettingsNow(refreshMessages = true)
            }
            addToolAction("settings_show_onboarding", "settings_show_onboarding_desc") {
                getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(UI_SETTINGS_QUICK_START_HIDDEN, false)
                    .apply()
                Toast.makeText(this@MainActivity, tr("quick_start_title"), Toast.LENGTH_SHORT).show()
            }
        }

        if (group == SettingsGroup.REGION) {
            addSection("settings_region", "settings_region_desc")
            addChoiceChips(
                "settings_time_format",
                listOf("24" to "24h", "12" to "12h"),
                if (use24HourFormat) "24" else "12"
            ) { selected ->
                use24HourFormat = selected == "24"
                applySettingsNow(refreshMessages = true)
            }
            addChoiceChips(
                "settings_date_format",
                listOf("short" to tr("short"), "long" to tr("long")),
                if (shortDateFormatEnabled) "short" else "long"
            ) { selected ->
                shortDateFormatEnabled = selected == "short"
                applySettingsNow(refreshMessages = true)
            }
        }

        if (group == SettingsGroup.DATA) {
            addSection("settings_data_storage", "settings_data_storage_desc")
            addToolAction("settings_export_backup", "settings_export_backup_desc") {
                openBackupCreateDocument()
            }
            addToolAction("settings_import_backup", "settings_import_backup_desc") {
                openBackupPicker()
            }
            addToolAction("settings_cleanup_cache", "settings_cleanup_cache_desc") {
                cleanupMediaCache()
                Toast.makeText(this@MainActivity, tr("cleanup_done"), Toast.LENGTH_SHORT).show()
            }
            addToolAction("settings_connection_diag", "settings_connection_diag_desc") {
                showConnectionDiagnostics()
            }
        }

        root.addView(settingsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(buildPageBottomNav(
            selected = PageTab.SETTINGS,
            onNewContacts = {
                dialog.dismiss()
                showMeshPanel(scanFirst = true)
            },
            onContacts = {
                dialog.dismiss()
                showChatList()
            },
            onProfile = {
                dialog.dismiss()
                showOwnProfilePage()
            },
            onSettings = { }
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun openBackupPicker() {
        if (backupOperationRunning) {
            Toast.makeText(this, tr("backup_busy"), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, BACKUP_IMPORT_REQUEST)
    }

    private fun openBackupCreateDocument() {
        if (backupOperationRunning) {
            Toast.makeText(this, tr("backup_busy"), Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "truskawka_backup_$timestamp.tbk")
        }
        startActivityForResult(intent, BACKUP_EXPORT_REQUEST)
    }

    private fun exportEncryptedBackupTo(uri: Uri) {
        if (backupOperationRunning) {
            Toast.makeText(this, tr("backup_busy"), Toast.LENGTH_SHORT).show()
            return
        }
        promptBackupPassword(
            title = tr("backup_password_export_title"),
            message = tr("backup_password_export_desc"),
            requireStrongPassword = true
        ) { password ->
            runExportEncryptedBackupTo(uri, password)
        }
    }

    private fun runExportEncryptedBackupTo(uri: Uri, password: CharArray) {
        if (backupOperationRunning) return
        backupOperationRunning = true
        showBackupProgress("${tr("export_backup")}...")
        thread(name = "export-backup") {
            try {
                val payload = buildBackupPayload().toByteArray(StandardCharsets.UTF_8)
                val encrypted = BackupSupport.encrypt(payload, password) ?: run {
                    hideBackupProgress()
                    backupOperationRunning = false
                    runOnUiThread { Toast.makeText(this, tr("backup_failed"), Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(encrypted)
                        out.flush()
                    } ?: error("output stream is null")
                }
                    .onSuccess {
                        hideBackupProgress()
                        backupOperationRunning = false
                        runOnUiThread {
                            Toast.makeText(this, tr("backup_exported"), Toast.LENGTH_LONG).show()
                        }
                    }
                    .onFailure {
                        hideBackupProgress()
                        backupOperationRunning = false
                        runOnUiThread { Toast.makeText(this, tr("backup_failed"), Toast.LENGTH_SHORT).show() }
                    }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun promptBackupPassword(
        title: String,
        message: String,
        requireStrongPassword: Boolean,
        onPassword: (CharArray) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = tr("backup_password_hint")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setNegativeButton(tr("cancel"), null)
            .setPositiveButton(tr("continue"), null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text?.toString().orEmpty()
            if (requireStrongPassword && password.length < BACKUP_PASSWORD_MIN_LENGTH) {
                Toast.makeText(this, tr("backup_password_short"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onPassword(password.toCharArray())
        }
    }

    private fun importEncryptedBackup(uri: Uri) {
        if (backupOperationRunning) {
            Toast.makeText(this, tr("backup_busy"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("import_backup"))
            .setMessage(tr("backup_import_confirm"))
            .setNegativeButton(tr("cancel"), null)
            .setPositiveButton(tr("continue")) { _, _ ->
                promptBackupPassword(
                    title = tr("backup_password_import_title"),
                    message = tr("backup_password_import_desc"),
                    requireStrongPassword = false
                ) { password ->
                    runImportEncryptedBackup(uri, password)
                }
            }
            .show()
    }

    private fun runImportEncryptedBackup(uri: Uri, password: CharArray) {
        if (backupOperationRunning) return
        backupOperationRunning = true
        showBackupProgress("${tr("import_backup")}...")
        thread(name = "import-backup") {
            try {
                val sourceName = ImageTransferPreparer.queryDisplayName(contentResolver, uri).lowercase(Locale.US)
                if (!sourceName.endsWith(".tbk")) {
                    hideBackupProgress()
                    backupOperationRunning = false
                    runOnUiThread { Toast.makeText(this, tr("backup_invalid_file"), Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val encrypted = BackupSupport.readUriBytesWithLimit(contentResolver, uri, MAX_BACKUP_BYTES) ?: run {
                    hideBackupProgress()
                    backupOperationRunning = false
                    runOnUiThread { Toast.makeText(this, tr("backup_invalid_file"), Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val decrypted = BackupSupport.decrypt(this, encrypted, password) ?: run {
                    hideBackupProgress()
                    backupOperationRunning = false
                    runOnUiThread { Toast.makeText(this, tr("backup_failed"), Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val text = String(decrypted, StandardCharsets.UTF_8)
                val restoredCount = restoreBackupPayload(text)
                runOnUiThread {
                    hideBackupProgress()
                    backupOperationRunning = false
                    if (restoredCount >= 0) {
                        Toast.makeText(this, "${tr("backup_imported")}: $restoredCount ${tr("restored_messages")}", Toast.LENGTH_LONG).show()
                        showChatMessages(currentChatKey())
                        showChatList()
                    } else {
                        Toast.makeText(
                            this,
                            backupRestoreErrorMessage ?: tr("backup_failed"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun buildBackupPayload(): String {
        val bodyRows = mutableListOf<String>()
        bodyRows += "TSK1\t${System.currentTimeMillis()}"
        bodyRows += listOf("S", "theme_dark", if (darkThemeEnabled) "1" else "0").joinToString("\t")
        bodyRows += listOf("S", "language", selectedLanguage.code).joinToString("\t")
        bodyRows += listOf("S", "display_name", AppProfileStore.displayName(this).encodeStoredText()).joinToString("\t")
        chatStore.listChats().forEach { chat ->
            bodyRows += listOf(
                "C",
                chat.chatKey.encodeStoredText(),
                chat.title.encodeStoredText(),
                chat.kind.encodeStoredText(),
                (chat.peerId ?: "").encodeStoredText(),
                if (chat.verified) "1" else "0",
                chat.unreadCount.toString(),
                if (chat.pinned) "1" else "0",
                chat.lastTimestamp.toString()
            ).joinToString("\t")
        }
        val chatKeys = chatStore.listChats().map { it.chatKey }.distinct()
        chatKeys.forEach { chatKey ->
            chatStore.loadMessages(chatKey).forEach { msg ->
                bodyRows += listOf(
                    "M",
                    chatKey.encodeStoredText(),
                    msg.author.encodeStoredText(),
                    msg.body.encodeStoredText(),
                    if (msg.mine) "1" else "0",
                    (msg.imagePath ?: "").encodeStoredText(),
                    (msg.audioPath ?: "").encodeStoredText(),
                    msg.timestamp.toString(),
                    (msg.meshMessageId ?: "").encodeStoredText(),
                    (msg.status ?: "").encodeStoredText(),
                    (msg.reaction ?: "").encodeStoredText()
                ).joinToString("\t")
            }
        }
        val body = bodyRows.joinToString("\n")
        val checksum = BackupSupport.crc32Hex(body.toByteArray(StandardCharsets.UTF_8))
        return "$body\nCRC\t$checksum"
    }

    private fun restoreBackupPayload(text: String): Int {
        backupRestoreErrorMessage = null
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || !lines.first().startsWith("TSK1")) {
            backupRestoreErrorMessage = tr("backup_corrupted")
            return -1
        }
        val checksumLine = lines.lastOrNull().orEmpty()
        if (!checksumLine.startsWith("CRC\t")) {
            backupRestoreErrorMessage = tr("backup_corrupted")
            return -1
        }
        val expectedChecksum = checksumLine.substringAfter("CRC\t").trim().lowercase(Locale.US)
        val bodyLines = lines.dropLast(1)
        val actualChecksum = BackupSupport.crc32Hex(
            bodyLines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        )
        if (expectedChecksum != actualChecksum) {
            backupRestoreErrorMessage = tr("backup_corrupted")
            return -1
        }
        val parsedRows = mutableListOf<Pair<String, ChatMessage>>()
        val parsedChats = linkedMapOf<String, StoredChat>()
        val peerAliases = mutableMapOf<String, String>()
        var restoredTheme: Boolean? = null
        var restoredLanguage: AppLanguage? = null
        var restoredDisplayName: String? = null
        var restoredCount = 0
        bodyLines.drop(1).forEach { line ->
            val parts = line.split("\t")
            when (parts.firstOrNull()) {
                "S" -> {
                    if (parts.size < 3) return@forEach
                    val key = parts[1]
                    val value = parts[2].decodeStoredText()
                    when (key) {
                        "theme_dark" -> restoredTheme = value == "1"
                        "language" -> restoredLanguage = AppLanguage.fromCode(value)
                        "display_name" -> restoredDisplayName = value
                    }
                    return@forEach
                }
                "C" -> {
                    if (parts.size < 9) return@forEach
                    val chatKey = parts[1].decodeStoredText()
                    val title = parts[2].decodeStoredText()
                    val kind = parts[3].decodeStoredText().ifBlank { ChatKind.PEER.name }
                    val peerId = parts[4].decodeStoredText().ifBlank { null }
                    val verified = parts[5] == "1"
                    val unread = parts[6].toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val pinned = parts[7] == "1"
                    val updatedAt = parts[8].toLongOrNull() ?: System.currentTimeMillis()
                    parsedChats[chatKey] = StoredChat(
                        chatKey = chatKey,
                        title = title.ifBlank { chatKey.toDisplayTitle() },
                        kind = kind,
                        peerId = peerId,
                        verified = verified,
                        unreadCount = unread,
                        pinned = pinned,
                        createdAt = updatedAt,
                        updatedAt = updatedAt
                    )
                    return@forEach
                }
                "M" -> Unit
                else -> return@forEach
            }
            if (parts.size < 11) return@forEach
            val chatKey = parts[1].decodeStoredText()
            val author = parts[2].decodeStoredText()
            val body = parts[3].decodeStoredText()
            val mine = parts[4] == "1"
            val imagePath = parts[5].decodeStoredText().ifBlank { null }
            val audioPath = parts[6].decodeStoredText().ifBlank { null }
            val timestamp = parts[7].toLongOrNull() ?: System.currentTimeMillis()
            val messageId = parts[8].decodeStoredText().ifBlank { null }
            val status = parts[9].decodeStoredText().ifBlank { null }
            val reaction = parts[10].decodeStoredText().ifBlank { null }
            val restored = ChatMessage(
                author = author,
                body = body,
                mine = mine,
                imagePath = imagePath,
                audioPath = audioPath,
                reaction = reaction,
                timestamp = timestamp,
                messageId = messageId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                status = status?.let { runCatching { MessageStatus.valueOf(it) }.getOrNull() }
            )
            parsedRows += chatKey to restored
            restoredCount += 1
            if (chatKey.startsWith("peer:")) {
                val peerId = chatKey.removePrefix("peer:")
                if (!mine && author.isNotBlank()) {
                    peerAliases[peerId] = author
                }
            }
        }
        chatStore.clearAllData()
        meshMessages.clear()
        savedMessages.clear()
        peerMessages.clear()
        messages.clear()
        parsedChats.values.forEach { chatStore.ensureChat(it) }
        parsedRows.forEach { (chatKey, msg) ->
            if (!parsedChats.containsKey(chatKey) && chatKey.startsWith("peer:")) {
                val peerId = chatKey.removePrefix("peer:")
                chatStore.ensureChat(
                    StoredChat(
                        chatKey = chatKey,
                        title = msg.author.ifBlank { "@$peerId".take(12) },
                        kind = ChatKind.PEER.name,
                        peerId = peerId,
                        updatedAt = msg.timestamp
                    )
                )
            } else if (!parsedChats.containsKey(chatKey) && (chatKey == CHAT_EVERYONE || chatKey == CHAT_SAVED)) {
                chatStore.ensureBaseChats()
            }
            messagesForChat(chatKey).add(msg)
            persistChatMessage(chatKey, msg)
        }
        peerAliases.forEach { (nodeId, alias) ->
            val uuid = runCatching { UUID.fromString(nodeId) }.getOrNull() ?: return@forEach
            rememberPeer(uuid, alias)
        }
        restoredTheme?.let { darkThemeEnabled = it }
        restoredLanguage?.let { selectedLanguage = it }
        if (restoredTheme != null || restoredLanguage != null) {
            saveUiSettings()
        }
        restoredDisplayName?.let { AppProfileStore.setDisplayName(this, it) }
        return restoredCount
    }

    private fun showBackupProgress(text: String) {
        runOnUiThread {
            if (backupProgressDialog == null) {
                val dialog = Dialog(this).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setCancelable(false)
                }
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    setBackgroundColor(SOFT_PINK_PANEL)
                    addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(10)
                    })
                    backupProgressText = terminalText(text).apply {
                        textSize = 14f
                        setTextColor(BERRY_TEXT)
                    }
                    addView(backupProgressText, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
                dialog.setContentView(content)
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                backupProgressDialog = dialog
            }
            backupProgressText?.text = text
            if (backupProgressDialog?.isShowing != true) {
                backupProgressDialog?.show()
            }
        }
    }

    private fun hideBackupProgress() {
        runOnUiThread {
            if (backupProgressDialog?.isShowing == true) {
                backupProgressDialog?.dismiss()
            }
        }
    }

    private fun cleanupMediaCache() {
        MediaCacheCleaner.cleanup(filesDir, MAX_MEDIA_CACHE_BYTES)
    }

    private fun showConnectionDiagnostics() {
        val peers = meshService?.knownPeers().orEmpty()
        val diagnostics = meshService?.meshDiagnostics()
        val direct = peers.count { it.isDirect || it.hopCount <= 1 }
        val hops = peers.count { !(it.isDirect || it.hopCount <= 1) }
        val ratio = if (sentCounter <= 0) 0 else ((deliveredCounter * 100f) / sentCounter).toInt()
        val avgHops = peers.map { it.hopCount.coerceAtLeast(1) }.average().takeIf { !it.isNaN() } ?: 0.0
        val discoveryMode = if (meshAggressiveMode) tr("mesh_mode_aggressive") else tr("mesh_mode_balanced")
        val msg = buildString {
            append("${tr("diag_sent")}: $sentCounter\n")
            append("${tr("diag_delivered")}: $deliveredCounter\n")
            append("${tr("diag_read")}: $readCounter\n")
            append("${tr("diag_failed")}: $failedCounter\n")
            append("${tr("diag_delivery_ratio")}: $ratio%\n")
            append("${tr("diag_direct_nodes")}: $direct\n")
            append("${tr("diag_hop_nodes")}: $hops\n")
            append("${tr("diag_avg_hops")}: ${"%.1f".format(avgHops)}\n")
            append("${tr("settings_mesh_section")}: $discoveryMode, ${tr("settings_max_hops")}: $meshMaxHops\n")
            diagnostics?.let { snapshot ->
                append("${tr("diag_router_neighbors")}: ${snapshot.router.neighborCount}\n")
                append("${tr("diag_pending_delivery")}: ${snapshot.router.pendingMessageCount}\n")
                append("${tr("diag_retry_ready")}: ${snapshot.router.retryReadyCount}\n")
                append("${tr("diag_seen_cache")}: ${snapshot.router.seenMessageCount}\n")
                append("${tr("diag_routes")}: ${snapshot.router.routeCount}\n")
                append("${tr("diag_pending_handshake")}: ${snapshot.pendingHandshakeMessages}\n")
                append("${tr("diag_incoming_files")}: ${snapshot.incomingFileTransfers}\n")
            }
            append("${tr("diag_last_relay")}: $lastRelayInfo")
        }
        AlertDialog.Builder(this)
            .setTitle(tr("connection_diagnostics"))
            .setMessage(msg)
            .setNeutralButton(tr("copy")) { _, _ ->
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Truskawka diagnostics", msg))
                Toast.makeText(this, tr("copied"), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadUiSettings() {
        val prefs = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        darkThemeEnabled = prefs.getBoolean(UI_SETTINGS_THEME_DARK, false)
        selectedLanguage = AppLanguage.fromCode(prefs.getString(UI_SETTINGS_LANGUAGE, AppLanguage.EN.code))
        appLockEnabled = prefs.getBoolean(UI_SETTINGS_APP_LOCK_ENABLED, false)
        appLockPin = prefs.getString(UI_SETTINGS_APP_LOCK_PIN, "").orEmpty()
        appLockTimeoutMinutes = prefs.getInt(UI_SETTINGS_APP_LOCK_TIMEOUT, 5).coerceIn(1, 60)
        // Do not restore unlock state across app launches.
        // If user fully re-enters the app, PIN must be requested again.
        lastUnlockAt = 0L
        notificationEnabled = prefs.getBoolean(UI_SETTINGS_NOTIF_ENABLED, true)
        notificationPreviewEnabled = prefs.getBoolean(UI_SETTINGS_NOTIF_PREVIEW, true)
        notificationBroadcastEnabled = prefs.getBoolean(UI_SETTINGS_NOTIF_BROADCAST, true)
        compactChatListEnabled = prefs.getBoolean(UI_SETTINGS_CHAT_COMPACT, false)
        messageTextScale = prefs.getFloat(UI_SETTINGS_CHAT_TEXT_SCALE, 1.0f).coerceIn(0.9f, 1.3f)
        cropChatImagesEnabled = prefs.getBoolean(UI_SETTINGS_CHAT_IMAGE_CROP, true)
        use24HourFormat = prefs.getBoolean(UI_SETTINGS_TIME_24H, true)
        shortDateFormatEnabled = prefs.getBoolean(UI_SETTINGS_DATE_SHORT, false)
        meshAggressiveMode = prefs.getBoolean(UI_SETTINGS_MESH_AGGRESSIVE, true)
        meshMaxHops = prefs.getInt(UI_SETTINGS_MESH_MAX_HOPS, 8).coerceIn(1, 8)
    }

    private fun saveUiSettings() {
        getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(UI_SETTINGS_THEME_DARK, darkThemeEnabled)
            .putString(UI_SETTINGS_LANGUAGE, selectedLanguage.code)
            .putBoolean(UI_SETTINGS_APP_LOCK_ENABLED, appLockEnabled)
            .putString(UI_SETTINGS_APP_LOCK_PIN, appLockPin)
            .putInt(UI_SETTINGS_APP_LOCK_TIMEOUT, appLockTimeoutMinutes)
            .putBoolean(UI_SETTINGS_NOTIF_ENABLED, notificationEnabled)
            .putBoolean(UI_SETTINGS_NOTIF_PREVIEW, notificationPreviewEnabled)
            .putBoolean(UI_SETTINGS_NOTIF_BROADCAST, notificationBroadcastEnabled)
            .putBoolean(UI_SETTINGS_CHAT_COMPACT, compactChatListEnabled)
            .putFloat(UI_SETTINGS_CHAT_TEXT_SCALE, messageTextScale)
            .putBoolean(UI_SETTINGS_CHAT_IMAGE_CROP, cropChatImagesEnabled)
            .putBoolean(UI_SETTINGS_TIME_24H, use24HourFormat)
            .putBoolean(UI_SETTINGS_DATE_SHORT, shortDateFormatEnabled)
            .putBoolean(UI_SETTINGS_MESH_AGGRESSIVE, meshAggressiveMode)
            .putInt(UI_SETTINGS_MESH_MAX_HOPS, meshMaxHops)
            .apply()
    }

    private fun applyDateTimeFormat() {
        val locale = Locale.getDefault()
        messageTimeFormat = if (use24HourFormat) {
            SimpleDateFormat("HH:mm", locale)
        } else {
            SimpleDateFormat("h:mm a", locale)
        }
        messageDateFormat = if (shortDateFormatEnabled) {
            SimpleDateFormat("dd.MM.yyyy", locale)
        } else {
            DateFormat.getDateInstance(DateFormat.LONG, locale)
        }
    }

    private fun ensureAppUnlocked() {
        if (!appLockEnabled || appLockPin.isBlank()) return
        if (appLockDialogVisible) return
        val timeoutMs = appLockTimeoutMinutes * 60_000L
        val now = System.currentTimeMillis()
        val firstUnlockRequired = lastUnlockAt <= 0L
        val backgroundElapsed = if (appWentBackgroundAt > 0L) now - appWentBackgroundAt else Long.MAX_VALUE
        if (!firstUnlockRequired && backgroundElapsed < timeoutMs) return
        showAppLockDialog()
    }

    private fun showAppLockDialog() {
        if (appLockDialogVisible) return
        appLockDialogVisible = true
        val input = EditText(this).apply {
            hint = tr("settings_lock_enter_pin")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            setSingleLine(true)
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("settings_lock_title"))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(tr("unlock")) { _, _ ->
                val value = input.text?.toString().orEmpty()
                if (value == appLockPin) {
                    lastUnlockAt = System.currentTimeMillis()
                    appWentBackgroundAt = 0L
                    saveUiSettings()
                    appLockDialogVisible = false
                } else {
                    Toast.makeText(this, tr("settings_lock_invalid_pin"), Toast.LENGTH_SHORT).show()
                    appLockDialogVisible = false
                    mainHandler.post { showAppLockDialog() }
                }
            }
            .setNegativeButton(tr("cancel")) { _, _ ->
                appLockDialogVisible = false
                finish()
            }
            .setOnCancelListener { appLockDialogVisible = false }
            .setOnDismissListener { appLockDialogVisible = false }
            .show()
    }

    private fun showSetAppLockPinDialog(onSaved: () -> Unit) {
        val input = EditText(this).apply {
            hint = tr("settings_lock_pin_hint")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            setSingleLine(true)
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("settings_lock_pin"))
            .setView(input)
            .setNegativeButton(tr("cancel"), null)
            .setPositiveButton(tr("save")) { _, _ ->
                val value = input.text?.toString().orEmpty()
                if (value.length < 4) {
                    Toast.makeText(this, tr("settings_lock_pin_short"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                appLockPin = value
                appLockEnabled = true
                lastUnlockAt = System.currentTimeMillis()
                onSaved()
            }
            .show()
    }

    private fun applyThemePalette() {
        if (darkThemeEnabled) {
            CREAM_BACKGROUND = DARK_BACKGROUND
            BERRY_TEXT = DARK_TEXT
            BERRY_TEXT_DIM = DARK_TEXT_DIM
            ACCENT_PINK = DARK_ACCENT
            STRAWBERRY_RED = DARK_STRAWBERRY
            LEAF_GREEN = LIGHT_LEAF_GREEN
            INPUT_SURFACE = DARK_SURFACE
            PINK_SHADOW_STROKE = DARK_STROKE
            MUTED_CORAL = DARK_CORAL
            SERVICE_BUBBLE = DARK_SERVICE_BUBBLE
            SERVICE_BUBBLE_STROKE = DARK_SERVICE_STROKE
            INCOMING_BUBBLE = DARK_INCOMING_BUBBLE
            INCOMING_BUBBLE_STROKE = DARK_INCOMING_BUBBLE_STROKE
            INCOMING_TEXT = DARK_INCOMING_TEXT
            OUTGOING_BUBBLE = DARK_OUTGOING_BUBBLE
            OUTGOING_BUBBLE_STROKE = DARK_OUTGOING_BUBBLE_STROKE
            IMAGE_BORDER = DARK_IMAGE_BORDER
            SOFT_PINK_PANEL = DARK_PANEL
            SOFT_PINK_STROKE = DARK_SOFT_STROKE
        } else {
            CREAM_BACKGROUND = LIGHT_CREAM_BACKGROUND
            BERRY_TEXT = LIGHT_BERRY_TEXT
            BERRY_TEXT_DIM = LIGHT_BERRY_TEXT_DIM
            ACCENT_PINK = LIGHT_ACCENT_PINK
            STRAWBERRY_RED = LIGHT_STRAWBERRY_RED
            LEAF_GREEN = LIGHT_LEAF_GREEN
            INPUT_SURFACE = LIGHT_INPUT_SURFACE
            PINK_SHADOW_STROKE = LIGHT_PINK_SHADOW_STROKE
            MUTED_CORAL = LIGHT_MUTED_CORAL
            SERVICE_BUBBLE = LIGHT_SERVICE_BUBBLE
            SERVICE_BUBBLE_STROKE = LIGHT_SERVICE_BUBBLE_STROKE
            INCOMING_BUBBLE = LIGHT_INCOMING_BUBBLE
            INCOMING_BUBBLE_STROKE = LIGHT_INCOMING_BUBBLE_STROKE
            INCOMING_TEXT = LIGHT_INCOMING_TEXT
            OUTGOING_BUBBLE = LIGHT_OUTGOING_BUBBLE
            OUTGOING_BUBBLE_STROKE = LIGHT_OUTGOING_BUBBLE_STROKE
            IMAGE_BORDER = LIGHT_IMAGE_BORDER
            SOFT_PINK_PANEL = LIGHT_SOFT_PINK_PANEL
            SOFT_PINK_STROKE = LIGHT_SOFT_PINK_STROKE
        }
    }

    private fun tr(key: String): String = Translations.tr(selectedLanguage, key)

    private fun contactDisplayName(): String {
        val display = AppProfileStore.displayName(this).trim()
        return display.ifBlank { currentNickname.removePrefix("@") }
    }

    private fun showMessageSearchDialog(chatKey: String) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(SOFT_PINK_PANEL)
        }
        val resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val input = EditText(this).apply {
            hint = tr("search_messages_hint")
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            background = roundedDrawable(INPUT_SURFACE, dp(20), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        fun renderResults() {
            val query = input.text?.toString().orEmpty().trim().lowercase(Locale.getDefault())
            resultsContainer.removeAllViews()
            if (query.isBlank()) {
                resultsContainer.addView(terminalText(tr("type_to_search_chat")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(18), 0, 0)
                })
                return
            }

            val results = messagesForChat(chatKey)
                .filterNot { it.author == "system" || it.author == "mesh" }
                .filter { message ->
                    message.body.lowercase(Locale.getDefault()).contains(query) ||
                        message.author.lowercase(Locale.getDefault()).contains(query) ||
                        (message.imagePath != null && tr("photo").lowercase(Locale.getDefault()).contains(query)) ||
                        (message.audioPath != null && tr("voice_message").lowercase(Locale.getDefault()).contains(query))
                }
                .take(25)

            if (results.isEmpty()) {
                resultsContainer.addView(terminalText(tr("no_matches")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                    setPadding(0, dp(18), 0, 0)
                })
            } else {
                results.forEach { result ->
                    resultsContainer.addView(searchResultRow(result) {
                        val index = messages.indexOf(result)
                        if (index >= 0) chatList.setSelection(index)
                        dialog.dismiss()
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    })
                }
            }
        }

        root.addView(terminalText(tr("search")).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
        })
        root.addView(resultsContainer)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderResults()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderResults()

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        input.requestFocus()
        showKeyboard(input)
    }

    private fun searchResultRow(message: ChatMessage, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
            addView(terminalText("${message.displayAuthor()}  ${message.displayDate()} ${message.displayTime()}").apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
            })
            addView(terminalText(message.body.ifBlank {
                when {
                    message.imagePath != null -> tr("photo")
                    message.audioPath != null -> tr("voice_message")
                    else -> ""
                }
            }).apply {
                textSize = 14f
                setTextColor(BERRY_TEXT)
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun String.toDisplayTitle(): String = when (this) {
        "everyone" -> tr("everyone")
        "saved" -> tr("saved_messages")
        else -> this
    }

    private fun formatPeerPresence(lastSeen: Long): String {
        if (lastSeen <= 0L) return tr("seen_recently")
        val elapsed = System.currentTimeMillis() - lastSeen
        return when {
            elapsed < 2L * 60L * 1000L -> tr("online_now")
            elapsed < 60L * 60L * 1000L -> "${tr("seen")} ${elapsed / 60_000L} ${tr("min_ago")}"
            elapsed < 24L * 60L * 60L * 1000L -> "${tr("seen")} ${elapsed / 3_600_000L} ${tr("h_ago")}"
            else -> "${tr("seen")} ${elapsed / 86_400_000L} ${tr("d_ago")}"
        }
    }

    private fun summaryDisplayTitle(summary: ChatSummary): String {
        val title = summary.title.toDisplayTitle()
        return if (summary.kind == ChatKind.PEER.name) title.removePrefix("@") else title
    }

    private fun summaryPresence(summary: ChatSummary): Pair<String, Boolean> {
        if (summary.kind == ChatKind.SAVED.name) return tr("local") to true
        if (summary.kind == ChatKind.EVERYONE.name) {
            val hasPeers = (meshService?.peerCount() ?: 0) > 0
            return if (hasPeers) tr("online") to true else tr("offline") to false
        }
        val peerId = summary.peerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return tr("offline") to false
        val active = meshService?.knownPeers().orEmpty().any { it.nodeId == peerId }
        if (active) return tr("online") to true

        val lastSeen = chatStore.getPeer(peerId.toString())?.lastSeen ?: 0L
        val recentlyOnline = lastSeen > 0L && (System.currentTimeMillis() - lastSeen) < 2L * 60L * 1000L
        return if (recentlyOnline) tr("online") to true else tr("offline") to false
    }

    private fun isPeerOnline(peerId: UUID?): Boolean {
        if (peerId == null) return false
        return meshService?.knownPeers().orEmpty().any { it.nodeId == peerId }
    }

    private fun showChatRowActions(summary: ChatSummary, onChanged: () -> Unit) {
        val pinLabel = if (summary.pinned) tr("unpin") else tr("pin")
        val actions = mutableListOf(
            StyledActionItem(pinLabel) {
                chatStore.setChatPinned(summary.chatKey, !summary.pinned)
                onChanged()
            }
        )
        if (summary.kind == ChatKind.PEER.name) {
            actions += StyledActionItem(tr("delete"), destructive = true) {
                deleteChatSummary(summary, deleteForEveryone = true)
                onChanged()
            }
        }
        showStyledActionDialog(actions = actions)
    }

    private fun deleteChatSummary(summary: ChatSummary, deleteForEveryone: Boolean) {
        if (deleteForEveryone) {
            sendDeleteChatControl(summary.peerId)
        }
        chatStore.deleteChat(summary.chatKey, summary.peerId)
        val chatKey = summary.chatKey
        if (currentChatKey() == chatKey && !savedMessagesSelected) {
            showChatMessages(chatKey)
            updateRecipientHint()
            updateChatTitle()
        }
        chatAdapter.notifyDataSetChanged()
    }

    private fun showMessageActions(message: ChatMessage) {
        if (message.author == "system" || message.author == "mesh") return
        val actions = mutableListOf(
            StyledActionItem(tr("react")) { showReactionPicker(message) },
            StyledActionItem(tr("forward_saved")) { forwardMessageToSaved(message) },
            StyledActionItem(tr("delete"), destructive = true) { confirmDeleteMessage(message) }
        )
        if (message.imagePath == null && message.audioPath == null) {
            actions += StyledActionItem(tr("edit")) { editMessage(message) }
        }
        showStyledActionDialog(actions = actions)
    }

    private fun showReactionPicker(message: ChatMessage) {
        val values = arrayOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02", "\uD83D\uDD25", "\uD83D\uDE2E", "\u274C")
        val actions = values.map { value ->
            StyledActionItem(value) {
                val next = value.takeIf { it != "\u274C" }
                message.reaction = next
                if (message.localId > 0L) {
                    chatStore.updateMessageReaction(message.localId, next)
                } else {
                    rebuildStoredChat(currentChatKey(), messagesForChat(currentChatKey()))
                }
                chatAdapter.notifyDataSetChanged()
            }
        }
        showStyledActionDialog(actions = actions)
    }

    private fun forwardMessageToSaved(message: ChatMessage) {
        val author = usernameField.text.toString().prefixAt()
        when {
            message.imagePath != null -> {
                addImageMessage(
                    author = author,
                    imagePath = message.imagePath,
                    mine = true,
                    chatKey = CHAT_SAVED,
                    status = MessageStatus.READ
                )
            }
            message.audioPath != null -> {
                addAudioMessage(
                    author = author,
                    audioPath = message.audioPath,
                    mine = true,
                    chatKey = CHAT_SAVED,
                    status = MessageStatus.READ
                )
            }
            else -> saveLocalMessage(ChatMessage(author, message.body, true, status = MessageStatus.READ))
        }
        Toast.makeText(this, tr("forwarded_to_saved"), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteMessage(target: ChatMessage) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val checkbox = CheckBox(this).apply {
            text = tr("delete_for_everyone_q")
            setTextColor(BERRY_TEXT)
            isChecked = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = roundedDrawable(INPUT_SURFACE, dp(20), SOFT_PINK_STROKE)
            addView(terminalText(tr("delete_for_myself_q")).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                setPadding(0, 0, 0, dp(12))
            })
            addView(checkbox)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
                addView(terminalAction(tr("cancel")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                    background = roundedDrawable(Color.TRANSPARENT, dp(16), SOFT_PINK_STROKE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener { dialog.dismiss() }
                })
                addView(terminalAction(tr("delete")).apply {
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(STRAWBERRY_RED, dp(16))
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                    setOnClickListener {
                        dialog.dismiss()
                        deleteMessage(target, deleteForEveryone = checkbox.isChecked)
                    }
                })
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { dialog.dismiss() }
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
        content.setOnClickListener { }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun deleteMessage(target: ChatMessage, deleteForEveryone: Boolean) {
        if (deleteForEveryone) {
            sendDeleteMessageControl(target)
        }
        clearSendTimeout(target.localId)
        clearTextRetry(target.localId)
        val chatKey = currentChatKey()
        val removed = removeMessageEverywhere(target)
        if (!removed) {
            return
        }
        if (target.localId > 0L) {
            chatStore.deleteMessage(target.localId)
        } else {
            rebuildStoredChat(chatKey, messagesForChat(chatKey))
        }
        if (messagesForChat(chatKey).isEmpty()) {
            showChatMessages(chatKey)
        } else {
            chatAdapter.notifyDataSetChanged()
        }
    }

    private fun sendDeleteChatControl(peerIdText: String?) {
        val peer = peerIdText?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        meshService?.sendMessage(peer.toString(), "$CONTROL_PREFIX$CONTROL_DELETE_CHAT")
    }

    private fun sendDeleteMessageControl(message: ChatMessage) {
        if (savedMessagesSelected) return
        val bodyEncoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(message.body.toByteArray(Charsets.UTF_8))
        val kind = when {
            message.imagePath != null -> "i"
            message.audioPath != null -> "a"
            else -> "t"
        }
        val token = messageDeleteToken(message.body, kind)
        val ownerMineOnSender = if (message.mine) "1" else "0"
        val mediaName = when (kind) {
            "i" -> message.imagePath?.let { File(it).name }.orEmpty()
            "a" -> message.audioPath?.let { File(it).name }.orEmpty()
            else -> ""
        }
        val mediaNameEncoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mediaName.toByteArray(Charsets.UTF_8))
        val payload =
            "$CONTROL_PREFIX$CONTROL_DELETE_MESSAGE|${message.timestamp}|$kind|$bodyEncoded|$token|$ownerMineOnSender|$mediaNameEncoded"
        if (currentChatKey() == CHAT_EVERYONE) {
            meshService?.broadcastMessage(payload)
        } else {
            val peer = selectedRecipientId ?: currentChatKey()
                .removePrefix("peer:")
                .let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return
            meshService?.prepareChatWith(peer.toString())
            val first = meshService?.sendMessage(peer.toString(), payload)
            if (first is SendResult.Failed) {
                meshService?.prepareChatWith(peer.toString())
                meshService?.sendMessage(peer.toString(), payload)
            }
        }
    }

    private fun handleIncomingControl(sender: IncomingSender, body: String) {
        val payload = body.removePrefix(CONTROL_PREFIX)
        if (payload == CONTROL_DELETE_CHAT) {
            sender.nodeId?.let { peerId ->
                val chatKey = peerChatKey(peerId)
                chatStore.deleteChat(chatKey, peerId.toString())
                peerMessages.remove(chatKey)
                if (selectedRecipientId == peerId) {
                    showChatMessages(peerChatKey(peerId))
                    updateRecipientHint()
                    updateChatTitle()
                } else {
                    chatAdapter.notifyDataSetChanged()
                }
            }
            return
        }

        if (!payload.startsWith(CONTROL_DELETE_MESSAGE)) return
        val parts = payload.split("|")
        if (parts.size < 4) return
        val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: return
        val kind = when (parts.getOrNull(2).orEmpty()) {
            "1" -> "i"
            "0", "" -> "t"
            else -> parts.getOrNull(2).orEmpty()
        }
        val bodyValue = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(parts.getOrNull(3).orEmpty()), Charsets.UTF_8)
        }.getOrDefault("")
        val token = parts.getOrNull(4).orEmpty()
        val ownerMineOnSender = parts.getOrNull(5)?.let { it == "1" }
        val expectedMine = ownerMineOnSender?.let { !it }
        val mediaNameHint = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(parts.getOrNull(6).orEmpty()), Charsets.UTF_8)
        }.getOrDefault("").trim()

        val chatKey = incomingChatKey(sender)
        val buffer = messagesForChat(chatKey)
        val typedCandidates = buffer.filter { it ->
            matchesDeleteKind(it, kind)
        }
        val incomingCandidates = typedCandidates.filter { it ->
            (expectedMine == null || it.mine == expectedMine) &&
                matchesDeleteKind(it, kind)
        }

        var match = incomingCandidates.lastOrNull {
            it.timestamp == timestamp &&
                (if (kind == "t") it.body == bodyValue else true)
        }
        if (match == null && token.isNotBlank()) {
            match = incomingCandidates.lastOrNull {
                messageDeleteToken(it.body, kindForDelete(it)) == token
            }
        }
        if (match == null && mediaNameHint.isNotBlank()) {
            val normalizedHint = normalizeMediaName(mediaNameHint)
            match = incomingCandidates.lastOrNull { candidate ->
                val path = when (kind) {
                    "i" -> candidate.imagePath
                    "a" -> candidate.audioPath
                    else -> null
                } ?: return@lastOrNull false
                val candidateName = File(path).name
                val normalizedCandidate = normalizeMediaName(candidateName)
                normalizedCandidate == normalizedHint ||
                    normalizedCandidate.endsWith(normalizedHint) ||
                    normalizedHint.endsWith(normalizedCandidate)
            }
        }
        if (match == null && bodyValue.isNotBlank()) {
            match = incomingCandidates.lastOrNull { it.body == bodyValue }
        }
        if (match == null) {
            match = incomingCandidates.lastOrNull { it.timestamp == timestamp }
        }
        if (match == null) {
            match = typedCandidates.lastOrNull {
                (bodyValue.isBlank() || it.body == bodyValue) &&
                    (it.timestamp == timestamp || token.isBlank() || messageDeleteToken(it.body, kindForDelete(it)) == token)
            }
        }
        match ?: return

        val removed = removeMessageEverywhere(match)
        if (!removed) return
        if (match.localId > 0L) {
            chatStore.deleteMessage(match.localId)
        } else {
            rebuildStoredChat(chatKey, buffer)
        }
        chatAdapter.notifyDataSetChanged()
    }

    private fun editMessage(target: ChatMessage) {
        if (target.imagePath != null || target.audioPath != null) return
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val input = EditText(this).apply {
            setText(target.body)
            setSingleLine(false)
            maxLines = 4
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(INPUT_SURFACE, dp(16), SOFT_PINK_STROKE)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedDrawable(INPUT_SURFACE, dp(20), SOFT_PINK_STROKE)
            addView(terminalText(tr("edit")).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                setPadding(0, 0, 0, dp(10))
            })
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(12), 0, 0)
                addView(terminalAction(tr("cancel")).apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                    background = roundedDrawable(Color.TRANSPARENT, dp(16), SOFT_PINK_STROKE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener { dialog.dismiss() }
                })
                addView(terminalAction(tr("save")).apply {
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(STRAWBERRY_RED, dp(16))
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                    setOnClickListener {
                        val next = input.text?.toString()?.trim().orEmpty()
                        if (next.isBlank()) return@setOnClickListener
                        dialog.dismiss()
                        target.body = next
                        if (target.localId > 0L) {
                            chatStore.updateMessageBody(target.localId, next)
                        } else {
                            rebuildStoredChat(currentChatKey(), messagesForChat(currentChatKey()))
                        }
                        chatAdapter.notifyDataSetChanged()
                    }
                })
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { dialog.dismiss() }
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
        content.setOnClickListener { }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun showStyledActionDialog(actions: List<StyledActionItem>) {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = roundedDrawable(INPUT_SURFACE, dp(22), SOFT_PINK_STROKE)
            actions.forEachIndexed { index, item ->
                addView(terminalAction(item.label).apply {
                    textSize = 16f
                    setTextColor(if (item.destructive) STRAWBERRY_RED else BERRY_TEXT)
                    background = roundedDrawable(
                        if (item.destructive) 0x14FF4359 else 0x0AFF4359,
                        dp(14),
                        if (item.destructive) 0x55FF4359 else SOFT_PINK_STROKE
                    )
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener {
                        dialog.dismiss()
                        item.action()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(8)
                })
            }
            addView(terminalAction(tr("cancel")).apply {
                textSize = 15f
                setTextColor(BERRY_TEXT_DIM)
                background = roundedDrawable(Color.TRANSPARENT, dp(14), SOFT_PINK_STROKE)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { dialog.dismiss() }
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
        content.setOnClickListener { }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun removeMessageEverywhere(target: ChatMessage): Boolean {
        var removedAny = false
        fun MutableList<ChatMessage>.removeTarget() {
            val removed = removeAll {
                when {
                    target.localId > 0L -> it.localId == target.localId
                    target.messageId != null -> it.messageId == target.messageId
                    else -> it === target || (
                        it.timestamp == target.timestamp &&
                            it.body == target.body &&
                            it.mine == target.mine &&
                            ((it.imagePath == null) == (target.imagePath == null)) &&
                            ((it.audioPath == null) == (target.audioPath == null))
                        )
                }
            }
            removedAny = removedAny || removed
        }
        meshMessages.removeTarget()
        savedMessages.removeTarget()
        messages.removeTarget()
        peerMessages.values.forEach { it.removeTarget() }
        return removedAny
    }

    private fun messageDeleteToken(body: String, kind: String): String {
        val source = when (kind) {
            "i" -> "img"
            "a" -> "aud"
            else -> "txt:${body.trim()}"
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }.take(16)
    }

    private fun kindForDelete(message: ChatMessage): String = when {
        message.imagePath != null -> "i"
        message.audioPath != null -> "a"
        else -> "t"
    }

    private fun normalizeMediaName(name: String): String =
        name.substringAfter('_', name)
            .lowercase(Locale.getDefault())
            .trim()

    private fun matchesDeleteKind(message: ChatMessage, kind: String): Boolean = when (kind) {
        "i" -> message.imagePath != null
        "a" -> message.audioPath != null
        else -> message.imagePath == null && message.audioPath == null
    }

    private fun peerFingerprint(peerId: UUID): String =
        peerId.toString()
            .replace("-", "")
            .take(20)
            .uppercase(Locale.getDefault())

    private fun keepNicknamePrefix() {
        if (normalizingNickname) return
        val current = usernameField.text?.toString().orEmpty()
        if (current.startsWith("@") && current.length in 2..MAX_NICKNAME_LENGTH) return

        normalizingNickname = true
        val normalized = "@${current.removePrefix("@")}".take(MAX_NICKNAME_LENGTH)
        usernameField.setText(normalized)
        usernameField.setSelection(normalized.length.coerceAtLeast(1))
        normalizingNickname = false
    }

    private fun requestMissingPermissions() {
        val permissions = MeshPermissionPolicy.missingRequiredPermissions {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions, MESH_PERMISSIONS_REQUEST_CODE)
        } else {
            startAppAfterPermissions()
        }
    }

    private fun showPermissionIntro() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = roundedDrawable(SOFT_PINK_PANEL, dp(26), SOFT_PINK_STROKE)
            addView(terminalText(tr("enable_nearby_chat")).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalText(tr("permissions_intro_desc")).apply {
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalAction(tr("continue")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(STRAWBERRY_RED, dp(20))
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    requestMissingPermissions()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(FrameLayout(this).apply {
            setPadding(dp(22), dp(44), dp(22), dp(28))
            setBackgroundColor(0x33FFB7C5)
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startVoiceRecording()
            } else {
                Toast.makeText(this, tr("mic_permission_needed"), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode != MESH_PERMISSIONS_REQUEST_CODE) return

        if (hasRequiredPermissions()) {
            startAppAfterPermissions()
        } else {
            addMessage("system", "permissions denied: mesh radio offline", false)
            showPermissionRecovery()
        }
    }

    private fun startAppAfterPermissions() {
        if (!ensureBluetoothEnabled()) return
        if (MeshPermissionPolicy.requiresLocationServices() && !ensureLocationEnabled()) return
        startAndBindMeshService()
    }

    private fun ensureBluetoothEnabled(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val enabled = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        if (!enabled) {
            addMessage("system", "bluetooth disabled", false)
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return false
        }
        return true
    }

    private fun ensureLocationEnabled(): Boolean {
        val locationManager = getSystemService(LocationManager::class.java)
        val enabled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
        if (!enabled) {
            addMessage("system", "location disabled: bluetooth scan may be blocked", false)
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return false
        }
        return true
    }

    private fun hasRequiredPermissions(): Boolean =
        MeshPermissionPolicy.missingRequiredPermissions {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.isEmpty()

    private fun showPermissionRecovery() {
        Toast.makeText(
            this,
            tr("permissions_recovery_toast"),
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    private fun startAndBindMeshService() {
        if (serviceBound) return
        val intent = Intent(this, MeshNetworkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun refreshHeader() {
        counterView.text = (meshService?.peerCount() ?: 0).toString()
    }

    private fun showKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(usernameField.windowToken, 0)
    }

    private fun attachReplySwipe(view: View, message: ChatMessage) {
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > dp(72) && dy < dp(42)) {
                        setReplyTarget(message)
                    }
                }
            }
            false
        }
    }

    private fun terminalText(value: String): TextView =
        TextView(this).apply {
            text = value
            typeface = Typeface.DEFAULT
            setTextColor(BERRY_TEXT)
            includeFontPadding = false
        }

    private fun terminalAction(value: String): TextView =
        TextView(this).apply {
            text = value
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BERRY_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = Gravity.CENTER
        }

    private fun circleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun microphoneButtonDrawable(): Drawable =
        object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = STRAWBERRY_RED
                style = Paint.Style.FILL
            }
            private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }

            override fun draw(canvas: Canvas) {
                val b = bounds
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val r = minOf(b.width(), b.height()) / 2f
                canvas.drawCircle(cx, cy, r, fillPaint)

                val body = RectF(cx - dp(5), cy - dp(12), cx + dp(5), cy + dp(3))
                canvas.drawRoundRect(body, dp(5).toFloat(), dp(5).toFloat(), bodyPaint)
                val arc = RectF(cx - dp(10), cy - dp(5), cx + dp(10), cy + dp(11))
                canvas.drawArc(arc, 20f, 140f, false, iconPaint)
                canvas.drawLine(cx, cy + dp(12), cx, cy + dp(17), iconPaint)
                canvas.drawLine(cx - dp(7), cy + dp(17), cx + dp(7), cy + dp(17), iconPaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                iconPaint.alpha = alpha
                bodyPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                iconPaint.colorFilter = colorFilter
                bodyPaint.colorFilter = colorFilter
            }

            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

    private fun roundedDrawable(color: Int, cornerRadius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            this.cornerRadius = cornerRadius.toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun cursorDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(STRAWBERRY_RED)
            setSize(dp(2), dp(18))
        }

    private fun applySafeArea(root: View, inputBar: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = insets.systemWindowInsetTop
            val bottomInset = insets.systemWindowInsetBottom
            root.setPadding(dp(12), maxOf(dp(44), topInset + dp(8)), dp(12), dp(8))
            inputBar.setPadding(
                inputBar.paddingLeft,
                dp(8),
                inputBar.paddingRight,
                maxOf(dp(8), bottomInset + dp(8))
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun SendResult.toUiText(): String = when (this) {
        is SendResult.Sent -> "sent: $messageId"
        is SendResult.Queued -> "queued: $reason"
        is SendResult.Failed -> "failed: $error"
    }

    private fun String.prefixAt(): String =
        if (startsWith("@")) this else "@$this"

    private fun String.isScanNoise(): Boolean =
        startsWith("search people:")
            || startsWith("nearby search started")
            || startsWith("peer counter:")
            || startsWith("discovered:")
            || startsWith("secure session:")
            || startsWith("verified:")
            || startsWith("disconnected:")
            || startsWith("broadcast:")
            || startsWith("send to")
            || startsWith("image send:")
            || startsWith("message delivered:")
            || startsWith("message read:")
            || startsWith("wifi-direct")
            || startsWith("emulator relay")
            || startsWith("ble ")
            || startsWith("BLE ")

    private fun String.encodeStoredText(): String =
        replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")

    private fun String.decodeStoredText(): String =
        replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")

    private fun takePersistableUriPermissionIfPossible(uri: Uri, flags: Int) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
        }
    }

    private fun showImageProgress(text: String) {
        transferStatusView.text = text
        transferStatusView.visibility = View.VISIBLE
    }

    private fun hideImageProgress() {
        transferStatusView.visibility = View.GONE
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(s / 60L, s % 60L)
    }

    private fun recordingWave(ms: Long): String {
        val phase = ((ms / 220L) % 4L).toInt()
        return when (phase) {
            0 -> "▁▂▁"
            1 -> "▂▃▂"
            2 -> "▃▄▃"
            else -> "▂▁▂"
        }
    }

    private fun toggleAudioPlayback(path: String?, button: TextView) {
        val audioPath = path?.takeIf { it.isNotBlank() } ?: return
        if (!File(audioPath).exists()) return
        if (activePlayingPath == audioPath) {
            releaseAudioPlayer()
            button.text = tr("play")
            return
        }
        releaseAudioPlayer()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(audioPath)
            player.prepare()
            player.start()
        }.onFailure {
            player.release()
            button.text = tr("play")
            return
        }
        activePlayer = player
        activePlayingPath = audioPath
        button.text = tr("stop")
        player.setOnCompletionListener {
            releaseAudioPlayer()
            chatAdapter.notifyDataSetChanged()
        }
    }

    private fun audioDurationLabel(path: String?): String {
        val audioPath = path?.takeIf { it.isNotBlank() } ?: return "00:00"
        audioDurationCache[audioPath]?.let { return it }
        val durationMs = runCatching {
            val probe = MediaPlayer()
            probe.setDataSource(audioPath)
            probe.prepare()
            val d = probe.duration
            probe.release()
            d
        }.getOrDefault(0)
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val label = "%02d:%02d".format(totalSec / 60, totalSec % 60)
        audioDurationCache[audioPath] = label
        return label
    }

    private fun releaseAudioPlayer() {
        runCatching { activePlayer?.stop() }
        runCatching { activePlayer?.reset() }
        runCatching { activePlayer?.release() }
        activePlayer = null
        activePlayingPath = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun calculateChatImageSize(bitmap: Bitmap?): Pair<Int, Int> {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return (resources.displayMetrics.widthPixels * 0.58f).toInt() to dp(180)
        }

        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val maxWideWidth = (resources.displayMetrics.widthPixels * 0.58f).toInt()
        val maxPortraitHeight = dp(380)
        val maxLandscapeHeight = dp(220)

        return if (aspect < 0.72f) {
            val height = maxPortraitHeight
            val width = (height * aspect).toInt().coerceIn(dp(96), maxWideWidth)
            width to height
        } else {
            val width = maxWideWidth
            val height = (width / aspect).toInt().coerceIn(dp(120), maxLandscapeHeight)
            width to height
        }
    }

}
