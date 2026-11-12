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
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
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
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.kondraki.archon.MainActivity
import dev.kondraki.archon.R
import dev.kondraki.archon.ShortcutActivity
import dev.kondraki.archon.StickActivity
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Fresh
import dev.kondraki.archon.data.RemoteSnap
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.nextOutput
import dev.kondraki.archon.data.remoteFresh
import dev.kondraki.archon.data.shortOutput
import dev.kondraki.archon.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Geometry the wake layer (StickActivity) mirrors exactly: it only gets the stick box's bounds. */
object RcGeom {
    const val CAP_DP = 104
    const val LR_GAP_DP = 10
    const val LR_H_DP = 56
}

private object Rc {
    val on = ColorProvider(Color(0xFFEDC3C3))
    val dim = ColorProvider(Color(0xFFB77A7A))
    val faint = ColorProvider(Color(0xFF6A4646))
    val red = ColorProvider(Color(0xFFFA4242))
    val amber = ColorProvider(Color(0xFFFFB000))
    val amberDim = ColorProvider(Color(0xFFC98A00))
    val dead = ColorProvider(Color(0xFF5A3A3A))
    val dark = ColorProvider(Color(0xFF1A0505))
}

/** The phone's font-size setting (the M52 is at 0.8): widget text in sp follows it, so the remote
 *  divides it back out, like the app pins fontScale 1, and stays the size it was drawn at. */
@Volatile private var FS = 1f

private fun mono(size: TextUnit, color: ColorProvider, bold: Boolean = false, center: Boolean = false) =
    TextStyle(color = color, fontSize = (size.value / FS).sp, fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = if (center) TextAlign.Center else TextAlign.Start)

/**
 * The full-screen remote (canvas RemoteControl board v2, tasks #143): a physical TV remote for
 * the rig. Keys are plain widget taps (one tap → one command, the IR LED lit while it travels);
 * the stick is a tap-to-wake mouse: StickActivity opens over it as a real trackpad.
 */
class RemoteControlWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        RemoteState.load(context)
        FS = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val s = RemoteState.state.value
        if (!s.live && System.currentTimeMillis() - s.at > 60_000L) RemoteState.scope.launch { RemoteState.refresh(context) }
        provideContent {
            val snap by RemoteState.state.collectAsState()
            val tx by RemoteState.tx.collectAsState()
            Handset(context, snap, tx > 0)
        }
    }
}

class RemoteControlReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemoteControlWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); RemoteWorker.schedule(context) }
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RemoteWorker.schedule(context)
    }
    override fun onDisabled(context: Context) { super.onDisabled(context); RemoteWorker.cancelIfUnused(context) }
}

private val OpKey = ActionParameters.Key<String>("op")
private val ArgKey = ActionParameters.Key<String>("arg")
private fun op(name: String, arg: String = ""): Action = actionRunCallback<RcAction>(actionParametersOf(OpKey to name, ArgKey to arg))

