package com.example.bluetoothmanager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EmulatorRelayTransport(
    private val localNodeId: UUID,
    private val aliasProvider: () -> String,
    private val onLog: (String) -> Unit,
    private val onPayloadReceived: (ByteArray) -> Unit,
    private val onPeerSeen: (UUID, String) -> Unit,
    private val onConnected: () -> Unit,
    private val hosts: List<String> = HOSTS,
    private val port: Int = PORT
) : OpportunisticPacketTransmitter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputLock = Any()
    private val helloReplies = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var connectedHost: String? = null
    @Volatile
    private var lastTriedHost: String? = null
    @Volatile
    private var lastError: String? = null
    private var connectionJob: Job? = null

    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (isActive) {
                var connected = false
                hosts.forEach { host ->
                    if (!isActive || connected) return@forEach
                    val nextSocket = Socket()
                    try {
                        lastTriedHost = host
                        withContext(Dispatchers.IO) {
                            nextSocket.keepAlive = true
                            nextSocket.tcpNoDelay = true
                            nextSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        }
                        connected = true
                        socket = nextSocket
                        connectedHost = host
                        lastError = null
                        onLog("emulator relay connected: $host:$port")
                        sendHello(nextSocket)
                        onConnected()
                        readLoop(nextSocket)
                    } catch (e: IOException) {
                        lastError = e.message
                        nextSocket.closeQuietly()
                    } finally {
                        if (socket === nextSocket) socket = null
                        if (connectedHost == host && nextSocket.isClosed) connectedHost = null
                        nextSocket.closeQuietly()
                    }
                }
                if (isActive && !connected) {
                    onLog("emulator relay unavailable")
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        socket?.closeQuietly()
        socket = null
        connectedHost = null
        scope.cancel()
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun statusText(): String =
        if (isConnected()) {
            "Dev relay online (${connectedHost ?: "connected"}:$port)"
        } else {
            val tried = lastTriedHost ?: hosts.firstOrNull().orEmpty()
            val detail = lastError?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            "Dev relay offline ($tried:$port$detail)"
        }

    override fun tryBroadcast(packet: MeshPacket): Boolean {
        val activeSocket = socket ?: run {
            start()
            return false
        }
        if (activeSocket.isClosed || !activeSocket.isConnected) {
            start()
            return false
        }
        val payload = packet.toBytes()
        if (payload.isEmpty() || payload.size > MAX_FRAME_SIZE) return false
        scope.launch {
            runCatching { writeFrame(activeSocket, payload) }
                .onFailure {
                    onLog("emulator relay write failed")
                    activeSocket.closeQuietly()
                }
        }
        return true
    }

    override fun trySendTo(nodeId: UUID, packet: MeshPacket): Boolean = tryBroadcast(packet)

    fun announce() {
        socket?.takeIf { it.isConnected && !it.isClosed }?.let(::sendHello)
    }

    override fun broadcast(packet: MeshPacket) {
        tryBroadcast(packet)
    }

    override fun sendTo(nodeId: UUID, packet: MeshPacket) {
        trySendTo(nodeId, packet)
    }

    private suspend fun readLoop(activeSocket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(activeSocket.getInputStream()))
            while (scope.isActive && !activeSocket.isClosed) {
                val length = withContext(Dispatchers.IO) { input.readInt() }
                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    throw IOException("invalid frame length: $length")
                }
                val payload = ByteArray(length)
                withContext(Dispatchers.IO) { input.readFully(payload) }
                if (!handleControlPayload(payload)) {
                    onPayloadReceived(payload)
                }
            }
        } catch (_: EOFException) {
            onLog("emulator relay disconnected")
        } catch (e: IOException) {
            onLog("emulator relay read failed")
        }
    }

    private suspend fun writeFrame(activeSocket: Socket, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            synchronized(outputLock) {
                val output = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream()))
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun sendHello(activeSocket: Socket) {
        val alias = aliasProvider().replace("\t", " ").replace("\n", " ").take(48)
        val payload = "$HELLO_PREFIX\t$localNodeId\t$alias".toByteArray(Charsets.UTF_8)
        scope.launch {
            runCatching { writeFrame(activeSocket, payload) }
                .onFailure { onLog("emulator relay hello failed") }
        }
    }

    private fun handleControlPayload(payload: ByteArray): Boolean {
        val text = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return false
        if (!text.startsWith("$HELLO_PREFIX\t")) return false
        val parts = text.split('\t', limit = 3)
        val nodeId = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return true
        if (nodeId == localNodeId) return true
        val alias = parts.getOrNull(2)
            ?.takeIf { it.startsWith("@") && it.length > 1 }
            ?: "@${nodeId.toString().take(8)}"
        onPeerSeen(nodeId, alias)
        val now = System.currentTimeMillis()
        val lastReply = helloReplies[nodeId] ?: 0L
        if (now - lastReply > HELLO_REPLY_INTERVAL_MS) {
            helloReplies[nodeId] = now
            announce()
        }
        return true
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        private val HOSTS = listOf("10.0.2.2", "127.0.0.1", "10.0.3.2")
        private const val PORT = 8899
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val HELLO_REPLY_INTERVAL_MS = 10_000L
        private const val MAX_FRAME_SIZE = 1024 * 1024
        private const val HELLO_PREFIX = "TRUSKAWKA_RELAY_HELLO_V1"
    }
}
