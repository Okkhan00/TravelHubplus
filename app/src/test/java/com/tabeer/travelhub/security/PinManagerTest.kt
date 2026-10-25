package com.tabeer.travelhub.security

import org.junit.Assert.*
import org.junit.Test

class PinManagerTest {
    private class MapKv : KeyValueStore {
        val m = HashMap<String, Any>()
        override fun getString(key: String) = m[key] as String?
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getLong(key: String, def: Long) = (m[key] as Long?) ?: def
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun getInt(key: String, def: Int) = (m[key] as Int?) ?: def
        override fun putInt(key: String, value: Int) { m[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { m.remove(it) } }
    }

    private var now = 1_000_000L
    private val kv = MapKv()
    private val pin = PinManager(kv, iterations = 1000, clock = { now })

    @Test fun noPinByDefault() = assertFalse(pin.isSet())

    @Test fun correctPinVerifiesWrongDoesNot() {
        assertTrue(pin.setPin("2468"))
        assertTrue(pin.isSet())
        assertEquals(PinManager.VerifyResult.Success, pin.verify("2468"))
        assertTrue(pin.verify("1111") is PinManager.VerifyResult.Wrong)
    }

    @Test fun pinFormatEnforced() {
        assertFalse(pin.setPin("123"))
        assertFalse(pin.setPin("12ab"))
        assertFalse(pin.setPin("1".repeat(13)))
        assertFalse(pin.isSet())
    }

    @Test fun pinNotStoredInPlainTextAndSaltIsUnique() {
        pin.setPin("2468")
        assertFalse(kv.m.values.any { it.toString().contains("2468") })
        val salt1 = kv.getString("pin_salt")
        pin.setPin("2468")
        assertNotEquals(salt1, kv.getString("pin_salt"))
    }

    @Test fun lockoutAfterRepeatedFailuresThenRecovers() {
        pin.setPin("2468")
        repeat(4) { assertTrue(pin.verify("0000") is PinManager.VerifyResult.Wrong) }
        assertTrue(pin.verify("0000") is PinManager.VerifyResult.LockedOut)
        // even the correct PIN is refused during the lockout
        assertTrue(pin.verify("2468") is PinManager.VerifyResult.LockedOut)
        now += 31_000
        assertEquals(PinManager.VerifyResult.Success, pin.verify("2468"))
        // failure counter was reset
        assertTrue(pin.verify("0000") is PinManager.VerifyResult.Wrong)
    }

    @Test fun clearRemovesPin() {
        pin.setPin("2468")
        pin.clear()
        assertFalse(pin.isSet())
    }
}