@Composable
private fun Handset(ctx: Context, s: RemoteSnap, sending: Boolean) {
    val off = remoteFresh(s, System.currentTimeMillis()) == Fresh.OFFLINE
    val standby = s.screensKnown && !s.screensOn
    fun act(a: Action) = if (off) null else a
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.rc_body)).padding(horizontal = 18.dp, vertical = 14.dp)) {
        // IR LED · SOURCE ................ POWER
        Row(GlanceModifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(if (sending) R.drawable.rc_led_on else R.drawable.rc_led_off), "Sending", modifier = GlanceModifier.size(11.dp))
            Spacer(GlanceModifier.width(8.dp))
            SmallKey("SOURCE", GlanceModifier.width(104.dp).height(40.dp), act(op("source")), dead = off)
            Spacer(GlanceModifier.defaultWeight())
            Box(GlanceModifier.size(56.dp).background(ImageProvider(if (standby) R.drawable.rc_power_lit else R.drawable.rc_power))
                .let { m -> act(op("power"))?.let { m.clickable(it) } ?: m }, contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.rc_power_icon), "Power: screens", colorFilter = ColorFilter.tint(Rc.dark), modifier = GlanceModifier.size(26.dp))
            }
        }
        // the display: tap = open Archon (offline: retry)
        Column(GlanceModifier.fillMaxWidth().padding(top = 12.dp).height(88.dp).background(ImageProvider(R.drawable.rc_lcd)).padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(if (off) actionRunCallback<RefreshAction>() else actionStartActivity(openApp(ctx)))) {
            val line1 = when {
                off -> "NO SIGNAL · TAP TO RETRY"
                standby -> "STANDBY · PANELS OFF"
                !s.hasPlayer -> "NOTHING PLAYING"
                else -> (if (s.playing) "▶ " else "‖ ") + s.title.uppercase()
            }
            Text(line1, style = mono(15.sp, if (off) Rc.amberDim else Rc.amber), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth()) {
                val vol = if (off) "--" else (s.volume?.let { "VOL $it" + if (s.sinkMuted) " MUTED" else "" } ?: "VOL --") + "  WS ${s.ws ?: "-"}"
                Text(vol, style = mono(14.sp, Rc.amberDim), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                val out = when { off -> "--"; s.listening -> "PHONE ONLY"; else -> shortOutput(s.outputs.firstOrNull { it.active }?.name.orEmpty()) }
                Text(out, style = mono(14.sp, Rc.amberDim), maxLines = 1)
            }
        }
        // BACK · SPACE · ENTER · MIC
        Row(GlanceModifier.fillMaxWidth().padding(top = 12.dp).height(44.dp)) {
            SmallKey("BACK", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("key", "esc")), dead = off)
            Spacer(GlanceModifier.width(8.dp))
            SmallKey("SPACE", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("key", "space")), dead = off)   // replaced HOME (#152)
            Spacer(GlanceModifier.width(8.dp))
            SmallKey("ENTER", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("key", "enter")), dead = off)
            Spacer(GlanceModifier.width(8.dp))
            SmallKey("MIC", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("mic")), dead = off, lit = s.micMuted)
        }
        // the stick: tap to wake (StickActivity opens exactly over this block)
        Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 12.dp)) {
            Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.rc_well), null, contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
                Box(GlanceModifier.size(RcGeom.CAP_DP.dp).background(ImageProvider(R.drawable.rc_cap)), contentAlignment = Alignment.Center) {
                    Text(if (off) "" else "TAP", style = mono(13.sp, Rc.faint, center = true))
                }
                // on top: the hit area, a raw RemoteViews frame with a MUTABLE PendingIntent, so the
                // launcher can fill in the tap's screen bounds (StickActivity sits exactly over this box)
                if (!off) AndroidRemoteViews(stickHit(ctx), GlanceModifier.fillMaxSize())
            }
            Row(GlanceModifier.fillMaxWidth().padding(top = RcGeom.LR_GAP_DP.dp).height(RcGeom.LR_H_DP.dp)) {
                BigKey("L", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("click", "left")), off)
                Spacer(GlanceModifier.width(8.dp))
                BigKey("R", GlanceModifier.defaultWeight().fillMaxHeight(), act(op("click", "right")), off)
            }
        }
        // VOL · MUTE + transport · CH
        Row(GlanceModifier.fillMaxWidth().padding(top = 12.dp).height(140.dp), verticalAlignment = Alignment.CenterVertically) {
            Rocker("+", "VOL", "−", act(op("vol", "5")), act(op("vol", "-5")), off)
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                SmallKey("MUTE", GlanceModifier.width(110.dp).height(46.dp), act(op("mute")), dead = off, lit = s.sinkMuted)
                Spacer(GlanceModifier.height(10.dp))
                Row {
                    IconKey(R.drawable.w_prev, "Previous", 62.dp, act(op("media", "previous")), off)
                    Spacer(GlanceModifier.width(8.dp))
                    IconKey(if (s.playing) R.drawable.w_pause else R.drawable.w_play, "Play or pause", 76.dp, act(op("media", "play-pause")), off)
                    Spacer(GlanceModifier.width(8.dp))
                    IconKey(R.drawable.w_next, "Next", 62.dp, act(op("media", "next")), off)
                }
            }
            Rocker("▲", "CH", "▼", act(op("ch", "1")), act(op("ch", "-1")), off)
        }
        // colour keys
        Row(GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
            val modes = s.modes.take(2)
            ColourKey(if (off) R.drawable.rc_bar_dead else R.drawable.rc_bar_red, "LOCK", act(op("lock")), off)
            Spacer(GlanceModifier.width(10.dp))
            ColourKey(if (off) R.drawable.rc_bar_dead else R.drawable.rc_bar_green, (modes.getOrNull(0)?.label ?: "WORK").uppercase(),
                modes.getOrNull(0)?.let { act(op("mode", it.id + "|" + it.label)) }, off)
            Spacer(GlanceModifier.width(10.dp))
            ColourKey(if (off) R.drawable.rc_bar_dead else R.drawable.rc_bar_yellow, (modes.getOrNull(1)?.label ?: "FREE").uppercase(),
                modes.getOrNull(1)?.let { act(op("mode", it.id + "|" + it.label)) }, off)
            Spacer(GlanceModifier.width(10.dp))
            ColourKey(when { off -> R.drawable.rc_bar_dead; s.listening -> R.drawable.rc_bar_blue_lit; else -> R.drawable.rc_bar_blue },
                if (s.listening) "LISTENING" else "LISTEN", act(actionStartActivity(listenIntent(ctx))), off)
        }
        // the maker's mark
        Column(GlanceModifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.emblem), "Archon", colorFilter = ColorFilter.tint(Rc.red), modifier = GlanceModifier.size(20.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text("A R C H O N", style = mono(16.sp, Rc.red, bold = true))
            }
            Text("${Deck.host} · LT-01", style = mono(11.sp, Rc.faint, center = true))
        }
    }
}

