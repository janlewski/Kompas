package app.kompas.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.kompas.data.db.entities.AlarmRegistrationEntity
import app.kompas.data.db.entities.AppSettingsEntity
import app.kompas.data.db.entities.CheckInEntity
import app.kompas.data.db.entities.CheckInReminderEntity
import app.kompas.data.db.entities.CheckInTagEntity
import app.kompas.data.db.entities.DoseEventEntity
import app.kompas.data.db.entities.DoseOccurrenceEntity
import app.kompas.data.db.entities.EpisodeEntity
import app.kompas.data.db.entities.MedicationEntity
import app.kompas.data.db.entities.QuietHoursEntity
import app.kompas.data.db.entities.ScheduleTimeEntity
import app.kompas.data.db.entities.ScheduleVersionEntity
import app.kompas.data.db.entities.SleepRecordEntity
import app.kompas.data.db.entities.SymptomTagEntity

@Dao
interface CheckInDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CheckInEntity)

    @Query("DELETE FROM check_in WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM check_in WHERE id = :id")
    suspend fun find(id: String): CheckInEntity?

    @Query("SELECT * FROM check_in ORDER BY recordedAtUtc DESC LIMIT 1")
    suspend fun latest(): CheckInEntity?

    @Query("SELECT * FROM check_in WHERE localDate BETWEEN :start AND :end ORDER BY experiencedAtUtc DESC")
    suspend fun between(start: String, end: String): List<CheckInEntity>

    @Query("SELECT * FROM check_in ORDER BY experiencedAtUtc")
    suspend fun all(): List<CheckInEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceTags(tags: List<CheckInTagEntity>)

    @Query("DELETE FROM check_in_tag WHERE checkInId = :checkInId")
    suspend fun clearTags(checkInId: String)

    @Query("SELECT tagId FROM check_in_tag WHERE checkInId = :checkInId")
    suspend fun tagIds(checkInId: String): List<String>

    @Query("SELECT * FROM check_in_tag")
    suspend fun allTags(): List<CheckInTagEntity>

    @Transaction
    suspend fun replace(entity: CheckInEntity, tagIds: List<String>) {
        upsert(entity)
        clearTags(entity.id)
        if (tagIds.isNotEmpty()) {
            replaceTags(tagIds.map { CheckInTagEntity(entity.id, it) })
        }
    }
}

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SleepRecordEntity)

    @Query("SELECT * FROM sleep_record WHERE wakeDate = :wakeDate")
    suspend fun find(wakeDate: String): SleepRecordEntity?

    @Query("DELETE FROM sleep_record WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM sleep_record WHERE wakeDate BETWEEN :start AND :end")
    suspend fun between(start: String, end: String): List<SleepRecordEntity>

    @Query("SELECT * FROM sleep_record")
    suspend fun all(): List<SleepRecordEntity>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SymptomTagEntity)

    @Query("SELECT * FROM symptom_tag WHERE archivedAtUtc IS NULL ORDER BY sortOrder, labelEn")
    suspend fun active(): List<SymptomTagEntity>

    @Query("SELECT * FROM symptom_tag")
    suspend fun all(): List<SymptomTagEntity>
}

