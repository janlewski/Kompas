package app.feelings.tracker.core.time

import java.time.LocalDate
import java.time.LocalTime

object QuietHoursWindow {
    /** True when [time] falls in [start, end). A window with [start] after [end] crosses midnight. */
    fun contains(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (start == end) return false
        return if (start.isBefore(end)) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }

    /**
     * Local date of the window's end for a trigger that is already inside quiet hours.
     * When the window crosses midnight and the trigger is on the evening side, the end is the next day.
     */
    fun endDate(triggerDate: LocalDate, triggerTime: LocalTime, start: LocalTime, end: LocalTime): LocalDate {
        val crossesMidnight = start.isAfter(end)
        return if (crossesMidnight && !triggerTime.isBefore(start)) {
            triggerDate.plusDays(1)
        } else {
            triggerDate
        }
    }
}
