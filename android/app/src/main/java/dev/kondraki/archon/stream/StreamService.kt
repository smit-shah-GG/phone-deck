package dev.kondraki.archon.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import dev.kondraki.archon.MainActivity
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Palette
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.str
import dev.kondraki.archon.ui.Phosphor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The stream's foreground service (mediaPlayback, + microphone when the phone mic is on): it owns
 * the session through StreamEngine so audio survives the screen locking, and carries the system
 * media player, which on Android 13+ is also the lock-screen control (canvas Player board):
 *   slots 1-3 = the rig's player (play/pause, previous, next; empty when the rig has no player)
 *   slot 4    = the phone mic, mute/unmute (only when the session has the mic)
 *   slot 5    = Stop stream
 * Artwork: the rig's album art, else the phosphor card. Volume keys = phone volume (USAGE_MEDIA).
 */
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var art: Bitmap? = null
    private var artKey: String? = null
    private var micInFgs = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Stream", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "archon-stream").apply {
            setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = media("play-pause")
                override fun onPause() = media("play-pause")
                override fun onSkipToNext() = media("next")
                override fun onSkipToPrevious() = media("previous")
                override fun onStop() = stop(this@StreamService)
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) { A_STOP -> stop(this@StreamService); A_MIC -> StreamEngine.toggleMic() }
                }
            })
            setSessionActivity(PendingIntent.getActivity(this@StreamService, 0,
                Intent(this@StreamService, MainActivity::class.java).putExtra("panel", "stream")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        Deck.hold("stream")                                     // keep /ws live: now playing for the player
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "archon:stream")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "archon:stream")
            .apply { setReferenceCounted(false); acquire() }

        // the engine stopped from anywhere (tile, player, panel): the service goes with it
        scope.launch {
            var seenLive = false
            StreamEngine.status.collect { st ->
                if (st.phase != Phase.IDLE) seenLive = true
                else if (seenLive && !StreamEngine.active) stopSelf()
            }
        }
        // repaint the player on any change of: stream status, mic mute, the rig's now playing
        scope.launch {
            combine(StreamEngine.status, StreamEngine.micMuted,
                Deck.state.map { it.obj("audio").obj("now_playing") }.distinctUntilChanged()) { st, mic, np -> Triple(st, mic, np) }
                .collect { (st, mic, np) -> paint(st, mic, np) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_STOP -> { StreamEngine.stop(); stopSelf(); return START_NOT_STICKY }
            else -> {
                val o = intent?.getStringExtra("opts")?.let { Deck.json.decodeFromString(StreamOpts.serializer(), it) } ?: StreamEngine.opts
                foreground(o.mic)
                StreamEngine.apply(o)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (StreamEngine.active) StreamEngine.stop()
        scope.cancel()
        session.release()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        Deck.release("stream")
        super.onDestroy()
    }

    private fun foreground(mic: Boolean) {
        micInFgs = mic
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTE, notification("lightning", "connecting…", null), type)
    }

    private fun media(act: String) { scope.launch { Deck.post("/media/$act") } }

    private suspend fun paint(st: StreamStatus, micMuted: Boolean, np: kotlinx.serialization.json.JsonObject?) {
        val status = np.str("status").orEmpty()
        val hasPlayer = status == "Playing" || status == "Paused"
        val track = np.str("track")?.takeIf { it.isNotBlank() } ?: np.str("title")?.takeIf { it.isNotBlank() }
        val artist = np.str("artist")?.takeIf { it.isNotBlank() }
        val o = StreamEngine.opts

        val (title, sub) = when (st.phase) {
            Phase.CONNECTING -> "lightning" to "Connecting…"
            Phase.RECONNECTING -> "lightning" to "Reconnecting · rig unreachable"
            Phase.PAUSED -> "lightning" to "Video paused · resumes when Archon is on screen"
            else -> if (hasPlayer && track != null) track to listOfNotNull(artist, if (o.mic && !micMuted) "mic live" else "on lightning").joinToString(" · ")
                    else "lightning" to "Listening · " + when { st.videoOn -> "${o.monitor ?: "screen"} + audio"; o.mic -> "mic live"; else -> "audio only" }
        }

        // artwork: the rig's art when it has one, else the phosphor card for this state
        val key = np.str("art_key").orEmpty()
        val rigArt = if (hasPlayer && key.isNotEmpty()) {
            if (key != artKey) { artKey = key; art = Deck.art(key) }
            art
        } else null
        val bmp = rigArt ?: card(st, o, micMuted)

        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
            .build())

        val pbState = when {
            st.phase == Phase.CONNECTING || st.phase == Phase.RECONNECTING -> PlaybackState.STATE_CONNECTING
            st.phase == Phase.PAUSED || status == "Paused" -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        val actions = if (hasPlayer) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP else PlaybackState.ACTION_STOP
        val ps = PlaybackState.Builder().setState(pbState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).setActions(actions)
        if (o.mic) ps.addCustomAction(PlaybackState.CustomAction.Builder(A_MIC, if (micMuted) "Unmute mic" else "Mute mic",
            if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic).build())
        ps.addCustomAction(PlaybackState.CustomAction.Builder(A_STOP, "Stop stream", R.drawable.ic_stop).build())
        if (!hasPlayer) session.setExtras(Bundle().apply {          // keep slots 2-3 empty, not filled by custom actions
            putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS", true)
            putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT", true)
        }) else session.setExtras(Bundle())
        session.setPlaybackState(ps.build())

        getSystemService(NotificationManager::class.java).notify(NOTE, notification(title, sub, bmp))
        if (o.mic != micInFgs) foreground(o.mic)                  // the mic switched mid-session
    }

    private fun card(st: StreamStatus, o: StreamOpts, micMuted: Boolean): Bitmap {
        val ph = palette()
        val warm = st.phase != Phase.LIVE
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val (word, detail) = when {
            st.phase == Phase.CONNECTING -> "CONNECTING" to "offer sent\nwaiting for the rig"
            st.phase == Phase.RECONNECTING -> "SIGNAL LOST" to "rig unreachable\nretrying"
            st.phase == Phase.PAUSED -> "PAUSED" to "video only\nresumes on screen"
            st.videoOn -> "STREAMING" to "${o.monitor ?: "screen"} · ${QUALITY[o.quality] ?: o.quality}\naudio + video"
            o.mic && !micMuted -> "MIC LIVE" to "phone mic → rig\n" + if (o.listen) "audio both ways" else "send only"
            else -> "LISTENING" to "audio only\nphone · $time"
        }
        return ArtCard.render(this, word, detail, warm, ph.crt, ph.primary.toArgb(), ph.secondary.toArgb(), ph.on.toArgb(), ph.onVar.toArgb())
    }

    private fun palette(): Phosphor = runCatching {
        getSharedPreferences("ui", Context.MODE_PRIVATE).getString("palette", null)
            ?.let { Phosphor.from(Deck.json.decodeFromString(Palette.serializer(), it)) }
    }.getOrNull() ?: Phosphor()

    private fun notification(title: String, text: String, large: Bitmap?): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.emblem)                      // the SCP emblem everywhere (#127)
            .setContentTitle(title).setContentText(text).setLargeIcon(large)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL = "stream"
        private const val NOTE = 7
        private const val A_STOP = "stop"
        private const val A_MIC = "mic"
        val QUALITY = mapOf("low" to "540p", "medium" to "720p", "high" to "1080p")

        /** Start the session, or apply new options to it. Call while the app is visible when the
         *  options include the mic (Android only grants microphone services from the foreground). */
        fun apply(ctx: Context, o: StreamOpts) {
            ctx.startForegroundService(Intent(ctx, StreamService::class.java)
                .putExtra("opts", Deck.json.encodeToString(StreamOpts.serializer(), o)))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(A_STOP))
        }
    }
}
