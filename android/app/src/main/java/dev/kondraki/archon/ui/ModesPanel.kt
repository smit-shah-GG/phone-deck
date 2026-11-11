package dev.kondraki.archon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Modes, 1:1 with app.js loadModes(): big terminal buttons (2 per row) from GET /modes, and
 *  the $ logline with what the last mode did. */
@Composable
fun ModesPanel() {
    val ph = LocalPh.current
    val scope = rememberCoroutineScope()
    var modes by remember { mutableStateOf<List<JsonObject>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { modes = (Deck.getElement("/modes") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList() }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelHead("Modes")
        val list = modes
        when {
            list == null -> Mono("…", 13.sp, ph.onVar)
            list.isEmpty() -> Mono("No modes. Add them to ~/.config/phone-deck/modes.json", 13.sp, ph.onVar.copy(alpha = 0.7f))
            else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                list.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { m ->
                            val id = m.str("id").orEmpty()
                            val label = m.str("label") ?: id
                            ModeButton(label, dim = busy == id, Modifier.weight(1f)) {
                                if (busy != null) return@ModeButton
                                busy = id; output = "applying $label…"
                                scope.launch {
                                    val r = Deck.postJson("/modes/run", buildJsonObject { put("id", id) })
                                    output = when {
                                        r == null -> "✗ rig offline"
                                        r.bool("ok") == true -> (r["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n").orEmpty()
                                        else -> "✗ ${r.str("error").orEmpty()}"
                                    }
                                    busy = null
                                }
                            }
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        if (output.isNotEmpty()) {
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxWidth().heightIn(max = 256.dp).clip(shape).background(ph.low).border(1.dp, ph.outline, shape)
                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = ph.secondary, shadow = Shadow(ph.secondary.copy(alpha = 0.45f), Offset.Zero, 12f))) { append("$ ") }
                    append(output)
                }, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.primary,
                    shadow = Shadow(ph.primary.copy(alpha = 0.35f), Offset.Zero, 8f)))
            }
        }
    }
}

/** .mode: a big terminal button (top-lit gradient, hairline frame, wide tracking). */
@Composable
private fun ModeButton(label: String, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.alpha(if (dim) 0.5f else 1f).clip(shape)
        .background(Brush.verticalGradient(listOf(ph.surface, ph.low))).border(1.dp, ph.outline, shape)
        .tap(onClick).padding(horizontal = 20.dp, vertical = 30.dp), contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 20.sp, letterSpacing = 3.sp, color = ph.on))
    }
}
