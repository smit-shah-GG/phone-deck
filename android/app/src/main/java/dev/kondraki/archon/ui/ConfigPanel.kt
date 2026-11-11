package dev.kondraki.archon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Config, 1:1 with deck.html #panel-config: pick one of the five JSON files, edit it as text,
 *  Save (POST /config/{name}; the server validates and says why it refused). */
@Composable
fun ConfigPanel(onBack: () -> Unit) {
    val ph = LocalPh.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("commands") }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("" to ph.onVar) }
    LaunchedEffect(name) { status = "" to ph.onVar; text = Deck.getJson("/config/$name").str("text").orEmpty() }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHead("Config", onBack)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("commands", "modes", "context", "voice", "fleet").forEach { n ->
                val on = n == name
                val shape = RoundedCornerShape(6.dp)
                Box(Modifier.clip(shape).background(if (on) lerp(Color.Black, ph.primary, 0.88f) else ph.high)
                    .border(1.dp, ph.outline, shape).tap { name = n }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Mono("$n.json", 13.sp, if (on) ph.onPrimary else ph.on)
                }
            }
            Box(Modifier.weight(1f)) { Mono(status.first, 12.sp, status.second) }
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.boxGlow(ph.primary, 6.dp).clip(shape).background(lerp(Color.Black, ph.primary, 0.88f)).border(1.dp, ph.outline, shape)
                .tap {
                    status = "saving…" to ph.onVar
                    scope.launch {
                        val r = Deck.postJson("/config/$name", buildJsonObject { put("text", text) })
                        status = if (r?.bool("ok") == true) "✓ saved" to ph.secondary else "✗ ${r?.str("error") ?: "failed"}" to DANGER
                    }
                }.padding(horizontal = 16.dp, vertical = 4.dp)) { Mono("Save", 14.sp, ph.onPrimary) }
        }
        val shape = RoundedCornerShape(6.dp)
        BasicTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.on),
            cursorBrush = SolidColor(ph.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).fillMaxWidth().clip(shape).background(ph.low).border(1.dp, ph.outline, shape)
                .verticalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}
