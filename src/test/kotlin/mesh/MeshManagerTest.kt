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
import kotlin.test.assertFalse
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

    @Test
    fun `queued messages are sent once with their queued ids after handshake`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val received = mutableListOf<String>()
        managerA.initialize()
        managerB.initialize()
        managerB.setMessageListener { _, message, _, _ -> received += message }

        val first = managerA.sendMessage(nodeB, "ok")
        val second = managerA.sendMessage(nodeB, "ok")

        val firstId = assertNotNull((first as SendResult.Queued).messageId)
        val secondId = assertNotNull((second as SendResult.Queued).messageId)
        assertTrue(firstId != secondId)

        transportA.takeAll().last { it.type == PacketType.HANDSHAKE }.let(managerB::onWifiDirectPacketReceived)
        transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        val sent = transportA.takeAll().filter { it.type == PacketType.MESSAGE }
        assertEquals(listOf(firstId, secondId), sent.map { it.messageId })
        sent.forEach(managerB::onWifiDirectPacketReceived)
        assertEquals(listOf("ok", "ok"), received)
        assertEquals(0, managerA.getDiagnostics().pendingHandshakeMessages)
    }

    @Test
    fun `unsigned broadcast impersonating a known peer is rejected`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val received = mutableListOf<String>()
        managerA.initialize()
        managerB.initialize()
        managerB.setMessageListener { _, message, _, _ -> received += message }

        managerA.initiateHandshakeWith(nodeB)
        transportA.takeSingle(PacketType.HANDSHAKE).let(managerB::onWifiDirectPacketReceived)
        transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        managerB.onWifiDirectPacketReceived(
            MeshPacket(
                type = PacketType.MESSAGE,
                flags = mesh.protocol.PacketFlags(isEncrypted = false),
                messageId = UUID.randomUUID(),
                senderId = nodeA,
                recipientId = MeshPacket.BROADCAST_ID,
                timestamp = System.currentTimeMillis(),
                payload = "forged".toByteArray(),
                signature = ByteArray(0)
            )
        )
        managerA.broadcastMessage("genuine")
        transportA.takeAll().filter { it.type == PacketType.MESSAGE }.forEach(managerB::onWifiDirectPacketReceived)

        assertEquals(listOf("genuine"), received)
    }

    @Test
    fun `different key for a known node id is refused until the user accepts it`() {
        // Fixed ids so the handshake tie-break is deterministic (A < B).
        val nodeA = UUID(0, 1)
        val nodeB = UUID(0, 2)
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val transportImpostor = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val realB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val impostor = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportImpostor, nodeB)
        val events = mutableListOf<PeerEvent>()
        managerA.setPeerListener { _, event -> events += event }
        managerA.initialize()
        realB.initialize()
        impostor.initialize()

        realB.initiateHandshakeWith(nodeA)
        transportB.takeSingle(PacketType.HANDSHAKE).let(managerA::onWifiDirectPacketReceived)
        transportA.takeSingle(PacketType.HANDSHAKE_ACK).let(realB::onWifiDirectPacketReceived)
        val pinned = assertNotNull(managerA.getPeerFingerprint(nodeB))

        impostor.initiateHandshakeWith(nodeA)
        transportImpostor.takeSingle(PacketType.HANDSHAKE).let(managerA::onWifiDirectPacketReceived)

        assertTrue(transportA.takeAll().none { it.type == PacketType.HANDSHAKE_ACK })
        assertEquals(pinned, managerA.getPeerFingerprint(nodeB))
        assertTrue(PeerEvent.KEY_CHANGED in events)
        assertTrue(managerA.hasPendingKeyChange(nodeB))

        assertTrue(managerA.acceptKeyChange(nodeB))
        transportA.takeSingle(PacketType.HANDSHAKE).let(impostor::onWifiDirectPacketReceived)
        transportImpostor.takeSingle(PacketType.HANDSHAKE_ACK).let(managerA::onWifiDirectPacketReceived)

        assertFalse(managerA.hasPendingKeyChange(nodeB))
        assertTrue(assertNotNull(managerA.getPeerFingerprint(nodeB)) != pinned)
    }

    @Test
    fun `simultaneous handshakes end with one shared session`() {
        val nodeA = UUID(0, 1)
        val nodeB = UUID(0, 2)
        val transportA = CapturingTransport()
        val transportB = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
        val receivedByA = mutableListOf<String>()
        val receivedByB = mutableListOf<String>()
        managerA.setMessageListener { _, message, _, _ -> receivedByA += message }
        managerB.setMessageListener { _, message, _, _ -> receivedByB += message }
        managerA.initialize()
        managerB.initialize()

        managerA.initiateHandshakeWith(nodeB)
        managerB.initiateHandshakeWith(nodeA)
        val handshakeFromA = transportA.takeSingle(PacketType.HANDSHAKE)
        val handshakeFromB = transportB.takeSingle(PacketType.HANDSHAKE)
        managerB.onWifiDirectPacketReceived(handshakeFromA)
        managerA.onWifiDirectPacketReceived(handshakeFromB)
        transportB.takeAll().filter { it.type == PacketType.HANDSHAKE_ACK }.forEach(managerA::onWifiDirectPacketReceived)
        transportA.takeAll().filter { it.type == PacketType.HANDSHAKE_ACK }.forEach(managerB::onWifiDirectPacketReceived)

        assertTrue(managerA.sendMessage(nodeB, "hi") is SendResult.Sent)
        transportA.takeAll().filter { it.type == PacketType.MESSAGE }.forEach(managerB::onWifiDirectPacketReceived)
        assertTrue(managerB.sendMessage(nodeA, "yo") is SendResult.Sent)
        transportB.takeAll().filter { it.type == PacketType.MESSAGE }.forEach(managerA::onWifiDirectPacketReceived)

        assertEquals(listOf("hi"), receivedByB)
        assertEquals(listOf("yo"), receivedByA)
    }

    @Test
    fun `unconfirmed messages survive a restart and are sent with the same id`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val storageDir = Files.createTempDirectory("mesh-outbox-test").toFile()
        val storageA = InMemorySecureKeyStorage()
        try {
            val firstA = MeshManager(storageA, FakeBleTransport(nodeA), CapturingTransport(), nodeA, storageDir.resolve("a"))
            firstA.initialize()
            val queuedId = assertNotNull((firstA.sendMessage(nodeB, "later") as SendResult.Queued).messageId)

            // "Restart": a new manager over the same storage.
            val transportA = CapturingTransport()
            val transportB = CapturingTransport()
            val secondA = MeshManager(storageA, FakeBleTransport(nodeA), transportA, nodeA, storageDir.resolve("a"))
            val managerB = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeB), transportB, nodeB)
            val received = mutableListOf<String>()
            managerB.setMessageListener { _, message, _, _ -> received += message }
            secondA.initialize()
            managerB.initialize()
            assertEquals(1, secondA.getDiagnostics().pendingHandshakeMessages)

            secondA.initiateHandshakeWith(nodeB)
            transportA.takeAll().last { it.type == PacketType.HANDSHAKE }.let(managerB::onWifiDirectPacketReceived)
            transportB.takeSingle(PacketType.HANDSHAKE_ACK).let(secondA::onWifiDirectPacketReceived)
            val sent = transportA.takeAll().single { it.type == PacketType.MESSAGE }
            assertEquals(queuedId, sent.messageId)
            managerB.onWifiDirectPacketReceived(sent)
            assertEquals(listOf("later"), received)

            // Recipient's ACK removes it from disk.
            transportB.takeAll().filter { it.type == PacketType.ACK }.forEach(secondA::onWifiDirectPacketReceived)
            assertTrue(storageDir.resolve("a/outbox").listFiles().orEmpty().none { it.name.endsWith(".msg") })
        } finally {
            storageDir.deleteRecursively()
        }
    }

    @Test
    fun `tampered file chunks fail the integrity check`() {
        val nodeA = UUID.randomUUID()
        val transportA = CapturingTransport()
        val managerA = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(nodeA), transportA, nodeA)
        managerA.initialize()
        val imageBytes = ByteArray(1_000) { (it % 211).toByte() }
        assertTrue(managerA.sendImage(null, "public.jpg", "image/jpeg", imageBytes) is SendResult.Sent)
        val packets = transportA.takeAll().filter {
            it.type == PacketType.FILE_META || it.type == PacketType.FILE_CHUNK
        }

        fun receiver(): Pair<MeshManager, MutableList<ByteArray>> {
            val node = UUID.randomUUID()
            val files = mutableListOf<ByteArray>()
            val manager = MeshManager(InMemorySecureKeyStorage(), FakeBleTransport(node), CapturingTransport(), node)
            manager.initialize()
            manager.setFileListener { _, _, _, bytes, _, _ -> files += bytes }
            return manager to files
        }

        val (honest, honestFiles) = receiver()
        packets.forEach(honest::onWifiDirectPacketReceived)
        assertContentEquals(imageBytes, assertNotNull(honestFiles.singleOrNull()))

        val (victim, victimFiles) = receiver()
        val tamperedIndex = packets.indexOfLast { it.type == PacketType.FILE_CHUNK }
        packets.mapIndexed { index, packet ->
            if (index != tamperedIndex) packet else packet.copy(
                payload = packet.payload.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
            )
        }.forEach(victim::onWifiDirectPacketReceived)
        assertTrue(victimFiles.isEmpty())
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
