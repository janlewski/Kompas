package app.feelings.tracker.platform.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.feelings.tracker.MainActivity
import app.feelings.tracker.R
import app.feelings.tracker.domain.model.AlarmItem
import app.feelings.tracker.domain.model.AlarmKind
import app.feelings.tracker.domain.model.DoseOccurrence
import app.feelings.tracker.domain.model.MedImportance
import app.feelings.tracker.domain.model.Medication
import app.feelings.tracker.domain.model.QuickRateDimension
import app.feelings.tracker.ui.confirm.DoseConfirmActivity

class NotificationFactory(private val context: Context) {
    fun postGroup(
        minuteKey: String,
        items: List<AlarmItem>,
        medications: Map<String, Medication>,
        occurrences: Map<String, DoseOccurrence>,
        importanceByOccurrence: Map<String, MedImportance>,
        showNames: Boolean,
        snoozeMinutes: Int,
        quickRate: QuickRateDimension,
    ) {
        Channels.ensure(context, importanceByOccurrence.values.any { it == MedImportance.IMPORTANT })
        if (items.size == 1) {
            val item = items.first()
            val notification = child(item, minuteKey, grouped = false, medications, occurrences, importanceByOccurrence, showNames, snoozeMinutes, quickRate)
            manager().notify(idFor(item), notification)
            return
        }
        val group = "due_$minuteKey"
        items.forEach { item ->
            val notification = child(item, group, grouped = true, medications, occurrences, importanceByOccurrence, showNames, snoozeMinutes, quickRate)
            manager().notify(idFor(item), notification)
        }
        val summaryChannel = summaryChannel(items, importanceByOccurrence)
        val summary = base(summaryChannel)
            .setContentTitle(context.getString(R.string.notif_summary_title))
            .setContentText(context.getString(R.string.notif_summary_body, items.size))
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()
        manager().notify(group.hashCode(), summary)
    }

