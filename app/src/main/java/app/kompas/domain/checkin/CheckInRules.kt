package app.kompas.domain.checkin

import app.kompas.domain.model.CheckIn
import app.kompas.domain.model.CheckInSource
import app.kompas.domain.model.SCALE_VERSION
import app.kompas.domain.model.ValueProvenance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class CheckInProblem {
    RATING_OUT_OF_RANGE,
    PAIN_OUT_OF_RANGE,
    NOTE_TOO_LONG,
    TIME_IN_FUTURE,
    TIME_TOO_OLD,
    EMPTY,
}

enum class SleepProblem { OUT_OF_RANGE }

enum class SleepWarning { UNUSUALLY_SHORT, UNUSUALLY_LONG }

data class CheckInDraft(
    val experiencedAt: Instant,
    val zoneId: ZoneId,
    val stress: Int?,
    val stressTouched: Boolean,
    val physical: Int?,
    val physicalTouched: Boolean,
    val mental: Int?,
    val mentalTouched: Boolean,
    val note: String?,
    val painIntensity: Int?,
    val tagIds: List<String>,
    val reusedFrom: CheckIn?,
    val source: CheckInSource,
    val promptAlarmId: String?,
    val existingId: String? = null,
    val recordedAtUtc: Instant? = null,
)

object CheckInRules {
    const val NOTE_LIMIT = 2000
    const val NOTE_COUNTER_FROM = 1800
    val FUTURE_GRACE: Duration = Duration.ofMinutes(5)
    val PAST_LIMIT: Duration = Duration.ofDays(7)
    val RETRO_AFTER: Duration = Duration.ofHours(2)
    val REUSE_WINDOW: Duration = Duration.ofDays(7)

    fun validate(draft: CheckInDraft, now: Instant): List<CheckInProblem> {
        val problems = mutableListOf<CheckInProblem>()
        listOf(draft.stress, draft.physical, draft.mental).forEach { value ->
            if (value != null && value !in 1..5) problems += CheckInProblem.RATING_OUT_OF_RANGE
        }
        if (draft.painIntensity != null && draft.painIntensity !in 0..10) {
            problems += CheckInProblem.PAIN_OUT_OF_RANGE
        }
        if ((draft.note?.length ?: 0) > NOTE_LIMIT) problems += CheckInProblem.NOTE_TOO_LONG
        if (draft.experiencedAt.isAfter(now.plus(FUTURE_GRACE))) problems += CheckInProblem.TIME_IN_FUTURE
        if (draft.experiencedAt.isBefore(now.minus(PAST_LIMIT))) problems += CheckInProblem.TIME_TOO_OLD
        val hasContent = draft.stress != null || draft.physical != null || draft.mental != null ||
            !draft.note.isNullOrBlank() || draft.painIntensity != null || draft.tagIds.isNotEmpty()
        if (!hasContent) problems += CheckInProblem.EMPTY
        return problems
    }

    fun assemble(draft: CheckInDraft, id: String, recordedAt: Instant): CheckIn {
        val reused = draft.reusedFrom
        fun provenance(value: Int?, touched: Boolean, previous: Int?): ValueProvenance = when {
            value == null -> ValueProvenance.SKIPPED
            reused != null && !touched && value == previous -> ValueProvenance.REUSED
            else -> ValueProvenance.SELECTED
        }
        val localDate = draft.experiencedAt.atZone(draft.zoneId).toLocalDate()
        val retrospective = Duration.between(draft.experiencedAt, recordedAt) > RETRO_AFTER
        return CheckIn(
            id = id,
            experiencedAtUtc = draft.experiencedAt,
            recordedAtUtc = draft.recordedAtUtc ?: recordedAt,
            updatedAtUtc = if (draft.existingId != null) recordedAt else null,
            zoneId = draft.zoneId,
            localDate = localDate,
            source = draft.source,
            promptAlarmId = draft.promptAlarmId,
            stress = draft.stress,
            stressProv = provenance(draft.stress, draft.stressTouched, reused?.stress),
            physical = draft.physical,
            physicalProv = provenance(draft.physical, draft.physicalTouched, reused?.physical),
            mental = draft.mental,
            mentalProv = provenance(draft.mental, draft.mentalTouched, reused?.mental),
            reusedFromCheckInId = if (
                reused != null && (
                    provenance(draft.stress, draft.stressTouched, reused.stress) == ValueProvenance.REUSED ||
                        provenance(draft.physical, draft.physicalTouched, reused.physical) == ValueProvenance.REUSED ||
                        provenance(draft.mental, draft.mentalTouched, reused.mental) == ValueProvenance.REUSED
                    )
            ) {
                reused.id
            } else {
                null
            },
            painIntensity = draft.painIntensity,
            note = draft.note?.takeIf { it.isNotEmpty() },
            tagIds = draft.tagIds.distinct(),
            scaleVersion = SCALE_VERSION,
            isRetrospective = retrospective,
        )
    }

    fun sleepWarning(minutes: Int): SleepWarning? = when {
        minutes < 120 -> SleepWarning.UNUSUALLY_SHORT
        minutes > 840 -> SleepWarning.UNUSUALLY_LONG
        else -> null
    }

    fun sleepProblem(minutes: Int): SleepProblem? =
        if (minutes !in 0..1440) SleepProblem.OUT_OF_RANGE else null

    fun canUndo(savedAt: Instant, clock: Clock): Boolean =
        Duration.between(savedAt, clock.instant()) <= Duration.ofSeconds(5)
}
