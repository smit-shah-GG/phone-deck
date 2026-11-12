package dev.kondraki.archon.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.plainNum
import dev.kondraki.archon.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val WS_COUNT = 15            // app.js WS_COUNT: workspaces shown per monitor
private val WIN_LIST_H = 154.dp            // five window rows (~30 dp pitch, measured on the M52)

/** HOME: one card per monitor in PHYSICAL order (capped at 3), each with its workspace grid at
 *  the bottom, then a strip with the context row and the docked mini player (#156). */
@Composable
fun Home(state: JsonObject?) {
    val ph = LocalPh.current
    val hypr = state.obj("hypr")
    val mons = hypr.list("monitors")
    val wins = hypr.list("windows")
    val scope = rememberCoroutineScope()
    fun post(path: String, body: JsonObject) { scope.launch { Deck.post(path, body) } }

    // brightness levels (ddcutil) and the context config are fetched, not pushed (app.js does the same)
    var bright by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var ctx by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        ctx = Deck.getJson("/context")
        while (true) {
            Deck.getJson("/brightness")?.let { b -> bright = b.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { k to it.toInt() } }.toMap() }
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mons.take(3).forEach { m ->
                val name = m.str("name") ?: "?"
                MonitorCard(
                    m, wins.filter { it.str("monitor") == name }, bright[name],
                    Modifier.weight(1f).fillMaxHeight(),
                    onWs = { n -> post("/hypr/workspace", buildJsonObject { put("id", n) }) },
                    onDpms = { on -> post("/hypr/dpms", buildJsonObject { put("monitor", name); put("on", on) }) },
                    onBright = { pct -> bright = bright + (name to pct); post("/brightness", buildJsonObject { put("monitor", name); put("pct", pct) }) },
                    onWin = { addr -> post("/hypr/focus", buildJsonObject { put("address", addr) }) },
                )
            }
        }
        // the bottom strip (#156): the context row shares it with the docked mini player
        HandRow(Modifier.fillMaxWidth(), gap = 12.dp, align = Alignment.CenterVertically,
            look = { Box(Modifier.weight(1f)) {
                ContextStrip(state, ctx) { path, body -> post(path, body) } } },
            thumb = { MiniPlayer() })
    }
}

@Composable
private fun MonitorCard(
    m: JsonObject, wins: List<JsonObject>, bright: Int?, modifier: Modifier,
    onWs: (Int) -> Unit, onDpms: (Boolean) -> Unit, onBright: (Int) -> Unit, onWin: (String) -> Unit,
) {
    val ph = LocalPh.current
    val active = m.num("active_ws")?.toInt()
    val dpms = m.bool("dpms") != false
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("${m.str("name")} · ${m.str("model").orEmpty()} · ${plainNum(m["refresh"])}Hz", 12.sp,
                ph.onVar.copy(alpha = 0.7f), Modifier.weight(1f, fill = false))
            if (m.bool("focused") == true) Mono(" ●", 12.sp, ph.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.clip(shape).background(if (dpms) ph.high else Color(0xFF991B1B))
                .border(1.dp, ph.outline, shape).tap { onDpms(!dpms) }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Mono(if (dpms) "● on" else "○ off", 12.sp, ph.on)
            }
            Label("BRT", 12.sp, ph.primary, spacing = 0f, glow = true)
            BrightSlider(bright, Modifier.weight(1f), onBright)
        }
        // five window entries always fit (more scroll); the workspace grid takes what's left (#156)
        Column(Modifier.height(WIN_LIST_H).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            wins.forEach { w ->
                val ws = w.num("ws")?.toInt()
                val title = w.str("title")?.takeIf { it.isNotEmpty() } ?: w.str("cls") ?: "?"
                WinButton((if (ws != null) "$ws · " else "") + title, w.bool("focused") == true) {
                    w.str("address")?.let(onWin)
                }
            }
        }
        // the workspace grid at the BOTTOM of the card: tapped constantly, so nearest the thumbs (#156)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..WS_COUNT).chunked(5).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { n -> WsButton(n, n == active, Modifier.weight(1f).fillMaxHeight()) { onWs(n) } }
                }
            }
        }
    }
}

/** .bg-zinc-900.rounded-xl in theme.css: a top-lit card with box-drawing corner ticks. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ph = LocalPh.current
    Column(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(ph.surface, ph.low)))
            .cornerTicks(ph.primary.copy(alpha = 0.55f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

fun Modifier.cornerTicks(color: Color, len: Dp = 11.dp, inset: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val l = len.toPx(); val i = inset.toPx(); val w = 1f
    drawLine(color, Offset(i, i), Offset(i + l, i), w); drawLine(color, Offset(i, i), Offset(i, i + l), w)
    val r = size.width - i; val b = size.height - i
    drawLine(color, Offset(r, b), Offset(r - l, b), w); drawLine(color, Offset(r, b), Offset(r, b - l), w)
}

/** The breathing glow of the active workspace / focused window (theme.css pglow, 2.6 s). Off
 *  with the CRT switch, like the PWA. */
