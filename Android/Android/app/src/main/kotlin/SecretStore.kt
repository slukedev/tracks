package tracks.module

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// Secrets the app keeps, such as the music server's password: AES-GCM with a key that lives in
/// the Android Keystore and never leaves it, so the preferences file holds only ciphertext.
///
/// `ServerStore.swift` calls it through Skip's `AnyDynamicObject`, which reaches statics through
/// the companion object.
class SecretStore {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tracks.secrets"
        /// Excluded from backups in `res/xml`: the key stays on this device, so a restored copy
        /// couldn't be read anyway.
        private const val PREFS = "tracks-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val lock = Any()

        private val prefs: SharedPreferences
            get() = skip.foundation.ProcessInfo.processInfo.androidContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /// The secret kept under `name`, or null if there's none or it can't be read.
        fun read(name: String): String? = synchronized(lock) {
            val stored = prefs.getString(name, null) ?: return null
            val cipher: Cipher
            val key: SecretKey
            try {
                cipher = Cipher.getInstance(TRANSFORMATION)
                key = key()
            } catch (e: Exception) {
                // The Keystore didn't answer. The secret may read fine next time, so keep it.
                logger.error("Couldn't reach the Keystore for ${name}: ${e}")
                return null
            }
            try {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            } catch (e: Exception) {
                // The key isn't the one that sealed it (the app's data came from another device),
                // so this can never be read. Forget it and let the person sign in again.
                logger.error("Couldn't read the secret ${name}: ${e}")
                prefs.edit().remove(name).commit()
                null
            }
        }

        /// Keeps `value` under `name`. False if it couldn't be encrypted or saved.
        fun write(name: String, value: String): Boolean = synchronized(lock) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
                val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
            } catch (e: Exception) {
                logger.error("Couldn't keep the secret ${name}: ${e}")
                false
            }
        }

        fun remove(name: String) = synchronized(lock) {
            prefs.edit().remove(name).commit()
            Unit
        }

        /// The app's key, made the first time it's needed.
        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
