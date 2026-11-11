package dev.kondraki.archon.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Ambient Cogitator (app.js cogShow/cogHide): the idle screen. Entry = a tap on the band's
 * clock, or idle_min minutes untouched (GET /theme ambient), held off while voice records or the
 * rig's screen is showing (#123 F, user 2026-11-11). Any touch exits and is swallowed
 * (MainActivity). Burn-in guard: the PWA's black + slow drift, and after 10 min the window dims
 * to 15% (#123 F) until a touch.
 */
object Cog {
    var on by mutableStateOf(false); private set
    var lastTouch = System.currentTimeMillis()
    var idleMin = 2
    var liturgy by mutableStateOf(true)
    /** Peak-hold ghosts, keyed like the PWA (g gt c ct vr ram). */
    val peak = mutableMapOf<String, Double>()
    var shownAt = 0L

    fun show() {
        if (on) return
        peak.clear(); shownAt = System.currentTimeMillis(); on = true
    }
    fun hide() { on = false }
    fun touched() { lastTouch = System.currentTimeMillis() }

    /** Read idle_min / liturgy (the System panel writes them). */
    suspend fun loadCfg() {
        val a = Deck.getJson("/theme").obj("ambient") ?: return
        a.num("idle_min")?.let { idleMin = it.toInt().coerceIn(1, 30) }
        (a["liturgy"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { liturgy = it }
    }

    /** "Was it pegged while I was away": seed the peaks from /history since the last touch. */
    suspend fun seedPeaks(since: Long) {
        val h = Deck.getElement("/history") as? JsonArray ?: return
        h.forEach { e ->
            val s = e as? JsonObject ?: return@forEach
            if ((s.num("t") ?: 0.0) < since) return@forEach
            listOf("g", "gt", "vr", "c", "ct", "ram").forEach { k -> s.num(k)?.let { v -> if (v > (peak[k] ?: 0.0)) peak[k] = v } }
        }
    }
}

private data class Cur(val g: Double, val gt: Double, val vr: Double, val vt: Double, val c: Double, val ct: Double, val ram: Double, val rt: Double) {
    operator fun get(k: String) = when (k) { "g" -> g; "gt" -> gt; "vr" -> vr; "c" -> c; "ct" -> ct; else -> ram }
}

private fun cur(state: JsonObject?): Cur {
    val t = state.obj("telemetry"); val g = t.obj("gpu"); val c = t.obj("cpu")
    return Cur(g.num("util") ?: 0.0, g.num("temp") ?: 0.0, g.num("vram_used") ?: 0.0, g.num("vram_total")?.takeIf { it > 0 } ?: 12288.0,
        c.num("util") ?: 0.0, c.num("temp") ?: 0.0, c.num("mem_used") ?: 0.0, c.num("mem_total")?.takeIf { it > 0 } ?: 32.0)
}

private data class Metric(val k: String, val lab: String, val unit: String, val lo: Double, val max: Double, val warn: Double, val col: Color, val fmt: (Double) -> String)

/** cogColors(): the GPU pair in the theme's primary, the CPU pair in the counter colour (amber,
 *  or green when the primary is amber-ish), danger in true red. */
private fun counterColor(p: Color): Color {
    val r = p.red; val g = p.green; val b = p.blue
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    var h = 0f
    if (mx != mn) {
        h = when (mx) { r -> (g - b) / (mx - mn); g -> 2 + (b - r) / (mx - mn); else -> 4 + (r - g) / (mx - mn) }
        h = (h * 60 + 360) % 360
    }
    return if (abs(h - 41) < 40) Color(51, 255, 160) else Color(255, 176, 0)
}
private val DANGER_C = Color(255, 82, 82)

private val LITANY: List<(Cur) -> String> = listOf(
    { _ -> "++ cogitator vigil unbroken ++" },
    { c -> "++ thermal rites nominal : ${"%.0f".format(c.gt)}°C ++" },
    { c -> "++ vram offering : ${"%.1f".format(c.vr / 1024)}G consecrated ++" },
    { _ -> "++ the omnissiah preserves : all systems sanctified ++" },
    { c -> "++ canticle of load : ${"%.0f".format(c.g)}% devotion ++" },
)

@Composable
fun Cogitator(state: JsonObject?) {
    val ph = LocalPh.current
    val P = ph.primary; val A = counterColor(P)
    var t by remember { mutableLongStateOf(0L) }                     // frame clock (ms)
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) { delay(33); t = withFrameMillis { it } - t0 } }
    LaunchedEffect(Unit) { Cog.seedPeaks(Cog.lastTouch) }
    val c = cur(state)
    Cog.peak.let { pk -> listOf("g", "gt", "vr", "c", "ct", "ram").forEach { k -> if (c[k] > (pk[k] ?: 0.0)) pk[k] = c[k] } }
    // eased display values (the needles glide, 0.06 per frame like the PWA)
    val disp = remember { mutableMapOf<String, Double>() }
    listOf("g", "gt", "vr", "c", "ct", "ram").forEach { k -> val v = c[k]; disp[k] = disp[k]?.let { it + (v - it) * 0.06 } ?: v }

    val sec = t / 1000f
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Lissajous pixel drift (AMOLED), 8 dp
        Column(Modifier.fillMaxSize().offset(x = (8 * sin(sec / 29)).dp, y = (8 * sin(sec / 37)).dp)
            .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.fillMaxWidth(0.30f).fillMaxHeight()) { Left(state, P) }
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).border(1.dp, P.copy(alpha = 0.14f), RoundedCornerShape(6.dp))) {
                    Canvas(Modifier.fillMaxSize()) { dials(c, disp, P, A) }
                }
            }
            NowPlaying(state, P, sec)
            if (Cog.liturgy) Liturgy(c, P)
        }
    }
}

