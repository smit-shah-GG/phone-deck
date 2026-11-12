package dev.kondraki.archon.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handedness (#156, locked 2026-11-12): every screen has a LOOK side (video, art, lists, trace)
 * and a THUMB side (its primary actions + the docked mini player), and the slim rail sits on the
 * thumb edge. Right-handed puts the thumb side on the right; the setting swaps them. Per phone
 * (local prefs), default right-handed.
 */
object Hand {
    val right = mutableStateOf(true)
    fun load(ctx: Context) { right.value = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("right_handed", true) }
    fun set(ctx: Context, r: Boolean) {
        right.value = r
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("right_handed", r).apply()
    }
}

/** The thumb column's width: exactly the docked mini player plus the panel's padding. */
val THUMB_W: Dp = 300.dp
val MINI_H: Dp = 58.dp

/** [look] and [thumb] side by side, the thumb on the dominant side. */
@Composable
fun HandRow(modifier: Modifier = Modifier, gap: Dp = 12.dp, align: Alignment.Vertical = Alignment.Top,
            look: @Composable RowScope.() -> Unit, thumb: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = align) {
        if (Hand.right.value) { look(); thumb() } else { thumb(); look() }
    }
}

/**
 * The docked mini player (canvas SlimScreens, S2): prev / play-pause / next, what's playing, and
 * the rig's volume, always in the same thumb corner. Reads the live state itself, so any screen
 * can dock it. Not on Audio (that screen is the player) or in focus mode / PiP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val ph = LocalPh.current
    val state by Deck.state.collectAsState()
    val a = state.obj("audio")
    val np = a.obj("now_playing")
    val playing = np.str("status") == "Playing"
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.width(THUMB_W).height(MINI_H).clip(shape).background(lerp(ph.bg, ph.surface, 0.5f))
        .border(1.dp, ph.primary.copy(alpha = 0.7f), shape).boxGlow(ph.primary, 8.dp, 0.15f).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("previous" to "◁◁", "play-pause" to if (playing) "❚❚" else "▶", "next" to "▷▷").forEach { (act, g) ->
            val pp = act == "play-pause"
            val ks = RoundedCornerShape(7.dp)
            Box(Modifier.width(if (pp) 46.dp else 40.dp).fillMaxHeight().clip(ks)
                .background(if (pp) lerp(Color.Black, ph.primary, 0.88f) else ph.high)
                .border(1.dp, if (pp) ph.primary else ph.outline, ks)
                .tap { scope.launch { Deck.post("/media/$act") } }, contentAlignment = Alignment.Center) {
                Mono(g, 15.sp, if (pp) ph.onPrimary else ph.on)
            }
        }
        Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val title = np.str("track")?.takeIf { it.isNotBlank() } ?: np.str("title").orEmpty()
            androidx.compose.material3.Text(title.ifEmpty { "nothing playing" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = CssLine + androidx.compose.ui.text.TextStyle(fontFamily = Plex, fontSize = 10.sp, color = ph.onVar))
            // the rig's volume: drag, posted once on release (like the old rail's slider)
            var dragging by remember { mutableStateOf(false) }
            var local by remember { mutableFloatStateOf(0f) }
            val vol = a.num("volume")?.toFloat() ?: 0f
            val muted = a.bool("sink_muted") == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Slider(value = if (dragging) local else vol, valueRange = 0f..150f,
                    onValueChange = { dragging = true; local = it },
                    onValueChangeFinished = { val v = local.toInt(); dragging = false; scope.launch { Deck.post("/audio/volume", buildJsonObject { put("pct", v) }) } },
                    modifier = Modifier.weight(1f).height(20.dp),
                    thumb = { Box(Modifier.size(14.dp).glow(ph.primary, 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (muted) DANGER else ph.primary)) },
                    track = { Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ph.high)) })
                Mono(if (muted) "×" else (if (dragging) local else vol).toInt().toString(), 9.sp, if (muted) DANGER else ph.onVar,
                    modifier = Modifier.width(20.dp))
            }
        }
    }
}

/** The slim rail (R2): icons only, on the thumb edge; More at its foot. */
@Composable
fun SlimRail(panel: String?, more: Boolean, onPanel: (String) -> Unit, modifier: Modifier = Modifier) {
    val ph = LocalPh.current
    Column(modifier.width(58.dp).fillMaxHeight().background(lerp(ph.bg, ph.surface, 0.30f)).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        RAIL.forEach { (key, glyph) -> RailIcon(key, glyph, panel == key) { onPanel(key) } }
        Spacer(Modifier.weight(1f))
        RailIcon("more", "⋯", more) { onPanel("more") }
    }
}

private val RAIL = listOf("audio" to "♪", "stream" to "▶", "voice" to "◉", "modes" to "◇", "site19" to "emblem", "remote" to "✥")

@Composable
private fun RailIcon(key: String, glyph: String, on: Boolean, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(if (on) ph.active else ph.high)
        .border(1.dp, if (on) ph.primary else ph.line, shape).then(if (on) Modifier.boxGlow(ph.primary, 6.dp, 0.25f) else Modifier)
        .tap(onClick), contentAlignment = Alignment.Center) {
        if (glyph == "emblem") Image(painterResource(R.drawable.emblem), "Site-19", Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (on) ph.primary else ph.on))
        else Mono(glyph, 18.sp, if (on) ph.primary else ph.on)
    }
}
