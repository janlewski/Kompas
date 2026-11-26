package app.kompas.domain.medication

import app.kompas.core.time.LocalScheduleResolver
import app.kompas.domain.model.DoseOccurrence
import app.kompas.domain.model.DoseOccurrenceState
import app.kompas.domain.model.ScheduleKind
import app.kompas.domain.model.ScheduleVersion
import app.kompas.domain.model.ZonePolicy
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object OccurrenceGenerator {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun occurrenceId(versionId: String, date: LocalDate, time: java.time.LocalTime): String =
        "$versionId|$date|${time.format(TIME)}"

    fun window(today: LocalDate): ClosedRange<LocalDate> = today.minusDays(1)..today.plusDays(7)

    fun versionOn(versions: List<ScheduleVersion>, date: LocalDate): ScheduleVersion? =
        versions.filter { !it.effectiveFromDate.isAfter(date) }
            .maxWithOrNull(compareBy<ScheduleVersion> { it.effectiveFromDate }.thenBy { it.versionNo })

    fun plan(
        versions: List<ScheduleVersion>,
        today: LocalDate,
        deviceZone: ZoneId,
    ): List<DoseOccurrence> {
        if (versions.isEmpty()) return emptyList()
        val medicationId = versions.first().medicationId
        val range = window(today)
        val planned = mutableListOf<DoseOccurrence>()
        var date = range.start
        while (!date.isAfter(range.endInclusive)) {
            val version = versionOn(versions, date)
            if (version != null && version.kind == ScheduleKind.SCHEDULED &&
                WeekMask.contains(version.daysOfWeekMask, date.dayOfWeek)
            ) {
                val zone = when (version.zonePolicy) {
                    ZonePolicy.FIXED_ZONE -> version.fixedZoneId ?: deviceZone
                    ZonePolicy.FOLLOW_DEVICE -> deviceZone
                }
                version.times.distinct().sorted().forEach { time ->
                    val id = occurrenceId(version.id, date, time)
                    val resolved = LocalScheduleResolver.resolve(date, time, zone)
                    planned += DoseOccurrence(
                        id = id,
                        medicationId = medicationId,
                        scheduleVersionId = version.id,
                        localDate = date,
                        localTime = time,
                        zoneId = zone,
                        scheduledAtUtc = resolved.instant,
                        dstResolution = resolved.resolution,
                        plannedDoseAmount = version.doseAmount,
                        plannedDoseUnit = version.doseUnit,
                        state = DoseOccurrenceState.PLANNED,
                    )
                }
            }
            date = date.plusDays(1)
        }
        return planned
    }

    fun idsToCancel(
        existingPlannedIds: Set<String>,
        generatedIds: Set<String>,
        protectedIds: Set<String>,
    ): Set<String> = existingPlannedIds - generatedIds - protectedIds
}
