package app.kompas.data.repo

import androidx.room.withTransaction
import app.kompas.data.db.AppDatabase
import app.kompas.data.db.entities.AlarmRegistrationEntity
import app.kompas.data.db.entities.AppSettingsEntity
import app.kompas.data.db.entities.CheckInEntity
import app.kompas.data.db.entities.CheckInReminderEntity
import app.kompas.data.db.entities.DoseEventEntity
import app.kompas.data.db.entities.DoseOccurrenceEntity
import app.kompas.data.db.entities.EpisodeEntity
import app.kompas.data.db.entities.MedicationEntity
import app.kompas.data.db.entities.QuietHoursEntity
import app.kompas.data.db.entities.ScheduleTimeEntity
import app.kompas.data.db.entities.ScheduleVersionEntity
import app.kompas.data.db.entities.SleepRecordEntity
import app.kompas.data.db.entities.SymptomTagEntity
import app.kompas.domain.checkin.CheckInStore
import app.kompas.domain.checkin.SleepStore
import app.kompas.domain.medication.MedicationStore
import app.kompas.domain.medication.OccurrenceGenerator
import app.kompas.domain.model.AlarmItem
import app.kompas.domain.model.AlarmKind
import app.kompas.domain.model.AlarmMode
import app.kompas.domain.model.AlarmRegistration
import app.kompas.domain.model.AlarmState
import app.kompas.domain.model.AppSettings
import app.kompas.domain.model.CheckIn
import app.kompas.domain.model.CheckInReminder
import app.kompas.domain.model.DoseEvent
import app.kompas.domain.model.DoseOccurrence
import app.kompas.domain.model.Episode
import app.kompas.domain.model.Medication
import app.kompas.domain.model.QuietHours
import app.kompas.domain.model.ScheduleVersion
import app.kompas.domain.model.SleepRecord
import app.kompas.domain.model.SymptomTag
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CheckInRepository(private val db: suspend () -> AppDatabase) : CheckInStore {
    override suspend fun upsert(checkIn: CheckIn) {
        db().checkInDao().replace(checkIn.toEntity(), checkIn.tagIds)
    }

    override suspend fun delete(id: String): Boolean = db().checkInDao().delete(id) > 0

    override suspend fun latest(): CheckIn? = db().checkInDao().latest()?.toModel(db())

    override suspend fun find(id: String): CheckIn? = db().checkInDao().find(id)?.toModel(db())

    override suspend fun between(start: LocalDate, end: LocalDate): List<CheckIn> {
        val database = db()
        return database.checkInDao().between(start.toString(), end.toString()).map { it.toModel(database) }
    }

    suspend fun tags(): List<SymptomTag> = db().tagDao().active().map { it.toModel() }

    suspend fun addTag(tag: SymptomTag) {
        db().tagDao().insert(tag.toEntity())
    }
}

class SleepRepository(private val db: suspend () -> AppDatabase) : SleepStore {
    override suspend fun find(wakeDate: LocalDate): SleepRecord? = db().sleepDao().find(wakeDate.toString())?.toModel()

    override suspend fun upsert(record: SleepRecord) {
        db().sleepDao().upsert(record.toEntity())
    }

    override suspend fun delete(id: String) {
        db().sleepDao().delete(id)
    }

    override suspend fun between(start: LocalDate, end: LocalDate): List<SleepRecord> =
        db().sleepDao().between(start.toString(), end.toString()).map { it.toModel() }
}

class MedicationRepository(private val db: suspend () -> AppDatabase) : MedicationStore {
    override suspend fun medications(): List<Medication> = db().medicationDao().all().map { it.toModel() }

    override suspend fun medication(id: String): Medication? = db().medicationDao().find(id)?.toModel()

    override suspend fun versions(medicationId: String): List<ScheduleVersion> {
        val database = db()
        return database.medicationDao().versions(medicationId).map { version ->
            version.toModel(database.medicationDao().times(version.id).map { LocalTime.parse(it.localTime) })
        }
    }

    override suspend fun saveMedication(medication: Medication, version: ScheduleVersion) {
        val database = db()
        database.withTransaction {
            database.medicationDao().upsert(medication.toEntity())
            database.medicationDao().insertVersion(version.toEntity())
            if (version.times.isNotEmpty()) {
                database.medicationDao().insertTimes(
                    version.times.map { ScheduleTimeEntity(version.id, it.toString()) },
                )
            }
            regenerate(database, medication.id, LocalDate.now(), ZoneId.systemDefault())
        }
    }

