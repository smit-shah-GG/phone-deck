package dev.kondraki.archon

import android.app.Application
import dev.kondraki.archon.data.Deck
import dev.kondraki.archon.data.Session

/** Creates the one Session the whole process shares (screens now; tiles and shortcuts next). */
class ArchonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Deck.session = Session(this)
    }
}
