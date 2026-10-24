package com.aurum.musictv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.data.remote.NetworkResilience
import com.aurum.musictv.data.remote.await
import com.aurum.musictv.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One published TV release, read from the repo's latest GitHub Release. */
data class UpdateInfo(
    val build: Long,
    val label: String,
    val notes: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, PERMISSION, DOWNLOADING, INSTALLING, FAILED }

data class UpdateUi(
    val phase: Phase = Phase.IDLE,
    val info: UpdateInfo? = null,
    val percent: Int = 0,
    val message: String? = null,
    val showDialog: Boolean = false,
)

/**
 * In-app updater, built to cost nothing on a 1 GB / 8 GB TV box:
 *
 *  - No new library, no background service, no WorkManager, no polling.
 *    One tiny GitHub API call ~5 s after launch, at most once per 6 hours.
 *  - The APK is streamed straight into a PackageInstaller session: it is
 *    never written to the app's own storage, so there is no 15-30 MB temp
 *    file to clean up and nothing extra held in RAM (32 KB buffer).
 *
 * Security:
 *  - HTTPS only, and every redirect hop must stay on github.com /
 *    *.githubusercontent.com (see [http]).
 *  - The download URL must belong to [REPO]'s own release downloads.
 *  - SHA-256 of the stream is checked against GitHub's asset digest when
 *    GitHub provides one, and the exact byte size must match.
 *  - Android itself only installs the APK if it is signed with the SAME
 *    key as the installed app and has the same package name, so a
 *    tampered or foreign APK can never replace Astra TV.
 *
 * Publishing an update = push a commit whose message contains "[release]"
 * (the CI workflow then creates the GitHub Release tv-build<N>). The commit
 * message becomes the "What's new" text in the popup.
 */
object UpdateManager {

    // Repo whose Releases are checked. Must be PUBLIC for TVs to read it.
    private const val REPO = "shivam-s01/AurumTV"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val ACTION_STATUS = "com.aurum.musictv.UPDATE_STATUS"

    private val TAG_RE = Regex("^tv-build(\\d+)$")
    private val NAME_RE = Regex("^AstraTV-(.+)-b\\d+\\.apk$", RegexOption.IGNORE_CASE)

    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var downloadJob: Job? = null

    private val http: OkHttpClient by lazy {
        AurumApi.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS) // big download: no overall cap
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(Interceptor { chain ->
                val url = chain.request().url
                val host = url.host
                val ok = url.isHttps && (host == "github.com" || host == "api.github.com" ||
                    host.endsWith(".githubusercontent.com"))
                if (!ok) throw IOException("Blocked non-GitHub host")
                chain.proceed(chain.request())
            })
            .build()
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Build number (versionCode) of the installed app. */
    fun installedBuild(context: Context): Long = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrDefault(0L)

    /** Silent background check (throttled to once per 6 h). */
    fun checkOnLaunch(context: Context) = check(context, manual = false)

