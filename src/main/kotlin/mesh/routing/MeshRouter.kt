package mesh.routing

import mesh.protocol.MeshPacket
import mesh.protocol.PacketType
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Mesh Router implementing Epidemic Routing with Controlled Flooding.
 *
 * Algorithm Selection Rationale:
 * - AODV/DSR: Too complex for sparse mountain networks, high route discovery overhead
 * - Pure Flooding: Works but wastes bandwidth on dense networks
 * - Epidemic: Perfect for delay-tolerant networks with intermittent connectivity
 *
 * Our Hybrid Approach:
 * 1. Flood discovery packets to find neighbors
 * 2. Use epidemic routing for message delivery (store-and-forward)
 * 3. Probabilistic forwarding to reduce redundancy when network is dense
 */
class MeshRouter(
    private val localNodeId: UUID,
    private val messageHandler: MessageHandler,
    private val transmitter: PacketTransmitter
) {
    private var maxRelayHops: Byte = MeshPacket.DEFAULT_TTL
    // Deduplication: seen message IDs with timestamp (auto-expire after 1 hour)
    private val seenMessages = ConcurrentHashMap<UUID, Long>()
    private val seenMessagesMaxAge = 3600_000L // 1 hour

    // Store-and-forward queue: messages waiting for delivery
    private val pendingMessages = ConcurrentLinkedDeque<PendingMessage>()
    private val maxPendingMessages = 1000
    private val pendingMessageTTL = 24 * 3600_000L // 24 hours

    // Known neighbors with last-seen timestamp
    private val neighbors = ConcurrentHashMap<UUID, NeighborInfo>()
    private val neighborTimeout = 300_000L // 5 minutes

    // Routing table: destination -> next hop (for optimized routing)
    private val routingTable = ConcurrentHashMap<UUID, RouteEntry>()

    /**
     * Main entry point: called when a packet is received from any interface
     *
     * PSEUDOCODE:
     * ```
     * onMessageReceived(packet):
     *     // Step 1: Deduplication
     *     if packet.messageId in seenMessages:
     *         return  // Already processed
     *     seenMessages.add(packet.messageId, currentTime)
     *
     *     // Step 2: TTL Check
     *     if packet.hopCount >= packet.ttl:
     *         return  // Exceeded hop limit
     *
     *     // Step 3: Update neighbor info
     *     updateNeighbor(packet.senderId, rssi, interface)
     *
     *     // Step 4: Check if we're the destination
     *     if packet.recipientId == localNodeId OR packet.isBroadcast():
     *         deliverToApplication(packet)
     *         if !packet.isBroadcast():
     *             sendAck(packet)
     *             return  // Don't relay unicast to self
     *
     *     // Step 5: Relay decision
     *     if shouldRelay(packet):
     *         relayPacket = packet.incrementHopCount()
     *         scheduleTransmission(relayPacket)
     * ```
     */
    fun onPacketReceived(packet: MeshPacket, rssi: Int, sourceInterface: TransportType) {
        val now = System.currentTimeMillis()

        // Step 1: Deduplication check
        if (isDuplicate(packet.messageId)) {
            return
        }
        markAsSeen(packet.messageId, now)

        // Step 2: TTL check
        if (packet.isExpired()) {
            return
        }

        // Step 3: Update neighbor table
        updateNeighborInfo(packet.senderId, rssi, sourceInterface, now)

        // Step 4: Handle by packet type
        when (packet.type) {
            PacketType.DISCOVERY -> handleDiscovery(packet)
            PacketType.HANDSHAKE -> handleHandshake(packet)
            PacketType.HANDSHAKE_ACK -> handleHandshakeAck(packet)
            PacketType.MESSAGE -> handleMessage(packet)
            PacketType.ACK -> handleAck(packet)
            PacketType.READ_RECEIPT -> handleReadReceipt(packet)
            PacketType.ROUTE_REQ -> handleRouteRequest(packet)
            PacketType.ROUTE_REP -> handleRouteReply(packet)
            PacketType.HEARTBEAT -> { /* Just neighbor update above */ }
            else -> handleMessage(packet) // Default: treat as message
        }
    }

    private fun handleMessage(packet: MeshPacket) {
        // Check if we're the destination
        val isForUs = packet.recipientId == localNodeId
        val isBroadcast = packet.isBroadcast()

        if (isForUs || isBroadcast) {
            // Deliver to application layer
            messageHandler.onMessageReceived(packet)

            // Send ACK for unicast messages
            if (isForUs && packet.flags.requiresAck) {
                sendAcknowledgment(packet)
            }

            // Don't relay unicast messages intended for us
            if (isForUs && !isBroadcast) {
                return
            }
        }

        // Relay decision
        if (shouldRelay(packet)) {
            relayPacket(packet)
        }
    }

    /**
     * Decides whether to relay a packet (probabilistic for dense networks)
     */
    private fun shouldRelay(packet: MeshPacket): Boolean {
        // Always relay if we have few neighbors (sparse network)
        val neighborCount = neighbors.size
        if (neighborCount <= 3) {
            return true
        }

        // Probabilistic relay for denser networks to reduce flooding
        // P(relay) = min(1, K/neighborCount) where K = 3
        val relayProbability = minOf(1.0, 3.0 / neighborCount)
        return Math.random() < relayProbability
    }

    private fun relayPacket(packet: MeshPacket) {
        val relayPacket = packet.forRelay() ?: return // TTL exceeded
        transmitter.broadcast(relayPacket)
    }

    /**
     * Send a new message (called by application layer)
     */
    fun sendMessage(
        recipientId: UUID,
        payload: ByteArray,
        signature: ByteArray,
        requiresAck: Boolean = true
    ): UUID {
        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = mesh.protocol.PacketFlags(
                requiresAck = requiresAck,
                isEncrypted = true
            ),
            ttl = maxRelayHops,
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = recipientId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = signature
        )

        // Check if recipient is a known neighbor
        if (neighbors.containsKey(recipientId)) {
            // Direct delivery
            transmitter.sendTo(recipientId, packet)
        } else {
            // Flood to network
            transmitter.broadcast(packet)
        }

        // Store for retry if ACK required
        if (requiresAck) {
            storePendingMessage(packet)
        }
        return packet.messageId
    }

    fun setMaxRelayHops(maxHops: Int) {
        maxRelayHops = maxHops.coerceIn(1, MeshPacket.DEFAULT_TTL.toInt()).toByte()
    }

    /**
     * Store-and-Forward: queue message for later delivery
     */
    private fun storePendingMessage(packet: MeshPacket) {
        if (pendingMessages.size >= maxPendingMessages) {
            pendingMessages.pollFirst() // Remove oldest
        }
        pendingMessages.add(PendingMessage(
            packet = packet,
            storedAt = System.currentTimeMillis(),
            retryCount = 0
        ))
    }

    /**
     * Called when a new neighbor is discovered - triggers store-and-forward sync
     */
    fun onNeighborDiscovered(neighborId: UUID) {
        val now = System.currentTimeMillis()

        // Check pending messages for this neighbor
        pendingMessages.forEach { pending ->
            if (pending.packet.recipientId == neighborId &&
                now - pending.storedAt < pendingMessageTTL) {
                // Found a pending message for this neighbor!
                transmitter.sendTo(neighborId, pending.packet)
                pending.retryCount++
            }
        }

        // Also exchange message summaries for epidemic sync
        initiateEpidemicSync(neighborId)
    }

    /**
     * Epidemic sync: exchange message IDs to find missing messages
     */
    private fun initiateEpidemicSync(neighborId: UUID) {
        // Send summary vector of messages we have
        // Neighbor responds with messages we're missing
        // This is a simplified epidemic routing protocol
    }

    private fun handleDiscovery(packet: MeshPacket) {
        messageHandler.onNeighborDiscovered(packet.senderId, packet.payload)
        if (shouldRelay(packet)) {
            relayPacket(packet)
        }
    }

    private fun handleHandshake(packet: MeshPacket) {
        if (packet.recipientId == localNodeId) {
            messageHandler.onHandshakeReceived(packet.senderId, packet.payload)
        } else if (shouldRelay(packet)) {
            relayPacket(packet)
        }
    }

    private fun handleHandshakeAck(packet: MeshPacket) {
        if (packet.recipientId == localNodeId) {
            messageHandler.onHandshakeAckReceived(packet.senderId, packet.payload)
        } else if (shouldRelay(packet)) {
            relayPacket(packet)
        }
    }

    private fun handleAck(packet: MeshPacket) {
        if (packet.recipientId != localNodeId) {
            if (shouldRelay(packet)) {
                relayPacket(packet)
            }
            return
        }
        if (packet.payload.size < 16) return
        val buffer = ByteBuffer.wrap(packet.payload)
        val ackedMessageId = UUID(buffer.long, buffer.long)
        pendingMessages.removeIf { it.packet.messageId == ackedMessageId }
        messageHandler.onAckReceived(ackedMessageId)
    }

    private fun handleReadReceipt(packet: MeshPacket) {
        val isForUs = packet.recipientId == localNodeId
        if (isForUs) {
            val readMessageId = UUID(
                java.nio.ByteBuffer.wrap(packet.payload).long,
                java.nio.ByteBuffer.wrap(packet.payload, 8, 8).long
            )
            messageHandler.onReadReceiptReceived(packet.senderId, readMessageId)
            return
        }

        if (shouldRelay(packet)) {
            relayPacket(packet)
        }
    }

    private fun handleRouteRequest(packet: MeshPacket) {
        // Simplified AODV route request
        val targetId = UUID(
            java.nio.ByteBuffer.wrap(packet.payload).long,
            java.nio.ByteBuffer.wrap(packet.payload, 8, 8).long
        )

        if (targetId == localNodeId) {
            // We are the target, send route reply
            sendRouteReply(packet.senderId, packet.messageId)
        } else {
            // Forward route request
            relayPacket(packet)
        }
    }

    private fun handleRouteReply(packet: MeshPacket) {
        // Update routing table
        val targetId = UUID(
            java.nio.ByteBuffer.wrap(packet.payload).long,
            java.nio.ByteBuffer.wrap(packet.payload, 8, 8).long
        )
        routingTable[targetId] = RouteEntry(
            nextHop = packet.senderId,
            hopCount = packet.hopCount.toInt(),
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun sendRouteReply(requesterId: UUID, requestMessageId: UUID) {
        val payload = java.nio.ByteBuffer.allocate(16)
            .putLong(localNodeId.mostSignificantBits)
            .putLong(localNodeId.leastSignificantBits)
            .array()

        val packet = MeshPacket(
            type = PacketType.ROUTE_REP,
            flags = mesh.protocol.PacketFlags(),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = requesterId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = ByteArray(0) // Route packets don't need signature
        )
        transmitter.broadcast(packet)
    }

    private fun sendAcknowledgment(originalPacket: MeshPacket) {
        val payload = ByteBuffer.allocate(16)
            .putLong(originalPacket.messageId.mostSignificantBits)
            .putLong(originalPacket.messageId.leastSignificantBits)
            .array()

        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            flags = mesh.protocol.PacketFlags(),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = originalPacket.senderId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            signature = ByteArray(0)
        )
        if (neighbors.containsKey(originalPacket.senderId)) {
            transmitter.sendTo(originalPacket.senderId, ackPacket)
        } else {
            transmitter.broadcast(ackPacket)
        }
    }

    // --- Helper methods ---

    private fun isDuplicate(messageId: UUID): Boolean {
        return seenMessages.containsKey(messageId)
    }

    private fun markAsSeen(messageId: UUID, timestamp: Long) {
        seenMessages[messageId] = timestamp
        // Cleanup old entries periodically
        if (seenMessages.size > 10000) {
            cleanupSeenMessages(timestamp)
        }
    }

    private fun cleanupSeenMessages(now: Long) {
        seenMessages.entries.removeIf { now - it.value > seenMessagesMaxAge }
    }

    private fun updateNeighborInfo(
        nodeId: UUID,
        rssi: Int,
        transport: TransportType,
        timestamp: Long
    ) {
        neighbors.compute(nodeId) { _, existing ->
            NeighborInfo(
                nodeId = nodeId,
                rssi = rssi,
                lastSeen = timestamp,
                transport = transport,
                messageCount = (existing?.messageCount ?: 0) + 1
            )
        }
    }

    /**
     * Periodic maintenance: cleanup expired entries, retry pending messages
     */
    fun performMaintenance() {
        val now = System.currentTimeMillis()

        // Cleanup expired neighbors
        neighbors.entries.removeIf { now - it.value.lastSeen > neighborTimeout }

        // Cleanup expired pending messages
        pendingMessages.removeIf { now - it.storedAt > pendingMessageTTL }

        // Cleanup old seen messages
        cleanupSeenMessages(now)

        // Retry pending messages
        retryPendingMessages()
    }

    private fun retryPendingMessages() {
        pendingMessages.forEach { pending ->
            if (pending.retryCount < 5) { // Max 5 retries
                // Check if any neighbor might reach the destination
                transmitter.broadcast(pending.packet)
                pending.retryCount++
            }
        }
    }
}

