package dev.kondraki.archon.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Heat
import dev.kondraki.archon.data.Link
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.cpuLine
import dev.kondraki.archon.data.gpuLine
import dev.kondraki.archon.data.heat
import dev.kondraki.archon.data.micLine
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The V3 cockpit shell, 1:1: band on top; the centre (a summoned panel) and the rail
 *  (volume, transport, dock) below. Panels not ported yet say so instead of pretending. */
@Composable
fun Shell(state: JsonObject?, link: Link) {
    val ph = LocalPh.current
    var panel by rememberSaveable { mutableStateOf<String?>(null) }      // null = home
    val track by dev.kondraki.archon.stream.StreamEngine.video.collectAsState()
    val focus by StreamUi.focus
    LaunchedEffect(track) { if (track == null) StreamUi.focus.value = false }     // no video, no focus mode
    val t = track
    if (focus && t != null) { FocusStage(t); return }
    Column(Modifier.fillMaxSize().background(ph.bg)) {
        Band(state, link)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val railW = max(150.dp, maxWidth * 0.2f)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                    when (val p = panel) {
                        null -> Home(state)
                        "audio" -> AudioPanel(state)
                        "stream" -> StreamPanel(state)
                        "modes" -> ModesPanel()
                        "voice" -> VoicePanel()
                        "remote" -> RemotePanel()
                        "system" -> SystemPanel(state) { panel = "more" }
                        "config" -> ConfigPanel { panel = "more" }
                        "files" -> FilesPanel(state) { panel = "more" }
                        "more" -> AboutPanel { panel = it }   // its launchers open Files / Config / System
                        else -> NotPorted(p) { panel = it }
                    }
                }
                Rail(state, panel, Modifier.width(railW).fillMaxHeight()) { panel = if (panel == it) null else it }   // the lit row toggles home
            }
        }
    }
}

private val PANELS = listOf("audio" to "Audio", "stream" to "Stream", "voice" to "Voice", "modes" to "Modes",
    "site19" to "Site-19", "remote" to "Remote")
private val PHASE = mapOf("site19" to 3)
private val SUB = mapOf("files" to "Files", "config" to "Config", "system" to "System")   // under More (PANEL_PARENT)

@Composable
private fun Band(state: JsonObject?, link: Link) {
    val ph = LocalPh.current
    val t = state.obj("telemetry")
    val a = state.obj("audio")
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .background(lerp(ph.bg, ph.surface, 0.18f))
            .drawBehind { drawLine(ph.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(Deck.host, 13.sp, ph.primary, spacing = 0.22f, glow = true, bold = true)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            t.obj("gpu")?.let { Readout(gpuLine(it), heat(it.num("temp") ?: 0.0, it.num("util") ?: 0.0)) }
            t.obj("cpu")?.let { Readout(cpuLine(it), heat(it.num("temp") ?: 0.0, it.num("util") ?: 0.0)) }
            a?.let { Readout(micLine(it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StreamChip(state.obj("stream").str("state"))
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (link == Link.ONLINE) ph.primary else ph.onVar.copy(alpha = 0.4f))
                .then(if (link == Link.ONLINE) Modifier.glow(ph.primary, 4.dp) else Modifier))
            Battery()
            Clock()
        }
    }
}

/** A boxed band readout that colours itself by its worst signal (amber warn, red alert). */
@Composable
private fun Readout(text: String, h: Heat = Heat.OK) {
    val ph = LocalPh.current
    val c = when (h) { Heat.ALERT -> DANGER; Heat.WARN -> ph.secondary; Heat.OK -> ph.on }
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.clip(shape).background(ph.primary.copy(alpha = 0.04f))
        .border(1.dp, if (h == Heat.OK) ph.primary.copy(alpha = 0.22f) else c.copy(alpha = 0.5f), shape)
        .padding(horizontal = 7.dp, vertical = 1.dp)) { Mono(text, 11.sp, c) }
}

/** STRM: lit while a stream session is live on the rig, amber while its video is lost. */
@Composable
private fun StreamChip(s: String?) {
    val ph = LocalPh.current
    val c = when (s) { "live" -> ph.primary; "lost" -> ph.secondary; else -> ph.onVar.copy(alpha = 0.45f) }
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.clip(shape).border(1.dp, if (s == "live" || s == "lost") c else ph.line, shape)
        .padding(horizontal = 5.dp, vertical = 1.dp)) { Label("STRM", 10.sp, c, spacing = 0.18f, glow = s == "live") }
}

/** The phone's own battery (the PWA's #st-bat). */
@Composable
private fun Battery() {
    val ctx = LocalContext.current
    var pct by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            pct = if (level >= 0 && scale > 0) level * 100 / scale else null
            delay(60_000)
        }
    }
    pct?.let { Readout("$it%") }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    Readout(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
}

@Composable
private fun Rail(state: JsonObject?, panel: String?, modifier: Modifier, onPanel: (String) -> Unit) {
    val ph = LocalPh.current
    val a = state.obj("audio")
    val scope = rememberCoroutineScope()
    Column(
        modifier.background(lerp(ph.bg, ph.surface, 0.30f))
            .drawBehind { drawLine(ph.line, Offset(0.5f, 0f), Offset(0.5f, size.height), 1f) }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VolRow(a.num("volume")?.toInt(), a.bool("sink_muted") == true,
            onMute = { scope.launch { Deck.post("/audio/mute", buildJsonObject { put("target", "sink") }) } },
            onSet = { v -> scope.launch { Deck.post("/audio/volume", buildJsonObject { put("pct", v) }) } })
        Transport(a.obj("now_playing").str("status") == "Playing") { act -> scope.launch { Deck.post("/media/$act") } }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ph.line))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PANELS.forEach { (key, label) -> DockRow(label, panel == key, muted = key in PHASE) { onPanel(key) } }
            Spacer(Modifier.weight(1f))
            DockRow("More …", panel == "more" || panel in SUB, muted = true) { onPanel("more") }
        }
    }
}

@Composable
private fun NotPorted(key: String, onBack: (String) -> Unit) {
    val ph = LocalPh.current
    val name = PANELS.firstOrNull { it.first == key }?.second ?: SUB[key] ?: "More"
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (key in SUB) Label("‹ More", 10.sp, ph.onVar, spacing = 0.14f, modifier = Modifier.tap { onBack("more") })
            PanelHead(name)
        }
        Mono("Not in Archon yet: arrives in phase ${PHASE[key] ?: 3}. The PWA still has it.", 13.sp, ph.onVar, maxLines = 3)
    }
}

@Suppress("unused") private val keepColor = Color.Unspecified
