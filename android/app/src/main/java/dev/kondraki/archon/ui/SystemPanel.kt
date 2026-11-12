package dev.kondraki.archon.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.list
import dev.kondraki.archon.data.num
import dev.kondraki.archon.data.obj
import dev.kondraki.archon.data.plainNum
import dev.kondraki.archon.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A sub-panel head under More: ‹ MORE + the title (the PWA's .phead .back). */
@Composable
fun SubHead(title: String, onBack: () -> Unit) {
    val ph = LocalPh.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("‹ More", 10.sp, ph.onVar, spacing = 0.14f, modifier = Modifier.tap(onBack))
        PanelHead(title)
    }
}

/**
 * System, 1:1 with deck.html #panel-system: Tailscale, Theme (profile + CRT), Ambient (idle
 * after + liturgy), the action grid, top processes. Suspend / Reboot / Power Off ask for the
 * fingerprint (#123 decision D); Restart NM keeps a plain confirm, like the PWA.
 */
@Composable
fun SystemPanel(state: JsonObject?, onBack: () -> Unit) {
    val ph = LocalPh.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf<JsonObject?>(null) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmNm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { theme = Deck.getJson("/theme") }

    fun postTheme(body: JsonObject) {
        scope.launch { Deck.postJson("/theme", body); theme = Deck.getJson("/theme"); Skin.refresh(ctx) }
    }
    fun act(action: String) {
        busy = action
        scope.launch {
            val r = Deck.postJson("/action", buildJsonObject { put("action", action); putJsonObject("params") {} })
            result = when { r == null -> "✗ rig offline"; r.bool("ok") == true -> "✓ ok"; else -> "✗ ${r.str("error") ?: r.str("stderr") ?: plainNum(r["code"])}" }
            busy = null
        }
    }
    fun lock() { scope.launch { val r = Deck.postJson("/sys/lock"); result = if (r?.bool("ok") == true) "✓ ok" else "✗ ${r?.str("error") ?: "rig offline"}" } }
    fun withFingerprint(what: String, run: () -> Unit) {
        val activity = ctx as? android.app.Activity ?: return
        val auth = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (activity.getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { run(); return }
        BiometricPrompt.Builder(activity).setTitle(what).setSubtitle("Confirm with your fingerprint")
            .setAllowedAuthenticators(auth).build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = run()
            })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHead("System", onBack)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val ts = state.obj("sysinfo").obj("tailscale")
                Card(Modifier.fillMaxWidth()) {
                    Mono(if (ts == null) "Tailscale —" else
                        "Tailscale: ${if (ts.bool("up") == true) "up" else "down"} · ${ts.str("self").orEmpty()} · ${plainNum(ts["online"])}/${plainNum(ts["total"])} peers online",
                        14.sp, ph.onVar, maxLines = 2)
                }
                Card(Modifier.fillMaxWidth()) {
                    Mono("Theme", 14.sp, ph.onVar)
                    SegRow(listOf("green" to "Green", "amber" to "Amber", "ice" to "Ice", "auto" to "Auto"), theme.str("profile")) {
                        postTheme(buildJsonObject { put("profile", it) })
                    }
                    ToggleLine("CRT effects", "· scanlines, vignette, flicker", theme.bool("crt") != false) {
                        postTheme(buildJsonObject { put("crt", theme.bool("crt") == false) })
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    // #156: which side the rail, the thumb column and the mini player sit on; per phone
                    Mono("Handedness · this phone", 14.sp, ph.onVar)
                    val hctx = androidx.compose.ui.platform.LocalContext.current
                    SegRow(listOf("right" to "Right-handed", "left" to "Left-handed"), if (Hand.right.value) "right" else "left") {
                        Hand.set(hctx, it == "right")
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Mono("Ambient — idle after", 14.sp, ph.onVar)
                    val amb = theme.obj("ambient")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            SegRow(listOf("1" to "1m", "2" to "2m", "5" to "5m", "10" to "10m"), amb.num("idle_min")?.toInt()?.toString()) {
                                postTheme(buildJsonObject { putJsonObject("ambient") { put("idle_min", it.toInt()) } })
                            }
                        }
                        ToggleLine("Liturgy", null, amb.bool("liturgy") != false, compact = true) {
                            postTheme(buildJsonObject { putJsonObject("ambient") { put("liturgy", amb.bool("liturgy") == false) } })
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActButton("» PERF MODE", ActKind.PRIMARY, busy == "perfmode", Modifier.fillMaxWidth()) { act("perfmode") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActButton("Lock", ActKind.PLAIN, false, Modifier.weight(1f)) { lock() }
                        ActButton("Restart NM", ActKind.PLAIN, busy == "nm_restart", Modifier.weight(1f)) { confirmNm = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActButton("Suspend", ActKind.WARN, busy == "suspend", Modifier.weight(1f)) { withFingerprint("Suspend the host?") { act("suspend") } }
                        ActButton("Reboot", ActKind.WARN, busy == "reboot", Modifier.weight(1f)) { withFingerprint("Reboot the host?") { act("reboot") } }
                    }
                    ActButton("Power Off", ActKind.DANGER, busy == "poweroff", Modifier.fillMaxWidth()) { withFingerprint("Power OFF the host?") { act("poweroff") } }
                }
                if (result.isNotEmpty()) Mono(result, 12.sp, ph.onVar.copy(alpha = 0.7f), maxLines = 3)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardTitle("Top processes (by memory)")
                state.obj("sysinfo").list("procs").forEach { p -> ProcRow(p) { pid -> scope.launch { Deck.post("/sys/kill", buildJsonObject { put("pid", pid) }) } } }
            }
        }
    }
    if (confirmNm) AlertDialog(
        onDismissRequest = { confirmNm = false },
        containerColor = ph.surface, titleContentColor = ph.primary, textContentColor = ph.on,
        title = { Label("Restart NetworkManager?", 14.sp, ph.primary) },
        text = { Mono("The rig drops off the network for a few seconds.", 13.sp, ph.on, maxLines = 3) },
        confirmButton = { Mono("Restart", 14.sp, ph.primary, Modifier.tap { confirmNm = false; act("nm_restart") }.padding(8.dp)) },
        dismissButton = { Mono("Cancel", 14.sp, ph.onVar, Modifier.tap { confirmNm = false }.padding(8.dp)) },
    )
}

