package app.kompas.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kompas.AppContainer
import app.kompas.BuildConfig
import app.kompas.R
import app.kompas.core.result.AppError
import app.kompas.core.result.AppResult
import app.kompas.core.result.BackupProblem
import app.kompas.domain.analytics.Baseline
import app.kompas.domain.analytics.DayAggregator
import app.kompas.domain.analytics.SleepSpearman
import app.kompas.domain.analytics.SpearmanResult
import app.kompas.domain.analytics.TileKind
import app.kompas.domain.analytics.WeeklySummary
import app.kompas.domain.analytics.WeekRelation
import app.kompas.domain.checkin.CheckInDraft
import app.kompas.domain.checkin.CheckInProblem
import app.kompas.domain.checkin.SaveCheckInResult
import app.kompas.domain.checkin.SaveSleepResult
import app.kompas.domain.medication.DoseOutcomeResolver
import app.kompas.domain.medication.OccurrenceGenerator
import app.kompas.domain.medication.ScheduleRules
import app.kompas.domain.medication.WeekMask
import app.kompas.domain.model.AgeBand
import app.kompas.domain.model.AlarmMode
import app.kompas.domain.model.AppLanguage
import app.kompas.domain.model.CheckInQuietBehavior
import app.kompas.domain.model.CheckInReminder
import app.kompas.domain.model.CheckInSource
import app.kompas.domain.model.DoseOutcomeKind
import app.kompas.domain.model.DoseSource
import app.kompas.domain.model.DoseStatus
import app.kompas.domain.model.MedImportance
import app.kompas.domain.model.MedicationQuietBehavior
import app.kompas.domain.model.QuickRateDimension
import app.kompas.domain.model.SCALE_VERSION
import app.kompas.domain.model.ScheduleKind
import app.kompas.domain.model.ScheduleVersion
import app.kompas.domain.model.SleepSource
import app.kompas.domain.model.SymptomTag
import app.kompas.domain.model.ThemePref
import app.kompas.domain.model.ZonePolicy
import app.kompas.export.BackupService
import app.kompas.export.OpenedBackup
import app.kompas.platform.notify.Channels
import app.kompas.ui.components.RatingRow
import app.kompas.ui.components.TileCard
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.UUID

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val TRENDS = "trends"
    const val MEDS = "meds"
    const val SETTINGS = "settings"
    const val REMINDERS = "reminders"
    const val STATUS = "status"
    const val BACKUP = "backup"
    const val HELP = "help"
}

@Composable
fun FeelingsApp(container: AppContainer) {
    val db by container.dbState.collectAsStateWithLifecycle()
    val onboarded by container.prefs.onboardingCompleted.collectAsStateWithLifecycle(initialValue = false)
    when (val state = db) {
        null -> Text(stringResource(R.string.loading), modifier = Modifier.padding(24.dp))
        is AppResult.Err -> KeyLossScreen(container, state.error)
        is AppResult.Ok -> if (!onboarded) Onboarding(container) else Shell(container)
    }
}

