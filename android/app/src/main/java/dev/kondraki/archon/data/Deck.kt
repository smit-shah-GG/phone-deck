package dev.kondraki.archon.data

import dev.kondraki.archon.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The deck's colours, from GET /palette: the same values /theme.css paints the PWA with. */
@Serializable
data class Palette(
    val profile: String = "",
    val crt: Boolean = true,
    val colors: Map<String, String> = emptyMap(),
)

/** The one place that talks to the deck server. The URL is baked in at build time from
 *  ~/.config/archon-android/archon.properties, so no tailnet name lives in this (public) repo. */
object Deck {
    val url: String = BuildConfig.DECK_URL

    /** The machine the deck runs on, from the URL's first label (lightning.x.ts.net → LIGHTNING). */
    val host: String = url.substringAfter("://").substringBefore('.').substringBefore(':').uppercase()

    val json = Json { ignoreUnknownKeys = true }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun palette(): Palette = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$url/palette").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.decodeFromString(Palette.serializer(), r.body.string())
        }
    }
}
