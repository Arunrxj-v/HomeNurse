package com.homenurse.core.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/**
 * Sign in with Google through the current Android-recommended API:
 * Credential Manager + [GetGoogleIdOption] (the legacy Google Sign-In
 * library is deliberately NOT used).
 *
 * Flow: Credential Manager → Google account → Google ID token → sent to the
 * HomeNurse backend, which is the only party that validates it. The user's
 * Google password never reaches HomeNurse.
 *
 * Nonce handling: a fresh random nonce is generated per attempt and
 * SHA-256-hashed before being handed to Google (per Credential Manager
 * guidance). The raw nonce travels with the ID token to the backend, which
 * verifies `sha256(rawNonce) == payload.nonce` — binding the token to this
 * sign-in attempt and preventing replay.
 */
class GoogleIdentityProvider(context: Context) {

    private val credentialManager = CredentialManager.create(context.applicationContext)

    sealed interface Result {
        data class Success(val idToken: String, val nonce: String) : Result
        /** User dismissed the account picker. */
        data object Cancelled : Result
        /** Not configured (missing web client id) or no Google account/Play services. */
        data class Unavailable(val reason: String) : Result
        data object Failed : Result
    }

    /**
     * Opens the Google account picker and returns an ID token + the raw nonce
     * to send to the backend.
     */
    suspend fun signIn(serverClientId: String): Result {
        if (serverClientId.isBlank()) {
            return Result.Unavailable("google_web_client_id_missing")
        }
        val rawNonce = newNonce()
        val hashedNonce = sha256Hex(rawNonce)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setServerClientId(serverClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = try {
            credentialManager.getCredential(context = appContext, request = request)
        } catch (error: GetCredentialCancellationException) {
            return Result.Cancelled
        } catch (error: GetCredentialException) {
            return Result.Unavailable(error.message ?: "credential_unavailable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.Failed
        }
        val credential = response.credential
        val isValidType =
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        if (!isValidType) return Result.Failed
        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.Failed
        }
        if (idToken.isBlank()) return Result.Failed
        return Result.Success(idToken = idToken, nonce = rawNonce)
    }

    /**
     * Clears the Credential Manager state (cached account selection) on sign
     * out, so the next user is not silently offered the previous account.
     * Best-effort: local sign-out never depends on it.
     */
    suspend fun clearCredentialState() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Ignore — non-critical.
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        // Base64URL, unpadded — the exact encoding Google round-trips in the
        // ID token's `nonce` claim.
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val appContext: Context = context.applicationContext
}