private enum class ActKind { PRIMARY, PLAIN, WARN, DANGER }

@Composable
private fun ActButton(text: String, kind: ActKind, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(6.dp)
    val (bg, fg) = when (kind) {
        ActKind.PRIMARY -> lerp(Color.Black, ph.primary, 0.88f) to ph.onPrimary
        ActKind.PLAIN -> ph.high to ph.on
        ActKind.WARN -> Color(0xB378350F) to ph.on            // tailwind amber-900/70, as in the PWA
        ActKind.DANGER -> Color(0xB37F1D1D) to ph.on          // red-900/70
    }
    Box(modifier.alpha(if (dim) 0.5f else 1f).then(if (kind == ActKind.PRIMARY) Modifier.boxGlow(ph.primary, 6.dp) else Modifier)
        .clip(shape).background(bg).border(1.dp, ph.outline, shape).tap(onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = CssLine + TextStyle(fontFamily = Plex, fontSize = 14.sp, color = fg))
    }
}

/** A labelled .tgl switch row. */
@Composable
private fun ToggleLine(label: String, hint: String?, on: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val ph = LocalPh.current
    Row(Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()).tap(onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (compact) Label(label, 11.sp, ph.secondary, spacing = 0.12f)
        else Row(Modifier.weight(1f)) { Mono(label, 14.sp, ph.onVar); if (hint != null) Mono(" $hint", 11.sp, ph.onVar.copy(alpha = 0.6f)) }
        Switch(on)
    }
}

@Composable
fun Switch(on: Boolean) {
    val ph = LocalPh.current
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(46.dp, 22.dp).then(if (on) Modifier.boxGlow(ph.primary, 4.dp, 0.35f) else Modifier).clip(shape)
        .background(if (on) lerp(Color.Black, ph.primary, 0.18f) else ph.low).border(1.dp, if (on) ph.primary else ph.outline, shape)) {
        Box(Modifier.padding(start = if (on) 26.dp else 2.dp, top = 2.dp).size(16.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) ph.primary else ph.onVar))
    }
}

/** .seg segmented control (shared shape with the Stream panel's). */
@Composable
fun SegRow(items: List<Pair<String, String>>, value: String?, small: Boolean = false, onPick: (String) -> Unit) {
    val ph = LocalPh.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) ->
            val on = v == value
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.weight(1f).then(if (on) Modifier.boxGlow(ph.primary, 6.dp) else Modifier).clip(shape)
                .background(if (on) ph.primary else ph.low).border(1.dp, if (on) ph.primary else ph.outline, shape)
                .tap { onPick(v) }.padding(horizontal = if (small) 4.dp else 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Mono(label, if (small) 12.sp else 13.sp, if (on) ph.onPrimary else ph.onVar)
            }
        }
    }
}

/** .proc: name · memory bar · % · ✕ (only your own processes can be killed). */
@Composable
private fun ProcRow(p: JsonObject, onKill: (Int) -> Unit) {
    val ph = LocalPh.current
    val mem = (p.num("mem") ?: 0.0).coerceIn(0.0, 100.0)
    val mine = p.bool("mine") == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Mono(p.str("name").orEmpty(), 12.sp, ph.onVar, Modifier.weight(1f))
        Box(Modifier.width(90.dp).height(9.dp).background(ph.low).border(1.dp, ph.outline)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((mem / 100).toFloat()).background(ph.primary))
        }
        Text("${plainNum(p["mem"])}%", Modifier.width(38.dp), style = TextStyle(fontFamily = Plex, fontSize = 12.sp, color = ph.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End))
        Mono("✕", 12.sp, if (mine) ph.onVar else ph.onVar.copy(alpha = 0.25f),
            Modifier.then(if (mine) Modifier.tap { p.num("pid")?.toInt()?.let(onKill) } else Modifier).padding(horizontal = 4.dp))
    }
}
