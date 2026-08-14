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
import mesh.routing.RouterDiagnostics
import mesh.routing.TransportType
import mesh.transport.BleServiceCallback
import mesh.transport.BleTransportService
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.Collections
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
    val localNodeId: UUID = UUID.randomUUID(),
    private val transferStorageDir: File? = null
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
    private val orphanFileChunks = ConcurrentHashMap<UUID, OrphanFileChunks>()
    private val outgoingFiles = ConcurrentHashMap<UUID, OutgoingFileTransfer>()
    private val trackedMessageDeliveries = ConcurrentHashMap.newKeySet<UUID>()
    private var localAlias: String = "@${localNodeId.toString().take(8)}"
    private var maxRelayHops: Int = MeshPacket.DEFAULT_TTL.toInt()

    // Pending messages awaiting session establishment
    private val pendingOutbox = ConcurrentHashMap<UUID, MutableList<PendingOutboxMessage>>()

    // Message listeners
    private var messageListener: ((senderId: UUID, message: String, timestamp: Long, isBroadcast: Boolean) -> Unit)? = null
    private var fileListener: ((senderId: UUID, fileName: String, mimeType: String, bytes: ByteArray, timestamp: Long, isBroadcast: Boolean) -> Unit)? = null
    private var fileTransferProgressListener: ((FileTransferProgress) -> Unit)? = null
    private var peerListener: ((nodeId: UUID, event: PeerEvent) -> Unit)? = null
    private var messageStatusListener: ((messageId: UUID, status: MessageDeliveryStatus) -> Unit)? = null
    private var transportLogListener: ((message: String) -> Unit)? = null

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
        router.setMaxRelayHops(maxRelayHops)

        // Configure transports
        bleTransport.setRouter(router)
        bleTransport.setCallback(this)
        bleTransport.initialize()
        restorePersistedFileTransfers()
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

        executor.scheduleAtFixedRate(
            { requestMissingFileChunks() },
            3, 3, TimeUnit.SECONDS
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
            val added = queuePendingMessage(recipientId, messageBytes)
            initiateHandshake(recipientId)
            return if (added) {
                SendResult.Queued("No session, handshake initiated")
            } else {
                SendResult.Queued("No session, already queued")
            }
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
            ttl = maxRelayHops.toByte(),
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
        onProgress: ((FileTransferProgress) -> Unit)? = null
    ): SendResult {
        if (bytes.isEmpty()) return SendResult.Failed("Image is empty")
        if (bytes.size > MAX_IMAGE_BYTES) return SendResult.Failed("Image is too large")

        val target = recipientId ?: MeshPacket.BROADCAST_ID
        if (target != MeshPacket.BROADCAST_ID && !crypto.hasSessionWith(target)) {
            initiateHandshake(target)
            return SendResult.Failed("Secure session not established; handshake initiated")
        }
        val transferId = UUID.randomUUID()
        val chunks = bytes.asIterableChunks(FILE_CHUNK_SIZE)
        val safeName = fileName.take(96).ifBlank { "image.jpg" }
        val safeMime = mimeType.take(64).ifBlank { "image/*" }
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        val mimeBytes = safeMime.toByteArray(Charsets.UTF_8)
        outgoingFiles[transferId] = OutgoingFileTransfer(
            recipientId = target,
            fileName = safeName,
            mimeType = safeMime,
            totalBytes = bytes.size,
            chunks = chunks,
            createdAt = System.currentTimeMillis(),
            isBroadcast = target == MeshPacket.BROADCAST_ID
        )
        persistOutgoingFileTransfer(transferId, outgoingFiles[transferId]!!)
        cleanupOutgoingFiles()

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

        if (!sendFilePacket(PacketType.FILE_META, target, metaPayload)) {
            return SendResult.Failed("Failed to send file metadata")
        }
        onProgress?.invoke(
            FileTransferProgress(
                transferId = transferId,
                sentChunks = 0,
                totalChunks = chunks.size,
                totalBytes = bytes.size,
                fileName = safeName,
                mimeType = safeMime,
                recipientId = target,
                isBroadcast = target == MeshPacket.BROADCAST_ID,
                direction = TransferDirection.OUTGOING,
                status = TransferStatus.ACTIVE
            )
        )
        chunks.forEachIndexed { index, chunk ->
            val chunkPayload = ByteBuffer.allocate(4 + 16 + 4 + 4 + chunk.size)
                .put(FILE_CHUNK_MAGIC)
                .putLong(transferId.mostSignificantBits)
                .putLong(transferId.leastSignificantBits)
                .putInt(index)
                .putInt(chunks.size)
                .put(chunk)
                .array()
            if (!sendFilePacket(PacketType.FILE_CHUNK, target, chunkPayload)) {
                return SendResult.Failed("Failed to send file chunk ${index + 1}/${chunks.size}")
            }
            onProgress?.invoke(
                FileTransferProgress(
                    transferId = transferId,
                    sentChunks = index + 1,
                    totalChunks = chunks.size,
                    totalBytes = bytes.size,
                    fileName = safeName,
                    mimeType = safeMime,
                    recipientId = target,
                    isBroadcast = target == MeshPacket.BROADCAST_ID,
                    direction = TransferDirection.OUTGOING,
                    status = if (index + 1 >= chunks.size) TransferStatus.COMPLETED else TransferStatus.ACTIVE
                )
            )
        }
        return SendResult.Sent(transferId)
    }

    private fun sendFilePacket(type: PacketType, recipientId: UUID, payload: ByteArray): Boolean {
        val isBroadcast = recipientId == MeshPacket.BROADCAST_ID
        val wirePayload = if (isBroadcast) {
            payload
        } else {
            val encrypted = crypto.encryptMessage(recipientId, payload) ?: return false
            encodeEncryptedPayload(encrypted)
        }
        val packet = MeshPacket(
            type = type,
            flags = PacketFlags(
                isEncrypted = !isBroadcast,
                requiresAck = !isBroadcast
            ),
            ttl = maxRelayHops.toByte(),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = recipientId,
            timestamp = System.currentTimeMillis(),
            payload = wirePayload,
            signature = crypto.sign(wirePayload)
        )
        if (isBroadcast) {
            combinedTransmitter.broadcast(packet)
        } else {
            router.sendPacket(
                type = type,
                recipientId = recipientId,
                payload = wirePayload,
                signature = packet.signature,
                flags = packet.flags
            )
        }
        return true
    }

    private fun sendEncryptedMessage(recipientId: UUID, plaintext: ByteArray): SendResult {
        // Encrypt message
        val encrypted = crypto.encryptMessage(recipientId, plaintext)
            ?: return SendResult.Failed("Encryption failed")

        // Build packet
        // Payload format: [nonce_len(1) | nonce | ciphertext]
        val payload = encodeEncryptedPayload(encrypted)
        val signature = crypto.sign(payload)

        val messageId = router.sendMessage(recipientId, payload, signature, requiresAck = true)
        trackedMessageDeliveries.add(messageId)
        return SendResult.Sent(messageId)
    }

    private fun queuePendingMessage(recipientId: UUID, message: ByteArray): Boolean {
        val queue = pendingOutbox.computeIfAbsent(recipientId) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(queue) {
            if (queue.any { it.payload.contentEquals(message) }) {
                return false
            }
            if (queue.size >= MAX_PENDING_OUTBOX_PER_PEER) {
                queue.removeAt(0)
            }
            queue.add(PendingOutboxMessage(message, System.currentTimeMillis()))
        }
        return true
    }

    private fun retryPendingMessages() {
        val now = System.currentTimeMillis()

        pendingOutbox.forEach { (recipientId, messages) ->
            if (crypto.hasSessionWith(recipientId)) {
                // Session established, send pending messages
                val snapshot = synchronized(messages) { messages.toList() }
                snapshot.forEach { pending ->
                    sendEncryptedMessage(recipientId, pending.payload)
                }
                pendingOutbox.remove(recipientId)
            } else {
                // Remove expired messages (older than 24 hours)
                val hasPending = synchronized(messages) {
                    messages.removeIf { now - it.queuedAt > 24 * 3600 * 1000 }
                    messages.isNotEmpty()
                }

                // Retry handshake
                if (hasPending) {
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
            ttl = maxRelayHops.toByte(),
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
            flags = PacketFlags(isEncrypted = false),
            ttl = maxRelayHops.toByte(),
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

    override fun onMessageReceived(packet: MeshPacket): Boolean {
        return when (packet.type) {
            PacketType.MESSAGE -> handleIncomingMessage(packet)
            PacketType.FILE_META -> handleIncomingFileMeta(packet)
            PacketType.FILE_CHUNK -> handleIncomingFileChunk(packet)
            PacketType.FILE_CHUNK_REQUEST -> handleIncomingFileChunkRequest(packet)
            else -> true /* Handled by router */
        }
    }

    private fun handleIncomingFileMeta(packet: MeshPacket): Boolean =
        runCatching {
            val payload = decodeFilePayload(packet) ?: return@runCatching false
            val buffer = ByteBuffer.wrap(payload)
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
                timestamp = packet.timestamp,
                isBroadcast = packet.isBroadcast()
            )
            persistIncomingFileMeta(transferId, incomingFiles[transferId]!!)
            orphanFileChunks.remove(transferId)?.let { orphan ->
                val transfer = incomingFiles[transferId] ?: return@let
                orphan.chunks.forEachIndexed { index, chunk ->
                    if (index in transfer.chunks.indices && chunk != null) {
                        transfer.chunks[index] = chunk
                        persistIncomingFileChunk(transferId, index, chunk)
                    }
                }
                completeFileTransferIfReady(transferId, transfer)
            }
            notifyIncomingFileProgress(transferId, incomingFiles[transferId] ?: return@runCatching true)
            requestMissingFileChunks(transferId)
            true
        }.getOrDefault(false)

    private fun handleIncomingFileChunk(packet: MeshPacket): Boolean =
        runCatching {
            val payload = decodeFilePayload(packet) ?: return@runCatching false
            val buffer = ByteBuffer.wrap(payload)
            val magic = ByteArray(4).also { buffer.get(it) }
            require(magic.contentEquals(FILE_CHUNK_MAGIC))
            val transferId = UUID(buffer.long, buffer.long)
            val index = buffer.int
            val totalChunks = buffer.int
            val chunk = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val transfer = incomingFiles[transferId]
            if (transfer == null) {
                storeOrphanFileChunk(transferId, index, totalChunks, chunk)
                return@runCatching true
            }
            require(totalChunks == transfer.chunks.size)
            require(index in transfer.chunks.indices)
            require(chunk.size <= FILE_CHUNK_SIZE)
            transfer.chunks[index] = chunk
            persistIncomingFileChunk(transferId, index, chunk)

            notifyIncomingFileProgress(transferId, transfer)
            completeFileTransferIfReady(transferId, transfer)
            if (incomingFiles.containsKey(transferId)) {
                requestMissingFileChunks(transferId)
            }
            true
        }.getOrDefault(false)

    private fun storeOrphanFileChunk(transferId: UUID, index: Int, totalChunks: Int, chunk: ByteArray) {
        require(totalChunks in 1..MAX_IMAGE_CHUNKS)
        require(index in 0 until totalChunks)
        require(chunk.size <= FILE_CHUNK_SIZE)
        cleanupOrphanFileChunks()
        val orphan = orphanFileChunks.compute(transferId) { _, existing ->
            existing ?: OrphanFileChunks(arrayOfNulls(totalChunks), System.currentTimeMillis())
        } ?: return
        if (orphan.chunks.size != totalChunks) {
            orphanFileChunks.remove(transferId)
            return
        }
        orphan.chunks[index] = chunk
    }

    private fun handleIncomingFileChunkRequest(packet: MeshPacket): Boolean =
        runCatching {
            val payload = decodeChunkRequestPayload(packet) ?: return@runCatching false
            val buffer = ByteBuffer.wrap(payload)
            val magic = ByteArray(4).also { buffer.get(it) }
            require(magic.contentEquals(FILE_CHUNK_REQUEST_MAGIC))
            val transferId = UUID(buffer.long, buffer.long)
            val totalChunks = buffer.int
            val requestedCount = buffer.short.toInt() and 0xFFFF
            require(requestedCount in 1..MAX_CHUNK_REQUEST_INDEXES)
            val outgoing = outgoingFiles[transferId] ?: return@runCatching true
            require(totalChunks == outgoing.chunks.size)
            if (!outgoing.isBroadcast && packet.senderId != outgoing.recipientId) {
                return@runCatching false
            }
            repeat(requestedCount) {
                val index = buffer.int
                if (index in outgoing.chunks.indices) {
                    resendFileChunk(transferId, index, outgoing)
                }
            }
            true
        }.getOrDefault(false)

    private fun resendFileChunk(transferId: UUID, index: Int, outgoing: OutgoingFileTransfer) {
        val chunk = outgoing.chunks[index]
        val chunkPayload = ByteBuffer.allocate(4 + 16 + 4 + 4 + chunk.size)
            .put(FILE_CHUNK_MAGIC)
            .putLong(transferId.mostSignificantBits)
            .putLong(transferId.leastSignificantBits)
            .putInt(index)
            .putInt(outgoing.chunks.size)
            .put(chunk)
            .array()
        sendFilePacket(PacketType.FILE_CHUNK, outgoing.recipientId, chunkPayload)
    }

    private fun completeFileTransferIfReady(transferId: UUID, transfer: IncomingFileTransfer) {
        if (transfer.chunks.any { it == null }) return
        val bytes = ByteArray(transfer.totalBytes)
        var offset = 0
        transfer.chunks.forEach { part ->
            val safePart = part ?: return
            if (offset + safePart.size > bytes.size) {
                incomingFiles.remove(transferId)
                return
            }
            System.arraycopy(safePart, 0, bytes, offset, safePart.size)
            offset += safePart.size
        }
        if (offset == transfer.totalBytes) {
            incomingFiles.remove(transferId)
            deleteTransferDirectory("incoming", transferId)
            notifyFileProgress(
                FileTransferProgress(
                    transferId = transferId,
                    sentChunks = transfer.chunks.size,
                    totalChunks = transfer.chunks.size,
                    totalBytes = transfer.totalBytes,
                    fileName = transfer.fileName,
                    mimeType = transfer.mimeType,
                    recipientId = transfer.senderId,
                    isBroadcast = transfer.isBroadcast,
                    direction = TransferDirection.INCOMING,
                    status = TransferStatus.COMPLETED
                )
            )
            fileListener?.invoke(
                transfer.senderId,
                transfer.fileName,
                transfer.mimeType,
                bytes,
                transfer.timestamp,
                transfer.isBroadcast
            )
        }
    }

    private fun cleanupOrphanFileChunks() {
        val now = System.currentTimeMillis()
        orphanFileChunks.entries.removeIf { now - it.value.createdAt > FILE_TRANSFER_TIMEOUT_MS }
    }

    private fun cleanupOutgoingFiles() {
        val now = System.currentTimeMillis()
        outgoingFiles.entries.removeIf { now - it.value.createdAt > FILE_TRANSFER_TIMEOUT_MS }
        transferStorageDir?.resolve("outgoing")?.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > FILE_TRANSFER_TIMEOUT_MS) {
                dir.deleteRecursively()
            }
        }
    }

    private fun requestMissingFileChunks() {
        incomingFiles.keys.forEach(::requestMissingFileChunks)
        cleanupOrphanFileChunks()
        cleanupOutgoingFiles()
    }

    private fun requestMissingFileChunks(transferId: UUID) {
        val transfer = incomingFiles[transferId] ?: return
        val now = System.currentTimeMillis()
        if (now - transfer.lastChunkRequestAt < FILE_CHUNK_REQUEST_INTERVAL_MS) return
        val missing = transfer.chunks
            .mapIndexedNotNull { index, chunk -> if (chunk == null) index else null }
            .take(MAX_CHUNK_REQUEST_INDEXES)
        if (missing.isEmpty()) return
        if (sendFileChunkRequest(transferId, transfer, missing)) {
            transfer.lastChunkRequestAt = now
        }
    }

    private fun sendFileChunkRequest(
        transferId: UUID,
        transfer: IncomingFileTransfer,
        missingIndexes: List<Int>
    ): Boolean {
        val payload = ByteBuffer.allocate(4 + 16 + 4 + 2 + missingIndexes.size * 4)
            .put(FILE_CHUNK_REQUEST_MAGIC)
            .putLong(transferId.mostSignificantBits)
            .putLong(transferId.leastSignificantBits)
            .putInt(transfer.chunks.size)
            .putShort(missingIndexes.size.toShort())
            .also { buffer -> missingIndexes.forEach(buffer::putInt) }
            .array()

        if (transfer.isBroadcast) {
            val packet = MeshPacket(
                type = PacketType.FILE_CHUNK_REQUEST,
                flags = PacketFlags(isEncrypted = false),
                ttl = maxRelayHops.toByte(),
                messageId = UUID.randomUUID(),
                senderId = localNodeId,
                recipientId = transfer.senderId,
                timestamp = System.currentTimeMillis(),
                payload = payload,
                signature = crypto.sign(payload)
            )
            combinedTransmitter.sendTo(transfer.senderId, packet)
            return true
        } else {
            return sendFilePacket(PacketType.FILE_CHUNK_REQUEST, transfer.senderId, payload)
        }
    }

    private fun notifyIncomingFileProgress(transferId: UUID, transfer: IncomingFileTransfer) {
        val receivedChunks = transfer.chunks.count { it != null }
        notifyFileProgress(
            FileTransferProgress(
                transferId = transferId,
                sentChunks = receivedChunks,
                totalChunks = transfer.chunks.size,
                totalBytes = transfer.totalBytes,
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                recipientId = transfer.senderId,
                isBroadcast = transfer.isBroadcast,
                direction = TransferDirection.INCOMING,
                status = TransferStatus.ACTIVE
            )
        )
    }

    private fun notifyFileProgress(progress: FileTransferProgress) {
        fileTransferProgressListener?.invoke(progress)
    }

    private fun restorePersistedFileTransfers() {
        restoreIncomingFileTransfers()
        restoreOutgoingFileTransfers()
    }

    private fun restoreIncomingFileTransfers() {
        val root = transferStorageDir?.resolve("incoming") ?: return
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val meta = loadTransferMeta(dir) ?: return@forEach
            val transferId = runCatching { UUID.fromString(dir.name) }.getOrNull() ?: return@forEach
            val senderId = runCatching { UUID.fromString(meta.getProperty("senderId")) }.getOrNull() ?: return@forEach
            val chunkCount = meta.getProperty("chunkCount")?.toIntOrNull() ?: return@forEach
            val chunks = arrayOfNulls<ByteArray>(chunkCount)
            dir.resolve("chunks").listFiles()?.forEach { file ->
                val index = file.name.toIntOrNull() ?: return@forEach
                if (index in chunks.indices) {
                    chunks[index] = runCatching { file.readBytes() }.getOrNull()
                }
            }
            val transfer = IncomingFileTransfer(
                senderId = senderId,
                fileName = meta.getProperty("fileName", "file.bin"),
                mimeType = meta.getProperty("mimeType", "application/octet-stream"),
                totalBytes = meta.getProperty("totalBytes")?.toIntOrNull() ?: return@forEach,
                chunks = chunks,
                timestamp = meta.getProperty("timestamp")?.toLongOrNull() ?: System.currentTimeMillis(),
                isBroadcast = meta.getProperty("isBroadcast").toBoolean()
            )
            incomingFiles[transferId] = transfer
            notifyIncomingFileProgress(transferId, transfer)
            completeFileTransferIfReady(transferId, transfer)
            if (incomingFiles.containsKey(transferId)) {
                requestMissingFileChunks(transferId)
            }
        }
    }

    private fun restoreOutgoingFileTransfers() {
        val root = transferStorageDir?.resolve("outgoing") ?: return
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val meta = loadTransferMeta(dir) ?: return@forEach
            val transferId = runCatching { UUID.fromString(dir.name) }.getOrNull() ?: return@forEach
            val recipientId = runCatching { UUID.fromString(meta.getProperty("recipientId")) }.getOrNull() ?: return@forEach
            val chunkCount = meta.getProperty("chunkCount")?.toIntOrNull() ?: return@forEach
            val chunks = (0 until chunkCount).map { index ->
                val chunk = dir.resolve("chunks").resolve(index.toString())
                if (!chunk.exists()) return@forEach
                chunk.readBytes()
            }
            outgoingFiles[transferId] = OutgoingFileTransfer(
                recipientId = recipientId,
                fileName = meta.getProperty("fileName", "file.bin"),
                mimeType = meta.getProperty("mimeType", "application/octet-stream"),
                totalBytes = meta.getProperty("totalBytes")?.toIntOrNull() ?: return@forEach,
                chunks = chunks,
                createdAt = meta.getProperty("createdAt")?.toLongOrNull() ?: System.currentTimeMillis(),
                isBroadcast = meta.getProperty("isBroadcast").toBoolean()
            )
        }
    }

    private fun persistIncomingFileMeta(transferId: UUID, transfer: IncomingFileTransfer) {
        val dir = transferDirectory("incoming", transferId) ?: return
        saveTransferMeta(
            dir,
            Properties().apply {
                setProperty("senderId", transfer.senderId.toString())
                setProperty("fileName", transfer.fileName)
                setProperty("mimeType", transfer.mimeType)
                setProperty("totalBytes", transfer.totalBytes.toString())
                setProperty("chunkCount", transfer.chunks.size.toString())
                setProperty("timestamp", transfer.timestamp.toString())
                setProperty("isBroadcast", transfer.isBroadcast.toString())
            }
        )
    }

    private fun persistIncomingFileChunk(transferId: UUID, index: Int, chunk: ByteArray) {
        val dir = transferDirectory("incoming", transferId)?.resolve("chunks") ?: return
        dir.mkdirs()
        dir.resolve(index.toString()).writeBytes(chunk)
    }

    private fun persistOutgoingFileTransfer(transferId: UUID, transfer: OutgoingFileTransfer) {
        val dir = transferDirectory("outgoing", transferId) ?: return
        saveTransferMeta(
            dir,
            Properties().apply {
                setProperty("recipientId", transfer.recipientId.toString())
                setProperty("fileName", transfer.fileName)
                setProperty("mimeType", transfer.mimeType)
                setProperty("totalBytes", transfer.totalBytes.toString())
                setProperty("chunkCount", transfer.chunks.size.toString())
                setProperty("createdAt", transfer.createdAt.toString())
                setProperty("isBroadcast", transfer.isBroadcast.toString())
            }
        )
        val chunksDir = dir.resolve("chunks").apply { mkdirs() }
        transfer.chunks.forEachIndexed { index, chunk ->
            chunksDir.resolve(index.toString()).writeBytes(chunk)
        }
    }

    private fun transferDirectory(kind: String, transferId: UUID): File? =
        transferStorageDir?.resolve(kind)?.resolve(transferId.toString())?.apply { mkdirs() }

    private fun deleteTransferDirectory(kind: String, transferId: UUID) {
        transferStorageDir?.resolve(kind)?.resolve(transferId.toString())?.deleteRecursively()
    }

    private fun saveTransferMeta(dir: File, properties: Properties) {
        dir.mkdirs()
        dir.resolve("meta.properties").outputStream().use { properties.store(it, null) }
    }

    private fun loadTransferMeta(dir: File): Properties? {
        val file = dir.resolve("meta.properties")
        if (!file.exists()) return null
        return runCatching {
            Properties().apply {
                file.inputStream().use(::load)
            }
        }.getOrNull()
    }

    private fun decodeFilePayload(packet: MeshPacket): ByteArray? {
        if (!packet.isBroadcast() && !packet.flags.isEncrypted) {
            return null
        }
        return decodeMessagePayload(packet)
    }

    private fun decodeChunkRequestPayload(packet: MeshPacket): ByteArray? {
        if (!packet.flags.isEncrypted) {
            if (!verifyOptionalPublicSignature(packet)) return null
            return packet.payload
        }
        return decodeMessagePayload(packet)
    }

    private fun decodeMessagePayload(packet: MeshPacket): ByteArray? {
        if (!packet.flags.isEncrypted) {
            if (!verifyOptionalPublicSignature(packet)) return null
            return packet.payload
        }

        val peerIdentity = crypto.getSessionInfo(packet.senderId)?.peerIdentity ?: return null
        if (packet.signature.isEmpty() || !crypto.verifySignature(packet.payload, packet.signature, peerIdentity)) {
            return null
        }

        val encrypted = decodeEncryptedPayload(packet.payload) ?: return null
        return crypto.decryptMessage(packet.senderId, encrypted)
    }

    private fun verifyOptionalPublicSignature(packet: MeshPacket): Boolean {
        if (packet.signature.isEmpty()) return true
        val peerIdentity = crypto.getSessionInfo(packet.senderId)?.peerIdentity ?: return true
        return crypto.verifySignature(packet.payload, packet.signature, peerIdentity)
    }

    private fun decodeEncryptedPayload(payload: ByteArray): EncryptedMessage? {
        if (payload.isEmpty()) return null
        val buffer = ByteBuffer.wrap(payload)
        val nonceLen = buffer.get().toInt() and 0xFF
        if (nonceLen <= 0 || nonceLen > buffer.remaining()) return null
        val nonce = ByteArray(nonceLen).also { buffer.get(it) }
        if (buffer.remaining() <= 0) return null
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return EncryptedMessage(ciphertext, nonce)
    }

    private fun handleIncomingMessage(packet: MeshPacket): Boolean {
        val plaintext = decodeMessagePayload(packet) ?: return false

        // Deliver to application
        val message = String(plaintext, Charsets.UTF_8)
        messageListener?.invoke(packet.senderId, message, packet.timestamp, packet.isBroadcast())
        if (!packet.isBroadcast()) {
            sendReadReceipt(packet.senderId, packet.messageId)
        }
        return true
    }

    private fun sendReadReceipt(recipientId: UUID, readMessageId: UUID) {
        val payload = ByteBuffer.allocate(16)
            .putLong(readMessageId.mostSignificantBits)
            .putLong(readMessageId.leastSignificantBits)
            .array()
        val packet = MeshPacket(
            type = PacketType.READ_RECEIPT,
            flags = PacketFlags(isEncrypted = false),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = recipientId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = crypto.sign(payload)
        )
        combinedTransmitter.sendTo(recipientId, packet)
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
                displayName = alias,
                isDirect = false,
                hopCount = 2
            )
            peerListener?.invoke(nodeId, PeerEvent.DISCOVERED)
        } else {
            knownPeers[nodeId]?.lastSeen = System.currentTimeMillis()
            if (alias != null) {
                knownPeers[nodeId]?.displayName = alias
            }
            if (knownPeers[nodeId]?.hopCount ?: 2 < 2) {
                knownPeers[nodeId]?.hopCount = 1
                knownPeers[nodeId]?.isDirect = true
            }
        }

        if (alias != null) {
            aliases[nodeId] = alias
        }
        router.onNeighborDiscovered(nodeId)
    }

    override fun onHandshakeReceived(nodeId: UUID, publicKey: ByteArray) {
        onNeighborDiscovered(nodeId, ByteArray(0))
        // Process handshake and send ACK
        when (val result = crypto.handleHandshakeAndCreateAck(nodeId, publicKey)) {
            is HandshakeResult.Success -> {
                // Send ACK
                val ackPacket = MeshPacket(
                    type = PacketType.HANDSHAKE_ACK,
                    flags = PacketFlags(isEncrypted = false),
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
                requestMissingFileChunks()
            }
            is HandshakeResult.Failed -> {
                // Log failure
            }
        }
    }

    override fun onHandshakeAckReceived(nodeId: UUID, encryptedPayload: ByteArray) {
        onNeighborDiscovered(nodeId, ByteArray(0))
        when (val result = crypto.handleHandshakeAck(nodeId, encryptedPayload)) {
            is HandshakeResult.Success -> {
                peerListener?.invoke(nodeId, PeerEvent.SESSION_ESTABLISHED)

                // Send any pending messages
                val queued = pendingOutbox[nodeId]
                val snapshot = if (queued != null) synchronized(queued) { queued.toList() } else emptyList()
                snapshot.forEach { pending -> sendEncryptedMessage(nodeId, pending.payload) }
                pendingOutbox.remove(nodeId)
                requestMissingFileChunks()
            }
            is HandshakeResult.Failed -> {
                // Log failure
            }
        }
    }

    override fun onAckReceived(messageId: UUID) {
        if (trackedMessageDeliveries.remove(messageId)) {
            messageStatusListener?.invoke(messageId, MessageDeliveryStatus.DELIVERED)
        }
    }

    override fun onReadReceiptReceived(senderId: UUID, messageId: UUID) {
        if (trackedMessageDeliveries.remove(messageId)) {
            messageStatusListener?.invoke(messageId, MessageDeliveryStatus.READ)
        }
    }

    override fun onDeliveryFailed(messageId: UUID) {
        if (trackedMessageDeliveries.remove(messageId)) {
            messageStatusListener?.invoke(messageId, MessageDeliveryStatus.FAILED)
        }
    }

    override fun signControlPayload(payload: ByteArray): ByteArray = crypto.sign(payload)

    override fun isControlPacketAuthentic(packet: MeshPacket): Boolean {
        val peerIdentity = crypto.getSessionInfo(packet.senderId)?.peerIdentity ?: return false
        return packet.signature.isNotEmpty() &&
            crypto.verifySignature(packet.payload, packet.signature, peerIdentity)
    }

    // ==================== BleServiceCallback Interface ====================

    override fun onDeviceDiscovered(address: String, name: String?, rssi: Int) {
        transportLogListener?.invoke("ble device found: ${name ?: address} rssi=$rssi")
    }

    override fun onAdvertisementPeerDiscovered(address: String, nodeId: UUID, alias: String?, rssi: Int) {
        transportLogListener?.invoke("ble radio hello: ${alias ?: nodeId.toString().take(8)} rssi=$rssi")
        onNeighborDiscovered(nodeId, alias?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        markPeerDirect(nodeId)
        initiateHandshakeWith(nodeId)
    }

    override fun onPeerConnected(address: String) {
        transportLogListener?.invoke("ble connected: $address")
        activeMeshScan(localAlias)
        executor.schedule({ activeMeshScan(localAlias) }, 1200, TimeUnit.MILLISECONDS)
    }

    override fun onPeerDisconnected(address: String) {
        transportLogListener?.invoke("ble disconnected: $address")
    }

    override fun onPeerIdentified(address: String, nodeId: UUID) {
        transportLogListener?.invoke("ble peer identified: ${nodeId.toString().take(8)}")
        onNeighborDiscovered(nodeId, ByteArray(0))
        markPeerDirect(nodeId)
        initiateHandshakeWith(nodeId)
    }

    override fun onError(message: String) {
        transportLogListener?.invoke("ble error: $message")
    }

    // ==================== Public API ====================

    fun setMessageListener(listener: (senderId: UUID, message: String, timestamp: Long, isBroadcast: Boolean) -> Unit) {
        this.messageListener = listener
    }

    fun setFileListener(listener: (senderId: UUID, fileName: String, mimeType: String, bytes: ByteArray, timestamp: Long, isBroadcast: Boolean) -> Unit) {
        this.fileListener = listener
    }

    fun setFileTransferProgressListener(listener: (FileTransferProgress) -> Unit) {
        this.fileTransferProgressListener = listener
    }

    fun setPeerListener(listener: (nodeId: UUID, event: PeerEvent) -> Unit) {
        this.peerListener = listener
    }

    fun setMessageStatusListener(listener: (messageId: UUID, status: MessageDeliveryStatus) -> Unit) {
        this.messageStatusListener = listener
    }

    fun setTransportLogListener(listener: (message: String) -> Unit) {
        this.transportLogListener = listener
    }

    fun getKnownPeers(): List<PeerInfo> {
        pruneStalePeers()
        return knownPeers.values
            .sortedByDescending { it.lastSeen }
            .toList()
    }

    fun getDiagnostics(): MeshDiagnostics {
        pruneStalePeers()
        val peers = knownPeers.values.toList()
        val pendingHandshakeMessages = pendingOutbox.values.sumOf { queue ->
            synchronized(queue) { queue.size }
        }
        val routerDiagnostics = if (::router.isInitialized) {
            router.diagnosticsSnapshot()
        } else {
            RouterDiagnostics(
                neighborCount = 0,
                pendingMessageCount = 0,
                retryReadyCount = 0,
                seenMessageCount = 0,
                routeCount = 0,
                cachedPacketCount = 0,
                maxRelayHops = maxRelayHops
            )
        }
        return MeshDiagnostics(
            knownPeerCount = peers.size,
            directPeerCount = peers.count { it.isDirect || it.hopCount <= 1 },
            routedPeerCount = peers.count { !(it.isDirect || it.hopCount <= 1) },
            pendingHandshakeMessages = pendingHandshakeMessages,
            incomingFileTransfers = incomingFiles.size,
            maxRelayHops = maxRelayHops,
            router = routerDiagnostics
        )
    }

    fun setMaxRelayHops(maxHops: Int) {
        maxRelayHops = maxHops.coerceIn(1, MeshPacket.DEFAULT_TTL.toInt())
        if (::router.isInitialized) {
            router.setMaxRelayHops(maxRelayHops)
        }
    }

    fun getAlias(nodeId: UUID): String = aliases[nodeId]
        ?: knownPeers[nodeId]?.displayName
        ?: "@${nodeId.toString().take(8)}"

    fun getLocalFingerprint(): String = crypto.getIdentityFingerprint()

    fun getPeerFingerprint(nodeId: UUID): String? = crypto.getSessionInfo(nodeId)?.peerFingerprint

    fun getPeerSafetyNumber(nodeId: UUID): String? = crypto.getSafetyNumber(nodeId)

    fun markPeerAsVerified(nodeId: UUID) {
        crypto.getSessionInfo(nodeId)?.peerFingerprint?.let {
            crypto.markPeerAsVerified(it)
        }
    }

    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        knownPeers.entries.removeIf { now - it.value.lastSeen > KNOWN_PEER_MAX_AGE_MS }
        aliases.entries.removeIf { (nodeId, _) -> !knownPeers.containsKey(nodeId) }
    }

    private fun markPeerDirect(nodeId: UUID) {
        knownPeers[nodeId]?.let {
            it.isDirect = true
            it.hopCount = 1
            it.lastSeen = System.currentTimeMillis()
        } ?: run {
            knownPeers[nodeId] = PeerInfo(
                nodeId = nodeId,
                discoveredAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                displayName = aliases[nodeId],
                isDirect = true,
                hopCount = 1
            )
            peerListener?.invoke(nodeId, PeerEvent.DISCOVERED)
        }
    }
}

