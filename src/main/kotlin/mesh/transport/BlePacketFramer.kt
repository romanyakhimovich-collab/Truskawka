package mesh.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object BlePacketFramer {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private const val HEADER_SIZE = 16
    private const val MAX_REASSEMBLY_BYTES = 1024 * 1024
    private val random = SecureRandom()

    fun fragment(packetBytes: ByteArray, maxFrameBytes: Int): List<ByteArray> {
        require(packetBytes.isNotEmpty()) { "packet is empty" }
        require(maxFrameBytes > HEADER_SIZE) { "BLE frame size must exceed $HEADER_SIZE bytes" }
        require(packetBytes.size <= MAX_REASSEMBLY_BYTES) {
            "packet too large for BLE framing: ${packetBytes.size}"
        }

        val chunkSize = maxFrameBytes - HEADER_SIZE
        val chunkCount = ((packetBytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        require(chunkCount <= UShort.MAX_VALUE.toInt()) {
            "packet requires too many BLE frames: $chunkCount"
        }
        val transferId = random.nextInt()

        return List(chunkCount) { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, packetBytes.size)
            val chunk = packetBytes.copyOfRange(start, end)
            ByteBuffer.allocate(HEADER_SIZE + chunk.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .putInt(transferId)
                .putShort(index.toShort())
                .putShort(chunkCount.toShort())
                .putInt(packetBytes.size)
                .put(chunk)
                .array()
        }
    }

    class Reassembler {
        private val transfers = ConcurrentHashMap<Int, PartialTransfer>()

        fun accept(frame: ByteArray): ByteArray? {
            if (!isFramed(frame)) return frame
            if (frame.size < HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            buffer.position(MAGIC.size)
            val transferId = buffer.int
            val index = buffer.short.toInt() and 0xFFFF
            val chunkCount = buffer.short.toInt() and 0xFFFF
            val totalBytes = buffer.int

            if (totalBytes !in 1..MAX_REASSEMBLY_BYTES) return null
            if (chunkCount <= 0 || index !in 0 until chunkCount) return null
            val chunk = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (chunk.isEmpty()) return null

            val transfer = transfers.compute(transferId) { _, existing ->
                existing ?: PartialTransfer(totalBytes, arrayOfNulls(chunkCount), System.currentTimeMillis())
            } ?: return null

            if (transfer.totalBytes != totalBytes || transfer.chunks.size != chunkCount) {
                transfers.remove(transferId)
                return null
            }

            transfer.chunks[index] = chunk
            if (transfer.chunks.any { it == null }) {
                cleanupExpired()
                return null
            }

            val assembled = ByteArray(totalBytes)
            var offset = 0
            transfer.chunks.forEach { part ->
                val bytes = part ?: return null
                if (offset + bytes.size > assembled.size) {
                    transfers.remove(transferId)
                    return null
                }
                System.arraycopy(bytes, 0, assembled, offset, bytes.size)
                offset += bytes.size
            }

            transfers.remove(transferId)
            return if (offset == totalBytes) assembled else null
        }

        private fun cleanupExpired() {
            val now = System.currentTimeMillis()
            transfers.entries.removeIf { now - it.value.createdAt > TRANSFER_TIMEOUT_MS }
        }
    }

    private fun isFramed(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private data class PartialTransfer(
        val totalBytes: Int,
        val chunks: Array<ByteArray?>,
        val createdAt: Long
    )

    private const val TRANSFER_TIMEOUT_MS = 2 * 60 * 1000L
}