    fun postStatus(textRes: Int) {
        Channels.ensure(context, includeImportant = false)
        val notification = base(Channels.STATUS)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(textRes))
            .build()
        manager().notify(STATUS_ID, notification)
    }

    fun postTest(channelId: String) {
        Channels.ensure(context, channelId == Channels.IMPORTANT)
        val notification = base(channelId)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_body))
            .build()
        manager().notify(channelId.hashCode(), notification)
    }

    fun cancel(item: AlarmItem) {
        manager().cancel(idFor(item))
    }

    private fun child(
        item: AlarmItem,
        group: String,
        grouped: Boolean,
        medications: Map<String, Medication>,
        occurrences: Map<String, DoseOccurrence>,
        importanceByOccurrence: Map<String, MedImportance>,
        showNames: Boolean,
        snoozeMinutes: Int,
        quickRate: QuickRateDimension,
    ): android.app.Notification {
        val channel = channelFor(item, importanceByOccurrence)
        val builder = base(channel)
        when (item.kind) {
            AlarmKind.CHECK_IN -> {
                builder.setContentTitle(context.getString(R.string.notif_checkin_title))
                    .setContentText(context.getString(R.string.notif_checkin_body))
                builder.addAction(snoozeAction(item, CHECKIN_SNOOZE_MINUTES, context.getString(R.string.notif_snooze_15)))
                if (quickRate != QuickRateDimension.OFF) {
                    quickRateAction(quickRate)?.let(builder::addAction)
                }
            }
            AlarmKind.DOSE, AlarmKind.SNOOZE -> {
                val occurrence = occurrences[item.refId]
                val medication = occurrence?.let { medications[it.medicationId] }
                if (showNames && medication != null) {
                    builder.setContentTitle(medication.name)
                        .setContentText(occurrence.plannedDoseAmount.orEmpty())
                    builder.addAction(doseAction(item, medication.id, occurrence.id, ACTION_TAKEN, R.string.action_taken))
                    builder.addAction(doseAction(item, medication.id, occurrence.id, ACTION_SKIPPED, R.string.action_skipped))
                } else {
                    builder.setContentTitle(context.getString(R.string.notif_med_hidden_title))
                        .setContentText(context.getString(R.string.notif_med_hidden_body))
                }
                builder.addAction(snoozeAction(item, snoozeMinutes, context.getString(R.string.action_snooze)))
            }
        }
        if (item.silent) builder.setSilent(true)
        if (grouped) {
            builder.setGroup(group).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }
        return builder.build()
    }

    private fun base(channelId: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(app.feelings.tracker.R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.notif_public))
            .build()
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun doseAction(
        item: AlarmItem,
        medicationId: String,
        occurrenceId: String,
        action: String,
        labelRes: Int,
    ): NotificationCompat.Action {
        val actionId = java.util.UUID.randomUUID().toString()
        val intent = Intent(context, ActionReceiver::class.java)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_ACTION_ID, actionId)
            .putExtra(EXTRA_MEDICATION_ID, medicationId)
            .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(EXTRA_REF_ID, item.refId)
        if (Build.VERSION.SDK_INT < 31) {
            val confirm = Intent(context, DoseConfirmActivity::class.java)
                .putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context,
                actionId.hashCode(),
                confirm,
                PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
            )
            return NotificationCompat.Action.Builder(0, context.getString(labelRes), pending).build()
        }
        val pending = PendingIntent.getBroadcast(
            context,
            actionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
        return NotificationCompat.Action.Builder(0, context.getString(labelRes), pending)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun snoozeAction(item: AlarmItem, minutes: Int, label: String): NotificationCompat.Action {
        val actionId = java.util.UUID.randomUUID().toString()
        val intent = Intent(context, ActionReceiver::class.java)
            .putExtra(EXTRA_ACTION, ACTION_SNOOZE)
            .putExtra(EXTRA_ACTION_ID, actionId)
            .putExtra(EXTRA_REF_ID, item.refId)
            .putExtra(EXTRA_KIND, item.kind.name)
            .putExtra(EXTRA_MINUTES, minutes)
        val pending = PendingIntent.getBroadcast(
            context,
            actionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun quickRateAction(dimension: QuickRateDimension): NotificationCompat.Action? {
        if (Build.VERSION.SDK_INT < 31) {
            val pending = PendingIntent.getActivity(
                context,
                QUICK_REQUEST,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
            )
            return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_quick_rate), pending).build()
        }
        val actionId = java.util.UUID.randomUUID().toString()
        val intent = Intent(context, ActionReceiver::class.java)
            .putExtra(EXTRA_ACTION, ACTION_QUICK)
            .putExtra(EXTRA_ACTION_ID, actionId)
            .putExtra(EXTRA_DIMENSION, dimension.name)
        val remote = RemoteInput.Builder(EXTRA_RATING)
            .setLabel(context.getString(R.string.notif_quick_rate))
            .setChoices(arrayOf("1", "2", "3", "4", "5"))
            .setAllowFreeFormInput(false)
            .build()
        val pending = PendingIntent.getBroadcast(
            context,
            actionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable(),
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_quick_rate), pending)
            .addRemoteInput(remote)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun channelFor(item: AlarmItem, importance: Map<String, MedImportance>): String = when (item.kind) {
        AlarmKind.CHECK_IN -> Channels.CHECKIN
        else -> if (importance[item.refId] == MedImportance.IMPORTANT) Channels.IMPORTANT else Channels.MEDICATION
    }

    private fun summaryChannel(items: List<AlarmItem>, importance: Map<String, MedImportance>): String {
        if (items.any { importance[it.refId] == MedImportance.IMPORTANT }) return Channels.IMPORTANT
        if (items.any { it.kind != AlarmKind.CHECK_IN }) return Channels.MEDICATION
        return Channels.CHECKIN
    }

    private fun idFor(item: AlarmItem): Int = (item.kind.name + item.refId).hashCode()

    private fun manager() = context.getSystemService(android.app.NotificationManager::class.java)

    private fun immutable(): Int = PendingIntent.FLAG_IMMUTABLE

    private fun mutable(): Int = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_MEDICATION_ID = "medicationId"
        const val EXTRA_OCCURRENCE_ID = "occurrenceId"
        const val EXTRA_REF_ID = "refId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_DIMENSION = "dimension"
        const val EXTRA_RATING = "rating"
        const val ACTION_TAKEN = "taken"
        const val ACTION_SKIPPED = "skipped"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_QUICK = "quick"
        private const val CHECKIN_SNOOZE_MINUTES = 15
        private const val QUICK_REQUEST = 42
        private const val STATUS_ID = 7
    }
}
