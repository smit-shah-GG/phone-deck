package dev.kondraki.archon.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kondraki.archon.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The deck's colours, from GET /palette: the same values /theme.css paints the PWA with. */
@Serializable
data class Palette(
    val profile: String = "",
    val crt: Boolean = true,
    val colors: Map<String, String> = emptyMap(),
)

enum class Link { CONNECTING, ONLINE, OFFLINE, UNAUTHORIZED }

/**
 * The one place that talks to the deck, and the one process-wide holder of its live state
 * (tasks #117 rule: the server is the brain; this client renders what /ws sends and posts
 * the same requests the PWA does). The URL is baked in at build time from
 * ~/.config/archon-android/archon.properties, so no tailnet name lives in this public repo.
 */
object Deck {
    val url: String = BuildConfig.DECK_URL

    /** The machine the deck runs on, from the URL's first label (lightning.x.ts.net → LIGHTNING). */
    val host: String = url.substringAfter("://").substringBefore('.').substringBefore(':').uppercase()

    val json = Json { ignoreUnknownKeys = true }
    lateinit var session: Session

    /** Every request carries the session cookie, like the PWA's same-origin fetches. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)          // notice a dead socket on a flaky network
            .addInterceptor { chain ->
                val t = if (::session.isInitialized) session.token.value else null
                chain.proceed(chain.request().newBuilder().apply {
                    if (t != null) header("Cookie", "deck_session=$t")
                }.build())
            }
            .build()
    }
    val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).build() }

    // ---- live state (/ws)
    private val _state = MutableStateFlow<JsonObject?>(null)
    val state: StateFlow<JsonObject?> = _state
    private val _link = MutableStateFlow(Link.CONNECTING)
    val link: StateFlow<Link> = _link

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var loop: Job? = null

    /** /ws stays open while anyone holds it: the app on screen ("ui") and the stream service
     *  ("stream", for the player's now playing). Reconnects every 2 s like app.js. */
    private val holders = mutableSetOf<String>()
    @Synchronized fun hold(who: String) { holders += who; start() }
    @Synchronized fun release(who: String) { holders -= who; if (holders.isEmpty()) stop() }

    private fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                if (session.token.value == null) { _link.value = Link.UNAUTHORIZED; delay(1000); continue }
                val closed = MutableStateFlow(false)
                socket = http.newWebSocket(Request.Builder().url(url.replaceFirst("http", "ws") + "/ws").build(),
                    object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) { _link.value = Link.ONLINE }
                        override fun onMessage(ws: WebSocket, text: String) {
                            runCatching { _state.value = json.parseToJsonElement(text).jsonObject }
                        }
                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            _link.value = if (code == 4401) Link.UNAUTHORIZED else Link.OFFLINE
                            closed.value = true
                        }
                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                            // the server refuses an unauthenticated upgrade before accepting it (403)
                            _link.value = if (response?.code == 403 || response?.code == 401) Link.UNAUTHORIZED else Link.OFFLINE
                            closed.value = true
                        }
                    })
                while (!closed.value) delay(250)
                if (_link.value == Link.UNAUTHORIZED) session.clear()     // back to the PIN screen
                delay(2000)
            }
        }
    }

    private fun stop() {
        loop?.cancel(); loop = null
        socket?.close(1000, "background"); socket = null
        _link.value = Link.CONNECTING
    }

    /** POST a JSON body, like app.js post(); false on any failure (the next /ws push shows the truth). */
    suspend fun post(path: String, body: JsonObject = buildJsonObject { }): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$url$path")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** GET JSON (brightness levels, context config, /state, /modes); null on any failure. */
    suspend fun getElement(path: String): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$url$path").build()).execute().use { r ->
                if (r.isSuccessful) json.parseToJsonElement(r.body.string()) else null
            }
        }.getOrNull()
    }
    suspend fun getJson(path: String): JsonObject? = getElement(path) as? JsonObject

    /** POST and read the JSON answer ({"ok": …}); null when the deck can't be reached. */
    /** For calls that wait on the rig's own work (speech-to-text, Friday's LLM answer: a cold
     *  model load alone is ~15 s). Covers jarvis' SearXNG 15 s + ollama 90 s with headroom. */
    val slow: OkHttpClient by lazy { http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build() }

    suspend fun postJson(path: String, body: JsonObject = buildJsonObject { }, client: OkHttpClient = http): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$url$path")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) json.parseToJsonElement(r.body.string()) as? JsonObject else null }
        }.getOrNull()
    }

    suspend fun palette(): Palette = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$url/palette").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.decodeFromString(Palette.serializer(), r.body.string())
        }
    }

    /** The rig's album art (GET /media/art), keyed like the PWA so it's only fetched per track. */
    suspend fun art(key: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$url/media/art?k=$key").build()).execute().use { r ->
                if (r.isSuccessful) BitmapFactory.decodeStream(r.body.byteStream()) else null
            }
        }.getOrNull()
    }
}
