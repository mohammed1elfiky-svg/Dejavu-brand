package com.dejavu.core.ui.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.dejavu.core.network.TokenStore
import com.dejavu.core.network.Tokens
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the session tokens encrypted with an AES-GCM key that never leaves the
 * Android Keystore. Reads are served from memory; writes go to disk.
 */
class SecureTokenStore(context: Context, name: String = "session") : TokenStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val cipher = TokenCipher("dejavu_$name")
    private val cache = AtomicReference(load())

    override fun current(): Tokens? = cache.get()

    override fun save(tokens: Tokens?) {
        cache.set(tokens)
        prefs.edit {
            if (tokens == null) remove(KEY) else putString(KEY, cipher.encrypt("${tokens.accessToken}\n${tokens.refreshToken}"))
        }
    }

    private fun load(): Tokens? {
        val stored = prefs.getString(KEY, null) ?: return null
        val parts = cipher.decrypt(stored)?.split("\n")
        // Unreadable (e.g. key reset after a backup restore): start signed out.
        return if (parts?.size == 2) Tokens(parts[0], parts[1]) else null
    }

    private companion object {
        const val KEY = "tokens"
    }
}

private class TokenCipher(private val alias: String) {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        }
        String(c.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)))
    }.getOrNull()

    private fun key(): SecretKey =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
