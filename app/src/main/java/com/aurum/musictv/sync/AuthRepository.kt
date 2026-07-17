package com.aurum.musictv.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.aurum.musictv.data.remote.SupabaseClientProvider
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * TV login, same shape as Spotify TV / mobile auth_service.dart: one tap,
 * system Google account picker, Supabase verifies the idToken against the
 * SAME server client id mobile uses — so it's the SAME Supabase user,
 * automatically. No separate TV account, no pairing code, no QR flow
 * needed since Android TV's Credential Manager can show the account
 * picker directly on-device (most TV boxes have a Google account already
 * signed in at the OS level).
 */
object AuthRepository {

    val sessionStatus: StateFlow<SessionStatus>
        get() = SupabaseClientProvider.auth.sessionStatus

    val isSignedIn: Boolean
        get() = SupabaseClientProvider.auth.currentUserOrNull() != null

    val displayName: String?
        get() = SupabaseClientProvider.auth.currentUserOrNull()
            ?.userMetadata?.get("full_name")?.toString()?.trim('"')

    val avatarUrl: String?
        get() = SupabaseClientProvider.auth.currentUserOrNull()
            ?.userMetadata?.get("avatar_url")?.toString()?.trim('"')

    /** Returns null on success, error message on failure — same contract
     *  as mobile's signInWithGoogle(). */
    suspend fun signInWithGoogle(context: Context): String? {
        return try {
            val credentialManager = CredentialManager.create(context)
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(SupabaseClientProvider.GOOGLE_WEB_CLIENT_ID)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return "Sign-in failed: unexpected credential type."
            }

            val googleIdTokenCredential = try {
                GoogleIdTokenCredential.createFrom(credential.data)
            } catch (e: GoogleIdTokenParsingException) {
                return "Sign-in failed: could not parse Google ID token."
            }

            SupabaseClientProvider.auth.signInWith(IDToken) {
                idToken = googleIdTokenCredential.idToken
                provider = Google
            }
            null
        } catch (e: Exception) {
            "Sign-in failed: ${e.message}"
        }
    }

    /** Used by the QR pairing flow: the phone obtained the Google idToken
     *  via a normal web Google Sign-In button, the Worker handed it back to
     *  the TV over polling, and this does the exact same Supabase call
     *  signInWithGoogle() does — same server client id, same resulting
     *  Supabase user, just without the TV's Credential Manager step. */
    suspend fun signInWithIdToken(idToken: String): String? {
        return try {
            SupabaseClientProvider.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
            }
            null
        } catch (e: Exception) {
            "Sign-in failed: ${e.message}"
        }
    }

    suspend fun signOut() {
        runCatching { SupabaseClientProvider.auth.signOut() }
    }
}
