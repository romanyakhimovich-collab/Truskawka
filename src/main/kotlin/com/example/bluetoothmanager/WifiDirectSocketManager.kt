package com.example.bluetoothmanager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mesh.protocol.MeshPacket
import mesh.routing.OpportunisticPacketTransmitter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WifiDirectSocketManager(
    private val context: Context,
    private val localNodeId: UUID,
    private val onLog: (String) -> Unit,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit,
    private val onPayloadReceived: (ByteArray) -> Unit
) : OpportunisticPacketTransmitter {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectedSockets = ConcurrentHashMap<String, Socket>()
    private val outputLocks = ConcurrentHashMap<String, Any>()
    private val connectingDevices = ConcurrentHashMap.newKeySet<String>()

    private var registered = false
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var thisDevice: WifiP2pDevice? = null
    private var latestPeers: List<WifiP2pDevice> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> handleWifiP2pStateChanged(intent)
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersAndConnect()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChanged(intent)
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> handleThisDeviceChanged(intent)
            }
        }
    }

    fun startDiscovery() {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager == null || p2pChannel == null) {
            onLog("wifi-direct unavailable")
            return
        }
        if (!hasWifiPermission()) {
            onLog("wifi-direct permission missing")
            return
        }

        register()
        try {
            p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onLog("wifi-direct discovery started")
                }

                override fun onFailure(reason: Int) {
                    onLog("wifi-direct discovery failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            onLog("wifi-direct discovery blocked: ${e.message}")
        }
    }

    fun stop() {
        val p2pManager = manager
        val p2pChannel = channel
        if (p2pManager != null && p2pChannel != null && hasWifiPermission()) {
            runCatching {
                p2pManager.stopPeerDiscovery(p2pChannel, emptyActionListener)
                p2pManager.removeGroup(p2pChannel, emptyActionListener)
            }
        }
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
        closeServer()
        closeAllSockets()
        scope.cancel()
    }

    fun peerCount(): Int = latestPeers.size + connectedSockets.size

    fun sendPayloadViaWifiDirect(payload: ByteArray): Result<Unit> {
        if (payload.isEmpty()) return Result.failure(IllegalArgumentException("empty payload"))
        if (payload.size > MAX_FRAME_SIZE) {
            return Result.failure(IllegalArgumentException("payload too large: ${payload.size}"))
        }

        val sockets = connectedSockets.entries.toList()
        if (sockets.isEmpty()) {
            return Result.failure(IllegalStateException("no Wi-Fi Direct socket available"))
        }

        sockets.forEach { (key, socket) ->
            scope.launch {
                runCatching { writeFrame(key, socket, payload) }
                    .onFailure {
                        onLog("wifi-direct write failed: ${it.message}")
                        closeSocket(key)
                    }
            }
        }
        return Result.success(Unit)
    }

    override fun tryBroadcast(packet: MeshPacket): Boolean =
        sendPayloadViaWifiDirect(packet.toBytes()).isSuccess

    override fun trySendTo(nodeId: UUID, packet: MeshPacket): Boolean {
        // Socket identity is IP-based until a peer identity frame is exchanged.
        // Mesh packet recipient/deduplication keeps this safe for now.
        return tryBroadcast(packet)
    }

    override fun broadcast(packet: MeshPacket) {
        tryBroadcast(packet)
    }

    override fun sendTo(nodeId: UUID, packet: MeshPacket) {
        trySendTo(nodeId, packet)
    }

    private fun handleWifiP2pStateChanged(intent: Intent) {
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        val label = if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "enabled" else "disabled"
        onLog("wifi-direct $label")
    }

    private fun requestPeersAndConnect() {
        val p2pManager = manager ?: return
        val p2pChannel = channel ?: return
        if (!hasWifiPermission()) return

        try {
            p2pManager.requestPeers(p2pChannel) { list ->
                latestPeers = list.deviceList.toList()
                onPeersChanged(latestPeers)
                onLog("wifi-direct peers: ${latestPeers.size}")
                latestPeers.firstOrNull { it.deviceAddress !in connectingDevices }?.let(::connectToPeer)
            }
        } catch (e: SecurityException) {
            onLog("wifi-direct peers blocked: ${e.message}")
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val p2pManager = manager ?: return
        val p2pChannel = channel ?: return
        if (!hasWifiPermission()) return
        if (!connectingDevices.add(device.deviceAddress)) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }

        try {
            p2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onLog("wifi-direct connect requested: ${device.deviceName ?: device.deviceAddress}")
                }

                override fun onFailure(reason: Int) {
                    connectingDevices.remove(device.deviceAddress)
                    onLog("wifi-direct connect failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            connectingDevices.remove(device.deviceAddress)
            onLog("wifi-direct connect blocked: ${e.message}")
        }
    }

    private fun handleConnectionChanged(intent: Intent) {
        val p2pManager = manager ?: return
        val p2pChannel = channel ?: return
        if (!hasWifiPermission()) return

        @Suppress("DEPRECATION")
        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
        if (networkInfo?.isConnected != true) {
            onLog("wifi-direct disconnected")
            connectingDevices.clear()
            closeAllSockets()
            closeServer()
            return
        }

        p2pManager.requestConnectionInfo(p2pChannel) { info ->
            handleConnectionInfo(info)
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return

        if (info.isGroupOwner) {
            onLog("wifi-direct role: group owner")
            startServerSocket()
        } else {
            val ownerAddress = info.groupOwnerAddress?.hostAddress
            if (ownerAddress == null) {
                onLog("wifi-direct client missing group owner address")
                return
            }
            onLog("wifi-direct role: client -> $ownerAddress")
            connectToGroupOwner(ownerAddress)
        }
    }

    private fun handleThisDeviceChanged(intent: Intent) {
        @Suppress("DEPRECATION")
        thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        val device = thisDevice ?: return
        onLog("wifi-direct self: ${device.deviceName ?: localNodeId.toString().take(8)} state=${device.status}")
    }

    private fun startServerSocket() {
        if (serverJob?.isActive == true) return

        serverJob = scope.launch {
            try {
                ServerSocket(SOCKET_PORT).use { server ->
                    serverSocket = server
                    onLog("wifi-direct server listening :$SOCKET_PORT")
                    while (isActive) {
                        val socket = withContext(Dispatchers.IO) { server.accept() }
                        socket.keepAlive = true
                        val key = socket.inetAddress.hostAddress ?: socket.remoteSocketAddress.toString()
                        registerSocket(key, socket)
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    onLog("wifi-direct server error: ${e.message}")
                }
            } finally {
                serverSocket = null
                serverJob = null
            }
        }
    }

    private fun connectToGroupOwner(hostAddress: String) {
        if (connectedSockets.containsKey(hostAddress)) return

        scope.launch {
            try {
                val socket = Socket()
                socket.keepAlive = true
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(hostAddress, SOCKET_PORT), CONNECT_TIMEOUT_MS)
                }
                registerSocket(hostAddress, socket)
                onLog("wifi-direct socket connected: $hostAddress:$SOCKET_PORT")
            } catch (e: IOException) {
                closeSocket(hostAddress)
                onLog("wifi-direct socket connect failed: ${e.message}")
            }
        }
    }

    private fun registerSocket(key: String, socket: Socket) {
        connectedSockets[key]?.closeQuietly()
        connectedSockets[key] = socket
        outputLocks[key] = Any()
        onLog("wifi-direct socket ready: $key")
        startReaderLoop(key, socket)
    }

    private fun startReaderLoop(key: String, socket: Socket) {
        scope.launch {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (isActive && !socket.isClosed) {
                    val length = withContext(Dispatchers.IO) { input.readInt() }
                    if (length <= 0 || length > MAX_FRAME_SIZE) {
                        throw IOException("invalid frame length: $length")
                    }
                    val payload = ByteArray(length)
                    withContext(Dispatchers.IO) { input.readFully(payload) }
                    onPayloadReceived(payload)
                }
            } catch (e: EOFException) {
                onLog("wifi-direct socket closed: $key")
            } catch (e: IOException) {
                onLog("wifi-direct read failed: ${e.message}")
            } finally {
                closeSocket(key)
            }
        }
    }

    private suspend fun writeFrame(key: String, socket: Socket, payload: ByteArray) {
        val lock = outputLocks.getOrPut(key) { Any() }
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun closeServer() {
        serverJob?.cancel()
        serverJob = null
        serverSocket?.closeQuietly()
        serverSocket = null
    }

    private fun closeAllSockets() {
        connectedSockets.keys.toList().forEach(::closeSocket)
    }

    private fun closeSocket(key: String) {
        connectedSockets.remove(key)?.closeQuietly()
        outputLocks.remove(key)
    }

    private fun hasWifiPermission(): Boolean {
        val hasLocation = Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasNearbyWifi = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        return hasLocation && hasNearbyWifi
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    private val emptyActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = Unit
    }

    companion object {
        const val SOCKET_PORT = 8888
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_FRAME_SIZE = 1024 * 1024
    }
}
