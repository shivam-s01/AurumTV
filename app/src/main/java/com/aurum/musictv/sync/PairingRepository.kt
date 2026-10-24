package com.aurum.musictv.sync

import com.aurum.musictv.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * TV sign-in without typing anything on the TV (Spotify / YouTube TV style).
 *
 *  1. TV asks Supabase for a short one-time code (`tv_pair_create`) and shows
 *     it as a QR + text.
 *  2. The phone app (already signed in with Google) scans the QR / types the
 *     code and approves it (`tv_pair_approve`), handing over a fresh Google
 *     ID token — nothing else, no password, no session tokens.
 *  3. The TV polls (`tv_pair_poll`), receives the token once (the server
 *     deletes it immediately) and signs in as the SAME Supabase user, with
 *     its OWN independent session (so phone and TV never log each other out).
 *
 * All three are Postgres functions (see supabase_tv_pairing.sql): no extra
 * server, no Worker route needed for the pairing itself.
 */
object PairingRepository {

    /** Page that opens the Astra phone app for a scanned code (optional
     *  Worker route, see tv-landing-worker.js). The in-app scanner and the
     *  typed code work without it. */
    private const val LINK_BASE = "https://astra.mmusic.workers.dev/tv/"
    const val CODE_TTL_SECONDS = 300

    data class Session(val code: String, val qrText: String)

    sealed interface Poll {
        data object Pending : Poll
        data object Expired : Poll
        data class Approved(val idToken: String) : Poll
        data object Error : Poll
    }

    /** New one-time code, or null when the server can't be reached. */
    suspend fun create(): Session? = try {
        val raw = SupabaseClientProvider.postgrest.rpc("tv_pair_create").data
        val code = raw.trim().trim('"').uppercase()
        if (code.length < 6) null else Session(code = code, qrText = LINK_BASE + code)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun poll(code: String): Poll = try {
        val raw = SupabaseClientProvider.postgrest.rpc(
            "tv_pair_poll",
            buildJsonObject { put("p_code", code) },
        ).data
        val json = JSONObject(raw)
        when (json.optString("status")) {
            "approved" -> {
                val token = json.optString("id_token")
                if (token.isNotBlank()) Poll.Approved(token) else Poll.Error
            }
            "expired" -> Poll.Expired
            else -> Poll.Pending
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Poll.Error
    }
}
