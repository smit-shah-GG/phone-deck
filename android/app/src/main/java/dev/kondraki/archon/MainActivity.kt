package dev.kondraki.archon

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.MotionEvent
import androidx.glance.appwidget.updateAll
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.kondraki.archon.ui.Cog
import dev.kondraki.archon.ui.Cogitator
import dev.kondraki.archon.ui.VoiceLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.stream.StreamEngine
import dev.kondraki.archon.data.Palette
import dev.kondraki.archon.ui.LocalPh
import dev.kondraki.archon.ui.Opening
import dev.kondraki.archon.ui.Phosphor
import dev.kondraki.archon.ui.PinScreen
import dev.kondraki.archon.ui.Shell
import dev.kondraki.archon.ui.Skin
import dev.kondraki.archon.ui.VideoSurface
import androidx.compose.foundation.background
import dev.kondraki.archon.ui.crt
import kotlinx.coroutines.launch

/**
 * One activity: PIN (first run) → fingerprint (the Archon opening screen) → the shell.
 * The fingerprint gates the UI on a cold start and after 5 minutes in the background; the
 * live /ws connection runs only while the activity is visible.
 */
class MainActivity : ComponentActivity() {
    private val locked = mutableStateOf(true)
    private val inPip = mutableStateOf(false)
    private val ph get() = Skin.ph
    private var stoppedAt = 0L
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)   // edge to edge: no inset for the cutout
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())          // fullscreen, like the PWA
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // the PWA's wake lock
        // Draw under the camera cutout like the fullscreen PWA does; otherwise landscape loses
        // ~90 px on the punch-hole side (measured on the M52 2026-11-11).
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        Skin.loadCached(this)
        // PiP (#122 step 4): auto-enter on leaving the app while video streams; buttons follow the
        // session (mic present / muted)
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(StreamEngine.video, StreamEngine.micMuted, StreamEngine.status) { v, _, _ -> v != null }
                .collect { hasVideo -> updatePip(hasVideo) }
        }
        // Debug builds only: adb can hand over a token and skip the fingerprint, so the app can
        // be driven over adb. Release builds compile this out (BuildConfig.DEBUG is a constant).
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("token")?.let { Deck.session.adopt(it) }
            if (intent.getBooleanExtra("unlocked", false)) locked.value = false
            // --ez pinWidget true: ask the launcher to place the Remote widget (its own Add dialog)
            if (intent.getBooleanExtra("pinWidget", false))
                android.appwidget.AppWidgetManager.getInstance(this).requestPinAppWidget(
                    android.content.ComponentName(this, dev.kondraki.archon.widget.RemoteReceiver::class.java), null, null)
        }

        setContent {
            val token by Deck.session.token.collectAsState()
            val link by Deck.link.collectAsState()
            val state by Deck.state.collectAsState()
            val isLocked by locked
            val pip by inPip
            val video by StreamEngine.video.collectAsState()
            // stopped from inside PiP: nothing left to show, so close the PiP window
            LaunchedEffect(pip, video) { if (pip && video == null) moveTaskToBack(true) }
            LaunchedEffect(token) { if (token != null) Deck.session.refreshIfDue() }
            // fontScale 1: the PWA's text ignores the phone's font-size setting (the M52 is at 0.8,
            // which shrank every label to 80% here), so the app does too: identical to the deck.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalPh provides ph.value, LocalDensity provides Density(d.density, fontScale = 1f)) {
                Box(Modifier.fillMaxSize().crt(ph.value.crt)) {
                    val v = video
                    when {
                        pip && v != null -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                            contentAlignment = androidx.compose.ui.Alignment.Center) { VideoSurface(v, crosshair = false, touch = false) }
                        token == null -> PinScreen()
                        isLocked -> Opening(link) { askFingerprint() }
                        else -> Shell(state, link)
                    }
                    // the cogitator sits over the unlocked shell; any touch exits (dispatchTouchEvent)
                    if (Cog.on && !pip && token != null && !isLocked) Cogitator(state)
                    // burn-in guard (#123 F): after 10 min in the cogitator, dim this window to 15%
                    LaunchedEffect(Cog.on) {
                        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                        if (Cog.on) {
                            delay(10 * 60_000L)
                            window.attributes = window.attributes.apply { screenBrightness = 0.15f }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Cog.touched()                                             // coming back counts as activity
        // redraw the Remote widget: its stick picks overlay vs activity by the overlay permission
        lifecycleScope.launch { runCatching { dev.kondraki.archon.widget.RemoteControlWidget().updateAll(this@MainActivity) } }
        // the cogitator's idle timer (app.js: every 5 s), held off while voice records, the rig's
        // screen is showing (video), PiP, or the app is locked (#123 F)
        idleJob?.cancel()
        idleJob = lifecycleScope.launch {
            runCatching { Cog.loadCfg() }
            var n = 0
            while (true) {
                delay(5_000)
                if (++n % 12 == 0) runCatching { Cog.loadCfg() }         // the System panel may have changed it
                if (!Cog.on && !locked.value && Deck.session.token.value != null && !inPip.value && !VoiceLog.busy &&
                    StreamEngine.video.value == null && System.currentTimeMillis() - Cog.lastTouch > Cog.idleMin * 60_000L) Cog.show()
            }
        }
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_MS) locked.value = true
        Deck.hold("ui")
        StreamEngine.setVisible(true)
        lifecycleScope.launch { if (Deck.session.token.value != null) runCatching { ShortcutActivity.refresh(this@MainActivity) }
            .onFailure { android.util.Log.w("archon", "shortcuts not refreshed", it) } }
        lifecycleScope.launch { Skin.refresh(this@MainActivity) }
    }

    override fun onResume() {
        super.onResume()
        if (locked.value && Deck.session.token.value != null) askFingerprint()
    }

    /** Any touch is activity; a touch while the cogitator is up only exits it, and the whole
     *  gesture is swallowed so it never clicks through to the shell (app.js cogEatClick). */
    private var idleJob: Job? = null
    private var swallowing = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Cog.touched()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Cog.on) { Cog.hide(); swallowing = true }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        Deck.release("ui")
        dev.kondraki.archon.data.InputLink.close()
        StreamEngine.setVisible(false)        // no surface shows the video now: drop it, keep audio (#122)
        super.onStop()
    }

    private fun updatePip(hasVideo: Boolean) {
        setPictureInPictureParams(android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .setAutoEnterEnabled(hasVideo)
            .setSeamlessResizeEnabled(true)
            .setActions(if (hasVideo) dev.kondraki.archon.stream.PipActions.actions(this) else emptyList())
            .build())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** Android's own fingerprint sheet ("Unlock Archon"), falling back to the phone's PIN. A
     *  phone with no lock screen set up has nothing to ask, so it just opens. */
    private fun askFingerprint() {
        if (prompting || !locked.value) return
        val auth = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            locked.value = false; return
        }
        prompting = true
        BiometricPrompt.Builder(this)
            .setTitle("Unlock Archon")
            .setSubtitle("Use your fingerprint")
            .setAllowedAuthenticators(auth)
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false; locked.value = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false                        // cancelled: stay on the opening screen; tap the emblem
                }
            })
    }

    private companion object { const val RELOCK_MS = 5 * 60 * 1000L }
}