private fun encodeEncryptedPayload(encrypted: EncryptedMessage): ByteArray =
    ByteBuffer.allocate(1 + encrypted.nonce.size + encrypted.ciphertext.size)
        .put(encrypted.nonce.size.toByte())
        .put(encrypted.nonce)
        .put(encrypted.ciphertext)
        .array()

// ==================== Data Classes ====================

data class PeerInfo(
    val nodeId: UUID,
    val discoveredAt: Long,
    var lastSeen: Long,
    var displayName: String? = null,
    var isDirect: Boolean = false,
    var hopCount: Int = 2
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
    val timestamp: Long,
    val isBroadcast: Boolean,
    var lastChunkRequestAt: Long = 0L
)

data class OrphanFileChunks(
    val chunks: Array<ByteArray?>,
    val createdAt: Long
)

data class OutgoingFileTransfer(
    val recipientId: UUID,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val chunks: List<ByteArray>,
    val createdAt: Long,
    val isBroadcast: Boolean
)

data class MeshDiagnostics(
    val knownPeerCount: Int,
    val directPeerCount: Int,
    val routedPeerCount: Int,
    val pendingHandshakeMessages: Int,
    val incomingFileTransfers: Int,
    val maxRelayHops: Int,
    val router: RouterDiagnostics
)

