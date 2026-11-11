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
}
