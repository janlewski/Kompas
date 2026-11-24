package app.feelings.tracker.export

import android.content.Context
import app.feelings.tracker.core.crypto.DbKeyManager
import app.feelings.tracker.core.crypto.SqlCipherKeys
import app.feelings.tracker.core.result.BackupProblem
import app.feelings.tracker.data.db.AppDatabase
import app.feelings.tracker.data.db.DatabaseFactory
import androidx.room.withTransaction
import app.feelings.tracker.data.db.entities.AppSettingsEntity
import app.feelings.tracker.data.db.entities.CheckInEntity
import app.feelings.tracker.data.db.entities.CheckInReminderEntity
import app.feelings.tracker.data.db.entities.CheckInTagEntity
import app.feelings.tracker.data.db.entities.DoseEventEntity
import app.feelings.tracker.data.db.entities.DoseOccurrenceEntity
import app.feelings.tracker.data.db.entities.EpisodeEntity
import app.feelings.tracker.data.db.entities.MedicationEntity
import app.feelings.tracker.data.db.entities.QuietHoursEntity
import app.feelings.tracker.data.db.entities.ScheduleTimeEntity
import app.feelings.tracker.data.db.entities.ScheduleVersionEntity
import app.feelings.tracker.data.db.entities.SleepRecordEntity
import app.feelings.tracker.data.db.entities.SymptomTagEntity
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId

