package mesh

import mesh.crypto.EncryptedMessage
import mesh.crypto.HandshakeResult
import mesh.crypto.MeshCrypto
import mesh.crypto.SecureKeyStorage
import mesh.protocol.MeshPacket
import mesh.protocol.PacketFlags
import mesh.protocol.PacketType
import mesh.routing.MeshRouter
import mesh.routing.MessageHandler
import mesh.routing.OpportunisticPacketTransmitter
import mesh.routing.PacketTransmitter
import mesh.routing.TransportType
import mesh.transport.BleServiceCallback
import mesh.transport.BleTransportService
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Main Mesh Manager - Orchestrates all mesh networking components
 *
 * This is the primary entry point for the mesh messenger application.
 * It coordinates:
 * - BLE and Wi-Fi Direct transports
 * - Message routing (flooding + epidemic)
 * - End-to-end encryption
 * - Store-and-forward queuing
 *
 * Usage:
 * ```kotlin
 * val meshManager = MeshManager(secureStorage)
 * meshManager.initialize()
 * meshManager.setMessageListener { senderId, message, timestamp ->
 *     // Handle incoming message
 * }
 * meshManager.start()
 *
 * // Send a message
 * meshManager.sendMessage(recipientId, "Hello from the mountains!")
 * ```
 */
