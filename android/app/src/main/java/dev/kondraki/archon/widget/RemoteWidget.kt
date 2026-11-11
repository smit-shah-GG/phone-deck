package dev.kondraki.archon.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.kondraki.archon.MainActivity
import dev.kondraki.archon.R
import dev.kondraki.archon.ShortcutActivity
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Fresh
import dev.kondraki.archon.data.RemoteSnap
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.remoteFresh
import dev.kondraki.archon.data.remoteStamp
import dev.kondraki.archon.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The phosphor palette as shipped (the 9-patches carry the same values; see tools/ninepatch.py). */
private object P {
    val on = ColorProvider(Color(0xFFEDC3C3))
    val onVar = ColorProvider(Color(0xFFB77A7A))
    val primary = ColorProvider(Color(0xFFFA4242))
    val amber = ColorProvider(Color(0xFFFFB000))
    val onPrimary = ColorProvider(Color(0xFF02160B))
    val dead = ColorProvider(Color(0xFF5A3A3A))
    val rule = ColorProvider(Color(0xFF1F0C0C))
    val artBg = ColorProvider(Color(0xFF1C2830))
}

/** Glance can't load app fonts (handoff §9.3): the system monospace, like the canvas board. */
private fun mono(size: TextUnit, color: ColorProvider, bold: Boolean = false) =
    TextStyle(color = color, fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

/**
 * Remote, 4×2 (canvas Widget board; #121 decision 6): the M52's resting face at home. Row 1 =
 * emblem, LIGHTNING, how fresh this is (tap = open Archon, ↻ = refresh); row 2 = now playing +
 * prev / play-pause / next on the rig; row 3 = LISTEN and SCREENS (lit when on), then the first
 * two modes. Offline: controls dim and stop responding; row 1 retries.
 */
class RemoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single                 // handoff §9.6: one layout, no per-size map

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        RemoteState.load(context)
        val s = RemoteState.state.value
        if (!s.live && System.currentTimeMillis() - s.at > 60_000L) RemoteState.scope.launch { RemoteState.refresh(context) }
        provideContent {
            val snap by RemoteState.state.collectAsState()       // observe, don't read (handoff §9.2)
            val art by RemoteState.art.collectAsState()
            Remote(context, snap, art)
        }
    }
}

class RemoteReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemoteWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); RemoteWorker.schedule(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); RemoteWorker.cancel(context) }
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RemoteWorker.schedule(context)                          // KEEP: survives reboots and reinstalls
    }
}

@Composable
private fun Remote(context: Context, s: RemoteSnap, art: android.graphics.Bitmap?) {
    val fresh = remoteFresh(s, System.currentTimeMillis())
    val off = fresh == Fresh.OFFLINE
    val stale = fresh == Fresh.STALE
    val text = if (stale) P.onVar else P.on
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.w_panel)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        // row 1: who and how fresh; tap = open Archon (offline: retry)
        Row(GlanceModifier.fillMaxWidth().height(22.dp)
            .clickable(if (off) actionRunCallback<RefreshAction>() else actionStartActivity(openApp(context))),
            verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.emblem), "Archon", colorFilter = ColorFilter.tint(if (off) P.amber else P.primary),
                modifier = GlanceModifier.size(15.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(Deck.host, style = mono(11.sp, if (off) P.amber else P.primary, bold = true), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            val at = if (s.at > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.at)) else "--:--"
            Text(remoteStamp(fresh, at), style = mono(10.sp, if (stale || off) P.amber else P.onVar), maxLines = 1)
            Box(GlanceModifier.size(28.dp, 22.dp).clickable(actionRunCallback<RefreshAction>()), contentAlignment = Alignment.CenterEnd) {
                Image(ImageProvider(R.drawable.w_refresh), "Refresh", colorFilter = ColorFilter.tint(P.onVar), modifier = GlanceModifier.size(14.dp))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(P.rule)) {}
        // row 2: now playing + the rig's transport
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (art != null && !off) Image(ImageProvider(art), "Album art", modifier = GlanceModifier.size(40.dp))
            else Box(GlanceModifier.size(40.dp).background(P.artBg)) {}
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                val title = when { off -> "—"; !s.hasPlayer -> "nothing playing"; else -> s.title.ifEmpty { "—" } }
                val sub = when { off -> "no connection"; !s.hasPlayer -> "on ${Deck.host.lowercase()}"; else -> s.artist }
                Text(title, style = mono(11.sp, text), maxLines = 1)
                if (sub.isNotEmpty()) Text(sub, style = mono(10.sp, P.onVar), maxLines = 1)
            }
            val ctl = when { off -> P.dead; stale -> P.onVar; else -> P.on }
            Transport(R.drawable.w_prev, "Previous", ctl, 32.dp, if (off) null else "previous")
            Transport(if (s.playing) R.drawable.w_pause else R.drawable.w_play, "Play or pause", ctl, 36.dp, if (off) null else "play-pause")
            Transport(R.drawable.w_next, "Next", ctl, 32.dp, if (off) null else "next")
        }
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(P.rule)) {}
        Spacer(GlanceModifier.height(8.dp))
        // row 3: two toggles, then the first two modes
        Row(GlanceModifier.fillMaxWidth().height(36.dp)) {
            Chip(R.drawable.w_listen, "LISTEN", if (off) Look.DEAD else if (s.listening) Look.ON else Look.OFF,
                if (off) null else actionStartActivity(listenIntent(context)))
            Spacer(GlanceModifier.width(6.dp))
            Chip(R.drawable.w_screens, "SCREENS", if (off || !s.screensKnown) Look.DEAD else if (s.screensOn) Look.ON else Look.OFF,
                if (off || !s.screensKnown) null else actionRunCallback<ScreensAction>())
            s.modes.take(2).forEach { m ->
                Spacer(GlanceModifier.width(6.dp))
                Chip(if (m.id == "free") R.drawable.w_free else R.drawable.w_work, m.label.uppercase(), if (off) Look.DEAD else Look.MODE,
                    if (off) null else actionRunCallback<ModeAction>(actionParametersOf(ModeKey to m.id, LabelKey to m.label)))
            }
        }
    }
}

