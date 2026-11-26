package app.kompas.domain.checkin

import app.kompas.core.ids.UuidProvider
import app.kompas.domain.model.CheckIn
import app.kompas.domain.model.SleepRecord
import app.kompas.domain.model.SleepSource
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

interface CheckInStore {
    suspend fun upsert(checkIn: CheckIn)

    suspend fun delete(id: String): Boolean

    suspend fun latest(): CheckIn?

    suspend fun find(id: String): CheckIn?

    suspend fun between(start: LocalDate, end: LocalDate): List<CheckIn>
}

interface SleepStore {
    suspend fun find(wakeDate: LocalDate): SleepRecord?

    suspend fun upsert(record: SleepRecord)

    suspend fun delete(id: String)

    suspend fun between(start: LocalDate, end: LocalDate): List<SleepRecord>
}

sealed class SaveCheckInResult {
    data class Saved(val checkIn: CheckIn) : SaveCheckInResult()

    data class Invalid(val problems: List<CheckInProblem>) : SaveCheckInResult()
}

class SaveCheckInUseCase(
    private val store: CheckInStore,
    private val clock: Clock,
    private val ids: UuidProvider,
) {
    suspend fun save(draft: CheckInDraft): SaveCheckInResult {
        val now = clock.instant()
        val problems = CheckInRules.validate(draft, now)
        if (problems.isNotEmpty()) return SaveCheckInResult.Invalid(problems)
        val id = draft.existingId ?: ids.newId()
        val assembled = CheckInRules.assemble(draft, id, now)
        store.upsert(assembled)
        return SaveCheckInResult.Saved(assembled)
    }

    suspend fun undo(id: String, savedAt: java.time.Instant): Boolean {
        if (!CheckInRules.canUndo(savedAt, clock)) return false
        return store.delete(id)
    }
}

sealed class SaveSleepResult {
    data class Saved(val record: SleepRecord, val warning: SleepWarning?) : SaveSleepResult()

    data class Invalid(val problem: SleepProblem) : SaveSleepResult()
}

class SaveSleepUseCase(
    private val store: SleepStore,
    private val clock: Clock,
    private val ids: UuidProvider,
) {
    suspend fun save(
        wakeDate: LocalDate,
        minutes: Int,
        source: SleepSource,
        zoneId: ZoneId,
    ): SaveSleepResult {
        val problem = CheckInRules.sleepProblem(minutes)
        if (problem != null) return SaveSleepResult.Invalid(problem)
        val existing = store.find(wakeDate)
        val now = clock.instant()
        val record = SleepRecord(
            id = existing?.id ?: ids.newId(),
            wakeDate = wakeDate,
            durationMinutes = minutes,
            source = source,
            zoneId = zoneId,
            recordedAtUtc = existing?.recordedAtUtc ?: now,
            updatedAtUtc = if (existing != null) now else null,
        )
        store.upsert(record)
        return SaveSleepResult.Saved(record, CheckInRules.sleepWarning(minutes))
    }
}