    override suspend fun archive(medicationId: String, at: Instant) {
        db().medicationDao().archive(medicationId, at.toEpochMilli())
    }

    override suspend fun occurrences(from: LocalDate, to: LocalDate): List<DoseOccurrence> =
        db().medicationDao().occurrencesBetween(from.toString(), to.toString()).map { it.toModel() }

    override suspend fun eventsFor(occurrenceIds: List<String>): List<DoseEvent> {
        if (occurrenceIds.isEmpty()) return emptyList()
        return db().medicationDao().eventsForOccurrences(occurrenceIds).map { it.toModel() }
    }

    override suspend fun eventsForMedication(medicationId: String): List<DoseEvent> =
        db().medicationDao().eventsForMedication(medicationId).map { it.toModel() }

    override suspend fun findByActionId(actionId: String): DoseEvent? =
        db().medicationDao().eventByAction(actionId)?.toModel()

    override suspend fun insertEvent(event: DoseEvent): Boolean =
        db().medicationDao().insertEvent(event.toEntity()) != -1L

    override suspend fun regenerateAll(today: LocalDate, zone: ZoneId) {
        val database = db()
        database.withTransaction {
            database.medicationDao().all().forEach { medication ->
                regenerate(database, medication.id, today, zone)
            }
        }
    }

    private suspend fun regenerate(database: AppDatabase, medicationId: String, today: LocalDate, zone: ZoneId) {
        val versions = database.medicationDao().versions(medicationId).map { version ->
            version.toModel(database.medicationDao().times(version.id).map { LocalTime.parse(it.localTime) })
        }
        val generated = OccurrenceGenerator.plan(versions, today, zone)
        val protected = database.medicationDao().occurrenceIdsWithEvents().toSet()
        val window = OccurrenceGenerator.window(today)
        val existing = database.medicationDao().plannedFrom(medicationId, window.start.toString())
        val cancel = OccurrenceGenerator.idsToCancel(
            existing.map { it.id }.toSet(),
            generated.map { it.id }.toSet(),
            protected,
        )
        cancel.forEach { database.medicationDao().cancelOccurrence(it) }
        generated.filter { it.id !in protected }.forEach { database.medicationDao().upsertOccurrence(it.toEntity()) }
    }
}

class ReminderRepository(private val db: suspend () -> AppDatabase) {
    suspend fun all(): List<CheckInReminder> = db().reminderDao().all().map { it.toModel() }

    suspend fun save(reminder: CheckInReminder) {
        db().reminderDao().upsert(reminder.toEntity())
    }

    suspend fun delete(id: String) {
        db().reminderDao().delete(id)
    }

    suspend fun disableAll() {
        db().reminderDao().disableAll()
    }

    suspend fun quietHours(): QuietHours = db().reminderDao().quietHours()?.toModel() ?: QuietHours()

    suspend fun saveQuietHours(quietHours: QuietHours) {
        db().reminderDao().upsertQuietHours(quietHours.toEntity())
    }
}

class SettingsRepository(private val db: suspend () -> AppDatabase) {
    suspend fun get(): AppSettings = db().settingsDao().get()?.toModel() ?: AppSettings()

    suspend fun save(settings: AppSettings) {
        db().settingsDao().upsert(settings.toEntity())
    }

    suspend fun bumpGeneration(): Long {
        val database = db()
        database.settingsDao().bumpGeneration()
        return database.settingsDao().get()?.scheduleGeneration ?: 0L
    }

    suspend fun setLastExport(at: Instant) {
        db().settingsDao().setLastExport(at.toEpochMilli())
    }

    suspend fun setRemindersPaused(paused: Boolean) {
        db().settingsDao().setRemindersPaused(paused)
    }
}

class AlarmRepository(private val db: suspend () -> AppDatabase) {
    suspend fun registered(): List<AlarmRegistration> = db().alarmDao().registered().map { it.toModel() }

    suspend fun all(): List<AlarmRegistration> = db().alarmDao().all().map { it.toModel() }

    suspend fun save(registration: AlarmRegistration) {
        db().alarmDao().upsert(registration.toEntity())
    }

    suspend fun cancel(id: String) {
        db().alarmDao().cancel(id)
    }

    suspend fun markFired(id: String, at: Instant): Boolean = db().alarmDao().markFired(id, at.toEpochMilli()) > 0

