package mesh.routing

import mesh.protocol.MeshPacket
import mesh.protocol.PacketFlags
import mesh.protocol.PacketType
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshRouterTest {
    @Test
    fun `diagnostics snapshot reports router queues and neighbor cache`() {
        val localNode = UUID.randomUUID()
        val sender = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val clock = FakeClock(1_000L)
        val router = MeshRouter(
            localNode,
            RecordingHandler(authentic = true),
            RecordingTransmitter(),
            clock::now
        )

        router.onPacketReceived(
            MeshPacket(
                type = PacketType.HEARTBEAT,
                flags = PacketFlags(isEncrypted = false),
                messageId = UUID.randomUUID(),
                senderId = sender,
                recipientId = MeshPacket.BROADCAST_ID,
                timestamp = clock.now(),
                payload = ByteArray(0),
                signature = ByteArray(0)
            ),
            rssi = -42,
            sourceInterface = TransportType.BLE
        )
        router.sendMessage(
            recipientId = recipient,
            payload = byteArrayOf(1, 2, 3),
            signature = byteArrayOf(4),
            requiresAck = true
        )

        val initial = router.diagnosticsSnapshot()
        assertEquals(1, initial.neighborCount)
        assertEquals(1, initial.pendingMessageCount)
        assertEquals(0, initial.retryReadyCount)
        assertEquals(1, initial.seenMessageCount)

        clock.advance(2_500L)

        assertEquals(1, router.diagnosticsSnapshot().retryReadyCount)
    }

    @Test
    fun `pending message retries use backoff and eventually fail`() {
        val localNode = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val clock = FakeClock(1_000L)
        val handler = RecordingHandler(authentic = true)
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, handler, transmitter, clock::now)

        val messageId = router.sendMessage(
            recipientId = recipient,
            payload = byteArrayOf(1, 2, 3),
            signature = byteArrayOf(4),
            requiresAck = true
        )

        assertEquals(1, transmitter.broadcasts.size)

        router.performMaintenance()
        assertEquals(1, transmitter.broadcasts.size)

        clock.advance(2_500L)
        router.performMaintenance()
        assertEquals(2, transmitter.broadcasts.size)

        repeat(5) {
            clock.advance(60_000L)
            router.performMaintenance()
        }

        assertEquals(listOf(messageId), handler.failedDeliveries)
        assertEquals(6, transmitter.broadcasts.size)
    }

    @Test
    fun `ack removes pending message before retry failure`() {
        val localNode = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val clock = FakeClock(1_000L)
        val handler = RecordingHandler(authentic = true)
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, handler, transmitter, clock::now)

        val messageId = router.sendMessage(
            recipientId = recipient,
            payload = byteArrayOf(1),
            signature = byteArrayOf(2),
            requiresAck = true
        )

        router.onPacketReceived(
            MeshPacket(
                type = PacketType.ACK,
                flags = PacketFlags(isEncrypted = false),
                messageId = UUID.randomUUID(),
                senderId = recipient,
                recipientId = localNode,
                timestamp = clock.now(),
                payload = messageId.toPayload(),
                signature = byteArrayOf(3)
            ),
            rssi = 0,
            sourceInterface = TransportType.WIFI_DIRECT
        )

        repeat(6) {
            clock.advance(60_000L)
            router.performMaintenance()
        }

        assertEquals(listOf(messageId), handler.acks)
        assertEquals(emptyList(), handler.failedDeliveries)
        assertEquals(1, transmitter.broadcasts.size)
    }

    @Test
    fun `duplicate unicast message is not delivered twice but is acked again`() {
        val localNode = UUID.randomUUID()
        val sender = UUID.randomUUID()
        val handler = RecordingHandler(authentic = true)
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, handler, transmitter)
        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            messageId = UUID.randomUUID(),
            senderId = sender,
            recipientId = localNode,
            timestamp = 1L,
            payload = byteArrayOf(1),
            signature = byteArrayOf(2)
        )

        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)
        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)

        assertEquals(listOf(packet), handler.messages)
        assertEquals(2, transmitter.directSends.size)
        assertTrue(transmitter.directSends.all { it.first == sender && it.second.type == PacketType.ACK })
    }

    @Test
    fun `duplicate unicast file chunk is acked again`() {
        val localNode = UUID.randomUUID()
        val sender = UUID.randomUUID()
        val handler = RecordingHandler(authentic = true)
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, handler, transmitter)
        val packet = MeshPacket(
            type = PacketType.FILE_CHUNK,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            messageId = UUID.randomUUID(),
            senderId = sender,
            recipientId = localNode,
            timestamp = 1L,
            payload = byteArrayOf(1),
            signature = byteArrayOf(2)
        )

        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)
        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)

        assertEquals(listOf(packet), handler.messages)
        assertEquals(2, transmitter.directSends.size)
        assertTrue(transmitter.directSends.all { it.first == sender && it.second.type == PacketType.ACK })
    }

    @Test
    fun `unaccepted unicast message is not acked`() {
        val localNode = UUID.randomUUID()
        val sender = UUID.randomUUID()
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, RejectingHandler(), transmitter)

        router.onPacketReceived(
            MeshPacket(
                type = PacketType.MESSAGE,
                flags = PacketFlags(requiresAck = true, isEncrypted = true),
                messageId = UUID.randomUUID(),
                senderId = sender,
                recipientId = localNode,
                timestamp = 1L,
                payload = byteArrayOf(1),
                signature = byteArrayOf(2)
            ),
            rssi = -40,
            sourceInterface = TransportType.BLE
        )

        assertEquals(emptyList(), transmitter.directSends)
    }


    @Test
    fun `acked duplicate unicast can be relayed again after retry interval`() {
        val localNode = UUID.randomUUID()
        val sender = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val clock = FakeClock(1_000L)
        val transmitter = RecordingTransmitter()
        val router = MeshRouter(localNode, RecordingHandler(authentic = true), transmitter, clock::now)
        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            messageId = UUID.randomUUID(),
            senderId = sender,
            recipientId = recipient,
            timestamp = clock.now(),
            payload = byteArrayOf(1),
            signature = byteArrayOf(2)
        )

        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)
        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)
        assertEquals(1, transmitter.broadcasts.size)

        clock.advance(2_000L)
        router.onPacketReceived(packet, rssi = -40, sourceInterface = TransportType.BLE)

        assertEquals(2, transmitter.broadcasts.size)
    }

    @Test
    fun `spoofed ack is ignored when authenticator rejects it`() {
        val localNode = UUID.randomUUID()
        val ackedMessage = UUID.randomUUID()
        val handler = RecordingHandler(authentic = false)
        val router = MeshRouter(localNode, handler, RecordingTransmitter())

        router.onPacketReceived(
            MeshPacket(
                type = PacketType.ACK,
                flags = PacketFlags(isEncrypted = false),
                messageId = UUID.randomUUID(),
                senderId = UUID.randomUUID(),
                recipientId = localNode,
                timestamp = 1L,
                payload = ackedMessage.toPayload(),
                signature = ByteArray(0)
            ),
            rssi = 0,
            sourceInterface = TransportType.WIFI_DIRECT
        )

        assertEquals(emptyList(), handler.acks)
    }

    @Test
    fun `short route and receipt payloads are dropped without callback`() {
        val localNode = UUID.randomUUID()
        val handler = RecordingHandler(authentic = true)
        val router = MeshRouter(localNode, handler, RecordingTransmitter())

        listOf(PacketType.READ_RECEIPT, PacketType.ROUTE_REQ, PacketType.ROUTE_REP).forEach { type ->
            router.onPacketReceived(
                MeshPacket(
                    type = type,
                    flags = PacketFlags(isEncrypted = false),
                    messageId = UUID.randomUUID(),
                    senderId = UUID.randomUUID(),
                    recipientId = localNode,
                    timestamp = 1L,
                    payload = byteArrayOf(1, 2, 3),
                    signature = byteArrayOf(1)
                ),
                rssi = 0,
                sourceInterface = TransportType.BLE
            )
        }

        assertEquals(emptyList(), handler.readReceipts)
    }

    private class RecordingHandler(
        private val authentic: Boolean
    ) : MessageHandler {
        val messages = mutableListOf<MeshPacket>()
        val acks = mutableListOf<UUID>()
        val readReceipts = mutableListOf<UUID>()
        val failedDeliveries = mutableListOf<UUID>()

        override fun onMessageReceived(packet: MeshPacket): Boolean {
            messages += packet
            return true
        }
        override fun onNeighborDiscovered(nodeId: UUID, payload: ByteArray) = Unit
        override fun onHandshakeReceived(nodeId: UUID, publicKey: ByteArray) = Unit
        override fun onHandshakeAckReceived(nodeId: UUID, encryptedPayload: ByteArray) = Unit
        override fun onAckReceived(messageId: UUID) {
            acks += messageId
        }
        override fun onReadReceiptReceived(senderId: UUID, messageId: UUID) {
            readReceipts += messageId
        }
        override fun onDeliveryFailed(messageId: UUID) {
            failedDeliveries += messageId
        }
        override fun isControlPacketAuthentic(packet: MeshPacket): Boolean = authentic
    }

    private class RejectingHandler : MessageHandler {
        override fun onMessageReceived(packet: MeshPacket): Boolean = false
        override fun onNeighborDiscovered(nodeId: UUID, payload: ByteArray) = Unit
        override fun onHandshakeReceived(nodeId: UUID, publicKey: ByteArray) = Unit
        override fun onHandshakeAckReceived(nodeId: UUID, encryptedPayload: ByteArray) = Unit
        override fun onAckReceived(messageId: UUID) = Unit
        override fun onReadReceiptReceived(senderId: UUID, messageId: UUID) = Unit
    }

    private class RecordingTransmitter : PacketTransmitter {
        val broadcasts = mutableListOf<MeshPacket>()
        val directSends = mutableListOf<Pair<UUID, MeshPacket>>()

        override fun broadcast(packet: MeshPacket) {
            broadcasts += packet
        }

        override fun sendTo(nodeId: UUID, packet: MeshPacket) {
            directSends += nodeId to packet
        }
    }

    private class FakeClock(
        private var value: Long
    ) {
        fun now(): Long = value
        fun advance(delta: Long) {
            value += delta
        }
    }
}

private fun UUID.toPayload(): ByteArray =
    ByteBuffer.allocate(16)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()
