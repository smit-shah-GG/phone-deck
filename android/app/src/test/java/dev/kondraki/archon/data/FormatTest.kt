package dev.kondraki.archon.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FormatTest {
    private fun j(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun heatMatchesThePwaThresholds() {
        assertEquals(Heat.OK, heat(79.0, 89.0))
        assertEquals(Heat.WARN, heat(80.0, 10.0))
        assertEquals(Heat.WARN, heat(40.0, 90.0))
        assertEquals(Heat.ALERT, heat(88.0, 0.0))
    }

    @Test fun bandLinesReadLikeTheBand() {
        assertEquals("GPU 12% · 3.1/12G · 54°",
            gpuLine(j("""{"util":12.7,"vram_used":3174,"vram_total":12288,"temp":54}""")))
        assertEquals("CPU 7% · 61° · 14/31G", cpuLine(j("""{"util":7,"temp":61.2,"mem_used":14,"mem_total":31}""")))
        assertEquals("CPU 7% · 14.5/31G", cpuLine(j("""{"util":7,"temp":0,"mem_used":14.5,"mem_total":31}""")))
        assertEquals("MIC · 100%", micLine(j("""{"mic_muted":false,"sink_muted":false,"volume":100}""")))
        assertEquals("MIC× · MUTE", micLine(j("""{"mic_muted":true,"sink_muted":true,"volume":100}""")))
    }

    @Test fun nowPlaying() {
        assertEquals("▶ a - b", nowPlayingLine(j("""{"status":"Playing","title":"a - b"}""")))
        assertEquals("‖ a - b", nowPlayingLine(j("""{"status":"Paused","title":"a - b"}""")))
        assertEquals("—", nowPlayingLine(j("""{"status":"","title":""}""")))
        assertEquals("—", nowPlayingLine(null))
    }

    @Test fun missingFieldsNeverThrow() {
        val empty = j("{}")
        gpuLine(empty); cpuLine(empty); micLine(empty)
        assertEquals(emptyList<JsonObject>(), empty.list("sinks"))
    }

    private fun jwt(payload: String): String {
        val b64 = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        return b64("""{"alg":"HS256"}""") + "." + b64(payload) + ".sig"
    }

    @Test fun jwtExpReadsTheClaim() {
        assertEquals(1794500000L, jwtExp(jwt("""{"sub":"deck","exp":1794500000}""")))
        assertEquals(0L, jwtExp("garbage"))
    }

    @Test fun refreshWhenUnderFiveDaysRemain() {
        val now = 1_000_000L
        assertFalse(refreshDue(now + 6 * 86400, now))
        assertTrue(refreshDue(now + 4 * 86400, now))
        assertTrue(refreshDue(0, now))
    }

    @Test fun loginOutcomes() {
        assertEquals(LoginResult.Ok("tok"),
            loginOutcome(303, "/", listOf("deck_session=tok; HttpOnly; Path=/; SameSite=strict")))
        assertEquals(LoginResult.BadPin, loginOutcome(303, "/login?bad=1", emptyList()))
        assertEquals(LoginResult.Locked(31), loginOutcome(303, "/login?locked=31", emptyList()))
        assertTrue(loginOutcome(500, null, emptyList()) is LoginResult.Failed)
        assertTrue(loginOutcome(303, "/", emptyList()) is LoginResult.Failed)
    }
}
