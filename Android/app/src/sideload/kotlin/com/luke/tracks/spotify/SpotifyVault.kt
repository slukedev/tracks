package com.luke.tracks.spotify

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.luke.tracks.library.SecretBox

/**
 * The saved sign-in: librespot's opaque credentials, sealed with a Keystore key, and the
 * account's name, which stays after a refused sign-in so the source can ask to sign in again.
 */
interface SpotifyVault {
    fun credentials(): ByteArray?
    fun save(credentials: ByteArray, name: String)
    /** Forgets the credentials, keeping the name. */
    fun forgetCredentials()
    /** Forgets everything. */
    fun clear()
    val name: String?
}

class SealedSpotifyVault(private val prefs: SharedPreferences, private val box: SecretBox) : SpotifyVault {
    override fun credentials(): ByteArray? {
        val sealed = prefs.getString(CREDENTIALS, null) ?: return null
        val opened = box.open(sealed)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        // Sealed on another phone, or the key went with a reinstall: as good as signed out.
        if (opened == null) forgetCredentials()
        return opened
    }

    override fun save(credentials: ByteArray, name: String) = prefs.edit {
        putString(CREDENTIALS, box.seal(Base64.encodeToString(credentials, Base64.NO_WRAP)))
        putString(NAME, name)
    }

    override fun forgetCredentials() = prefs.edit { remove(CREDENTIALS) }

    override fun clear() = prefs.edit { clear() }

    override val name: String? get() = prefs.getString(NAME, null)

    companion object {
        const val PREFS = "spotify"
        private const val CREDENTIALS = "credentials"
        private const val NAME = "name"
    }
}
