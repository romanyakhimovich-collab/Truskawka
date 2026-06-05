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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mesh.SendResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
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
    private var isRecordingVoice = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt: Long = 0L
    private var activePlayer: MediaPlayer? = null
    private var activePlayingPath: String? = null
    private val audioDurationCache = mutableMapOf<String, String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf(
        ChatMessage("system", "offline mesh ready", false),
        ChatMessage("@relay", "waiting for nearby nodes", false)
    )
    private val meshMessages = messages.toMutableList()
    private val savedMessages = mutableListOf<ChatMessage>()
    private val peerMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private lateinit var chatAdapter: ChatAdapter

    private lateinit var usernameField: EditText
    private lateinit var statusGroup: LinearLayout
    private lateinit var counterView: TextView
    private lateinit var chatTitleView: TextView
    private lateinit var networkStatusView: TextView
    private lateinit var chatList: ListView
    private lateinit var transferStatusView: TextView
    private lateinit var messageInput: EditText
    private lateinit var actionButton: TextView
    private lateinit var chatStore: ChatStore
    private val messageTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val messageDateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    private val voiceStopRunnable = Runnable {
        if (isRecordingVoice) {
            stopVoiceRecordingAndSend(forceSend = true)
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
                updateMessageStatus(line.substringAfter(":").trim(), MessageStatus.DELIVERED)
            } else if (line.startsWith("message read:")) {
                updateMessageStatus(line.substringAfter(":").trim(), MessageStatus.READ)
            } else if (!line.isScanNoise()) {
                addMessage("mesh", line, false)
            }
            refreshHeader()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            meshService = (service as MeshNetworkService.LocalBinder).service()
            meshService?.addLogListener(logListener)
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
        applyThemePalette()
        chatStore = ChatStore(this)
        loadStoredMessages()
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
        if (hasRequiredPermissions() && !serviceBound) {
            startAppAfterPermissions()
        }
    }

    override fun onDestroy() {
        meshService?.removeLogListener(logListener)
        stopVoiceRecording(cleanupOnly = true)
        releaseAudioPlayer()
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

        chatAdapter = ChatAdapter(messages)
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

        val inputBar = buildInputBar()
        root.addView(inputBar)
        applySafeArea(root, inputBar)
        setContentView(root)
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
                    background = roundedDrawable(INPUT_SURFACE, dp(14), SOFT_PINK_STROKE)
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
                Gravity.LEFT or Gravity.CENTER_VERTICAL
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
                background = roundedDrawable(INPUT_SURFACE, dp(24), PINK_SHADOW_STROKE)
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
                background = roundedDrawable(0x26FF4359, dp(22), 0x55FF4359)
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
                setOnTouchListener { v, event ->
                    if (messageInput.text.toString().isNotBlank()) return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startVoiceRecording()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.performClick()
                            if (isRecordingVoice) {
                                stopVoiceRecordingAndSend(forceSend = false)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            addView(actionButton)
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

    @Deprecated("Deprecated Android callback is enough for this minimal Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMAGE_PICK_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        takePersistableUriPermissionIfPossible(uri, data.flags)
        showSelectedImageComposer(uri)
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
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(terminalText(tr("preview_image")).apply {
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(terminalAction("X").apply {
                    textSize = 18f
                    setOnClickListener { dialog.dismiss() }
                })
            })
            addView(ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
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
        val fileName = queryDisplayName(uri)
        showImageProgress(tr("preparing_image"))

        thread(name = "image-compress-send") {
            val prepared = prepareImageForTransfer(uri, fileName)
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

            val localPath = copyImageToLocalFile(prepared.fileName, prepared.bytes).absolutePath
            val author = usernameField.text.toString().prefixAt()

            runOnUiThread {
                if (savedMessagesSelected) {
                    saveLocalMessage(ChatMessage(author, "", true, localPath, status = MessageStatus.READ))
                    if (caption.isNotBlank()) {
                        saveLocalMessage(ChatMessage(author, caption, true, status = MessageStatus.READ))
                    }
                    hideImageProgress()
                } else {
                    addImageMessage(author, localPath, mine = true)
                    showImageProgress(tr("sending_image"))
                }
            }

            if (savedMessagesSelected) return@thread

            val result = meshService?.sendImage(
                selectedRecipientId?.toString(),
                prepared.fileName,
                prepared.mimeType,
                prepared.bytes
            ) ?: SendResult.Failed("service offline")
            runOnUiThread {
                if (result is SendResult.Failed) {
                    hideImageProgress()
                    addMessage("mesh", result.toUiText(), false)
                } else {
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
        val author = usernameField.text.toString().prefixAt()
        val targetId = selectedRecipientId
        if (savedMessagesSelected) {
            saveLocalMessage(ChatMessage(author, text, true, status = MessageStatus.READ))
            return
        }
        val localMessage = addMessage(
            author = author,
            body = text,
            mine = true,
            status = if (targetId == null) null else MessageStatus.SENDING
        )
        val result = if (targetId == null) {
            meshService?.broadcastMessage(text)
        } else {
            meshService?.sendMessage(targetId.toString(), text)
        } ?: SendResult.Failed("service offline")
        when (result) {
            is SendResult.Sent -> {
                if (targetId != null) {
                    localMessage.messageId = result.messageId
                    persistChatMessageIdentity(localMessage)
                }
            }
            is SendResult.Failed -> addMessage("mesh", result.toUiText(), false)
            is SendResult.Queued -> Unit
        }
    }

    private fun startVoiceRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions()
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
        showImageProgress(tr("recording_voice"))
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
            hideImageProgress()
            return
        }
        recordingStartedAt = 0L
        hideImageProgress()
    }

    private fun sendPreparedVoice(file: File) {
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
        val localMessage = addAudioMessage(
            author = author,
            audioPath = file.absolutePath,
            mine = true,
            timestamp = timestamp,
            status = if (selectedRecipientId == null) null else MessageStatus.SENDING
        )
        showImageProgress(tr("sending_voice"))
        thread(name = "voice-send") {
            val result = meshService?.sendImage(
                selectedRecipientId?.toString(),
                file.name,
                "audio/mp4",
                bytes
            ) ?: SendResult.Failed("service offline")
            runOnUiThread {
                if (result is SendResult.Failed) {
                    localMessage.status = null
                    persistChatMessageIdentity(localMessage)
                    addMessage("mesh", result.toUiText(), false)
                } else if (result is SendResult.Sent && selectedRecipientId != null) {
                    localMessage.messageId = result.messageId
                    persistChatMessageIdentity(localMessage)
                }
                showImageProgress(if (result is SendResult.Failed) tr("voice_send_failed") else tr("voice_sent"))
                mainHandler.postDelayed({ hideImageProgress() }, 1_200)
            }
        }
    }

    private fun updateActionButton() {
        actionButton.text = when {
            isRecordingVoice -> tr("recording_short")
            messageInput.text.toString().isBlank() -> tr("mic_icon")
            else -> ">"
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
            background = roundedDrawable(if (selected) STRAWBERRY_RED else Color.TRANSPARENT, dp(20))
            setOnClickListener { onClick() }
        }

    private enum class PageTab { NEW_CONTACTS, CONTACTS, PROFILE, SETTINGS }

    private enum class AppLanguage(val code: String, val label: String) {
        EN("en", "EN"),
        PL("pl", "PL"),
        ES("es", "ES"),
        RU("ru", "RU");

        companion object {
            fun fromCode(code: String?): AppLanguage =
                entries.firstOrNull { it.code == code } ?: EN
        }
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
            background = roundedDrawable(INPUT_SURFACE, dp(24), SOFT_PINK_STROKE)

            addView(chatListBottomItem(tr("new_contacts"), selected == PageTab.NEW_CONTACTS, onNewContacts), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(4)
            })
            addView(chatListBottomItem(tr("contacts"), selected == PageTab.CONTACTS, onContacts), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(
                if (selected) ACCENT_PINK else INPUT_SURFACE,
                dp(20),
                if (selected) STRAWBERRY_RED else SOFT_PINK_STROKE
            )
            addView(TextView(this@MainActivity).apply {
                text = when (summary.kind) {
                    ChatKind.SAVED.name -> "*"
                    ChatKind.EVERYONE.name -> "#"
                    else -> if (summary.verified) "ok" else "@"
                }
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(STRAWBERRY_RED)
                background = circleDrawable(0x33FFB7C5)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(12)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(terminalText(displayTitle).apply {
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                    maxLines = 1
                })
                if (summary.pinned) {
                    addView(terminalText(tr("pinned")).apply {
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(STRAWBERRY_RED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                addView(terminalText(preview).apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(terminalText(presence.first).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (presence.second) LEAF_GREEN else BERRY_TEXT_DIM)
                background = roundedDrawable(0x14FF4359, dp(12), SOFT_PINK_STROKE)
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
                }, LinearLayout.LayoutParams(dp(10), dp(10)))
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
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

        val nearbyTitle = terminalText("${tr("patch")} ${meshService?.peerCount() ?: 0}").apply {
            textSize = 20f
            setTextColor(BERRY_TEXT)
        }
        val transportStatus = terminalText(meshService?.meshTransportStatus() ?: tr("mesh_starting")).apply {
            textSize = 12f
            setTextColor(BERRY_TEXT_DIM)
            setPadding(0, 0, 0, dp(10))
        }
        val radarView = PatchRadarView(this).apply {
            minimumHeight = dp(180)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180)
            ).apply { bottomMargin = dp(12) }
            background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
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
            nearbyTitle.text = "${tr("patch")} $peerCount"
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
            radarView.setPeerCounts(directPeers.size, meshPeers.size)

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
        root.addView(radarView)

        val contentScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(terminalText(tr("patch_upper")).apply {
                textSize = 15f
                setPadding(0, dp(18), 0, dp(10))
                setTextColor(BERRY_TEXT)
            })
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
                gravity = Gravity.RIGHT
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
            addView(terminalAction(tr("close")).apply {
                textSize = 15f
                setPadding(0, dp(14), 0, 0)
                setOnClickListener { dialog.dismiss() }
            })
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
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(terminalAction("X").apply {
                textSize = 18f
                setOnClickListener { dialog.dismiss() }
            })
        })
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
        val sender = parseIncomingSender(meta.substringBefore(" at ", "@peer"))
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestampAndBody = meta.substringAfter(" at ", "")
        val timestamp = timestampAndBody.substringBefore(": ", "")
            .toLongOrNull()
            ?: System.currentTimeMillis()
        val body = timestampAndBody.substringAfter(": ", line)
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
        val sender = parseIncomingSender(meta.substringBefore(" at ", "@peer"))
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestampAndPayload = meta.substringAfter(" at ", "")
        val timestamp = timestampAndPayload.substringBefore(": ", "")
            .toLongOrNull()
            ?: System.currentTimeMillis()
        val payload = timestampAndPayload.substringAfter(": ", line.substringAfter(": ", ""))
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
        val sender = parseIncomingSender(meta.substringBefore(" at ", "@peer"))
        if (isSelfSender(sender.nodeId)) return
        val author = sender.label
        val timestampAndPayload = meta.substringAfter(" at ", "")
        val timestamp = timestampAndPayload.substringBefore(": ", "")
            .toLongOrNull()
            ?: System.currentTimeMillis()
        val payload = timestampAndPayload.substringAfter(": ", line.substringAfter(": ", ""))
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
                        if (message.status != MessageStatus.READ) {
                            message.status = status
                            changed = true
                        }
                    }
                }
        if (changed) {
            chatStore.updateStatusByMeshMessageId(messageId.toString(), status.name)
            chatAdapter.notifyDataSetChanged()
        }
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
                val parts = line.split("\t", limit = 6)
                val author = parts.firstOrNull().orEmpty().ifBlank { "@me" }
                val body = parts.getOrNull(1)?.decodeStoredText() ?: return@mapNotNull null
                val imagePath = parts.getOrNull(2)?.decodeStoredText()?.ifBlank { null }
                val audioPath = parts.getOrNull(5)?.decodeStoredText()?.ifBlank { null }
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
        if (mine) currentNickname else author

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
            addView(terminalAction(tr("close")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                background = roundedDrawable(ACCENT_PINK, dp(18), SOFT_PINK_STROKE)
                setOnClickListener { dialog.dismiss() }
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

    private fun showOwnProfilePage() {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val originalName = meshService?.getNickname()?.take(MAX_NICKNAME_LENGTH) ?: currentNickname
        val input = EditText(this).apply {
            setText(getDisplayName())
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

            addView(terminalText(tr("profile")).apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })

            addView(terminalText(tr("original_name_fixed")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(16), 0, dp(6))
            })
            addView(terminalText(originalName).apply {
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                background = roundedDrawable(INPUT_SURFACE, dp(18), SOFT_PINK_STROKE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })

            addView(terminalText(tr("display_name_editable")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(input)

            addView(terminalAction(tr("save")).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(STRAWBERRY_RED, dp(18))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    setDisplayName(input.text?.toString().orEmpty())
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

    private fun showSettingsPage() {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        var draftDark = darkThemeEnabled
        var draftLanguage = selectedLanguage

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(20))
            setBackgroundColor(CREAM_BACKGROUND)

            addView(terminalText(tr("settings")).apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            })

            addView(terminalText(tr("theme")).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(16), 0, dp(8))
            })
        }

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lightButton = chatListBottomItem(tr("theme_light"), !draftDark) { }
        val darkButton = chatListBottomItem(tr("theme_dark"), draftDark) { }
        lightButton.setOnClickListener {
            draftDark = false
            lightButton.background = roundedDrawable(STRAWBERRY_RED, dp(20))
            lightButton.setTextColor(Color.WHITE)
            darkButton.background = roundedDrawable(Color.TRANSPARENT, dp(20))
            darkButton.setTextColor(BERRY_TEXT_DIM)
        }
        darkButton.setOnClickListener {
            draftDark = true
            darkButton.background = roundedDrawable(STRAWBERRY_RED, dp(20))
            darkButton.setTextColor(Color.WHITE)
            lightButton.background = roundedDrawable(Color.TRANSPARENT, dp(20))
            lightButton.setTextColor(BERRY_TEXT_DIM)
        }
        themeRow.addView(lightButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        themeRow.addView(darkButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(themeRow)

        root.addView(terminalText(tr("language")).apply {
            textSize = 12f
            setTextColor(BERRY_TEXT_DIM)
            setPadding(0, dp(16), 0, dp(8))
        })

        val langButtons = linkedMapOf<AppLanguage, TextView>()
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        AppLanguage.entries.forEachIndexed { index, lang ->
            val button = chatListBottomItem(lang.label, draftLanguage == lang) { }
            button.setOnClickListener {
                draftLanguage = lang
                langButtons.forEach { (candidate, view) ->
                    if (candidate == lang) {
                        view.background = roundedDrawable(STRAWBERRY_RED, dp(20))
                        view.setTextColor(Color.WHITE)
                    } else {
                        view.background = roundedDrawable(Color.TRANSPARENT, dp(20))
                        view.setTextColor(BERRY_TEXT_DIM)
                    }
                }
            }
            langButtons[lang] = button
            langRow.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (index > 0) marginStart = dp(4)
                if (index < AppLanguage.entries.lastIndex) marginEnd = dp(4)
            })
        }
        root.addView(langRow)

        root.addView(terminalAction(tr("save")).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(STRAWBERRY_RED, dp(18))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                darkThemeEnabled = draftDark
                selectedLanguage = draftLanguage
                saveUiSettings()
                applyThemePalette()
                dialog.dismiss()
                recreate()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(18)
        })

        root.addView(View(this), LinearLayout.LayoutParams(
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

    private fun loadUiSettings() {
        val prefs = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        darkThemeEnabled = prefs.getBoolean(UI_SETTINGS_THEME_DARK, false)
        selectedLanguage = AppLanguage.fromCode(prefs.getString(UI_SETTINGS_LANGUAGE, AppLanguage.EN.code))
    }

    private fun saveUiSettings() {
        getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(UI_SETTINGS_THEME_DARK, darkThemeEnabled)
            .putString(UI_SETTINGS_LANGUAGE, selectedLanguage.code)
            .apply()
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

    private fun tr(key: String): String {
        val en = mapOf(
            "chats" to "Chats",
            "new_contacts" to "New contacts",
            "contacts" to "Contacts",
            "profile" to "Profile",
            "settings" to "Settings",
            "no_chats_yet" to "No chats yet",
            "theme" to "Theme",
            "theme_light" to "Light",
            "theme_dark" to "Black pink",
            "language" to "Language",
            "save" to "Save",
            "profile_updated" to "Profile updated",
            "original_name_fixed" to "Original name (fixed)",
            "display_name_editable" to "Display name (editable)",
            "display_name" to "Display name",
            "everyone" to "Everyone",
            "saved_messages" to "Saved messages",
            "today" to "Today",
            "yesterday" to "Yesterday",
            "hint_save_message" to "save a message...",
            "hint_type_message" to "type a message...",
            "hint_message" to "message",
            "online_now" to "online now",
            "last_seen_prefix" to "Last seen:",
            "status_searching_nearby" to "Searching nearby...",
            "status_mesh_online" to "Mesh online",
            "status_person_found" to "Nearby person found",
            "status_secure_ready" to "Secure chat ready",
            "status_permissions_needed" to "Permissions needed",
            "status_bt_disabled" to "Bluetooth disabled",
            "delivered" to "Delivered",
            "read" to "Read",
            "offline" to "Offline",
            "patch" to "Patch",
            "patch_upper" to "PATCH",
            "mesh_starting" to "Mesh starting...",
            "search_in_patch" to "search in patch...",
            "direct_in_range" to "Direct in range",
            "no_direct_peers" to "No direct peers in Bluetooth range",
            "no_direct_matches" to "No direct matches",
            "direct_ble" to "direct BLE",
            "verified" to "Verified",
            "reachable_via_hops" to "Reachable via hops",
            "no_multihop_routes" to "No multi-hop routes discovered yet",
            "no_hop_matches" to "No hop matches",
            "via" to "via",
            "hops" to "hops",
            "photo" to "photo",
            "private_notes" to "private notes for yourself",
            "nearby_public_mesh" to "nearby public mesh",
            "verified_contact" to "verified contact",
            "tap_open_chat" to "tap to open chat",
            "private_local_chat_desc" to "Private local chat stored on this device.",
            "broadcast_chat_desc" to "Broadcast chat for everyone nearby.",
            "peer_id" to "Peer ID",
            "saved_auto_read" to "Saved messages are automatically marked as read.",
            "messages_public_mesh" to "Messages here are public to nearby mesh users.",
            "contact_verified_local" to "This contact is marked as verified on this device.",
            "compare_code_verify" to "Compare the code with your friend, then mark it verified.",
            "search_messages" to "Search messages",
            "mark_verified" to "Mark as verified",
            "contact_verified" to "Contact verified",
            "close" to "Close",
            "delete" to "Delete",
            "edit" to "Edit",
            "cancel" to "Cancel",
            "delete_for_everyone_q" to "delete for everyone?",
            "delete_for_myself_q" to "Delete for myself?",
            "search_messages_hint" to "search messages...",
            "type_to_search_chat" to "Type to search this chat",
            "no_matches" to "No matches",
            "search" to "Search",
            "seen_recently" to "seen recently",
            "seen" to "seen",
            "min_ago" to "min ago",
            "h_ago" to "h ago",
            "d_ago" to "d ago",
            "local" to "local",
            "online" to "online",
            "pin" to "Pin",
            "unpin" to "Unpin",
            "pinned" to "pinned",
            "saved_empty" to "saved messages are empty",
            "broadcast_empty" to "broadcast is empty",
            "chat_empty" to "chat is empty",
            "grow" to "Grow",
            "grow_desc" to "Bring phones close to exchange local keys and join each other's patch.",
            "start_discovery" to "Start discovery",
            "scan_nearby_ble" to "scan nearby BLE mesh nodes",
            "searching_nearby_patch" to "Searching nearby patch",
            "open_patch" to "Open patch",
            "show_direct_hop" to "show direct and hop peers",
            "back_to_chats" to "Back to chats",
            "start_chatting" to "Start chatting",
            "choose_nickname" to "Choose a nickname",
            "welcome_truskawka" to "Welcome to Truskawka",
            "choose_mesh_nickname" to "Choose your mesh nickname",
            "nickname_rules" to "The @ stays fixed. Max 12 characters.",
            "nickname_change_online_only" to "Nickname can be changed when mesh is online",
            "nickname_change_once_week" to "Nickname can be changed once a week",
            "could_not_read_image" to "Could not read image",
            "image_too_large" to "Image is too large",
            "incoming_image_unavailable" to "incoming image not available on device",
            "incoming_audio_unavailable" to "incoming voice message not available on device",
            "status_sprouting" to "Sprouting",
            "status_ripe" to "Ripe",
            "broadcast" to "Broadcast",
            "enable_nearby_chat" to "Enable nearby chat",
            "permissions_intro_desc" to "Truskawka needs Bluetooth, Nearby devices, Location, and Microphone access so mesh radio can discover phones and send voice notes. Internet and router Wi-Fi are not required.",
            "continue" to "Continue",
            "permissions_recovery_toast" to "Allow Bluetooth, Nearby devices, Location, and Microphone for mesh radio",
            "preparing_image" to "preparing image...",
            "sending_image" to "sending image...",
            "image_sent" to "image sent",
            "image_received" to "image received",
            "you" to "You",
            "direct" to "Direct",
            "mesh_hops" to "Mesh hops"
            ,
            "send" to "Send",
            "add_caption" to "Add a caption...",
            "preview_image" to "Image preview",
            "voice_message" to "voice message",
            "recording_voice" to "recording voice...",
            "sending_voice" to "sending voice...",
            "voice_sent" to "voice sent",
            "voice_received" to "voice received",
            "voice_send_failed" to "voice send failed",
            "voice_too_large" to "voice message is too large",
            "voice_too_short" to "hold to record a bit longer",
            "voice_read_failed" to "could not read recorded voice",
            "play" to "Play",
            "stop" to "Stop",
            "mic_permission_needed" to "Microphone permission is required",
            "voice_record_failed" to "Could not start recording",
            "mic" to "MIC",
            "mic_icon" to "\uD83C\uDFA4",
            "recording_short" to "REC"
        )
        val ru = mapOf(
            "chats" to "Чаты",
            "new_contacts" to "Новые контакты",
            "contacts" to "Контакты",
            "profile" to "Профиль",
            "settings" to "Настройки",
            "no_chats_yet" to "Чатов пока нет",
            "theme" to "Тема",
            "theme_light" to "Светлая",
            "theme_dark" to "Черно-розовая",
            "language" to "Язык",
            "save" to "Сохранить",
            "profile_updated" to "Профиль обновлен",
            "original_name_fixed" to "Оригинальное имя (фиксированное)",
            "display_name_editable" to "Отображаемое имя (изменяемое)",
            "display_name" to "Отображаемое имя",
            "everyone" to "Все",
            "saved_messages" to "Избранное",
            "today" to "Сегодня",
            "yesterday" to "Вчера",
            "hint_save_message" to "сохранить сообщение...",
            "hint_type_message" to "введите сообщение...",
            "hint_message" to "сообщение",
            "online_now" to "в сети",
            "last_seen_prefix" to "Был(а) в сети:",
            "status_searching_nearby" to "Поиск рядом...",
            "status_mesh_online" to "Mesh онлайн",
            "status_person_found" to "Найден человек рядом",
            "status_secure_ready" to "Защищенный чат готов",
            "status_permissions_needed" to "Нужны разрешения",
            "status_bt_disabled" to "Bluetooth выключен",
            "delivered" to "Доставлено",
            "read" to "Прочитано",
            "offline" to "Оффлайн",
            "patch" to "Патч",
            "patch_upper" to "ПАТЧ",
            "mesh_starting" to "Mesh запускается...",
            "search_in_patch" to "поиск в патче...",
            "direct_in_range" to "Прямо в зоне",
            "no_direct_peers" to "Нет прямых устройств в зоне Bluetooth",
            "no_direct_matches" to "Нет прямых совпадений",
            "direct_ble" to "прямой BLE",
            "verified" to "Подтверждено",
            "reachable_via_hops" to "Доступно через хопы",
            "no_multihop_routes" to "Маршруты через хопы пока не найдены",
            "no_hop_matches" to "Нет совпадений по хопам",
            "via" to "через",
            "hops" to "хопов",
            "photo" to "фото",
            "private_notes" to "личные заметки",
            "nearby_public_mesh" to "публичный nearby mesh",
            "verified_contact" to "подтвержденный контакт",
            "tap_open_chat" to "нажмите, чтобы открыть чат",
            "private_local_chat_desc" to "Локальный приватный чат на этом устройстве.",
            "broadcast_chat_desc" to "Публичный чат для всех рядом.",
            "peer_id" to "Peer ID",
            "saved_auto_read" to "Избранное автоматически считается прочитанным.",
            "messages_public_mesh" to "Сообщения здесь публичны для nearby mesh.",
            "contact_verified_local" to "Этот контакт помечен как подтвержденный на этом устройстве.",
            "compare_code_verify" to "Сверьте код с другом и подтвердите контакт.",
            "search_messages" to "Поиск сообщений",
            "mark_verified" to "Отметить как подтвержденный",
            "contact_verified" to "Контакт подтвержден",
            "close" to "Закрыть",
            "delete" to "Удалить",
            "edit" to "Изменить",
            "cancel" to "Отмена",
            "delete_for_everyone_q" to "удалить для всех?",
            "delete_for_myself_q" to "Удалить только у себя?",
            "search_messages_hint" to "поиск сообщений...",
            "type_to_search_chat" to "Введите текст для поиска в чате",
            "no_matches" to "Совпадений нет",
            "search" to "Поиск",
            "seen_recently" to "был(а) недавно",
            "seen" to "был(а)",
            "min_ago" to "мин назад",
            "h_ago" to "ч назад",
            "d_ago" to "д назад",
            "local" to "локально",
            "online" to "онлайн",
            "pin" to "Закрепить",
            "unpin" to "Открепить",
            "pinned" to "закреплено",
            "saved_empty" to "избранное пусто",
            "broadcast_empty" to "эфир пуст",
            "chat_empty" to "чат пуст",
            "grow" to "Grow",
            "grow_desc" to "Поднесите телефоны ближе для обмена ключами и входа в патч.",
            "start_discovery" to "Начать поиск",
            "scan_nearby_ble" to "скан nearby BLE mesh узлов",
            "searching_nearby_patch" to "Идет поиск nearby patch",
            "open_patch" to "Открыть патч",
            "show_direct_hop" to "показать прямые и hop узлы",
            "back_to_chats" to "Назад к чатам",
            "start_chatting" to "Начать чат",
            "choose_nickname" to "Выберите никнейм",
            "welcome_truskawka" to "Добро пожаловать в Truskawka",
            "choose_mesh_nickname" to "Выберите mesh никнейм",
            "nickname_rules" to "Символ @ фиксирован. Максимум 12 символов.",
            "nickname_change_online_only" to "Ник можно менять, когда mesh онлайн",
            "nickname_change_once_week" to "Ник можно менять раз в неделю",
            "could_not_read_image" to "Не удалось прочитать изображение",
            "image_too_large" to "Изображение слишком большое",
            "incoming_image_unavailable" to "полученное изображение недоступно на устройстве",
            "incoming_audio_unavailable" to "полученное голосовое недоступно на устройстве",
            "status_sprouting" to "В пути",
            "status_ripe" to "Доставлено",
            "broadcast" to "Эфир",
            "enable_nearby_chat" to "Включить nearby чат",
            "permissions_intro_desc" to "Truskawka требует полный доступ к Bluetooth, Nearby devices и Location, чтобы mesh radio мог рекламироваться и сканировать телефоны рядом. Интернет и роутерный Wi-Fi не нужны.",
            "continue" to "Продолжить",
            "permissions_recovery_toast" to "Разрешите Bluetooth, Nearby devices и Location для mesh radio",
            "preparing_image" to "подготовка изображения...",
            "sending_image" to "отправка изображения...",
            "image_sent" to "изображение отправлено",
            "image_received" to "изображение получено",
            "you" to "Вы",
            "direct" to "Прямые",
            "mesh_hops" to "Mesh хопы"
            ,
            "send" to "Отправить",
            "add_caption" to "Добавить подпись...",
            "preview_image" to "Предпросмотр изображения",
            "voice_message" to "голосовое сообщение",
            "recording_voice" to "запись голосового...",
            "sending_voice" to "отправка голосового...",
            "voice_sent" to "голосовое отправлено",
            "voice_received" to "голосовое получено",
            "voice_send_failed" to "ошибка отправки голосового",
            "voice_too_large" to "голосовое слишком большое",
            "voice_too_short" to "удерживайте кнопку чуть дольше",
            "voice_read_failed" to "не удалось прочитать запись",
            "play" to "Слушать",
            "stop" to "Стоп",
            "mic_permission_needed" to "Нужно разрешение на микрофон",
            "voice_record_failed" to "Не удалось начать запись",
            "mic" to "МИК",
            "mic_icon" to "\uD83C\uDFA4",
            "recording_short" to "ЗАП"
        )
        val pl = mapOf(
            "chats" to "Czaty",
            "new_contacts" to "Nowe kontakty",
            "contacts" to "Kontakty",
            "profile" to "Profil",
            "settings" to "Ustawienia",
            "no_chats_yet" to "Brak czatow",
            "theme" to "Motyw",
            "theme_light" to "Jasny",
            "theme_dark" to "Czarno-rozowy",
            "language" to "Jezyk",
            "save" to "Zapisz",
            "profile_updated" to "Profil zaktualizowany",
            "original_name_fixed" to "Oryginalna nazwa (stala)",
            "display_name_editable" to "Nazwa wyswietlana (edytowalna)",
            "display_name" to "Nazwa wyswietlana",
            "everyone" to "Wszyscy",
            "saved_messages" to "Zapisane",
            "today" to "Dzis",
            "yesterday" to "Wczoraj",
            "hint_save_message" to "zapisz wiadomosc...",
            "hint_type_message" to "napisz wiadomosc...",
            "hint_message" to "wiadomosc",
            "online_now" to "online",
            "last_seen_prefix" to "Ostatnio widziany:",
            "status_searching_nearby" to "Szukanie w poblizu...",
            "status_mesh_online" to "Mesh online",
            "status_person_found" to "Znaleziono osobe",
            "status_secure_ready" to "Bezpieczny chat gotowy",
            "status_permissions_needed" to "Wymagane uprawnienia",
            "status_bt_disabled" to "Bluetooth wylaczony",
            "delivered" to "Dostarczono",
            "read" to "Przeczytano",
            "offline" to "Offline",
            "patch" to "Patch",
            "patch_upper" to "PATCH",
            "mesh_starting" to "Mesh startuje...",
            "search_in_patch" to "szukaj w patch...",
            "direct_in_range" to "Bezposrednio w zasiegu",
            "no_direct_peers" to "Brak bezposrednich urzadzen w zasiegu Bluetooth",
            "no_direct_matches" to "Brak bezposrednich dopasowan",
            "direct_ble" to "bezposredni BLE",
            "verified" to "Zweryfikowano",
            "reachable_via_hops" to "Dostepne przez hop",
            "no_multihop_routes" to "Brak tras wielohopowych",
            "no_hop_matches" to "Brak dopasowan hop",
            "via" to "przez",
            "hops" to "hop",
            "photo" to "zdjecie",
            "private_notes" to "prywatne notatki",
            "nearby_public_mesh" to "publiczny nearby mesh",
            "verified_contact" to "zweryfikowany kontakt",
            "tap_open_chat" to "dotknij, aby otworzyc chat",
            "private_local_chat_desc" to "Prywatny lokalny chat na tym urzadzeniu.",
            "broadcast_chat_desc" to "Publiczny chat dla wszystkich w poblizu.",
            "peer_id" to "Peer ID",
            "saved_auto_read" to "Zapisane wiadomosci sa automatycznie przeczytane.",
            "messages_public_mesh" to "Wiadomosci tutaj sa publiczne dla nearby mesh.",
            "contact_verified_local" to "Ten kontakt jest zweryfikowany na tym urzadzeniu.",
            "compare_code_verify" to "Porownaj kod z znajomym i oznacz jako zweryfikowany.",
            "search_messages" to "Szukaj wiadomosci",
            "mark_verified" to "Oznacz jako zweryfikowany",
            "contact_verified" to "Kontakt zweryfikowany",
            "close" to "Zamknij",
            "delete" to "Usun",
            "edit" to "Edytuj",
            "cancel" to "Anuluj",
            "delete_for_everyone_q" to "usun dla wszystkich?",
            "delete_for_myself_q" to "Usun tylko u mnie?",
            "search_messages_hint" to "szukaj wiadomosci...",
            "type_to_search_chat" to "Wpisz, aby szukac w tym chacie",
            "no_matches" to "Brak wynikow",
            "search" to "Szukaj",
            "seen_recently" to "widziany niedawno",
            "seen" to "widziany",
            "min_ago" to "min temu",
            "h_ago" to "h temu",
            "d_ago" to "d temu",
            "local" to "lokalnie",
            "online" to "online",
            "pin" to "Przypnij",
            "unpin" to "Odepnij",
            "pinned" to "przypiete",
            "saved_empty" to "zapisane wiadomosci sa puste",
            "broadcast_empty" to "broadcast jest pusty",
            "chat_empty" to "chat jest pusty",
            "grow" to "Grow",
            "grow_desc" to "Zbliz telefony, aby wymienic klucze i dolaczyc do patch.",
            "start_discovery" to "Rozpocznij wykrywanie",
            "scan_nearby_ble" to "skanuj nearby BLE mesh",
            "searching_nearby_patch" to "Szukanie nearby patch",
            "open_patch" to "Otworz patch",
            "show_direct_hop" to "pokaz bezposrednie i hop wezly",
            "back_to_chats" to "Wroc do chatow",
            "start_chatting" to "Zacznij chat",
            "choose_nickname" to "Wybierz nickname",
            "welcome_truskawka" to "Witamy w Truskawka",
            "choose_mesh_nickname" to "Wybierz mesh nickname",
            "nickname_rules" to "@ pozostaje stale. Maks 12 znakow.",
            "nickname_change_online_only" to "Nickname mozna zmienic, gdy mesh jest online",
            "nickname_change_once_week" to "Nickname mozna zmienic raz w tygodniu",
            "could_not_read_image" to "Nie mozna odczytac obrazu",
            "image_too_large" to "Obraz jest za duzy",
            "incoming_image_unavailable" to "odebrany obraz niedostepny na urzadzeniu",
            "incoming_audio_unavailable" to "odebrana wiadomosc glosowa niedostepna",
            "status_sprouting" to "W drodze",
            "status_ripe" to "Dostarczono",
            "broadcast" to "Broadcast",
            "enable_nearby_chat" to "Wlacz nearby chat",
            "permissions_intro_desc" to "Truskawka wymaga pelnego dostepu do Bluetooth, Nearby devices i Location, aby mesh radio moglo reklamowac i skanowac telefony obok. Internet i Wi-Fi routera nie sa wymagane.",
            "continue" to "Kontynuuj",
            "permissions_recovery_toast" to "Zezwol na Bluetooth, Nearby devices i Location dla mesh radio",
            "preparing_image" to "przygotowywanie obrazu...",
            "sending_image" to "wysylanie obrazu...",
            "image_sent" to "obraz wyslany",
            "image_received" to "obraz odebrany",
            "you" to "Ty",
            "direct" to "Direct",
            "mesh_hops" to "Mesh hops"
            ,
            "send" to "Wyslij",
            "add_caption" to "Dodaj podpis...",
            "preview_image" to "Podglad obrazu",
            "voice_message" to "wiadomosc glosowa",
            "recording_voice" to "nagrywanie glosu...",
            "sending_voice" to "wysylanie glosu...",
            "voice_sent" to "glos wyslany",
            "voice_received" to "glos odebrany",
            "voice_send_failed" to "blad wysylania glosu",
            "voice_too_large" to "wiadomosc glosowa jest za duza",
            "voice_too_short" to "przytrzymaj przycisk troche dluzej",
            "voice_read_failed" to "nie mozna odczytac nagrania",
            "play" to "Play",
            "stop" to "Stop",
            "mic_permission_needed" to "Wymagane uprawnienie mikrofonu",
            "voice_record_failed" to "Nie mozna rozpoczac nagrywania",
            "mic" to "MIK",
            "mic_icon" to "\uD83C\uDFA4",
            "recording_short" to "REC"
        )
        val es = mapOf(
            "chats" to "Chats",
            "new_contacts" to "Nuevos contactos",
            "contacts" to "Contactos",
            "profile" to "Perfil",
            "settings" to "Ajustes",
            "no_chats_yet" to "Sin chats todavia",
            "theme" to "Tema",
            "theme_light" to "Claro",
            "theme_dark" to "Negro rosa",
            "language" to "Idioma",
            "save" to "Guardar",
            "profile_updated" to "Perfil actualizado",
            "original_name_fixed" to "Nombre original (fijo)",
            "display_name_editable" to "Nombre visible (editable)",
            "display_name" to "Nombre visible",
            "everyone" to "Todos",
            "saved_messages" to "Guardados",
            "today" to "Hoy",
            "yesterday" to "Ayer",
            "hint_save_message" to "guardar mensaje...",
            "hint_type_message" to "escribe un mensaje...",
            "hint_message" to "mensaje",
            "online_now" to "en linea",
            "last_seen_prefix" to "Ultima vez:",
            "status_searching_nearby" to "Buscando cerca...",
            "status_mesh_online" to "Mesh en linea",
            "status_person_found" to "Persona cercana encontrada",
            "status_secure_ready" to "Chat seguro listo",
            "status_permissions_needed" to "Permisos necesarios",
            "status_bt_disabled" to "Bluetooth desactivado",
            "delivered" to "Entregado",
            "read" to "Leido",
            "offline" to "Offline",
            "patch" to "Patch",
            "patch_upper" to "PATCH",
            "mesh_starting" to "Mesh iniciando...",
            "search_in_patch" to "buscar en patch...",
            "direct_in_range" to "Directo en rango",
            "no_direct_peers" to "No hay pares directos en rango Bluetooth",
            "no_direct_matches" to "Sin coincidencias directas",
            "direct_ble" to "BLE directo",
            "verified" to "Verificado",
            "reachable_via_hops" to "Alcanzable por hops",
            "no_multihop_routes" to "No hay rutas multi-hop aun",
            "no_hop_matches" to "Sin coincidencias de hops",
            "via" to "via",
            "hops" to "hops",
            "photo" to "foto",
            "private_notes" to "notas privadas",
            "nearby_public_mesh" to "nearby mesh publico",
            "verified_contact" to "contacto verificado",
            "tap_open_chat" to "toca para abrir chat",
            "private_local_chat_desc" to "Chat local privado guardado en este dispositivo.",
            "broadcast_chat_desc" to "Chat publico para todos cerca.",
            "peer_id" to "Peer ID",
            "saved_auto_read" to "Guardados se marcan como leidos automaticamente.",
            "messages_public_mesh" to "Los mensajes aqui son publicos para nearby mesh.",
            "contact_verified_local" to "Este contacto esta marcado como verificado en este dispositivo.",
            "compare_code_verify" to "Compara el codigo con tu amigo y luego verificalo.",
            "search_messages" to "Buscar mensajes",
            "mark_verified" to "Marcar como verificado",
            "contact_verified" to "Contacto verificado",
            "close" to "Cerrar",
            "delete" to "Eliminar",
            "edit" to "Editar",
            "cancel" to "Cancelar",
            "delete_for_everyone_q" to "eliminar para todos?",
            "delete_for_myself_q" to "Eliminar solo para mi?",
            "search_messages_hint" to "buscar mensajes...",
            "type_to_search_chat" to "Escribe para buscar en este chat",
            "no_matches" to "Sin coincidencias",
            "search" to "Buscar",
            "seen_recently" to "visto recientemente",
            "seen" to "visto",
            "min_ago" to "min atras",
            "h_ago" to "h atras",
            "d_ago" to "d atras",
            "local" to "local",
            "online" to "online",
            "pin" to "Fijar",
            "unpin" to "Desfijar",
            "pinned" to "fijado",
            "saved_empty" to "guardados vacio",
            "broadcast_empty" to "broadcast vacio",
            "chat_empty" to "chat vacio",
            "grow" to "Grow",
            "grow_desc" to "Acerca los telefonos para intercambiar claves y unirte al patch.",
            "start_discovery" to "Iniciar descubrimiento",
            "scan_nearby_ble" to "escanear nodos nearby BLE mesh",
            "searching_nearby_patch" to "Buscando nearby patch",
            "open_patch" to "Abrir patch",
            "show_direct_hop" to "mostrar nodos directos y hop",
            "back_to_chats" to "Volver a chats",
            "start_chatting" to "Comenzar chat",
            "choose_nickname" to "Elige un nickname",
            "welcome_truskawka" to "Bienvenido a Truskawka",
            "choose_mesh_nickname" to "Elige tu mesh nickname",
            "nickname_rules" to "@ se mantiene fijo. Maximo 12 caracteres.",
            "nickname_change_online_only" to "El nickname puede cambiarse cuando mesh esta online",
            "nickname_change_once_week" to "El nickname puede cambiarse una vez por semana",
            "could_not_read_image" to "No se pudo leer la imagen",
            "image_too_large" to "La imagen es demasiado grande",
            "incoming_image_unavailable" to "la imagen recibida no esta disponible en el dispositivo",
            "incoming_audio_unavailable" to "el audio recibido no esta disponible en el dispositivo",
            "status_sprouting" to "En ruta",
            "status_ripe" to "Entregado",
            "broadcast" to "Broadcast",
            "enable_nearby_chat" to "Activar chat nearby",
            "permissions_intro_desc" to "Truskawka necesita acceso completo a Bluetooth, Nearby devices y Location para que mesh radio pueda anunciarse y escanear telefonos cercanos. Internet y Wi-Fi de router no son necesarios.",
            "continue" to "Continuar",
            "permissions_recovery_toast" to "Permite Bluetooth, Nearby devices y Location para mesh radio",
            "preparing_image" to "preparando imagen...",
            "sending_image" to "enviando imagen...",
            "image_sent" to "imagen enviada",
            "image_received" to "imagen recibida",
            "you" to "Tu",
            "direct" to "Directo",
            "mesh_hops" to "Mesh hops"
            ,
            "send" to "Enviar",
            "add_caption" to "Agregar texto...",
            "preview_image" to "Vista previa de imagen",
            "voice_message" to "mensaje de voz",
            "recording_voice" to "grabando voz...",
            "sending_voice" to "enviando voz...",
            "voice_sent" to "voz enviada",
            "voice_received" to "voz recibida",
            "voice_send_failed" to "error al enviar voz",
            "voice_too_large" to "el mensaje de voz es demasiado grande",
            "voice_too_short" to "manten pulsado un poco mas",
            "voice_read_failed" to "no se pudo leer la grabacion",
            "play" to "Play",
            "stop" to "Stop",
            "mic_permission_needed" to "Se requiere permiso de microfono",
            "voice_record_failed" to "No se pudo iniciar la grabacion",
            "mic" to "MIC",
            "mic_icon" to "\uD83C\uDFA4",
            "recording_short" to "REC"
        )
        val source = when (selectedLanguage) {
            AppLanguage.EN -> en
            AppLanguage.PL -> pl
            AppLanguage.ES -> es
            AppLanguage.RU -> ru
        }
        return source[key] ?: en[key] ?: key
    }

    private fun getDisplayName(): String =
        getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
            .getString("display_name", "")
            .orEmpty()

    private fun setDisplayName(value: String) {
        getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
            .edit()
            .putString("display_name", value.trim().take(24))
            .apply()
    }

    private fun contactDisplayName(): String {
        val display = getDisplayName().trim()
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

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(terminalText(tr("search")).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(terminalAction("X").apply {
                textSize = 18f
                setOnClickListener { dialog.dismiss() }
            })
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
        val options = if (summary.kind == ChatKind.PEER.name) {
            arrayOf(pinLabel, tr("delete"))
        } else {
            arrayOf(pinLabel)
        }
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    tr("delete") -> {
                        deleteChatSummary(summary, deleteForEveryone = true)
                        onChanged()
                    }
                    else -> {
                        chatStore.setChatPinned(summary.chatKey, !summary.pinned)
                        onChanged()
                    }
                }
            }
            .show()
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
        val options = if (message.imagePath == null && message.audioPath == null) {
            arrayOf(tr("delete"), tr("edit"))
        } else {
            arrayOf(tr("delete"))
        }
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    tr("delete") -> confirmDeleteMessage(message)
                    tr("edit") -> editMessage(message)
                }
            }
            .show()
    }

    private fun confirmDeleteMessage(target: ChatMessage) {
        val checkbox = CheckBox(this).apply {
            text = tr("delete_for_everyone_q")
            setTextColor(BERRY_TEXT)
            isChecked = false
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(terminalText(tr("delete_for_myself_q")).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                setPadding(0, 0, 0, dp(10))
            })
            addView(checkbox)
        }

        AlertDialog.Builder(this)
            .setView(panel)
            .setPositiveButton(tr("delete")) { _, _ ->
                deleteMessage(target, deleteForEveryone = checkbox.isChecked)
            }
            .setNegativeButton(tr("cancel"), null)
            .show()
    }

    private fun deleteMessage(target: ChatMessage, deleteForEveryone: Boolean) {
        if (deleteForEveryone) {
            sendDeleteMessageControl(target)
        }
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
        val payload =
            "$CONTROL_PREFIX$CONTROL_DELETE_MESSAGE|${message.timestamp}|$kind|$bodyEncoded|$token|$ownerMineOnSender"
        if (currentChatKey() == CHAT_EVERYONE) {
            meshService?.broadcastMessage(payload)
        } else {
            val peer = selectedRecipientId ?: return
            meshService?.sendMessage(peer.toString(), payload)
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
        val input = EditText(this).apply {
            setText(target.body)
            setSingleLine(false)
            maxLines = 4
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(INPUT_SURFACE, dp(16), SOFT_PINK_STROKE)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("edit"))
            .setView(input)
            .setPositiveButton(tr("save")) { _, _ ->
                val next = input.text?.toString()?.trim().orEmpty()
                if (next.isBlank()) return@setPositiveButton
                target.body = next
                if (target.localId > 0L) {
                    chatStore.updateMessageBody(target.localId, next)
                } else {
                    rebuildStoredChat(currentChatKey(), messagesForChat(currentChatKey()))
                }
                chatAdapter.notifyDataSetChanged()
            }
            .setNegativeButton(tr("cancel"), null)
            .show()
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
        val permissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions, 42)
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
        if (requestCode != 42) return

        if (hasRequiredPermissions()) {
            startAppAfterPermissions()
        } else {
            addMessage("system", "permissions denied: mesh radio offline", false)
            showPermissionRecovery()
        }
    }

    private fun startAppAfterPermissions() {
        if (!ensureBluetoothEnabled()) return
        if (!ensureLocationEnabled()) return
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
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    private fun terminalText(value: String): TextView =
        TextView(this).apply {
            text = value
            typeface = Typeface.MONOSPACE
            setTextColor(BERRY_TEXT)
            includeFontPadding = false
        }

    private fun terminalAction(value: String): TextView =
        TextView(this).apply {
            text = value
            typeface = Typeface.MONOSPACE
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

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "image.jpg"
    }

    private fun copyImageToLocalFile(fileName: String, bytes: ByteArray): File {
        val directory = File(filesDir, "sent_images").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image.jpg" }
        return File(directory, "${System.currentTimeMillis()}_$safeName").also { it.writeBytes(bytes) }
    }

    private fun prepareImageForTransfer(uri: Uri, originalName: String): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return decodeImageWithImageDecoder(uri)?.let { bitmap ->
                val scaled = scaleBitmapIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
                val compressed = compressBitmap(scaled)
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
                PreparedImage("${originalName.substringBeforeLast('.', "image")}.jpg", "image/jpeg", compressed)
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, MAX_IMAGE_DIMENSION)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: decodeImageWithImageDecoder(uri) ?: return null

        val scaled = scaleBitmapIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
        val compressed = compressBitmap(scaled)
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        val safeName = originalName
            .substringBeforeLast('.', originalName)
            .ifBlank { "image" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
        return PreparedImage("$safeName.jpg", "image/jpeg", compressed)
    }

    private fun decodeImageWithImageDecoder(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxDimension: Int): Int {
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / 2 >= maxDimension || height / 2 >= maxDimension) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        var quality = 82
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 8
        } while (bytes.size > TARGET_IMAGE_BYTES && quality >= 50)
        return bytes
    }

    private fun showImageProgress(text: String) {
        transferStatusView.text = text
        transferStatusView.visibility = View.VISIBLE
    }

    private fun hideImageProgress() {
        transferStatusView.visibility = View.GONE
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

    private inner class PatchRadarView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = SOFT_PINK_STROKE
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = STRAWBERRY_RED
        }
        private val directPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = LEAF_GREEN
        }
        private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x66FF4359
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BERRY_TEXT_DIM
            textSize = dp(11).toFloat()
            textAlign = Paint.Align.CENTER
        }

        private var directCount: Int = 0
        private var meshCount: Int = 0

        fun setPeerCounts(direct: Int, mesh: Int) {
            directCount = direct
            meshCount = mesh
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.42f

            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.72f, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.45f, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.18f, centerPaint)
            canvas.drawText(tr("you"), cx, cy + dp(4), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(10).toFloat()
                textAlign = Paint.Align.CENTER
            })

            drawPeers(canvas, cx, cy, radius * 0.52f, directCount, directPaint)
            drawPeers(canvas, cx, cy, radius * 0.84f, meshCount, meshPaint)

            canvas.drawText("${tr("direct")}: $directCount", cx, height - dp(26).toFloat(), textPaint)
            canvas.drawText("${tr("mesh_hops")}: $meshCount", cx, height - dp(10).toFloat(), textPaint)
        }

        private fun drawPeers(canvas: Canvas, cx: Float, cy: Float, radius: Float, count: Int, paint: Paint) {
            if (count <= 0) return
            repeat(count.coerceAtMost(14)) { index ->
                val angle = (index.toFloat() / count.toFloat()) * (Math.PI * 2.0)
                val x = cx + (kotlin.math.cos(angle).toFloat() * radius)
                val y = cy + (kotlin.math.sin(angle).toFloat() * radius)
                canvas.drawCircle(x, y, dp(4).toFloat(), paint)
            }
        }
    }

    private inner class ChatAdapter(private val items: List<ChatMessage>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ChatMessage = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = getItem(position)
            val isServiceLog = item.author == "system" || item.author == "mesh"
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = when {
                    isServiceLog -> Gravity.CENTER_HORIZONTAL
                    item.mine -> Gravity.RIGHT
                    else -> Gravity.LEFT
                }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                if (shouldShowDateHeader(position)) {
                    addView(dateHeader(item), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(8)
                    })
                }
                if (item.imagePath != null) {
                    val bitmap = BitmapFactory.decodeFile(item.imagePath)
                    val imageSize = calculateChatImageSize(bitmap)
                    val image = BorderedImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = false
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showImagePreview(item.imagePath) }
                        setOnLongClickListener {
                            showMessageActions(item)
                            true
                        }
                    }
                    addView(image, LinearLayout.LayoutParams(
                        imageSize.first,
                        imageSize.second
                    ))
                    addView(messageTimeView(item, overOutgoing = item.mine).apply {
                        setPadding(0, dp(4), dp(4), 0)
                    }, LinearLayout.LayoutParams(
                        imageSize.first,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    return@apply
                }
                if (item.audioPath != null) {
                    addView(voiceBubble(item), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
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
                if (!isServiceLog) {
                    bubble.setOnLongClickListener {
                        showMessageActions(item)
                        true
                    }
                }
            }
        }

        private fun shouldShowDateHeader(position: Int): Boolean {
            if (position == 0) return true
            val current = Calendar.getInstance().apply { timeInMillis = getItem(position).timestamp }
            val previous = Calendar.getInstance().apply { timeInMillis = getItem(position - 1).timestamp }
            return !current.isSameDay(previous)
        }

        private fun dateHeader(item: ChatMessage): TextView =
            TextView(this@MainActivity).apply {
                text = item.displayDate()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                background = roundedDrawable(SERVICE_BUBBLE, dp(26), SERVICE_BUBBLE_STROKE)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }

        private fun serviceBubble(item: ChatMessage): TextView =
            TextView(this@MainActivity).apply {
                text = "i ${item.body}"
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1f)
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                setTextColor(MUTED_CORAL)
                background = roundedDrawable(SERVICE_BUBBLE, dp(26), SERVICE_BUBBLE_STROKE)
                setPadding(dp(22), dp(12), dp(22), dp(12))
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            }

        private fun messageBubble(item: ChatMessage): LinearLayout =
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                minimumWidth = dp(74)
                minimumHeight = dp(46)
                background = if (item.mine) {
                    roundedDrawable(OUTGOING_BUBBLE, dp(26), OUTGOING_BUBBLE_STROKE)
                } else {
                    roundedDrawable(INCOMING_BUBBLE, dp(26), INCOMING_BUBBLE_STROKE)
                }
                setPadding(dp(18), dp(10), dp(14), dp(8))

                addView(TextView(this@MainActivity).apply {
                    text = item.body.wrapForChatBubble()
                    typeface = Typeface.DEFAULT
                    textSize = 15f
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setTextColor(if (item.mine) Color.WHITE else INCOMING_TEXT)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.70f).toInt()
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

                addView(messageTimeView(item, overOutgoing = item.mine), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.RIGHT
                    topMargin = dp(2)
                })
            }

        private fun voiceBubble(item: ChatMessage): LinearLayout =
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = if (item.mine) {
                    roundedDrawable(OUTGOING_BUBBLE, dp(26), OUTGOING_BUBBLE_STROKE)
                } else {
                    roundedDrawable(INCOMING_BUBBLE, dp(26), INCOMING_BUBBLE_STROKE)
                }
                setPadding(dp(16), dp(10), dp(14), dp(8))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val action = terminalAction(
                        if (activePlayingPath == item.audioPath) tr("stop") else tr("play")
                    ).apply {
                        textSize = 12f
                        setTextColor(if (item.mine) Color.WHITE else INCOMING_TEXT)
                        background = roundedDrawable(
                            if (item.mine) 0x40FFFFFF else 0x22FF4359,
                            dp(12),
                            if (item.mine) 0x55FFFFFF else SOFT_PINK_STROKE
                        )
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener {
                            toggleAudioPlayback(item.audioPath, this)
                        }
                    }
                    addView(action)
                    addView(terminalText(audioDurationLabel(item.audioPath)).apply {
                        textSize = 12f
                        setTextColor(if (item.mine) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM)
                        setPadding(dp(10), 0, 0, 0)
                    })
                })
                addView(messageTimeView(item, overOutgoing = item.mine), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.RIGHT
                    topMargin = dp(2)
                })
                setOnLongClickListener {
                    showMessageActions(item)
                    true
                }
            }

        private fun messageTimeView(item: ChatMessage, overOutgoing: Boolean): View {
            val tint = if (overOutgoing) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM
            val statusLabel = when (item.status) {
                MessageStatus.SENDING -> tr("status_sprouting")
                MessageStatus.DELIVERED -> tr("status_ripe")
                MessageStatus.READ -> tr("status_ripe")
                null -> if (selectedRecipientId == null) tr("broadcast") else ""
            }
            if (!item.mine || item.status == null) {
                return TextView(this@MainActivity).apply {
                    text = listOf(statusLabel, item.displayTime())
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    textSize = 11f
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.RIGHT
                    setTextColor(tint)
                }
            }

            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = listOf(statusLabel, item.displayTime())
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    textSize = 11f
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.RIGHT
                    setTextColor(tint)
                })
                addView(CheckMarksView(this@MainActivity, item.status ?: MessageStatus.DELIVERED, tint), LinearLayout.LayoutParams(
                    dp(18),
                    dp(12)
                ).apply {
                    marginStart = dp(4)
                })
            }
        }
    }

    private inner class CheckMarksView(
        context: Context,
        private val status: MessageStatus,
        color: Int
    ) : View(context) {
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = px(1.8f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (status) {
                MessageStatus.SENDING -> drawClock(canvas)
                MessageStatus.DELIVERED -> drawMark(canvas, px(6f))
                MessageStatus.READ -> {
                    drawMark(canvas, px(1f))
                    drawMark(canvas, px(7f))
                }
            }
        }

        private fun drawClock(canvas: Canvas) {
            canvas.drawCircle(px(9f), px(6f), px(4.2f), markPaint)
            canvas.drawLine(px(9f), px(6f), px(9f), px(3.5f), markPaint)
            canvas.drawLine(px(9f), px(6f), px(11.2f), px(7.5f), markPaint)
        }

        private fun drawMark(canvas: Canvas, startX: Float) {
            val path = Path().apply {
                moveTo(startX, px(6.5f))
                lineTo(startX + px(3.5f), px(10f))
                lineTo(startX + px(10f), px(2f))
            }
            canvas.drawPath(path, markPaint)
        }

        private fun px(value: Float): Float =
            value * resources.displayMetrics.density
    }

    private inner class BorderedImageView(context: Context) : ImageView(context) {
        private val clipPath = Path()
        private val clipRect = RectF()
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = IMAGE_BORDER
        }

        override fun onDraw(canvas: Canvas) {
            val radius = dp(8).toFloat()
            clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(saveCount)

            val halfStroke = borderPaint.strokeWidth / 2f
            val rect = RectF(
                halfStroke,
                halfStroke,
                width - halfStroke,
                height - halfStroke
            )
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }
    }

    private inner class ZoomableImageView(
        context: Context,
        private val onDragAtOriginalSize: () -> Unit
    ) : ImageView(context) {
        private val contentMatrix = Matrix()
        private var minScale = 1f
        private var currentScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var closeTriggered = false
        private var originalDragPrimed = false
        private var dragging = false

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val target = (currentScale * detector.scaleFactor).coerceIn(minScale, minScale * 5f)
                    val factor = target / currentScale
                    currentScale = target
                    if (currentScale > minScale * 1.05f) {
                        originalDragPrimed = false
                    }
                    contentMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    constrainImage()
                    imageMatrix = contentMatrix
                    return true
                }
            }
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val target = if (currentScale > minScale * 1.4f) minScale else minScale * 2.4f
                    val factor = target / currentScale
                    currentScale = target
                    originalDragPrimed = false
                    contentMatrix.postScale(factor, factor, e.x, e.y)
                    constrainImage()
                    imageMatrix = contentMatrix
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            post { resetImageMatrix() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            resetImageMatrix()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    closeTriggered = false
                    dragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging && !scaleDetector.isInProgress && currentScale <= minScale * 1.05f) {
                        val moved = kotlin.math.hypot(
                            (event.x - downX).toDouble(),
                            (event.y - downY).toDouble()
                        )
                        if (!closeTriggered && moved > dp(34)) {
                            closeTriggered = true
                            if (originalDragPrimed) {
                                onDragAtOriginalSize()
                            } else {
                                originalDragPrimed = true
                                resetImageMatrix()
                            }
                        }
                    } else if (dragging && !scaleDetector.isInProgress && currentScale > minScale) {
                        contentMatrix.postTranslate(event.x - lastX, event.y - lastY)
                        constrainImage()
                        imageMatrix = contentMatrix
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun resetImageMatrix() {
            val drawable = drawable ?: return
            if (width == 0 || height == 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return

            val scale = minOf(
                width.toFloat() / drawable.intrinsicWidth.toFloat(),
                height.toFloat() / drawable.intrinsicHeight.toFloat()
            )
            val dx = (width - drawable.intrinsicWidth * scale) / 2f
            val dy = (height - drawable.intrinsicHeight * scale) / 2f

            minScale = scale
            currentScale = scale
            contentMatrix.reset()
            contentMatrix.setScale(scale, scale)
            contentMatrix.postTranslate(dx, dy)
            imageMatrix = contentMatrix
        }

        private fun constrainImage() {
            val drawable = drawable ?: return
            val rect = RectF(
                0f,
                0f,
                drawable.intrinsicWidth.toFloat(),
                drawable.intrinsicHeight.toFloat()
            )
            contentMatrix.mapRect(rect)

            val dx = when {
                rect.width() <= width -> width / 2f - rect.centerX()
                rect.left > 0f -> -rect.left
                rect.right < width -> width - rect.right
                else -> 0f
            }
            val dy = when {
                rect.height() <= height -> height / 2f - rect.centerY()
                rect.top > 0f -> -rect.top
                rect.bottom < height -> height - rect.bottom
                else -> 0f
            }
            contentMatrix.postTranslate(dx, dy)
        }
    }

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
            val width = (height * aspect).toInt().coerceIn(dp(150), maxWideWidth)
            width to height
        } else {
            val width = maxWideWidth
            val height = (width / aspect).toInt().coerceIn(dp(120), maxLandscapeHeight)
            width to height
        }
    }

    private data class ChatMessage(
        var author: String,
        var body: String,
        val mine: Boolean,
        val imagePath: String? = null,
        val audioPath: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        var messageId: UUID? = null,
        var status: MessageStatus? = null,
        var localId: Long = 0L
    )

    private enum class MessageStatus {
        SENDING,
        DELIVERED,
        READ
    }

    private data class PreparedImage(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    private data class IncomingSender(
        val label: String,
        val nodeId: UUID?,
        val isBroadcast: Boolean
    )

    companion object {
        private const val LIGHT_CREAM_BACKGROUND = 0xFFFFFDF9.toInt()
        private const val LIGHT_BERRY_TEXT = 0xFF3C2328.toInt()
        private const val LIGHT_BERRY_TEXT_DIM = 0xFF8B6A71.toInt()
        private const val LIGHT_ACCENT_PINK = 0xFFFFD9DE.toInt()
        private const val LIGHT_STRAWBERRY_RED = 0xFFFF4359.toInt()
        private const val LIGHT_LEAF_GREEN = 0xFF2ECC71.toInt()
        private const val LIGHT_INPUT_SURFACE = 0xFFFFFDFD.toInt()
        private const val LIGHT_PINK_SHADOW_STROKE = 0xFFFFCBD3.toInt()
        private const val LIGHT_MUTED_CORAL = 0xFFE88E8E.toInt()
        private const val LIGHT_SERVICE_BUBBLE = 0xFFFFF0F2.toInt()
        private const val LIGHT_SERVICE_BUBBLE_STROKE = 0xFFFFD0D7.toInt()
        private const val LIGHT_INCOMING_BUBBLE = 0xD9FFFFFF.toInt()
        private const val LIGHT_INCOMING_BUBBLE_STROKE = 0xCCF1D7DC.toInt()
        private const val LIGHT_INCOMING_TEXT = 0xFF536174.toInt()
        private const val LIGHT_OUTGOING_BUBBLE = 0xD9FF8A99.toInt()
        private const val LIGHT_OUTGOING_BUBBLE_STROKE = 0xCCFF7184.toInt()
        private const val LIGHT_IMAGE_BORDER = 0xFFFF7086.toInt()
        private const val LIGHT_SOFT_PINK_PANEL = 0xFFFFF3F5.toInt()
        private const val LIGHT_SOFT_PINK_STROKE = 0xFFFFE0E5.toInt()

        private const val DARK_BACKGROUND = 0xFF1A0B0E.toInt()
        private const val DARK_TEXT = 0xFFFCECEF.toInt()
        private const val DARK_TEXT_DIM = 0xFFC8AAB1.toInt()
        private const val DARK_ACCENT = 0xFF442026.toInt()
        private const val DARK_STRAWBERRY = 0xFFFF5E76.toInt()
        private const val DARK_SURFACE = 0xFF2A1117.toInt()
        private const val DARK_STROKE = 0xFF5C2B34.toInt()
        private const val DARK_CORAL = 0xFFE39AAB.toInt()
        private const val DARK_SERVICE_BUBBLE = 0xFF2E151B.toInt()
        private const val DARK_SERVICE_STROKE = 0xFF5A2A34.toInt()
        private const val DARK_INCOMING_BUBBLE = 0xD93C2228.toInt()
        private const val DARK_INCOMING_BUBBLE_STROKE = 0xCC70414C.toInt()
        private const val DARK_INCOMING_TEXT = 0xFFF6E8EB.toInt()
        private const val DARK_OUTGOING_BUBBLE = 0xD9FF5E76.toInt()
        private const val DARK_OUTGOING_BUBBLE_STROKE = 0xCCFF7E90.toInt()
        private const val DARK_IMAGE_BORDER = 0xFFFF8FA1.toInt()
        private const val DARK_PANEL = 0xFF241015.toInt()
        private const val DARK_SOFT_STROKE = 0xFF4C2430.toInt()

        private var CREAM_BACKGROUND = LIGHT_CREAM_BACKGROUND
        private var BERRY_TEXT = LIGHT_BERRY_TEXT
        private var BERRY_TEXT_DIM = LIGHT_BERRY_TEXT_DIM
        private var ACCENT_PINK = LIGHT_ACCENT_PINK
        private var STRAWBERRY_RED = LIGHT_STRAWBERRY_RED
        private var LEAF_GREEN = LIGHT_LEAF_GREEN
        private var INPUT_SURFACE = LIGHT_INPUT_SURFACE
        private var PINK_SHADOW_STROKE = LIGHT_PINK_SHADOW_STROKE
        private var MUTED_CORAL = LIGHT_MUTED_CORAL
        private var SERVICE_BUBBLE = LIGHT_SERVICE_BUBBLE
        private var SERVICE_BUBBLE_STROKE = LIGHT_SERVICE_BUBBLE_STROKE
        private var INCOMING_BUBBLE = LIGHT_INCOMING_BUBBLE
        private var INCOMING_BUBBLE_STROKE = LIGHT_INCOMING_BUBBLE_STROKE
        private var INCOMING_TEXT = LIGHT_INCOMING_TEXT
        private var OUTGOING_BUBBLE = LIGHT_OUTGOING_BUBBLE
        private var OUTGOING_BUBBLE_STROKE = LIGHT_OUTGOING_BUBBLE_STROKE
        private var IMAGE_BORDER = LIGHT_IMAGE_BORDER
        private var SOFT_PINK_PANEL = LIGHT_SOFT_PINK_PANEL
        private var SOFT_PINK_STROKE = LIGHT_SOFT_PINK_STROKE

        private const val MAX_NICKNAME_LENGTH = 12
        private const val CHAT_EVERYONE = "everyone"
        private const val CHAT_SAVED = "saved"
        private const val SAVED_MESSAGES_PREFS = "saved_messages"
        private const val SAVED_MESSAGES_KEY = "items"
        private const val UI_SETTINGS_PREFS = "truskawka_ui_settings"
        private const val UI_SETTINGS_THEME_DARK = "theme_dark"
        private const val UI_SETTINGS_LANGUAGE = "language"
        private const val IMAGE_PICK_REQUEST = 93
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val TARGET_IMAGE_BYTES = 512 * 1024
        private const val MAX_IMAGE_DIMENSION = 1280
        private const val VOICE_MIN_DURATION_MS = 400L
        private const val VOICE_MAX_DURATION_MS = 60_000L
        private const val MAX_VOICE_BYTES = 1_200_000L
        private const val BUBBLE_WRAP_CHARS = 24
        private const val CONTROL_PREFIX = "__truskawka_ctl__:"
        private const val CONTROL_DELETE_MESSAGE = "dm"
        private const val CONTROL_DELETE_CHAT = "dc"
    }

    private fun String.wrapForChatBubble(): String {
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
}
