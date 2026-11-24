package app.feelings.tracker.domain.reminders

import app.feelings.tracker.core.time.LocalScheduleResolver
import app.feelings.tracker.core.time.QuietHoursWindow
import app.feelings.tracker.domain.medication.WeekMask
import app.feelings.tracker.domain.model.CheckInQuietBehavior
import app.feelings.tracker.domain.model.MedicationQuietBehavior
import app.feelings.tracker.domain.model.QuietHours
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object QuietHoursPolicy {
    /** Check-ins may be dropped or moved. Medication times are never moved. */
    fun adjustCheckIn(
        instant: Instant,
        date: LocalDate,
        time: LocalTime,
        quietHours: QuietHours,
        zone: ZoneId,
    ): Instant? {
        if (!applies(quietHours, date, time)) return instant
        return when (quietHours.checkInBehavior) {
            CheckInQuietBehavior.SUPPRESS -> null
            CheckInQuietBehavior.DEFER_TO_END -> {
                val endDate = QuietHoursWindow.endDate(date, time, quietHours.startLocal, quietHours.endLocal)
                LocalScheduleResolver.resolve(endDate, quietHours.endLocal, zone).instant
            }
        }
    }

    fun medicationSilent(instant: Instant, quietHours: QuietHours, zoneId: ZoneId): Boolean {
        if (quietHours.medicationBehavior != MedicationQuietBehavior.DELIVER_SILENTLY) return false
        val zoned = instant.atZone(zoneId)
        return applies(quietHours, zoned.toLocalDate(), zoned.toLocalTime())
    }

    fun applies(quietHours: QuietHours, date: LocalDate, time: LocalTime): Boolean {
        if (!quietHours.enabled) return false
        if (!WeekMask.contains(quietHours.daysOfWeekMask, date.dayOfWeek)) {
            val crosses = quietHours.startLocal.isAfter(quietHours.endLocal)
            val previous = date.minusDays(1)
            val fromPreviousEvening = crosses && time.isBefore(quietHours.endLocal) &&
                WeekMask.contains(quietHours.daysOfWeekMask, previous.dayOfWeek)
            if (!fromPreviousEvening) return false
        }
        return QuietHoursWindow.contains(time, quietHours.startLocal, quietHours.endLocal)
    }
}
