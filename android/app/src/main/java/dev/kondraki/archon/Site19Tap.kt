package dev.kondraki.archon

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.bool
import dev.kondraki.archon.data.nfcUid
import dev.kondraki.archon.data.site19Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Site-19 tap-anywhere (#123 decision A): Android hands any NfcA tag here (phone unlocked, app
 * open or not), and the card's UID goes to POST /site19/toggle. The deck decides; unknown tags
 * just get 'access denied' (the user is fine with other tags opening Archon). No UI: a toast and
 * done. Inside the Site-19 panel, reader mode takes the tag first, so this doesn't double-fire.
 */
class Site19Tap : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        val uid = tag?.id?.let(::nfcUid).orEmpty()
        val app = applicationContext
        if (uid.isNotEmpty() && Deck.session.token.value != null) {
            Toast.makeText(app, "◈ Reading clearance — crossing…", Toast.LENGTH_SHORT).show()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                val r = Deck.postJson("/site19/toggle", buildJsonObject { put("uid", uid) }, Deck.slow)   // the crossing takes >10 s
                Toast.makeText(app, site19Line(r?.bool("ok"), r?.bool("denied") == true, uid, r == null), Toast.LENGTH_LONG).show()
            }
        } else if (Deck.session.token.value == null) {
            Toast.makeText(app, "Open Archon and sign in first", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
