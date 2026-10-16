package com.ondumusic.player.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenStore {
    /**
     * Distinguishes "nothing stored" from "the read failed". Collapsing the two sends a
     * user with a perfectly good stored session to the login screen with no explanation.
     */
    sealed interface LoadOutcome {
        data class Found(val token: String) : LoadOutcome
        data object Missing : LoadOutcome
        data class Failed(val error: Exception) : LoadOutcome
    }

    fun save(token: String)
    fun load(): LoadOutcome
    fun clear()
}

/**
 * The session token, encrypted with a key that never leaves the Android Keystore. The
 * ciphertext lives in private preferences; without the key it's useless, and the key
 * doesn't survive a backup or a move to another device.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(token.toByteArray())
        prefs.edit {
            putString(KEY_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    override fun load(): TokenStore.LoadOutcome {
        val token = prefs.getString(KEY_TOKEN, null) ?: return TokenStore.LoadOutcome.Missing
        val iv = prefs.getString(KEY_IV, null) ?: return TokenStore.LoadOutcome.Missing
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            TokenStore.LoadOutcome.Found(String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP))))
        } catch (e: Exception) {
            TokenStore.LoadOutcome.Failed(e)
        }
    }

    override fun clear() = prefs.edit { clear() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "session-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKEN = "token"
        const val KEY_IV = "iv"
    }
}
