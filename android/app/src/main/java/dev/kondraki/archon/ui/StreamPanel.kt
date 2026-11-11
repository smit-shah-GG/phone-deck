package dev.kondraki.archon.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import dev.kondraki.archon.stream.Phase
import dev.kondraki.archon.stream.StreamEngine
import dev.kondraki.archon.stream.StreamOpts
import dev.kondraki.archon.stream.StreamService
import kotlinx.serialization.json.JsonObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Remembered stream options (#122 decision 3). */
object StreamPrefs {
    fun load(ctx: Context): StreamOpts = runCatching {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("stream", null)
            ?.let { Deck.json.decodeFromString(StreamOpts.serializer(), it) }
    }.getOrNull() ?: StreamOpts()

    fun save(ctx: Context, o: StreamOpts) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("stream", Deck.json.encodeToString(StreamOpts.serializer(), o)).apply()
    }
}

/** The Stream panel, 1:1 with deck.html #panel-stream: the control card (status, the four
 *  switches, monitor + quality, Connect / Stop) and the stage. The PWA's 'LOCK input' button
 *  is deliberately not here (#122 decision 2). */
@Composable
fun StreamPanel(state: JsonObject?) {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val st by StreamEngine.status.collectAsState()
    val track by StreamEngine.video.collectAsState()
    var opts by remember { mutableStateOf(StreamPrefs.load(ctx)) }
    val names = state.obj("hypr").list("monitors").mapNotNull { it.str("name") }
    val server = state.obj("stream")
    val live = st.phase == Phase.LIVE

    // default the monitor to the focused one, like the PWA's first seg
    LaunchedEffect(names) {
        if (opts.monitor == null || opts.monitor !in names) {
            val pick = state.obj("hypr").list("monitors").firstOrNull { it["focused"]?.toString() == "true" }?.str("name") ?: names.firstOrNull()
            if (pick != null) { opts = opts.copy(monitor = pick); StreamPrefs.save(ctx, opts) }
        }
    }
    // picker follow (app.js renderStream): the capture moved to another output → show that one,
    // without reconnecting (the server already moved it)
    val out = server.str("output")
    LaunchedEffect(out, live) {
        if (live && server.str("state") == "live" && out != null && out != opts.monitor && out in names) {
            opts = opts.copy(monitor = out); StreamPrefs.save(ctx, opts); StreamEngine.follow(opts)
        }
    }

    fun change(o: StreamOpts) {
        opts = o; StreamPrefs.save(ctx, o)
        if (StreamEngine.active) {
            if (o.anyDirection) StreamService.apply(ctx, o) else StreamService.stop(ctx)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) change(opts.copy(mic = true))
    }

    val status = when {
        live && server.str("state") == "lost" -> "● connected · video lost (${server.str("output")}) — reacquiring…"
        live && server.str("state") == "live" && out != null && out != server.str("requested") && st.videoOn -> "● connected · video moved to $out"
        st.phase == Phase.IDLE && !opts.anyDirection -> "enable a direction first"
        else -> st.note
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("Stream")
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.width(288.dp).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardTitle("Control")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (live) ph.primary else ph.onVar.copy(alpha = 0.4f))
                            .then(if (live) Modifier.glow(ph.primary, 5.dp) else Modifier))
                        Mono(status, 13.sp, ph.onVar, maxLines = 2)
                    }
                    SwitchRow("Listen to PC", opts.listen) { change(opts.copy(listen = !opts.listen)) }
                    SwitchRow("Phone mic → PC", opts.mic) {
                        if (opts.mic) change(opts.copy(mic = false))
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            change(opts.copy(mic = true))
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    SwitchRow("Phone-only output", opts.phoneOnly) { change(opts.copy(phoneOnly = !opts.phoneOnly)) }
                    SwitchRow("Video (screen)", opts.video, last = true) { change(opts.copy(video = !opts.video)) }
                    Seg(names.map { it to it }, opts.monitor) { change(opts.copy(monitor = it)) }
                    Seg(listOf("low" to "540p", "medium" to "720p", "high" to "1080p"), opts.quality) { change(opts.copy(quality = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("Connect", filled = true, Modifier.weight(1f)) {
                            if (opts.anyDirection) StreamService.apply(ctx, opts)
                        }
                        BigButton("Stop", filled = false, Modifier.weight(1f)) { StreamService.stop(ctx) }
                    }
                    Mono("Mic shows up on the rig as input “Monitor of PhoneDeckMic”: select it in the app you want it in. " +
                        "Headphones recommended to avoid echo. Audio keeps playing with the screen off.", 11.sp, ph.onVar.copy(alpha = 0.7f), maxLines = 6)
                }
            }
            Stage(track, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** The stage: the rig's screen when video is on, the ▛ STREAM ▟ placeholder otherwise. */
@Composable
private fun Stage(track: VideoTrack?, modifier: Modifier) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(Color.Black).border(1.dp, ph.outline, shape), contentAlignment = Alignment.Center) {
        if (track == null) {
            Mono("▛ STREAM ▟", 14.sp, ph.onVar.copy(alpha = 0.6f))
        } else {
            val ctx = LocalContext.current
            val view = remember {
                SurfaceViewRenderer(ctx).apply {
                    init(StreamEngine.egl.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                }
            }
            DisposableEffect(track) {
                track.addSink(view)
                onDispose { track.removeSink(view) }
            }
            DisposableEffect(Unit) { onDispose { view.release() } }
            AndroidView({ view }, Modifier.fillMaxSize())
        }
    }
}

/** A row with a .tgl sliding switch. */
@Composable
private fun SwitchRow(label: String, on: Boolean, last: Boolean = false, onClick: () -> Unit) {
    val ph = LocalPh.current
    Column {
        Row(Modifier.fillMaxWidth().tap(onClick).padding(bottom = if (last) 0.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Mono(label, 14.sp, ph.onVar)
            val knob by animateDpAsState(if (on) 26.dp else 2.dp, label = "tgl")
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.size(46.dp, 22.dp).then(if (on) Modifier.boxGlow(ph.primary, 4.dp, 0.35f) else Modifier).clip(shape)
                .background(if (on) lerp(Color.Black, ph.primary, 0.18f) else ph.low)
                .border(1.dp, if (on) ph.primary else ph.outline, shape)) {
                Box(Modifier.offset(x = knob, y = 2.dp).size(16.dp).clip(CircleShape).background(if (on) ph.primary else ph.onVar))
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(ph.high))
    }
}

/** .seg: a segmented terminal toggle (replaces a native select). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Seg(items: List<Pair<String, String>>, value: String?, onPick: (String) -> Unit) {
    val ph = LocalPh.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) ->
            val on = v == value
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.weight(1f).then(if (on) Modifier.boxGlow(ph.primary, 6.dp) else Modifier).clip(shape)
                .background(if (on) ph.primary else ph.low).border(1.dp, if (on) ph.primary else ph.outline, shape)
                .tap { onPick(v) }.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Mono(label, 13.sp, if (on) ph.onPrimary else ph.onVar)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.then(if (filled) Modifier.boxGlow(ph.primary, 6.dp) else Modifier).clip(shape)
        .background(if (filled) lerp(Color.Black, ph.primary, 0.88f) else ph.high)
        .border(1.dp, ph.outline, shape).tap(onClick).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Mono(text, 14.sp, if (filled) ph.onPrimary else ph.on)
    }
}
