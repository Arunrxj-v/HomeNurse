package com.homenurse.core.security

import android.content.Context
import android.util.Base64
import android.content.SharedPreferences

/**
 * Secure name-value storage for small non-document secrets: the encrypted
 * session blob (auth tokens), notification preferences, local profile name.
 *
 * Values are encrypted with the Keystore-held master key ([KeyManager.wrap]);
 * plaintext values are never written to SharedPreferences. Medical documents
 * and structured medical records do NOT live here — they live in the medical
 * vault (encrypted files + encrypted Room database).
 */
interface SecureStorage {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
    fun contains(key: String): Boolean
    fun clear()
}

class EncryptedSecureStorage(
    context: Context,
    private val keyManager: KeyManager,
    prefsName: String = "homenurse_secure",
) : SecureStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun putString(key: String, value: String) {
        val blob = keyManager.wrap(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun getString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            String(keyManager.unwrap(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (error: SecurityException) {
            null
        } catch (error: javax.crypto.AEADBadTagException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/** Test double: unencrypted in-memory storage. */
class InMemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, String>()

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key]

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun clear() = values.clear()
}
