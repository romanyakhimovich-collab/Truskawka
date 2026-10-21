package com.example.bluetoothmanager

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupProtocolTest {
    @Test
    fun `text envelope round-trips name, members and body with separators`() {
        val groupId = UUID.randomUUID()
        val members = listOf(UUID.randomUUID(), UUID.randomUUID())
        val encoded = GroupProtocol.encodeText(groupId, "Поход | 2026", members, "see you at 5|ok")

        val decoded = assertNotNull(GroupProtocol.decodeText(encoded))

        assertEquals(groupId, decoded.groupId)
        assertEquals("Поход | 2026", decoded.name)
        assertEquals(members, decoded.members)
        assertEquals("see you at 5|ok", decoded.body)
    }

    @Test
    fun `plain and malformed messages are not group envelopes`() {
        assertNull(GroupProtocol.decodeText("hello"))
        assertNull(GroupProtocol.decodeText("${GroupProtocol.TEXT_PREFIX}not-a-uuid|x|y|z"))
        assertNull(GroupProtocol.decodeText("${GroupProtocol.TEXT_PREFIX}${UUID.randomUUID()}|only-two"))
    }

    @Test
    fun `file names carry the group id`() {
        val groupId = UUID.randomUUID()
        val decoded = assertNotNull(GroupProtocol.decodeFileName(GroupProtocol.encodeFileName(groupId, "voice_1.m4a")))
        assertEquals(groupId, decoded.first)
        assertEquals("voice_1.m4a", decoded.second)
        assertNull(GroupProtocol.decodeFileName("photo.jpg"))
    }

    @Test
    fun `chat key maps back to group id`() {
        val groupId = UUID.randomUUID()
        assertEquals(groupId, GroupProtocol.groupIdFromChatKey(GroupProtocol.chatKey(groupId)))
        assertNull(GroupProtocol.groupIdFromChatKey("peer:$groupId"))
    }
}