class MeshManager(
    private val secureStorage: SecureKeyStorage,
    private val bleTransport: BleTransportService,
    private val wifiDirectTransport: OpportunisticPacketTransmitter? = null,
    val localNodeId: UUID = UUID.randomUUID()
) : MessageHandler, BleServiceCallback {

    // Core components
    private val crypto = MeshCrypto(secureStorage)
    private lateinit var router: MeshRouter

    // Combined transmitter (BLE + WiFi Direct)
    private val combinedTransmitter = object : PacketTransmitter {
        override fun broadcast(packet: MeshPacket) {
            if (wifiDirectTransport?.tryBroadcast(packet) != true) {
                bleTransport.broadcast(packet)
            }
        }

        override fun sendTo(nodeId: UUID, packet: MeshPacket) {
            if (wifiDirectTransport?.trySendTo(nodeId, packet) != true) {
                bleTransport.sendTo(nodeId, packet)
            }
        }
    }

    // Known peers: nodeId -> PeerInfo
    private val knownPeers = ConcurrentHashMap<UUID, PeerInfo>()
    private val aliases = ConcurrentHashMap<UUID, String>()
    private val incomingFiles = ConcurrentHashMap<UUID, IncomingFileTransfer>()
    private var localAlias: String = "@${localNodeId.toString().take(8)}"

    // Pending messages awaiting session establishment
    private val pendingOutbox = ConcurrentHashMap<UUID, MutableList<PendingOutboxMessage>>()

    // Message listeners
    private var messageListener: ((senderId: UUID, message: String, timestamp: Long) -> Unit)? = null
    private var fileListener: ((senderId: UUID, fileName: String, mimeType: String, bytes: ByteArray, timestamp: Long) -> Unit)? = null
    private var peerListener: ((nodeId: UUID, event: PeerEvent) -> Unit)? = null

    // Background executor
    private val executor = Executors.newScheduledThreadPool(2)

    /**
     * Initialize the mesh network
     */
    fun initialize() {
        // Initialize crypto (generates or loads identity keys)
        crypto.initialize()

        // Initialize router
        router = MeshRouter(localNodeId, this, combinedTransmitter)

        // Configure transports
        bleTransport.setRouter(router)
        bleTransport.setCallback(this)
        bleTransport.initialize()
    }

    fun onWifiDirectPacketReceived(packet: MeshPacket) {
        if (::router.isInitialized) {
            router.onPacketReceived(packet, 0, TransportType.WIFI_DIRECT)
        }
    }

    fun activeMeshScan(localAlias: String) {
        this.localAlias = localAlias.trim().ifBlank { this.localAlias }
        announcePresence(localAlias)
        retryPendingMessages()
    }

    /**
     * Start mesh networking (advertising, scanning, listening)
     */
    fun start() {
        bleTransport.start()

        // Schedule periodic maintenance
        executor.scheduleAtFixedRate(
            { router.performMaintenance() },
            30, 30, TimeUnit.SECONDS
        )

        // Schedule pending message retry
        executor.scheduleAtFixedRate(
            { retryPendingMessages() },
            10, 10, TimeUnit.SECONDS
        )
    }

    /**
     * Stop mesh networking
     */
    fun stop() {
        bleTransport.stop()
        executor.shutdown()
    }

    // ==================== Sending Messages ====================

    /**
     * Send a text message to a specific peer
     */
    fun sendMessage(recipientId: UUID, message: String): SendResult {
        val messageBytes = message.toByteArray(Charsets.UTF_8)

        // Check if we have an established session
        if (!crypto.hasSessionWith(recipientId)) {
            // Queue message and initiate handshake
            queuePendingMessage(recipientId, messageBytes)
            initiateHandshake(recipientId)
            return SendResult.Queued("No session, handshake initiated")
        }

        return sendEncryptedMessage(recipientId, messageBytes)
    }

    /**
     * Send a broadcast message to all nearby nodes
     */
    fun broadcastMessage(message: String): SendResult {
        val messageBytes = message.toByteArray(Charsets.UTF_8)

        // Broadcasts are not encrypted (anyone can read)
        val signature = crypto.sign(messageBytes)

        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(isEncrypted = false),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = MeshPacket.BROADCAST_ID,
            timestamp = System.currentTimeMillis(),
            payload = messageBytes,
            signature = signature
        )

        combinedTransmitter.broadcast(packet)
        return SendResult.Sent(packet.messageId)
    }

    fun sendImage(
        recipientId: UUID?,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: ((sentChunks: Int, totalChunks: Int) -> Unit)? = null
    ): SendResult {
        if (bytes.isEmpty()) return SendResult.Failed("Image is empty")
        if (bytes.size > MAX_IMAGE_BYTES) return SendResult.Failed("Image is too large")

        val target = recipientId ?: MeshPacket.BROADCAST_ID
        val transferId = UUID.randomUUID()
        val chunks = bytes.asIterableChunks(FILE_CHUNK_SIZE)
        val safeName = fileName.take(96).ifBlank { "image.jpg" }
        val safeMime = mimeType.take(64).ifBlank { "image/*" }
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        val mimeBytes = safeMime.toByteArray(Charsets.UTF_8)

        val metaPayload = ByteBuffer.allocate(
            4 + 16 + 4 + 4 + 4 + 2 + 2 + mimeBytes.size + nameBytes.size
        )
            .put(FILE_META_MAGIC)
            .putLong(transferId.mostSignificantBits)
            .putLong(transferId.leastSignificantBits)
            .putInt(bytes.size)
            .putInt(FILE_CHUNK_SIZE)
            .putInt(chunks.size)
            .putShort(mimeBytes.size.toShort())
            .putShort(nameBytes.size.toShort())
            .put(mimeBytes)
            .put(nameBytes)
            .array()

        sendFilePacket(PacketType.FILE_META, target, metaPayload)
        onProgress?.invoke(0, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            val chunkPayload = ByteBuffer.allocate(4 + 16 + 4 + 4 + chunk.size)
                .put(FILE_CHUNK_MAGIC)
                .putLong(transferId.mostSignificantBits)
                .putLong(transferId.leastSignificantBits)
                .putInt(index)
                .putInt(chunks.size)
                .put(chunk)
                .array()
            sendFilePacket(PacketType.FILE_CHUNK, target, chunkPayload)
            onProgress?.invoke(index + 1, chunks.size)
        }
        return SendResult.Sent(transferId)
    }

    private fun sendFilePacket(type: PacketType, recipientId: UUID, payload: ByteArray) {
        val packet = MeshPacket(
            type = type,
            flags = PacketFlags(isEncrypted = false),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = recipientId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = crypto.sign(payload)
        )
        if (recipientId == MeshPacket.BROADCAST_ID) {
            combinedTransmitter.broadcast(packet)
        } else {
            combinedTransmitter.sendTo(recipientId, packet)
        }
    }

    private fun sendEncryptedMessage(recipientId: UUID, plaintext: ByteArray): SendResult {
        // Encrypt message
        val encrypted = crypto.encryptMessage(recipientId, plaintext)
            ?: return SendResult.Failed("Encryption failed")

        // Sign the ciphertext
        val signature = crypto.sign(encrypted.ciphertext)

        // Build packet
        // Payload format: [nonce_len(1) | nonce | ciphertext]
        val payload = ByteBuffer.allocate(1 + encrypted.nonce.size + encrypted.ciphertext.size)
            .put(encrypted.nonce.size.toByte())
            .put(encrypted.nonce)
            .put(encrypted.ciphertext)
            .array()

        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = recipientId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = signature
        )

        router.sendMessage(recipientId, payload, signature, requiresAck = true)
        return SendResult.Sent(packet.messageId)
    }

    private fun queuePendingMessage(recipientId: UUID, message: ByteArray) {
        pendingOutbox.computeIfAbsent(recipientId) { mutableListOf() }
            .add(PendingOutboxMessage(message, System.currentTimeMillis()))
    }

    private fun retryPendingMessages() {
        val now = System.currentTimeMillis()

        pendingOutbox.forEach { (recipientId, messages) ->
            if (crypto.hasSessionWith(recipientId)) {
                // Session established, send pending messages
                messages.forEach { pending ->
                    sendEncryptedMessage(recipientId, pending.payload)
                }
                pendingOutbox.remove(recipientId)
            } else {
                // Remove expired messages (older than 24 hours)
                messages.removeIf { now - it.queuedAt > 24 * 3600 * 1000 }

                // Retry handshake
                if (messages.isNotEmpty()) {
                    initiateHandshake(recipientId)
                }
            }
        }
    }

    private fun announcePresence(localAlias: String) {
        val payload = localAlias.trim().ifBlank { "@${localNodeId.toString().take(8)}" }
            .take(48)
            .toByteArray(Charsets.UTF_8)
        val packet = MeshPacket(
            type = PacketType.DISCOVERY,
            flags = PacketFlags(isEncrypted = false, useBleOnly = true),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = MeshPacket.BROADCAST_ID,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = ByteArray(0)
        )
        combinedTransmitter.broadcast(packet)
    }

    // ==================== Handshake ====================

    private fun initiateHandshake(targetNodeId: UUID) {
        val handshakePayload = crypto.createHandshakePayload(targetNodeId)
        val signature = crypto.sign(handshakePayload)

        val packet = MeshPacket(
            type = PacketType.HANDSHAKE,
            flags = PacketFlags(),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = targetNodeId,
            timestamp = System.currentTimeMillis(),
            payload = handshakePayload,
            signature = signature
        )

        combinedTransmitter.broadcast(packet) // Flood to find the peer
    }

    fun initiateHandshakeWith(targetNodeId: UUID) {
        if (!crypto.hasSessionWith(targetNodeId)) {
            initiateHandshake(targetNodeId)
        }
    }

    // ==================== MessageHandler Interface ====================

    override fun onMessageReceived(packet: MeshPacket) {
        when (packet.type) {
            PacketType.MESSAGE -> handleIncomingMessage(packet)
            PacketType.FILE_META -> handleIncomingFileMeta(packet)
            PacketType.FILE_CHUNK -> handleIncomingFileChunk(packet)
            else -> { /* Handled by router */ }
        }
    }

    private fun handleIncomingFileMeta(packet: MeshPacket) {
        runCatching {
            val buffer = ByteBuffer.wrap(packet.payload)
            val magic = ByteArray(4).also { buffer.get(it) }
            require(magic.contentEquals(FILE_META_MAGIC))
            val transferId = UUID(buffer.long, buffer.long)
            val totalBytes = buffer.int
            val chunkSize = buffer.int
            val chunkCount = buffer.int
            val mimeLength = buffer.short.toInt()
            val nameLength = buffer.short.toInt()
            require(totalBytes in 1..MAX_IMAGE_BYTES)
            require(chunkSize in 1..FILE_CHUNK_SIZE)
            require(chunkCount in 1..MAX_IMAGE_CHUNKS)
            val mimeType = ByteArray(mimeLength).also { buffer.get(it) }.toString(Charsets.UTF_8)
            val fileName = ByteArray(nameLength).also { buffer.get(it) }.toString(Charsets.UTF_8)
            incomingFiles[transferId] = IncomingFileTransfer(
                senderId = packet.senderId,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                chunks = arrayOfNulls(chunkCount),
                timestamp = packet.timestamp
            )
        }
    }

    private fun handleIncomingFileChunk(packet: MeshPacket) {
        runCatching {
            val buffer = ByteBuffer.wrap(packet.payload)
            val magic = ByteArray(4).also { buffer.get(it) }
            require(magic.contentEquals(FILE_CHUNK_MAGIC))
            val transferId = UUID(buffer.long, buffer.long)
            val index = buffer.int
            val totalChunks = buffer.int
            val transfer = incomingFiles[transferId] ?: return
            require(totalChunks == transfer.chunks.size)
            require(index in transfer.chunks.indices)
            val chunk = ByteArray(buffer.remaining()).also { buffer.get(it) }
            transfer.chunks[index] = chunk

            if (transfer.chunks.all { it != null }) {
                val bytes = ByteArray(transfer.totalBytes)
                var offset = 0
                transfer.chunks.forEach { part ->
                    val safePart = part ?: return@forEach
                    System.arraycopy(safePart, 0, bytes, offset, safePart.size)
                    offset += safePart.size
                }
                if (offset == transfer.totalBytes) {
                    incomingFiles.remove(transferId)
                    fileListener?.invoke(
                        transfer.senderId,
                        transfer.fileName,
                        transfer.mimeType,
                        bytes,
                        transfer.timestamp
                    )
                }
            }
        }
    }

    private fun handleIncomingMessage(packet: MeshPacket) {
        val plaintext: ByteArray

        if (packet.flags.isEncrypted) {
            // Decrypt
            val buffer = ByteBuffer.wrap(packet.payload)
            val nonceLen = buffer.get().toInt()
            val nonce = ByteArray(nonceLen).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

            plaintext = crypto.decryptMessage(
                packet.senderId,
                EncryptedMessage(ciphertext, nonce)
            ) ?: run {
                // Decryption failed - maybe session not established?
                return
            }
        } else {
            // Unencrypted (broadcast)
            plaintext = packet.payload
        }

        if (packet.flags.isEncrypted) {
            val peerIdentity = crypto.getSessionInfo(packet.senderId)?.peerIdentity ?: return
            if (!crypto.verifySignature(packet.payload, packet.signature, peerIdentity)) {
                return
            }
        }

        // Deliver to application
        val message = String(plaintext, Charsets.UTF_8)
        messageListener?.invoke(packet.senderId, message, packet.timestamp)
    }

    override fun onNeighborDiscovered(nodeId: UUID, payload: ByteArray) {
        if (nodeId == localNodeId) return

        val alias = payload.toString(Charsets.UTF_8).trim()
            .takeIf { it.startsWith("@") && it.length > 1 }

        if (!knownPeers.containsKey(nodeId)) {
            knownPeers[nodeId] = PeerInfo(
                nodeId = nodeId,
                discoveredAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                displayName = alias
            )
            peerListener?.invoke(nodeId, PeerEvent.DISCOVERED)
        } else {
            knownPeers[nodeId]?.lastSeen = System.currentTimeMillis()
            if (alias != null) {
                knownPeers[nodeId]?.displayName = alias
            }
        }

        if (alias != null) {
            aliases[nodeId] = alias
        }
        router.onNeighborDiscovered(nodeId)
    }

    override fun onHandshakeReceived(nodeId: UUID, publicKey: ByteArray) {
        // Process handshake and send ACK
        when (val result = crypto.handleHandshakeAndCreateAck(nodeId, publicKey)) {
            is HandshakeResult.Success -> {
                // Send ACK
                val ackPacket = MeshPacket(
                    type = PacketType.HANDSHAKE_ACK,
                    flags = PacketFlags(),
                    messageId = UUID.randomUUID(),
                    senderId = localNodeId,
                    recipientId = nodeId,
                    timestamp = System.currentTimeMillis(),
                    payload = result.ackPayload,
                    signature = crypto.sign(result.ackPayload)
                )
                combinedTransmitter.sendTo(nodeId, ackPacket)

                // Notify peer listener
                peerListener?.invoke(nodeId, PeerEvent.SESSION_ESTABLISHED)
            }
            is HandshakeResult.Failed -> {
                // Log failure
            }
        }
    }

    override fun onHandshakeAckReceived(nodeId: UUID, encryptedPayload: ByteArray) {
        when (val result = crypto.handleHandshakeAck(nodeId, encryptedPayload)) {
            is HandshakeResult.Success -> {
                peerListener?.invoke(nodeId, PeerEvent.SESSION_ESTABLISHED)

                // Send any pending messages
                pendingOutbox[nodeId]?.forEach { pending ->
                    sendEncryptedMessage(nodeId, pending.payload)
                }
                pendingOutbox.remove(nodeId)
            }
            is HandshakeResult.Failed -> {
                // Log failure
            }
        }
    }

    override fun onAckReceived(messageId: UUID) {
        // Message was delivered
        // Could notify application here
    }

    // ==================== BleServiceCallback Interface ====================

    override fun onDeviceDiscovered(address: String, name: String?, rssi: Int) {
        // Device discovered, connection will be attempted automatically
    }

    override fun onPeerConnected(address: String) {
        activeMeshScan(localAlias)
        executor.schedule({ activeMeshScan(localAlias) }, 1200, TimeUnit.MILLISECONDS)
    }

    override fun onPeerDisconnected(address: String) {
        // BLE connection lost
    }

    override fun onPeerIdentified(address: String, nodeId: UUID) {
        // Peer's node ID received
        onNeighborDiscovered(nodeId, ByteArray(0))
        initiateHandshakeWith(nodeId)
    }

    override fun onError(message: String) {
        // Log error
    }

    // ==================== Public API ====================

    fun setMessageListener(listener: (senderId: UUID, message: String, timestamp: Long) -> Unit) {
        this.messageListener = listener
    }

    fun setFileListener(listener: (senderId: UUID, fileName: String, mimeType: String, bytes: ByteArray, timestamp: Long) -> Unit) {
        this.fileListener = listener
    }

    fun setPeerListener(listener: (nodeId: UUID, event: PeerEvent) -> Unit) {
        this.peerListener = listener
    }

    fun getKnownPeers(): List<PeerInfo> = knownPeers.values.toList()

    fun getAlias(nodeId: UUID): String = aliases[nodeId]
        ?: knownPeers[nodeId]?.displayName
        ?: "@${nodeId.toString().take(8)}"

    fun getLocalFingerprint(): String = crypto.getIdentityFingerprint()

    fun markPeerAsVerified(nodeId: UUID) {
        crypto.getSessionInfo(nodeId)?.peerFingerprint?.let {
            crypto.markPeerAsVerified(it)
        }
    }
}

// ==================== Data Classes ====================

data class PeerInfo(
    val nodeId: UUID,
    val discoveredAt: Long,
    var lastSeen: Long,
    var displayName: String? = null
)

data class PendingOutboxMessage(
    val payload: ByteArray,
    val queuedAt: Long
)

data class IncomingFileTransfer(
    val senderId: UUID,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val chunks: Array<ByteArray?>,
    val timestamp: Long
)

enum class PeerEvent {
    DISCOVERED,
    SESSION_ESTABLISHED,
    DISCONNECTED,
    VERIFIED
}

sealed class SendResult {
    data class Sent(val messageId: UUID) : SendResult()
    data class Queued(val reason: String) : SendResult()
    data class Failed(val error: String) : SendResult()
}

private val FILE_META_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
private val FILE_CHUNK_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
private const val FILE_CHUNK_SIZE = 384
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_IMAGE_CHUNKS = 6000

private fun ByteArray.asIterableChunks(size: Int): List<ByteArray> =
    indices.step(size).map { start ->
        copyOfRange(start, minOf(start + size, this.size))
    }
