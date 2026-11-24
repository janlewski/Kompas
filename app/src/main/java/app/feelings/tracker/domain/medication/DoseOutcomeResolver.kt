package app.feelings.tracker.domain.medication

import app.feelings.tracker.domain.model.DoseEvent
import app.feelings.tracker.domain.model.DoseOutcome
import app.feelings.tracker.domain.model.DoseOutcomeKind
import app.feelings.tracker.domain.model.DoseStatus

object DoseOutcomeResolver {
    fun resolve(events: List<DoseEvent>): DoseOutcome {
        val superseded = events.mapNotNull { it.supersedesEventId }.toSet()
        val current = events
            .filter { it.id !in superseded }
            .filter { it.status == DoseStatus.TAKEN || it.status == DoseStatus.SKIPPED || it.status == DoseStatus.UNKNOWN }
            .maxByOrNull { it.recordedAtUtc }
        if (current == null) return DoseOutcome(DoseOutcomeKind.NO_RECORD, null)
        val kind = when (current.status) {
            DoseStatus.TAKEN -> DoseOutcomeKind.TAKEN
            DoseStatus.SKIPPED -> DoseOutcomeKind.SKIPPED
            DoseStatus.UNKNOWN -> DoseOutcomeKind.UNKNOWN
            DoseStatus.SNOOZED -> DoseOutcomeKind.NO_RECORD
        }
        return DoseOutcome(kind, current)
    }
}
