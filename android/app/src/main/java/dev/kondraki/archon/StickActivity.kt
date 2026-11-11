package dev.kondraki.archon

import android.graphics.Color as AColor
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.InputLink
import androidx.compose.ui.text.font.FontFamily
import dev.kondraki.archon.widget.RcGeom
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The remote's stick, awake (canvas RemoteControl board v2, #143). A widget only takes taps, so
 * tapping the stick opens this: a small window placed exactly over the stick and L/R (from the
 * tap's source bounds), drawing them lit, and a real trackpad underneath: drag = move with the
 * Remote panel's acceleration, tap = left click, two-finger tap = right, two-finger drag =
 * scroll, hold L + drag = drag-select, haptics.
 *
 * It ends the moment a touch lands anywhere else (FLAG_WATCH_OUTSIDE_TOUCH; that touch is never
 * read as stick input and still reaches whatever is under it), or when the screen changes under
 * it (pause: another app, Home, the shade, screen off), or after 30 s untouched.
 */
class StickActivity : ComponentActivity() {
    private var idle: Job? = null
    private var leftDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Deck.session.token.value == null) { finish(); return }
        val d = resources.displayMetrics.density
        val dm = resources.displayMetrics
        val b = intent.sourceBounds ?: Rect(dm.widthPixels / 2 - (150 * d).toInt(), dm.heightPixels / 2 - (180 * d).toInt(),
            dm.widthPixels / 2 + (150 * d).toInt(), dm.heightPixels / 2 + (120 * d).toInt())
        val extra = ((RcGeom.LR_GAP_DP + RcGeom.LR_H_DP) * d).roundToInt()
        android.util.Log.i("archon", "stick: sourceBounds=${intent.sourceBounds} → window $b")
        window.setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left; y = b.top; width = b.width(); height = b.height() + extra
            dimAmount = 0f; windowAnimations = 0
        }
        poke()
        setContent {
            val dd = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(dd.density, fontScale = 1f)) {
                Stick(stickHeightPx = b.height(), onTouch = ::poke, onLeft = ::left)
            }
        }
    }

    /** Any touch that isn't ours ends the layer; it is never turned into stick input. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { finish(); return true }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() { super.onPause(); finish() }

    override fun onDestroy() {
        if (leftDown) send("jup") { put("b", "left") }
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun poke() {
        idle?.cancel()
        idle = lifecycleScope.launch { delay(30_000); finish() }
    }

    private fun left(down: Boolean) {
        leftDown = down
        send(if (down) "jdown" else "jup") { put("b", "left") }
    }
}

internal fun send(t: String, body: JsonObjectBuilder.() -> Unit = {}) =
    InputLink.send(buildJsonObject { put("t", t); body() })

private val RED = Color(0xFFFA4242)
private val KEY = Color(0xFF1C1414)
private val KEY_EDGE = Color(0xFF2E1A1A)

@Composable
internal fun Stick(stickHeightPx: Int, onTouch: () -> Unit, onLeft: (Boolean) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current.density
    var cap by remember { mutableStateOf(Offset.Zero) }          // where the thumb pulls the cap (px)
    var lHeld by remember { mutableStateOf(false) }
    var rHeld by remember { mutableStateOf(false) }
    val stickH = with(LocalDensity.current) { stickHeightPx.toDp() }
    Column(Modifier.fillMaxSize()) {
        // the stick box: the same box the widget clicked, a real trackpad
        Box(Modifier.fillMaxWidth().height(stickH).pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(); onTouch()
                val start = System.currentTimeMillis()
                var moved = false; var wasTwo = false; var scrollY: Float? = null
                val origin = first.position
                val pts = mutableMapOf<PointerId, Offset>(first.id to first.position)
                while (true) {
                    val ev = awaitPointerEvent(); onTouch()
                    ev.changes.forEach { ch ->
                        if (ch.pressed) {
                            if (ch.id !in pts) {
                                pts[ch.id] = ch.position
                                if (pts.size == 2) { wasTwo = true; scrollY = pts.values.map { it.y }.average().toFloat() }
                            }
                        } else pts.remove(ch.id)
                    }
                    if (pts.isEmpty()) break
                    if (pts.size >= 2) {
                        ev.changes.filter { it.pressed && it.id in pts }.forEach { pts[it.id] = it.position }
                        val y = pts.values.map { it.y }.average().toFloat()
                        scrollY?.let { s ->
                            val dy = (y - s) / density
                            if (abs(dy) > 3) {
                                send("scroll") { put("dy", if (dy < 0) 1 else -1) }; scrollY = y
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    } else {
                        val ch = ev.changes.firstOrNull { it.pressed && it.id in pts } ?: continue
                        val prev = pts[ch.id]!!
                        val dx = (ch.position.x - prev.x) / density; val dy = (ch.position.y - prev.y) / density
                        pts[ch.id] = ch.position
                        if (abs(dx) + abs(dy) > 2) moved = true
                        // the Remote panel's trackpad: gain 3.0 × (1 + speed × 0.06)
                        val f = 3.0f * (1 + hypot(dx, dy) * 0.06f)
                        val mx = (dx * f).roundToInt(); val my = (dy * f).roundToInt()
                        if (mx != 0 || my != 0) send("move") { put("dx", mx); put("dy", my) }
                        cap = ch.position - origin                       // the cap leans with the thumb
                    }
                    ev.changes.forEach { it.consume() }
                }
                cap = Offset.Zero                                         // springs back
                val quick = System.currentTimeMillis() - start < (if (wasTwo) 300 else 250)
                if (!moved && quick) {
                    send("click") { put("b", if (wasTwo) "right" else "left") }
                    view.performHapticFeedback(if (wasTwo) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val dia = min(size.width, size.height)
                val c = center
                val r = dia / 2
                // the dish, opaque: it covers the widget's sleeping cap
                drawCircle(Brush.radialGradient(listOf(Color(0xFF0A0606), Color(0xFF160E0E), Color(0xFF1F1515)),
                    center = Offset(c.x, c.y - r * 0.1f), radius = r), r, c)
                drawCircle(Color(0xFF2A1A1A), r - 22.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                // the lit ring, its glow spreading INWARD only: each stroke's outer edge stays on the
                // ring, so nothing spills past the box and gets clipped
                for (i in 4 downTo 1) {
                    val w = (2 + i * 3).dp.toPx()
                    drawCircle(RED.copy(alpha = 0.07f * i), r - w / 2, c, style = Stroke(w))
                }
                drawCircle(RED, r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
            // the cap: leans toward the thumb, inside the well
            val capPx = RcGeom.CAP_DP * density
            Box(Modifier.align(Alignment.Center).offset {
                val max = 56f * density
                val len = hypot(cap.x, cap.y)
                val k = if (len > max) max / len else 1f
                IntOffset((cap.x * k).roundToInt(), (cap.y * k).roundToInt())
            }.size(RcGeom.CAP_DP.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3A2828), Color(0xFF241818), Color(0xFF160E0E)),
                    center = Offset(capPx * 0.4f, capPx * 0.35f), radius = capPx * 0.7f))
                .border(1.dp, Color(0xFF4A2C2C), CircleShape), contentAlignment = Alignment.Center) {
                Text("LIVE", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = RED))
            }
        }
        // L · R, drawn over the widget's own; hold L + drag the stick = drag-select
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Btn("L", lHeld, Modifier.weight(1f)) { down ->
                lHeld = down; onTouch(); onLeft(down)
                if (down) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            Btn("R", rHeld, Modifier.weight(1f)) { down ->
                rHeld = down; onTouch()
                send(if (down) "jdown" else "jup") { put("b", "right") }
                if (down) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }
}

@Composable
private fun Btn(label: String, held: Boolean, modifier: Modifier, onPress: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.height(RcGeom.LR_H_DP.dp).clip(shape).background(if (held) Color(0xFF3A1414) else KEY)
        .border(1.dp, if (held) RED else KEY_EDGE, shape)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume(); onPress(true)
                while (true) { val ev = awaitPointerEvent(); ev.changes.forEach { it.consume() }; if (ev.changes.none { it.pressed }) break }
                onPress(false)
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = if (held) RED else Color(0xFFEDC3C3)))
    }
}
