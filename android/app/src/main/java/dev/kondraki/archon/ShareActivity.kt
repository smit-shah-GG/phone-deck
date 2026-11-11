package dev.kondraki.archon

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.ShareOutcome
import dev.kondraki.archon.data.shareWhat
import dev.kondraki.archon.share.Outgoing
import dev.kondraki.archon.share.ShareService
import dev.kondraki.archon.share.ShareState
import dev.kondraki.archon.share.Sharer
import dev.kondraki.archon.ui.DANGER
import dev.kondraki.archon.ui.Label
import dev.kondraki.archon.ui.LocalPh
import dev.kondraki.archon.ui.Mono
import dev.kondraki.archon.ui.Skin
import dev.kondraki.archon.ui.boxGlow
import dev.kondraki.archon.ui.crt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The share sheet's "Archon" / direct-share "lightning" target (#123 E): a small phosphor card
 * over the app you shared from. 'SENDING → LIGHTNING', then '✓ SENT' (closes itself after
 * 1.5 s) or the failure and its reason (stays until tapped). Files go through ShareService, so
 * leaving the card never cancels them; words are one quick POST from here.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = Outgoing.from(intent) ?: run { finish(); return }
        Skin.loadCached(this)
        if (savedInstanceState == null) start(out)
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalPh provides Skin.ph.value, LocalDensity provides Density(d.density, fontScale = 1f)) {
                val state by Sharer.state.collectAsState()
                LaunchedEffect(state, asking.value) {
                    if (!asking.value && (state as? ShareState.Finished)?.outcome?.ok == true) { delay(1500); finish() }
                }
                ShareCard(state) { finish() }
            }
        }
    }

    /** The upload's progress + result live in a notification (#123 E3), which needs Android 13's
     *  permission: asked once, on the first file share, and the card waits for the answer. */
    private val asking = androidx.compose.runtime.mutableStateOf(false)
    private val notifyAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        asking.value = false
    }

    private fun askForNotifications() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        asking.value = true
        notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() { super.onStart(); cardUp = true }
    override fun onStop() { cardUp = false; super.onStop() }

    private fun start(out: Outgoing) {
        if (Deck.session.token.value == null) {
            Sharer.set(ShareState.Finished(ShareOutcome(false, "NOT SIGNED IN", "open Archon and sign in, then share again")))
            return
        }
        if (out.uris.isNotEmpty()) {
            val files = Sharer.describe(this, out.uris)
            val total = if (files.all { it.size >= 0 }) files.sumOf { it.size } else -1L
            Sharer.set(ShareState.Sending(shareWhat(out.text, files.map { it.name }, total), 0, total))
            // a file the sending app didn't grant us: say so instead of crashing
            runCatching { ShareService.start(this, out) }.onSuccess { askForNotifications() }.onFailure {
                Sharer.set(ShareState.Finished(ShareOutcome(false, "CAN'T READ FILE", "the sending app didn't share access to it")))
            }
            return
        }
        Sharer.set(ShareState.Sending(shareWhat(out.text, emptyList(), 0), 0, 0))     // total 0 = words, no bar
        val app = applicationContext
        scope.launch {
            val o = Sharer.send(app, out, emptyList())
            Sharer.set(ShareState.Finished(o))
            if (!cardUp) Toast.makeText(app, (if (o.ok) "✓ " else "✗ ") + o.head + (o.detail.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Word shares outlive the card (one short POST): app-wide, not the activity's scope. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        @Volatile private var cardUp = false
    }
}

@Composable
private fun ShareCard(state: ShareState, close: () -> Unit) {
    val ph = LocalPh.current
    val none = remember { MutableInteractionSource() }
    // a light scrim; tapping it leaves (a file upload carries on in its notification)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
        .clickable(none, indication = null, onClick = close), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth().boxGlow(ph.primary, 10.dp, 0.25f)
            .clip(shape).background(ph.surface).border(1.dp, ph.outline, shape)
            .clickable(none, indication = null, onClick = { if (state is ShareState.Finished) close() })
            .crt(ph.crt).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.emblem), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(ph.primary))
                Label("ARCHON", 11.sp, ph.primary, spacing = 0.25f, glow = true, modifier = Modifier.padding(start = 8.dp))
            }
            val host = Deck.host.uppercase()
            when (state) {
                is ShareState.Sending -> {
                    Label("SENDING → $host", 18.sp, ph.secondary, spacing = 0.12f, glow = true)
                    Mono(state.what, 13.sp, ph.onVar, maxLines = 2)
                    if (state.total != 0L) {                      // files (-1 = size unknown)
                        Bar(if (state.total > 0) state.sent.toFloat() / state.total else null)
                        Mono("tap outside to leave — it keeps sending", 11.sp, ph.onVar.copy(alpha = 0.55f))
                    }
                }
                is ShareState.Finished -> {
                    val o = state.outcome
                    Label("${o.head}  ${if (o.ok) "✓" else "✗"}", 18.sp, if (o.ok) ph.secondary else DANGER, spacing = 0.12f, glow = true)
                    if (o.detail.isNotEmpty()) Mono(o.detail, 13.sp, ph.onVar, maxLines = 3)
                    if (!o.ok) Mono("tap to close", 11.sp, ph.onVar.copy(alpha = 0.55f))
                }
                ShareState.Idle -> Label("SENDING → $host", 18.sp, ph.secondary, spacing = 0.12f, glow = true)
            }
        }
    }
}

/** A thin phosphor progress bar; null = unknown size (the track alone). */
@Composable
private fun Bar(fraction: Float?) {
    val ph = LocalPh.current
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(ph.high)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(ph.secondary))
    }
}
