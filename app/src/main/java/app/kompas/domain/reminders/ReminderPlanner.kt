package app.kompas.domain.reminders

import app.kompas.core.time.LocalScheduleResolver
import app.kompas.domain.medication.WeekMask
import app.kompas.domain.model.AlarmItem
import app.kompas.domain.model.AlarmKind
import app.kompas.domain.model.CheckInQuietBehavior
import app.kompas.domain.model.CheckInReminder
import app.kompas.domain.model.DoseOccurrence
import app.kompas.domain.model.MedicationQuietBehavior
import app.kompas.domain.model.PlannedAlarm
import app.kompas.domain.model.QuietHours
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object ReminderPlanner {
    const val HORIZON = 16
    private const val LOOKAHEAD_DAYS = 8L

    fun plan(
        now: Instant,
        zone: ZoneId,
        reminders: List<CheckInReminder>,
        quietHours: QuietHours,
        occurrences: List<DoseOccurrence>,
        snoozes: List<SnoozeAlarm>,
        alreadyFiredKeys: Set<String>,
        remindersPaused: Boolean,
    ): List<PlannedAlarm> {
        if (remindersPaused) return emptyList()
        val candidates = mutableListOf<Candidate>()
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset)
            reminders.filter { it.enabled }.forEach { reminder ->
                if (!WeekMask.contains(reminder.daysOfWeekMask, date.dayOfWeek)) return@forEach
                val key = checkInKey(reminder.id, date)
                if (key in alreadyFiredKeys) return@forEach
                val resolved = LocalScheduleResolver.resolve(date, reminder.localTime, zone)
                if (!resolved.instant.isAfter(now)) return@forEach
                val adjusted = QuietHoursPolicy.adjustCheckIn(
                    resolved.instant,
                    date,
                    reminder.localTime,
                    quietHours,
                    zone,
                )
                if (adjusted != null && adjusted.isAfter(now)) {
                    candidates += Candidate(adjusted, AlarmItem(AlarmKind.CHECK_IN, reminder.id, silent = false))
                }
            }
        }
        occurrences.forEach { occurrence ->
            val key = doseKey(occurrence.id)
            if (key in alreadyFiredKeys) return@forEach
            if (!occurrence.scheduledAtUtc.isAfter(now)) return@forEach
            val silent = QuietHoursPolicy.medicationSilent(occurrence.scheduledAtUtc, quietHours, occurrence.zoneId)
            candidates += Candidate(
                occurrence.scheduledAtUtc,
                AlarmItem(AlarmKind.DOSE, occurrence.id, silent),
            )
        }
        snoozes.forEach { snooze ->
            if (!snooze.until.isAfter(now)) return@forEach
            val key = snoozeKey(snooze.actionId)
            if (key in alreadyFiredKeys) return@forEach
            candidates += Candidate(
                snooze.until,
                AlarmItem(AlarmKind.SNOOZE, snooze.refId, silent = false),
            )
        }
        return candidates
            .groupBy { it.instant.truncatedTo(ChronoUnit.MINUTES) }
            .entries
            .sortedBy { it.key }
            .take(HORIZON)
            .map { (_, group) ->
                PlannedAlarm(
                    triggerAtUtc = group.minOf { it.instant },
                    items = group.map { it.item }.distinctBy { it.kind to it.refId },
                )
            }
    }

    fun checkInKey(reminderId: String, date: LocalDate): String = "CHECK_IN|$reminderId|$date"

    fun doseKey(occurrenceId: String): String = "DOSE|$occurrenceId"

    fun snoozeKey(actionId: String): String = "SNOOZE|$actionId"

    private data class Candidate(val instant: Instant, val item: AlarmItem)
}

data class SnoozeAlarm(
    val actionId: String,
    val refId: String,
    val until: Instant,
)