private fun GlanceModifier.tap(a: Action?) = if (a == null) this else clickable(a)

@Composable
private fun SmallKey(label: String, modifier: GlanceModifier, a: Action?, dead: Boolean = false, lit: Boolean = false) {
    Box(modifier.background(ImageProvider(when { dead -> R.drawable.rc_key_dead; lit -> R.drawable.rc_key_lit; else -> R.drawable.rc_key_small })).tap(a),
        contentAlignment = Alignment.Center) {
        Text(label, style = mono(14.sp, when { dead -> Rc.dead; lit -> Rc.red; else -> Rc.dim }, center = true), maxLines = 1)
    }
}

@Composable
private fun BigKey(label: String, modifier: GlanceModifier, a: Action?, dead: Boolean) {
    Box(modifier.background(ImageProvider(if (dead) R.drawable.rc_key_dead else R.drawable.rc_key)).tap(a), contentAlignment = Alignment.Center) {
        Text(label, style = mono(22.sp, if (dead) Rc.dead else Rc.on, center = true))
    }
}

@Composable
private fun IconKey(icon: Int, label: String, w: Dp, a: Action?, dead: Boolean) {
    Box(GlanceModifier.width(w).height(46.dp).background(ImageProvider(if (dead) R.drawable.rc_key_dead else R.drawable.rc_rocker)).tap(a),
        contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), label, colorFilter = ColorFilter.tint(if (dead) Rc.dead else Rc.on), modifier = GlanceModifier.size(22.dp))
    }
}

/** A VOL / CH rocker: one pill, its top and bottom halves are separate keys. */
@Composable
private fun Rocker(up: String, label: String, down: String, aUp: Action?, aDown: Action?, dead: Boolean) {
    val fg = if (dead) Rc.dead else Rc.on
    Column(GlanceModifier.width(64.dp).fillMaxHeight().background(ImageProvider(if (dead) R.drawable.rc_key_dead else R.drawable.rc_rocker)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.fillMaxWidth().defaultWeight().tap(aUp), contentAlignment = Alignment.Center) { Text(up, style = mono(24.sp, fg, center = true)) }
        Text(label, style = mono(13.sp, if (dead) Rc.dead else Rc.dim, center = true))
        Box(GlanceModifier.fillMaxWidth().defaultWeight().tap(aDown), contentAlignment = Alignment.Center) { Text(down, style = mono(24.sp, fg, center = true)) }
    }
}

@Composable
private fun RowScope.ColourKey(bar: Int, label: String, a: Action?, dead: Boolean) {
    Column(GlanceModifier.defaultWeight().tap(a), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.fillMaxWidth().height(22.dp).background(ImageProvider(bar))) {}
        Spacer(GlanceModifier.height(4.dp))
        Text(label, style = mono(12.sp, if (dead) Rc.dead else Rc.dim, center = true), maxLines = 1)
    }
}

