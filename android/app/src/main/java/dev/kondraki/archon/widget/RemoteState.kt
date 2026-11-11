package dev.kondraki.archon.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Link
import dev.kondraki.archon.data.ModeChip
import dev.kondraki.archon.data.RemoteSnap
import dev.kondraki.archon.data.remoteFrom
import dev.kondraki.archon.data.str
import dev.kondraki.archon.stream.Phase
import dev.kondraki.archon.stream.StreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Remote widget's one source of truth (canvas Widget board, 'freshness'):
 * - LIVE while the app or the stream holds /ws: every state message updates it (only when what
 *   the widget draws changed);
 * - otherwise refreshed on each widget tap and by a 15-minute worker (Android's floor) via
 *   GET /state + GET /modes;
 * - persisted, so a fresh process draws the last-known face instead of an empty one.
 * The widget OBSERVES [state] (handoff §9.2: a session kept alive after a tap only redraws on
 * observed changes) and every change also pushes updateAll for widgets with no live session.
 */
object RemoteState {
    private val _state = MutableStateFlow(RemoteSnap())
    val state: StateFlow<RemoteSnap> = _state
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Mutex()
    @Volatile private var loaded = false
    private lateinit var app: Context

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            app = ctx.applicationContext
            runCatching {
                app.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("snap", null)
                    ?.let { _state.value = Deck.json.decodeFromString(RemoteSnap.serializer(), it).copy(live = false) }
            }
            runCatching { artFile().takeIf { it.exists() }?.let { _art.value = BitmapFactory.decodeFile(it.path) } }
            loaded = true
        }
    }

    private fun artFile() = File(app.cacheDir, "remote-art.png")

    /** The live feed: started once per process (ArchonApp). Cheap when nothing holds /ws. */
    @OptIn(FlowPreview::class)
    fun watch(ctx: Context) {
        load(ctx)
        scope.launch {
            combine(Deck.state, Deck.link, StreamEngine.status) { st, link, stream -> Triple(st, link, stream.phase != Phase.IDLE) }
                .debounce(400)
                .collect { (st, link, listening) ->
                    val cur = _state.value
                    val next = when {
                        link == Link.ONLINE && st != null -> remoteFrom(st, cur, listening, live = true, now = System.currentTimeMillis())
                        else -> cur.copy(live = false, listening = listening)     // keep the last-known face
                    }
                    set(next)
                }
        }
    }

    /** GET /state + /modes now (a widget tap, the worker, a stale widget being drawn). */
    suspend fun refresh(ctx: Context) {
        load(ctx)
        if (Deck.session.token.value == null) return
        refreshing.withLock {
            val st = withTimeoutOrNull(4000) { Deck.getJson("/state") }
            val cur = _state.value
            val listening = StreamEngine.status.value.phase != Phase.IDLE
            if (st == null) { set(cur.copy(online = false, live = false, listening = listening)); return }
            val modes = withTimeoutOrNull(3000) { Deck.getElement("/modes") as? JsonArray }
                ?.mapNotNull { m -> (m as? JsonObject)?.let { o -> o.str("id")?.let { ModeChip(it, o.str("label") ?: it) } } }
                ?.take(2) ?: cur.modes
            set(remoteFrom(st, cur, listening, live = Deck.link.value == Link.ONLINE, now = System.currentTimeMillis(), modes = modes))
        }
    }

    /** An optimistic change after a tap (the refresh that follows confirms or undoes it). */
    suspend fun assume(ctx: Context, f: (RemoteSnap) -> RemoteSnap) { load(ctx); set(f(_state.value)) }

    private suspend fun set(next: RemoteSnap) {
        val prev = _state.value
        _state.value = next
        if (next.artKey != prev.artKey) loadArt(next.artKey)
        if (next.sameFace(prev) && next.at - prev.at < 60_000L) return    // nothing the widget draws changed
        runCatching {
            app.getSharedPreferences("remote", Context.MODE_PRIVATE).edit()
                .putString("snap", Deck.json.encodeToString(RemoteSnap.serializer(), next)).apply()
        }
        push()
    }

    private suspend fun loadArt(key: String) {
        val bmp = if (key.isEmpty()) null else withTimeoutOrNull(4000) { Deck.art(key) }
            ?.let { Bitmap.createScaledBitmap(it, 120, 120, true) }      // 40 dp at xxhdpi; keeps the RemoteViews small
        _art.value = bmp
        runCatching { if (bmp == null) artFile().delete() else artFile().outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        push()
    }

    private suspend fun push() {
        runCatching {
            if (GlanceAppWidgetManager(app).getGlanceIds(RemoteWidget::class.java).isNotEmpty()) RemoteWidget().updateAll(app)
        }
    }
}

/** Every 15 minutes (Android's floor), with a network: re-read the rig, redraw the widget. */
class RemoteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { RemoteState.refresh(applicationContext) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RemoteWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("remote-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("remote-refresh")
    }
}
