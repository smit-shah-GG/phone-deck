package dev.kondraki.archon

import android.app.Application
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Session
import dev.kondraki.archon.stream.StreamEngine

/** Creates the one Session the whole process shares (screens now; tiles and shortcuts next). */
class ArchonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Deck.session = Session(this)
        StreamEngine.init(this)
        dev.kondraki.archon.widget.RemoteState.watch(this)     // the Remote widget follows /ws while it's open
    }
}
