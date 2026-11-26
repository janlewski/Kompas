package app.kompas.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kompas.core.time.DstResolution
import app.kompas.domain.model.AgeBand
import app.kompas.domain.model.AlarmMode
import app.kompas.domain.model.AlarmState
import app.kompas.domain.model.CheckInQuietBehavior
import app.kompas.domain.model.CheckInSource
import app.kompas.domain.model.DoseOccurrenceState
import app.kompas.domain.model.DoseSource
import app.kompas.domain.model.DoseStatus
import app.kompas.domain.model.MedImportance
import app.kompas.domain.model.MedicationQuietBehavior
import app.kompas.domain.model.QuickRateDimension
import app.kompas.domain.model.ScheduleKind
import app.kompas.domain.model.SleepDefinition
import app.kompas.domain.model.SleepSource
import app.kompas.domain.model.ValueProvenance
import app.kompas.domain.model.ZonePolicy

@Entity(
    tableName = "check_in",
    indices = [Index("localDate"), Index("experiencedAtUtc")],
)
data class CheckInEntity(
    @PrimaryKey val id: String,
    val experiencedAtUtc: Long,
    val recordedAtUtc: Long,
    val updatedAtUtc: Long?,
    val zoneId: String,
    val localDate: String,
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
    val scaleVersion: Int,
    val isRetrospective: Boolean,
)

@Entity(tableName = "symptom_tag")
data class SymptomTagEntity(
    @PrimaryKey val id: String,
    val labelPl: String,
    val labelEn: String,
    val isBuiltIn: Boolean,
    val archivedAtUtc: Long?,
    val sortOrder: Int,
)

@Entity(
    tableName = "check_in_tag",
    primaryKeys = ["checkInId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = CheckInEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SymptomTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("tagId")],
)
data class CheckInTagEntity(
    val checkInId: String,
    val tagId: String,
)

@Entity(
    tableName = "sleep_record",
    indices = [Index(value = ["wakeDate"], unique = true)],
)
data class SleepRecordEntity(
    @PrimaryKey val id: String,
    val wakeDate: String,
    val durationMinutes: Int,
    val definition: SleepDefinition,
    val source: SleepSource,
    val zoneId: String,
    val recordedAtUtc: Long,
    val updatedAtUtc: Long?,
)

@Entity(tableName = "medication")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val strengthText: String?,
    val form: String?,
    val createdAtUtc: Long,
    val archivedAtUtc: Long?,
)

@Entity(
    tableName = "schedule_version",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["medicationId", "versionNo"], unique = true),
        Index(value = ["medicationId", "effectiveFromDate"]),
    ],
)
data class ScheduleVersionEntity(
    @PrimaryKey val id: String,
    val medicationId: String,
    val versionNo: Int,
    val effectiveFromDate: String,
    val kind: ScheduleKind,
    val doseAmount: String?,
    val doseUnit: String?,
    val daysOfWeekMask: Int,
    val zonePolicy: ZonePolicy,
    val fixedZoneId: String?,
    val importance: MedImportance,
    val instructions: String?,
    val createdAtUtc: Long,
)

@Entity(
    tableName = "schedule_time",
    primaryKeys = ["versionId", "localTime"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleTimeEntity(
    val versionId: String,
    val localTime: String,
)

@Entity(
    tableName = "dose_occurrence",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleVersionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("medicationId"),
        Index("scheduledAtUtc"),
        Index(value = ["scheduleVersionId", "localDate", "localTime"], unique = true),
    ],
)
data class DoseOccurrenceEntity(
    @PrimaryKey val id: String,
    val medicationId: String,
    val scheduleVersionId: String,
    val localDate: String,
    val localTime: String,
    val zoneId: String,
    val scheduledAtUtc: Long,
    val dstResolution: DstResolution,
    val plannedDoseAmount: String?,
    val plannedDoseUnit: String?,
    val state: DoseOccurrenceState,
)

@Entity(
    tableName = "dose_event",
    foreignKeys = [
        ForeignKey(
            entity = DoseOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["actionId"], unique = true),
        Index("medicationId"),
        Index(value = ["occurrenceId", "recordedAtUtc"]),
    ],
)
data class DoseEventEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val occurrenceId: String?,
    val medicationId: String,
    val status: DoseStatus,
    val actualAtUtc: Long?,
    val recordedAtUtc: Long,
    val zoneId: String,
    val actualDoseAmount: String?,
    val actualDoseUnit: String?,
    val snoozeUntilUtc: Long?,
    val source: DoseSource,
    val supersedesEventId: String?,
)

@Entity(tableName = "checkin_reminder")
data class CheckInReminderEntity(
    @PrimaryKey val id: String,
    val localTime: String,
    val daysOfWeekMask: Int,
    val enabled: Boolean,
    val label: String?,
    val createdAtUtc: Long,
)

@Entity(tableName = "quiet_hours")
data class QuietHoursEntity(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean,
    val startLocal: String,
    val endLocal: String,
    val daysOfWeekMask: Int,
    val checkInBehavior: CheckInQuietBehavior,
    val medicationBehavior: MedicationQuietBehavior,
)

@Entity(
    tableName = "alarm_registration",
    indices = [Index("triggerAtUtc"), Index(value = ["requestCode"], unique = true)],
)
data class AlarmRegistrationEntity(
    @PrimaryKey val id: String,
    val triggerAtUtc: Long,
    val requestCode: Int,
    val mode: AlarmMode,
    val payloadJson: String,
    val generation: Long,
    val state: AlarmState,
    val firedAtUtc: Long?,
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val showMedNamesInNotifications: Boolean,
    val sameAsLastEnabled: Boolean,
    val enabledTilesMask: Int,
    val sleepPresetMinutes: String,
    val trendsEnabled: Boolean,
    val quickRateDimension: QuickRateDimension,
    val medSnoozeMinutes: Int,
    val ageBand: AgeBand,
    val lastExportAtUtc: Long?,
    val scheduleGeneration: Long,
    val remindersPausedAfterRestore: Boolean,
)

@Entity(
    tableName = "episode",
    indices = [Index("startDate")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val startDate: String,
    val endDate: String?,
    val note: String?,
)