@Composable
private fun Modifier.breathe(on: Boolean): Modifier {
    val ph = LocalPh.current
    if (!on) return this
    val t = if (ph.crt) rememberInfiniteTransition(label = "pglow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pglow").value else 0f
    return drawBehind {
        // box-shadow 0 0 12px primary@45% → 16px @70% at the peak: a tight halo, not a bloom
        val spread = (6 + 3 * t).dp.toPx()
        drawRoundRect(
            Brush.radialGradient(listOf(ph.primary.copy(alpha = 0.30f + 0.20f * t), Color.Transparent),
                center = center, radius = size.maxDimension / 2 + spread),
            topLeft = Offset(-spread, -spread), size = Size(size.width + 2 * spread, size.height + 2 * spread),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(spread),
        )
    }
}

/** filled primary (theme.css .bg-emerald-600): 88% primary over black, dark text. */
private fun Phosphor.filled() = lerp(Color.Black, primary, 0.88f)

@Composable
private fun WsButton(n: Int, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.breathe(active).clip(shape)
        .background(if (active) ph.filled() else ph.high)
        .border(1.dp, ph.outline, shape).tap(onClick), contentAlignment = Alignment.Center) {
        Mono(n.toString(), 14.sp, if (active) ph.onPrimary else ph.on)
    }
}

@Composable
private fun WinButton(text: String, focused: Boolean, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().breathe(focused).clip(shape)
        .background(if (focused) ph.filled() else ph.high)
        .border(1.dp, ph.outline, shape).tap(onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Mono(text, 12.sp, if (focused) ph.onPrimary else ph.on)
    }
}

/** The per-monitor brightness range (0–100, posts on release): the PWA shows Chrome's range
 *  with a primary fill left of the thumb. Disabled while ddcutil hasn't reported the monitor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrightSlider(value: Int?, modifier: Modifier, onSet: (Int) -> Unit) {
    val ph = LocalPh.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) local else (value ?: 50).toFloat()
    Slider(
        value = shown, valueRange = 0f..100f, enabled = value != null,
        onValueChange = { dragging = true; local = it },
        onValueChangeFinished = { onSet(local.toInt()); dragging = false },
        modifier = modifier.height(26.dp),
        thumb = { Box(Modifier.size(20.dp).clip(CircleShape).background(if (value != null) ph.primary else ph.outline)) },
        track = {
            val frac = shown / 100f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(ph.on.copy(alpha = 0.85f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (value != null) ph.primary else ph.outline))
            }
        },
    )
}

/** The context strip under the cards: a call row (mute + jump) while a call app records, and
 *  per-app key rows from context.json for the focused (or persist-present) window class. */
@Composable
private fun ContextStrip(state: JsonObject?, ctx: JsonObject?, post: (String, JsonObject) -> Unit) {
    val ph = LocalPh.current
    val hypr = state.obj("hypr")
    val wins = hypr.list("windows")
    val a = state.obj("audio")
    val focusedCls = wins.firstOrNull { it.bool("focused") == true }?.str("cls") ?: hypr.obj("active_window").str("class").orEmpty()
    fun loose(x: String, y: String) = x == y || x.contains(y) || y.contains(x)

    val rows = mutableListOf<@Composable RowScope.() -> Unit>()
    val calls = (ctx?.get("call_apps") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    val recording = (a?.get("recording") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    val rec = recording.filter { r -> calls.any { loose(r, it) } }
    if (rec.isNotEmpty()) rows += {
        val muted = a.bool("mic_muted") == true
        val callWin = wins.firstOrNull { w -> w.str("cls")?.let { c -> rec.any { loose(c, it) } } == true }
        CtxRow("Call", Modifier.weight(0.5f)) {
            CtxBtn(if (muted) "UNMUTE" else "MUTE", danger = muted, Modifier.weight(1f)) { post("/audio/mute", buildJsonObject { put("target", "mic") }) }
            callWin?.str("address")?.let { addr -> CtxBtn("↪ call", modifier = Modifier.weight(1f)) { post("/hypr/focus", buildJsonObject { put("address", addr) }) } }
        }
    }
    (ctx?.get("apps") as? JsonObject)?.forEach { (cls, appEl) ->
        val app = appEl as? JsonObject ?: return@forEach
        val present = if (app.str("show") == "persist") wins.any { it.str("cls") == cls } else focusedCls == cls
        if (!present) return@forEach
        rows += {
            CtxRow(app.str("label") ?: cls, Modifier.weight(1f)) {
                app.list("controls").forEach { c ->
                    CtxBtn(c.str("label") ?: c.str("key").orEmpty(), modifier = Modifier.weight(1f)) {
                        post("/context/key", buildJsonObject { put("cls", cls); put("key", c.str("key").orEmpty()); put("mods", c.str("mods").orEmpty()) })
                    }
                }
            }
        }
    }
    if (rows.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        rows.forEach { it() }
    }
}

@Composable
private fun CtxRow(label: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val ph = LocalPh.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label.uppercase(), 10.sp, ph.secondary, spacing = 0.14f)
        content()
    }
}

@Composable
private fun CtxBtn(text: String, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.height(40.dp).clip(shape).background(ph.high).border(1.dp, if (danger) DANGER else ph.outline, shape)
        .tap(onClick), contentAlignment = Alignment.Center) {
        Mono(text, 13.sp, if (danger) DANGER else ph.on)
    }
}