@Composable
private fun Left(state: JsonObject?, P: Color) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(500) } }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
    val blink = (now.time / 1000) % 2 == 1L
    // the clock gets the whole column (the Outposts board was left out, #123 F)
    Text(buildAnnotatedString {
        append(hm.substring(0, 2))
        withStyle(SpanStyle(color = P.copy(alpha = if (blink) 0.78f * 0.15f else 0.78f))) { append(":") }
        append(hm.substring(3))
    }, style = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 96.sp, lineHeight = 96.sp,
        color = P.copy(alpha = 0.78f), shadow = Shadow(P.copy(alpha = 0.28f), Offset.Zero, 40f)))
    Spacer(Modifier.height(8.dp))
    Text(SimpleDateFormat("EEE, dd MMM", Locale.UK).format(now).replace(",", "").uppercase(),
        style = TextStyle(fontFamily = Plex, fontSize = 15.sp, letterSpacing = 0.22.em, color = P.copy(alpha = 0.40f)))
    val boot = state.obj("sysinfo").num("boot")
    if (boot != null) {
        val up = max(0.0, now.time / 1000.0 - boot).toLong()
        Spacer(Modifier.height(4.dp))
        Text("UPTIME ${up / 86400}D ${"%02d".format((up / 3600) % 24)}H ${"%02d".format((up / 60) % 60)}M",
            style = TextStyle(fontFamily = Plex, fontSize = 12.sp, letterSpacing = 0.18.em, color = P.copy(alpha = 0.28f)))
    }
}

