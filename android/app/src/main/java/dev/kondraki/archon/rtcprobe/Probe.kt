package dev.kondraki.archon.rtcprobe

import android.util.Log
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * R0 probe (tasks #118): process-wide state shared by ProbeService (which owns the
 * WebRTC session, so it outlives the screen) and ProbeActivity (which only renders).
 * Everything logs under the "deckprobe" tag so the run can be read with adb logcat.
 */
object Probe {
    const val TAG = "deckprobe"

    val egl: EglBase by lazy { EglBase.create() }

    @Volatile var videoTrack: VideoTrack? = null
    @Volatile var onVideo: ((VideoTrack) -> Unit)? = null

    /** Counts decoded frames whether or not anything is on screen: proves video keeps
     *  flowing with the activity gone (screen locked), and lets logcat show the rate. */
    val frameCounter = object : VideoSink {
        private var n = 0L
        private var since = System.currentTimeMillis()
        override fun onFrame(frame: VideoFrame) {
            n++
            val now = System.currentTimeMillis()
            if (now - since >= 5000) {
                Log.i(TAG, "video: ${n} frames in ${now - since}ms (${frame.buffer.width}x${frame.buffer.height})")
                n = 0; since = now
            }
        }
    }

    fun log(msg: String) { Log.i(TAG, msg) }
}
