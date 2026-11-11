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

/** The Audio panel, 1:1 with deck.html #panel-audio: mutes, the now-playing hero, a copy of
 *  volume + transport (30%); output and input devices (the rest). */
@Composable
fun AudioPanel(state: JsonObject?) {
    val ph = LocalPh.current
    val a = state.obj("audio")
    val np = a.obj("now_playing")
    val scope = rememberCoroutineScope()
    fun post(path: String, body: JsonObject = buildJsonObject { }) { scope.launch { Deck.post(path, body) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("Audio")
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val leftW = maxWidth * 0.3f
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(leftW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuteButton("Mic", a.bool("mic_muted") == true, Modifier.weight(1f)) {
                            post("/audio/mute", buildJsonObject { put("target", "mic") })
                        }
                        MuteButton("Speaker", a.bool("sink_muted") == true, Modifier.weight(1f)) {
                            post("/audio/mute", buildJsonObject { put("target", "sink") })
                        }
                    }
                    NowPlaying(np, Modifier.weight(1f))
                    VolRow(a.num("volume")?.toInt(), a.bool("sink_muted") == true,
                        onMute = { post("/audio/mute", buildJsonObject { put("target", "sink") }) },
                        onSet = { v -> post("/audio/volume", buildJsonObject { put("pct", v) }) })
                    Transport(np.str("status") == "Playing") { act -> post("/media/$act") }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeviceList("Output device", a.list("sinks")) { id -> post("/audio/sink", buildJsonObject { put("id", id) }) }
                    DeviceList("Input device", a.list("sources")) { id -> post("/audio/source", buildJsonObject { put("id", id) }) }
                }
            }
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
