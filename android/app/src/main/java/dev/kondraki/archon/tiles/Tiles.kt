package dev.kondraki.archon.tiles

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Quick Settings tiles (canvas Main board; tasks #121 decisions 1-2). Each opening of the shade
 * asks GET /state once (1.5 s cap: the tile can't hold /ws open) and paints label, subtitle and
 * state. One UI shows only the label, so labels must stand alone; the Pixel adds the subtitle.
 * Both tiles act on a locked phone too (decided: neither can do harm).
 */
abstract class DeckTile : TileService() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected var last: JsonObject? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    protected fun refresh(after: Long = 0) {
        scope.launch {
            if (after > 0) delay(after)
            if (Deck.session.token.value == null) { paint(null, signedOut = true); return@launch }
            last = withTimeoutOrNull(1500) { Deck.getJson("/state") }
            paint(last, signedOut = false)
        }
    }

    private fun paint(state: JsonObject?, signedOut: Boolean) {
        val t = qsTile ?: return
        when {
            signedOut -> t.set(Tile.STATE_UNAVAILABLE, label, "Sign in to Archon")
            state == null -> t.set(Tile.STATE_UNAVAILABLE, label, "Rig offline")
            else -> render(t, state)
        }
        t.updateTile()
    }

    protected fun Tile.set(st: Int, label: String, sub: String) {
        state = st; this.label = label; subtitle = sub
        icon = Icon.createWithResource(this@DeckTile, iconRes)
        contentDescription = "$label, $sub"
    }

    abstract val label: String
    abstract val iconRes: Int
    abstract fun render(t: Tile, state: JsonObject)
}

/** Rig music: play / pause the rig's player; the Pixel's second line shows the track. */
class RigMusicTile : DeckTile() {
    override val label = "Rig music"
    override val iconRes = R.drawable.ic_tile_music

    override fun render(t: Tile, state: JsonObject) {
        val np = state.obj("audio").obj("now_playing")
        val status = np.str("status").orEmpty()
        val track = listOfNotNull(np.str("artist")?.takeIf { it.isNotBlank() }, np.str("track")?.takeIf { it.isNotBlank() })
            .joinToString(" - ").ifEmpty { np.str("title").orEmpty() }
        when (status) {
            "Playing" -> t.set(Tile.STATE_ACTIVE, label, track.ifEmpty { "Playing" })
            "Paused", "Stopped" -> t.set(Tile.STATE_INACTIVE, label, track.ifEmpty { "Paused" })
            else -> t.set(Tile.STATE_UNAVAILABLE, label, "Nothing playing")
        }
    }

    override fun onClick() {
        super.onClick()
        android.util.Log.i("archon", "tile: rig music click")
        val t = qsTile ?: return
        if (t.state == Tile.STATE_UNAVAILABLE) return
        t.state = if (t.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE   // optimistic
        t.updateTile()
        scope.launch { Deck.post("/media/play-pause"); refresh(after = 2500) }   // the deck re-reads audio every 2 s
    }
}

/** Screens = headless mode (user's call, 2026-11-11): lit while the panels are on; a tap runs
 *  headless.sh on (outputs disabled, black whatever the input does, box and stream live) or off.
 *  Not DPMS: any mouse/key wakes DPMS on lightning. Per-monitor DPMS stays in the app. */
class ScreensTile : DeckTile() {
    override val label = "Screens"
    override val iconRes = R.drawable.ic_tile_screens

    override fun render(t: Tile, state: JsonObject) {
        val h = state.obj("headless")
        val physical = state.obj("hypr").list("monitors").count { it.str("name")?.startsWith("HEADLESS") == false }
        when {
            h == null || h.bool("available") == false -> t.set(Tile.STATE_UNAVAILABLE, label, "No headless script")
            h.bool("on") == true -> t.set(Tile.STATE_INACTIVE, label, "Headless")
            else -> t.set(Tile.STATE_ACTIVE, label, if (physical > 0) "$physical on" else "On")
        }
    }

    override fun onClick() {
        super.onClick()
        android.util.Log.i("archon", "tile: screens click")
        val t = qsTile ?: return
        if (t.state == Tile.STATE_UNAVAILABLE) return
        val goHeadless = t.state == Tile.STATE_ACTIVE
        t.state = if (goHeadless) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE      // optimistic
        t.subtitle = if (goHeadless) "Going headless…" else "Screens on…"
        t.updateTile()
        scope.launch {
            Deck.postJson("/headless", buildJsonObject { put("on", goHeadless) })   // returns when the script is done
            refresh(after = 2500)
        }
    }
}
