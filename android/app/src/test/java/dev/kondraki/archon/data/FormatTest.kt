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

    @Test fun nfcUidMatchesWebNfc() {
        // made-up bytes (never the real card's UID: this repo is public)
        assertEquals("0a:b1:c2:00:ff:10:80", nfcUid(byteArrayOf(0x0a, 0xb1.toByte(), 0xc2.toByte(), 0x00, 0xff.toByte(), 0x10, 0x80.toByte())))
        assertEquals("", nfcUid(ByteArray(0)))
    }

    @Test fun site19Lines() {
        assertEquals("Access denied — unrecognized tag (04:aa).", site19Line(false, true, "04:aa", false))
        assertTrue(site19Line(true, false, "x", false).startsWith("◈ Crossing complete"))
        assertTrue(site19Line(false, false, "x", true).contains("offline"))
    }

    @Test fun shareOutcomes() {
        assertEquals(ShareOutcome(true, "SENT → LIGHTNING", "→ rig clipboard"),
            shareOutcome(200, j("""{"ok":true,"action":"copy","detail":"→ rig clipboard"}"""), "lightning"))
        assertEquals("RIG UNREACHABLE", shareOutcome(0, null, "lightning").head)
        assertEquals("NOT SIGNED IN", shareOutcome(401, j("""{"detail":"auth required"}"""), "lightning").head)
        assertEquals(ShareOutcome(false, "SHARE FAILED", "empty share"),
            shareOutcome(200, j("""{"ok":false,"action":"none","detail":"empty share"}"""), "lightning"))
        assertEquals("HTTP 500", shareOutcome(500, null, "lightning").detail)
    }

    @Test fun shareWhatLines() {
        assertEquals("clip.mp4 · 34.0 MB", shareWhat("", listOf("clip.mp4"), 34L * 1048576))
        assertEquals("3 files · 12 KB", shareWhat("", listOf("a", "b", "c"), 12 * 1024))
        assertEquals("a link https://x.y", shareWhat("  a link\n https://x.y ", emptyList(), 0))
        assertEquals(60, shareWhat("x".repeat(80), emptyList(), 0).length)
        assertEquals("doc.pdf", shareWhat("", listOf("doc.pdf"), -1))
    }
}
