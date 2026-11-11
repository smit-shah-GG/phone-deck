package dev.kondraki.archon.stream

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

// The focus-mode gamepad's maths, 1:1 with app.js initFocus() (#80, user-tuned 2026-11-10),
// pure so the JVM tests pin it. Sizes are CSS px = dp.

object Gamepad {
    const val DEAD = 0.08f          // stick deadzone (fraction of the radius)
    const val SPEED = 0.25f         // screen widths per second at full push (tuned 1.0 → 0.5 → 0.25)
    const val TICK_MS = 33L         // jmove rate while the stick is held (~30 Hz)
    const val SCROLL_PX = 18f       // one wheel step per 18 dp of drag
    const val KNOB_TRAVEL = 0.6f    // the knob moves up to 0.6 R from the centre

    /** Finger position → stick deflection, clamped to the unit circle. */
    fun deflect(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
        var x = dx / radius; var y = dy / radius
        val d = hypot(x, y)
        if (d > 1f) { x /= d; y /= d }
        return x to y
    }

    /** One tick's jmove: linear in deflection beyond the deadzone; the same px speed on both
     *  axes (dfy scaled by the video's aspect). null inside the deadzone. */
    fun move(jx: Float, jy: Float, dtSec: Float, aspect: Float): Pair<Float, Float>? {
        val d = hypot(jx, jy)
        if (d < DEAD) return null
        val k = (d - DEAD) / (1f - DEAD) / d * SPEED * dtSec.coerceAtMost(0.1f)
        return jx * k to jy * k * aspect
    }

    /** The scroll pad: accumulate vertical drag, emit wheel steps of SCROLL_PX. Finger UP
     *  (negative dy) = wheel step -1, the phone's natural direction (the user reversed the
     *  trackpad's convention here). Returns the steps and the remainder. */
    fun scroll(acc: Float): Pair<List<Int>, Float> {
        var a = acc
        val out = mutableListOf<Int>()
        while (abs(a) >= SCROLL_PX) {
            val s = sign(a)
            out += if (s < 0) -1 else 1
            a -= s * SCROLL_PX
        }
        return out to a
    }
}

/**
 * Touch-on-video (app.js vid pointer handlers): a tap clicks where you tapped, holding 500 ms
 * without moving right-clicks, moving more than 6 dp turns it into a left-button drag.
 * Feed it events; it says what to send. Every gesture starts by moving the cursor there.
 */
class VideoGesture {
    enum class Send { MOVE, DOWN, UP, CLICK, RCLICK }

    private var downAt: Pair<Float, Float>? = null
    private var moved = false
    private var dragging = false
    private var done = false             // right-click already fired: the rest of the touch is ignored

    fun down(x: Float, y: Float): List<Send> { downAt = x to y; moved = false; dragging = false; done = false; return listOf(Send.MOVE) }

    fun hold(): List<Send> =             // the 500 ms timer fired
        if (downAt != null && !moved && !done) { done = true; listOf(Send.RCLICK) } else emptyList()

    fun move(x: Float, y: Float): List<Send> {
        val start = downAt ?: return emptyList()
        if (done) return emptyList()
        if (!moved && hypot(x - start.first, y - start.second) > 6f) { moved = true; dragging = true; return listOf(Send.DOWN, Send.MOVE) }
        return if (moved) listOf(Send.MOVE) else emptyList()
    }

    fun up(): List<Send> {
        if (downAt == null) return emptyList()
        val out = when {
            done -> emptyList()
            dragging -> listOf(Send.UP)
            !moved -> listOf(Send.CLICK)
            else -> emptyList()
        }
        downAt = null; moved = false; dragging = false; done = false
        return out
    }

    val isDragging get() = dragging
}
