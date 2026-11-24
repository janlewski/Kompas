package app.feelings.tracker.platform.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.feelings.tracker.App
import app.feelings.tracker.domain.checkin.CheckInDraft
import app.feelings.tracker.domain.model.AlarmItem
import app.feelings.tracker.domain.model.AlarmKind
import app.feelings.tracker.domain.model.AlarmMode
import app.feelings.tracker.domain.model.AlarmRegistration
import app.feelings.tracker.domain.model.AlarmState
import app.feelings.tracker.domain.model.CheckInSource
import app.feelings.tracker.domain.model.DoseSource
import app.feelings.tracker.domain.model.DoseStatus
import app.feelings.tracker.domain.model.QuickRateDimension
import kotlinx.coroutines.launch

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as App
        app.container.appScope.launch {
            try {
                handle(app, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: App, intent: Intent) {
        val container = app.container
        if (!container.isReady()) return
        val action = intent.getStringExtra(NotificationFactory.EXTRA_ACTION) ?: return
        val actionId = intent.getStringExtra(NotificationFactory.EXTRA_ACTION_ID) ?: return
        when (action) {
            NotificationFactory.ACTION_TAKEN, NotificationFactory.ACTION_SKIPPED -> {
                val status = if (action == NotificationFactory.ACTION_TAKEN) DoseStatus.TAKEN else DoseStatus.SKIPPED
                container.recordDose.record(
                    actionId = actionId,
                    medicationId = intent.getStringExtra(NotificationFactory.EXTRA_MEDICATION_ID) ?: return,
                    occurrenceId = intent.getStringExtra(NotificationFactory.EXTRA_OCCURRENCE_ID),
                    status = status,
                    actualAtUtc = container.clock.instant(),
                    zoneId = container.zones.zoneId(),
                    actualDoseAmount = null,
                    actualDoseUnit = null,
                    snoozeUntilUtc = null,
                    source = DoseSource.NOTIFICATION,
                    supersedesEventId = null,
                )
            }
            NotificationFactory.ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(NotificationFactory.EXTRA_MINUTES, 10)
                val until = container.clock.instant().plusSeconds(minutes * 60L)
                val kind = intent.getStringExtra(NotificationFactory.EXTRA_KIND)
                val ref = intent.getStringExtra(NotificationFactory.EXTRA_REF_ID) ?: return
                if (kind == AlarmKind.CHECK_IN.name) {
                    if (container.alarms.find(actionId) == null) {
                        container.alarms.save(
                            AlarmRegistration(
                                id = actionId,
                                triggerAtUtc = until,
                                requestCode = container.alarms.nextRequestCode(),
                                mode = AlarmMode.APPROXIMATE_WINDOW,
                                items = listOf(AlarmItem(AlarmKind.SNOOZE, ref, silent = false)),
                                generation = container.settings.get().scheduleGeneration,
                                state = AlarmState.REGISTERED,
                                firedAtUtc = null,
                            ),
                        )
                    }
                } else {
                    val occurrence = container.medications.occurrences(
                        container.clock.instant().atZone(container.zones.zoneId()).toLocalDate().minusDays(1),
                        container.clock.instant().atZone(container.zones.zoneId()).toLocalDate().plusDays(7),
                    ).find { it.id == ref }
                    container.recordDose.record(
                        actionId = actionId,
                        medicationId = occurrence?.medicationId ?: return,
                        occurrenceId = ref,
                        status = DoseStatus.SNOOZED,
                        actualAtUtc = null,
                        zoneId = container.zones.zoneId(),
                        actualDoseAmount = null,
                        actualDoseUnit = null,
                        snoozeUntilUtc = until,
                        source = DoseSource.NOTIFICATION,
                        supersedesEventId = null,
                    )
                }
                container.scheduler.reconcile("snooze")
            }
            NotificationFactory.ACTION_QUICK -> {
                val results = RemoteInput.getResultsFromIntent(intent) ?: return
                val rating = results.getCharSequence(NotificationFactory.EXTRA_RATING)?.toString()?.toIntOrNull() ?: return
                if (rating !in 1..5) return
                val dimension = runCatching {
                    QuickRateDimension.valueOf(intent.getStringExtra(NotificationFactory.EXTRA_DIMENSION) ?: return)
                }.getOrNull() ?: return
                val now = container.clock.instant()
                val zone = container.zones.zoneId()
                val draft = CheckInDraft(
                    experiencedAt = now,
                    zoneId = zone,
                    stress = rating.takeIf { dimension == QuickRateDimension.STRESS },
                    stressTouched = dimension == QuickRateDimension.STRESS,
                    physical = rating.takeIf { dimension == QuickRateDimension.PHYSICAL },
                    physicalTouched = dimension == QuickRateDimension.PHYSICAL,
                    mental = rating.takeIf { dimension == QuickRateDimension.MENTAL },
                    mentalTouched = dimension == QuickRateDimension.MENTAL,
                    note = null,
                    painIntensity = null,
                    tagIds = emptyList(),
                    reusedFrom = null,
                    source = CheckInSource.NOTIFICATION_QUICK,
                    promptAlarmId = null,
                    existingId = actionId,
                )
                container.saveCheckIn.save(draft)
            }
        }
        val ref = intent.getStringExtra(NotificationFactory.EXTRA_REF_ID)
        val kindName = intent.getStringExtra(NotificationFactory.EXTRA_KIND)
        if (ref != null && kindName != null) {
            val kind = runCatching { AlarmKind.valueOf(kindName) }.getOrNull()
            if (kind != null) container.notifications.cancel(AlarmItem(kind, ref, false))
        }
    }
}
