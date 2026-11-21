package app.unrot

import android.app.Application
import app.unrot.rules.RulesRepository
import app.unrot.work.RulesWorker

class UnrotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RulesRepository.load(this)
        RulesWorker.schedule(this)
    }
}
