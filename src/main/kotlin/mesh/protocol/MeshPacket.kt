package mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Mesh network packet structure for offline P2P messaging.
 * Uses compact binary encoding for BLE efficiency.
 */
data class MeshPacket(
    val version: Byte = PROTOCOL_VERSION,
    val type: PacketType,
    val flags: PacketFlags,
    val hopCount: Byte = 0,
    val ttl: Byte = DEFAULT_TTL,
    val messageId: UUID,
    val senderId: UUID,
    val recipientId: UUID,          // Use BROADCAST_ID for broadcast
    val timestamp: Long,            // Unix milliseconds
    val payload: ByteArray,
    val signature: ByteArray        // Ed25519 signature (64 bytes)
) {
    companion object {
        const val PROTOCOL_VERSION: Byte = 1
        const val DEFAULT_TTL: Byte = 10           // Max 10 hops
        const val HEADER_SIZE = 70                  // Fixed binary header size
        const val MAX_BLE_PAYLOAD = 512            // BLE MTU constraint
        const val MAX_WIFI_PAYLOAD = 65535         // Wi-Fi Direct

        val BROADCAST_ID: UUID = UUID(0L, 0L)      // All zeros = broadcast

        /**
         * Deserialize packet from binary data
         */
        fun fromBytes(data: ByteArray): MeshPacket {
            require(data.size >= HEADER_SIZE) { "Packet too small: ${data.size} bytes" }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get()
            val type = PacketType.fromByte(buffer.get())
            val flags = PacketFlags.fromByte(buffer.get())
            val hopCount = buffer.get()
            val ttl = buffer.get()
            buffer.position(buffer.position() + 3) // Skip reserved

            val messageId = UUID(buffer.long, buffer.long)
            val senderId = UUID(buffer.long, buffer.long)
            val recipientId = UUID(buffer.long, buffer.long)
            val timestamp = buffer.long

            val payloadLength = buffer.int
            val signatureLength = buffer.short.toInt()
            require(payloadLength >= 0) { "Invalid payload length: $payloadLength" }
            require(signatureLength >= 0) { "Invalid signature length: $signatureLength" }
            require(buffer.remaining() >= payloadLength + signatureLength) {
                "Packet truncated: expected ${payloadLength + signatureLength} bytes, got ${buffer.remaining()}"
            }

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            val signature = ByteArray(signatureLength)
            buffer.get(signature)

            return MeshPacket(
                version, type, flags, hopCount, ttl,
                messageId, senderId, recipientId, timestamp,
                payload, signature
            )
        }
    }

    /**
     * Serialize packet to binary for transmission
     */
    fun toBytes(): ByteArray {
        require(signature.size <= Short.MAX_VALUE) {
            "Signature too large for protocol header: ${signature.size} bytes"
        }
        val totalSize = HEADER_SIZE + payload.size + signature.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(version)
        buffer.put(type.value)
        buffer.put(flags.toByte())
        buffer.put(hopCount)
        buffer.put(ttl)
        buffer.put(ByteArray(3)) // Reserved

        buffer.putLong(messageId.mostSignificantBits)
        buffer.putLong(messageId.leastSignificantBits)
        buffer.putLong(senderId.mostSignificantBits)
        buffer.putLong(senderId.leastSignificantBits)
        buffer.putLong(recipientId.mostSignificantBits)
        buffer.putLong(recipientId.leastSignificantBits)
        buffer.putLong(timestamp)

        buffer.putInt(payload.size)
        buffer.putShort(signature.size.toShort())
        buffer.put(payload)
        buffer.put(signature)

        return buffer.array()
    }

    /**
     * Create a relay copy with incremented hop count
     */
    fun forRelay(): MeshPacket? {
        if (hopCount >= ttl) return null // TTL exceeded
        return copy(hopCount = (hopCount + 1).toByte())
    }

    /**
     * Check if packet is a broadcast
     */
    fun isBroadcast(): Boolean = recipientId == BROADCAST_ID

    /**
     * Check if TTL is exceeded
     */
    fun isExpired(): Boolean = hopCount >= ttl

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}

/**
 * Packet types for mesh protocol
 */
enum class PacketType(val value: Byte) {
    DISCOVERY(0x01),       // Node discovery/announcement
    HANDSHAKE(0x02),       // Key exchange initiation
    HANDSHAKE_ACK(0x03),   // Key exchange response
    MESSAGE(0x10),         // Encrypted text message
    ACK(0x11),             // Delivery acknowledgment
    READ_RECEIPT(0x12),    // Message read receipt
    FILE_META(0x20),       // File transfer metadata
    FILE_CHUNK(0x21),      // File data chunk
    ROUTE_REQ(0x30),       // Route request (AODV)
    ROUTE_REP(0x31),       // Route reply (AODV)
    ROUTE_ERR(0x32),       // Route error
    HEARTBEAT(0x40);       // Keep-alive ping

    companion object {
        fun fromByte(value: Byte): PacketType =
            entries.find { it.value == value } ?: MESSAGE
    }
}

/**
 * Packet flags for transmission control
 */
data class PacketFlags(
    val requiresAck: Boolean = false,
    val isEncrypted: Boolean = true,
    val isFragmented: Boolean = false,
    val isUrgent: Boolean = false,
    val useBleOnly: Boolean = false,
    val useWifiDirect: Boolean = false
) {
    fun toByte(): Byte {
        var flags = 0
        if (requiresAck) flags = flags or 0x01
        if (isEncrypted) flags = flags or 0x02
        if (isFragmented) flags = flags or 0x04
        if (isUrgent) flags = flags or 0x08
        if (useBleOnly) flags = flags or 0x10
        if (useWifiDirect) flags = flags or 0x20
        return flags.toByte()
    }

    companion object {
        fun fromByte(value: Byte): PacketFlags {
            val v = value.toInt()
            return PacketFlags(
                requiresAck = (v and 0x01) != 0,
                isEncrypted = (v and 0x02) != 0,
                isFragmented = (v and 0x04) != 0,
                isUrgent = (v and 0x08) != 0,
                useBleOnly = (v and 0x10) != 0,
                useWifiDirect = (v and 0x20) != 0
            )
        }
    }
}
