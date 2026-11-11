package dev.kondraki.archon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.str
import dev.kondraki.archon.stream.StreamEngine
import dev.kondraki.archon.stream.StreamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Launcher shortcuts (canvas Shortcuts board): an invisible activity fires one request, toasts
 * what happened and closes; the app never opens. Starting from an activity also keeps phase 2's
 * Listen shortcut inside Android's rules for starting a media service.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent.getStringExtra("do").orEmpty()
        val app = applicationContext
        // Listen starts the media service right here, while this activity is alive and visible:
        // started after finish(), Android could count it as a background start and refuse it.
        if (what == "listen" && Deck.session.token.value != null) {
            Toast.makeText(app, listen(), Toast.LENGTH_SHORT).show()
            finish(); return
        }
        // the Remote widget's LISTEN chip: a toggle (the tile and the Listen shortcut only start)
        if (what == "listen-toggle" && Deck.session.token.value != null) {
            val msg = if (StreamEngine.active) { StreamService.stop(this); "Stopped listening" } else listen()
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val msg = when {
                Deck.session.token.value == null -> "Open Archon and sign in first"
                what == "lock" -> Deck.postJson("/sys/lock").say("Rig locked")
                what == "listen" -> listen()
                what.startsWith("mode:") -> {
                    val id = what.removePrefix("mode:")
                    Deck.postJson("/modes/run", buildJsonObject { put("id", id) })
                        .say("${intent.getStringExtra("label") ?: id} mode started")
                }
                else -> "Unknown shortcut"
            }
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** Audio-only listening (#122 decision 3: the tile and the shortcut ignore the panel's
     *  saved switches but keep its monitor/quality). Started from this activity, so Android
     *  always lets the media service start, even when the tap came from the shade. */
    private fun listen(): String {
        if (StreamEngine.active) return "Already streaming"
        val saved = dev.kondraki.archon.ui.StreamPrefs.load(this)
        StreamService.apply(this, saved.copy(listen = true, mic = false, phoneOnly = false, video = false))
        return "Listening to ${Deck.host.lowercase()}"
    }

    private fun JsonObject?.say(ok: String): String = when {
        this == null -> "Rig offline, not run"
        bool("ok") == true -> ok
        else -> "Failed: ${str("error") ?: "the rig said no"}"
    }

    companion object {
        /** Rebuild the dynamic shortcuts (canvas order): Listen, the first two modes from
         *  GET /modes, Lock rig. Called whenever the app comes to the foreground. */
        /** Direct share (#123 E4): 'Send to lightning' in the share sheet's top row, matched to
         *  ShareActivity by res/xml/shortcuts.xml's share-target category; long-lived so Android
         *  can rank it. NOT excluded from the launcher: measured on One UI 5 (Android 13),
         *  setExcludedFromSurfaces(SURFACE_LAUNCHER) drops it from the share sheet too, so it also
         *  shows in the long-press menu (where it just opens Archon). */
        private fun shareTarget(ctx: Context): ShortcutInfo = ShortcutInfo.Builder(ctx, "share:rig")
            .setShortLabel(Deck.host.lowercase()).setLongLabel("Send to ${Deck.host.lowercase()}")
            .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
            .setCategories(setOf(SHARE_CATEGORY)).setLongLived(true).setRank(10)
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()

        const val SHARE_CATEGORY = "dev.kondraki.archon.category.SEND_TO_RIG"

        suspend fun refresh(ctx: Context) {
            val modes = (Deck.getElement("/modes") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return
            fun intent(doWhat: String, label: String? = null) = Intent(ctx, ShortcutActivity::class.java)
                .setAction("dev.kondraki.archon.SHORTCUT").putExtra("do", doWhat)
                .apply { label?.let { putExtra("label", it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val listen = ShortcutInfo.Builder(ctx, "listen").setShortLabel("Listen").setLongLabel("Listen to the rig")
                .setIcon(Icon.createWithResource(ctx, R.drawable.sc_listen)).setRank(0).setIntent(intent("listen")).build()
            val list = listOf(listen) + modes.take(2).mapIndexed { i, m ->
                val id = m.str("id") ?: return@mapIndexed null
                val label = m.str("label") ?: id
                val icon = when (id) { "work" -> R.drawable.sc_work; "free" -> R.drawable.sc_free; else -> R.drawable.sc_mode }
                ShortcutInfo.Builder(ctx, "mode:$id").setShortLabel(label).setLongLabel("$label mode")
                    .setIcon(Icon.createWithResource(ctx, icon)).setRank(i + 1).setIntent(intent("mode:$id", label)).build()
            }.filterNotNull() + ShortcutInfo.Builder(ctx, "lock").setShortLabel("Lock rig").setLongLabel("Lock the rig")
                .setIcon(Icon.createWithResource(ctx, R.drawable.sc_lock)).setRank(9).setIntent(intent("lock")).build()
            // false = Android's rate limit (it resets when the app comes to the foreground)
            if (!ctx.getSystemService(ShortcutManager::class.java).setDynamicShortcuts(list + shareTarget(ctx)))
                android.util.Log.w("archon", "shortcuts rate-limited")
        }
    }
}
