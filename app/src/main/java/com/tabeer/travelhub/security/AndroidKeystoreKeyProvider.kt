package com.tabeer.travelhub.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256 key generated inside the Android Keystore (hardware-backed where the device supports it).
 * The key material is non-exportable: this app (or an attacker copying its files) never sees the raw key.
 * The key is NOT bound to user authentication so the app can fill logins without a prompt on every use;
 * access to the UI is instead gated by the app lock (PIN/biometric).
 */
class AndroidKeystoreKeyProvider(private val alias: String = "travelhub_vault_key_v1") : KeyProvider {
    @Synchronized
    override fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
