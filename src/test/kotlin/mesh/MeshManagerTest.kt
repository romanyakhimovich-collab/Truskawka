package mesh

import mesh.crypto.SecureKeyStorage
import mesh.crypto.TrustInfo
import mesh.protocol.MeshPacket
import mesh.protocol.PacketType
import mesh.routing.OpportunisticPacketTransmitter
import mesh.transport.BleTransportService
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshManagerTest {
    @Test
    fun `diagnostics reports pending messages waiting for handshake`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), CapturingTransport(), nodeA)
        managerA.initialize()

        val result = managerA.sendMessage(nodeB, "hello")

        assertTrue(result is SendResult.Queued)
        assertEquals(1, managerA.getDiagnostics().pendingHandshakeMessages)
    }

    @Test
    fun `private image packets are encrypted and reassembled by recipient`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val receivedFiles = mutableListOf<ByteArray>()

        managerA.initialize()
        managerB.initialize()
        managerB.setFileListener { _, _, _, bytes, _, _ -> receivedFiles += bytes }

        managerA.initiateHandshakeWith(nodeB)
        transportA.takeSingle(PacketType.HANDSHAKE).let(managerB::onWifiDirectPacketReceived)
        transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        val imageBytes = ByteArray(900) { (it % 251).toByte() }
        val result = managerA.sendImage(nodeB, "private.jpg", "image/jpeg", imageBytes)

        assertTrue(result is SendResult.Sent)
        val filePackets = transportA.takeAll().filter {
            it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
        }
        assertTrue(filePackets.isNotEmpty())
        assertTrue(filePackets.all { it.flags.isEncrypted })
        assertTrue(filePackets.none { it.payload.toString(Charsets.UTF_8).contains("private.jpg") })

        filePackets.forEach(managerB::onWifiDirectPacketReceived)

        assertContentEquals(imageBytes, assertNotNull(receivedFiles.singleOrNull()))
    }

    @Test
    fun `private image reassembles when chunks arrive before metadata`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val receivedFiles = mutableListOf<ByteArray>()

        managerA.initialize()
        managerB.initialize()
        managerB.setFileListener { _, _, _, bytes, _, _ -> receivedFiles += bytes }

        managerA.initiateHandshakeWith(nodeB)
        transportA.takeSingle(PacketType.HANDSHAKE).let(managerB::onWifiDirectPacketReceived)
        transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        val imageBytes = ByteArray(1_200) { (it % 241).toByte() }
        val result = managerA.sendImage(nodeB, "out-of-order.jpg", "image/jpeg", imageBytes)

        assertTrue(result is SendResult.Sent)
        val filePackets = transportA.takeAll().filter {
            it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
        }
        filePackets.filter { it.type == PacketType.FILE_CHUNK }
            .forEach(managerB::onWifiDirectPacketReceived)

        assertTrue(receivedFiles.isEmpty())

        filePackets.single { it.type == PacketType.FILE_META }
            .let(managerB::onWifiDirectPacketReceived)

        assertContentEquals(imageBytes, assertNotNull(receivedFiles.singleOrNull()))
    }

    @Test
    fun `private image requests and resumes missing chunk`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val receivedFiles = mutableListOf<ByteArray>()

        managerA.initialize()
        managerB.initialize()
        managerB.setFileListener { _, _, _, bytes, _, _ -> receivedFiles += bytes }

        managerA.initiateHandshakeWith(nodeB)
        transportA.takeSingle(PacketType.HANDSHAKE).let(managerB::onWifiDirectPacketReceived)
        transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        val imageBytes = ByteArray(1_200) { (it % 199).toByte() }
        val result = managerA.sendImage(nodeB, "resume.jpg", "image/jpeg", imageBytes)
        assertTrue(result is SendResult.Sent)

        val filePackets = transportA.takeAll().filter {
            it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
        }
        val meta = filePackets.single { it.type == PacketType.FILE_META }
        val chunks = filePackets.filter { it.type == PacketType.FILE_CHUNK }
        chunks.filterIndexed { index, _ -> index != 1 }
            .forEach(managerB::onWifiDirectPacketReceived)
        meta.let(managerB::onWifiDirectPacketReceived)

        assertTrue(receivedFiles.isEmpty())
        val request = transportB.takeAll().single { it.type == PacketType.FILE_CHUNK_REQUEST }
        managerA.onWifiDirectPacketReceived(request)

        val replayedChunks = transportA.takeAll().filter { it.type == PacketType.FILE_CHUNK }
        assertTrue(replayedChunks.isNotEmpty())
        replayedChunks.forEach(managerB::onWifiDirectPacketReceived)

        assertContentEquals(imageBytes, assertNotNull(receivedFiles.singleOrNull()))
    }

    @Test
    fun `incoming partial transfer is restored from disk and resumes`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val storageDir = Files.createTempDirectory("mesh-transfer-test").toFile()
        val transportA = CapturingTransport()
        val firstTransportB = CapturingTransport()
        val secondTransportB = CapturingTransport()
        val storageA = InMemorySecureKeyStorage()
        val storageB = InMemorySecureKeyStorage()
        val managerA = MeshManager(storageA, FakeBleTransport(nodeA), transportA, nodeA, storageDir.resolve("a"))
        val firstManagerB = MeshManager(storageB, FakeBleTransport(nodeB), firstTransportB, nodeB, storageDir.resolve("b"))
        val secondManagerB = MeshManager(storageB, FakeBleTransport(nodeB), secondTransportB, nodeB, storageDir.resolve("b"))
        val receivedFiles = mutableListOf<ByteArray>()

        try {
            managerA.initialize()
            firstManagerB.initialize()
            managerA.initiateHandshakeWith(nodeB)
            transportA.takeSingle(PacketType.HANDSHAKE).let(firstManagerB::onWifiDirectPacketReceived)
            firstTransportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

            val imageBytes = ByteArray(1_200) { (it % 157).toByte() }
            val result = managerA.sendImage(nodeB, "restore.jpg", "image/jpeg", imageBytes)
            assertTrue(result is SendResult.Sent)

            val filePackets = transportA.takeAll().filter {
                it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
            }
            val meta = filePackets.single { it.type == PacketType.FILE_META }
            val chunks = filePackets.filter { it.type == PacketType.FILE_CHUNK }
            chunks.filterIndexed { index, _ -> index != 1 }
                .forEach(firstManagerB::onWifiDirectPacketReceived)
            firstTransportB.takeAll()
            meta.let(firstManagerB::onWifiDirectPacketReceived)
            firstTransportB.takeAll()

            secondManagerB.setFileListener { _, _, _, bytes, _, _ -> receivedFiles += bytes }
            secondManagerB.initialize()
            secondManagerB.initiateHandshakeWith(nodeA)
            secondTransportB.takeSingle(PacketType.HANDSHAKE).let(managerA::onWifiDirectPacketReceived)
            transportA.takeSingle(PacketType.HANDSHAKE_ACK).let(secondManagerB::onWifiDirectPacketReceived)

            val request = secondTransportB.takeAll().single { it.type == PacketType.FILE_CHUNK_REQUEST }
            managerA.onWifiDirectPacketReceived(request)
            transportA.takeAll()
                .filter { it.type == PacketType.FILE_CHUNK }
                .forEach(secondManagerB::onWifiDirectPacketReceived)

            assertContentEquals(imageBytes, assertNotNull(receivedFiles.singleOrNull()))
        } finally {
            storageDir.deleteRecursively()
        }
    }

    @Test
    fun `private image without session starts handshake and is not sent in clear`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        managerA.initialize()

        val result = managerA.sendImage(nodeB, "private.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        assertTrue(result is SendResult.Failed)
        assertTrue(transportA.takeAll().none {
            it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
        })
    }

    private class CapturingTransport : OpportunisticPacketTransmitter {
        private val packets = mutableListOf<MeshPacket>()

        override fun tryBroadcast(packet: MeshPacket): Boolean {
            packets += packet
            return true
        }

        override fun trySendTo(nodeId: UUID, packet: MeshPacket): Boolean {
            packets += packet
            return true
        }

        override fun broadcast(packet: MeshPacket) {
            tryBroadcast(packet)
        }

        override fun sendTo(nodeId: UUID, packet: MeshPacket) {
            trySendTo(nodeId, packet)
        }

        fun takeSingle(type: PacketType): MeshPacket =
            takeAll().single { it.type == type }

        fun takeAll(): List<MeshPacket> =
            packets.toList().also { packets.clear() }
    }

    private class FakeBleTransport(localNodeId: UUID) : BleTransportService(localNodeId) {
        override fun initialize(): Boolean = true
        override fun startAdvertising(): Boolean = true
        override fun stopAdvertising() = Unit
        override fun startScanning(): Boolean = true
        override fun stopScanning() = Unit
        override fun connectToDevice(address: String) = Unit
        override fun disconnectDevice(address: String) = Unit
        override fun writeToDevice(address: String, data: ByteArray): Boolean = false
        override fun notifyAllDevices(data: ByteArray) = Unit
        override fun requestMtu(address: String, mtu: Int) = Unit
    }

    private class InMemorySecureKeyStorage : SecureKeyStorage {
        private var identityKey: KeyPair? = null
        private var exchangeKey: KeyPair? = null
        private var trustedPeers: List<TrustInfo> = emptyList()

        override fun saveIdentityKey(keyPair: KeyPair) {
            identityKey = keyPair
        }

        override fun loadIdentityKey(): KeyPair? = identityKey

        override fun saveExchangeKey(keyPair: KeyPair) {
            exchangeKey = keyPair
        }

        override fun loadExchangeKey(): KeyPair? = exchangeKey

        override fun saveTrustedPeers(peers: List<TrustInfo>) {
            trustedPeers = peers
        }

        override fun loadTrustedPeers(): List<TrustInfo> = trustedPeers
    }
}
