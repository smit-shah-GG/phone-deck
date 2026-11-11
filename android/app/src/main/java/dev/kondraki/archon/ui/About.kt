package dev.kondraki.archon.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.BuildConfig
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.tiles.RigMusicTile
import dev.kondraki.archon.tiles.ScreensTile

/** More → About: the app's own Foundation dossier (canvas About board, right phone), the ACS
 *  classification, the CC BY-SA credit the emblem needs, and one-tap tile adding. */
@Composable
fun AboutPanel() {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHead("About")
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.weight(1.4f).fillMaxHeight()) {
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
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardTitle("Quick settings")
                if (Build.VERSION.SDK_INT >= 33) {
                    TileButton("Add the Rig music tile") { addTile(ctx, RigMusicTile::class.java, "Rig music", R.drawable.ic_tile_music) }
                    TileButton("Add the Screens tile") { addTile(ctx, ScreensTile::class.java, "Screens", R.drawable.ic_tile_screens) }
                } else Mono("Add them from the Quick Settings editor.", 12.sp, ph.onVar)
                CardTitle("Build")
                Mono("Archon ${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}", 11.sp, ph.onVar, maxLines = 2)
                Mono("Emblem: SCP Foundation logo by far2, high-res by Aelanna, CC BY-SA 3.0 (creativecommons.org/licenses/by-sa/3.0).",
                    11.sp, ph.onVar, maxLines = 4)
                Mono("Fonts: IBM Plex Mono, Chakra Petch (SIL OFL 1.1).", 11.sp, ph.onVar, maxLines = 2)
            }
        }
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

@Composable
private fun TileButton(text: String, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(ph.high).border(1.dp, ph.outline, shape).tap(onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp)) { Mono(text, 13.sp, ph.on) }
}

private fun addTile(ctx: android.content.Context, cls: Class<*>, label: String, icon: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(ctx, cls), label, Icon.createWithResource(ctx, icon), ctx.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "$label tile added"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "$label is already in Quick Settings"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Not added"
            else -> "Couldn't add it here: use the Quick Settings editor"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