@Composable
private fun Transport(icon: Int, label: String, tint: ColorProvider, width: Dp, act: String?) {
    var m = GlanceModifier.size(width, 40.dp)
    if (act != null) m = m.clickable(actionRunCallback<MediaAction>(actionParametersOf(ActKey to act)))
    Box(m, contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), label, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(if (width > 32.dp) 20.dp else 16.dp))
    }
}

private enum class Look(val bg: Int, val fg: ColorProvider) {
    ON(R.drawable.w_chip_on, P.onPrimary), OFF(R.drawable.w_chip_off, P.on),
    MODE(R.drawable.w_chip_mode, P.amber), DEAD(R.drawable.w_chip_dead, P.dead),
}

@Composable
private fun androidx.glance.layout.RowScope.Chip(icon: Int, label: String, look: Look, action: androidx.glance.action.Action?) {
    var m = GlanceModifier.defaultWeight().fillMaxHeight().background(ImageProvider(look.bg))
    if (action != null) m = m.clickable(action)
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(icon), null, colorFilter = ColorFilter.tint(look.fg), modifier = GlanceModifier.size(13.dp))
        Spacer(GlanceModifier.width(4.dp))
        Text(label, style = mono(10.sp, look.fg), maxLines = 1)
    }
}

/** Distinct data URIs keep each PendingIntent separate. */
private fun openApp(ctx: Context) = Intent(ctx, MainActivity::class.java).setData(Uri.parse("archon://widget/open"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** LISTEN goes through the shortcut trampoline: starting the media service from an activity is
 *  always allowed, and it's the same audio-only start as the tile and the Listen shortcut. */
private fun listenIntent(ctx: Context) = Intent(ctx, ShortcutActivity::class.java).setData(Uri.parse("archon://widget/listen"))
    .setAction("dev.kondraki.archon.SHORTCUT").putExtra("do", "listen-toggle")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

private val ActKey = ActionParameters.Key<String>("act")
private val ModeKey = ActionParameters.Key<String>("mode")
private val LabelKey = ActionParameters.Key<String>("label")

private fun toast(ctx: Context, msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

/* Taps run in a broadcast receiver with a ~10 s budget (handoff §9.9): every call is capped. */

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = RemoteState.refresh(context)
}

class MediaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val act = parameters[ActKey] ?: return
        if (act == "play-pause") RemoteState.assume(context) { it.copy(playing = !it.playing) }
        if (withTimeoutOrNull(4000) { Deck.post("/media/$act") } != true) toast(context, "Rig offline, not sent")
        delay(2500)                                          // the deck re-reads audio every 2 s
        RemoteState.refresh(context)
    }
}

class ScreensAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val goHeadless = RemoteState.state.value.screensOn
        RemoteState.assume(context) { it.copy(screensOn = !goHeadless) }
        val r = withTimeoutOrNull(7000) { Deck.postJson("/headless", buildJsonObject { put("on", goHeadless) }) }
        if (r == null) toast(context, if (goHeadless) "Going headless…" else "Screens on…")   // the script may still be running
        RemoteState.refresh(context)
    }
}

class ModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ModeKey] ?: return
        val label = parameters[LabelKey] ?: id
        val r = withTimeoutOrNull(8000) { Deck.postJson("/modes/run", buildJsonObject { put("id", id) }) }
        toast(context, when {
            r == null -> "Rig offline, not run"
            r.bool("ok") == true -> "$label mode started"
            else -> "Failed: ${r.str("error") ?: "the rig said no"}"
        })
    }
}