    suspend fun find(id: String): AlarmRegistration? = db().alarmDao().find(id)?.toModel()

    suspend fun nextRequestCode(): Int = db().alarmDao().maxRequestCode() + 1
}

class EpisodeRepository(private val db: suspend () -> AppDatabase) {
    suspend fun all(): List<Episode> = db().episodeDao().all().map { it.toModel() }

    suspend fun save(episode: Episode) {
        db().episodeDao().upsert(episode.toEntity())
    }
}

private fun CheckIn.toEntity() = CheckInEntity(
    id, experiencedAtUtc.toEpochMilli(), recordedAtUtc.toEpochMilli(), updatedAtUtc?.toEpochMilli(),
    zoneId.id, localDate.toString(), source, promptAlarmId, stress, stressProv, physical, physicalProv,
    mental, mentalProv, reusedFromCheckInId, painIntensity, note, scaleVersion, isRetrospective,
)

private suspend fun CheckInEntity.toModel(db: AppDatabase) = CheckIn(
    id = id,
    experiencedAtUtc = Instant.ofEpochMilli(experiencedAtUtc),
    recordedAtUtc = Instant.ofEpochMilli(recordedAtUtc),
    updatedAtUtc = updatedAtUtc?.let(Instant::ofEpochMilli),
    zoneId = ZoneId.of(zoneId),
    localDate = LocalDate.parse(localDate),
    source = source,
    promptAlarmId = promptAlarmId,
    stress = stress,
    stressProv = stressProv,
    physical = physical,
    physicalProv = physicalProv,
    mental = mental,
    mentalProv = mentalProv,
    reusedFromCheckInId = reusedFromCheckInId,
    painIntensity = painIntensity,
    note = note,
    tagIds = db.checkInDao().tagIds(id),
    scaleVersion = scaleVersion,
    isRetrospective = isRetrospective,
)

private fun SymptomTag.toEntity() = SymptomTagEntity(id, labelPl, labelEn, isBuiltIn, archivedAtUtc?.toEpochMilli(), sortOrder)

private fun SymptomTagEntity.toModel() = SymptomTag(
    id, labelPl, labelEn, isBuiltIn, archivedAtUtc?.let(Instant::ofEpochMilli), sortOrder,
)

private fun SleepRecord.toEntity() = SleepRecordEntity(
    id, wakeDate.toString(), durationMinutes, definition, source, zoneId.id,
    recordedAtUtc.toEpochMilli(), updatedAtUtc?.toEpochMilli(),
)

private fun SleepRecordEntity.toModel() = SleepRecord(
    id, LocalDate.parse(wakeDate), durationMinutes, definition, source, ZoneId.of(zoneId),
    Instant.ofEpochMilli(recordedAtUtc), updatedAtUtc?.let(Instant::ofEpochMilli),
)

private fun Medication.toEntity() = MedicationEntity(
    id, name, strengthText, form, createdAtUtc.toEpochMilli(), archivedAtUtc?.toEpochMilli(),
)

private fun MedicationEntity.toModel() = Medication(
    id, name, strengthText, form, Instant.ofEpochMilli(createdAtUtc), archivedAtUtc?.let(Instant::ofEpochMilli),
)

private fun ScheduleVersion.toEntity() = ScheduleVersionEntity(
    id, medicationId, versionNo, effectiveFromDate.toString(), kind, doseAmount, doseUnit, daysOfWeekMask,
    zonePolicy, fixedZoneId?.id, importance, instructions, createdAtUtc.toEpochMilli(),
)

private fun ScheduleVersionEntity.toModel(times: List<LocalTime>) = ScheduleVersion(
    id, medicationId, versionNo, LocalDate.parse(effectiveFromDate), kind, doseAmount, doseUnit, daysOfWeekMask,
    zonePolicy, fixedZoneId?.let(ZoneId::of), importance, instructions, Instant.ofEpochMilli(createdAtUtc), times,
)

private fun DoseOccurrence.toEntity() = DoseOccurrenceEntity(
    id, medicationId, scheduleVersionId, localDate.toString(), localTime.toString(), zoneId.id,
    scheduledAtUtc.toEpochMilli(), dstResolution, plannedDoseAmount, plannedDoseUnit, state,
)

