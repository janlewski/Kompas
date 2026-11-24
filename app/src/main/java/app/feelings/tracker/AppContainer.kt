package app.feelings.tracker

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.feelings.tracker.core.crypto.DbKeyManager
import app.feelings.tracker.core.crypto.KeystoreKeyWrapper
import app.feelings.tracker.core.ids.UuidProvider
import app.feelings.tracker.core.result.AppError
import app.feelings.tracker.core.result.AppResult
import app.feelings.tracker.core.time.ZoneProvider
import app.feelings.tracker.data.db.DatabaseFactory
import app.feelings.tracker.data.db.DatabaseHolder
import app.feelings.tracker.data.prefs.UiPrefs
import app.feelings.tracker.data.repo.AlarmRepository
import app.feelings.tracker.data.repo.CheckInRepository
import app.feelings.tracker.data.repo.EpisodeRepository
import app.feelings.tracker.data.repo.MedicationRepository
import app.feelings.tracker.data.repo.ReminderRepository
import app.feelings.tracker.data.repo.SettingsRepository
import app.feelings.tracker.data.repo.SleepRepository
import app.feelings.tracker.domain.analytics.LateDelivery
import app.feelings.tracker.domain.checkin.SaveCheckInUseCase
import app.feelings.tracker.domain.checkin.SaveSleepUseCase
import app.feelings.tracker.domain.medication.RecordDoseUseCase
import app.feelings.tracker.domain.medication.SaveScheduleUseCase
import app.feelings.tracker.domain.model.AlarmState
import app.feelings.tracker.export.ExportCipher
import app.feelings.tracker.export.SodiumExportCipher
import app.feelings.tracker.platform.alarms.AlarmScheduler
import app.feelings.tracker.platform.notify.Channels
import app.feelings.tracker.platform.notify.NotificationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

class AppContainer(
    private val context: Context,
    val cipher: ExportCipher = SodiumExportCipher(),
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.systemDefaultZone()
    val zones = ZoneProvider.system()
    val ids = UuidProvider.random()
    val prefs = UiPrefs(context)
    val keyManager = DbKeyManager(context, KeystoreKeyWrapper())
    val database = DatabaseHolder { DatabaseFactory.open(context, keyManager) }
    val checkIns = CheckInRepository { database.require() }
    val sleep = SleepRepository { database.require() }
    val medications = MedicationRepository { database.require() }
    val reminders = ReminderRepository { database.require() }
    val settings = SettingsRepository { database.require() }
    val alarms = AlarmRepository { database.require() }
    val episodes = EpisodeRepository { database.require() }
    val saveCheckIn = SaveCheckInUseCase(checkIns, clock, ids)
    val saveSleep = SaveSleepUseCase(sleep, clock, ids)
    val recordDose = RecordDoseUseCase(medications, clock, ids)
    val saveSchedule = SaveScheduleUseCase(medications, clock, ids)
    val notifications = NotificationFactory(context)
    val scheduler = AlarmScheduler(
        context, medications, reminders, settings, alarms, notifications, clock, zones,
    )

    private val _dbState = MutableStateFlow<AppResult<Unit>?>(null)
    val dbState: StateFlow<AppResult<Unit>?> = _dbState

    fun start() {
        appScope.launch {
            val opened = database.open()
            _dbState.value = when (opened) {
                is AppResult.Ok -> AppResult.Ok(Unit)
                is AppResult.Err -> opened
            }
            if (opened is AppResult.Ok) {
                val important = medications.medications().any { med ->
                    medications.versions(med.id).any { it.importance == app.feelings.tracker.domain.model.MedImportance.IMPORTANT }
                }
                Channels.ensure(context, important)
                scheduler.reconcile("start")
            }
        }
    }

    suspend fun ensureOpen(): AppResult<Unit> = when (val opened = database.open()) {
        is AppResult.Ok -> AppResult.Ok(Unit)
        is AppResult.Err -> opened
    }

    fun isReady(): Boolean = _dbState.value is AppResult.Ok

    suspend fun deleteAll() {
        alarms.registered().forEach { registration ->
            val intent = Intent(context, app.feelings.tracker.platform.alarms.AlarmReceiver::class.java)
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                registration.requestCode,
                intent.putExtra(AlarmScheduler.EXTRA_ID, registration.id),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                context.getSystemService(AlarmManager::class.java).cancel(pending)
            }
        }
        NotificationManagerCompat.from(context).cancelAll()
        database.closeAndDrop()
        context.deleteDatabase(DbKeyManager.DATABASE_NAME)
        keyManager.deleteKeyMaterial()
        prefs.clear()
        _dbState.value = null
        start()
    }

    fun statusRows(): List<StatusRow> {
        val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val exact = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }
        val power = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = power.isIgnoringBatteryOptimizations(context.packageName)
        val manager = context.getSystemService(NotificationManager::class.java)
        val dnd = if (Build.VERSION.SDK_INT >= 23) {
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }
        return listOf(
            StatusRow(R.string.status_notifications, notificationsOn, notificationSettings()),
            StatusRow(R.string.status_exact, exact, exactAlarmSettings()),
            StatusRow(R.string.status_dnd, dnd, null),
            StatusRow(R.string.status_battery, ignoringBattery, null),
        )
    }

    suspend fun lateMinutes(): Long? {
        val fired = alarms.all().filter { it.state == AlarmState.FIRED && it.firedAtUtc != null }
            .maxByOrNull { it.firedAtUtc!! } ?: return null
        return LateDelivery.lateMinutes(fired.triggerAtUtc.toEpochMilli(), fired.firedAtUtc!!.toEpochMilli())
    }

    fun notificationSettings(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun exactAlarmSettings(): Intent? = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}"))
    } else {
        null
    }

    fun channelSettings(channelId: String): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }

    fun databaseFiles(): List<File> = listOf(
        context.getDatabasePath(DbKeyManager.DATABASE_NAME),
        File(context.getDatabasePath(DbKeyManager.DATABASE_NAME).path + "-wal"),
        File(context.getDatabasePath(DbKeyManager.DATABASE_NAME).path + "-shm"),
    )
}

data class StatusRow(val labelRes: Int, val enabled: Boolean, val fix: Intent?)
