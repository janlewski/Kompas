package app.feelings.tracker.platform.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.feelings.tracker.App
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as App
        app.container.appScope.launch {
            try {
                app.container.scheduler.onFired(intent.getStringExtra(AlarmScheduler.EXTRA_ID))
            } finally {
                pending.finish()
            }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as App
        app.container.appScope.launch {
            try {
                if (!app.container.isReady()) return@launch
                app.container.scheduler.reconcile(intent.action ?: "system")
                if (intent.action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED &&
                    android.os.Build.VERSION.SDK_INT >= 31 &&
                    !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
                ) {
                    app.container.notifications.postStatus(app.feelings.tracker.R.string.notif_exact_revoked)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
