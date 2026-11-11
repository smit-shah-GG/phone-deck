package dev.kondraki.archon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.ui.Chakra
import dev.kondraki.archon.ui.Phosphor
import dev.kondraki.archon.ui.Plex
import kotlinx.coroutines.delay

/** Phase 1 step 1 (tasks #120): the toolchain proof. Archon's opening screen, coloured from the
 *  live GET /palette, which proves the build, the tailnet and the new endpoint in one go. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())          // fullscreen, like the PWA
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { Opening() }
    }
}

private sealed interface Link {
    data object Connecting : Link
    data class Online(val ph: Phosphor) : Link
    data class Offline(val why: String) : Link
}

@Composable
private fun Opening() {
    var link by remember { mutableStateOf<Link>(Link.Connecting) }
    LaunchedEffect(Unit) {
        while (true) {                                          // 'connect first': keep trying
            link = runCatching { Link.Online(Phosphor.from(Deck.palette())) }
                .getOrElse { Link.Offline(it.message ?: it.javaClass.simpleName) }
            if (link is Link.Online) break
            delay(3000)
        }
    }
    val ph = (link as? Link.Online)?.ph ?: Phosphor()
    val status = when (val l = link) {
        Link.Connecting -> "${Deck.host} · CONNECTING"
        is Link.Online -> "${Deck.host} · ONLINE"
        is Link.Offline -> "${Deck.host} · OFFLINE"
    }
    val statusColor = if (link is Link.Offline) ph.secondary else ph.onVar

    Box(
        Modifier.fillMaxSize().background(ph.bg).crt(ph.crt),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painterResource(R.drawable.emblem), contentDescription = null,
                modifier = Modifier.size(132.dp), colorFilter = ColorFilter.tint(ph.primary),
            )
            Text(
                "ARCHON",
                style = TextStyle(
                    fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 38.sp,
                    letterSpacing = 12.sp, color = ph.primary,
                    shadow = Shadow(ph.primary.copy(alpha = 0.6f), Offset.Zero, 24f),
                ),
            )
            Text(status, style = TextStyle(fontFamily = Plex, fontSize = 11.sp, letterSpacing = 3.sp, color = statusColor))
            (link as? Link.Offline)?.let {
                Text(it.why, style = TextStyle(fontFamily = Plex, fontSize = 10.sp, color = ph.onVar.copy(alpha = 0.7f)))
            }
        }
    }
}

/** The CRT overlay the PWA draws with body::before/::after: scanlines + a vignette. Off when the
 *  System → Theme CRT switch is off (palette.crt). */
private fun Modifier.crt(on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawContent()
    val line = Color.Black.copy(alpha = 0.10f)
    var y = 2f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 3f
    }
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
            center = center, radius = size.maxDimension * 0.62f,
        ),
    )
}