data class FileTransferProgress(
    val transferId: UUID,
    val sentChunks: Int,
    val totalChunks: Int,
    val totalBytes: Int,
    val fileName: String,
    val mimeType: String,
    val recipientId: UUID,
    val isBroadcast: Boolean,
    val direction: TransferDirection = TransferDirection.OUTGOING,
    val status: TransferStatus = TransferStatus.ACTIVE
)

enum class TransferDirection {
    OUTGOING,
    INCOMING
}

enum class TransferStatus {
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class PeerEvent {
    DISCOVERED,
    SESSION_ESTABLISHED,
    DISCONNECTED,
    VERIFIED
}

enum class MessageDeliveryStatus {
    FAILED,
    DELIVERED,
    READ
}

sealed class SendResult {
    data class Sent(val messageId: UUID) : SendResult()
    data class Queued(val reason: String) : SendResult()
    data class Failed(val error: String) : SendResult()
}

private val FILE_META_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
private val FILE_CHUNK_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
private val FILE_CHUNK_REQUEST_MAGIC = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
private const val FILE_CHUNK_SIZE = 384
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_IMAGE_CHUNKS = 6000
private const val FILE_TRANSFER_TIMEOUT_MS = 10 * 60 * 1000L
private const val FILE_CHUNK_REQUEST_INTERVAL_MS = 2_500L
private const val MAX_CHUNK_REQUEST_INDEXES = 96
private const val KNOWN_PEER_MAX_AGE_MS = 60_000L
private const val MAX_PENDING_OUTBOX_PER_PEER = 96

private fun ByteArray.asIterableChunks(size: Int): List<ByteArray> =
    indices.step(size).map { start ->
        copyOfRange(start, minOf(start + size, this.size))
    }
