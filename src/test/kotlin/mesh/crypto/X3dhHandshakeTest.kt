package mesh.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class X3dhHandshakeTest {
    @Test
    fun `initiator and recipient derive same offline X3DH session key`() {
        val initiator = X3dhHandshake.generatePrivateBundle()
        val recipient = X3dhHandshake.generatePrivateBundle()
        val recipientPublic = X3dhHandshake.publicBundle(recipient)

        val initiatorResult = assertNotNull(
            X3dhHandshake.deriveAsInitiator(initiator.identityDh, recipientPublic)
        )
        val recipientKey = X3dhHandshake.deriveAsRecipient(
            recipientBundle = recipient,
            initiatorIdentityDhPublic = initiator.identityDh.public.encoded,
            initiatorEphemeralPublic = initiatorResult.ephemeralPublic
        )

        assertContentEquals(initiatorResult.sessionKey, recipientKey)
    }
}