@Composable
private fun KeyLossScreen(container: AppContainer, error: AppError) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.key_loss_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (error is AppError.KeyLost) R.string.key_loss_body else R.string.error_db))
        val scope = rememberCoroutineScope()
        Button(onClick = { scope.launch { container.deleteAll() } }) {
            Text(stringResource(R.string.key_loss_start_over))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    var menu by remember { mutableStateOf(false) }
    val tabs = listOf(Routes.HOME to R.string.nav_home, Routes.HISTORY to R.string.nav_history, Routes.TRENDS to R.string.nav_trends, Routes.MEDS to R.string.nav_meds)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(R.string.menu_settings) { menu = false; nav.navigate(Routes.SETTINGS) }
                        MenuItem(R.string.menu_reminders) { menu = false; nav.navigate(Routes.REMINDERS) }
                        MenuItem(R.string.menu_status) { menu = false; nav.navigate(Routes.STATUS) }
                        MenuItem(R.string.menu_backup) { menu = false; nav.navigate(Routes.BACKUP) }
                        MenuItem(R.string.menu_help) { menu = false; nav.navigate(Routes.HELP) }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { (dest, label) ->
                    NavigationBarItem(
                        selected = route == dest,
                        onClick = {
                            nav.navigate(dest) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (dest == Routes.TRENDS) Icons.Default.ShowChart else Icons.Default.Home,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, Routes.HOME, Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(container) }
            composable(Routes.HISTORY) { HistoryScreen(container) }
            composable(Routes.TRENDS) { TrendsScreen(container) }
            composable(Routes.MEDS) { MedsScreen(container) }
            composable(Routes.SETTINGS) { SettingsScreen(container) }
            composable(Routes.REMINDERS) { RemindersScreen(container) }
            composable(Routes.STATUS) { StatusScreen(container) }
            composable(Routes.BACKUP) { BackupScreen(container) }
            composable(Routes.HELP) { HelpScreen() }
        }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun Onboarding(container: AppContainer) {
    var step by remember { mutableIntStateOf(0) }
    var ack by remember { mutableStateOf(false) }
    var age by remember { mutableStateOf(AgeBand.UNSPECIFIED) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        when (step) {
            0 -> {
                Text(stringResource(R.string.onboarding_privacy))
                Text(stringResource(R.string.onboarding_no_advice))
                Text(stringResource(R.string.onboarding_emergency))
                TextButton(onClick = { step = 8 }) { Text(stringResource(R.string.menu_help)) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = ack, onCheckedChange = { ack = it })
                    Text(stringResource(R.string.onboarding_ack), modifier = Modifier.padding(start = 8.dp))
                }
            }
            1 -> {
                Text(stringResource(R.string.onboarding_age_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.onboarding_age_body))
                AgeBand.entries.forEach { band ->
                    TextButton(onClick = { age = band }) { Text(stringResource(ageLabel(band))) }
                }
                if (age == AgeBand.UNDER_13) Text(stringResource(R.string.age_under_13_note))
            }
            2 -> Text(stringResource(R.string.onboarding_tiles_title))
            3 -> {
                Text(stringResource(R.string.onboarding_reminders_title))
                Text(stringResource(R.string.notif_rationale))
            }
            else -> Text(stringResource(R.string.onboarding_battery))
        }
        Button(
            onClick = {
                if (step < 4) step += 1 else scope.launch {
                    val settings = container.settings.get()
                    container.settings.save(settings.copy(ageBand = age))
                    container.prefs.setOnboardingCompleted(true)
                }
            },
            enabled = step != 0 || ack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(if (step < 4) R.string.action_continue else R.string.action_done)) }
    }
}

private fun ageLabel(band: AgeBand): Int = when (band) {
    AgeBand.UNDER_13 -> R.string.age_under_13
    AgeBand.AGE_13_17 -> R.string.age_13_17
    AgeBand.ADULT -> R.string.age_adult
    AgeBand.UNSPECIFIED -> R.string.age_unspecified
}