// --- Supporting data classes ---

data class NeighborInfo(
    val nodeId: UUID,
    val rssi: Int,                    // Signal strength
    val lastSeen: Long,               // Timestamp
    val transport: TransportType,     // BLE or WiFi Direct
    val messageCount: Int = 0         // Messages exchanged
)

data class RouteEntry(
    val nextHop: UUID,
    val hopCount: Int,
    val lastUpdated: Long
)

data class PendingMessage(
    val packet: MeshPacket,
    val storedAt: Long,
    var retryCount: Int
)

enum class TransportType {
    BLE,
    WIFI_DIRECT,
    BOTH
}

/**
 * Interface for sending packets over physical layer
 */
interface PacketTransmitter {
    fun broadcast(packet: MeshPacket)
    fun sendTo(nodeId: UUID, packet: MeshPacket)
}

interface OpportunisticPacketTransmitter : PacketTransmitter {
    fun tryBroadcast(packet: MeshPacket): Boolean
    fun trySendTo(nodeId: UUID, packet: MeshPacket): Boolean
}

/**
 * Callbacks to application layer
 */
interface MessageHandler {
    fun onMessageReceived(packet: MeshPacket)
    fun onNeighborDiscovered(nodeId: UUID, payload: ByteArray)
    fun onHandshakeReceived(nodeId: UUID, publicKey: ByteArray)
    fun onHandshakeAckReceived(nodeId: UUID, encryptedPayload: ByteArray)
    fun onAckReceived(messageId: UUID)
    fun onReadReceiptReceived(senderId: UUID, messageId: UUID)
}
