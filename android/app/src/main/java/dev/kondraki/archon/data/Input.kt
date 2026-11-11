package dev.kondraki.archon.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** /ws/input: touch-on-video and the gamepad (app.js connectInput/sendInput). Opened on the
 *  first send, closed when the app leaves the screen. The server answers jwhere/jmove with
 *  {"t":"cur","fx","fy"}, the cursor as fractions of the streamed screen, for the crosshair. */
object InputLink {
    private var ws: WebSocket? = null
    private val _cur = MutableStateFlow<Pair<Float, Float>?>(null)
    val cur: StateFlow<Pair<Float, Float>?> = _cur

    @Synchronized fun send(o: JsonObject) {
        (ws ?: open())?.send(o.toString())
    }

    @Synchronized fun close() {
        ws?.close(1000, "background"); ws = null; _cur.value = null
    }

    private fun open(): WebSocket? {
        if (Deck.session.token.value == null) return null
        ws = Deck.http.newWebSocket(Request.Builder().url(Deck.url.replaceFirst("http", "ws") + "/ws/input").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val m = Deck.json.parseToJsonElement(text).jsonObject
                        if ((m["t"] as? JsonPrimitive)?.content == "cur") {
                            val fx = (m["fx"] as? JsonPrimitive)?.floatOrNull
                            val fy = (m["fy"] as? JsonPrimitive)?.floatOrNull
                            if (fx != null && fy != null) _cur.value = fx to fy
                        }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket)
            })
        return ws
    }

    @Synchronized private fun gone(s: WebSocket) { if (ws === s) ws = null }
}
