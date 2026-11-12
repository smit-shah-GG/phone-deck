package dev.kondraki.archon.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import android.app.Activity
import android.nfc.NfcAdapter
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.nfcUid
import dev.kondraki.archon.data.site19Line
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Site-19, 1:1 with deck.html #panel-site19: PRESENT CLEARANCE arms the phone's NFC reader
 * (native reader mode, not Web NFC); presenting the Level-5 card sends its UID to
 * POST /site19/toggle and shows the result and the controller's output. Tap-anywhere
 * (Site19Tap) covers the rest of the phone.
 */
@Composable
fun Site19Panel() {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var armed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap the button, then present the card to the phone.") }
    var output by remember { mutableStateOf("") }

    DisposableEffect(armed) {
        val activity = ctx as? Activity
        if (armed && activity != null && nfc != null) {
            nfc.enableReaderMode(activity, { tag ->
                val uid = nfcUid(tag.id)
                activity.runOnUiThread {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    status = "Reading clearance — crossing…"
                    scope.launch {
                        val r = Deck.postJson("/site19/toggle", buildJsonObject { put("uid", uid) }, Deck.slow)   // the crossing takes >10 s
                        output = r?.str("output") ?: r?.toString().orEmpty()
                        status = site19Line(r?.bool("ok"), r?.bool("denied") == true, uid, r == null)
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }
        onDispose { if (activity != null && nfc != null) runCatching { nfc.disableReaderMode(activity) } }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelHead("Site-19"); Mono("level 5 clearance", 11.sp, ph.onVar)
        }
        // #156: status + the last crossing's output are the LOOK side; PRESENT CLEARANCE is a tall
        // button in the thumb column, above the docked mini player
        HandRow(Modifier.weight(1f).fillMaxWidth(),
            look = {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status, Modifier.fillMaxWidth(), style = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = ph.onVar))
                    val sh = RoundedCornerShape(6.dp)
                    Box(Modifier.weight(1f).fillMaxWidth().clip(sh).background(ph.low).border(1.dp, ph.outline, sh)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (output.isEmpty()) Mono("the controller's output appears here after a crossing", 12.sp, ph.onVar.copy(alpha = 0.6f), maxLines = 2)
                        else Mono(output, 12.sp, ph.on, maxLines = 40)
                    }
                }
            },
            thumb = {
                Column(Modifier.width(THUMB_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.weight(1f).fillMaxWidth().boxGlow(ph.primary, 8.dp).clip(shape).background(lerp(Color.Black, ph.primary, 0.88f))
                        .border(1.dp, ph.outline, shape)
                        .tap {
                            when {
                                nfc == null -> status = "This phone has no NFC."
                                !nfc.isEnabled -> status = "NFC is off — turn it on in quick settings, then try again."
                                armed -> status = "Scanner armed — present the card."
                                else -> { armed = true; status = "Awaiting clearance — present the card…" }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text("◈\nPRESENT\nCLEARANCE", textAlign = TextAlign.Center, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp, color = ph.onPrimary))
                    }
                    MiniPlayer()
                }
            })
    }
}
