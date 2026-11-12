package dev.kondraki.archon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.InputLink
import dev.kondraki.archon.data.bool
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private fun send(t: String, body: JsonObjectBuilder.() -> Unit = {}) =
    InputLink.send(buildJsonObject { put("t", t); body() })

/** Remote, 1:1 with deck.html #panel-remote: the trackpad + Left/Middle/Right (left), the
 *  keyboard field, keys and combos (right), over /ws/input with the hid vocabulary. */
@Composable
fun RemotePanel() {
    val ph = LocalPh.current
    var available by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { available = Deck.getJson("/input/available")?.bool("ok") != false }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("Remote")
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Trackpad")
                Trackpad(available, Modifier.weight(1f).fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("left" to "Left", "middle" to "Middle", "right" to "Right").forEach { (b, label) ->
                        Key(label, Modifier.weight(1f), tall = true) { send("click") { put("b", b) } }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Keyboard")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyboardField(Modifier.weight(1f))
                    Key("⌫", wide = true) { send("key") { put("name", "backspace") } }
                    Key("⏎", wide = true) { send("key") { put("name", "enter") } }
                }
                // Space replaced Home (#152: push-to-talk for voice mode on the rig)
                listOf(listOf("esc" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓"),
                    listOf("space" to "Space", "end" to "End", "left" to "←", "right" to "→")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (k, label) -> Key(label, Modifier.weight(1f)) { send("key") { put("name", k) } } }
                    }
                }
                listOf(listOf("super" to "Super", "alt,tab" to "Alt⇥", "ctrl,w" to "^W"),
                    listOf("ctrl,c" to "^C", "ctrl,v" to "^V", "ctrl,shift,c" to "^⇧C")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (combo, label) ->
                            Key(label, Modifier.weight(1f), combo = true) {
                                send("combo") { putJsonArray("keys") { combo.split(',').forEach { add(it) } } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * app.js initRemote(), 1:1: one finger moves with acceleration (gain 3.0 × (1 + speed × 0.06));
 * a quick tap (< 250 ms, no movement) clicks left; a second finger makes it a two-finger
 * gesture: dragging scrolls (one step per 3 dp), a quick two-finger tap (< 300 ms) clicks right.
 */
@Composable
private fun Trackpad(available: Boolean, modifier: Modifier) {
    val ph = LocalPh.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(ph.low)
        .drawBehind {                                          // the input grid every 22 dp
            val step = 22.dp.toPx(); val c = ph.primary.copy(alpha = 0.06f)
            var x = step; while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step; while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, ph.primary.copy(alpha = 0.08f)), center = center, radius = size.maxDimension * 0.7f))
        }
        .border(1.dp, ph.primary, shape)
        .cornerTicks(ph.primary.copy(alpha = 0.55f))
        .pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown()
                val start = System.currentTimeMillis()
                var moved = false; var wasTwo = false; var scrollY: Float? = null
                val pts = mutableMapOf<PointerId, Offset>(first.id to first.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { ch ->
                        if (ch.pressed) {
                            if (ch.id !in pts) {                         // another finger landed
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
                            val d = (y - s) / density
                            if (abs(d) > 3) { send("scroll") { put("dy", if (d < 0) 1 else -1) }; scrollY = y }
                        }
                    } else {
                        val ch = ev.changes.firstOrNull { it.pressed && it.id in pts } ?: continue
                        val prev = pts[ch.id]!!
                        val dx = (ch.position.x - prev.x) / density; val dy = (ch.position.y - prev.y) / density
                        pts[ch.id] = ch.position
                        if (abs(dx) + abs(dy) > 2) moved = true
                        val f = 3.0f * (1 + hypot(dx, dy) * 0.06f)
                        val mx = (dx * f).roundToInt(); val my = (dy * f).roundToInt()
                        if (mx != 0 || my != 0) send("move") { put("dx", mx); put("dy", my) }
                    }
                    ev.changes.forEach { it.consume() }
                }
                val quick = System.currentTimeMillis() - start < (if (wasTwo) 300 else 250)
                if (!moved && quick) {
                    send("click") { put("b", if (wasTwo) "right" else "left") }
                    haptic.performHapticFeedback(if (wasTwo) HapticFeedbackType.LongPress else HapticFeedbackType.ContextClick)
                }
            }
        }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(if (available) "Trackpad — drag to move · tap = click · two-finger tap = right · two-finger drag = scroll"
             else "Remote input unavailable (no /dev/uinput access)", Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center,
            style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.onVar))
    }
}

/** The soft-keyboard field: diffs what you type and streams the added text / backspaces
 *  (app.js #kbd). Resets past 200 characters; the keyboard's Send key sends Enter. */
@Composable
private fun KeyboardField(modifier: Modifier) {
    val ph = LocalPh.current
    var value by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = { v ->
            if (v.length > value.length && v.startsWith(value)) send("text") { put("s", v.substring(value.length)) }
            else if (v.length < value.length) repeat(value.length - v.length) { send("key") { put("name", "backspace") } }
            else if (v != value) send("text") { put("s", v.takeLast(1)) }
            value = if (v.length > 200) "" else v
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send("key") { put("name", "enter") } }),
        textStyle = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = ph.primary,
            shadow = Shadow(ph.primary.copy(alpha = 0.3f), Offset.Zero, 8f)),
        cursorBrush = SolidColor(ph.primary),
        modifier = modifier.clip(shape).background(ph.low).border(1.dp, ph.outline, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box { if (value.isEmpty()) Mono("type here…", 14.sp, ph.onVar.copy(alpha = 0.6f)); inner() }
        },
    )
}

/** A Remote key: .rkey (pale) or .rcombo (amber); a click tick on press. */
@Composable
private fun Key(label: String, modifier: Modifier = Modifier, combo: Boolean = false, tall: Boolean = false, wide: Boolean = false, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(ph.high)
        .border(1.dp, if (combo) ph.secondary.copy(alpha = 0.35f) else ph.outline, shape)
        .tap(onClick).padding(horizontal = if (wide) 12.dp else 4.dp, vertical = if (tall) 12.dp else 8.dp),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(label, maxLines = 1, textAlign = TextAlign.Center,
            style = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = if (combo) ph.secondary else ph.on))
    }
}
