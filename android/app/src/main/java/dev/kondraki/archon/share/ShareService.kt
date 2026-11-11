package dev.kondraki.archon.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import dev.kondraki.archon.R
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.ShareOutcome
import dev.kondraki.archon.data.shareWhat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * File shares (#123 E3): the upload runs in a short foreground service, so leaving the card or
 * locking the phone doesn't kill a long video. Its notification shows progress and then turns
 * into the result. The read grant for the shared files comes along in the start intent's
 * ClipData and lasts until this start is finished (stopSelf(startId)). Shares queue up.
 */
class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val one = Mutex()
    private var jobs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Send to rig", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = intent?.getParcelableArrayListExtra(E_URIS, Uri::class.java).orEmpty()
        val out = Outgoing(intent?.getStringExtra(E_TITLE).orEmpty(), intent?.getStringExtra(E_TEXT).orEmpty(), uris)
        val files = Sharer.describe(this, uris)
        val total = files.sumOf { it.size.coerceAtLeast(0) }.takeIf { files.all { f -> f.size >= 0 } } ?: -1L
        val what = shareWhat(out.text, files.map { it.name }, total)
        // foreground at once (Android's 5 s rule), even if an earlier share is still going
        startForeground(NOTE_PROGRESS, progress(what, 0, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        jobs++
        scope.launch {
            val outcome = one.withLock {
                Sharer.set(ShareState.Sending(what, 0, total))
                var last = 0L
                Sharer.send(this@ShareService, out, files) { sent ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 400) {                       // ~2 updates a second is plenty
                        last = now
                        Sharer.set(ShareState.Sending(what, sent, total))
                        getSystemService(NotificationManager::class.java).notify(NOTE_PROGRESS, progress(what, sent, total))
                    }
                }
            }
            Sharer.set(ShareState.Finished(outcome))
            result(outcome, what)
            if (--jobs == 0) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun base(): Notification.Builder = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.emblem)                          // the SCP emblem everywhere (#127)
        .setCategory(Notification.CATEGORY_PROGRESS)

    private fun progress(what: String, sent: Long, total: Long): Notification = base()
        .setContentTitle("Sending to ${Deck.host.lowercase()}").setContentText(what)
        .setProgress(100, if (total > 0) (sent * 100 / total).toInt() else 0, total <= 0)
        .setOngoing(true).setOnlyAlertOnce(true).build()

    /** The progress note becomes the result: a success clears itself, a failure stays. */
    private fun result(o: ShareOutcome, what: String) {
        val n = base().setContentTitle(if (o.ok) "✓ ${o.head}" else "✗ ${o.head}")
            .setContentText(o.detail.ifEmpty { what }).setAutoCancel(true)
            .apply { if (o.ok) setTimeoutAfter(6000) }.build()
        getSystemService(NotificationManager::class.java).notify(NOTE_RESULT, n)
    }

    companion object {
        private const val CHANNEL = "share"
        private const val NOTE_PROGRESS = 11
        private const val NOTE_RESULT = 12
        private const val E_URIS = "uris"
        private const val E_TITLE = "title"
        private const val E_TEXT = "text"

        /** Hand the files over with their read grant (ClipData + FLAG_GRANT_READ_URI_PERMISSION). */
        fun start(ctx: Context, out: Outgoing) {
            val clip = ClipData.newRawUri("shared", out.uris.first()).apply { out.uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            ctx.startForegroundService(Intent(ctx, ShareService::class.java)
                .putParcelableArrayListExtra(E_URIS, ArrayList(out.uris))
                .putExtra(E_TITLE, out.title).putExtra(E_TEXT, out.text)
                .also { it.clipData = clip }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
