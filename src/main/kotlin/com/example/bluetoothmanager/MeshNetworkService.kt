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
import mesh.MeshManager
import mesh.PeerEvent
import mesh.SendResult
import mesh.protocol.MeshPacket
import mesh.transport.android.AndroidBleService
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MeshNetworkService : Service() {
    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var meshManager: MeshManager
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
                    aliasProvider = ::getNickname,
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
            localNodeId = localNodeId
        )
        meshManager.setMaxRelayHops(maxRelayHops)
        meshManager.setMessageListener { senderId, message, timestamp, isBroadcast ->
            if (senderId == localNodeId) {
                return@setMessageListener
            }
            val scope = if (isBroadcast) "broadcast" else "private"
            val isControl = message.startsWith(CONTROL_PREFIX)
            if (!isControl) {
                showIncomingNotification(
                    isBroadcast = isBroadcast,
                    title = if (isBroadcast) "Broadcast from ${meshManager.getAlias(senderId)}" else meshManager.getAlias(senderId),
                    body = message,
                    notificationId = senderId.hashCode() xor timestamp.toInt()
                )
            }
            publish("message from ${meshManager.getAlias(senderId)}|$senderId|$scope at $timestamp: $message")
        }
        meshManager.setFileListener { senderId, fileName, mimeType, bytes, timestamp, isBroadcast ->
            if (senderId == localNodeId) {
                return@setFileListener
            }
            val file = writeIncomingFile(fileName, mimeType, bytes)
            val scope = if (isBroadcast) "broadcast" else "private"
            val isAudio = mimeType.startsWith("audio/", ignoreCase = true)
            showIncomingNotification(
                isBroadcast = isBroadcast,
                title = if (isBroadcast) "Broadcast from ${meshManager.getAlias(senderId)}" else meshManager.getAlias(senderId),
                body = if (isAudio) "sent a voice message" else "sent an image",
                notificationId = senderId.hashCode() xor timestamp.toInt()
            )
            if (isAudio) {
                publish("audio from ${meshManager.getAlias(senderId)}|$senderId|$scope at $timestamp: ${file.absolutePath}|$mimeType")
            } else {
                publish("image from ${meshManager.getAlias(senderId)}|$senderId|$scope at $timestamp: ${file.absolutePath}|$mimeType")
            }
        }
        meshManager.setPeerListener { nodeId, event ->
            val label = when (event) {
                PeerEvent.DISCOVERED -> "discovered"
                PeerEvent.SESSION_ESTABLISHED -> "secure session"
                PeerEvent.DISCONNECTED -> "disconnected"
                PeerEvent.VERIFIED -> "verified"
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
            bleTransport.setLocalAlias(getNickname())
            bleTransport.startAdvertising()
            bleTransport.startScanning()
            meshManager.activeMeshScan(getNickname())
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
            bleTransport.setLocalAlias(display)
            meshManager.activeMeshScan(display)
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
        meshManager.activeMeshScan(getNickname())
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
            meshManager.sendImage(recipient, fileName, mimeType, bytes) { sent, total ->
                if (sent == 0 || sent == total || sent % maxOf(1, total / 10) == 0) {
                    publish("image progress: $sent/$total")
                }
            }
        }.getOrElse { SendResult.Failed(it.message ?: "image send failed") }
        publish("image send: ${result.label()}")
        return result
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
            meshManager.activeMeshScan(getNickname())
            publish("peer counter: ${peerCount()}")
            mainHandler.postDelayed({
                if (::meshManager.isInitialized) {
                    emulatorRelayTransport?.announce()
                    meshManager.activeMeshScan(getNickname())
                    publish("peer counter: ${peerCount()}")
                }
            }, 1_000L)
            mainHandler.postDelayed({
                if (::meshManager.isInitialized) {
                    emulatorRelayTransport?.announce()
                    meshManager.activeMeshScan(getNickname())
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
            .setContentText("mesh radio online")
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
                "Mesh network",
                NotificationManager.IMPORTANCE_LOW
            )
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(meshChannel)
                createNotificationChannel(messagesChannel)
            }
        }
    }

    private fun showIncomingNotification(isBroadcast: Boolean, title: String, body: String, notificationId: Int) {
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
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (notificationPreviewEnabled) body else "New message")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(notificationId, notification)
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

    companion object {
        private const val CHANNEL_ID = "mesh_network"
        private const val MESSAGES_CHANNEL_ID = "mesh_messages"
        private const val NOTIFICATION_ID = 1001
        private const val UI_SETTINGS_PREFS = "truskawka_ui_settings"
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
