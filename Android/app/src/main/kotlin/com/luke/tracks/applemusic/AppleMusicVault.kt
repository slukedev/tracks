package com.luke.tracks.applemusic

import android.content.SharedPreferences
import androidx.core.content.edit
import com.luke.tracks.library.SecretBox

/**
 * The Music User Token from Apple's sign-in, sealed with a Keystore key, and the storefront it
 * belongs to, which stays after the token is refused so the source can ask to sign in again.
 */
interface AppleMusicVault {
    fun userToken(): String?
    fun save(userToken: String)
    var storefront: String?
    /** Forgets the token, keeping the storefront. */
    fun forgetToken()
    /** Forgets everything. */
    fun clear()
}

class SealedAppleMusicVault(private val prefs: SharedPreferences, private val box: SecretBox) : AppleMusicVault {
    @Volatile private var opened: String? = null

    override fun userToken(): String? {
        opened?.let { return it }
        val sealed = prefs.getString(TOKEN, null) ?: return null
        val token = box.open(sealed)
        // Sealed on another phone, or the key went with a reinstall: as good as signed out.
        if (token == null) forgetToken()
        opened = token
        return token
    }

    override fun save(userToken: String) {
        opened = userToken
        prefs.edit { putString(TOKEN, box.seal(userToken)) }
    }

    override var storefront: String?
        get() = prefs.getString(STOREFRONT, null)
        set(value) = prefs.edit { if (value == null) remove(STOREFRONT) else putString(STOREFRONT, value) }

    override fun forgetToken() {
        opened = null
        prefs.edit { remove(TOKEN) }
    }

    override fun clear() {
        opened = null
        prefs.edit { clear() }
    }

    companion object {
        const val PREFS = "appleMusic"
        private const val TOKEN = "userToken"
        private const val STOREFRONT = "storefront"
    }
}
