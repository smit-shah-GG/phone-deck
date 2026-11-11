package dev.kondraki.archon.stream

import dev.kondraki.archon.stream.VideoGesture.Send
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.hypot

class GamepadTest {
    private fun near(a: Float, b: Float) = assertEquals(b.toDouble(), a.toDouble(), 1e-5)

    @Test fun deflectionIsClampedToTheCircle() {
        val (x, y) = Gamepad.deflect(300f, 400f, 100f)
        near(hypot(x, y), 1f); near(x, 0.6f); near(y, 0.8f)
        val (a, b) = Gamepad.deflect(10f, 0f, 100f)
        near(a, 0.1f); near(b, 0f)
    }

    @Test fun deadzoneSendsNothing() {
        assertNull(Gamepad.move(0.05f, 0.05f, 0.033f, 16f / 9))
    }

    @Test fun fullPushIsAQuarterScreenPerSecondLinear() {
        val (dx, _) = Gamepad.move(1f, 0f, 1f / 10, 16f / 9)!!          // dt capped at 0.1 s
        near(dx, 0.025f)                                                  // 0.25 w/s × 0.1 s
        val (half, _) = Gamepad.move(0.54f, 0f, 0.1f, 16f / 9)!!          // linear past the deadzone
        near(half, (0.54f - 0.08f) / 0.92f * 0.25f * 0.1f)
        val (_, dy) = Gamepad.move(0f, 1f, 0.1f, 16f / 9)!!              // same px speed vertically
        near(dy, 0.025f * 16f / 9)
    }

    @Test fun dtIsCappedSoAStallDoesNotJump() {
        val (dx, _) = Gamepad.move(1f, 0f, 2f, 1f)!!
        near(dx, 0.025f)
    }

    @Test fun scrollStepsEvery18AndFingerUpIsWheelDown() {
        val (steps, rest) = Gamepad.scroll(-40f)                          // finger moved up 40 dp
        assertEquals(listOf(-1, -1), steps); near(rest, -4f)
        val (down, r2) = Gamepad.scroll(17f)
        assertEquals(emptyList<Int>(), down); near(r2, 17f)
    }

    @Test fun tapClicks() {
        val g = VideoGesture()
        assertEquals(listOf(Send.MOVE), g.down(10f, 10f))
        assertEquals(emptyList<Send>(), g.move(12f, 13f))                 // under 6 dp: still a tap
        assertEquals(listOf(Send.CLICK), g.up())
    }

    @Test fun holdRightClicksOnce() {
        val g = VideoGesture()
        g.down(0f, 0f)
        assertEquals(listOf(Send.RCLICK), g.hold())
        assertEquals(emptyList<Send>(), g.move(50f, 50f))
        assertEquals(emptyList<Send>(), g.up())
    }

    @Test fun moveTurnsIntoADrag() {
        val g = VideoGesture()
        g.down(0f, 0f)
        assertEquals(listOf(Send.DOWN, Send.MOVE), g.move(10f, 0f))
        assertEquals(listOf(Send.MOVE), g.move(20f, 0f))
        assertEquals(emptyList<Send>(), g.hold())                        // too late to right-click
        assertEquals(listOf(Send.UP), g.up())
    }
}
