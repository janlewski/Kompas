package app.kompas.domain.medication

import java.time.DayOfWeek

object WeekMask {
    const val ALL_DAYS = 127

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun overlaps(left: Int, right: Int): Boolean = left and right != 0
}
