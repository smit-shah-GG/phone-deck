package dev.kondraki.archon.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Palette

/** IBM Plex Mono for data and body, Chakra Petch for display: the PWA's pair (both OFL,
 *  licences in android/third_party/fonts). */
val Plex = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)
val Chakra = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

/** The deck palette as Compose colours. Defaults are today's red phosphor, used until the
 *  first GET /palette answers (and if it never does: 'connect first' still needs a face). */
@Immutable
data class Phosphor(
    val bg: Color = Color(0xFF000000),
    val low: Color = Color(0xFF030603),
    val surface: Color = Color(0xFF130808),
    val high: Color = Color(0xFF210F0F),
    val on: Color = Color(0xFFEDC3C3),
    val onVar: Color = Color(0xFFB77A7A),
    val primary: Color = Color(0xFFFA4242),
    val onPrimary: Color = Color(0xFF02160B),
    val secondary: Color = Color(0xFFFFB000),
    val outline: Color = Color(0xFF491C1C),
    val crt: Boolean = true,
) {
    companion object {
        fun from(p: Palette): Phosphor {
            val d = Phosphor()
            fun c(key: String, fallback: Color) = p.colors[key]?.let(::parseHex) ?: fallback
            return Phosphor(
                bg = c("bg", d.bg), low = c("low", d.low), surface = c("surface", d.surface),
                high = c("high", d.high), on = c("on", d.on), onVar = c("on-var", d.onVar),
                primary = c("primary", d.primary), onPrimary = c("on-primary", d.onPrimary),
                secondary = c("secondary", d.secondary), outline = c("outline", d.outline),
                crt = p.crt,
            )
        }
    }
}

/** "#rrggbb" → Color; null for anything else (a bad value must not crash the app). */
fun parseHex(s: String): Color? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** CSS's line box: the PWA inherits line-height 1.5 from Tailwind's preflight, so every text
 *  run is 1.5× its font size tall, centred. Android text is ~1.2× by default, which made
 *  every row shorter than the PWA's (measured 2026-11-11: dock rows 26 vs 34.4 units). */
val CssLine = TextStyle(
    lineHeight = 1.5.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
