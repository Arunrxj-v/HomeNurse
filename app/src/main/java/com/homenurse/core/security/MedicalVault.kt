package com.homenurse.core.security

import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MedicalVault owns the local data-encryption key (DEK) for this device.
 *
 * * The DEK is 32 random bytes generated on first use.
 * * It is wrapped (encrypted) by the Android Keystore master key — the DEK
 *   itself is never stored in plaintext and never exported by the Keystore.
 * * Per-file keys are derived deterministically from the DEK with HMAC-SHA256,
 *   so rotating one file's key material cannot break other files.
 *
 * Destroying the vault (Settings → "Delete all medical data") discards the DEK:
 * any leftover ciphertext becomes permanently unreadable, in addition to the
 * files being deleted.
 */
class MedicalVault(
    private val keyManager: KeyManager,
    private val secureStorage: SecureStorage,
) {

    @Synchronized
    fun dek(): ByteArray {
        secureStorage.getString(KEY_DEK)?.let { encoded ->
            return try {
                keyManager.unwrap(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
            } catch (error: GeneralSecurityException) {
                regenerate()
            } catch (error: IllegalArgumentException) {
                regenerate()
            }
        }
        return regenerate()
    }

    /** Derive a purpose-bound subkey: HMAC-SHA256(dek, "purpose:id"). */
    fun deriveKey(purpose: String, id: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dek(), "HmacSHA256"))
        return mac.doFinal("$purpose:$id".toByteArray(Charsets.UTF_8))
    }

    /** Discard the DEK (caller is responsible for deleting the files/rows too). */
    @Synchronized
    fun destroy() {
        secureStorage.remove(KEY_DEK)
    }

    private fun regenerate(): ByteArray {
        val fresh = keyManager.randomBytes(32)
        val wrapped = keyManager.wrap(fresh)
        secureStorage.putString(KEY_DEK, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
        return fresh
    }

    private companion object {
        const val KEY_DEK = "vault.dek"
    }
}
