package com.example.ikev2manager.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.ikev2manager.data.Credentials
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the account credentials with an AES key held by Android Keystore. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
    private val alias = "ikev2_manager_aes_gcm_v1"

    fun save(credentials: Credentials) {
        val json = JSONObject()
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json)
        val packed = cipher.iv + encrypted
        prefs.edit().putString("blob", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(): Credentials? {
        val encoded = prefs.getString("blob", null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            Credentials(json.getString("username"), json.getString("password"))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove("blob").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
