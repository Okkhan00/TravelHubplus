package com.tabeer.travelhub.security

import com.tabeer.travelhub.data.FileIo
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** A saved login. [toString] is redacted so it can never leak into logs. */
data class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(redacted)"
}

/**
 * Encrypted credential vault, stored in its own file, separate from portal configuration.
 * Each entry is AES-GCM encrypted with the record id as associated data.
 * Failures return null / false and never expose secrets in exceptions or logs.
 */
class CredentialStore(private val file: File, private val cipher: AesGcmCipher) {
    private val entries: MutableMap<String, String> = load()

    @Synchronized fun has(id: String): Boolean = entries.containsKey(id)

    @Synchronized fun ids(): Set<String> = entries.keys.toSet()

    @Synchronized fun count(): Int = entries.size

    /** @return true if stored. */
    @Synchronized fun save(id: String, c: Credentials): Boolean = try {
        val plain = JSONObject().put("u", c.username).put("p", c.password).toString().toByteArray(Charsets.UTF_8)
        val blob = try { cipher.encrypt(plain, id.toByteArray(Charsets.UTF_8)) } finally { plain.fill(0) }
        entries[id] = Base64.getEncoder().encodeToString(blob)
        persist()
        true
    } catch (e: Exception) {
        false
    }

    /** @return the credentials, or null if missing, corrupted, or undecryptable (e.g. key lost). */
    @Synchronized fun get(id: String): Credentials? {
        val b64 = entries[id] ?: return null
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(b64), id.toByteArray(Charsets.UTF_8))
            try {
                val o = JSONObject(String(plain, Charsets.UTF_8))
                Credentials(o.optString("u"), o.optString("p"))
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized fun delete(id: String) {
        if (entries.remove(id) != null) persist()
    }

    @Synchronized fun deleteAll() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val obj = JSONObject()
        entries.forEach { (k, v) -> obj.put(k, v) }
        FileIo.writeAtomic(file, JSONObject().put("version", 1).put("entries", obj).toString())
    }

    private fun load(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val e = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("entries")
            val m = mutableMapOf<String, String>()
            e.keys().forEach { k -> m[k] = e.getString(k) }
            m
        } catch (ex: Exception) {
            FileIo.quarantine(file)
            mutableMapOf()
        }
    }
}
