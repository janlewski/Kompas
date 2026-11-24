package app.feelings.tracker.platform.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.feelings.tracker.core.time.ZoneProvider
import app.feelings.tracker.data.repo.AlarmRepository
import app.feelings.tracker.data.repo.MedicationRepository
import app.feelings.tracker.data.repo.ReminderRepository
import app.feelings.tracker.data.repo.SettingsRepository
import app.feelings.tracker.domain.medication.DoseOutcomeResolver
import app.feelings.tracker.domain.model.AlarmItem
import app.feelings.tracker.domain.model.AlarmKind
import app.feelings.tracker.domain.model.AlarmMode
import app.feelings.tracker.domain.model.AlarmRegistration
import app.feelings.tracker.domain.model.AlarmState
import app.feelings.tracker.domain.model.DoseOutcomeKind
import app.feelings.tracker.domain.model.DoseStatus
import app.feelings.tracker.domain.model.MedImportance
import app.feelings.tracker.domain.reminders.ReminderPlanner
import app.feelings.tracker.domain.reminders.SnoozeAlarm
import app.feelings.tracker.platform.notify.NotificationFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AlarmScheduler(
    private val context: Context,
    private val medications: MedicationRepository,
    private val reminders: ReminderRepository,
    private val settingsRepo: SettingsRepository,
    private val alarms: AlarmRepository,
    private val notifications: NotificationFactory,
    private val clock: Clock,
    private val zones: ZoneProvider,
) {
    suspend fun reconcile(@Suppress("UNUSED_PARAMETER") reason: String) {
        val zone = zones.zoneId()
        val today = clock.instant().atZone(zone).toLocalDate()
        medications.regenerateAll(today, zone)
        val generation = settingsRepo.bumpGeneration()
        val settings = settingsRepo.get()
        val reminderRows = reminders.all()
        val quiet = reminders.quietHours()
        val window = app.feelings.tracker.domain.medication.OccurrenceGenerator.window(today)
        val occurrences = medications.occurrences(window.start, window.endInclusive)
            .filter { it.state == app.feelings.tracker.domain.model.DoseOccurrenceState.PLANNED }
        val events = medications.eventsFor(occurrences.map { it.id })
        val eventsByOccurrence = events.groupBy { it.occurrenceId }
        val openOccurrences = occurrences.filter { occurrence ->
            DoseOutcomeResolver.resolve(eventsByOccurrence[occurrence.id].orEmpty()).kind == DoseOutcomeKind.NO_RECORD
        }
        val fired = alarms.all().filter { it.state == AlarmState.FIRED }
        val firedKeys = fired.flatMap { registration ->
            registration.items.map { item ->
                when (item.kind) {
                    AlarmKind.CHECK_IN -> ReminderPlanner.checkInKey(
                        item.refId,
                        Instant.ofEpochMilli(registration.triggerAtUtc.toEpochMilli()).atZone(zone).toLocalDate(),
                    )
                    AlarmKind.DOSE -> ReminderPlanner.doseKey(item.refId)
                    AlarmKind.SNOOZE -> ReminderPlanner.snoozeKey(registration.id)
                }
            }
        }.toSet()
        val doseSnoozes = events.filter { it.status == DoseStatus.SNOOZED && it.snoozeUntilUtc != null }
            .filter { event ->
                val outcome = DoseOutcomeResolver.resolve(eventsByOccurrence[event.occurrenceId].orEmpty())
                outcome.kind == DoseOutcomeKind.NO_RECORD
            }
            .map { SnoozeAlarm(it.actionId, it.occurrenceId ?: it.medicationId, it.snoozeUntilUtc!!) }
        val checkInSnoozes = alarms.registered().flatMap { registration ->
            registration.items.filter { it.kind == AlarmKind.SNOOZE }.map { item ->
                SnoozeAlarm(registration.id, item.refId, registration.triggerAtUtc)
            }
        }
        val planned = ReminderPlanner.plan(
            now = clock.instant(),
            zone = zone,
            reminders = reminderRows,
            quietHours = quiet,
            occurrences = openOccurrences,
            snoozes = doseSnoozes + checkInSnoozes,
            alreadyFiredKeys = firedKeys,
            remindersPaused = settings.remindersPausedAfterRestore,
        )
        val previous = alarms.registered()
        previous.forEach { registration ->
            pending(registration.requestCode, registration.id, PendingIntent.FLAG_NO_CREATE)?.let { intent ->
                alarmManager().cancel(intent)
                intent.cancel()
            }
            alarms.cancel(registration.id)
        }
        val exactAllowed = canScheduleExact()
        var requestCode = alarms.nextRequestCode()
        planned.forEach { alarm ->
            val containsMedication = alarm.items.any { it.kind != AlarmKind.CHECK_IN }
            val mode = if (containsMedication && exactAllowed) AlarmMode.EXACT else AlarmMode.APPROXIMATE_WINDOW
            val id = UUID.randomUUID().toString()
            val registration = AlarmRegistration(
                id = id,
                triggerAtUtc = alarm.triggerAtUtc,
                requestCode = requestCode,
                mode = mode,
                items = alarm.items,
                generation = generation,
                state = AlarmState.REGISTERED,
                firedAtUtc = null,
            )
            val intent = pending(requestCode, id, PendingIntent.FLAG_UPDATE_CURRENT)
            if (intent != null) {
                val trigger = alarm.triggerAtUtc.toEpochMilli()
                val scheduled = runCatching {
                    if (mode == AlarmMode.EXACT) {
                        alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
                    } else {
                        alarmManager().setWindow(AlarmManager.RTC_WAKEUP, trigger, WINDOW_MS, intent)
                    }
                }
                if (scheduled.isFailure && mode == AlarmMode.EXACT) {
                    alarmManager().setWindow(AlarmManager.RTC_WAKEUP, trigger, WINDOW_MS, intent)
                    alarms.save(registration.copy(mode = AlarmMode.APPROXIMATE_WINDOW))
                } else if (scheduled.isSuccess) {
                    alarms.save(registration)
                }
            }
            requestCode += 1
        }
    }

    suspend fun onFired(registrationId: String?) {
        if (registrationId == null) return
        val registration = alarms.find(registrationId) ?: return
        val settings = settingsRepo.get()
        if (registration.generation != settings.scheduleGeneration) return
        if (registration.state != AlarmState.REGISTERED) return
        if (!alarms.markFired(registrationId, clock.instant())) return
        val zone = zones.zoneId()
        val today = clock.instant().atZone(zone).toLocalDate()
        val window = app.feelings.tracker.domain.medication.OccurrenceGenerator.window(today)
        val occurrences = medications.occurrences(window.start, window.endInclusive).associateBy { it.id }
        val meds = medications.medications().associateBy { it.id }
        val versions = meds.keys.flatMap { medications.versions(it) }
        val importance = occurrences.mapValues { (_, occurrence) ->
            versions.find { it.id == occurrence.scheduleVersionId }?.importance ?: MedImportance.STANDARD
        }
        val minute = registration.triggerAtUtc.truncatedTo(ChronoUnit.MINUTES).epochSecond.toString()
        notifications.postGroup(
            minuteKey = minute,
            items = registration.items,
            medications = meds,
            occurrences = occurrences,
            importanceByOccurrence = importance,
            showNames = settings.showMedNamesInNotifications,
            snoozeMinutes = settings.medSnoozeMinutes,
            quickRate = settings.quickRateDimension,
        )
        reconcile("fired")
    }

    fun pendingExists(requestCode: Int, registrationId: String): Boolean =
        pending(requestCode, registrationId, PendingIntent.FLAG_NO_CREATE) != null

    private fun pending(requestCode: Int, registrationId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ID, registrationId)
        val merged = flags or PendingIntent.FLAG_IMMUTABLE
        return if (flags and PendingIntent.FLAG_NO_CREATE != 0) {
            PendingIntent.getBroadcast(context, requestCode, intent, merged)
        } else {
            PendingIntent.getBroadcast(context, requestCode, intent, merged)
        }
    }

    private fun canScheduleExact(): Boolean {
        val manager = alarmManager()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    companion object {
        const val EXTRA_ID = "registrationId"
        private const val WINDOW_MS = 10 * 60 * 1000L
    }
}
