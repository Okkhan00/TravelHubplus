package com.tabeer.travelhub.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Fixed(private val k: SecretKey) : KeyProvider { override fun getKey() = k }
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file() = File(tmp.root, "vault.json")
    private fun store(k: SecretKey = key) = CredentialStore(file(), AesGcmCipher(Fixed(k)))

    @Test fun saveGetDelete() {
        val s = store()
        assertTrue(s.save("p1", Credentials("alice", "pa55")))
        assertEquals(Credentials("alice", "pa55"), s.get("p1"))
        assertTrue(s.has("p1"))
        s.delete("p1")
        assertNull(s.get("p1"))
        assertFalse(s.has("p1"))
    }

    @Test fun neverWrittenInPlaintext() {
        val s = store()
        s.save("p1", Credentials("alice_user", "SuperSecretPassw0rd"))
        val raw = file().readText()
        assertFalse(raw.contains("SuperSecretPassw0rd"))
        assertFalse(raw.contains("alice_user"))
    }

    @Test fun persistsAcrossInstances() {
        store().save("p1", Credentials("u", "p"))
        assertEquals(Credentials("u", "p"), store().get("p1"))
    }

    @Test fun wrongKeyReturnsNullInsteadOfThrowing() {
        store().save("p1", Credentials("u", "p"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val s = store(other)
        assertTrue(s.has("p1"))
        assertNull(s.get("p1"))
    }

    @Test fun blobSwappedBetweenPortalsIsRejected() {
        val s = store()
        s.save("p1", Credentials("u1", "p1"))
        s.save("p2", Credentials("u2", "p2"))
        val json = org.json.JSONObject(file().readText())
        val e = json.getJSONObject("entries")
        e.put("p2", e.getString("p1"))
        file().writeText(json.toString())
        assertNull(store().get("p2"))
    }

    @Test fun corruptedFileIsQuarantinedNotCrashing() {
        file().writeText("garbage{{")
        val s = store()
        assertEquals(0, s.count())
        assertTrue(File(file().path + ".corrupt").exists())
        assertTrue(s.save("p1", Credentials("u", "p")))
    }

    @Test fun corruptedEntryReturnsNull() {
        val s = store()
        s.save("p1", Credentials("u", "p"))
        val json = org.json.JSONObject(file().readText())
        json.getJSONObject("entries").put("p1", "AAAA")
        file().writeText(json.toString())
        assertNull(store().get("p1"))
    }

    @Test fun toStringIsRedacted() {
        assertFalse(Credentials("alice", "pw123").toString().contains("pw123"))
    }

    @Test fun deleteAllClearsEverything() {
        val s = store()
        s.save("a", Credentials("u", "p")); s.save("b", Credentials("u", "p"))
        s.deleteAll()
        assertEquals(0, store().count())
    }
}
