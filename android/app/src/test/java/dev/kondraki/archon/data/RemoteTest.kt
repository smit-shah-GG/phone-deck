package dev.kondraki.archon.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {
    private fun j(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val modes = listOf(ModeChip("work", "Work"), ModeChip("free", "Free"))

    @Test fun fromGetState() {
        val s = remoteFrom(j("""{"audio":{"now_playing":{"status":"Playing","title":"A - B","artist":"A","track":"B","art_key":"k1"}},
            "headless":{"on":true,"available":true}}"""), RemoteSnap(), listening = false, live = false, now = 5, modes = modes)
        assertEquals("B", s.title); assertEquals("A", s.artist); assertTrue(s.playing); assertTrue(s.hasPlayer)
        assertFalse(s.screensOn); assertTrue(s.screensKnown); assertEquals("k1", s.artKey); assertEquals(modes, s.modes)
        assertTrue(s.online); assertEquals(5, s.at)
    }

    @Test fun wsStateKeepsTheLastHeadlessAndModes() {
        val prev = RemoteSnap(screensOn = false, screensKnown = true, modes = modes)
        val s = remoteFrom(j("""{"audio":{"now_playing":{"status":"Paused","title":"only a title"}}}"""), prev, listening = true, live = true, now = 9)
        assertEquals("only a title", s.title); assertEquals("", s.artist); assertFalse(s.playing)
        assertFalse(s.screensOn); assertTrue(s.screensKnown); assertEquals(modes, s.modes); assertTrue(s.listening); assertTrue(s.live)
    }

    @Test fun nothingPlaying() {
        val s = remoteFrom(j("""{"audio":{"now_playing":{"status":"","title":""}}}"""), RemoteSnap(), false, false, 1)
        assertFalse(s.hasPlayer); assertEquals("", s.title)
    }

    @Test fun faceIgnoresTheTimestamp() {
        assertTrue(RemoteSnap(at = 1, title = "x").sameFace(RemoteSnap(at = 2, title = "x")))
        assertFalse(RemoteSnap(title = "x").sameFace(RemoteSnap(title = "y")))
    }

    @Test fun freshnessAndStamp() {
        val now = 100 * 60_000L
        assertEquals(Fresh.OFFLINE, remoteFresh(RemoteSnap(online = false, live = true), now))
        assertEquals(Fresh.LIVE, remoteFresh(RemoteSnap(online = true, live = true, at = 0), now))
        assertEquals(Fresh.FRESH, remoteFresh(RemoteSnap(online = true, at = now - 14 * 60_000L), now))
        assertEquals(Fresh.STALE, remoteFresh(RemoteSnap(online = true, at = now - 16 * 60_000L), now))
        assertEquals("live", remoteStamp(Fresh.LIVE, "14:23"))
        assertEquals("as of 14:23", remoteStamp(Fresh.STALE, "14:23"))
        assertEquals("rig offline · tap", remoteStamp(Fresh.OFFLINE, "14:23"))
    }

    @Test fun remoteFieldsFromState() {
        val s = remoteFrom(j("""{"audio":{"volume":62,"sink_muted":true,"mic_muted":false,
            "sinks":[{"id":41,"name":"Speakers","active":false},{"id":57,"name":"Headphones","active":true}],
            "now_playing":{"status":"Playing","title":"t"}},
            "hypr":{"monitors":[{"name":"DP-1","active_ws":6,"focused":false},{"name":"DP-3","active_ws":2,"focused":true}]}}"""),
            RemoteSnap(), false, true, 1)
        assertEquals(62, s.volume); assertTrue(s.sinkMuted); assertFalse(s.micMuted); assertEquals(2, s.ws)
        assertEquals(listOf(Output(41, "Speakers", false), Output(57, "Headphones", true)), s.outputs)
        assertEquals(Output(41, "Speakers", false), nextOutput(s.outputs))
        assertEquals(null, nextOutput(listOf(Output(1, "x", true))))
    }

    @Test fun shortOutputNames() {
        assertEquals("ANALOG STEREO", shortOutput("Starship/Matisse HD Audio Controller Analog Stereo"))
        assertEquals("SPEAKERS", shortOutput("Speakers"))
        assertEquals("--", shortOutput(" "))
    }
}
