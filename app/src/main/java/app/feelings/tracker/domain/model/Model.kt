package app.feelings.tracker.domain.model

import app.feelings.tracker.core.time.DstResolution
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class CheckInSource { APP, NOTIFICATION_OPEN, NOTIFICATION_QUICK }

enum class ValueProvenance { SELECTED, REUSED, SKIPPED }

enum class SleepDefinition { ASLEEP_ESTIMATE }

enum class SleepSource { MANUAL, REUSED_CONFIRMED }

enum class ScheduleKind { SCHEDULED, PRN, STOPPED }

enum class ZonePolicy { FOLLOW_DEVICE, FIXED_ZONE }

enum class MedImportance { STANDARD, IMPORTANT }

enum class DoseOccurrenceState { PLANNED, CANCELLED_BY_NEW_VERSION }

enum class DoseStatus { TAKEN, SKIPPED, SNOOZED, UNKNOWN }

enum class DoseSource { APP, NOTIFICATION }

enum class QuickRateDimension { OFF, STRESS, PHYSICAL, MENTAL }

enum class AgeBand { UNDER_13, AGE_13_17, ADULT, UNSPECIFIED }

enum class CheckInQuietBehavior { SUPPRESS, DEFER_TO_END }

enum class MedicationQuietBehavior { DELIVER_NORMALLY, DELIVER_SILENTLY }

enum class AlarmMode { EXACT, APPROXIMATE_WINDOW }

enum class AlarmState { REGISTERED, FIRED, CANCELLED }

enum class AlarmKind { CHECK_IN, DOSE, SNOOZE }

enum class AppLanguage { SYSTEM, PL, EN }

enum class ThemePref { SYSTEM, LIGHT, DARK }

const val SCALE_VERSION = 1
const val TILE_SLEEP = 1
const val TILE_STRESS = 2
const val TILE_PHYSICAL = 4
const val TILE_MENTAL = 8
const val TILE_NOTES = 16
const val ALL_TILES = TILE_SLEEP or TILE_STRESS or TILE_PHYSICAL or TILE_MENTAL or TILE_NOTES

data class CheckIn(
    val id: String,
    val experiencedAtUtc: Instant,
    val recordedAtUtc: Instant,
    val updatedAtUtc: Instant?,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val source: CheckInSource,
    val promptAlarmId: String?,
    val stress: Int?,
    val stressProv: ValueProvenance,
    val physical: Int?,
    val physicalProv: ValueProvenance,
    val mental: Int?,
    val mentalProv: ValueProvenance,
    val reusedFromCheckInId: String?,
    val painIntensity: Int?,
    val note: String?,
    val tagIds: List<String>,
    val scaleVersion: Int = SCALE_VERSION,
    val isRetrospective: Boolean,
)

data class SymptomTag(
    val id: String,
    val labelPl: String,
    val labelEn: String,
    val isBuiltIn: Boolean,
    val archivedAtUtc: Instant?,
    val sortOrder: Int,
)

data class SleepRecord(
    val id: String,
    val wakeDate: LocalDate,
    val durationMinutes: Int,
    val definition: SleepDefinition = SleepDefinition.ASLEEP_ESTIMATE,
    val source: SleepSource,
    val zoneId: ZoneId,
    val recordedAtUtc: Instant,
    val updatedAtUtc: Instant?,
)

data class Medication(
    val id: String,
    val name: String,
    val strengthText: String?,
    val form: String?,
    val createdAtUtc: Instant,
    val archivedAtUtc: Instant?,
)

data class ScheduleVersion(
    val id: String,
    val medicationId: String,
    val versionNo: Int,
    val effectiveFromDate: LocalDate,
    val kind: ScheduleKind,
    val doseAmount: String?,
    val doseUnit: String?,
    val daysOfWeekMask: Int,
    val zonePolicy: ZonePolicy,
    val fixedZoneId: ZoneId?,
    val importance: MedImportance,
    val instructions: String?,
    val createdAtUtc: Instant,
    val times: List<LocalTime>,
)

data class DoseOccurrence(
    val id: String,
    val medicationId: String,
    val scheduleVersionId: String,
    val localDate: LocalDate,
    val localTime: LocalTime,
    val zoneId: ZoneId,
    val scheduledAtUtc: Instant,
    val dstResolution: DstResolution,
    val plannedDoseAmount: String?,
    val plannedDoseUnit: String?,
    val state: DoseOccurrenceState,
)

data class DoseEvent(
    val id: String,
    val actionId: String,
    val occurrenceId: String?,
    val medicationId: String,
    val status: DoseStatus,
    val actualAtUtc: Instant?,
    val recordedAtUtc: Instant,
    val zoneId: ZoneId,
    val actualDoseAmount: String?,
    val actualDoseUnit: String?,
    val snoozeUntilUtc: Instant?,
    val source: DoseSource,
    val supersedesEventId: String?,
)

enum class DoseOutcomeKind { TAKEN, SKIPPED, UNKNOWN, NO_RECORD }

data class DoseOutcome(val kind: DoseOutcomeKind, val event: DoseEvent?)

data class CheckInReminder(
    val id: String,
    val localTime: LocalTime,
    val daysOfWeekMask: Int,
    val enabled: Boolean,
    val label: String?,
    val createdAtUtc: Instant,
)

data class QuietHours(
    val enabled: Boolean = false,
    val startLocal: LocalTime = LocalTime.of(22, 0),
    val endLocal: LocalTime = LocalTime.of(7, 0),
    val daysOfWeekMask: Int = 127,
    val checkInBehavior: CheckInQuietBehavior = CheckInQuietBehavior.SUPPRESS,
    val medicationBehavior: MedicationQuietBehavior = MedicationQuietBehavior.DELIVER_NORMALLY,
)

data class AppSettings(
    val showMedNamesInNotifications: Boolean = false,
    val sameAsLastEnabled: Boolean = true,
    val enabledTilesMask: Int = ALL_TILES,
    val sleepPresetMinutes: List<Int> = listOf(360, 420, 480, 540),
    val trendsEnabled: Boolean = true,
    val quickRateDimension: QuickRateDimension = QuickRateDimension.OFF,
    val medSnoozeMinutes: Int = 10,
    val ageBand: AgeBand = AgeBand.UNSPECIFIED,
    val lastExportAtUtc: Instant? = null,
    val scheduleGeneration: Long = 0,
    val remindersPausedAfterRestore: Boolean = false,
)

data class Episode(
    val id: String,
    val label: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val note: String?,
)

data class AlarmItem(
    val kind: AlarmKind,
    val refId: String,
    val silent: Boolean,
)

data class PlannedAlarm(
    val triggerAtUtc: Instant,
    val items: List<AlarmItem>,
)

data class AlarmRegistration(
    val id: String,
    val triggerAtUtc: Instant,
    val requestCode: Int,
    val mode: AlarmMode,
    val items: List<AlarmItem>,
    val generation: Long,
    val state: AlarmState,
    val firedAtUtc: Instant?,
)
