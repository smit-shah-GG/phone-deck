package dev.kondraki.archon.ui

import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.BuildConfig
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck

/** More → About: the app's own Foundation dossier (canvas About board, right phone), the ACS
 *  classification, the PWA's More launchers (Files / Config / System) and the build credits. */
@Composable
fun AboutPanel(onOpen: (String) -> Unit) {
    val ph = LocalPh.current
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("About")
        // #156: the dossier is the LOOK side; Files / Config / System stack in the thumb column
        // above the docked mini player
        HandRow(Modifier.weight(1f).fillMaxWidth(), gap = 16.dp, look = {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Image(painterResource(R.drawable.emblem), null, Modifier.size(52.dp), colorFilter = ColorFilter.tint(ph.primary))
                        Column {
                            Mono("ITEM #", 10.sp, ph.onVar)
                            Label("Archon", 24.sp, ph.primary, spacing = 0.25f, glow = true, bold = true)
                        }
                    }
                    Acs()
                    Section("Special containment procedures",
                        "ARCHON is to remain on the person of its operator. It answers only to the operator's fingerprint " +
                            "and to one rig, ${Deck.host}, reached over the tailnet. Destructive commands require a second " +
                            "confirmation. When separated from the rig, ARCHON says so and keeps trying.")
                    Section("Description",
                        "A handheld interface used to direct other assets and to open crossings into Site-19. " +
                            "Its disruption is confined to a single individual.")
                    // the build credits read here, on the look side (#156)
                    CardTitle("Build")
                    Mono("Archon ${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}", 11.sp, ph.onVar, maxLines = 2)
                    Mono("Emblem: SCP Foundation logo by far2, high-res by Aelanna, CC BY-SA 3.0 (creativecommons.org/licenses/by-sa/3.0).",
                        11.sp, ph.onVar, maxLines = 4)
                    Mono("Fonts: IBM Plex Mono, Chakra Petch (SIL OFL 1.1).", 11.sp, ph.onVar, maxLines = 2)
                }
            }
        }, thumb = {
            Column(Modifier.width(THUMB_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UpdateCard()
                listOf("files" to "Files", "config" to "Config", "system" to "System").forEach { (key, name) ->
                    LaunchButton(name, Modifier.fillMaxWidth().height(64.dp)) { onOpen(key) }
                }
                Spacer(Modifier.weight(1f))
                MiniPlayer()
            }
        })
    }
}

/** The ACS bar: Containment ESOTERIC · Secondary THAUMIEL · Disruption 1 DARK · Risk 2 CAUTION. */
@Composable
private fun Acs() {
    val ph = LocalPh.current
    val cells = listOf(
        Triple("Containment", "Esoteric", ph.on),
        Triple("Secondary", "Thaumiel", ph.secondary),
        Triple("Disruption", "1 · Dark", ph.on),
        Triple("Risk", "2 · Caution", ph.primary),
    )
    Row(Modifier.fillMaxWidth().border(1.dp, ph.outline)) {
        cells.forEachIndexed { i, (k, v, c) ->
            Column(Modifier.weight(1f)
                .then(if (i == 1) Modifier.background(ph.secondary.copy(alpha = 0.06f)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                Mono(k.uppercase(), 9.sp, ph.onVar)
                Label(v, 14.sp, c, spacing = 0.12f, glow = c != ph.on, bold = true)
            }
            if (i < cells.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(ph.outline))
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    val ph = LocalPh.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Mono(title.uppercase(), 10.sp, ph.primary)
        Text(body, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.on))
    }
}

/** .lbtn: a big framed launcher button (the PWA's More screen). */
@Composable
private fun LaunchButton(name: String, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(ph.high).border(1.dp, ph.line, shape).tap(onClick),
        contentAlignment = Alignment.Center) { Label(name, 15.sp, ph.on, spacing = 0.2f) }
}

/** The in-app updater's face (#157): only shown when there's something to say. */
@Composable
private fun UpdateCard() {
    val ph = LocalPh.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val u by dev.kondraki.archon.data.Updater.state.collectAsState()
    val shape = RoundedCornerShape(8.dp)
    val go = { scope.launch { dev.kondraki.archon.data.Updater.install(ctx) }; Unit }
    when (val s = u) {
        is dev.kondraki.archon.data.UpdateState.Available -> Column(Modifier.fillMaxWidth().clip(shape).background(lerp(Color.Black, ph.secondary, 0.14f))
            .border(1.dp, ph.secondary, shape).boxGlow(ph.secondary, 6.dp, 0.25f).tap(go).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("⬆ UPDATE TO ${s.version}", 14.sp, ph.secondary, spacing = 0.14f, glow = true)
            Mono(s.note.ifEmpty { "installed: ${BuildConfig.VERSION_NAME} · tap to install" }, 11.sp, ph.onVar, maxLines = 2)
        }
        is dev.kondraki.archon.data.UpdateState.Downloading -> Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, ph.outline, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("downloading ${s.version} · ${s.pct}%", 12.sp, ph.on)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ph.high)) {
                Box(Modifier.fillMaxWidth(s.pct / 100f).height(4.dp).background(ph.secondary))
            }
        }
        is dev.kondraki.archon.data.UpdateState.Installing -> Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, ph.outline, shape).padding(12.dp)) {
            Mono("installing ${s.version}… Archon restarts on the new build", 12.sp, ph.on, maxLines = 2)
        }
        is dev.kondraki.archon.data.UpdateState.Failed -> Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, DANGER, shape).tap(go).padding(12.dp)) {
            Mono("✗ update ${s.version}: ${s.why}", 12.sp, DANGER, maxLines = 2)
            Mono("tap to try again", 11.sp, ph.onVar)
        }
        dev.kondraki.archon.data.UpdateState.None -> {}
    }
}
