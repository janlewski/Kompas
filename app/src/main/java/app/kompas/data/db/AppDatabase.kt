package app.kompas.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import app.kompas.data.db.dao.AlarmDao
import app.kompas.data.db.dao.CheckInDao
import app.kompas.data.db.dao.EpisodeDao
import app.kompas.data.db.dao.MedicationDao
import app.kompas.data.db.dao.ReminderDao
import app.kompas.data.db.dao.SettingsDao
import app.kompas.data.db.dao.SleepDao
import app.kompas.data.db.dao.TagDao
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

@Database(
    entities = [
        CheckInEntity::class,
        SymptomTagEntity::class,
        CheckInTagEntity::class,
        SleepRecordEntity::class,
        MedicationEntity::class,
        ScheduleVersionEntity::class,
        ScheduleTimeEntity::class,
        DoseOccurrenceEntity::class,
        DoseEventEntity::class,
        CheckInReminderEntity::class,
        QuietHoursEntity::class,
        AlarmRegistrationEntity::class,
        AppSettingsEntity::class,
        EpisodeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun checkInDao(): CheckInDao

    abstract fun sleepDao(): SleepDao

    abstract fun tagDao(): TagDao

    abstract fun medicationDao(): MedicationDao

    abstract fun reminderDao(): ReminderDao

    abstract fun settingsDao(): SettingsDao

    abstract fun alarmDao(): AlarmDao

    abstract fun episodeDao(): EpisodeDao
}

object BuiltInTags {
    val rows: List<Triple<String, String, String>> = listOf(
        Triple("11111111-1111-4111-8111-111111111101", "ból głowy", "headache"),
        Triple("11111111-1111-4111-8111-111111111102", "gorączka", "fever"),
        Triple("11111111-1111-4111-8111-111111111103", "kaszel", "cough"),
        Triple("11111111-1111-4111-8111-111111111104", "ból gardła", "sore throat"),
        Triple("11111111-1111-4111-8111-111111111105", "nudności", "nausea"),
        Triple("11111111-1111-4111-8111-111111111106", "ból brzucha", "stomach ache"),
        Triple("11111111-1111-4111-8111-111111111107", "zmęczenie", "tiredness"),
        Triple("11111111-1111-4111-8111-111111111108", "katar", "runny nose"),
    )
}

class SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        BuiltInTags.rows.forEachIndexed { index, (id, pl, en) ->
            db.execSQL(
                "INSERT INTO symptom_tag (id, labelPl, labelEn, isBuiltIn, archivedAtUtc, sortOrder) VALUES (?, ?, ?, 1, NULL, ?)",
                arrayOf<Any?>(id, pl, en, index),
            )
        }
        db.execSQL(
            """
            INSERT INTO app_settings (
                id, showMedNamesInNotifications, sameAsLastEnabled, enabledTilesMask, sleepPresetMinutes,
                trendsEnabled, quickRateDimension, medSnoozeMinutes, ageBand, lastExportAtUtc,
                scheduleGeneration, remindersPausedAfterRestore
            ) VALUES (1, 0, 1, 31, '360,420,480,540', 1, 'OFF', 10, 'UNSPECIFIED', NULL, 0, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO quiet_hours (
                id, enabled, startLocal, endLocal, daysOfWeekMask, checkInBehavior, medicationBehavior
            ) VALUES (1, 0, '22:00', '07:00', 127, 'SUPPRESS', 'DELIVER_NORMALLY')
            """.trimIndent(),
        )
    }
}