@Composable
private fun HomeScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var stress by remember { mutableStateOf<Int?>(null) }
    var physical by remember { mutableStateOf<Int?>(null) }
    var mental by remember { mutableStateOf<Int?>(null) }
    var stressTouched by remember { mutableStateOf(false) }
    var physicalTouched by remember { mutableStateOf(false) }
    var mentalTouched by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepSource by remember { mutableStateOf(SleepSource.MANUAL) }
    var note by remember { mutableStateOf("") }
    var pain by remember { mutableStateOf<Int?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var yesterday by remember { mutableStateOf<Int?>(null) }
    var presets by remember { mutableStateOf(listOf(360, 420, 480, 540)) }
    var mask by remember { mutableIntStateOf(31) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf(false) }
    var reusedId by remember { mutableStateOf<String?>(null) }
    val zone = container.zones.zoneId()
    val today = container.clock.instant().atZone(zone).toLocalDate()
    val savedText = stringResource(R.string.saved)
    val undoText = stringResource(R.string.action_undo)
    val emptyText = stringResource(R.string.error_empty)
    val rangeText = stringResource(R.string.error_range)
    val noteText = stringResource(R.string.error_note)
    val futureText = stringResource(R.string.error_future)
    val pastText = stringResource(R.string.error_past)
    val stressLabels = listOf(
        stringResource(R.string.stress_1), stringResource(R.string.stress_2), stringResource(R.string.stress_3),
        stringResource(R.string.stress_4), stringResource(R.string.stress_5),
    )
    val feelingLabels = listOf(
        stringResource(R.string.feeling_1), stringResource(R.string.feeling_2), stringResource(R.string.feeling_3),
        stringResource(R.string.feeling_4), stringResource(R.string.feeling_5),
    )
    val stressTile = stringResource(R.string.tile_stress)
    val physicalTile = stringResource(R.string.tile_physical)
    val mentalTile = stringResource(R.string.tile_mental)
    LaunchedEffect(Unit) {
        val settings = container.settings.get()
        presets = settings.sleepPresetMinutes
        mask = settings.enabledTilesMask
        val latest = container.checkIns.latest()
        if (settings.sameAsLastEnabled && latest != null &&
            Duration.between(latest.recordedAtUtc, container.clock.instant()) <= java.time.Duration.ofDays(7)
        ) {
            stress = latest.stress
            physical = latest.physical
            mental = latest.mental
            reusedId = latest.id
            banner = latest.experiencedAtUtc.atZone(zone).toLocalTime().toString()
        }
        yesterday = container.sleep.find(today.minusDays(1))?.durationMinutes
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.today_label, today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
            Text(stringResource(R.string.last_check_in_none))
            if (banner != null) Text(stringResource(R.string.same_as_last, banner!!))
            if (mask and 1 != 0) {
                TileCard(stringResource(R.string.tile_sleep)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { minutes ->
                            FilterChip(selected = sleepMinutes == minutes, onClick = {
                                sleepMinutes = minutes
                                sleepSource = SleepSource.MANUAL
                            }, label = { Text(stringResource(R.string.duration_hm, minutes / 60, minutes % 60)) })
                        }
                    }
                    yesterday?.let { previous ->
                        TextButton(onClick = {
                            sleepMinutes = previous
                            sleepSource = SleepSource.REUSED_CONFIRMED
                        }) {
                            Text(stringResource(R.string.sleep_same, stringResource(R.string.duration_hm, previous / 60, previous % 60)))
                        }
                    }
                    if (warning) Text(stringResource(R.string.sleep_warning))
                }
            }
            if (mask and 2 != 0) TileCard(stringResource(R.string.tile_stress)) {
                RatingRow(stressLabels, stressLabels.mapIndexed { index, label -> stringResource(R.string.rating_semantics, stressTile, label, index + 1) }, stress) {
                    stress = it
                    stressTouched = true
                }
            }
            if (mask and 4 != 0) TileCard(stringResource(R.string.tile_physical)) {
                RatingRow(feelingLabels, feelingLabels.mapIndexed { index, label -> stringResource(R.string.rating_semantics, physicalTile, label, index + 1) }, physical) {
                    physical = it
                    physicalTouched = true
                }
            }
            if (mask and 8 != 0) TileCard(stringResource(R.string.tile_mental)) {
                RatingRow(feelingLabels, feelingLabels.mapIndexed { index, label -> stringResource(R.string.rating_semantics, mentalTile, label, index + 1) }, mental) {
                    mental = it
                    mentalTouched = true
                }
            }
            if (mask and 16 != 0) {
                TextButton(onClick = { notesOpen = !notesOpen }) { Text(stringResource(R.string.tile_notes)) }
                if (notesOpen) {
                    OutlinedTextField(note, { if (it.length <= 2000) note = it }, label = { Text(stringResource(R.string.notes_hint)) }, modifier = Modifier.fillMaxWidth())
                    if (note.length >= 1800) Text(stringResource(R.string.note_counter, note.length, 2000))
                    Text(stringResource(R.string.pain_label))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        (0..10).forEach { value ->
                            FilterChip(selected = pain == value, onClick = { pain = if (pain == value) null else value }, label = { Text(value.toString()) })
                        }
                    }
                }
            }
            error?.let { Text(it) }
            val canSave = stress != null || physical != null || mental != null || sleepMinutes != null || note.isNotBlank() || pain != null
            Button(onClick = {
                scope.launch {
                    error = null
                    warning = false
                    val now = container.clock.instant()
                    if (sleepMinutes != null) {
                        when (val sleepResult = container.saveSleep.save(today, sleepMinutes!!, sleepSource, zone)) {
                            is SaveSleepResult.Invalid -> error = rangeText
                            is SaveSleepResult.Saved -> warning = sleepResult.warning != null
                        }
                    }
                    if (stress != null || physical != null || mental != null || note.isNotBlank() || pain != null) {
                        val latest = reusedId?.let { container.checkIns.find(it) }
                        val result = container.saveCheckIn.save(
                            CheckInDraft(
                                experiencedAt = now, zoneId = zone, stress = stress, stressTouched = stressTouched,
                                physical = physical, physicalTouched = physicalTouched, mental = mental,
                                mentalTouched = mentalTouched, note = note.ifBlank { null }, painIntensity = pain,
                                tagIds = emptyList(), reusedFrom = latest, source = CheckInSource.APP, promptAlarmId = null,
                            ),
                        )
                        when (result) {
                            is SaveCheckInResult.Invalid -> error = problemText(result.problems.first(), emptyText, rangeText, noteText, futureText, pastText)
                            is SaveCheckInResult.Saved -> {
                                val savedAt = result.checkIn.recordedAtUtc
                                val id = result.checkIn.id
                                snackbar.showSnackbar(savedText, undoText).let { outcome ->
                                    if (outcome == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        container.saveCheckIn.undo(id, savedAt)
                                    }
                                }
                                stress = null; physical = null; mental = null; note = ""; pain = null
                                stressTouched = false; physicalTouched = false; mentalTouched = false
                                banner = null; reusedId = null
                            }
                        }
                    }
                }
            }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_save))
            }
            Text(
                if (DateFormat.is24HourFormat(context)) stringResource(R.string.change_time) else stringResource(R.string.change_time),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun problemText(problem: CheckInProblem, empty: String, range: String, note: String, future: String, past: String): String =
    when (problem) {
        CheckInProblem.EMPTY -> empty
        CheckInProblem.RATING_OUT_OF_RANGE, CheckInProblem.PAIN_OUT_OF_RANGE -> range
        CheckInProblem.NOTE_TOO_LONG -> note
        CheckInProblem.TIME_IN_FUTURE -> future
        CheckInProblem.TIME_TOO_OLD -> past
    }

@Composable
private fun HistoryScreen(container: AppContainer) {
    val resources = LocalResources.current
    var lines by remember { mutableStateOf(listOf<String>()) }
    val empty = stringResource(R.string.history_empty)
    val sleepLabel = stringResource(R.string.tile_sleep)
    val checkLabel = stringResource(R.string.filter_checkins)
    val none = stringResource(R.string.outcome_none)
    LaunchedEffect(Unit) {
        val zone = container.zones.zoneId()
        val today = container.clock.instant().atZone(zone).toLocalDate()
        val checkIns = container.checkIns.between(today.minusDays(30), today)
        val sleep = container.sleep.between(today.minusDays(30), today)
        lines = sleep.map { resources.getString(R.string.history_sleep_line, sleepLabel, it.wakeDate.toString(), it.durationMinutes) } +
            checkIns.map { resources.getString(R.string.history_checkin_line, checkLabel, it.localDate.toString(), it.stress?.toString() ?: none) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (lines.isEmpty()) Text(empty) else lines.forEach { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
private fun TrendsScreen(container: AppContainer) {
    val resources = LocalResources.current
    var body by remember { mutableStateOf(listOf<String>()) }
    var points by remember { mutableStateOf(listOf<Float>()) }
    val off = stringResource(R.string.trends_off)
    val similar = stringResource(R.string.compare_similar)
    val higher = stringResource(R.string.compare_higher)
    val better = stringResource(R.string.compare_better)
    val worse = stringResource(R.string.compare_worse)
    val lower = stringResource(R.string.compare_lower)
    val notEnough = stringResource(R.string.compare_need)
    val pattern = stringResource(R.string.possible_pattern)
    val noPattern = stringResource(R.string.no_clear_pattern)
    val footer = stringResource(R.string.not_medical_advice)
    val physical = stringResource(R.string.tile_physical)
    LaunchedEffect(Unit) {
        val settings = container.settings.get()
        if (!settings.trendsEnabled) {
            body = listOf(off)
            return@LaunchedEffect
        }
        val zone = container.zones.zoneId()
        val today = container.clock.instant().atZone(zone).toLocalDate()
        val start = today.minusDays(28)
        val checkIns = container.checkIns.between(start, today.minusDays(1))
        val byDay = checkIns.groupBy { it.localDate }.mapValues { entry -> entry.value.mapNotNull { it.physical } }
        val series = DayAggregator.series(byDay, start, today.minusDays(1))
        val values = series.mapNotNull { it.value }
        val usual = Baseline.usualRange(values)
        val observed = series.count { it.value != null }
        val lines = mutableListOf(resources.getString(R.string.coverage, observed, series.size))
        lines += if (usual.median == null) {
            resources.getString(R.string.usual_range_need, usual.observed)
        } else {
            resources.getString(R.string.usual_range_span, usual.low, usual.high)
        }
        val week = WeekFields.ISO
        val thisWeek = series.filter { it.date.get(week.weekOfWeekBasedYear()) == today.get(week.weekOfWeekBasedYear()) }.mapNotNull { it.value }
        val summary = WeeklySummary.summarize(TileKind.PHYSICAL, thisWeek, emptyList())
        lines += when (summary.relation) {
            WeekRelation.SIMILAR -> similar
            WeekRelation.HIGHER -> higher
            WeekRelation.LOWER -> lower
            WeekRelation.BETTER -> better
            WeekRelation.WORSE -> worse
            WeekRelation.NOT_ENOUGH -> notEnough
        }
        val sleep = container.sleep.between(today.minusDays(120), today)
        val sleepByDate = sleep.associate { it.wakeDate to it.durationMinutes.toDouble() }
        val pairedDates = sleepByDate.keys.filter { byDay[it]?.isNotEmpty() == true }.sorted()
        val result = if (pairedDates.size >= 30) {
            SleepSpearman.evaluate(
                pairedDates.map { sleepByDate.getValue(it) },
                pairedDates.map { DayAggregator.dayValue(byDay.getValue(it)) ?: 0.0 },
                pairedDates,
            )
        } else {
            SpearmanResult.Insufficient(app.kompas.domain.analytics.SpearmanGate.TOO_FEW, pairedDates.size, 0)
        }
        lines += when (result) {
            is SpearmanResult.Pattern -> pattern
            SpearmanResult.NoClearPattern -> noPattern
            is SpearmanResult.Insufficient -> noPattern
        }
        lines += footer
        body = lines
        points = series.map { it.value?.toFloat() ?: Float.NaN }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_trends), style = MaterialTheme.typography.titleLarge)
        val summary = body.joinToString(". ")
        Canvas(Modifier.fillMaxWidth().heightIn(min = 120.dp).semantics { contentDescription = summary }) {
            val finite = points.filter { !it.isNaN() }
            if (finite.isEmpty()) return@Canvas
            val min = finite.min()
            val max = finite.max().coerceAtLeast(min + 1f)
            points.forEachIndexed { index, value ->
                if (!value.isNaN()) {
                    val x = size.width * index / points.size.coerceAtLeast(1)
                    val y = size.height * (1f - (value - min) / (max - min))
                    drawCircle(color = androidx.compose.ui.graphics.Color.Black, radius = 6f, center = Offset(x, y))
                }
            }
        }
        Text(stringResource(R.string.show_table))
        body.forEach { Text(it) }
        Text(stringResource(R.string.chart_summary, physical, summary))
    }
}

@Composable
private fun MedsScreen(container: AppContainer) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var meds by remember { mutableStateOf(listOf<String>()) }
    val nameError = stringResource(R.string.error_name)
    val doseError = stringResource(R.string.error_dose)
    val timeError = stringResource(R.string.error_times)
    val dayError = stringResource(R.string.error_days)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { meds = container.medications.medications().map { it.name } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (meds.isEmpty()) Text(stringResource(R.string.meds_empty)) else meds.forEach { Text(it) }
        Text(stringResource(R.string.add_medication), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it }, label = { Text(stringResource(R.string.field_dose)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.med_disclaimer))
        error?.let { Text(it) }
        Button(onClick = {
            scope.launch {
                val problems = ScheduleRules.validate(name, amount.ifBlank { null }, listOf(LocalTime.of(8, 0)), WeekMask.ALL_DAYS, null, true)
                if (problems.isNotEmpty()) {
                    error = when {
                        app.kompas.domain.medication.ScheduleProblem.DOSE_AMOUNT in problems -> doseError
                        app.kompas.domain.medication.ScheduleProblem.TIMES in problems -> timeError
                        app.kompas.domain.medication.ScheduleProblem.DAYS in problems -> dayError
                        else -> nameError
                    }
                    return@launch
                }
                val zone = container.zones.zoneId()
                container.saveSchedule.create(
                    name, null, null,
                    ScheduleVersion(
                        id = UUID.randomUUID().toString(), medicationId = "", versionNo = 1,
                        effectiveFromDate = LocalDate.now(zone), kind = ScheduleKind.SCHEDULED,
                        doseAmount = amount.ifBlank { null }, doseUnit = null, daysOfWeekMask = WeekMask.ALL_DAYS,
                        zonePolicy = ZonePolicy.FOLLOW_DEVICE, fixedZoneId = null, importance = MedImportance.STANDARD,
                        instructions = null, createdAtUtc = Instant.EPOCH, times = listOf(LocalTime.of(8, 0)),
                    ),
                )
                container.scheduler.reconcile("med")
                meds = container.medications.medications().map { it.name }
                name = ""
                amount = ""
                error = null
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_save)) }
        Text(stringResource(R.string.outcome_none))
    }
}

@Composable
private fun RemindersScreen(container: AppContainer) {
    var rows by remember { mutableStateOf(listOf<CheckInReminder>()) }
    var quiet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val overlap = stringResource(R.string.overlap_warning)
    LaunchedEffect(Unit) {
        rows = container.reminders.all()
        quiet = container.reminders.quietHours().enabled
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) Text(stringResource(R.string.reminders_empty))
        rows.forEach { Text(stringResource(R.string.reminder_time, it.localTime.toString())) }
        if (rows.map { it.localTime }.distinct().size != rows.size) Text(overlap)
        Button(onClick = {
            scope.launch {
                container.reminders.save(
                    CheckInReminder(UUID.randomUUID().toString(), LocalTime.of(9, 0), WeekMask.ALL_DAYS, true, null, container.clock.instant()),
                )
                container.scheduler.reconcile("reminder")
                rows = container.reminders.all()
            }
        }) { Text(stringResource(R.string.add_reminder)) }
        Row {
            Switch(checked = quiet, onCheckedChange = { checked ->
                quiet = checked
                scope.launch {
                    val current = container.reminders.quietHours()
                    container.reminders.saveQuietHours(current.copy(enabled = checked, checkInBehavior = CheckInQuietBehavior.SUPPRESS, medicationBehavior = MedicationQuietBehavior.DELIVER_NORMALLY))
                    container.scheduler.reconcile("quiet")
                }
            })
            Text(stringResource(R.string.quiet_hours))
        }
        Text(stringResource(R.string.show_names_body))
        Button(onClick = { container.notifications.postTest(Channels.CHECKIN) }) { Text(stringResource(R.string.test_notification)) }
    }
}

@Composable
private fun StatusScreen(container: AppContainer) {
    val context = LocalContext.current
    var late by remember { mutableStateOf<Long?>(null) }
    val rows = container.statusRows()
    LaunchedEffect(Unit) { late = container.lateMinutes() }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            TextButton(onClick = { row.fix?.let { context.startActivity(it) } }, enabled = row.fix != null) {
                Text(
                    stringResource(
                        R.string.status_value,
                        stringResource(row.labelRes),
                        stringResource(if (row.enabled) R.string.status_on else R.string.status_off),
                    ),
                )
            }
        }
        late?.let { Text(stringResource(R.string.status_late, it)) }
        Text(stringResource(R.string.status_honest))
        Text(stringResource(R.string.status_force_stop))
        Text(stringResource(R.string.status_doze))
        Text(stringResource(R.string.mode_approximate))
        Text(stringResource(R.string.mode_exact))
    }
}