private fun openApp(ctx: Context) = Intent(ctx, MainActivity::class.java).setData(Uri.parse("archon://remote/open"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** With "Display over other apps", the stick wakes as a floating window (StickOverlay, via a
 *  broadcast); without it, as StickActivity. Chosen at each render; opening Archon re-renders. */
private fun stickHit(ctx: Context) = android.widget.RemoteViews(ctx.packageName, R.layout.rc_stick_hit).apply {
    val flags = android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    setOnClickPendingIntent(R.id.rc_stick_hit,
        if (dev.kondraki.archon.StickOverlay.allowed(ctx))
            android.app.PendingIntent.getBroadcast(ctx, 8, Intent(ctx, dev.kondraki.archon.StickReceiver::class.java)
                .setData(Uri.parse("archon://remote/stick-overlay")), flags)
        else android.app.PendingIntent.getActivity(ctx, 7, stickIntent(ctx), flags))
}

private fun stickIntent(ctx: Context) = Intent(ctx, StickActivity::class.java).setData(Uri.parse("archon://remote/stick"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

private fun listenIntent(ctx: Context) = Intent(ctx, ShortcutActivity::class.java).setData(Uri.parse("archon://remote/listen"))
    .setAction("dev.kondraki.archon.SHORTCUT").putExtra("do", "listen-toggle")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

private fun toast(ctx: Context, msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

/** Every key of the remote. Runs in a broadcast receiver (~10 s budget): each call is capped,
 *  and the IR LED is lit for the duration. */
class RcAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val op = parameters[OpKey] ?: return
        val arg = parameters[ArgKey].orEmpty()
        if (Deck.session.token.value == null) { toast(context, "Open Archon and sign in first"); return }
        val snap = RemoteState.state.value
        fun body(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(b)
        suspend fun call(path: String, b: JsonObject = body { }, cap: Long = 4000): JsonObject? =
            withTimeoutOrNull(cap) { Deck.postJson(path, b) }
        val r: JsonObject? = RemoteState.sending(context) {
            when (op) {
                "power" -> {
                    if (!snap.screensKnown) return@sending null
                    RemoteState.assume(context) { it.copy(screensOn = !snap.screensOn) }
                    call("/headless", body { put("on", snap.screensOn) }, 8000)
                }
                "source" -> nextOutput(snap.outputs)?.let { o -> call("/audio/sink", body { put("id", o.id) }) }
                "vol" -> {
                    val step = arg.toIntOrNull() ?: 0
                    snap.volume?.let { v -> RemoteState.assume(context) { it.copy(volume = (v + step).coerceIn(0, maxOf(v, 100))) } }
                    call("/audio/volume", body { put("step", step) })
                }
                "mute" -> { RemoteState.assume(context) { it.copy(sinkMuted = !it.sinkMuted) }; call("/audio/mute", body { put("target", "sink") }) }
                "mic" -> { RemoteState.assume(context) { it.copy(micMuted = !it.micMuted) }; call("/audio/mute", body { put("target", "mic") }) }
                "ch" -> call("/hypr/workspace", body { put("rel", arg.toIntOrNull() ?: 1) })
                "media" -> {
                    if (arg == "play-pause") RemoteState.assume(context) { it.copy(playing = !it.playing) }
                    if (withTimeoutOrNull(4000) { Deck.post("/media/$arg") } == true) body { put("ok", true) } else null
                }
                "key" -> call("/input", body { put("t", "combo"); putJsonArray("keys") { add(arg) } })
                "click" -> call("/input", body { put("t", "click"); put("b", arg) })
                "lock" -> call("/sys/lock")
                "mode" -> call("/modes/run", body { put("id", arg.substringBefore('|')) }, 8000)
                else -> null
            }
        }
        when {
            r == null -> toast(context, "Rig offline, not sent")
            r.bool("ok") == false -> toast(context, "Failed: ${r.str("error") ?: "the rig said no"}")
            op == "mode" -> toast(context, "${arg.substringAfter('|')} mode started")
            op == "lock" -> toast(context, "Rig locked")
        }
        // keep the optimistic face until the deck has re-read: it polls audio every 2 s, so an
        // immediate GET /state returns the pre-press value and flips MUTE / VOL straight back
        if (op in setOf("media", "mute", "mic", "vol", "source", "ch")) delay(2500)
        RemoteState.refresh(context)
    }
}
