package mesh.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlePacketFramerTest {
    @Test
    fun `fragmented packet reassembles in order`() {
        val packet = ByteArray(257) { it.toByte() }
        val frames = BlePacketFramer.fragment(packet, maxFrameBytes = 23)
        val reassembler = BlePacketFramer.Reassembler()

        frames.dropLast(1).forEach { frame ->
            assertNull(reassembler.accept(frame))
        }

        assertContentEquals(packet, reassembler.accept(frames.last()))
        assertTrue(frames.all { it.size <= 23 })
    }

    @Test
    fun `fragmented packet reassembles out of order`() {
        val packet = "offline mesh packets can arrive out of order".repeat(20).toByteArray()
        val frames = BlePacketFramer.fragment(packet, maxFrameBytes = 64)
        val reassembler = BlePacketFramer.Reassembler()

        frames.reversed().dropLast(1).forEach { frame ->
            assertNull(reassembler.accept(frame))
        }

        assertContentEquals(packet, reassembler.accept(frames.first()))
    }

    @Test
    fun `unframed packet is accepted for compatibility`() {
        val packet = "legacy packet".toByteArray()
        val reassembler = BlePacketFramer.Reassembler()

        assertContentEquals(packet, reassembler.accept(packet))
    }

    @Test
    fun `large packets use multiple frames`() {
        val packet = ByteArray(800) { (it % 251).toByte() }
        val frames = BlePacketFramer.fragment(packet, maxFrameBytes = 80)

        assertTrue(frames.size > 1)
        assertEquals(80, frames.first().size)
    }
}
