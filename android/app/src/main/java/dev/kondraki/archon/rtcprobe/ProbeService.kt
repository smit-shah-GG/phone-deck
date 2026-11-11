package dev.kondraki.archon.rtcprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.os.PowerManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R0 probe: owns the WebRTC session in a mediaPlayback foreground service, so audio
 * should survive the screen locking (the PWA's dies 4-5 s after, measured 2026-11-11).
 *
 * Same handshake as the PWA's streamConnect(): recvonly audio + video transceivers,
 * gather ICE fully (aiortc is non-trickle), POST the offer to /audio/webrtc/offer with
 * the deck_session cookie, apply the answer. The deck URL, token and monitor arrive as
 * intent extras so no tailnet name or credential ever lands in this (public) source.
 */
class ProbeService : Service() {
    private var pc: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var wake: PowerManager.WakeLock? = null
    private var url = ""
    private var token = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        url = intent?.getStringExtra("url").orEmpty().trimEnd('/')
        token = intent?.getStringExtra("token").orEmpty()
        val monitor = intent?.getStringExtra("monitor") ?: "DP-3"
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deckprobe:stream").apply { acquire(60 * 60 * 1000L) }
        if (url.isEmpty() || token.isEmpty()) { Probe.log("missing url/token extras"); return START_NOT_STICKY }
        Thread({ runCatching { connect(monitor) }.onFailure { Probe.log("connect failed: $it") } }, "probe-connect").start()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "deck probe", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "probe").setContentTitle("deck probe")
            .setContentText("streaming from the rig").setSmallIcon(android.R.drawable.ic_media_play).build()
    }

    private fun connect(monitor: String) {
        val t0 = System.currentTimeMillis()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())   // media volume, not call volume
            .createAudioDeviceModule()
        val ctx = Probe.egl.eglBaseContext
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(ctx))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(ctx, true, true))
            .createPeerConnectionFactory()
        factory = f

        val gathered = CountDownLatch(1)
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                Probe.log("ice gathering: $s (+${System.currentTimeMillis() - t0}ms)")
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) =
                Probe.log("connection: $s (+${System.currentTimeMillis() - t0}ms)")
            override fun onTrack(tr: RtpTransceiver) {
                val track = tr.receiver.track()
                Probe.log("track: ${track?.kind()}")
                if (track is VideoTrack) {
                    track.addSink(Probe.frameCounter)
                    Probe.videoTrack = track
                    Probe.onVideo?.invoke(track)
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Probe.log("ice: $s")
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: org.webrtc.IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out org.webrtc.IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("createPeerConnection returned null")
        pc = p
        val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)

        val offer = sdp { obs -> p.createOffer(obs, MediaConstraints()) }
        sdpSet { obs -> p.setLocalDescription(obs, offer) }
        gathered.await(15, TimeUnit.SECONDS)
        Probe.log("posting offer (+${System.currentTimeMillis() - t0}ms)")

        val body = JSONObject().put("sdp", p.localDescription.description).put("type", "offer")
            .put("listen", true).put("mic", false).put("phoneOnly", false)
            .put("video", monitor).put("videoQuality", "medium").toString()
        val req = Request.Builder().url("$url/audio/webrtc/offer")
            .header("Cookie", "deck_session=$token")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val ans = OkHttpClient().newCall(req).execute().use { r ->
            check(r.isSuccessful) { "offer HTTP ${r.code}" }
            JSONObject(r.body.string())
        }
        sdpSet { obs -> p.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, ans.getString("sdp"))) }
        Probe.log("answer applied (+${System.currentTimeMillis() - t0}ms)")
    }

    override fun onDestroy() {
        Probe.log("stopping")
        Probe.videoTrack = null
        pc?.dispose(); pc = null
        factory?.dispose(); factory = null
        wake?.let { if (it.isHeld) it.release() }
        if (url.isNotEmpty()) Thread {
            runCatching {
                OkHttpClient().newCall(Request.Builder().url("$url/audio/webrtc/stop")
                    .header("Cookie", "deck_session=$token")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().close()
            }
        }.start()
        super.onDestroy()
    }

    // --- tiny blocking adapters over the callback SDP API (we're on a worker thread) ---
    private fun sdp(start: (SdpObserver) -> Unit): SessionDescription {
        var out: SessionDescription? = null; var err: String? = null
        val done = CountDownLatch(1)
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { out = d; done.countDown() }
            override fun onCreateFailure(e: String) { err = e; done.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String) {}
        })
        done.await(10, TimeUnit.SECONDS)
        return out ?: error("createOffer failed: $err")
    }

    private fun sdpSet(start: (SdpObserver) -> Unit) {
        var err: String? = null
        val done = CountDownLatch(1)
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
            override fun onSetSuccess() { done.countDown() }
            override fun onSetFailure(e: String) { err = e; done.countDown() }
        })
        done.await(10, TimeUnit.SECONDS)
        err?.let { error("set description failed: $it") }
    }
}