private fun DoseOccurrenceEntity.toModel() = DoseOccurrence(
    id, medicationId, scheduleVersionId, LocalDate.parse(localDate), LocalTime.parse(localTime), ZoneId.of(zoneId),
    Instant.ofEpochMilli(scheduledAtUtc), dstResolution, plannedDoseAmount, plannedDoseUnit, state,
)

private fun DoseEvent.toEntity() = DoseEventEntity(
    id, actionId, occurrenceId, medicationId, status, actualAtUtc?.toEpochMilli(), recordedAtUtc.toEpochMilli(),
    zoneId.id, actualDoseAmount, actualDoseUnit, snoozeUntilUtc?.toEpochMilli(), source, supersedesEventId,
)

private fun DoseEventEntity.toModel() = DoseEvent(
    id, actionId, occurrenceId, medicationId, status, actualAtUtc?.let(Instant::ofEpochMilli),
    Instant.ofEpochMilli(recordedAtUtc), ZoneId.of(zoneId), actualDoseAmount, actualDoseUnit,
    snoozeUntilUtc?.let(Instant::ofEpochMilli), source, supersedesEventId,
)

private fun CheckInReminder.toEntity() = CheckInReminderEntity(
    id, localTime.toString(), daysOfWeekMask, enabled, label, createdAtUtc.toEpochMilli(),
)

private fun CheckInReminderEntity.toModel() = CheckInReminder(
    id, LocalTime.parse(localTime), daysOfWeekMask, enabled, label, Instant.ofEpochMilli(createdAtUtc),
)

private fun QuietHours.toEntity() = QuietHoursEntity(
    enabled = enabled,
    startLocal = startLocal.toString(),
    endLocal = endLocal.toString(),
    daysOfWeekMask = daysOfWeekMask,
    checkInBehavior = checkInBehavior,
    medicationBehavior = medicationBehavior,
)

private fun QuietHoursEntity.toModel() = QuietHours(
    enabled, LocalTime.parse(startLocal), LocalTime.parse(endLocal), daysOfWeekMask, checkInBehavior, medicationBehavior,
)

private fun AppSettings.toEntity() = AppSettingsEntity(
    showMedNamesInNotifications = showMedNamesInNotifications,
    sameAsLastEnabled = sameAsLastEnabled,
    enabledTilesMask = enabledTilesMask,
    sleepPresetMinutes = sleepPresetMinutes.joinToString(","),
    trendsEnabled = trendsEnabled,
    quickRateDimension = quickRateDimension,
    medSnoozeMinutes = medSnoozeMinutes,
    ageBand = ageBand,
    lastExportAtUtc = lastExportAtUtc?.toEpochMilli(),
    scheduleGeneration = scheduleGeneration,
    remindersPausedAfterRestore = remindersPausedAfterRestore,
)

private fun AppSettingsEntity.toModel() = AppSettings(
    showMedNamesInNotifications, sameAsLastEnabled, enabledTilesMask,
    sleepPresetMinutes.split(',').mapNotNull { it.toIntOrNull() },
    trendsEnabled, quickRateDimension, medSnoozeMinutes, ageBand,
    lastExportAtUtc?.let(Instant::ofEpochMilli), scheduleGeneration, remindersPausedAfterRestore,
)

private fun Episode.toEntity() = EpisodeEntity(id, label, startDate.toString(), endDate?.toString(), note)

private fun EpisodeEntity.toModel() = Episode(id, label, LocalDate.parse(startDate), endDate?.let(LocalDate::parse), note)

private fun AlarmRegistration.toEntity() = AlarmRegistrationEntity(
    id, triggerAtUtc.toEpochMilli(), requestCode, mode, items.toPayload(), generation, state, firedAtUtc?.toEpochMilli(),
)

private fun AlarmRegistrationEntity.toModel() = AlarmRegistration(
    id, Instant.ofEpochMilli(triggerAtUtc), requestCode, mode, payloadJson.toItems(), generation, state,
    firedAtUtc?.let(Instant::ofEpochMilli),
)

private fun List<AlarmItem>.toPayload(): String {
    val array = JSONArray()
    forEach { item ->
        array.put(JSONObject().put("kind", item.kind.name).put("refId", item.refId).put("silent", item.silent))
    }
    return array.toString()
}

private fun String.toItems(): List<AlarmItem> {
    val array = JSONArray(this)
    return List(array.length()) { index ->
        val obj = array.getJSONObject(index)
        AlarmItem(AlarmKind.valueOf(obj.getString("kind")), obj.getString("refId"), obj.optBoolean("silent"))
    }
}
