package com.example.bluetoothmanager

import android.Manifest
import android.app.Activity
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
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
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var meshService: MeshNetworkService? = null
    private var serviceBound = false
    private var selectedRecipientId: UUID? = null
    private var selectedRecipientLabel: String = "everyone"
    private var savedMessagesSelected = false
    private var normalizingNickname = false
    private var nicknameDialogShowing = false
    private var currentNickname = "@jachimowicz"

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

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            updateNetworkStatusFromLog(line)
            if (line.startsWith("image from ")) {
                addReceivedImage(line)
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
            currentNickname = meshService?.getNickname()?.take(MAX_NICKNAME_LENGTH) ?: "@jachimowicz"
            usernameField.setText(currentNickname)
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
        chatStore = ChatStore(this)
        loadStoredMessages()
        buildUi()

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
            setPadding(0, 0, 0, dp(10))

            val topRow = FrameLayout(this@MainActivity)
            val leftGroup = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.truskawka_logo)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedDrawable(Color.WHITE, dp(8), PINK_SHADOW_STROKE)
                    clipToOutline = false
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(8)
                })

                addView(terminalText("Truskawka/").apply {
                    textSize = 18f
                    setTextColor(STRAWBERRY_RED)
                    setPadding(0, dp(6), dp(4), dp(6))
                    setOnClickListener { showChatList() }
                })

                usernameField = EditText(this@MainActivity).apply {
                    setText("@jachimowicz")
                    setSingleLine(true)
                    typeface = Typeface.MONOSPACE
                    textSize = 16f
                    setTextColor(BERRY_TEXT)
                    setHintTextColor(BERRY_TEXT_DIM)
                    setBackgroundColor(Color.TRANSPARENT)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(MAX_NICKNAME_LENGTH))
                    minWidth = dp(118)
                    setPadding(0, 0, dp(4), 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        textCursorDrawable = cursorDrawable()
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) saveUsername()
                    }
                    setOnEditorActionListener { _, _, event ->
                        if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                            saveUsername()
                            clearFocus()
                            hideKeyboard()
                            true
                        } else {
                            false
                        }
                    }
                    setOnClickListener {
                        requestFocus()
                        setSelection(text.length.coerceAtLeast(1))
                        showKeyboard(this)
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            keepNicknamePrefix()
                        }
                    })
                }
                addView(usernameField)
            }
            topRow.addView(leftGroup, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT or Gravity.CENTER_VERTICAL
            ))

            statusGroup = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedDrawable(ACCENT_PINK, dp(14))
                addView(terminalText("Nearby").apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT)
                    setPadding(dp(2), 0, dp(6), 0)
                })
                counterView = terminalText("0").apply {
                    textSize = 13f
                    setTextColor(BERRY_TEXT)
                }
                addView(counterView)
                setOnClickListener { showMeshPanel(scanFirst = true) }
            }
            topRow.addView(statusGroup, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT or Gravity.CENTER_VERTICAL
            ))
            addView(topRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ))

            chatTitleView = terminalText(selectedRecipientLabel.toDisplayTitle()).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(24), dp(8), dp(24), 0)
                setOnClickListener { showCurrentChatProfile() }
            }
            addView(chatTitleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            networkStatusView = terminalText("Offline").apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(24), dp(2), dp(24), 0)
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
            setPadding(0, dp(8), 0, dp(8))

            addView(ImageView(this@MainActivity).apply {
                minimumWidth = dp(40)
                minimumHeight = dp(40)
                background = circleDrawable(ACCENT_PINK)
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(BERRY_TEXT, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { openImagePicker() }
            })

            messageInput = EditText(this@MainActivity).apply {
                hint = "type a message..."
                setSingleLine(false)
                maxLines = 4
                setHorizontallyScrolling(false)
                typeface = Typeface.MONOSPACE
                textSize = 15f
                setTextColor(BERRY_TEXT)
                setHintTextColor(BERRY_TEXT_DIM)
                background = roundedDrawable(INPUT_SURFACE, dp(22), PINK_SHADOW_STROKE)
                elevation = dp(2).toFloat()
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateActionButton()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(messageInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })

            actionButton = terminalAction(">").apply {
                background = circleDrawable(STRAWBERRY_RED)
                setTextColor(CREAM_BACKGROUND)
                gravity = Gravity.CENTER
                minWidth = dp(42)
                minHeight = dp(42)
                setOnClickListener { handleInputAction() }
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

        val saveButton = terminalAction("Start chatting").apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(STRAWBERRY_RED, dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                val requested = input.text.toString().trim().prefixAt().take(MAX_NICKNAME_LENGTH)
                if (requested.length < 2) {
                    Toast.makeText(this@MainActivity, "Choose a nickname", Toast.LENGTH_SHORT).show()
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

            addView(terminalText("Welcome to Truskawka").apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(BERRY_TEXT)
                setPadding(0, dp(16), 0, dp(6))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(terminalText("Choose your mesh nickname").apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(BERRY_TEXT_DIM)
                setPadding(dp(6), 0, dp(6), dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(terminalText("The @ stays fixed. Max 12 characters.").apply {
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
            Toast.makeText(this, "Nickname can be changed when mesh is online", Toast.LENGTH_SHORT).show()
            return
        }

        val display = service.setNickname(requested).take(MAX_NICKNAME_LENGTH)
        if (usernameField.text.toString() != display) {
            usernameField.setText(display)
            usernameField.setSelection(usernameField.text.length)
        }
        if (requested != display) {
            Toast.makeText(this, "Nickname can be changed once a week", Toast.LENGTH_SHORT).show()
        }
        if (previous != display) {
            currentNickname = display
            renameLocalMessages(previous, display)
        }
    }

    private fun handleInputAction() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            addMessage("system", "voice placeholder", false)
            return
        }

        val author = usernameField.text.toString().prefixAt()
        val targetId = selectedRecipientId
        if (savedMessagesSelected) {
            saveLocalMessage(ChatMessage(author, text, true, status = MessageStatus.READ))
            messageInput.text.clear()
            return
        }

        val localMessage = addMessage(
            author = author,
            body = text,
            mine = true,
            status = if (targetId == null) null else MessageStatus.SENDING
        )
        messageInput.text.clear()
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
        sendSelectedImage(uri)
    }

    private fun sendSelectedImage(uri: Uri) {
        val fileName = queryDisplayName(uri)
        showImageProgress("preparing image...")

        thread(name = "image-compress-send") {
            val prepared = prepareImageForTransfer(uri, fileName)
            if (prepared == null) {
                runOnUiThread {
                    hideImageProgress()
                    Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            if (prepared.bytes.size > MAX_IMAGE_BYTES) {
                runOnUiThread {
                    hideImageProgress()
                    Toast.makeText(this, "Image is too large", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            val localPath = copyImageToLocalFile(prepared.fileName, prepared.bytes).absolutePath
            val author = usernameField.text.toString().prefixAt()

            runOnUiThread {
                if (savedMessagesSelected) {
                    saveLocalMessage(ChatMessage(author, "", true, localPath, status = MessageStatus.READ))
                    hideImageProgress()
                } else {
                    addImageMessage(author, localPath, mine = true)
                    showImageProgress("sending image...")
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
                    showImageProgress("image sent")
                    mainHandler.postDelayed({ hideImageProgress() }, 1_200)
                }
            }
        }
    }

    private fun updateActionButton() {
        actionButton.text = if (messageInput.text.toString().isBlank()) "^" else ">"
    }

    private fun showChatList() {
        syncKnownPeers()
        chatStore.ensureBaseChats()

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(SOFT_PINK_PANEL)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(terminalText("Chats").apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(terminalAction("+").apply {
                textSize = 20f
                background = circleDrawable(ACCENT_PINK)
                setOnClickListener {
                    dialog.dismiss()
                    showMeshPanel(scanFirst = true)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(terminalAction("X").apply {
                textSize = 18f
                setOnClickListener { dialog.dismiss() }
            })
        }
        root.addView(header)

        val summaries = chatStore.listChats()
            .filter { it.kind != ChatKind.PEER.name || it.peerId != null }
        if (summaries.isEmpty()) {
            root.addView(terminalText("No chats yet").apply {
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                setPadding(0, dp(24), 0, 0)
            })
        } else {
            summaries.forEach { summary ->
                root.addView(chatSummaryRow(summary) {
                    val peerId = summary.peerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    selectChat(summary.chatKey, summary.title, peerId)
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(
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

    private fun chatSummaryRow(summary: ChatSummary, onClick: () -> Unit): LinearLayout {
        val preview = when {
            summary.lastImagePath != null -> "photo"
            summary.lastBody.isNotBlank() -> summary.lastBody
            summary.kind == ChatKind.SAVED.name -> "private notes for yourself"
            summary.kind == ChatKind.EVERYONE.name -> "nearby public mesh"
            summary.verified -> "verified contact"
            else -> "tap to open chat"
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
                addView(terminalText(summary.title.toDisplayTitle()).apply {
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(BERRY_TEXT)
                    maxLines = 1
                })
                addView(terminalText(preview).apply {
                    textSize = 12f
                    setTextColor(BERRY_TEXT_DIM)
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (summary.unreadCount > 0) {
                addView(terminalText(summary.unreadCount.toString()).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(STRAWBERRY_RED, dp(12))
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                })
            }
            setOnClickListener { onClick() }
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

        val nearbyTitle = terminalText("Nearby ${meshService?.peerCount() ?: 0}").apply {
            textSize = 20f
            setTextColor(BERRY_TEXT)
        }
        val transportStatus = terminalText(meshService?.meshTransportStatus() ?: "Mesh starting...").apply {
            textSize = 12f
            setTextColor(BERRY_TEXT_DIM)
            setPadding(0, 0, 0, dp(10))
        }
        val peopleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchInput = EditText(this).apply {
            hint = "search people..."
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(BERRY_TEXT)
            setHintTextColor(BERRY_TEXT_DIM)
            background = roundedDrawable(INPUT_SURFACE, dp(20), SOFT_PINK_STROKE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        fun refreshPeopleRows() {
            val peerCount = meshService?.peerCount() ?: 0
            nearbyTitle.text = "Nearby $peerCount"
            transportStatus.text = meshService?.meshTransportStatus() ?: "Mesh starting..."
            counterView.text = peerCount.toString()
            peopleContainer.removeAllViews()

            val query = searchInput.text?.toString().orEmpty().trim().lowercase(Locale.getDefault())
            val peers = meshService?.knownPeers().orEmpty().filter { peer ->
                val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                query.isBlank() || label.lowercase(Locale.getDefault()).contains(query)
            }
            peers.forEach { peer ->
                val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                rememberPeer(peer.nodeId, label)
            }
            if (peers.isEmpty()) {
                peopleContainer.addView(terminalText(if (query.isBlank()) "Searching nearby..." else "No matches").apply {
                    textSize = 14f
                    setTextColor(BERRY_TEXT_DIM)
                })
            } else {
                peers.forEach { peer ->
                    val label = peer.displayName ?: "@${peer.nodeId.toString().take(8)}"
                    val stored = chatStore.getPeer(peer.nodeId.toString())
                    val verified = stored?.verified == true
                    val subtitle = listOfNotNull(
                        if (verified) "verified" else null,
                        stored?.let { formatPeerPresence(it.lastSeen) } ?: "online now"
                    ).joinToString(" / ")
                    peopleContainer.addView(networkActionRow(label, subtitle, !savedMessagesSelected && selectedRecipientId == peer.nodeId) {
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
            addView(terminalAction("qr").apply {
                textSize = 13f
                setOnClickListener { showCurrentChatProfile() }
            })
            addView(terminalAction("X").apply {
                textSize = 18f
                setOnClickListener { dialog.dismiss() }
            })
        }
        root.addView(header)
        root.addView(transportStatus)

        root.addView(networkActionRow("Everyone", "broadcast mode", !savedMessagesSelected && selectedRecipientId == null) {
            selectChat(CHAT_EVERYONE, "everyone", null)
            dialog.dismiss()
        })

        root.addView(networkActionRow("Saved messages", "private local chat", savedMessagesSelected) {
            selectChat(CHAT_SAVED, "saved", null)
            dialog.dismiss()
        })

        root.addView(terminalText("PEOPLE").apply {
            textSize = 15f
            setPadding(0, dp(18), 0, dp(10))
            setTextColor(BERRY_TEXT)
        })
        root.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        })
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPeopleRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(peopleContainer)
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

    private fun networkPeerRow(name: String, signal: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(terminalText("[]").apply {
                textSize = 16f
                setPadding(0, 0, dp(12), 0)
            })
            addView(terminalText(name).apply {
                textSize = 15f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(terminalText(signal).apply {
                textSize = 12f
                setTextColor(BERRY_TEXT_DIM)
            })
        }
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
        chatKey: String = currentChatKey()
    ) {
        val message = ChatMessage(author, "", mine, imagePath, timestamp)
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
    }

    private fun addReceivedText(line: String) {
        val meta = line.substringAfter("message from ", "")
        val sender = parseIncomingSender(meta.substringBefore(" at ", "@peer"))
        val author = sender.label
        val timestampAndBody = meta.substringAfter(" at ", "")
        val timestamp = timestampAndBody.substringBefore(": ", "")
            .toLongOrNull()
            ?: System.currentTimeMillis()
        val body = timestampAndBody.substringAfter(": ", line)
        sender.nodeId?.let { rememberPeer(it, author) }
        val chatKey = incomingChatKey(sender)
        if (chatKey != currentChatKey()) chatStore.incrementUnread(chatKey)
        addMessage(author, body, mine = false, timestamp = timestamp, chatKey = chatKey)
    }

    private fun addReceivedImage(line: String) {
        val meta = line.substringAfter("image from ", "")
        val sender = parseIncomingSender(meta.substringBefore(" at ", "@peer"))
        val author = sender.label
        val timestampAndPayload = meta.substringAfter(" at ", "")
        val timestamp = timestampAndPayload.substringBefore(": ", "")
            .toLongOrNull()
            ?: System.currentTimeMillis()
        val payload = timestampAndPayload.substringAfter(": ", line.substringAfter(": ", ""))
        val imagePath = payload.substringBefore("|")
        if (imagePath.isBlank()) return
        sender.nodeId?.let { rememberPeer(it, author) }
        val chatKey = incomingChatKey(sender)
        if (chatKey != currentChatKey()) chatStore.incrementUnread(chatKey)
        addImageMessage(author, imagePath, mine = false, timestamp = timestamp, chatKey = chatKey)
        showImageProgress("image received")
        mainHandler.postDelayed({ hideImageProgress() }, 1_200)
    }

    private fun updateImageProgress(value: String) {
        val sent = value.substringBefore("/").toIntOrNull()
        val total = value.substringAfter("/", "").toIntOrNull()
        if (sent == null || total == null || total <= 0) {
            showImageProgress("sending image...")
            return
        }
        val percent = ((sent * 100f) / total).toInt().coerceIn(0, 100)
        showImageProgress("sending image $percent% ($sent/$total)")
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
        if (messages.size == 1 && messages.firstOrNull()?.body == "saved messages are empty") {
            messages.clear()
        }
        messages += message
        chatAdapter.notifyDataSetChanged()
        chatList.post { chatList.setSelection(chatAdapter.count - 1) }
    }

    private fun showSavedMessages() {
        messages.clear()
        if (savedMessages.isEmpty()) {
            messages += ChatMessage("system", "saved messages are empty", false)
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
            messages += ChatMessage("system", if (chatKey == CHAT_EVERYONE) "broadcast is empty" else "chat is empty", false)
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
                val parts = line.split("\t", limit = 5)
                val author = parts.firstOrNull().orEmpty().ifBlank { "@me" }
                val body = parts.getOrNull(1)?.decodeStoredText() ?: return@mapNotNull null
                val imagePath = parts.getOrNull(2)?.decodeStoredText()?.ifBlank { null }
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
            messageDay.isSameDay(today) -> "Today"
            messageDay.isSameDay(yesterday) -> "Yesterday"
            else -> messageDateFormat.format(Date(timestamp))
        }
    }

    private fun Calendar.isSameDay(other: Calendar): Boolean =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

    private fun updateRecipientHint() {
        messageInput.hint = when {
            savedMessagesSelected -> "save a message..."
            selectedRecipientId == null -> "type a message..."
            else -> "message $selectedRecipientLabel..."
        }
    }

    private fun updateChatTitle() {
        if (::chatTitleView.isInitialized) {
            chatTitleView.text = selectedRecipientLabel.toDisplayTitle()
        }
    }

    private fun updateNetworkStatusFromLog(line: String) {
        val status = when {
            line.startsWith("search people:") -> "Searching nearby..."
            line.startsWith("mesh started") || line.startsWith("service connected") -> "Mesh online"
            line.startsWith("discovered:") -> "Nearby person found"
            line.startsWith("secure session:") -> "Secure chat ready"
            line.startsWith("message delivered:") -> "Delivered"
            line.startsWith("message read:") -> "Read"
            line.startsWith("mesh service disconnected") -> "Offline"
            line.contains("permission missing", ignoreCase = true) -> "Permissions needed"
            line.contains("bluetooth disabled", ignoreCase = true) -> "Bluetooth disabled"
            else -> null
        } ?: return
        if (::networkStatusView.isInitialized) {
            networkStatusView.text = status
        }
    }

    private fun showCurrentChatProfile() {
        val title = selectedRecipientLabel.toDisplayTitle()
        val peerId = selectedRecipientId
        val storedPeer = peerId?.let { chatStore.getPeer(it.toString()) }
        val fingerprint = peerId?.let { storedPeer?.fingerprint ?: peerFingerprint(it) }
        val verified = storedPeer?.verified == true
        val subtitle = when {
            savedMessagesSelected -> "Private local chat stored on this device."
            selectedRecipientId == null -> "Broadcast chat for everyone nearby."
            else -> "${formatPeerPresence(storedPeer?.lastSeen ?: 0L)} / Peer ID: ${peerId.toString().take(8)}...${peerId.toString().takeLast(6)}"
        }
        val action = when {
            savedMessagesSelected -> "Saved messages are automatically marked as read."
            selectedRecipientId == null -> "Messages here are public to nearby mesh users."
            verified -> "This contact is marked as verified on this device."
            else -> "Compare the code with your friend, then mark it verified."
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
            addView(terminalAction("Search messages").apply {
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
                addView(terminalAction(if (verified) "Verified" else "Mark as verified").apply {
                    textSize = 16f
                    gravity = Gravity.CENTER
                    background = roundedDrawable(if (verified) 0x33FF4D6D else ACCENT_PINK, dp(18), SOFT_PINK_STROKE)
                    setOnClickListener {
                        rememberPeer(peerId, selectedRecipientLabel)
                        chatStore.setPeerVerified(peerId.toString(), true)
                        Toast.makeText(this@MainActivity, "Contact verified", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        updateChatTitle()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
            }
            addView(terminalAction("Close").apply {
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
            hint = "search messages..."
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
                resultsContainer.addView(terminalText("Type to search this chat").apply {
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
                        (message.imagePath != null && "photo".contains(query))
                }
                .take(25)

            if (results.isEmpty()) {
                resultsContainer.addView(terminalText("No matches").apply {
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
            addView(terminalText("Search").apply {
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
            addView(terminalText(message.body.ifBlank { "photo" }).apply {
                textSize = 14f
                setTextColor(BERRY_TEXT)
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun String.toDisplayTitle(): String = when (this) {
        "everyone" -> "Everyone"
        "saved" -> "Saved messages"
        else -> this
    }

    private fun formatPeerPresence(lastSeen: Long): String {
        if (lastSeen <= 0L) return "seen recently"
        val elapsed = System.currentTimeMillis() - lastSeen
        return when {
            elapsed < 2L * 60L * 1000L -> "online now"
            elapsed < 60L * 60L * 1000L -> "seen ${elapsed / 60_000L} min ago"
            elapsed < 24L * 60L * 60L * 1000L -> "seen ${elapsed / 3_600_000L} h ago"
            else -> "seen ${elapsed / 86_400_000L} d ago"
        }
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
            addView(terminalText("Enable nearby chat").apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BERRY_TEXT)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalText("Truskawka needs Bluetooth and Nearby permissions to find phones around you. Internet and router Wi-Fi are not required.").apply {
                textSize = 14f
                setTextColor(BERRY_TEXT_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(terminalAction("Continue").apply {
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
        }
    }

    private fun startAppAfterPermissions() {
        if (!ensureBluetoothEnabled()) return
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

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

                val bubble = if (isServiceLog) {
                    serviceBubble(item)
                } else {
                    messageBubble(item)
                }
                addView(bubble, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
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

        private fun messageTimeView(item: ChatMessage, overOutgoing: Boolean): View {
            val tint = if (overOutgoing) 0xE6FFFFFF.toInt() else BERRY_TEXT_DIM
            if (!item.mine || item.status == null) {
                return TextView(this@MainActivity).apply {
                    text = item.displayTime()
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
                    text = item.displayTime()
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
        val body: String,
        val mine: Boolean,
        val imagePath: String? = null,
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
        private const val CREAM_BACKGROUND = 0xFFFFF5F5.toInt()
        private const val BERRY_TEXT = 0xFF8E444D.toInt()
        private const val BERRY_TEXT_DIM = 0xFFB2777D.toInt()
        private const val ACCENT_PINK = 0xFFFFB7C5.toInt()
        private const val STRAWBERRY_RED = 0xFFFF4D6D.toInt()
        private const val INPUT_SURFACE = 0xFFFFFDFD.toInt()
        private const val PINK_SHADOW_STROKE = 0xFFF7D2DA.toInt()
        private const val MUTED_CORAL = 0xFFE88E8E.toInt()
        private const val SERVICE_BUBBLE = 0xFFFFEAEE.toInt()
        private const val SERVICE_BUBBLE_STROKE = 0xFFF5C7CF.toInt()
        private const val INCOMING_BUBBLE = 0xD9FFFFFF.toInt()
        private const val INCOMING_BUBBLE_STROKE = 0xCCF2CDD5.toInt()
        private const val INCOMING_TEXT = 0xFF536174.toInt()
        private const val OUTGOING_BUBBLE = 0xD9FF9FB4.toInt()
        private const val OUTGOING_BUBBLE_STROKE = 0xCCF39AAD.toInt()
        private const val IMAGE_BORDER = 0xFFFF8FA8.toInt()
        private const val SOFT_PINK_PANEL = 0xFFFFEEF2.toInt()
        private const val SOFT_PINK_STROKE = 0xFFF6D4DC.toInt()
        private const val MAX_NICKNAME_LENGTH = 12
        private const val CHAT_EVERYONE = "everyone"
        private const val CHAT_SAVED = "saved"
        private const val SAVED_MESSAGES_PREFS = "saved_messages"
        private const val SAVED_MESSAGES_KEY = "items"
        private const val IMAGE_PICK_REQUEST = 93
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val TARGET_IMAGE_BYTES = 512 * 1024
        private const val MAX_IMAGE_DIMENSION = 1280
        private const val BUBBLE_WRAP_CHARS = 24
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
