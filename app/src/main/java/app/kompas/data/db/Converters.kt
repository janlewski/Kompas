package app.kompas.data.db

import androidx.room.TypeConverter
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

class Converters {
    @TypeConverter fun checkInSource(value: CheckInSource): String = value.name

    @TypeConverter fun checkInSource(value: String): CheckInSource = CheckInSource.valueOf(value)

    @TypeConverter fun provenance(value: ValueProvenance): String = value.name

    @TypeConverter fun provenance(value: String): ValueProvenance = ValueProvenance.valueOf(value)

    @TypeConverter fun sleepDefinition(value: SleepDefinition): String = value.name

    @TypeConverter fun sleepDefinition(value: String): SleepDefinition = SleepDefinition.valueOf(value)

    @TypeConverter fun sleepSource(value: SleepSource): String = value.name

    @TypeConverter fun sleepSource(value: String): SleepSource = SleepSource.valueOf(value)

    @TypeConverter fun scheduleKind(value: ScheduleKind): String = value.name

    @TypeConverter fun scheduleKind(value: String): ScheduleKind = ScheduleKind.valueOf(value)

    @TypeConverter fun zonePolicy(value: ZonePolicy): String = value.name

    @TypeConverter fun zonePolicy(value: String): ZonePolicy = ZonePolicy.valueOf(value)

    @TypeConverter fun importance(value: MedImportance): String = value.name

    @TypeConverter fun importance(value: String): MedImportance = MedImportance.valueOf(value)

    @TypeConverter fun occurrenceState(value: DoseOccurrenceState): String = value.name

    @TypeConverter fun occurrenceState(value: String): DoseOccurrenceState = DoseOccurrenceState.valueOf(value)

    @TypeConverter fun dst(value: DstResolution): String = value.name

    @TypeConverter fun dst(value: String): DstResolution = DstResolution.valueOf(value)

    @TypeConverter fun doseStatus(value: DoseStatus): String = value.name

    @TypeConverter fun doseStatus(value: String): DoseStatus = DoseStatus.valueOf(value)

    @TypeConverter fun doseSource(value: DoseSource): String = value.name

    @TypeConverter fun doseSource(value: String): DoseSource = DoseSource.valueOf(value)

    @TypeConverter fun quickRate(value: QuickRateDimension): String = value.name

    @TypeConverter fun quickRate(value: String): QuickRateDimension = QuickRateDimension.valueOf(value)

    @TypeConverter fun ageBand(value: AgeBand): String = value.name

    @TypeConverter fun ageBand(value: String): AgeBand = AgeBand.valueOf(value)

    @TypeConverter fun checkInQuiet(value: CheckInQuietBehavior): String = value.name

    @TypeConverter fun checkInQuiet(value: String): CheckInQuietBehavior = CheckInQuietBehavior.valueOf(value)

    @TypeConverter fun medQuiet(value: MedicationQuietBehavior): String = value.name

    @TypeConverter fun medQuiet(value: String): MedicationQuietBehavior = MedicationQuietBehavior.valueOf(value)

    @TypeConverter fun alarmMode(value: AlarmMode): String = value.name

    @TypeConverter fun alarmMode(value: String): AlarmMode = AlarmMode.valueOf(value)

    @TypeConverter fun alarmState(value: AlarmState): String = value.name

    @TypeConverter fun alarmState(value: String): AlarmState = AlarmState.valueOf(value)
}
