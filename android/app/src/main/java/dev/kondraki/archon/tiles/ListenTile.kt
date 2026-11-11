package dev.kondraki.archon.tiles

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kondraki.archon.R
import dev.kondraki.archon.ShortcutActivity
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.stream.Phase
import dev.kondraki.archon.stream.StreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listen (canvas Main board): tap = audio-only listening on this phone, tap again = stop.
 * Starting on a securely locked phone asks for unlock first (#121 decision 2; whoever holds
 * the phone must not hear the rig); stopping never does. The label carries the state
 * (Listen / Listening) so One UI's circle reads alone.
 */
class ListenTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch { StreamEngine.status.collect { paint() } }
    }

    override fun onStopListening() { watch?.cancel(); watch = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun paint() {
        val t = qsTile ?: return
        val st = StreamEngine.status.value
        val (state, label, sub) = when {
            Deck.session.token.value == null -> Triple(Tile.STATE_UNAVAILABLE, "Listen", "Sign in to Archon")
            st.phase == Phase.LIVE -> Triple(Tile.STATE_ACTIVE, "Listening",
                if (st.videoOn) "Audio + video" else if (StreamEngine.opts.mic) "Audio + mic" else "Audio only")
            st.phase == Phase.CONNECTING -> Triple(Tile.STATE_ACTIVE, "Listen", "Connecting…")
            st.phase == Phase.RECONNECTING -> Triple(Tile.STATE_ACTIVE, "Listen", "Reconnecting…")
            else -> Triple(Tile.STATE_INACTIVE, "Listen", if (isLocked && isSecure) "Unlock to start" else "Off")
        }
        t.state = state; t.label = label; t.subtitle = sub
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_listen)
        t.contentDescription = "$label, $sub"
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (qsTile?.state == Tile.STATE_UNAVAILABLE) return
        if (StreamEngine.active) { StreamEngine.stop(); return }       // stopping is always allowed
        if (isLocked && isSecure) unlockAndRun { start() } else start()
    }

    /** Through ShortcutActivity: a visible (if invisible-looking) activity may always start the
     *  media foreground service, which a tile alone isn't guaranteed. */
    @Suppress("DEPRECATION")
    private fun start() {
        val i = Intent(this, ShortcutActivity::class.java).setAction("dev.kondraki.archon.SHORTCUT")
            .putExtra("do", "listen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
