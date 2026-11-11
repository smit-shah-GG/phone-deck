package dev.kondraki.phonedeck.rtcprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * R0 probe: renders the stream; owns nothing. Launch with extras url / token / monitor
 * (forwarded to ProbeService). Leaving or locking only drops the renderer: the service
 * keeps the session, which is the point of the test.
 */
class ProbeActivity : Activity() {
    private lateinit var view: SurfaceViewRenderer
    private var attached: VideoTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = SurfaceViewRenderer(this).apply {
            init(Probe.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
        setContentView(view)
        if (intent.hasExtra("token")) {
            startForegroundService(Intent(this, ProbeService::class.java).putExtras(intent))
        }
    }

    override fun onStart() {
        super.onStart()
        Probe.onVideo = { t -> runOnUiThread { attach(t) } }
        Probe.videoTrack?.let { attach(it) }
    }

    override fun onStop() {
        Probe.onVideo = null
        attached?.removeSink(view); attached = null
        super.onStop()
    }

    override fun onDestroy() { view.release(); super.onDestroy() }

    private fun attach(t: VideoTrack) {
        if (attached === t) return
        attached?.removeSink(view)
        t.addSink(view); attached = t
        Probe.log("renderer attached")
    }
}