class BackupService(
    private val context: Context,
    private val db: suspend () -> AppDatabase,
    private val cipher: ExportCipher,
    private val clock: Clock,
    private val zone: ZoneId,
    private val appVersion: String,
    private val appLanguage: String,
) {
    suspend fun exportBytes(): ByteArray {
        val database = db()
        val tables = linkedMapOf(
            "symptom_tag" to database.tagDao().all().map { it.toMap() },
            "check_in" to database.checkInDao().all().map { it.toMap() },
            "check_in_tag" to database.checkInDao().allTags().map { it.toMap() },
            "sleep_record" to database.sleepDao().all().map { it.toMap() },
            "medication" to database.medicationDao().all().map { it.toMap() },
            "schedule_version" to database.medicationDao().allVersions().map { it.toMap() },
            "schedule_time" to database.medicationDao().allTimes().map { it.toMap() },
            "dose_occurrence" to database.medicationDao().allOccurrences().map { it.toMap() },
            "dose_event" to database.medicationDao().allEvents().map { it.toMap() },
            "checkin_reminder" to database.reminderDao().all().map { it.toMap() },
            "quiet_hours" to listOfNotNull(database.reminderDao().quietHours()?.toMap()),
            "app_settings" to listOfNotNull(database.settingsDao().get()?.toMap()),
            "episode" to database.episodeDao().all().map { it.toMap() },
        )
        val hashes = linkedMapOf<String, String>()
        val counts = linkedMapOf<String, Int>()
        val lines = mutableListOf<String>()
        tables.forEach { (name, rows) ->
            counts[name] = rows.size
            val rowLines = rows.map { BackupJson.encode(mapOf("t" to name, "v" to it)) }
            hashes[name] = sha256(rowLines.joinToString("\n").encodeToByteArray())
            lines += rowLines
        }
        val manifest = mapOf(
            "type" to "manifest",
            "formatVersion" to 1,
            "dbSchemaVersion" to 1,
            "appVersion" to appVersion,
            "exportedAtUtc" to clock.instant().toEpochMilli(),
            "zoneId" to zone.id,
            "appLanguage" to appLanguage,
            "counts" to counts,
            "sha256" to hashes,
        )
        val end = mapOf("type" to "end", "rows" to lines.size)
        val plaintext = (listOf(BackupJson.encode(manifest)) + lines + BackupJson.encode(end))
            .joinToString("\n")
            .encodeToByteArray()
        return plaintext
    }

    fun seal(plaintext: ByteArray, passphrase: CharArray): ByteArray =
        BackupCodec.write(cipher, passphrase, plaintext)

    fun open(file: ByteArray, passphrase: CharArray): OpenedBackup {
        return when (val read = BackupCodec.read(cipher, passphrase, file)) {
            is ReadBackup.Failed -> OpenedBackup.Failed(read.problem)
            is ReadBackup.Plaintext -> parse(read.bytes)
        }
    }

    suspend fun restore(opened: OpenedBackup.Ready, rawKey: ByteArray): AppDatabase {
        val passphrase = SqlCipherKeys.rawKeyPassphrase(rawKey)
        val temp = DatabaseFactory.openPassphrase(context, TEMP_NAME, passphrase, seed = false)
        passphrase.fill(0)
        try {
            temp.withTransaction {
                opened.tags.forEach { temp.tagDao().insert(it) }
                opened.checkIns.forEach { temp.checkInDao().upsert(it) }
                opened.links.forEach { temp.checkInDao().replaceTags(listOf(it)) }
                opened.sleep.forEach { temp.sleepDao().upsert(it) }
                opened.medications.forEach { temp.medicationDao().upsert(it) }
                opened.versions.forEach { temp.medicationDao().insertVersion(it) }
                opened.times.forEach { temp.medicationDao().insertTimes(listOf(it)) }
                opened.occurrences.forEach { temp.medicationDao().upsertOccurrence(it) }
                opened.events.forEach { temp.medicationDao().insertEvent(it) }
                opened.reminders.forEach { temp.reminderDao().upsert(it.copy(enabled = false)) }
                opened.quietHours?.let { temp.reminderDao().upsertQuietHours(it) }
                opened.settings?.let {
                    temp.settingsDao().upsert(it.copy(scheduleGeneration = 0, remindersPausedAfterRestore = true))
                }
                opened.episodes.forEach { temp.episodeDao().upsert(it) }
            }
            val cursor = temp.openHelper.writableDatabase.query("PRAGMA foreign_key_check")
            val broken = cursor.moveToFirst()
            cursor.close()
            if (broken) error("fk")
            temp.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        } catch (thrown: Throwable) {
            temp.close()
            context.deleteDatabase(TEMP_NAME)
            throw thrown
        }
        return temp
    }

    fun swapIn(temp: AppDatabase, rawKey: ByteArray) {
        temp.close()
        val live = context.getDatabasePath(DbKeyManager.DATABASE_NAME)
        val imported = context.getDatabasePath(TEMP_NAME)
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm")).forEach { it.delete() }
        if (!imported.renameTo(live)) {
            imported.copyTo(live, overwrite = true)
            imported.delete()
        }
        File(imported.path + "-wal").delete()
        File(imported.path + "-shm").delete()
        // The caller wraps rawKey with the Keystore and reopens.
    }

    private fun parse(bytes: ByteArray): OpenedBackup {
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return OpenedBackup.Failed(BackupProblem.TRUNCATED)
        val manifest = BackupJson.parse(lines.first()) as? Map<*, *> ?: return OpenedBackup.Failed(BackupProblem.NOT_A_BACKUP)
        if (manifest["type"] != "manifest") return OpenedBackup.Failed(BackupProblem.NOT_A_BACKUP)
        val end = BackupJson.parse(lines.last()) as? Map<*, *> ?: return OpenedBackup.Failed(BackupProblem.TRUNCATED)
        if (end["type"] != "end") return OpenedBackup.Failed(BackupProblem.TRUNCATED)
        val body = lines.subList(1, lines.lastIndex)
        if ((end["rows"] as? Number)?.toInt() != body.size) return OpenedBackup.Failed(BackupProblem.TRUNCATED)
        val grouped = linkedMapOf<String, MutableList<String>>()
        val tags = mutableListOf<SymptomTagEntity>()
        val checkIns = mutableListOf<CheckInEntity>()
        val links = mutableListOf<CheckInTagEntity>()
        val sleep = mutableListOf<SleepRecordEntity>()
        val medications = mutableListOf<MedicationEntity>()
        val versions = mutableListOf<ScheduleVersionEntity>()
        val times = mutableListOf<ScheduleTimeEntity>()
        val occurrences = mutableListOf<DoseOccurrenceEntity>()
        val events = mutableListOf<DoseEventEntity>()
        val reminders = mutableListOf<CheckInReminderEntity>()
        var quiet: QuietHoursEntity? = null
        var settings: AppSettingsEntity? = null
        val episodes = mutableListOf<EpisodeEntity>()
        try {
            body.forEach { line ->
                val row = BackupJson.parse(line) as Map<*, *>
                val table = row["t"] as String
                grouped.getOrPut(table) { mutableListOf() }.add(line)
                val values = row["v"] as Map<*, *>
                when (table) {
                    "symptom_tag" -> tags += values.toTag()
                    "check_in" -> checkIns += values.toCheckIn()
                    "check_in_tag" -> links += CheckInTagEntity(values.str("checkInId"), values.str("tagId"))
                    "sleep_record" -> sleep += values.toSleep()
                    "medication" -> medications += values.toMedication()
                    "schedule_version" -> versions += values.toVersion()
                    "schedule_time" -> times += ScheduleTimeEntity(values.str("versionId"), values.str("localTime"))
                    "dose_occurrence" -> occurrences += values.toOccurrence()
                    "dose_event" -> events += values.toEvent()
                    "checkin_reminder" -> reminders += values.toReminder()
                    "quiet_hours" -> quiet = values.toQuiet()
                    "app_settings" -> settings = values.toSettings()
                    "episode" -> episodes += values.toEpisode()
                    else -> return OpenedBackup.Failed(BackupProblem.NOT_A_BACKUP)
                }
            }
        } catch (_: Exception) {
            return OpenedBackup.Failed(BackupProblem.WRONG_PASSPHRASE_OR_DAMAGED)
        }
        val expected = manifest["sha256"] as? Map<*, *> ?: return OpenedBackup.Failed(BackupProblem.NOT_A_BACKUP)
        val counts = manifest["counts"] as? Map<*, *> ?: return OpenedBackup.Failed(BackupProblem.NOT_A_BACKUP)
        grouped.forEach { (table, rowLines) ->
            val hash = sha256(rowLines.joinToString("\n").encodeToByteArray())
            if (expected[table] != hash) return OpenedBackup.Failed(BackupProblem.WRONG_PASSPHRASE_OR_DAMAGED)
            if ((counts[table] as? Number)?.toInt() != rowLines.size) {
                return OpenedBackup.Failed(BackupProblem.WRONG_PASSPHRASE_OR_DAMAGED)
            }
        }
        return OpenedBackup.Ready(
            exportedAtUtc = (manifest["exportedAtUtc"] as Number).toLong(),
            appVersion = manifest["appVersion"] as String,
            appLanguage = manifest["appLanguage"] as? String ?: "system",
            counts =
                counts.entries.associate { (key, value) ->
                    key as String to (value as Number).toInt()
                },
            tags = tags,
            checkIns = checkIns,
            links = links,
            sleep = sleep,
            medications = medications,
            versions = versions,
            times = times,
            occurrences = occurrences,
            events = events,
            reminders = reminders,
            quietHours = quiet,
            settings = settings,
            episodes = episodes,
        )
    }

    companion object {
        const val TEMP_NAME = "feelings-import.db"
    }
}

