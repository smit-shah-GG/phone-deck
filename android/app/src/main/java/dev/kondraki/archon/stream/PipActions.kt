package dev.kondraki.archon.stream

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.kondraki.archon.R

/** Picture-in-picture buttons (canvas PiP board): the phone mic (only when the session has it)
 *  and Stop. Expanding back to the app is Android's own control. Not exported: only the
 *  system's PiP menu, through these PendingIntents, can fire them. */
class PipActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            MIC -> StreamEngine.toggleMic()
            STOP -> StreamEngine.stop()
        }
    }

    companion object {
        private const val MIC = "dev.kondraki.archon.pip.MIC"
        private const val STOP = "dev.kondraki.archon.pip.STOP"

        fun actions(ctx: Context): List<RemoteAction> {
            fun pi(action: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
                Intent(ctx, PipActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
            val out = mutableListOf<RemoteAction>()
            if (StreamEngine.opts.mic) {
                val muted = StreamEngine.micMuted.value
                out += RemoteAction(Icon.createWithResource(ctx, if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                    if (muted) "Unmute mic" else "Mute mic", if (muted) "Unmute the phone mic" else "Mute the phone mic", pi(MIC, 10))
            }
            out += RemoteAction(Icon.createWithResource(ctx, R.drawable.ic_stop), "Stop", "Stop the stream", pi(STOP, 11))
            return out
        }
    }
}
