package dev.kondraki.archon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The deck's components, ported from deck.html / theme.css. Colours always come from the
// live palette (LocalPh); sizes are the PWA's CSS px, which are dp on the phone.

val LocalPh = staticCompositionLocalOf { Phosphor() }

val DANGER = Color(0xFFFF5252)                                // theme.css's true-danger red

val Phosphor.line get() = primary.copy(alpha = 0.16f)      // --sh-line
val Phosphor.line2 get() = primary.copy(alpha = 0.30f)     // --sh-line2
val Phosphor.active get() = lerp(Color.Black, primary, 0.18f)  // --sh-active

/** Chakra Petch structural label (dock rows, panel heads, wordmark). */
@Composable
fun Label(text: String, size: TextUnit, color: Color, spacing: Float = 0.12f, glow: Boolean = false, bold: Boolean = false, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, maxLines = 1, overflow = TextOverflow.Clip, style = TextStyle(
        fontFamily = Chakra, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        fontSize = size, letterSpacing = size * spacing, color = color,
        shadow = if (glow) Shadow(color.copy(alpha = 0.45f), Offset.Zero, 14f) else null,
    ))
}

/** Plex Mono data text. */
@Composable
fun Mono(text: String, size: TextUnit = 12.sp, color: Color = LocalPh.current.on, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Plex, fontSize = size, color = color))
}

/** A tap with a light haptic tick: the native upgrade the PWA couldn't do. */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return clickable { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
}

/** .phead h2: the panel title. */
@Composable
fun PanelHead(title: String) {
    val ph = LocalPh.current
    Label(title, 15.sp, ph.primary, spacing = 0.2f, glow = true)
}

/** Mic / Speaker: the big mute buttons. Red when muted, like [data-mute].muted. */
@Composable
fun MuteButton(label: String, muted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.clip(shape)
            .background(if (muted) lerp(Color.Black, DANGER, 0.22f) else ph.high)
            .border(1.dp, if (muted) DANGER else ph.line, shape)
            .tap(onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Mono(label, 14.sp, if (muted) DANGER else ph.on) }
}

/** .dev: a device row: dot + name, the active one glows. */
@Composable
fun DevRow(name: String, on: Boolean, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (on) lerp(Color.Black, ph.primary, 0.08f) else ph.low)
            .border(1.dp, if (on) ph.primary else ph.outline, shape)
            .tap(onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) ph.primary else ph.outline)
            .then(if (on) Modifier.glow(ph.primary, 8.dp) else Modifier))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
            fontFamily = Plex, fontSize = 13.sp, color = if (on) ph.primary else ph.onVar,
            shadow = if (on) Shadow(ph.primary.copy(alpha = 0.4f), Offset.Zero, 12f) else null))
    }
}

/** .vol: mute glyph + a fat 0–150 slider + the percentage. The slider holds its own value
 *  while dragged (app.js volDragging) and posts once, on release. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolRow(volume: Int?, muted: Boolean, onMute: () -> Unit, onSet: (Int) -> Unit) {
    val ph = LocalPh.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) local else (volume ?: 0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.size(34.dp).clip(shape)
                .background(if (muted) lerp(Color.Black, DANGER, 0.22f) else ph.high)
                .border(1.dp, if (muted) DANGER else ph.line, shape).tap(onMute),
            contentAlignment = Alignment.Center,
        ) { Mono("◼", 15.sp, if (muted) DANGER else ph.on) }
        // The PWA's range input, not Material's: a plain 8dp track with a hairline frame and a
        // round glowing 22dp thumb (deck.html .vol input[type=range]).
        Slider(
            value = shown, valueRange = 0f..150f,
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { onSet(local.toInt()); dragging = false },
            modifier = Modifier.weight(1f).height(26.dp),
            thumb = {
                Box(Modifier.size(22.dp).glow(ph.primary, 6.dp).clip(CircleShape).background(ph.primary))
            },
            track = {
                val shape = RoundedCornerShape(5.dp)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(ph.high).border(1.dp, ph.line, shape))
            },
        )
        Text(if (volume == null && !dragging) "—" else shown.toInt().toString(), modifier = Modifier.width(28.dp),
            style = TextStyle(fontFamily = Chakra, fontSize = 10.sp, color = ph.onVar), maxLines = 1)
    }
}

/** .trans: previous / play-pause / next on the rig's player. */
@Composable
fun Transport(playing: Boolean, onMedia: (String) -> Unit) {
    val ph = LocalPh.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("previous" to "◁◁", "play-pause" to if (playing) "❚❚" else "▶", "next" to "▷▷").forEach { (act, glyph) ->
            val pp = act == "play-pause"
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(ph.high)
                    .then(if (pp) Modifier.innerGlow(ph.primary) else Modifier)
                    .tap { onMedia(act) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Mono(glyph, 18.sp, if (pp) ph.primary else ph.on) }
        }
    }
}

/** .drow: a dock row; the open panel's row glows. */
@Composable
fun DockRow(label: String, on: Boolean, muted: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (on) ph.active else ph.high)
            .border(1.dp, if (on) ph.primary else ph.line, shape)
            .tap(onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mono("›", 10.sp, (if (on) ph.primary else ph.on).copy(alpha = 0.4f))
        Label(label, 11.sp, when { on -> ph.primary; muted -> ph.onVar; else -> ph.on }, glow = on)
    }
}

/** A soft glow around a small shape (box-shadow 0 0 Npx colour). */
fun Modifier.glow(color: Color, radius: Dp): Modifier = drawBehind {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent),
        center = center, radius = size.minDimension / 2 + radius.toPx()), radius = size.minDimension / 2 + radius.toPx())
}

/** inset 0 0 12px primary@12%: the play button's faint inner glow. */
fun Modifier.innerGlow(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(Brush.radialGradient(listOf(Color.Transparent, color.copy(alpha = 0.14f)),
        center = center, radius = size.maxDimension * 0.7f))
}

/** The CRT overlay the PWA draws with body::before/::after: scanlines + a vignette. Off with
 *  the System → Theme CRT switch (palette.crt). Draw-only, so touches pass straight through. */
fun Modifier.crt(on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawContent()
    val line = Color.Black.copy(alpha = 0.10f)
    var y = 2f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 3f
    }
    drawRect(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f),
        center = center, radius = size.maxDimension * 0.62f))
}
