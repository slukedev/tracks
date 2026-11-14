package com.luke.tracks.lastfm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import uniffi.tracks_engine.LastFmSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Last.fm session key, sealed with a key that lives in the Android Keystore (AES-GCM),
 * so the file it's saved in is useless off this phone. The key is never logged.
 */
internal class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)

    fun load(): LastFmSession? = runCatching {
        val user = prefs.getString(USER, null) ?: return null
        val sealed = Base64.decode(prefs.getString(SEALED, null) ?: return null, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        LastFmSession(user, String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8))
    }.getOrNull()

    /** The account's name, which isn't secret, without unsealing anything. */
    fun username(): String? = prefs.getString(USER, null)

    fun save(session: LastFmSession): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(session.sessionKey.toByteArray(Charsets.UTF_8))
        val sealed = cipher.iv + body
        prefs.edit(commit = true) {
            putString(USER, session.username)
            putString(SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
    }.isSuccess

    fun clear() {
        prefs.edit(commit = true) { remove(USER); remove(SEALED) }
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "tracks.lastfm.session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val USER = "username"
        const val SEALED = "session"
    }
}
