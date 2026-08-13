package mesh.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.UUID

class MeshPacketTest {
    @Test
    fun `packet binary encoding round trips`() {
        val packet = MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            hopCount = 2,
            ttl = 7,
            messageId = UUID.randomUUID(),
            senderId = UUID.randomUUID(),
            recipientId = UUID.randomUUID(),
            timestamp = 1_777_777_777L,
            payload = "payload".toByteArray(),
            signature = ByteArray(64) { it.toByte() }
        )

        val encoded = packet.toBytes()
        val decoded = MeshPacket.fromBytes(encoded)

        assertEquals(MeshPacket.HEADER_SIZE + packet.payload.size + packet.signature.size, encoded.size)
        assertEquals(packet.version, decoded.version)
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.flags, decoded.flags)
        assertEquals(packet.hopCount, decoded.hopCount)
        assertEquals(packet.ttl, decoded.ttl)
        assertEquals(packet.messageId, decoded.messageId)
        assertEquals(packet.senderId, decoded.senderId)
        assertEquals(packet.recipientId, decoded.recipientId)
        assertEquals(packet.timestamp, decoded.timestamp)
        assertContentEquals(packet.payload, decoded.payload)
        assertContentEquals(packet.signature, decoded.signature)
    }

    @Test
    fun `unknown packet type is rejected`() {
        val encoded = samplePacket().toBytes()
        encoded[1] = 0x7F

        assertFailsWith<IllegalArgumentException> {
            MeshPacket.fromBytes(encoded)
        }
    }

    @Test
    fun `unsupported protocol version is rejected`() {
        val encoded = samplePacket().toBytes()
        encoded[0] = 99

        assertFailsWith<IllegalArgumentException> {
            MeshPacket.fromBytes(encoded)
        }
    }

    @Test
    fun `packet with trailing bytes is rejected`() {
        val encoded = samplePacket().toBytes() + byteArrayOf(1)

        assertFailsWith<IllegalArgumentException> {
            MeshPacket.fromBytes(encoded)
        }
    }

    private fun samplePacket(): MeshPacket =
        MeshPacket(
            type = PacketType.MESSAGE,
            flags = PacketFlags(requiresAck = true, isEncrypted = true),
            messageId = UUID.randomUUID(),
            senderId = UUID.randomUUID(),
            recipientId = UUID.randomUUID(),
            timestamp = 1_777_777_777L,
            payload = "payload".toByteArray(),
            signature = ByteArray(64) { it.toByte() }
        )
}
