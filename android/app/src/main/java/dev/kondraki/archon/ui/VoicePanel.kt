package dev.kondraki.archon.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** One trace card: what the deck heard and planned; after a tap, its result (or Friday's answer). */
class Trace(val t: JsonObject) {
    var status by mutableStateOf(if (t["plan"] is JsonObject) "pending" else "")    // pending | running | done | fail
    var result by mutableStateOf<JsonObject?>(null)
}

/** A null reply: the rig took longer than Deck.slow allows, or the link dropped. */
private const val NO_REPLY = "✗ no reply from the rig (timed out or unreachable)"

/** The log outlives switching panels (the PWA keeps its DOM while the page lives). */
object VoiceLog { val traces = mutableStateListOf<Trace>() }

/**
 * Voice, 1:1 with app.js initVoice(): the mic (Desktop = the rig's mic via /voice/start+stop,
 * Device = this phone's mic, a clip sent to /voice/transcribe), one START/STOP button, and the
 * trace log, newest first: a pending plan is amber and runs on tap (confirm-flagged plans ask
 * first); Friday's answers type out phosphor-style with their sources.
 */
@Composable
fun VoicePanel() {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mic by remember { mutableStateOf("desktop") }
    var state by remember { mutableStateOf("idle") }                 // idle | recording | processing
    var recMode by remember { mutableStateOf("desktop") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var confirm by remember { mutableStateOf<Trace?>(null) }

    fun add(t: JsonObject?) {
        if (t != null) { VoiceLog.traces.add(0, Trace(t)); while (VoiceLog.traces.size > 20) VoiceLog.traces.removeAt(VoiceLog.traces.lastIndex) }
        state = "idle"
    }
    fun note(n: String) = add(buildJsonObject { put("heard", ""); put("note", n) })
    fun noReply() = buildJsonObject { put("heard", ""); put("note", NO_REPLY) }
    fun startDevice() {
        val f = File(ctx.cacheDir, "voice.m4a").apply { delete() }
        runCatching {
            recorder = MediaRecorder(ctx).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000); setAudioEncodingBitRate(64000)
                setOutputFile(f.absolutePath); prepare(); start()
            }
            recMode = "device"; state = "recording"
        }.onFailure { note("mic unavailable") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startDevice() else note("mic permission denied")
    }
    fun run(tr: Trace) {
        val plan = tr.t["plan"] as? JsonObject ?: return
        tr.status = "running"
        scope.launch {
            val r = Deck.postJson("/voice/execute", buildJsonObject { put("plan", plan) }, Deck.slow)
                ?: buildJsonObject { put("ok", false); put("result", NO_REPLY) }
            tr.result = r
            tr.status = if (r?.bool("ok") == true) "done" else "fail"
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("Voice")
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.width(280.dp).fillMaxHeight()) {
                CardTitle("Microphone")
                SegRow(listOf("desktop" to "Desktop (rig)", "device" to "Device (phone)"), mic, small = true) { if (state == "idle") mic = it }
                VoiceButton(state) {
                    when (state) {
                        "idle" -> {
                            if (mic == "device") {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startDevice()
                                else permission.launch(Manifest.permission.RECORD_AUDIO)
                            } else scope.launch {
                                val r = Deck.postJson("/voice/start")
                                if (r?.bool("ok") == true) { recMode = "desktop"; state = "recording" } else note(r?.str("error") ?: "mic unavailable")
                            }
                        }
                        "recording" -> {
                            state = "processing"
                            scope.launch {
                                if (recMode == "device") {
                                    val rec = recorder; recorder = null
                                    runCatching { rec?.stop() }; rec?.release()
                                    add(transcribe(ctx) ?: noReply())
                                } else add(Deck.postJson("/voice/stop", client = Deck.slow) ?: noReply())
                            }
                        }
                    }
                }
                Text(buildAnnotatedString {
                    append("Lane word first — ")
                    listOf("macro", "type", "input", "friday").forEachIndexed { i, w ->
                        if (i > 0) append(" · ")
                        withStyle(SpanStyle(color = ph.secondary, fontWeight = FontWeight.Medium)) { append(w) }
                    }
                    append(". Tap a result to run.")
                }, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.onVar.copy(alpha = 0.8f)))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Trace · Answers")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VoiceLog.traces) { tr ->
                        TraceCard(tr) {
                            val plan = tr.t["plan"] as? JsonObject
                            if (tr.status == "pending") { if (plan?.bool("confirm") == true) confirm = tr else run(tr) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { tr ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = ph.surface,
            title = { Label("Run \"${(tr.t["plan"] as? JsonObject).str("label").orEmpty()}\"?", 14.sp, ph.primary) },
            confirmButton = { Mono("Run", 14.sp, ph.primary, Modifier.tap { confirm = null; run(tr) }.padding(8.dp)) },
            dismissButton = { Mono("Cancel", 14.sp, ph.onVar, Modifier.tap { confirm = null }.padding(8.dp)) })
    }
}

/** #voice-btn: the amber terminal button; pulses while recording, dims while working. */
@Composable
private fun VoiceButton(state: String, onClick: () -> Unit) {
    val ph = LocalPh.current
    val pulse = if (state == "recording") rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.5f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "rec").value else 1f
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().alpha(if (state == "processing") 0.7f else pulse).clip(shape)
        .background(Brush.radialGradient(listOf(ph.secondary.copy(alpha = 0.12f), Color.Transparent)))
        .border(1.dp, ph.secondary, shape)
        .then(if (state == "processing") Modifier else Modifier.tap(onClick)).padding(vertical = 32.dp),
        contentAlignment = Alignment.Center) {
        Text(when (state) { "idle" -> "▶ START"; "recording" -> "■ STOP"; else -> "··· WORKING" },
            style = CssLine + TextStyle(fontFamily = Plex, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                color = ph.secondary, shadow = Shadow(ph.secondary.copy(alpha = 0.45f), Offset.Zero, 16f)))
    }
}

@Composable
private fun TraceCard(tr: Trace, onTap: () -> Unit) {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val t = tr.t
    val pending = tr.status == "pending"
    val friday = (t["plan"] as? JsonObject).str("lane") == "jarvis" && tr.status != "pending"
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(if (pending) Color.Black.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(Color.Black, ph.secondary, 0.06f) } else ph.low)
        .border(1.dp, if (pending) ph.secondary.copy(alpha = 0.5f) else ph.outline, shape)
        .then(if (pending) Modifier.tap(onTap) else Modifier).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val heard = t.str("heard").orEmpty()
        if (friday) {
            Label("Friday", 10.sp, ph.primary, spacing = 0.2f, glow = true)
            Mono("\"$heard\"", 12.sp, ph.onVar.copy(alpha = 0.7f))
            val r = tr.result
            if (tr.status == "running") Mono("…thinking", 14.sp, ph.onVar)
            else {
                Typewriter(r?.str("result") ?: "no answer", if (r?.bool("ok") == true) ph.on else ph.secondary)
                val sources = r.list("sources")
                if (sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    sources.forEachIndexed { i, s ->
                        val url = s.str("url") ?: return@forEachIndexed
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, ph.primary.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .tap { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .padding(horizontal = 7.dp, vertical = 1.dp)) { Mono("[${i + 1}] ${s.str("title").orEmpty()}", 12.sp, ph.primary) }
                    }
                }
            }
            return@Column
        }
        Text(if (heard.isNotEmpty()) "\"$heard\"" else "(nothing heard)", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = ph.on))
        Text(buildAnnotatedString {
            val verb = t.str("verb")
            if (verb != null) withStyle(SpanStyle(color = ph.secondary, fontWeight = FontWeight.Medium)) { append(verb) }
            else withStyle(SpanStyle(color = ph.onVar.copy(alpha = 0.6f))) { append("no verb") }
            append(" · ")
            withStyle(SpanStyle(color = ph.onVar)) { append(t.str("preview") ?: t.str("note") ?: "") }
            when (tr.status) {
                "pending" -> { append(" · "); withStyle(SpanStyle(color = ph.secondary)) { append("tap to run ▶") } }
                "running" -> { append(" · "); withStyle(SpanStyle(color = ph.onVar)) { append("running…") } }
                "done" -> { append(" · "); withStyle(SpanStyle(color = ph.secondary)) { append("✓ ${tr.result.str("result") ?: "ok"}") } }
                "fail" -> { append(" · "); withStyle(SpanStyle(color = DANGER)) { append("✗ ${tr.result.str("result") ?: "failed"}") } }
            }
        }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp))
    }
}

