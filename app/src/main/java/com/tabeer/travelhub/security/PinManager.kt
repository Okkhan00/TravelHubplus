package com.tabeer.travelhub.security

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, def: Long): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun remove(vararg keys: String)
}

class PrefsKv(private val p: SharedPreferences) : KeyValueStore {
    override fun getString(key: String) = p.getString(key, null)
    override fun putString(key: String, value: String) { p.edit().putString(key, value).apply() }
    override fun getLong(key: String, def: Long) = p.getLong(key, def)
    override fun putLong(key: String, value: Long) { p.edit().putLong(key, value).apply() }
    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun putInt(key: String, value: Int) { p.edit().putInt(key, value).apply() }
    override fun remove(vararg keys: String) { p.edit().apply { keys.forEach { remove(it) } }.apply() }
}

object PinHasher {
    const val DEFAULT_ITERATIONS = 150_000
    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * App-lock PIN: PBKDF2-HMAC-SHA256 with a unique random salt, constant-time comparison,
 * and escalating lockouts after repeated wrong attempts. The PIN itself is never stored.
 */
class PinManager(
    private val kv: KeyValueStore,
    private val iterations: Int = PinHasher.DEFAULT_ITERATIONS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    sealed class VerifyResult {
        data object Success : VerifyResult()
        data class Wrong(val attemptsLeft: Int) : VerifyResult()
        data class LockedOut(val untilMs: Long) : VerifyResult()
    }

    fun isSet(): Boolean = kv.getString(K_HASH) != null

    fun isValidFormat(pin: String): Boolean = PIN_FORMAT.matches(pin)

    /** @return false if [pin] doesn't meet the format (4–12 digits). */
    fun setPin(pin: String): Boolean {
        if (!isValidFormat(pin)) return false
        val salt = PinHasher.newSalt()
        val h = PinHasher.hash(pin, salt, iterations)
        kv.putString(K_SALT, Base64.getEncoder().encodeToString(salt))
        kv.putString(K_HASH, Base64.getEncoder().encodeToString(h))
        kv.putInt(K_ITER, iterations)
        kv.remove(K_FAILS, K_LOCK_UNTIL)
        return true
    }

    fun verify(pin: String): VerifyResult {
        val now = clock()
        val until = kv.getLong(K_LOCK_UNTIL, 0L)
        if (now < until) return VerifyResult.LockedOut(until)
        val salt = kv.getString(K_SALT)?.let { Base64.getDecoder().decode(it) }
        val expected = kv.getString(K_HASH)?.let { Base64.getDecoder().decode(it) }
        if (salt == null || expected == null) return VerifyResult.Wrong(0)
        val actual = PinHasher.hash(pin, salt, kv.getInt(K_ITER, iterations))
        if (MessageDigest.isEqual(actual, expected)) {
            kv.remove(K_FAILS, K_LOCK_UNTIL)
            return VerifyResult.Success
        }
        val fails = kv.getInt(K_FAILS, 0) + 1
        kv.putInt(K_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            val delay = minOf(BASE_LOCK_MS shl minOf(fails - FREE_ATTEMPTS, 7), MAX_LOCK_MS)
            kv.putLong(K_LOCK_UNTIL, now + delay)
            return VerifyResult.LockedOut(now + delay)
        }
        return VerifyResult.Wrong(FREE_ATTEMPTS - fails)
    }

    fun clear() = kv.remove(K_SALT, K_HASH, K_ITER, K_FAILS, K_LOCK_UNTIL)

    companion object {
        private val PIN_FORMAT = Regex("^\\d{4,12}$")
        private const val K_SALT = "pin_salt"
        private const val K_HASH = "pin_hash"
        private const val K_ITER = "pin_iter"
        private const val K_FAILS = "pin_fails"
        private const val K_LOCK_UNTIL = "pin_lock_until"
        const val FREE_ATTEMPTS = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 3_600_000L
    }
}
