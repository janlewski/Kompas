package app.feelings.tracker.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

enum class DstResolution {
    NORMAL,
    GAP_SHIFTED_FORWARD,
    OVERLAP_FIRST,
}

data class ResolvedLocalTime(
    val instant: Instant,
    val resolution: DstResolution,
    val zoneId: ZoneId,
)

object LocalScheduleResolver {
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): ResolvedLocalTime {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        val resolution = when {
            offsets.isEmpty() -> DstResolution.GAP_SHIFTED_FORWARD
            offsets.size > 1 -> DstResolution.OVERLAP_FIRST
            else -> DstResolution.NORMAL
        }
        // getValidOffsets puts the earlier offset at index 0 during an overlap.
        val earlier = offsets.firstOrNull()
        val zoned = ZonedDateTime.ofLocal(local, zone, earlier)
        return ResolvedLocalTime(zoned.toInstant(), resolution, zone)
    }
}