    /** Settings row tap: re-open the popup if an update is pending, else check now. */
    fun checkOrReopen(context: Context) {
        when (_ui.value.phase) {
            Phase.AVAILABLE, Phase.PERMISSION, Phase.DOWNLOADING, Phase.INSTALLING ->
                _ui.update { it.copy(showDialog = true) }
            Phase.FAILED -> if (_ui.value.info != null) _ui.update { it.copy(showDialog = true) }
                else check(context, manual = true)
            else -> check(context, manual = true)
        }
    }

    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        val phase = _ui.value.phase
        if (phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING) return
        scope.launch {
            if (!manual) {
                val last = SettingsStore.lastUpdateCheck(app)
                if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return@launch
            }
            if (!NetworkResilience.isOnline(app)) {
                if (manual) _ui.value = UpdateUi(Phase.FAILED, message = "No internet connection")
                return@launch
            }
            _ui.value = UpdateUi(Phase.CHECKING)
            try {
                val found = fetchLatest(installedBuild(app))
                SettingsStore.setLastUpdateCheck(app, System.currentTimeMillis())
                _ui.value = when {
                    found != null -> UpdateUi(Phase.AVAILABLE, info = found, showDialog = true)
                    manual -> UpdateUi(Phase.UP_TO_DATE)
                    else -> UpdateUi(Phase.IDLE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = if (manual) UpdateUi(Phase.FAILED, message = "Couldn't check for updates") else UpdateUi(Phase.IDLE)
            }
        }
    }

    /** "Update now": download straight into the installer, then hand over to Android. */
    fun install(context: Context) {
        val info = _ui.value.info ?: return
        val app = context.applicationContext
        val phase = _ui.value.phase
        if (phase == Phase.DOWNLOADING || phase == Phase.INSTALLING || phase == Phase.CHECKING) return

        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _ui.update { it.copy(phase = Phase.PERMISSION, message = null, showDialog = true) }
            return
        }
        downloadJob = scope.launch { downloadAndCommit(app, info) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Hide the popup (update stays available from Settings). A running download continues. */
    fun dismiss() {
        _ui.update { it.copy(showDialog = false) }
    }

    /** Opens Android's "Install unknown apps" page for this app (falls back to general settings). */
    fun openInstallSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** Activity.onResume: if the user just granted install permission, continue automatically. */
    fun onResume(context: Context) {
        if (_ui.value.phase == Phase.PERMISSION &&
            (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())
        ) {
            install(context)
        }
    }

    /** Short text for the Settings row. */
    fun statusLabel(u: UpdateUi): String = when (u.phase) {
        Phase.CHECKING -> "Checking…"
        Phase.UP_TO_DATE -> "You're up to date"
        Phase.AVAILABLE, Phase.PERMISSION -> "${u.info?.label ?: "Update"} available"
        Phase.DOWNLOADING -> "Downloading…"
        Phase.INSTALLING -> "Ready to install"
        Phase.FAILED -> if (u.info != null) "Update failed · tap to retry" else (u.message ?: "Couldn't check")
        Phase.IDLE -> "Tap to check"
    }

    // ── Install result callbacks (from UpdateInstallReceiver) ───────────

    internal fun onAwaitingUser() {
        _ui.update { it.copy(phase = Phase.INSTALLING, showDialog = true) }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // process is replaced by Android
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _ui.update { it.copy(phase = Phase.AVAILABLE, message = "Install was cancelled", showDialog = true) }
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _ui.update {
                    it.copy(
                        phase = Phase.FAILED, showDialog = true,
                        message = "This update is signed differently. Uninstall Astra TV once and install the new APK.",
                    )
                }
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                _ui.update {
                    it.copy(
                        phase = Phase.FAILED, showDialog = true,
                        message = "Not enough storage. Free some space (Settings > Clear Cache) and try again.",
                    )
                }
            else ->
                _ui.update {
                    it.copy(phase = Phase.FAILED, showDialog = true, message = message?.takeIf { m -> m.isNotBlank() } ?: "Install failed")
                }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun fetchLatest(installed: Long): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = withTimeoutOrNull(15_000) {
            http.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("Empty response")
            }
        } ?: throw IOException("Timed out")
        parse(body, installed)
    }

    private fun parse(json: String, installed: Long): UpdateInfo? {
        val o = JSONObject(json)
        val build = TAG_RE.matchEntire(o.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (build <= installed) return null

        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = a
                break
            }
        }
        val a = apk ?: return null
        val url = a.optString("browser_download_url")
        if (!url.startsWith("https://github.com/$REPO/releases/download/")) return null
        val size = a.optLong("size", 0L)
        if (size <= 0L) return null

        val sha = a.optString("digest").removePrefix("sha256:").lowercase()
            .takeIf { s -> s.length == 64 && s.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val label = NAME_RE.matchEntire(a.optString("name"))?.groupValues?.get(1)?.let { "v$it" } ?: "build $build"
        val notes = (if (o.isNull("body")) "" else o.optString("body"))
            .replace("[release]", "", ignoreCase = true)
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .take(5).joinToString("\n")
            .take(260)
        return UpdateInfo(build, label, notes, url, size, sha)
    }

    private suspend fun downloadAndCommit(app: Context, info: UpdateInfo) {
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            _ui.update { it.copy(phase = Phase.DOWNLOADING, percent = 0, message = null, showDialog = true) }
            runCatching { installer.mySessions.forEach { s -> installer.abandonSession(s.sessionId) } }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(info.size)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            val digest = MessageDigest.getInstance("SHA-256")

            installer.openSession(sessionId).use { session ->
                http.newCall(Request.Builder().url(info.url).build()).await().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
                    val body = resp.body ?: throw IOException("Empty download")
                    val len = body.contentLength()
                    if (len > 0 && len != info.size) throw IOException("Unexpected file size")

                    withContext(Dispatchers.IO) {
                        session.openWrite("update.apk", 0, info.size).use { out ->
                            val buf = ByteArray(32 * 1024)
                            val src = body.byteStream()
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                ensureActive()
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (done > info.size) throw IOException("Download larger than expected")
                                val pct = (done * 100 / info.size).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _ui.update { it.copy(percent = pct) }
                                }
                            }
                            if (done != info.size) throw IOException("Incomplete download")
                            session.fsync(out)
                        }
                    }
                }

                val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
                if (info.sha256 != null && !hex.equals(info.sha256, ignoreCase = true)) {
                    throw SecurityException("Checksum mismatch")
                }
                _ui.update { it.copy(phase = Phase.INSTALLING, percent = 100) }
                session.commit(statusSender(app, sessionId))
            }
        } catch (e: CancellationException) {
            abandon(installer, sessionId)
            _ui.update { it.copy(phase = Phase.AVAILABLE, percent = 0, message = null) }
            throw e
        } catch (e: Exception) {
            abandon(installer, sessionId)
            val msg = when (e) {
                is SecurityException -> "Downloaded file failed the safety check. Try again."
                is IOException -> "Download failed. Check your internet and try again."
                else -> "Update failed. Try again."
            }
            _ui.update { it.copy(phase = Phase.FAILED, message = msg, showDialog = true) }
        }
    }

    private fun abandon(installer: PackageInstaller, id: Int) {
        if (id >= 0) runCatching { installer.abandonSession(id) }
    }

    private fun statusSender(app: Context, sessionId: Int): IntentSender {
        val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // The installer must be able to add its result extras -> mutable (explicit intent, so safe).
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender
    }
}
