package app.feelings.tracker.platform.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.feelings.tracker.R

object Channels {
    const val CHECKIN = "checkin"
    const val MEDICATION = "medication"
    const val IMPORTANT = "medication_important"
    const val STATUS = "status"

    fun ensure(context: Context, includeImportant: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel(context, CHECKIN, R.string.channel_checkin, NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(
            channel(context, MEDICATION, R.string.channel_medication, NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(channel(context, STATUS, R.string.channel_status, NotificationManager.IMPORTANCE_LOW))
        if (includeImportant) {
            manager.createNotificationChannel(
                channel(context, IMPORTANT, R.string.channel_medication_important, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun channel(context: Context, id: String, nameRes: Int, importance: Int): NotificationChannel =
        NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(R.string.channel_description)
        }
}
