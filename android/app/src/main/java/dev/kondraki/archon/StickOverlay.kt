package dev.kondraki.archon

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.widget.RcGeom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The remote's stick, awake, as a FLOATING WINDOW (user's call, 2026-11-11). As an activity,
 * closing it counted as "an app closed" and the Pixel launcher jumped to its home page (the
 * drop-down close animation). A TYPE_APPLICATION_OVERLAY window never opens or closes an app:
 * the launcher stays resumed underneath and notices nothing.
 *
 * Needs "Display over other apps"; without it the widget falls back to StickActivity. Same
 * surface and rules as StickActivity: it covers only the stick block, any outside touch ends
 * it (and still reaches what's under it), and so do 30 s idle and the screen going off.
 */
object StickOverlay {
    private var root: View? = null
    private var owner: OverlayOwner? = null
    private var idle: Job? = null
    private var leftDown = false
    private val main = CoroutineScope(Dispatchers.Main)
    private var screenOff: BroadcastReceiver? = null

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, b: Rect) {
        hide(ctx)
        if (Deck.session.token.value == null || !allowed(ctx)) return
        dev.kondraki.archon.data.InputLink.close()               // a fresh /ws/input per wake (#145)
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val d = app.resources.displayMetrics.density
        val extra = ((RcGeom.LR_GAP_DP + RcGeom.LR_H_DP) * d).roundToInt()
        val o = OverlayOwner().also { it.start() }
        val compose = ComposeView(app).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(d, fontScale = 1f)) {
                    Stick(stickHeightPx = b.height(), onTouch = { poke(app) }, onLeft = { down ->
                        leftDown = down; send(if (down) "jdown" else "jup") { put("b", "left") }
                    })
                }
            }
        }
        @SuppressLint("ViewConstructor")
        val frame = object : FrameLayout(app) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { hide(app); return false }   // never read as stick input
                return super.dispatchTouchEvent(ev)
            }
        }
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        frame.addView(compose)
        val lp = WindowManager.LayoutParams(b.width(), b.height() + extra,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left; y = b.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
        }
        runCatching { wm.addView(frame, lp) }.onFailure { o.stop(); return }
        root = frame; owner = o
        screenOff = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = hide(app) }
            .also { app.registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        poke(app)
        android.util.Log.i("archon", "stick overlay at $b")
    }

    fun hide(ctx: Context) {
        val app = ctx.applicationContext
        idle?.cancel(); idle = null
        screenOff?.let { runCatching { app.unregisterReceiver(it) } }; screenOff = null
        if (leftDown) { send("jup") { put("b", "left") }; leftDown = false }
        root?.let { r -> runCatching { app.getSystemService(WindowManager::class.java).removeViewImmediate(r) } }
        root = null
        owner?.stop(); owner = null
    }

    private fun poke(app: Context) {
        idle?.cancel()
        idle = main.launch { delay(30_000); hide(app) }
    }
}

/** A window with no activity still needs a lifecycle + saved state for Compose to run in it. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { saved.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun stop() { if (reg.currentState != Lifecycle.State.INITIALIZED) reg.currentState = Lifecycle.State.DESTROYED }
}

/** The widget's stick tap, as a broadcast (its fill-in carries the tap's screen bounds). */
class StickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = intent.sourceBounds ?: return
        StickOverlay.show(context, b)
    }
}
