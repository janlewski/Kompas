package app.kompas.domain.medication

import app.kompas.core.ids.UuidProvider
import app.kompas.domain.model.DoseEvent
import app.kompas.domain.model.DoseSource
import app.kompas.domain.model.DoseStatus
import app.kompas.domain.model.Medication
import app.kompas.domain.model.ScheduleKind
import app.kompas.domain.model.ScheduleVersion
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface MedicationStore {
    suspend fun medications(): List<Medication>

    suspend fun medication(id: String): Medication?

    suspend fun versions(medicationId: String): List<ScheduleVersion>

    suspend fun saveMedication(medication: Medication, version: ScheduleVersion)

    suspend fun archive(medicationId: String, at: Instant)

    suspend fun occurrences(from: LocalDate, to: LocalDate): List<app.kompas.domain.model.DoseOccurrence>

    suspend fun eventsFor(occurrenceIds: List<String>): List<DoseEvent>

    suspend fun eventsForMedication(medicationId: String): List<DoseEvent>

    suspend fun findByActionId(actionId: String): DoseEvent?

    suspend fun insertEvent(event: DoseEvent): Boolean

    suspend fun regenerateAll(today: LocalDate, zone: ZoneId)
}

sealed class RecordDoseResult {
    data class Saved(val event: DoseEvent) : RecordDoseResult()

    data class Duplicate(val event: DoseEvent) : RecordDoseResult()
}

class RecordDoseUseCase(
    private val store: MedicationStore,
    private val clock: Clock,
    private val ids: UuidProvider,
) {
    suspend fun record(
        actionId: String,
        medicationId: String,
        occurrenceId: String?,
        status: DoseStatus,
        actualAtUtc: Instant?,
        zoneId: ZoneId,
        actualDoseAmount: String?,
        actualDoseUnit: String?,
        snoozeUntilUtc: Instant?,
        source: DoseSource,
        supersedesEventId: String?,
    ): RecordDoseResult {
        store.findByActionId(actionId)?.let { return RecordDoseResult.Duplicate(it) }
        val event = DoseEvent(
            id = ids.newId(),
            actionId = actionId,
            occurrenceId = occurrenceId,
            medicationId = medicationId,
            status = status,
            actualAtUtc = if (status == DoseStatus.TAKEN) actualAtUtc ?: clock.instant() else null,
            recordedAtUtc = clock.instant(),
            zoneId = zoneId,
            actualDoseAmount = actualDoseAmount,
            actualDoseUnit = actualDoseUnit,
            snoozeUntilUtc = if (status == DoseStatus.SNOOZED) snoozeUntilUtc else null,
            source = source,
            supersedesEventId = supersedesEventId,
        )
        val inserted = store.insertEvent(event)
        if (!inserted) {
            val existing = store.findByActionId(actionId)
            if (existing != null) return RecordDoseResult.Duplicate(existing)
        }
        return RecordDoseResult.Saved(event)
    }
}

class SaveScheduleUseCase(
    private val store: MedicationStore,
    private val clock: Clock,
    private val ids: UuidProvider,
) {
    suspend fun create(
        name: String,
        strengthText: String?,
        form: String?,
        version: ScheduleVersion,
    ): Medication {
        val now = clock.instant()
        val medication = Medication(
            id = ids.newId(),
            name = name.trim(),
            strengthText = strengthText?.trim()?.takeIf { it.isNotEmpty() },
            form = form?.trim()?.takeIf { it.isNotEmpty() },
            createdAtUtc = now,
            archivedAtUtc = null,
        )
        store.saveMedication(medication, version.copy(medicationId = medication.id, versionNo = 1, createdAtUtc = now))
        return medication
    }

    suspend fun replaceVersion(medicationId: String, version: ScheduleVersion) {
        val existing = store.versions(medicationId)
        val next = (existing.maxOfOrNull { it.versionNo } ?: 0) + 1
        store.saveMedication(
            store.medication(medicationId) ?: return,
            version.copy(
                id = ids.newId(),
                medicationId = medicationId,
                versionNo = next,
                createdAtUtc = clock.instant(),
            ),
        )
    }

    suspend fun stop(medicationId: String, from: LocalDate, zone: ZoneId) {
        val current = OccurrenceGenerator.versionOn(store.versions(medicationId), from) ?: return
        replaceVersion(
            medicationId,
            current.copy(
                kind = ScheduleKind.STOPPED,
                effectiveFromDate = from,
                times = emptyList(),
                fixedZoneId = current.fixedZoneId ?: zone,
            ),
        )
    }
}
