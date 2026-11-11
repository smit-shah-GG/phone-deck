package dev.kondraki.archon.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One mode chip on the Remote widget (the first two modes from GET /modes). */
@Serializable data class ModeChip(val id: String, val label: String)

/**
 * What the Remote widget shows (canvas Widget board), kept small and persisted so the widget can
 * draw its last-known face after the process is gone. Pure data: built from the deck's state.
 */
@Serializable
data class RemoteSnap(
    val at: Long = 0,                 // when this was last true (ms)
    val online: Boolean = false,      // the last fetch reached the rig
    val live: Boolean = false,        // fed by the open /ws (the app or the stream is running)
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val hasPlayer: Boolean = false,
    val screensOn: Boolean = true,    // panels on = not headless
    val screensKnown: Boolean = false,// /state carries headless; /ws doesn't, so it can lag in live mode
    val listening: Boolean = false,   // this phone's stream is running
    val modes: List<ModeChip> = emptyList(),
    val artKey: String = "",
) {
    /** Equal for the widget's purposes: `at` moves on every live message and isn't drawn then. */
    fun sameFace(o: RemoteSnap) = copy(at = 0) == o.copy(at = 0)
}

/** Build the widget's snapshot from a deck state (from /ws or GET /state). Fields the state
 *  doesn't carry (headless on /ws, the modes) come from [prev]. */
fun remoteFrom(state: JsonObject, prev: RemoteSnap, listening: Boolean, live: Boolean, now: Long,
               modes: List<ModeChip> = prev.modes): RemoteSnap {
    val np = state.obj("audio").obj("now_playing")
    val status = np.str("status").orEmpty()
    val track = np.str("track")?.takeIf { it.isNotBlank() }
    val artist = np.str("artist")?.takeIf { it.isNotBlank() }
    val h = state.obj("headless")
    return RemoteSnap(
        at = now, online = true, live = live,
        title = track ?: np.str("title").orEmpty(),
        artist = if (track != null) artist.orEmpty() else "",
        playing = status == "Playing", hasPlayer = status.isNotEmpty(),
        screensOn = if (h != null && h.bool("available") != false) h.bool("on") != true else prev.screensOn,
        screensKnown = (h != null && h.bool("available") != false) || prev.screensKnown,
        listening = listening, modes = modes, artKey = np.str("art_key").orEmpty(),
    )
}

/** The widget's freshness: live on /ws, fresh within 15 min, stale after, or offline. */
enum class Fresh { LIVE, FRESH, STALE, OFFLINE }

fun remoteFresh(s: RemoteSnap, now: Long): Fresh = when {
    !s.online -> Fresh.OFFLINE
    s.live -> Fresh.LIVE
    now - s.at > 15 * 60_000L -> Fresh.STALE
    else -> Fresh.FRESH
}

/** Row 1's stamp, as the canvas words it. */
fun remoteStamp(f: Fresh, at: String): String = when (f) {
    Fresh.LIVE -> "live"
    Fresh.FRESH, Fresh.STALE -> "as of $at"
    Fresh.OFFLINE -> "rig offline · tap"
}