@Composable
private fun BackupScreen(container: AppContainer) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val short = stringResource(R.string.passphrase_short)
    val mismatch = stringResource(R.string.passphrase_mismatch)
    val done = stringResource(R.string.backup_done)
    val wrong = stringResource(R.string.backup_wrong)
    val truncated = stringResource(R.string.backup_truncated)
    val newer = stringResource(R.string.backup_newer)
    val notBackup = stringResource(R.string.backup_not)
    val storage = stringResource(R.string.backup_storage)
    val limit = stringResource(R.string.backup_limit)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val chars = pass.toCharArray()
            val service = backupService(container, context)
            val plain = service.exportBytes()
            val sealed = service.seal(plain, chars)
            chars.fill('\u0000')
            context.contentResolver.openOutputStream(uri)?.use { it.write(sealed) }
            container.settings.setLastExport(container.clock.instant())
            message = done.format(plain.size)
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.passphrase_warning))
        OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.passphrase)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(again, { again = it }, label = { Text(stringResource(R.string.passphrase_again)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.passphrase_hint))
        Button(onClick = {
            when {
                pass.length < 10 -> message = short
                pass != again -> message = mismatch
                else -> create.launch("feelings-backup-${LocalDate.now()}.ftbk")
            }
        }) { Text(stringResource(R.string.backup_export)) }
        Text(stringResource(R.string.import_replace_hint))
        message?.let { Text(it) }
        Text(wrong); Text(truncated); Text(newer); Text(notBackup); Text(storage); Text(limit)
        Text(stringResource(R.string.restore_banner))
        Button(onClick = {
            scope.launch {
                container.settings.setRemindersPaused(false)
                container.scheduler.reconcile("restore-review")
            }
        }) { Text(stringResource(R.string.reenable_reminders)) }
    }
}

