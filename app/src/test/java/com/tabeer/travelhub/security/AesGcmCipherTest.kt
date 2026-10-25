package com.tabeer.travelhub.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCipherTest {
    private class Fixed(private val k: SecretKey) : KeyProvider { override fun getKey() = k }
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val aad = "portal-1".toByteArray()

    @Test fun roundTrip() {
        val c = AesGcmCipher(Fixed(newKey()))
        val blob = c.encrypt("s3cret-päss".toByteArray(), aad)
        assertEquals("s3cret-päss", String(c.decrypt(blob, aad)))
    }

    @Test fun ciphertextDoesNotContainPlaintextAndIvIsUnique() {
        val c = AesGcmCipher(Fixed(newKey()))
        val plain = "hunter2-hunter2".toByteArray()
        val a = c.encrypt(plain, aad)
        val b = c.encrypt(plain, aad)
        assertFalse(String(a, Charsets.ISO_8859_1).contains("hunter2"))
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = CryptoException::class) fun wrongKeyFails() {
        val blob = AesGcmCipher(Fixed(newKey())).encrypt("x".toByteArray(), aad)
        AesGcmCipher(Fixed(newKey())).decrypt(blob, aad)
    }

    @Test(expected = CryptoException::class) fun tamperedCiphertextFails() {
        val c = AesGcmCipher(Fixed(newKey()))
        val blob = c.encrypt("hello world".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        c.decrypt(blob, aad)
    }

    @Test(expected = CryptoException::class) fun wrongAssociatedDataFails() {
        val c = AesGcmCipher(Fixed(newKey()))
        c.decrypt(c.encrypt("x".toByteArray(), aad), "portal-2".toByteArray())
    }

    @Test(expected = CryptoException::class) fun truncatedDataFails() {
        AesGcmCipher(Fixed(newKey())).decrypt(ByteArray(5), aad)
    }

    @Test fun exceptionMessagesDoNotLeakSecrets() {
        val c = AesGcmCipher(Fixed(newKey()))
        val blob = c.encrypt("topsecret".toByteArray(), aad)
        blob[20] = (blob[20].toInt() xor 1).toByte()
        val e = try { c.decrypt(blob, aad); null } catch (e: CryptoException) { e }
        assertNotNull(e)
        assertFalse(e!!.message!!.contains("topsecret"))
    }
}
