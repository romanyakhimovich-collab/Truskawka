package com.example.bluetoothmanager

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSupportTest {
    @Test
    fun `password backup decrypts with matching password`() {
        val plain = "TSK1\t123\nCRC\t00000000".toByteArray()
        val encrypted = assertNotNull(BackupSupport.encrypt(plain, "correct horse".toCharArray()))

        assertTrue(BackupSupport.isPasswordBackup(encrypted))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("TSK1"))
        assertContentEquals(
            plain,
            assertNotNull(BackupSupport.decrypt(encrypted, "correct horse".toCharArray()))
        )
    }

    @Test
    fun `password backup rejects wrong password`() {
        val plain = byteArrayOf(1, 2, 3, 4)
        val encrypted = assertNotNull(BackupSupport.encrypt(plain, "right-password".toCharArray()))

        assertNull(BackupSupport.decrypt(encrypted, "wrong-password".toCharArray()))
    }

    @Test
    fun `empty password is rejected`() {
        assertNull(BackupSupport.encrypt(byteArrayOf(1), CharArray(0)))
        assertNull(BackupSupport.decrypt(byteArrayOf('T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), '2'.code.toByte()), CharArray(0)))
    }
}
