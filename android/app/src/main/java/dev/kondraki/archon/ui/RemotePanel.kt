package dev.kondraki.archon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
        // #156: the trackpad + L / M / R are the THUMB side; keys, combos and the docked mini
        // player go to the other side (the second thumb, or the phone on a stand)
        HandRow(Modifier.weight(1f).fillMaxWidth(),
            look = {
                Column(Modifier.width(THUMB_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyboardField(Modifier.fillMaxWidth())
                        listOf(listOf("esc" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓"),
                            listOf("space" to "Space", "end" to "End", "left" to "←", "right" to "→")).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (k, label) -> Key(label, Modifier.weight(1f)) { send("key") { put("name", k) } } }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Key("⌫", Modifier.weight(1f)) { send("key") { put("name", "backspace") } }
                            Key("⏎", Modifier.weight(1f)) { send("key") { put("name", "enter") } }
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
                    MiniPlayer()
                }
            },
            thumb = {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Trackpad(available, Modifier.weight(1f).fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("left" to "Left", "middle" to "Middle", "right" to "Right").forEach { (b, label) ->
                            Key(label, Modifier.weight(1f), tall = true) { send("click") { put("b", b) } }
                        }
                    }
                }
            })
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

/** The DIRECT-KEY strip (#156): tap it and the phone keyboard opens; every key goes straight
 *  to the rig and the strip never fills (for YouTube's j / k / i, voice push-to-talk, …).
 *  It holds one invisible character so Backspace has something to delete: losing it reads as
 *  ⌫ and it's put straight back. A password-type keyboard means no suggestions, so each key
 *  leaves at once instead of being held as part of a word. The last key flashes as feedback. */
@Composable
private fun KeyboardField(modifier: Modifier) {
    val ph = LocalPh.current
    val sentinel = "\u200B"
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(1))) }
    var last by remember { mutableStateOf("") }
    var lastAt by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(lastAt) { if (lastAt > 0) { delay(1500); last = "" } }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val t = v.text
            when {
                t.isEmpty() -> { send("key") { put("name", "backspace") }; last = "⌫"; lastAt = System.currentTimeMillis() }
                t.length > 1 -> {
                    val typed = t.replace(sentinel, "")
                    if (typed.isNotEmpty()) { send("text") { put("s", typed) }; last = typed.takeLast(1).let { if (it == " ") "␣" else it }; lastAt = System.currentTimeMillis() }
                }
            }
            value = TextFieldValue(sentinel, TextRange(1))           // never fills
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send("key") { put("name", "enter") }; last = "⏎"; lastAt = System.currentTimeMillis() }),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.height(40.dp).clip(shape).background(ph.low)
            .border(1.dp, if (focused) ph.primary else ph.outline, shape).then(if (focused) Modifier.boxGlow(ph.primary, 6.dp, 0.25f) else Modifier)
            .padding(horizontal = 12.dp),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono("⌨", 16.sp, ph.primary)
                Box(Modifier.weight(1f)) {
                    Box(Modifier.size(1.dp)) { inner() }                 // the real field: invisible
                    Mono(if (focused) "keys go straight to the rig" else "type on the rig", 13.sp, if (focused) ph.on else ph.onVar)
                }
                if (last.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono("last ", 11.sp, ph.onVar); Mono(last, 14.sp, ph.secondary)
                }
            }
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
