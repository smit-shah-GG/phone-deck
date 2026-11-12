package dev.kondraki.archon.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.nowPlayingLine
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle

/** The Audio panel (#156): the LOOK side has what's playing, the rig's play controls and volume
 *  (this screen IS the player, so it docks no mini player) and the rarely-changed input device;
 *  the THUMB side has the two mutes and the output device you actually switch. */
@Composable
fun AudioPanel(state: JsonObject?) {
    val ph = LocalPh.current
    val a = state.obj("audio")
    val np = a.obj("now_playing")
    val scope = rememberCoroutineScope()
    fun post(path: String, body: JsonObject = buildJsonObject { }) { scope.launch { Deck.post(path, body) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("Audio")
        HandRow(Modifier.weight(1f).fillMaxWidth(), gap = 16.dp,
            look = {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NowPlayingWide(np)
                    Transport(np.str("status") == "Playing") { act -> post("/media/$act") }
                    VolRow(a.num("volume")?.toInt(), a.bool("sink_muted") == true,
                        onMute = { post("/audio/mute", buildJsonObject { put("target", "sink") }) },
                        onSet = { v -> post("/audio/volume", buildJsonObject { put("pct", v) }) })
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        DeviceList("Input device", a.list("sources")) { id -> post("/audio/source", buildJsonObject { put("id", id) }) }
                    }
                }
            },
            thumb = {
                Column(Modifier.width(THUMB_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuteButton("Mic", a.bool("mic_muted") == true, Modifier.weight(1f).height(64.dp)) {
                            post("/audio/mute", buildJsonObject { put("target", "mic") })
                        }
                        MuteButton("Speaker", a.bool("sink_muted") == true, Modifier.weight(1f).height(64.dp)) {
                            post("/audio/mute", buildJsonObject { put("target", "sink") })
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        DeviceList("Output device", a.list("sinks")) { id -> post("/audio/sink", buildJsonObject { put("id", id) }) }
                    }
                }
            })
    }
}

/** What's playing, art beside the words (the look side's head). */
@Composable
private fun NowPlayingWide(np: JsonObject?) {
    val ph = LocalPh.current
    val key = np.str("art_key").orEmpty()
    var art by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) { art = if (key.isEmpty()) null else Deck.art(key) }   // only refetched per track
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ph.surface, ph.low)))
        .cornerTicks(ph.primary.copy(alpha = 0.55f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val bmp = art
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(120.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.size(120.dp).border(1.dp, ph.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Mono("NO SIGNAL", 10.sp, ph.onVar.copy(alpha = 0.7f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("NOW PLAYING", 10.sp, ph.secondary, spacing = 0.2f)
            Text(np.str("track")?.takeIf { it.isNotBlank() } ?: np.str("title").orEmpty().ifEmpty { "—" }, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = CssLine + TextStyle(fontFamily = Plex, fontSize = 15.sp, color = ph.on))
            val sub = listOfNotNull(np.str("artist")?.takeIf { it.isNotBlank() }, np.str("player")?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotEmpty()) Mono(sub, 12.sp, ph.onVar)
            Mono(when (np.str("status")) { "Playing" -> "▶ playing"; "Paused" -> "‖ paused"; else -> "" }, 11.sp, ph.onVar.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun NowPlaying(np: JsonObject?, modifier: Modifier) {
    val ph = LocalPh.current
    val key = np.str("art_key").orEmpty()
    var art by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) { art = if (key.isEmpty()) null else Deck.art(key) }   // only refetched per track
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ph.surface, ph.low)))
        .cornerTicks(ph.primary.copy(alpha = 0.55f)).padding(12.dp)) {
        val side = min(160.dp, min(maxWidth, maxHeight - 28.dp))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            val bmp = art
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.size(side).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(side).border(1.dp, ph.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Mono("NO SIGNAL", 10.sp, ph.onVar.copy(alpha = 0.7f))
                }
            }
            Text(nowPlayingLine(np), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.onVar))
        }
    }
}

@Composable
private fun DeviceList(title: String, devices: List<JsonObject>, onPick: (Int) -> Unit) {
    val ph = LocalPh.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CardTitle(title)
        devices.forEach { d ->
            DevRow(d.str("name") ?: "?", d.bool("active") == true) { d.num("id")?.toInt()?.let(onPick) }
        }
    }
}
