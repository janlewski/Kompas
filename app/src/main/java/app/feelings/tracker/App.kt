package app.feelings.tracker

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.feelings.tracker.platform.notify.Channels
import app.feelings.tracker.platform.work.MaintenanceWorker
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.ensure(this, includeImportant = false)
        val work = PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
        container.start()
    }
}