sealed class OpenedBackup {
    data class Ready(
        val exportedAtUtc: Long,
        val appVersion: String,
        val appLanguage: String,
        val counts: Map<String, Int>,
        val tags: List<SymptomTagEntity>,
        val checkIns: List<CheckInEntity>,
        val links: List<CheckInTagEntity>,
        val sleep: List<SleepRecordEntity>,
        val medications: List<MedicationEntity>,
        val versions: List<ScheduleVersionEntity>,
        val times: List<ScheduleTimeEntity>,
        val occurrences: List<DoseOccurrenceEntity>,
        val events: List<DoseEventEntity>,
        val reminders: List<CheckInReminderEntity>,
        val quietHours: QuietHoursEntity?,
        val settings: AppSettingsEntity?,
        val episodes: List<EpisodeEntity>,
    ) : OpenedBackup()

    data class Failed(val problem: BackupProblem) : OpenedBackup()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun Map<*, *>.str(key: String): String = this[key] as String

private fun Map<*, *>.strOrNull(key: String): String? = this[key] as? String

private fun Map<*, *>.long(key: String): Long = (this[key] as Number).toLong()

private fun Map<*, *>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<*, *>.int(key: String): Int = (this[key] as Number).toInt()

private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

private fun Map<*, *>.bool(key: String): Boolean = this[key] as Boolean

private fun SymptomTagEntity.toMap() = mapOf(
    "id" to id, "labelPl" to labelPl, "labelEn" to labelEn, "isBuiltIn" to isBuiltIn,
    "archivedAtUtc" to archivedAtUtc, "sortOrder" to sortOrder,
)

private fun Map<*, *>.toTag() = SymptomTagEntity(
    str("id"), str("labelPl"), str("labelEn"), bool("isBuiltIn"), longOrNull("archivedAtUtc"), int("sortOrder"),
)

private fun CheckInEntity.toMap() = mapOf(
    "id" to id, "experiencedAtUtc" to experiencedAtUtc, "recordedAtUtc" to recordedAtUtc,
    "updatedAtUtc" to updatedAtUtc, "zoneId" to zoneId, "localDate" to localDate, "source" to source.name,
    "promptAlarmId" to promptAlarmId, "stress" to stress, "stressProv" to stressProv.name,
    "physical" to physical, "physicalProv" to physicalProv.name, "mental" to mental,
    "mentalProv" to mentalProv.name, "reusedFromCheckInId" to reusedFromCheckInId,
    "painIntensity" to painIntensity, "note" to note, "scaleVersion" to scaleVersion,
    "isRetrospective" to isRetrospective,
)

private fun Map<*, *>.toCheckIn() = CheckInEntity(
    str("id"), long("experiencedAtUtc"), long("recordedAtUtc"), longOrNull("updatedAtUtc"), str("zoneId"),
    str("localDate"), app.feelings.tracker.domain.model.CheckInSource.valueOf(str("source")), strOrNull("promptAlarmId"),
    intOrNull("stress"), app.feelings.tracker.domain.model.ValueProvenance.valueOf(str("stressProv")),
    intOrNull("physical"), app.feelings.tracker.domain.model.ValueProvenance.valueOf(str("physicalProv")),
    intOrNull("mental"), app.feelings.tracker.domain.model.ValueProvenance.valueOf(str("mentalProv")),
    strOrNull("reusedFromCheckInId"), intOrNull("painIntensity"), strOrNull("note"), int("scaleVersion"),
    bool("isRetrospective"),
)

private fun CheckInTagEntity.toMap() = mapOf("checkInId" to checkInId, "tagId" to tagId)

private fun SleepRecordEntity.toMap() = mapOf(
    "id" to id, "wakeDate" to wakeDate, "durationMinutes" to durationMinutes, "definition" to definition.name,
    "source" to source.name, "zoneId" to zoneId, "recordedAtUtc" to recordedAtUtc, "updatedAtUtc" to updatedAtUtc,
)

private fun Map<*, *>.toSleep() = SleepRecordEntity(
    str("id"), str("wakeDate"), int("durationMinutes"),
    app.feelings.tracker.domain.model.SleepDefinition.valueOf(str("definition")),
    app.feelings.tracker.domain.model.SleepSource.valueOf(str("source")),
    str("zoneId"), long("recordedAtUtc"), longOrNull("updatedAtUtc"),
)

private fun MedicationEntity.toMap() = mapOf(
    "id" to id, "name" to name, "strengthText" to strengthText, "form" to form,
    "createdAtUtc" to createdAtUtc, "archivedAtUtc" to archivedAtUtc,
)

private fun Map<*, *>.toMedication() = MedicationEntity(
    str("id"), str("name"), strOrNull("strengthText"), strOrNull("form"), long("createdAtUtc"), longOrNull("archivedAtUtc"),
)

private fun ScheduleVersionEntity.toMap() = mapOf(
    "id" to id, "medicationId" to medicationId, "versionNo" to versionNo, "effectiveFromDate" to effectiveFromDate,
    "kind" to kind.name, "doseAmount" to doseAmount, "doseUnit" to doseUnit, "daysOfWeekMask" to daysOfWeekMask,
    "zonePolicy" to zonePolicy.name, "fixedZoneId" to fixedZoneId, "importance" to importance.name,
    "instructions" to instructions, "createdAtUtc" to createdAtUtc,
)

private fun Map<*, *>.toVersion() = ScheduleVersionEntity(
    str("id"), str("medicationId"), int("versionNo"), str("effectiveFromDate"),
    app.feelings.tracker.domain.model.ScheduleKind.valueOf(str("kind")),
    strOrNull("doseAmount"), strOrNull("doseUnit"), int("daysOfWeekMask"),
    app.feelings.tracker.domain.model.ZonePolicy.valueOf(str("zonePolicy")), strOrNull("fixedZoneId"),
    app.feelings.tracker.domain.model.MedImportance.valueOf(str("importance")), strOrNull("instructions"), long("createdAtUtc"),
)

private fun ScheduleTimeEntity.toMap() = mapOf("versionId" to versionId, "localTime" to localTime)

private fun DoseOccurrenceEntity.toMap() = mapOf(
    "id" to id, "medicationId" to medicationId, "scheduleVersionId" to scheduleVersionId, "localDate" to localDate,
    "localTime" to localTime, "zoneId" to zoneId, "scheduledAtUtc" to scheduledAtUtc,
    "dstResolution" to dstResolution.name, "plannedDoseAmount" to plannedDoseAmount,
    "plannedDoseUnit" to plannedDoseUnit, "state" to state.name,
)

private fun Map<*, *>.toOccurrence() = DoseOccurrenceEntity(
    str("id"), str("medicationId"), str("scheduleVersionId"), str("localDate"), str("localTime"), str("zoneId"),
    long("scheduledAtUtc"), app.feelings.tracker.core.time.DstResolution.valueOf(str("dstResolution")),
    strOrNull("plannedDoseAmount"), strOrNull("plannedDoseUnit"),
    app.feelings.tracker.domain.model.DoseOccurrenceState.valueOf(str("state")),
)

private fun DoseEventEntity.toMap() = mapOf(
    "id" to id, "actionId" to actionId, "occurrenceId" to occurrenceId, "medicationId" to medicationId,
    "status" to status.name, "actualAtUtc" to actualAtUtc, "recordedAtUtc" to recordedAtUtc, "zoneId" to zoneId,
    "actualDoseAmount" to actualDoseAmount, "actualDoseUnit" to actualDoseUnit, "snoozeUntilUtc" to snoozeUntilUtc,
    "source" to source.name, "supersedesEventId" to supersedesEventId,
)

private fun Map<*, *>.toEvent() = DoseEventEntity(
    str("id"), str("actionId"), strOrNull("occurrenceId"), str("medicationId"),
    app.feelings.tracker.domain.model.DoseStatus.valueOf(str("status")), longOrNull("actualAtUtc"),
    long("recordedAtUtc"), str("zoneId"), strOrNull("actualDoseAmount"), strOrNull("actualDoseUnit"),
    longOrNull("snoozeUntilUtc"), app.feelings.tracker.domain.model.DoseSource.valueOf(str("source")),
    strOrNull("supersedesEventId"),
)

private fun CheckInReminderEntity.toMap() = mapOf(
    "id" to id, "localTime" to localTime, "daysOfWeekMask" to daysOfWeekMask, "enabled" to enabled,
    "label" to label, "createdAtUtc" to createdAtUtc,
)

private fun Map<*, *>.toReminder() = CheckInReminderEntity(
    str("id"), str("localTime"), int("daysOfWeekMask"), bool("enabled"), strOrNull("label"), long("createdAtUtc"),
)

private fun QuietHoursEntity.toMap() = mapOf(
    "id" to id, "enabled" to enabled, "startLocal" to startLocal, "endLocal" to endLocal,
    "daysOfWeekMask" to daysOfWeekMask, "checkInBehavior" to checkInBehavior.name,
    "medicationBehavior" to medicationBehavior.name,
)

private fun Map<*, *>.toQuiet() = QuietHoursEntity(
    int("id"), bool("enabled"), str("startLocal"), str("endLocal"), int("daysOfWeekMask"),
    app.feelings.tracker.domain.model.CheckInQuietBehavior.valueOf(str("checkInBehavior")),
    app.feelings.tracker.domain.model.MedicationQuietBehavior.valueOf(str("medicationBehavior")),
)

private fun AppSettingsEntity.toMap() = mapOf(
    "id" to id, "showMedNamesInNotifications" to showMedNamesInNotifications,
    "sameAsLastEnabled" to sameAsLastEnabled, "enabledTilesMask" to enabledTilesMask,
    "sleepPresetMinutes" to sleepPresetMinutes, "trendsEnabled" to trendsEnabled,
    "quickRateDimension" to quickRateDimension.name, "medSnoozeMinutes" to medSnoozeMinutes,
    "ageBand" to ageBand.name, "lastExportAtUtc" to lastExportAtUtc,
)

private fun Map<*, *>.toSettings() = AppSettingsEntity(
    id = int("id"),
    showMedNamesInNotifications = bool("showMedNamesInNotifications"),
    sameAsLastEnabled = bool("sameAsLastEnabled"),
    enabledTilesMask = int("enabledTilesMask"),
    sleepPresetMinutes = str("sleepPresetMinutes"),
    trendsEnabled = bool("trendsEnabled"),
    quickRateDimension = app.feelings.tracker.domain.model.QuickRateDimension.valueOf(str("quickRateDimension")),
    medSnoozeMinutes = int("medSnoozeMinutes"),
    ageBand = app.feelings.tracker.domain.model.AgeBand.valueOf(str("ageBand")),
    lastExportAtUtc = longOrNull("lastExportAtUtc"),
    scheduleGeneration = 0,
    remindersPausedAfterRestore = true,
)

private fun EpisodeEntity.toMap() = mapOf(
    "id" to id, "label" to label, "startDate" to startDate, "endDate" to endDate, "note" to note,
)

private fun Map<*, *>.toEpisode() = EpisodeEntity(str("id"), str("label"), str("startDate"), strOrNull("endDate"), strOrNull("note"))
