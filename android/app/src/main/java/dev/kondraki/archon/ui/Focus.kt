package dev.kondraki.archon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.InputLink
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import dev.kondraki.archon.stream.Gamepad
import dev.kondraki.archon.stream.StreamEngine
import dev.kondraki.archon.stream.VideoGesture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Stream focus mode (#80): the stage over the whole app, the video anchored left, one gamepad
 *  column on the right. */
object StreamUi { val focus = mutableStateOf(false) }

private fun input(t: String, fx: Float? = null, fy: Float? = null, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) =
    InputLink.send(buildJsonObject {
        put("t", t); StreamEngine.opts.monitor?.let { put("monitor", it) }
        if (fx != null && fy != null) { put("fx", fx); put("fy", fy) }
        extra?.invoke(this)
    })

/**
 * The rig's screen, sized to its real aspect (letterboxed, never cropped), with touch-on-video
 * on top: tap = click there, hold 500 ms = right-click, drag = left-button drag. In focus mode it
 * also draws the crosshair the server reports, which leads the video's lag.
 */
@Composable
fun VideoSurface(track: VideoTrack, crosshair: Boolean, modifier: Modifier = Modifier, touch: Boolean = true) {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val view = remember {
        SurfaceViewRenderer(ctx).apply {
            init(StreamEngine.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                    val (fw, fh) = if (rotation % 180 == 0) w to h else h to w
                    if (fw > 0 && fh > 0) post { aspect = fw.toFloat() / fh }
                }
            })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(track) { track.addSink(view); onDispose { track.removeSink(view) } }
    DisposableEffect(Unit) { onDispose { view.release() } }
    val cur by InputLink.cur.collectAsState()

    Box(modifier.aspectRatio(aspect)) {
        AndroidView({ view }, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().then(if (!touch) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val g = VideoGesture()
                val down = awaitFirstDown()
                fun frac(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (o.y / size.height).coerceIn(0f, 1f)
                var lastMove = 0L
                fun emit(sends: List<VideoGesture.Send>, at: Offset) {
                    val (fx, fy) = frac(at)
                    for (s in sends) when (s) {
                        VideoGesture.Send.MOVE -> {
                            val now = System.currentTimeMillis()
                            if (!g.isDragging || now - lastMove > 40) { lastMove = now; input("vmove", fx, fy) }
                        }
                        VideoGesture.Send.DOWN -> { input("vdown", fx, fy); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        VideoGesture.Send.UP -> input("vup")
                        VideoGesture.Send.CLICK -> { input("vclick", fx, fy); haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
                        VideoGesture.Send.RCLICK -> { input("vrclick", fx, fy); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    }
                }
                emit(g.down(down.position.x / density, down.position.y / density), down.position)
                val holdAt = down.position
                val timer: Job = scope.launch { delay(500); emit(g.hold(), holdAt) }
                var last = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    last = ch.position
                    emit(g.move(ch.position.x / density, ch.position.y / density), ch.position)
                    ch.consume()
                }
                timer.cancel()
                emit(g.up(), last)
            }
        })) {
            val c = cur
            if (crosshair && c != null) {
                // 22 dp ring with a 34 dp cross, outlined in black so it reads on any picture
                val x = maxWidth * c.first; val y = maxHeight * c.second
                Box(Modifier.offset(x - 17.dp, y - 17.dp).size(34.dp).drawBehind {
                    val p = ph.primary; val k = Color.Black
                    val mid = size.width / 2
                    drawLine(k, Offset(mid, 0f), Offset(mid, size.height), 4.dp.toPx())
                    drawLine(k, Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx())
                    drawCircle(k, 12.dp.toPx(), style = Stroke(4.dp.toPx()))
                    drawLine(p, Offset(mid, 0f), Offset(mid, size.height), 2.dp.toPx())
                    drawLine(p, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx())
                    drawCircle(p, 11.dp.toPx(), style = Stroke(2.dp.toPx()))
                })
            }
        }
    }
}

/** Focus mode, over everything: the video anchored left at full height, and the spare width
 *  (max(136 dp, screen − 16:9 of the height)) as ONE gamepad column: ✕ / STRM, the stick, L | R,
 *  and everything below them is the scroll pad. */
@Composable
fun FocusStage(track: VideoTrack) {
    val ph = LocalPh.current
    val state by Deck.state.collectAsState()
    BackHandler { StreamUi.focus.value = false }
    LaunchedEffect(Unit) { input("jwhere") }                 // pulls an off-screen cursor onto the streamed screen
    DisposableEffect(Unit) { onDispose { InputLink.send(buildJsonObject { put("t", "jup"); put("b", "left") }) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val strip = max(136.dp, maxWidth - maxHeight * 16f / 9f)
        val videoW = maxWidth - strip
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(videoW).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                VideoSurface(track, crosshair = true, Modifier.fillMaxHeight())
            }
            Column(Modifier.width(strip).fillMaxHeight().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PadButton("✕", Modifier.weight(1f), small = true, onPress = { StreamUi.focus.value = false })
                    val s = state.obj("stream").str("state")
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Label("STRM", 9.sp, when (s) { "live" -> ph.primary; "lost" -> ph.secondary; else -> ph.onVar.copy(alpha = 0.5f) }, spacing = 0.2f)
                    }
                }
                Stick(min(strip - 12.dp, 150.dp), Modifier.align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PadButton("L", Modifier.weight(1f), onPress = { input("jdown") { put("b", "left") } }, onRelease = { input("jup") { put("b", "left") } })
                    PadButton("R", Modifier.weight(1f), onPress = { input("jdown") { put("b", "right") } }, onRelease = { input("jup") { put("b", "right") } })
                }
                ScrollPad(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The stick: relative motion, linear past an 8 % deadzone, a quarter screen-width per second
 *  at full push, sent ~30 Hz while held; a haptic tick as it leaves the deadzone. */
@Composable
private fun Stick(size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val ph = LocalPh.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var knob by remember { mutableStateOf(Offset.Zero) }
    val aspect = 16f / 9f
    Box(modifier.size(size).clip(CircleShape)
        .background(Brush.radialGradient(listOf(lerp(Color.Black, ph.primary, 0.10f), Color.Black)))
        .border(1.dp, ph.primary.copy(alpha = 0.45f), CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val r = this.size.width / 2f
                val c = Offset(r, this.size.height / 2f)
                var j = Gamepad.deflect(down.position.x - c.x, down.position.y - c.y, r)
                var engaged = hypot(j.first, j.second) >= Gamepad.DEAD
                if (engaged) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                knob = Offset(j.first, j.second)
                val ticker = scope.launch {
                    var last = System.nanoTime()
                    while (isActive) {
                        delay(Gamepad.TICK_MS)
                        val now = System.nanoTime(); val dt = (now - last) / 1e9f; last = now
                        Gamepad.move(j.first, j.second, dt, aspect)?.let { (dx, dy) ->
                            input("jmove") { put("dfx", dx); put("dfy", dy) }
                        }
                    }
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    j = Gamepad.deflect(ch.position.x - c.x, ch.position.y - c.y, r)
                    val now = hypot(j.first, j.second) >= Gamepad.DEAD
                    if (now && !engaged) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    engaged = now
                    knob = Offset(j.first, j.second)
                    ch.consume()
                }
                ticker.cancel(); knob = Offset.Zero
            }
        }, contentAlignment = Alignment.Center) {
        val travel = size / 2 * Gamepad.KNOB_TRAVEL
        Box(Modifier.offset { IntOffset((knob.x * travel.toPx()).roundToInt(), (knob.y * travel.toPx()).roundToInt()) }
            .size(size * 0.4f).clip(CircleShape).background(lerp(Color.Black, ph.primary, 0.45f))
            .border(1.dp, ph.primary, CircleShape))
    }
}

/** .fbtn: press on touch-down, release on touch-up (a tap is a click, holding L while steering
 *  is a drag); lit while held. */
@Composable
private fun PadButton(text: String, modifier: Modifier, small: Boolean = false, onPress: () -> Unit, onRelease: () -> Unit = {}) {
    val ph = LocalPh.current
    val haptic = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(if (held) lerp(Color.Black, ph.primary, 0.35f) else Color.Black)
        .border(1.dp, ph.primary.copy(alpha = 0.45f), shape)
        .pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(); d.consume()
                held = true; haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onPress()
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == d.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                }
                held = false; onRelease()
            }
        }.padding(vertical = if (small) 5.dp else 8.dp), contentAlignment = Alignment.Center) {
        Mono(text, 13.sp, if (small) ph.onVar else ph.primary)
    }
}

/** The scroll pad: one wheel step per 18 dp of vertical drag; finger up = wheel down. */
@Composable
private fun ScrollPad(modifier: Modifier) {
    val ph = LocalPh.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape)
        .drawBehind {
            drawRoundRect(ph.primary.copy(alpha = 0.35f), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(); d.consume()
                var y = d.position.y; var acc = 0f
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == d.id } ?: break
                    if (!ch.pressed) break
                    acc += (ch.position.y - y) / density; y = ch.position.y
                    val (steps, rest) = Gamepad.scroll(acc); acc = rest
                    steps.forEach { s -> input("scroll") { put("dy", s) }; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    ch.consume()
                }
            }
        }, contentAlignment = Alignment.Center) {
        Mono("⇕", 18.sp, ph.primary.copy(alpha = 0.55f))
    }
}
