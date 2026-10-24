package com.aurum.musictv.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.aurum.musictv.data.remote.NetworkResilience
import com.aurum.musictv.data.remote.SupabaseClientProvider
import com.aurum.musictv.settings.DeviceInfo
import com.aurum.musictv.settings.SettingsStore
import com.aurum.musictv.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One tiny RPC (~300 bytes) to Supabase: "this TV exists, runs this app
 * version, and this account is connected". Sent at most once per 12 hours,
 * or right away when the app version or the signed-in account changes.
 * No hardware ids, no location, no IP stored; the id is a random UUID made
 * at first launch. Failures are swallowed: this can never affect playback.
 *
 * Server side: supabase_migration_phase2_devices.sql (table is write-only
 * for the app; only you can read it from the Supabase dashboard).
 */
object DeviceTelemetry {

    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L

    suspend fun ping(context: Context) {
        val app = context.applicationContext
        try {
            val uid = SupabaseClientProvider.auth.currentUserOrNull()?.id
            val build = UpdateManager.installedBuild(app)
            val key = "$build|${uid ?: "-"}"
            val (lastKey, lastAt) = SettingsStore.lastPing(app)
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastAt < MIN_INTERVAL_MS) return
            if (!NetworkResilience.isOnline(app)) return

            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val dm = app.resources.displayMetrics

            val deviceId = SettingsStore.deviceId(app)
            val body = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_manufacturer", Build.MANUFACTURER.orEmpty())
                put("p_model", Build.MODEL.orEmpty())
                put("p_android", Build.VERSION.RELEASE.orEmpty())
                put("p_sdk", Build.VERSION.SDK_INT)
                put("p_app_version", DeviceInfo.appVersion(app))
                put("p_build", build)
                put("p_ram_mb", (mem.totalMem / (1024L * 1024L)).toInt())
                put("p_low_ram", am.isLowRamDevice)
                put("p_screen", "${dm.widthPixels}x${dm.heightPixels}")
            }
            SupabaseClientProvider.postgrest.rpc("register_tv_device", body)
            SettingsStore.setLastPing(app, key, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // best-effort only
        }
    }
}
