package dev.kondraki.archon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Link
import dev.kondraki.archon.data.LoginResult
import kotlinx.coroutines.launch

/** The Archon opening screen (canvas: About.dc.html, left phone): emblem, wordmark, the rig's
 *  status. Tapping the emblem re-asks for the fingerprint. */
@Composable
fun Opening(link: Link, onTap: () -> Unit) {
    val ph = LocalPh.current
    val status = when (link) {
        Link.ONLINE -> "${Deck.host} · ONLINE"
        Link.OFFLINE -> "${Deck.host} · OFFLINE"
        else -> "${Deck.host} · CONNECTING"
    }
    Box(Modifier.fillMaxSize().background(ph.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.emblem), contentDescription = "Unlock",
                modifier = Modifier.size(132.dp).tap(onTap), colorFilter = ColorFilter.tint(ph.primary))
            Wordmark(38)
            Text(status, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 11.sp, letterSpacing = 3.sp,
                color = if (link == Link.OFFLINE) ph.secondary else ph.onVar))
        }
    }
}

@Composable
fun Wordmark(size: Int) {
    val ph = LocalPh.current
    Text("ARCHON", style = CssLine + TextStyle(
        fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (size * 0.32).sp,
        color = ph.primary, shadow = Shadow(ph.primary.copy(alpha = 0.6f), Offset.Zero, 24f)))
}

/** First run on a phone (or after a week without contact): the PIN, ported from login.html.
 *  Keypad on the left, entry on the right. */
@Composable
fun PinScreen() {
    val ph = LocalPh.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun press(k: String) {
        error = null
        pin = when (k) { "C" -> ""; "back" -> pin.dropLast(1); else -> if (pin.length < 12) pin + k else pin }
    }
    fun submit() {
        if (busy || pin.isEmpty()) return
        busy = true
        scope.launch {
            when (val r = Deck.session.login(pin)) {
                is LoginResult.Ok -> Unit                                   // the token flow moves us on
                LoginResult.BadPin -> error = "Wrong PIN"
                is LoginResult.Locked -> error = "Locked — try again in ${r.seconds}s"
                is LoginResult.Failed -> error = "Can't reach ${Deck.host.lowercase()} (${r.why})"
            }
            pin = ""; busy = false
        }
    }

    Row(Modifier.fillMaxSize().background(ph.bg).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "back")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(ph.high).tap { press(k) },
                            contentAlignment = Alignment.Center) {
                            Mono(if (k == "back") "⌫" else k, if (k.length == 1 && k != "C") 24.sp else 18.sp,
                                if (k == "C" || k == "back") ph.onVar else ph.on)
                        }
                    }
                }
            }
        }
        Column(Modifier.width(256.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(painterResource(R.drawable.emblem), null, Modifier.size(56.dp), colorFilter = ColorFilter.tint(ph.primary))
            Label("Archon", 18.sp, ph.onVar, spacing = 0.3f)
            Mono(if (pin.isEmpty()) "·····" else "●".repeat(pin.length), 28.sp, ph.on)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ph.primary.copy(alpha = if (busy) 0.5f else 0.88f))
                .tap(::submit).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Mono(if (busy) "…" else "Unlock", 15.sp, ph.onPrimary)
            }
            error?.let { Mono(it, 13.sp, DANGER, maxLines = 2) }
        }
    }
}