private fun backupService(container: AppContainer, context: android.content.Context): BackupService {
    val language = "system"
    return BackupService(
        context = context,
        db = { container.database.require() },
        cipher = container.cipher,
        clock = container.clock,
        zone = ZoneId.systemDefault(),
        appVersion = BuildConfig.VERSION_NAME,
        appLanguage = language,
    )
}

@Composable
private fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var deleteText by remember { mutableStateOf("") }
    val word = stringResource(R.string.delete_word)
    val context = LocalContext.current
    var licenses by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        licenses = context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_language))
        TextButton(onClick = { scope.launch { container.prefs.setLanguage(AppLanguage.SYSTEM) } }) { Text(stringResource(R.string.language_system)) }
        TextButton(onClick = { scope.launch { container.prefs.setLanguage(AppLanguage.PL) } }) { Text(stringResource(R.string.language_pl)) }
        TextButton(onClick = { scope.launch { container.prefs.setLanguage(AppLanguage.EN) } }) { Text(stringResource(R.string.language_en)) }
        Text(stringResource(R.string.settings_theme))
        TextButton(onClick = { scope.launch { container.prefs.setTheme(ThemePref.SYSTEM) } }) { Text(stringResource(R.string.theme_system)) }
        TextButton(onClick = { scope.launch { container.prefs.setTheme(ThemePref.LIGHT) } }) { Text(stringResource(R.string.theme_light)) }
        TextButton(onClick = { scope.launch { container.prefs.setTheme(ThemePref.DARK) } }) { Text(stringResource(R.string.theme_dark)) }
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.about_statement))
        Text(stringResource(R.string.licenses))
        Text(licenses)
        Text(stringResource(R.string.delete_all_hint))
        OutlinedTextField(deleteText, { deleteText = it }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (deleteText == word) scope.launch { container.deleteAll() } }, enabled = deleteText == word) {
            Text(stringResource(R.string.delete_all))
        }
    }
}

@Composable
private fun HelpScreen() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.help_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.help_body))
    }
}
