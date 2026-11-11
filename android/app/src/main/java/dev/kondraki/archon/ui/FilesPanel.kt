package dev.kondraki.archon.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files, 1:1 with deck.html #panel-files: grab a screenshot of one monitor (and save it to
 *  the phone's Pictures/Archon), send a file from the phone to ~/phone-deck-drop on the rig. */
@Composable
fun FilesPanel(state: JsonObject?, onBack: () -> Unit) {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val names = state.obj("hypr").list("monitors").mapNotNull { it.str("name") }
    var mon by remember { mutableStateOf<String?>(null) }
    val pick = mon?.takeIf { it in names } ?: names.firstOrNull()
    var shot by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var grabbing by remember { mutableStateOf(false) }
    var shotNote by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    var upStatus by remember { mutableStateOf("") }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { file = uri; upStatus = "" } }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHead("Files", onBack)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardTitle("Screenshot")
                    SegRow(names.map { it to it }, pick) { mon = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Wide("Grab", filled = true, dim = grabbing, Modifier.weight(1f)) {
                            val m = pick ?: return@Wide
                            grabbing = true; shotNote = ""
                            scope.launch {
                                val png = fetchShot(m)
                                shot = png?.let { m to it }; if (png == null) shotNote = "✗ no capture (rig offline or no such output)"
                                grabbing = false
                            }
                        }
                        Wide("Save to phone", filled = false, dim = shot == null, Modifier.weight(1f)) {
                            val (m, png) = shot ?: return@Wide
                            scope.launch { shotNote = savePng(ctx, m, png) }
                        }
                    }
                    DashedArea(Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
                        val s = shot
                        val bmp = remember(s) { s?.second?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        if (bmp == null) Mono("▚ no capture yet ▞", 12.sp, ph.onVar.copy(alpha = 0.6f))
                        else Image(bmp.asImageBitmap(), "screenshot of ${s?.first}", Modifier.fillMaxWidth().border(1.dp, ph.outline), contentScale = ContentScale.FillWidth)
                    }
                    if (shotNote.isNotEmpty()) Mono(shotNote, 12.sp, ph.onVar, maxLines = 2)
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                CardTitle("Send file to PC")
                DashedArea(Modifier.fillMaxWidth().tap { chooser.launch("*/*") }) {
                    Box(Modifier.padding(14.dp)) { Mono(file?.let { displayName(ctx, it) } ?: "Choose a file…", 13.sp, ph.onVar, maxLines = 2) }
                }
                Wide("Send to PC", filled = true, dim = file == null, Modifier.fillMaxWidth()) {
                    val uri = file ?: run { upStatus = "pick a file first"; return@Wide }
                    val name = displayName(ctx, uri)
                    upStatus = "uploading $name…"
                    scope.launch { upStatus = upload(ctx, uri, name) }
                }
                if (upStatus.isNotEmpty()) Mono(upStatus, 12.sp, ph.onVar, maxLines = 3)
                Mono("Lands in ~/phone-deck-drop on the rig.", 12.sp, ph.onVar.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun Wide(text: String, filled: Boolean, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.alpha(if (dim) 0.5f else 1f).then(if (filled) Modifier.boxGlow(ph.primary, 6.dp) else Modifier).clip(shape)
        .background(if (filled) lerp(Color.Black, ph.primary, 0.88f) else ph.high).border(1.dp, ph.outline, shape)
        .tap(onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Mono(text, 14.sp, if (filled) ph.onPrimary else ph.on)
    }
}

/** theme.css .shot-area / #up-file: a dashed drop-zone on the low surface. */
@Composable
private fun DashedArea(modifier: Modifier, content: @Composable () -> Unit) {
    val ph = LocalPh.current
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(ph.low).drawBehind {
        drawRoundRect(ph.outline, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
    }, contentAlignment = Alignment.Center) { content() }
}

private suspend fun fetchShot(monitor: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        Deck.http.newCall(Request.Builder().url("${Deck.url}/screenshot?monitor=${URLEncoder.encode(monitor, "UTF-8")}").build())
            .execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }.getOrNull()
}

/** Pictures/Archon/<monitor>-<time>.png through MediaStore: no storage permission needed. */
private suspend fun savePng(ctx: Context, monitor: String, png: ByteArray): String = withContext(Dispatchers.IO) {
    val name = "$monitor-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Archon")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no media store")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(png) }
        "✓ saved to Pictures/Archon/$name"
    }.getOrElse { "✗ couldn't save: ${it.message}" }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"

/** POST /upload as multipart, streamed from the content resolver (no copy in memory). */
private suspend fun upload(ctx: Context, uri: Uri, name: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val type = ctx.contentResolver.getType(uri)?.toMediaTypeOrNull()
        val body = object : RequestBody() {
            override fun contentType() = type
            override fun writeTo(sink: BufferedSink) { ctx.contentResolver.openInputStream(uri)!!.source().use { sink.writeAll(it) } }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, body).build()
        Deck.http.newBuilder().writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url("${Deck.url}/upload").post(form).build()).execute().use { r ->
                val res = runCatching { Deck.json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
                when {
                    res?.bool("ok") == true -> "✓ saved ${res.str("name")} (${String.format(Locale.US, "%.2f", (res.num("size") ?: 0.0) / 1048576)} MB)"
                    else -> "✗ ${res?.str("error") ?: "HTTP ${r.code}"}"
                }
            }
    }.getOrElse { "✗ ${it.message ?: "upload failed"}" }
}

