package dev.kondraki.archon.stream

import android.content.Context
import android.media.AudioAttributes
import dev.kondraki.archon.data.Deck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Stream panel's switches (app.js streamOpts + the two segs). Remembered between sessions
 *  (#122 decision 3). */
@Serializable
data class StreamOpts(
    val listen: Boolean = true,
    val mic: Boolean = false,
    val phoneOnly: Boolean = false,
    val video: Boolean = false,
    val monitor: String? = null,
    val quality: String = "medium",
) {
    val anyDirection get() = listen || mic || video
}

enum class Phase { IDLE, CONNECTING, LIVE, RECONNECTING }

data class StreamStatus(val phase: Phase = Phase.IDLE, val note: String = "Idle", val videoOn: Boolean = false)

/**
 * The one stream session in the process (tasks #122). Same handshake as app.js streamConnect():
 * transceivers per the switches, full ICE gather (aiortc is non-trickle), POST
 * /audio/webrtc/offer, apply the answer. Owned by StreamService (a foreground service) so it
 * outlives the screen; the UI only renders [video] and [status].
 *
 * - option changes while live: reconnect 400 ms after the last change (app.js streamOptionChanged)
 * - video only while something shows it (#122 decision 1): [setVisible] renegotiates
 * - a dropped session retries with backoff 2 s → 2 min until Stop (#122 decision 4)
 */
object StreamEngine {
    private lateinit var app: Context
    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status
    private val _video = MutableStateFlow<VideoTrack?>(null)
    val video: StateFlow<VideoTrack?> = _video
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted

    @Volatile var opts = StreamOpts(); private set
    @Volatile private var visible = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    val active get() = job?.isActive == true

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Start, or apply new options to, the session. */
    @Synchronized fun apply(o: StreamOpts) {
        val wasActive = active
        opts = o
        restart(if (wasActive) 400 else 0)
    }

    @Synchronized fun stop() {
        job?.cancel(); job = null
        scope.launch {
            withContext(Dispatchers.Main) { close() }
            Deck.post("/audio/webrtc/stop")
        }
        _status.value = StreamStatus()
    }

    /** The app is (not) on screen: drop or restore the video (only when the session wants video). */
    @Synchronized fun setVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        if (active && opts.video) restart(0)
    }

    /** Adopt options without reconnecting: the picker followed a capture the server moved. */
    fun follow(o: StreamOpts) { opts = o }

    fun toggleMic() {
        _micMuted.value = !_micMuted.value
        micTrack?.setEnabled(!_micMuted.value)
    }

    private fun restart(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            var backoff = 2_000L
            var first = true
            while (isActive) {
                _status.value = StreamStatus(if (first) Phase.CONNECTING else Phase.RECONNECTING,
                    if (first) "connecting…" else "reconnecting…")
                val wasLive = runCatching { session() }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _status.value = StreamStatus(Phase.RECONNECTING, "error: ${e.message ?: e.javaClass.simpleName}")
                    false
                }
                if (!isActive) break
                if (wasLive) backoff = 2_000L
                _status.value = StreamStatus(Phase.RECONNECTING, "reconnecting in ${backoff / 1000} s…")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
                first = false
            }
        }
    }

    /** One session: returns when it ends (failed, or disconnected for 5 s); true if it went live. */
    private suspend fun session(): Boolean {
        withContext(Dispatchers.Main) { close() }
        val o = opts
        val wantVideo = o.video && visible
        val f = factory()
        val gathered = CompletableDeferred<Unit>()
        val conn = MutableStateFlow<PeerConnection.PeerConnectionState?>(null)
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { conn.value = s }
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? VideoTrack)?.let { _video.value = it }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("createPeerConnection returned null")
        pc = p

        val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        if (o.mic) {
            // one bidirectional m-line when also listening (app.js: aiortc.addTrack reuses the
            // first audio transceiver it finds)
            val src = f.createAudioSource(MediaConstraints()).also { micSource = it }
            val track = f.createAudioTrack("phone-mic", src).also { micTrack = it }
            track.setEnabled(!_micMuted.value)
            p.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(
                if (o.listen) RtpTransceiver.RtpTransceiverDirection.SEND_RECV else RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        } else if (o.listen) {
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
        }
        if (wantVideo) p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)

        val offer = sdp { p.createOffer(it, MediaConstraints()) }
        set { p.setLocalDescription(it, offer) }
        withTimeout(10_000) { gathered.await() }

        val body = buildJsonObject {
            put("sdp", p.localDescription.description); put("type", "offer")
            put("listen", o.listen); put("mic", o.mic); put("phoneOnly", o.phoneOnly)
            if (wantVideo) put("video", o.monitor ?: "") else put("video", null as String?)
            put("videoQuality", o.quality)
        }
        val answer = withContext(Dispatchers.IO) {
            Deck.http.newCall(Request.Builder().url("${Deck.url}/audio/webrtc/offer")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                check(r.isSuccessful) { if (r.code == 409) "superseded" else "offer rejected (HTTP ${r.code})" }
                (Deck.json.parseToJsonElement(r.body.string()).jsonObject["sdp"] as JsonPrimitive).content
            }
        }
        set { p.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }

        // watch the connection until it ends
        var live = false
        var downSince = 0L
        val started = System.currentTimeMillis()
        while (true) {
            when (conn.value) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (!live) { live = true; _status.value = StreamStatus(Phase.LIVE, "● connected", wantVideo) }
                    downSince = 0
                }
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> return live
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (downSince == 0L) downSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - downSince > 5_000) return live
                }
                else -> if (!live && System.currentTimeMillis() - started > 20_000) return false   // never connected
            }
            delay(250)
        }
    }

    private fun close() {
        _video.value = null
        pc?.dispose(); pc = null
        micTrack?.dispose(); micTrack = null
        micSource?.dispose(); micSource = null
    }

    private fun factory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())   // media volume: the volume keys work
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory().also { factory = it }
    }

    // ---- callback SDP API → suspend
    private suspend fun sdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { c ->
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String) { c.resumeWithException(IllegalStateException("createOffer: $e")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String) {}
        })
    }

    private suspend fun set(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { c ->
        start(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
            override fun onSetSuccess() { c.resume(Unit) }
            override fun onSetFailure(e: String) { c.resumeWithException(IllegalStateException("set description: $e")) }
        })
    }
}