private fun DrawScope.dials(cur: Cur, disp: Map<String, Double>, P: Color, A: Color) {
    val px = { n: Float -> n * density }
    val m = listOf(
        Metric("g", "GPU", "%", 0.0, 100.0, .80, P) { "%.0f".format(it) },
        Metric("gt", "GPU°", "°", 30.0, 90.0, .75, P) { "%.0f".format(it) },
        Metric("c", "CPU", "%", 0.0, 100.0, .80, A) { "%.0f".format(it) },
        Metric("ct", "CPU°", "°", 30.0, 90.0, .75, A) { "%.0f".format(it) },
        Metric("vr", "VRAM", "G", 0.0, cur.vt, .85, P) { "%.1f".format(it / 1024) },
        Metric("ram", "RAM", "G", 0.0, cur.rt, .85, A) { "%.1f".format(it) },
    )
    fun frac(mm: Metric, v: Double) = ((v - mm.lo) / (mm.max - mm.lo)).coerceIn(0.0, 1.0).toFloat()
    val W = size.width; val H = size.height
    val zoneH = H * .66f; val r = min(W / 9.2f, zoneH / 2.55f)
    val a0 = (PI * .75).toFloat(); val span = (PI * 1.5).toFloat()
    val cxs = floatArrayOf(.125f, .365f, .635f, .875f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    fun deg(a: Float) = a * 180f / PI.toFloat()
    m.take(4).forEachIndexed { i, mm ->
        val cx = W * cxs[i]; val cy = zoneH * .52f; val col = mm.col
        val f = frac(mm, disp[mm.k] ?: 0.0); val fp = frac(mm, Cog.peak[mm.k] ?: cur[mm.k]); val hot = f >= mm.warn
        val arc = Size(r * 2, r * 2); val tl = Offset(cx - r, cy - r)
        drawArc(col.copy(alpha = .20f), deg(a0), deg(span), false, tl, arc, style = Stroke(px(2f)))
        drawArc(DANGER_C.copy(alpha = .4f), deg(a0 + span * mm.warn.toFloat()), deg(span * (1 - mm.warn.toFloat())), false, tl, arc, style = Stroke(px(2f)))
        for (k in 0..10) {
            val a = a0 + span * k / 10; val big = k % 5 == 0; val inset = px(if (big) 7f else 4f)
            drawLine(col.copy(alpha = if (big) .32f else .13f), Offset(cx + cos(a) * (r - inset), cy + sin(a) * (r - inset)),
                Offset(cx + cos(a) * r, cy + sin(a) * r), px(if (big) 1.6f else 1f))
        }
        val ap = a0 + span * fp                                         // peak-hold ghost
        drawLine(col.copy(alpha = .22f), Offset(cx, cy), Offset(cx + cos(ap) * r * .82f, cy + sin(ap) * r * .82f), px(1.6f))
        val av = a0 + span * f                                          // the needle, glowing
        val nc = if (hot) DANGER_C.copy(alpha = .9f) else col.copy(alpha = .85f)
        for (g in 3 downTo 1) drawLine(nc.copy(alpha = .12f), Offset(cx - cos(av) * r * .12f, cy - sin(av) * r * .12f),
            Offset(cx + cos(av) * r * .82f, cy + sin(av) * r * .82f), px(2.2f + g * 2.5f), cap = StrokeCap.Round)
        drawLine(nc, Offset(cx - cos(av) * r * .12f, cy - sin(av) * r * .12f), Offset(cx + cos(av) * r * .82f, cy + sin(av) * r * .82f), px(2.2f))
        drawCircle(col.copy(alpha = .75f), px(2.5f), Offset(cx, cy))
        drawContext.canvas.nativeCanvas.apply {
            paint.color = (if (hot) DANGER_C.copy(alpha = .9f) else col.copy(alpha = .78f)).toArgbInt()
            paint.textSize = px(16f); paint.isFakeBoldText = true
            drawText(mm.fmt(disp[mm.k] ?: 0.0) + mm.unit, cx, cy + r * .62f, paint)
            paint.color = col.copy(alpha = .35f).toArgbInt(); paint.textSize = px(10f); paint.isFakeBoldText = false
            drawText(mm.lab, cx, cy + r + px(15f), paint)
        }
    }
    m.drop(4).forEachIndexed { i, mm ->                                // VRAM under the GPU pair, RAM under the CPU pair
        val bw = W * .42f; val bh = px(12f); val bx = W * (if (i == 0) .05f else .53f); val by = H * .78f; val col = mm.col
        val f = frac(mm, disp[mm.k] ?: 0.0); val fp = frac(mm, Cog.peak[mm.k] ?: cur[mm.k]); val hot = f >= mm.warn
        drawRect(col.copy(alpha = .22f), Offset(bx, by), Size(bw, bh), style = Stroke(px(1f)))
        drawRect((if (hot) DANGER_C else col).copy(alpha = if (hot) .75f else .5f), Offset(bx + px(1.5f), by + px(1.5f)), Size((bw - px(3f)) * f, bh - px(3f)))
        drawLine(col.copy(alpha = .4f), Offset(bx + bw * fp, by - px(2f)), Offset(bx + bw * fp, by + bh + px(2f)), px(1f))
        val total = if (mm.k == "vr") "%.0f".format(cur.vt / 1024) else "%.0f".format(cur.rt)
        drawContext.canvas.nativeCanvas.apply {
            paint.textAlign = android.graphics.Paint.Align.LEFT
            paint.color = col.copy(alpha = .55f).toArgbInt(); paint.textSize = px(10f)
            drawText("${mm.lab} ${mm.fmt(disp[mm.k] ?: 0.0)}/$total${mm.unit}", bx, by + bh + px(14f), paint)
            paint.textAlign = android.graphics.Paint.Align.CENTER
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

@Composable
private fun NowPlaying(state: JsonObject?, P: Color, sec: Float) {
    val np = state.obj("audio").obj("now_playing")
    val title = np.str("title").orEmpty()
    val key = np.str("art_key").orEmpty()
    var art by remember { mutableStateOf<Pair<String, Bitmap?>?>(null) }
    LaunchedEffect(key) { if (key.isNotEmpty() && art?.first != key) art = key to Deck.art(key) }
    val playing = np.str("status") == "Playing"
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bmp = art?.takeIf { it.first == key && title.isNotEmpty() }?.second
        if (bmp != null) {
            // duotone: greyscale, then multiplied by the theme's primary
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, P.copy(alpha = .18f), RoundedCornerShape(4.dp))
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); drawRect(P, blendMode = BlendMode.Multiply) }) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(if (title.isEmpty()) "— NO SIGNAL —" else if (playing) "▶ PLAYING" else "‖ PAUSED",
                style = TextStyle(fontFamily = Plex, fontSize = 11.sp, letterSpacing = 0.2.em, color = Color(255, 176, 0).copy(alpha = .55f)))
            if (title.isNotEmpty()) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Plex, fontSize = 14.sp, color = P.copy(alpha = .58f)))
        }
        // the decorative scope: alive while the rig plays, a calm ripple when it doesn't
        val ampH = remember { floatArrayOf(0.06f) }                      // eased per frame, not state
        ampH[0] += ((if (playing) 1f else 0.06f) - ampH[0]) * 0.05f
        val amp = ampH[0]
        Box(Modifier.width(130.dp).height(56.dp).drawWithContent {
            drawContent()
            drawRoundRect(P.copy(alpha = .12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.width(120.dp).height(38.dp)) {
                val mid = size.height / 2; val a = size.height * .34f * amp
                val path = androidx.compose.ui.graphics.Path()
                var x = 0f
                while (x <= size.width) {
                    val u = x / size.width
                    val y = mid + a * (0.6f * sin(u * 22 - sec * 6) + 0.3f * sin(u * 41 + sec * 3.3f) + 0.1f * sin(u * 90 - sec * 11))
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += density
                }
                val alpha = 0.3f + 0.6f * amp
                drawPath(path, P.copy(alpha = alpha * .35f), style = Stroke(density * 4))
                drawPath(path, P.copy(alpha = alpha), style = Stroke(density))
            }
        }
    }
}

@Composable
private fun Liturgy(c: Cur, P: Color) {
    var idx by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); shown = false; delay(1200); idx = (idx + 1) % LITANY.size; shown = true } }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(1200), label = "liturgy")
    Text(LITANY[idx](c), modifier = Modifier.fillMaxWidth().alpha(a).padding(bottom = 2.dp),
        style = TextStyle(fontFamily = Plex, fontSize = 11.sp, letterSpacing = 0.14.em, color = P.copy(alpha = .26f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center))
}