/** Friday's answer, typed out at 11 ms a character with a blinking ▋ (.jv-type / .jv-cursor). */
@Composable
private fun Typewriter(text: String, color: Color) {
    val ph = LocalPh.current
    var n by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) { while (n < text.length) { delay(11); n++ } }
    val blink = rememberInfiniteTransition(label = "cur").animateFloat(1f, 0f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "cur").value
    Text(buildAnnotatedString {
        append(text.take(n))
        if (n < text.length) withStyle(SpanStyle(color = ph.primary.copy(alpha = if (blink > 0.5f) 1f else 0f))) { append("▋") }
    }, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = color), modifier = Modifier.padding(top = 4.dp))
}

/** The phone-mic clip → POST /voice/transcribe (the deck's decoder reads the container itself). */
private suspend fun transcribe(ctx: Context): JsonObject? = withContext(Dispatchers.IO) {
    runCatching {
        val f = File(ctx.cacheDir, "voice.m4a")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.m4a", f.asRequestBody("audio/mp4".toMediaType())).build()
        Deck.slow.newCall(Request.Builder().url("${Deck.url}/voice/transcribe").post(form).build()).execute().use { r ->
            if (r.isSuccessful) Deck.json.parseToJsonElement(r.body.string()).jsonObject else null
        }.also { f.delete() }
    }.getOrNull()
}
