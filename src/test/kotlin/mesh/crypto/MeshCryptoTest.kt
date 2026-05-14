package mesh.crypto

import java.security.KeyPair
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshCryptoTest {
    @Test
    fun `offline handshake establishes shared encryption session`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val cryptoA = MeshCrypto(InMemorySecureKeyStorage()).also { it.initialize() }
        val cryptoB = MeshCrypto(InMemorySecureKeyStorage()).also { it.initialize() }

        val handshake = cryptoA.createHandshakePayload(nodeB)
        val ack = cryptoB.handleHandshakeAndCreateAck(nodeA, handshake)

        assertTrue(ack is HandshakeResult.Success)
        val completed = cryptoA.handleHandshakeAck(nodeB, ack.ackPayload)
        assertTrue(completed is HandshakeResult.Success)

        val encrypted = assertNotNull(cryptoA.encryptMessage(nodeB, "summit check-in".toByteArray()))
        val decrypted = assertNotNull(cryptoB.decryptMessage(nodeA, encrypted))

        assertContentEquals("summit check-in".toByteArray(), decrypted)
    }
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
