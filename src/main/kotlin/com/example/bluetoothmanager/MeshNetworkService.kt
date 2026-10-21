package com.example.bluetoothmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import mesh.MessageDeliveryStatus
import mesh.FileTransferProgress
import mesh.MeshManager
import mesh.PeerEvent
import mesh.SendResult
import mesh.TransferDirection
import mesh.TransferStatus
import mesh.protocol.MeshPacket
import mesh.transport.android.AndroidBleService
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MeshNetworkService : Service() {
    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val avatarExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private lateinit var meshManager: MeshManager
    private lateinit var chatStore: ChatStore
    private lateinit var bleTransport: AndroidBleService
    private lateinit var wifiDirectSocketManager: WifiDirectSocketManager
    private var emulatorRelayTransport: EmulatorRelayTransport? = null
    private lateinit var localNodeId: UUID
    private var wifiDirectPeers: List<WifiP2pDevice> = emptyList()
    private var automaticDiscoveryStarted = false
    private var discoveryPulseMs = DISCOVERY_PULSE_MS
    private var maxRelayHops = 8
    private var notificationsEnabled = true
    private var notificationPreviewEnabled = true
    private var notificationBroadcastEnabled = true

    private val discoveryPulse = object : Runnable {
        override fun run() {
            startNearbyDiscovery(silent = true)
            mainHandler.postDelayed(this, discoveryPulseMs)
        }
    }

    val nodeId: UUID
        get() = localNodeId

    inner class LocalBinder : Binder() {
        fun service(): MeshNetworkService = this@MeshNetworkService
    }

    override fun onCreate() {
        super.onCreate()
        loadRuntimeSettings()
        chatStore = ChatStore(applicationContext)
        createNotificationChannel()
        startMeshForeground()

        localNodeId = loadOrCreateNodeId()
        bleTransport = AndroidBleService(applicationContext, localNodeId)
        wifiDirectSocketManager = WifiDirectSocketManager(
            context = applicationContext,
            localNodeId = localNodeId,
            onLog = ::publish,
            onPeersChanged = {
                wifiDirectPeers = it
                publish("peer counter: ${peerCount()}")
            },
            onPayloadReceived = ::handleTransportPayload
        )
        val opportunisticTransports = buildList {
            add(wifiDirectSocketManager)
            if (shouldUseDevRelay()) {
                emulatorRelayTransport = EmulatorRelayTransport(
                    localNodeId = localNodeId,
                    aliasProvider = ::publicDisplayName,
                    onLog = ::publish,
                    onPayloadReceived = ::handleTransportPayload,
                    onPeerSeen = ::handleEmulatorRelayPeerSeen,
                    onConnected = ::handleEmulatorRelayConnected
                )
                add(emulatorRelayTransport!!)
            }
        }
        meshManager = MeshManager(
            secureStorage = AndroidSecureKeyStorage(applicationContext),
            bleTransport = bleTransport,
            wifiDirectTransport = CompositeOpportunisticTransport(opportunisticTransports),
            localNodeId = localNodeId,
            transferStorageDir = File(filesDir, "mesh_file_transfers")
        )
        meshManager.setMaxRelayHops(maxRelayHops)
        meshManager.setMessageListener { senderId, message, timestamp, isBroadcast ->
            if (senderId == localNodeId) {
                return@setMessageListener
            }
            if (BlockList.isBlocked(this, senderId)) return@setMessageListener
            val scope = if (isBroadcast) "broadcast" else "private"
            val isControl = message.startsWith(CONTROL_PREFIX)
            val group = if (isBroadcast) null else GroupProtocol.decodeText(message)
            val alias = meshManager.getAlias(senderId)
            when {
                isControl -> Unit
                group != null -> if (group.body.isNotBlank() && !isGroupLeft(group.groupId)) {
                    showIncomingNotification(
                        isBroadcast = false,
                        senderId = senderId,
                        title = "${group.name}: $alias",
                        body = group.body,
                        notificationId = senderId.hashCode() xor timestamp.toInt(),
                        chatKey = GroupProtocol.chatKey(group.groupId),
                        chatTitle = group.name
                    )
                }
                else -> showIncomingNotification(
                    isBroadcast = isBroadcast,
                    senderId = senderId,
                    title = if (isBroadcast) tr("notif_broadcast_from").format(alias) else alias,
                    body = message,
                    notificationId = senderId.hashCode() xor timestamp.toInt()
                )
            }
            publish("message from $alias|$senderId|$scope at $timestamp: $message")
        }
        meshManager.setFileListener { senderId, fileName, mimeType, bytes, timestamp, isBroadcast ->
            if (senderId == localNodeId) {
                return@setFileListener
            }
            if (BlockList.isBlocked(this, senderId)) return@setFileListener
            // A contact's profile photo: store it, don't show it as a chat message.
            if (!isBroadcast && PeerAvatars.isAvatarFileName(fileName)) {
                if (PeerAvatars.saveIncoming(this, senderId, bytes)) {
                    publish("avatar updated: $senderId")
                }
                return@setFileListener
            }
            // Group photo/voice: the file name carries the group id ("g~<id>~name").
            val group = if (isBroadcast) null else GroupProtocol.decodeFileName(fileName)
            if (group != null && isGroupLeft(group.first)) return@setFileListener
            val file = writeIncomingFile(group?.second ?: fileName, mimeType, bytes)
            val scope = when {
                group != null -> "group:${group.first}"
                isBroadcast -> "broadcast"
                else -> "private"
            }
            val isAudio = mimeType.startsWith("audio/", ignoreCase = true)
            val alias = meshManager.getAlias(senderId)
            showIncomingNotification(
                isBroadcast = isBroadcast,
                senderId = senderId,
                title = if (isBroadcast) tr("notif_broadcast_from").format(alias) else alias,
                body = tr(if (isAudio) "notif_sent_voice" else "notif_sent_photo"),
                notificationId = senderId.hashCode() xor timestamp.toInt(),
                chatKey = group?.let { GroupProtocol.chatKey(it.first) }
            )
            if (isAudio) {
                publish("audio from $alias|$senderId|$scope at $timestamp: ${file.absolutePath}|$mimeType")
            } else {
                publish("image from $alias|$senderId|$scope at $timestamp: ${file.absolutePath}|$mimeType")
            }
        }
        meshManager.setFileTransferProgressListener(::persistTransferProgress)
        meshManager.setPeerListener { nodeId, event ->
            if (event == PeerEvent.KEY_CHANGED) {
                // Parsed by MainActivity: "key changed: <alias>|<nodeId>"
                publish("key changed: ${meshManager.getAlias(nodeId)}|$nodeId")
                return@setPeerListener
            }
            if (event == PeerEvent.SESSION_ESTABLISHED) {
                shareAvatarWith(nodeId)
            }
            val label = when (event) {
                PeerEvent.DISCOVERED -> "discovered"
                PeerEvent.SESSION_ESTABLISHED -> "secure session"
                PeerEvent.DISCONNECTED -> "disconnected"
                PeerEvent.VERIFIED -> "verified"
                PeerEvent.KEY_CHANGED -> "key changed"
            }
            publish("$label: ${meshManager.getAlias(nodeId)}")
            publish("peer counter: ${peerCount()}")
        }
        meshManager.setMessageStatusListener { messageId, status ->
            val label = when (status) {
                MessageDeliveryStatus.FAILED -> "failed"
                MessageDeliveryStatus.DELIVERED -> "delivered"
                MessageDeliveryStatus.READ -> "read"
            }
            publish("message $label: $messageId")
        }
        meshManager.setTransportLogListener(::publish)
        try {
            meshManager.initialize()
            meshManager.start()
            publish("mesh started as ${localNodeId.shortId()}")
            startAutomaticDiscovery()
        } catch (e: Exception) {
            publish("mesh startup failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        avatarExecutor.shutdownNow()
        if (::meshManager.isInitialized) {
            meshManager.stop()
        }
        if (::wifiDirectSocketManager.isInitialized) {
            wifiDirectSocketManager.stop()
        }
        emulatorRelayTransport?.stop()
        mainHandler.removeCallbacks(discoveryPulse)
        super.onDestroy()
    }

    fun addLogListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeLogListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    fun knownPeers() = meshManager.getKnownPeers()

    fun meshDiagnostics() = meshManager.getDiagnostics()

    fun getLocalFingerprint(): String = meshManager.getLocalFingerprint()

    fun getPeerFingerprint(peerId: UUID): String? = meshManager.getPeerFingerprint(peerId)

    fun getPeerSafetyNumber(peerId: UUID): String? = meshManager.getPeerSafetyNumber(peerId)

    fun markPeerVerified(peerId: UUID) {
        meshManager.markPeerAsVerified(peerId)
    }

    /**
     * Sends our profile photo to a peer once per photo version (tracked by content hash).
     * Runs off the radio callback thread: the transfer is sent chunk by chunk.
     */
    fun shareAvatarWith(nodeId: UUID) {
        avatarExecutor.execute {
            val (hash, bytes) = PeerAvatars.prepareOwn(this) ?: return@execute
            val prefs = getSharedPreferences(AVATAR_SHARED_PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(nodeId.toString(), null) == hash) return@execute
            val result = runCatching {
                meshManager.sendImage(nodeId, "${PeerAvatars.FILE_PREFIX}$hash.jpg", "image/jpeg", bytes)
            }.getOrNull()
            if (result is SendResult.Sent) {
                prefs.edit().putString(nodeId.toString(), hash).apply()
            }
        }
    }

    /** After the user changes their photo: push it to everyone currently reachable. */
    fun shareAvatarWithNearbyPeers() {
        knownPeers().forEach { shareAvatarWith(it.nodeId) }
    }

    fun hasPendingKeyChange(peerId: UUID): Boolean = meshManager.hasPendingKeyChange(peerId)

    fun acceptKeyChange(peerId: UUID, verified: Boolean = false): Boolean =
        meshManager.acceptKeyChange(peerId, verified)

    fun getPendingKeyFingerprint(peerId: UUID): String? = meshManager.getPendingKeyFingerprint(peerId)

    fun recentTransfers(): List<StoredTransfer> = chatStore.listRecentTransfers()

    fun peerCount(): Int = knownPeers().size

    fun meshTransportStatus(): String {
        val relay = emulatorRelayTransport
        return when {
            relay != null -> relay.statusText()
            else -> "Phone mesh: Bluetooth + nearby Wi-Fi"
        }
    }

    fun searchPeople(): Int {
        val relayLabel = if (shouldUseDevRelay()) " + dev relay" else ""
        publish("search people: BLE + Wi-Fi Direct$relayLabel")
        return startNearbyDiscovery(silent = false)
    }

    fun startNearbyDiscovery(silent: Boolean = false): Int {
        if (!silent) {
            publish("nearby search started")
        }
        runCatching {
            if (shouldUseDevRelay()) {
                emulatorRelayTransport?.start()
                emulatorRelayTransport?.announce()
            }
            val alias = publicDisplayName()
            bleTransport.setLocalAlias(alias)
            bleTransport.startAdvertising()
            bleTransport.startScanning()
            meshManager.activeMeshScan(alias)
        }.onFailure {
            publish("ble search failed: ${it.message}")
        }
        wifiDirectSocketManager.startDiscovery()
        publish("peer counter: ${peerCount()}")
        return peerCount()
    }

    fun configureDiscovery(aggressive: Boolean) {
        discoveryPulseMs = if (aggressive) 7_000L else DISCOVERY_PULSE_MS
        saveRuntimeSetting(KEY_MESH_AGGRESSIVE, aggressive)
        if (automaticDiscoveryStarted) {
            mainHandler.removeCallbacks(discoveryPulse)
            mainHandler.postDelayed(discoveryPulse, discoveryPulseMs)
        }
    }

    fun configureNotificationSettings(enabled: Boolean, showPreview: Boolean, includeBroadcast: Boolean) {
        notificationsEnabled = enabled
        notificationPreviewEnabled = showPreview
        notificationBroadcastEnabled = includeBroadcast
        val prefs = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_NOTIF_ENABLED, enabled)
            .putBoolean(KEY_NOTIF_PREVIEW, showPreview)
            .putBoolean(KEY_NOTIF_BROADCAST, includeBroadcast)
            .apply()
    }

    fun configureMaxRelayHops(maxHops: Int) {
        maxRelayHops = maxHops.coerceIn(1, 8)
        meshManager.setMaxRelayHops(maxRelayHops)
        getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MESH_MAX_HOPS, maxRelayHops)
            .apply()
    }

    private fun startAutomaticDiscovery() {
        if (automaticDiscoveryStarted) return
        automaticDiscoveryStarted = true
        startNearbyDiscovery(silent = true)
        mainHandler.postDelayed(discoveryPulse, DISCOVERY_PULSE_MS)
    }

    fun sendPayloadViaWifiDirect(payload: ByteArray): Result<Unit> =
        wifiDirectSocketManager.sendPayloadViaWifiDirect(payload)

    fun getNickname(): String =
        getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
            .getString("nickname", null)
            ?.take(MAX_NICKNAME_LENGTH)
            ?: "@${localNodeId.shortId()}".take(MAX_NICKNAME_LENGTH)

    fun publicDisplayName(): String =
        AppProfileStore.displayName(this)
            .trim()
            .take(24)
            .ifBlank { getNickname() }

    fun refreshPublicDisplayName() {
        if (!::meshManager.isInitialized || !::bleTransport.isInitialized) return
        val alias = publicDisplayName()
        bleTransport.setLocalAlias(alias)
        meshManager.activeMeshScan(alias)
        emulatorRelayTransport?.announce()
        publish("display name updated: $alias")
    }

    fun hasStoredNickname(): Boolean =
        getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
            .contains("nickname")

    fun setNickname(nickname: String): String {
        val prefs = getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
        val current = getNickname()
        val normalized = nickname.trim()
            .removePrefix("@")
            .ifBlank { localNodeId.shortId() }
            .take(MAX_NICKNAME_LENGTH - 1)
        val display = "@$normalized"
        if (display == current) {
            if (!prefs.contains("nickname")) {
                prefs.edit()
                    .putString("nickname", display)
                    .putLong(KEY_NICKNAME_CHANGED_AT, System.currentTimeMillis())
                    .apply()
            }
            return current
        }

        val now = System.currentTimeMillis()
        val lastChangedAt = prefs.getLong(KEY_NICKNAME_CHANGED_AT, 0L)
        if (lastChangedAt > 0L && now - lastChangedAt < NICKNAME_CHANGE_INTERVAL_MS) {
            return current
        }

        prefs.edit()
            .putString("nickname", display)
            .putLong(KEY_NICKNAME_CHANGED_AT, now)
            .apply()
        publish("nick changed: $display")
        if (::meshManager.isInitialized) {
            refreshPublicDisplayName()
        }
        return display
    }

    fun sendMessage(recipientText: String, body: String): SendResult {
        val recipient = runCatching { UUID.fromString(recipientText.trim()) }.getOrNull()
            ?: return SendResult.Failed("Recipient must be a full UUID")
        val result = runCatching { meshManager.sendMessage(recipient, body) }
            .getOrElse { SendResult.Failed(it.message ?: "send failed") }
        publish("send to ${meshManager.getAlias(recipient)}: ${result.label()}")
        return result
    }

    fun prepareChatWith(recipientText: String) {
        val recipient = runCatching { UUID.fromString(recipientText.trim()) }.getOrNull() ?: return
        meshManager.activeMeshScan(publicDisplayName())
        emulatorRelayTransport?.announce()
        runCatching { meshManager.initiateHandshakeWith(recipient) }
            .onFailure { publish("handshake failed: ${it.message}") }
    }

    fun broadcastMessage(body: String): SendResult {
        emulatorRelayTransport?.announce()
        val result = runCatching { meshManager.broadcastMessage(body) }
            .getOrElse { SendResult.Failed(it.message ?: "broadcast failed") }
        publish("broadcast: ${result.label()}")
        return result
    }

    fun sendImage(recipientText: String?, fileName: String, mimeType: String, bytes: ByteArray): SendResult {
        val recipient = recipientText
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        emulatorRelayTransport?.announce()
        val result = runCatching {
            meshManager.sendImage(recipient, fileName, mimeType, bytes) { progress ->
                persistTransferProgress(progress)
                val sent = progress.sentChunks
                val total = progress.totalChunks
                if (sent == 0 || sent == total || sent % maxOf(1, total / 10) == 0) {
                    publish("image progress: $sent/$total")
                }
            }
        }.getOrElse { SendResult.Failed(it.message ?: "image send failed") }
        if (result is SendResult.Failed) {
            markLatestTransferFailed(recipient, fileName)
        }
        publish("image send: ${result.label()}")
        return result
    }

    private fun persistTransferProgress(progress: FileTransferProgress) {
        val chatKey = when {
            progress.isBroadcast -> CHAT_EVERYONE
            progress.direction == TransferDirection.INCOMING -> "peer:${progress.recipientId}"
            else -> "peer:${progress.recipientId}"
        }
        val now = System.currentTimeMillis()
        val sentBytes = if (progress.totalChunks <= 0) {
            0
        } else {
            ((progress.sentChunks.toLong() * progress.totalBytes) / progress.totalChunks)
                .coerceIn(0L, progress.totalBytes.toLong())
                .toInt()
        }
        chatStore.upsertTransfer(
            StoredTransfer(
                transferId = progress.transferId.toString(),
                chatKey = chatKey,
                fileName = progress.fileName,
                mimeType = progress.mimeType,
                totalBytes = progress.totalBytes,
                receivedBytes = sentBytes,
                status = progress.status.toStorageLabel(progress.direction),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun markLatestTransferFailed(recipient: UUID?, fileName: String) {
        val chatKey = recipient?.let { "peer:$it" } ?: CHAT_EVERYONE
        chatStore.listRecentTransfers(limit = 20)
            .firstOrNull { it.chatKey == chatKey && it.fileName == fileName && it.status == "sending" }
            ?.let { transfer ->
                chatStore.updateTransferProgress(
                    transfer.transferId,
                    transfer.receivedBytes,
                    "failed"
                )
            }
    }

    private fun publish(message: String) {
        listeners.forEach { it(message) }
    }

    private fun handleTransportPayload(payload: ByteArray) {
        runCatching {
            MeshPacket.fromBytes(payload)
        }.onSuccess { packet ->
            if (::meshManager.isInitialized) {
                meshManager.onWifiDirectPacketReceived(packet)
            }
        }.onFailure {
            publish("mesh packet rejected: ${it.message}")
        }
    }

    private fun handleEmulatorRelayConnected() {
        mainHandler.post {
            if (!::meshManager.isInitialized) return@post
            publish("emulator relay ready")
            emulatorRelayTransport?.announce()
            meshManager.activeMeshScan(publicDisplayName())
            publish("peer counter: ${peerCount()}")
            mainHandler.postDelayed({
                if (::meshManager.isInitialized) {
                    emulatorRelayTransport?.announce()
                    meshManager.activeMeshScan(publicDisplayName())
                    publish("peer counter: ${peerCount()}")
                }
            }, 1_000L)
            mainHandler.postDelayed({
                if (::meshManager.isInitialized) {
                    emulatorRelayTransport?.announce()
                    meshManager.activeMeshScan(publicDisplayName())
                    publish("peer counter: ${peerCount()}")
                }
            }, 3_000L)
        }
    }

    private fun handleEmulatorRelayPeerSeen(nodeId: UUID, alias: String) {
        mainHandler.post {
            if (!::meshManager.isInitialized) return@post
            meshManager.onNeighborDiscovered(nodeId, alias.toByteArray(Charsets.UTF_8))
            publish("emulator relay peer: $alias")
            publish("peer counter: ${peerCount()}")
        }
    }

    private fun writeIncomingFile(fileName: String, mimeType: String, bytes: ByteArray): File {
        val isAudio = mimeType.startsWith("audio/", ignoreCase = true)
        val folder = if (isAudio) "incoming_audio" else "incoming_images"
        val directory = File(filesDir, folder).apply { mkdirs() }
        val safeName = fileName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { if (isAudio) "voice.m4a" else "image.jpg" }
        return File(directory, "${System.currentTimeMillis()}_$safeName").also {
            it.writeBytes(bytes)
        }
    }

    private fun loadOrCreateNodeId(): UUID {
        val prefs = getSharedPreferences("mesh_node", Context.MODE_PRIVATE)
        val existing = prefs.getString("node_id", null)
        if (existing != null) {
            runCatching { UUID.fromString(existing) }.getOrNull()?.let { return it }
        }
        return UUID.randomUUID().also {
            prefs.edit().putString("node_id", it.toString()).apply()
        }
    }

    private fun startMeshForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Truskawka")
            .setContentText(tr("notif_mesh_online"))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val meshChannel = NotificationChannel(
                CHANNEL_ID,
                tr("notif_channel_mesh"),
                NotificationManager.IMPORTANCE_LOW
            )
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                tr("notif_channel_messages"),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(meshChannel)
                createNotificationChannel(messagesChannel)
            }
        }
    }

    /** UI language chosen in the app (notifications are built here, outside the activity). */
    private fun tr(key: String): String {
        val code = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(UI_SETTINGS_LANGUAGE, AppLanguage.EN.code)
        return Translations.tr(AppLanguage.fromCode(code), key)
    }

    /** Groups the user left are ignored instead of being re-created by new messages. */
    private fun isGroupLeft(groupId: UUID): Boolean =
        getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getStringSet(UI_SETTINGS_LEFT_GROUPS, emptySet())
            .orEmpty()
            .contains(groupId.toString())

    private fun showIncomingNotification(
        isBroadcast: Boolean,
        senderId: UUID,
        title: String,
        body: String,
        notificationId: Int,
        chatKey: String? = null,
        chatTitle: String? = null
    ) {
        if (!notificationsEnabled) return
        if (isBroadcast && !notificationBroadcastEnabled) return
        val manager = getSystemService(NotificationManager::class.java)
        if (MeshPermissionPolicy.requiresPostNotificationsPermission() &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val targetChat = chatKey ?: if (isBroadcast) CHAT_EVERYONE else "peer:$senderId"
            putExtra(EXTRA_OPEN_CHAT_KEY, targetChat)
            if (!isBroadcast && chatKey == null) putExtra(EXTRA_OPEN_PEER_ID, senderId.toString())
            putExtra(
                EXTRA_OPEN_CHAT_TITLE,
                chatTitle ?: if (isBroadcast) tr("everyone") else meshManager.getAlias(senderId)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (notificationPreviewEnabled) body else tr("notif_new_message"))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(MESSAGES_GROUP_KEY)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(notificationId, notification)
        val summary = Notification.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle("Truskawka")
            .setContentText(tr("notif_new_messages_summary"))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setGroup(MESSAGES_GROUP_KEY)
            .setGroupSummary(true)
            .build()
        manager.notify(MESSAGES_SUMMARY_NOTIFICATION_ID, summary)
    }

    private fun loadRuntimeSettings() {
        val prefs = getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val aggressive = prefs.getBoolean(KEY_MESH_AGGRESSIVE, true)
        discoveryPulseMs = if (aggressive) 7_000L else DISCOVERY_PULSE_MS
        maxRelayHops = prefs.getInt(KEY_MESH_MAX_HOPS, 8).coerceIn(1, 8)
        notificationsEnabled = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
        notificationPreviewEnabled = prefs.getBoolean(KEY_NOTIF_PREVIEW, true)
        notificationBroadcastEnabled = prefs.getBoolean(KEY_NOTIF_BROADCAST, true)
    }

    private fun saveRuntimeSetting(key: String, value: Boolean) {
        getSharedPreferences(UI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun UUID.shortId(): String = toString().take(8)

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        return fingerprint.startsWith("generic")
            || fingerprint.contains("emulator")
            || model.contains("emulator")
            || model.contains("android sdk built for")
            || product.contains("sdk")
            || product.contains("emulator")
            || manufacturer.contains("genymotion")
            || brand == "generic"
            || device.contains("generic")
    }

    private fun shouldUseDevRelay(): Boolean {
        return isProbablyEmulator()
    }

    private fun SendResult.label(): String = when (this) {
        is SendResult.Sent -> "sent ${messageId.shortId()}"
        is SendResult.Queued -> "queued ($reason)"
        is SendResult.Failed -> "failed ($error)"
    }

    private fun TransferStatus.toStorageLabel(direction: TransferDirection): String = when (this) {
        TransferStatus.ACTIVE -> if (direction == TransferDirection.INCOMING) "receiving" else "sending"
        TransferStatus.COMPLETED -> "completed"
        TransferStatus.FAILED -> "failed"
    }

    companion object {
        private const val CHANNEL_ID = "mesh_network"
        private const val MESSAGES_CHANNEL_ID = "mesh_messages"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGES_SUMMARY_NOTIFICATION_ID = 1002
        private const val MESSAGES_GROUP_KEY = "truskawka_messages"
        private const val UI_SETTINGS_PREFS = "truskawka_ui_settings"
        private const val AVATAR_SHARED_PREFS = "avatar_shared_with"
        private const val KEY_MESH_AGGRESSIVE = "mesh_aggressive"
        private const val KEY_MESH_MAX_HOPS = "mesh_max_hops"
        private const val KEY_NOTIF_ENABLED = "notif_enabled"
        private const val KEY_NOTIF_PREVIEW = "notif_preview"
        private const val KEY_NOTIF_BROADCAST = "notif_broadcast"
        private const val MAX_NICKNAME_LENGTH = 12
        private const val KEY_NICKNAME_CHANGED_AT = "nickname_changed_at"
        private const val NICKNAME_CHANGE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val DISCOVERY_PULSE_MS = 15_000L
        private const val CONTROL_PREFIX = "__truskawka_ctl__:"
    }
}
