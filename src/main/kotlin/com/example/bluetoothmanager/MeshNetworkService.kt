package com.example.bluetoothmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
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

    private lateinit var meshManager: MeshManager
    private lateinit var bleTransport: AndroidBleService
    private lateinit var wifiDirectSocketManager: WifiDirectSocketManager
    private lateinit var localNodeId: UUID
    private var wifiDirectPeers: List<WifiP2pDevice> = emptyList()

    val nodeId: UUID
        get() = localNodeId

    inner class LocalBinder : Binder() {
        fun service(): MeshNetworkService = this@MeshNetworkService
    }

    override fun onCreate() {
        super.onCreate()
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
            onPayloadReceived = { payload ->
                runCatching {
                    MeshPacket.fromBytes(payload)
                }.onSuccess { packet ->
                    if (::meshManager.isInitialized) {
                        meshManager.onWifiDirectPacketReceived(packet)
                    }
                }.onFailure {
                    publish("wifi-direct packet rejected: ${it.message}")
                }
            }
        )
        meshManager = MeshManager(
            secureStorage = AndroidSecureKeyStorage(applicationContext),
            bleTransport = bleTransport,
            wifiDirectTransport = wifiDirectSocketManager,
            localNodeId = localNodeId
        )
        meshManager.setMessageListener { senderId, message, timestamp ->
            publish("message from ${meshManager.getAlias(senderId)} at $timestamp: $message")
        }
        meshManager.setFileListener { senderId, fileName, mimeType, bytes, timestamp ->
            val file = writeIncomingImage(fileName, bytes)
            publish("image from ${meshManager.getAlias(senderId)} at $timestamp: ${file.absolutePath}|$mimeType")
        }
        meshManager.setPeerListener { nodeId, event ->
            val label = when (event) {
                PeerEvent.DISCOVERED -> "discovered"
                PeerEvent.SESSION_ESTABLISHED -> "secure session"
                PeerEvent.DISCONNECTED -> "disconnected"
                PeerEvent.VERIFIED -> "verified"
            }
            publish("$label: ${meshManager.getAlias(nodeId)}")
        }
        try {
            meshManager.initialize()
            meshManager.start()
            publish("mesh started as ${localNodeId.shortId()}")
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
        super.onDestroy()
    }

    fun addLogListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeLogListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    fun knownPeers() = meshManager.getKnownPeers()

    fun peerCount(): Int = meshManager.getKnownPeers().size + wifiDirectSocketManager.peerCount()

    fun searchPeople(): Int {
        publish("search people: BLE + Wi-Fi Direct")
        runCatching {
            bleTransport.startAdvertising()
            bleTransport.startScanning()
            meshManager.activeMeshScan(getNickname())
        }.onFailure {
            publish("ble search failed: ${it.message}")
        }
        wifiDirectSocketManager.startDiscovery()
        return peerCount()
    }

    fun sendPayloadViaWifiDirect(payload: ByteArray): Result<Unit> =
        wifiDirectSocketManager.sendPayloadViaWifiDirect(payload)

    fun getNickname(): String =
        getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
            .getString("nickname", null)
            ?.take(MAX_NICKNAME_LENGTH)
            ?: "@${localNodeId.shortId()}".take(MAX_NICKNAME_LENGTH)

    fun setNickname(nickname: String): String {
        val prefs = getSharedPreferences("bitchat_profile", Context.MODE_PRIVATE)
        val current = getNickname()
        val normalized = nickname.trim()
            .removePrefix("@")
            .ifBlank { localNodeId.shortId() }
            .take(MAX_NICKNAME_LENGTH - 1)
        val display = "@$normalized"
        if (display == current) {
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
            meshManager.activeMeshScan(display)
        }
        return display
    }

    fun sendMessage(recipientText: String, body: String): SendResult {
        val recipient = runCatching { UUID.fromString(recipientText.trim()) }.getOrNull()
            ?: return SendResult.Failed("Recipient must be a full UUID")
        val result = meshManager.sendMessage(recipient, body)
        publish("send to ${meshManager.getAlias(recipient)}: ${result.label()}")
        return result
    }

    fun prepareChatWith(recipientText: String) {
        val recipient = runCatching { UUID.fromString(recipientText.trim()) }.getOrNull() ?: return
        meshManager.activeMeshScan(getNickname())
        meshManager.initiateHandshakeWith(recipient)
    }

    fun broadcastMessage(body: String): SendResult {
        val result = meshManager.broadcastMessage(body)
        publish("broadcast: ${result.label()}")
        return result
    }

    fun sendImage(recipientText: String?, fileName: String, mimeType: String, bytes: ByteArray): SendResult {
        val recipient = recipientText
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        val result = meshManager.sendImage(recipient, fileName, mimeType, bytes) { sent, total ->
            if (sent == 0 || sent == total || sent % maxOf(1, total / 10) == 0) {
                publish("image progress: $sent/$total")
            }
        }
        publish("image send: ${result.label()}")
        return result
    }

    private fun publish(message: String) {
        listeners.forEach { it(message) }
    }

    private fun writeIncomingImage(fileName: String, bytes: ByteArray): File {
        val directory = File(filesDir, "incoming_images").apply { mkdirs() }
        val safeName = fileName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "image.jpg" }
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh network",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun UUID.shortId(): String = toString().take(8)

    private fun SendResult.label(): String = when (this) {
        is SendResult.Sent -> "sent ${messageId.shortId()}"
        is SendResult.Queued -> "queued ($reason)"
        is SendResult.Failed -> "failed ($error)"
    }

    companion object {
        private const val CHANNEL_ID = "mesh_network"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_NICKNAME_LENGTH = 12
        private const val KEY_NICKNAME_CHANGED_AT = "nickname_changed_at"
        private const val NICKNAME_CHANGE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
