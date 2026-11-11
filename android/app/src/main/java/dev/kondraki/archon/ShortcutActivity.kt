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
        CoroutineScope(Dispatchers.Main).launch {
            val msg = when {
                Deck.session.token.value == null -> "Open Archon and sign in first"
                what == "lock" -> Deck.postJson("/sys/lock").say("Rig locked")
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

    private fun JsonObject?.say(ok: String): String = when {
        this == null -> "Rig offline, not run"
        bool("ok") == true -> ok
        else -> "Failed: ${str("error") ?: "the rig said no"}"
    }

    companion object {
        /** Rebuild the dynamic shortcuts from GET /modes: up to three modes, then Lock rig.
         *  (Listen joins in phase 2.) Called whenever the app comes to the foreground. */
        suspend fun refresh(ctx: Context) {
            val modes = (Deck.getElement("/modes") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return
            fun intent(doWhat: String, label: String? = null) = Intent(ctx, ShortcutActivity::class.java)
                .setAction("dev.kondraki.archon.SHORTCUT").putExtra("do", doWhat)
                .apply { label?.let { putExtra("label", it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val list = modes.take(3).mapIndexed { i, m ->
                val id = m.str("id") ?: return@mapIndexed null
                val label = m.str("label") ?: id
                val icon = when (id) { "work" -> R.drawable.sc_work; "free" -> R.drawable.sc_free; else -> R.drawable.sc_mode }
                ShortcutInfo.Builder(ctx, "mode:$id").setShortLabel(label).setLongLabel("$label mode")
                    .setIcon(Icon.createWithResource(ctx, icon)).setRank(i).setIntent(intent("mode:$id", label)).build()
            }.filterNotNull() + ShortcutInfo.Builder(ctx, "lock").setShortLabel("Lock rig").setLongLabel("Lock the rig")
                .setIcon(Icon.createWithResource(ctx, R.drawable.sc_lock)).setRank(9).setIntent(intent("lock")).build()
            ctx.getSystemService(ShortcutManager::class.java).dynamicShortcuts = list
        }
    }
}
