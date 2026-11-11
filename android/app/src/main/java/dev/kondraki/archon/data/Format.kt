package dev.kondraki.archon.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.Locale

// Pure logic, kept free of Android so the JVM unit tests can cover it. Every string here
// mirrors what app.js renders, so the two clients read the same (the server stays the brain).

// ---- JSON access: the state is the server's raw /ws snapshot; a missing field is never fatal
fun JsonObject?.obj(key: String): JsonObject? = (this?.get(key) as? JsonObject)
fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonObject?.list(key: String): List<JsonObject> =
    ((this?.get(key) as? kotlinx.serialization.json.JsonArray) ?: emptyList<JsonElement>()).mapNotNull { it as? JsonObject }

// ---- band telemetry (app.js renderStatus)
enum class Heat { OK, WARN, ALERT }

/** amber at temp >= 80 or util >= 90, red at temp >= 88: the PWA's thresholds. */
fun heat(temp: Double, util: Double): Heat = when {
    temp >= 88 -> Heat.ALERT
    temp >= 80 || util >= 90 -> Heat.WARN
    else -> Heat.OK
}

fun gpuLine(g: JsonObject): String {
    val used = (g.num("vram_used") ?: 0.0) / 1024
    val total = (g.num("vram_total") ?: 0.0) / 1024
    // Locale.US: a phone set to a comma-decimal locale must still print 3.1, like the PWA
    return "GPU ${(g.num("util") ?: 0.0).toInt()}% · ${String.format(Locale.US, "%.1f", used)}/" +
        "${String.format(Locale.US, "%.0f", total)}G · ${(g.num("temp") ?: 0.0).toInt()}°"
}

fun cpuLine(c: JsonObject): String {
    val t = c.num("temp")
    val temp = if (t != null && t != 0.0) " · ${t.toInt()}°" else ""
    return "CPU ${(c.num("util") ?: 0.0).toInt()}%$temp · ${plain(c["mem_used"])}/${plain(c["mem_total"])}G"
}

fun micLine(a: JsonObject): String {
    val mic = if (a.bool("mic_muted") == true) "MIC×" else "MIC"
    val vol = if (a.bool("sink_muted") == true) "MUTE" else "${a.num("volume")?.toInt() ?: "—"}%"
    return "$mic · $vol"
}

/** "▶ title" / "‖ title" / "—", like app.js renderAudio. */
fun nowPlayingLine(np: JsonObject?): String {
    val title = np.str("title")
    if (title.isNullOrEmpty()) return "—"
    return (if (np.str("status") == "Playing") "▶ " else "‖ ") + title
}

private fun plain(e: JsonElement?): String = (e as? JsonPrimitive)?.let {
    it.longOrNull?.toString() ?: it.doubleOrNull?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: it.content
} ?: "—"

// ---- auth
/** The JWT's exp claim (epoch seconds), read without verifying: the server verifies; we only
 *  need to know when to refresh. 0 when unreadable. */
fun jwtExp(token: String): Long = runCatching {
    val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1].padEnd(((token.split('.')[1].length + 3) / 4) * 4, '=')))
    (Json.parseToJsonElement(payload).jsonObject["exp"] as JsonPrimitive).longOrNull ?: 0L
}.getOrDefault(0L)

/** Refresh when under 5 of the 7 days remain: daily use refreshes about once a day, and a phone
 *  left alone up to 5 days still renews silently. */
fun refreshDue(exp: Long, nowSec: Long): Boolean = exp - nowSec < 5 * 86400

sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object BadPin : LoginResult
    data class Locked(val seconds: Int) : LoginResult
    data class Failed(val why: String) : LoginResult
}

/** POST /login answers 303: "/" + a Set-Cookie on success, "/login?bad=1" for a wrong PIN,
 *  "/login?locked=N" while locked out. */
fun loginOutcome(code: Int, location: String?, setCookies: List<String>, cookieName: String = "deck_session"): LoginResult {
    if (code != 303 || location == null) return LoginResult.Failed("HTTP $code")
    Regex("locked=(\\d+)").find(location)?.let { return LoginResult.Locked(it.groupValues[1].toInt()) }
    if ("bad=" in location) return LoginResult.BadPin
    val token = setCookies.firstNotNullOfOrNull { c ->
        c.split(';').first().trim().takeIf { it.startsWith("$cookieName=") }?.substringAfter('=')
    }
    return if (location == "/" && !token.isNullOrEmpty()) LoginResult.Ok(token) else LoginResult.Failed("no session cookie")
}
