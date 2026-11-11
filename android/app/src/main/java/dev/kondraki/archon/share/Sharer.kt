package dev.kondraki.archon.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.ShareOutcome
import dev.kondraki.archon.data.shareOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/** One thing shared to Archon: words (EXTRA_TEXT, links included) and/or files (EXTRA_STREAM). */
data class Outgoing(val title: String, val text: String, val uris: List<Uri>) {
    companion object {
        /** From an ACTION_SEND / ACTION_SEND_MULTIPLE intent; null when there's nothing to send. */
        fun from(i: Intent): Outgoing? {
            if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return null
            val uris = buildList {
                if (i.action == Intent.ACTION_SEND) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }.distinct()
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val title = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return if (uris.isEmpty() && text.isBlank()) null else Outgoing(title, text, uris)
        }
    }
}

/** What the share card shows; one share at a time (a new share replaces the line). */
sealed interface ShareState {
    data object Idle : ShareState
    /** total: bytes of files, -1 = a file of unknown size, 0 = words only. */
    data class Sending(val what: String, val sent: Long, val total: Long) : ShareState
    data class Finished(val outcome: ShareOutcome) : ShareState
}

/**
 * Send-to-rig, native (#123 E): one multipart POST /share/api (title, text, files), the same
 * form the PWA's service worker relays; the deck decides what happens (a bare link opens, text
 * goes to the rig clipboard, files land in ~/phone-deck-drop, one image is also put on the
 * clipboard). Archon's stored token rides on Deck's cookie interceptor, so no relay is needed.
 */
object Sharer {
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state
    fun set(s: ShareState) { _state.value = s }

    /** Uploads can be large and the deck converts images to PNG before answering: long write
     *  and read timeouts (Deck.slow's 150 s read covers the PNG + clipboard steps). */
    private val client by lazy { Deck.slow.newBuilder().writeTimeout(15, TimeUnit.MINUTES).build() }

    data class FileInfo(val uri: Uri, val name: String, val size: Long, val type: String?)

    fun describe(ctx: Context, uris: List<Uri>): List<FileInfo> = uris.mapIndexed { n, uri ->
        var name: String? = null
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        FileInfo(uri, name ?: uri.lastPathSegment ?: "shared-${n + 1}", size, ctx.contentResolver.getType(uri))
    }

    /** POST it; [progress] gets the bytes written so far (files only). */
    suspend fun send(ctx: Context, out: Outgoing, files: List<FileInfo>, progress: (Long) -> Unit = {}): ShareOutcome =
        withContext(Dispatchers.IO) {
            var sent = 0L
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("title", out.title).addFormDataPart("text", out.text).addFormDataPart("url", "")
            files.forEach { f ->
                form.addFormDataPart("files", f.name, object : RequestBody() {
                    override fun contentType() = f.type?.toMediaTypeOrNull()
                    override fun contentLength() = f.size
                    override fun writeTo(sink: BufferedSink) {
                        ctx.contentResolver.openInputStream(f.uri)!!.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                sink.write(buf, 0, n); sent += n; progress(sent)
                            }
                        }
                    }
                })
            }
            runCatching {
                client.newCall(Request.Builder().url("${Deck.url}/share/api").post(form.build()).build()).execute().use { r ->
                    val body = runCatching { Deck.json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
                    shareOutcome(r.code, body, Deck.host)
                }
            }.getOrElse { e ->
                if (e is SecurityException || e is java.io.FileNotFoundException) ShareOutcome(false, "CAN'T READ FILE", e.message ?: "no permission")
                else shareOutcome(0, null, Deck.host)
            }
        }
}
