package app.feelings.tracker.domain.medication

import java.time.LocalTime
import java.time.ZoneId

enum class ScheduleProblem {
    NAME,
    DOSE_AMOUNT,
    TIMES,
    DAYS,
    ZONE,
    SNOOZE,
}

object ScheduleRules {
    private val DOSE_AMOUNT = Regex("""^\d{1,4}([.,]\d{1,3})?$""")

    fun validate(
        name: String,
        doseAmount: String?,
        times: List<LocalTime>,
        daysMask: Int,
        fixedZoneId: String?,
        scheduled: Boolean,
    ): List<ScheduleProblem> {
        val problems = mutableListOf<ScheduleProblem>()
        if (name.trim().length !in 1..80) problems += ScheduleProblem.NAME
        if (!doseAmount.isNullOrBlank() && !DOSE_AMOUNT.matches(doseAmount.trim())) {
            problems += ScheduleProblem.DOSE_AMOUNT
        }
        if (scheduled) {
            if (times.isEmpty() || times.size > 12 || times.distinct().size != times.size) {
                problems += ScheduleProblem.TIMES
            }
            if (daysMask and WeekMask.ALL_DAYS == 0) problems += ScheduleProblem.DAYS
            if (fixedZoneId != null) {
                val valid = runCatching { ZoneId.of(fixedZoneId) }.isSuccess
                if (!valid) problems += ScheduleProblem.ZONE
            }
        }
        return problems
    }

    fun snoozeMinutes(minutes: Int): ScheduleProblem? =
        if (minutes !in 5..60) ScheduleProblem.SNOOZE else null
}
