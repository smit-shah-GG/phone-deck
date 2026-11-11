package dev.kondraki.archon

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
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
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.stream.StreamEngine
import dev.kondraki.archon.data.Palette
import dev.kondraki.archon.ui.LocalPh
import dev.kondraki.archon.ui.Opening
import dev.kondraki.archon.ui.Phosphor
import dev.kondraki.archon.ui.PinScreen
import dev.kondraki.archon.ui.Shell
import dev.kondraki.archon.ui.crt
import kotlinx.coroutines.launch

/**
 * One activity: PIN (first run) → fingerprint (the Archon opening screen) → the shell.
 * The fingerprint gates the UI on a cold start and after 5 minutes in the background; the
 * live /ws connection runs only while the activity is visible.
 */
class MainActivity : ComponentActivity() {
    private val locked = mutableStateOf(true)
    private val ph = mutableStateOf(Phosphor())
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
        cachedPalette()?.let { ph.value = Phosphor.from(it) }
        // Debug builds only: adb can hand over a token and skip the fingerprint, so the app can
        // be driven over adb. Release builds compile this out (BuildConfig.DEBUG is a constant).
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("token")?.let { Deck.session.adopt(it) }
            if (intent.getBooleanExtra("unlocked", false)) locked.value = false
        }

        setContent {
            val token by Deck.session.token.collectAsState()
            val link by Deck.link.collectAsState()
            val state by Deck.state.collectAsState()
            val isLocked by locked
            LaunchedEffect(token) { if (token != null) Deck.session.refreshIfDue() }
            // fontScale 1: the PWA's text ignores the phone's font-size setting (the M52 is at 0.8,
            // which shrank every label to 80% here), so the app does too: identical to the deck.
            val d = LocalDensity.current
            CompositionLocalProvider(LocalPh provides ph.value, LocalDensity provides Density(d.density, fontScale = 1f)) {
                Box(Modifier.fillMaxSize().crt(ph.value.crt)) {
                    when {
                        token == null -> PinScreen()
                        isLocked -> Opening(link) { askFingerprint() }
                        else -> Shell(state, link)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_MS) locked.value = true
        Deck.hold("ui")
        StreamEngine.setVisible(true)
        lifecycleScope.launch { if (Deck.session.token.value != null) runCatching { ShortcutActivity.refresh(this@MainActivity) } }
        lifecycleScope.launch { runCatching { Deck.palette() }.onSuccess { savePalette(it); ph.value = Phosphor.from(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (locked.value && Deck.session.token.value != null) askFingerprint()
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        Deck.release("ui")
        StreamEngine.setVisible(false)        // no surface shows the video now: drop it, keep audio (#122)
        super.onStop()
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

    // The last palette, so a cold start paints the right colours before /palette answers.
    private fun cachedPalette(): Palette? = runCatching {
        getSharedPreferences("ui", Context.MODE_PRIVATE).getString("palette", null)
            ?.let { Deck.json.decodeFromString(Palette.serializer(), it) }
    }.getOrNull()

    private fun savePalette(p: Palette) {
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("palette", Deck.json.encodeToString(Palette.serializer(), p)).apply()
    }

    private companion object { const val RELOCK_MS = 5 * 60 * 1000L }
}
