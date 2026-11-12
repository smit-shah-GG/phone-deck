package dev.kondraki.archon.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.kondraki.archon.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where the in-app updater stands. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val version: String, val code: Int, val note: String = "") : UpdateState
    data class Downloading(val version: String, val pct: Int) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val version: String, val code: Int, val why: String) : UpdateState
}

/**
 * The in-app updater (#157): installing a new Archon without the browser. It asks the rig for
 * the published build's versionCode (GET /app.apk.json), and on Update downloads GET /app.apk
 * with Archon's own session straight into a PackageInstaller session. Android only accepts an
 * APK signed with the same key, so nothing else can slip in. The first update shows Android's
 * confirmation; once Archon is its own installer of record, later ones can go through without
 * it (setRequireUserAction). Release builds only: archon·dev is a separate app.
 */
object Updater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state
    fun set(s: UpdateState) { _state.value = s }

    suspend fun check() {
        if (BuildConfig.DEBUG) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        val m = Deck.getJson("/app.apk.json") ?: return
        val code = m.num("code")?.toInt() ?: 0
        val ver = m.str("version").orEmpty()
        _state.value = if (code > BuildConfig.VERSION_CODE) UpdateState.Available(ver, code) else UpdateState.None
    }

    /** Tap on Update. Without "install unknown apps" for Archon, opens that one setting first. */
    suspend fun install(ctx: Context) {
        val s = _state.value
        val (ver, code) = when (s) {
            is UpdateState.Available -> s.version to s.code
            is UpdateState.Failed -> s.version to s.code
            else -> return
        }
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Available(ver, code, "allow Archon to install apps, then tap Update again")
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        _state.value = UpdateState.Downloading(ver, 0)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    val http = Deck.slow.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
                    http.newCall(Request.Builder().url("${Deck.url}/app.apk").build()).execute().use { r ->
                        check(r.isSuccessful && r.header("Content-Type").orEmpty().contains("android.package-archive")) { "HTTP ${r.code}" }
                        val total = r.body.contentLength()
                        r.body.byteStream().use { input ->
                            session.openWrite("archon.apk", 0, total).use { out ->
                                val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(ver, pct) } }
                                }
                                session.fsync(out)
                            }
                        }
                    }
                    _state.value = UpdateState.Installing(ver)
                    val done = PendingIntent.getBroadcast(app, 0, Intent(app, UpdateReceiver::class.java).putExtra("version", ver).putExtra("code", code),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    session.commit(done.intentSender)
                }
                true
            }.getOrElse { e -> _state.value = UpdateState.Failed(ver, code, e.message ?: "download failed"); false }
        }
        if (!ok) return
    }
}

/** PackageInstaller's verdict: bring up Android's confirmation when it asks; report failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ver = intent.getStringExtra("version").orEmpty()
        val code = intent.getIntExtra("code", 0)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            PackageInstaller.STATUS_SUCCESS -> Updater.set(UpdateState.None)   // Android restarts Archon on the new build
            else -> Updater.set(UpdateState.Failed(ver, code,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install cancelled"))
        }
    }
}