@Dao
interface MedicationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicationEntity)

    @Query("SELECT * FROM medication ORDER BY createdAtUtc")
    suspend fun all(): List<MedicationEntity>

    @Query("SELECT * FROM medication WHERE id = :id")
    suspend fun find(id: String): MedicationEntity?

    @Query("UPDATE medication SET archivedAtUtc = :at WHERE id = :id")
    suspend fun archive(id: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: ScheduleVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimes(times: List<ScheduleTimeEntity>)

    @Query("SELECT * FROM schedule_version WHERE medicationId = :medicationId ORDER BY versionNo")
    suspend fun versions(medicationId: String): List<ScheduleVersionEntity>

    @Query("SELECT * FROM schedule_version")
    suspend fun allVersions(): List<ScheduleVersionEntity>

    @Query("SELECT * FROM schedule_time WHERE versionId = :versionId")
    suspend fun times(versionId: String): List<ScheduleTimeEntity>

    @Query("SELECT * FROM schedule_time")
    suspend fun allTimes(): List<ScheduleTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrence(entity: DoseOccurrenceEntity)

    @Query(
        "UPDATE dose_occurrence SET state = 'CANCELLED_BY_NEW_VERSION' " +
            "WHERE id = :id AND state = 'PLANNED'",
    )
    suspend fun cancelOccurrence(id: String)

    @Query(
        "SELECT * FROM dose_occurrence WHERE medicationId = :medicationId AND state = 'PLANNED' " +
            "AND localDate >= :fromDate",
    )
    suspend fun plannedFrom(medicationId: String, fromDate: String): List<DoseOccurrenceEntity>

    @Query("SELECT * FROM dose_occurrence WHERE localDate BETWEEN :start AND :end")
    suspend fun occurrencesBetween(start: String, end: String): List<DoseOccurrenceEntity>

    @Query("SELECT * FROM dose_occurrence WHERE id = :id")
    suspend fun occurrence(id: String): DoseOccurrenceEntity?

    @Query("SELECT * FROM dose_occurrence")
    suspend fun allOccurrences(): List<DoseOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: DoseEventEntity): Long

    @Query("SELECT * FROM dose_event WHERE actionId = :actionId")
    suspend fun eventByAction(actionId: String): DoseEventEntity?

    @Query("SELECT * FROM dose_event WHERE occurrenceId IN (:ids)")
    suspend fun eventsForOccurrences(ids: List<String>): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event WHERE medicationId = :medicationId")
    suspend fun eventsForMedication(medicationId: String): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event")
    suspend fun allEvents(): List<DoseEventEntity>

    @Query("SELECT occurrenceId FROM dose_event WHERE occurrenceId IS NOT NULL")
    suspend fun occurrenceIdsWithEvents(): List<String>
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CheckInReminderEntity)

    @Query("DELETE FROM checkin_reminder WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM checkin_reminder ORDER BY localTime")
    suspend fun all(): List<CheckInReminderEntity>

    @Query("UPDATE checkin_reminder SET enabled = 0")
    suspend fun disableAll()

    @Query("SELECT * FROM quiet_hours WHERE id = 1")
    suspend fun quietHours(): QuietHoursEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuietHours(entity: QuietHoursEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSettingsEntity)

    @Query("UPDATE app_settings SET scheduleGeneration = scheduleGeneration + 1 WHERE id = 1")
    suspend fun bumpGeneration()

    @Query("UPDATE app_settings SET lastExportAtUtc = :at WHERE id = 1")
    suspend fun setLastExport(at: Long)

    @Query("UPDATE app_settings SET remindersPausedAfterRestore = :paused WHERE id = 1")
    suspend fun setRemindersPaused(paused: Boolean)
}

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlarmRegistrationEntity)

    @Query("SELECT * FROM alarm_registration WHERE state = 'REGISTERED' ORDER BY triggerAtUtc")
    suspend fun registered(): List<AlarmRegistrationEntity>

    @Query("SELECT * FROM alarm_registration")
    suspend fun all(): List<AlarmRegistrationEntity>

    @Query("SELECT COALESCE(MAX(requestCode), 0) FROM alarm_registration")
    suspend fun maxRequestCode(): Int

    @Query("UPDATE alarm_registration SET state = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: String)

    @Query("UPDATE alarm_registration SET state = 'FIRED', firedAtUtc = :firedAt WHERE id = :id AND state = 'REGISTERED'")
    suspend fun markFired(id: String, firedAt: Long): Int

    @Query("SELECT * FROM alarm_registration WHERE id = :id")
    suspend fun find(id: String): AlarmRegistrationEntity?
}

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EpisodeEntity)

    @Query("SELECT * FROM episode")
    suspend fun all(): List<EpisodeEntity>
}
