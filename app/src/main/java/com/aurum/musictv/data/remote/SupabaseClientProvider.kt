package com.aurum.musictv.data.remote

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest

/**
 * Same Supabase project the mobile app talks to (see mobile
 * lib/services/auth_service.dart). Sign in on TV with the same Google
 * account and this is the SAME user row, SAME playlists, SAME favorites —
 * no separate TV account, no backend changes needed.
 *
 * Kept as a single lazily-initialized object (no DI framework) to stay
 * lightweight — this is the one and only network/auth entrypoint the
 * whole TV app uses for anything sync-related.
 */
object SupabaseClientProvider {

    // ── Same project credentials as mobile (lib/services/auth_service.dart) ──
    private const val SUPABASE_URL = "https://uurejujwjaxzwnjpsrrz.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_isqVWcsXnxihYSO4rwBqCQ_ieeVE4lw"

    // Web Client ID from Google Cloud Console — same one mobile uses as
    // serverClientId, required so Supabase can verify the TV's Google
    // idToken against the same OAuth client.
    const val GOOGLE_WEB_CLIENT_ID =
        "770149348902-m0d6id81ojka4tohae7udkj0b9eqqobm.apps.googleusercontent.com"

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            // No Realtime module: TV uses lightweight polling (see
            // SyncRepository) instead of an always-open websocket, which
            // matters on 1GB RAM boxes — one less persistent connection
            // and background dispatcher alive for the app's whole life.
        }
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest
}
