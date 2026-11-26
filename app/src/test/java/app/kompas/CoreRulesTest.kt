package app.kompas

import app.kompas.core.crypto.SqlCipherKeys
import app.kompas.core.time.DstResolution
import app.kompas.core.time.LocalScheduleResolver
import app.kompas.core.time.QuietHoursWindow
import app.kompas.domain.analytics.Baseline
import app.kompas.domain.analytics.DayAggregator
import app.kompas.domain.analytics.DayPoint
import app.kompas.domain.analytics.Quantiles
import app.kompas.domain.analytics.SleepSpearman
import app.kompas.domain.analytics.SpearmanGate
import app.kompas.domain.analytics.SpearmanResult
import app.kompas.domain.analytics.TileKind
import app.kompas.domain.analytics.WeeklySummary
import app.kompas.domain.analytics.WeekRelation
import app.kompas.domain.analytics.roundHalfTowardNeutral
import app.kompas.domain.checkin.CheckInDraft
import app.kompas.domain.checkin.CheckInProblem
import app.kompas.domain.checkin.CheckInRules
import app.kompas.domain.medication.DoseOutcomeResolver
import app.kompas.domain.medication.OccurrenceGenerator
import app.kompas.domain.medication.WeekMask
import app.kompas.domain.model.CheckInSource
import app.kompas.domain.model.DoseEvent
import app.kompas.domain.model.DoseOutcomeKind
import app.kompas.domain.model.DoseSource
import app.kompas.domain.model.DoseStatus
import app.kompas.domain.model.MedImportance
import app.kompas.domain.model.ScheduleKind
import app.kompas.domain.model.ScheduleVersion
import app.kompas.domain.model.ValueProvenance
import app.kompas.domain.model.ZonePolicy
import app.kompas.domain.reminders.QuietHoursPolicy
import app.kompas.domain.reminders.ReminderPlanner
import app.kompas.domain.model.CheckInReminder
import app.kompas.domain.model.QuietHours
import app.kompas.export.BackupCodec
import app.kompas.export.BackupFormat
import app.kompas.export.CipherChunk
import app.kompas.export.DecryptOutcome
import app.kompas.export.EncryptedPayload
import app.kompas.export.ExportCipher
import app.kompas.export.ReadBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class CoreRulesTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun warsawSpringGapMovesForward() {
        val resolved = LocalScheduleResolver.resolve(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), warsaw)
        assertEquals(DstResolution.GAP_SHIFTED_FORWARD, resolved.resolution)
        assertEquals(LocalTime.of(3, 30), resolved.instant.atZone(warsaw).toLocalTime())
    }

    @Test
    fun warsawAutumnOverlapUsesEarlierOffset() {
        val resolved = LocalScheduleResolver.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), warsaw)
        assertEquals(DstResolution.OVERLAP_FIRST, resolved.resolution)
        assertEquals(ZoneOffset.ofHours(2), resolved.instant.atZone(warsaw).offset)
    }

    @Test
    fun quietHoursCrossMidnight() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(7, 0)
        assertTrue(QuietHoursWindow.contains(LocalTime.of(23, 0), start, end))
        assertTrue(QuietHoursWindow.contains(LocalTime.of(6, 30), start, end))
        assertTrue(!QuietHoursWindow.contains(LocalTime.of(12, 0), start, end))
    }

    @Test
    fun checkInBoundaries() {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        val zone = ZoneOffset.UTC
        fun draft(at: Instant, stress: Int? = 3, note: String? = null) = CheckInDraft(
            at, zone, stress, true, null, false, null, false, note, null, emptyList(), null, CheckInSource.APP, null,
        )
        assertTrue(CheckInRules.validate(draft(now), now).isEmpty())
        assertTrue(CheckInProblem.EMPTY in CheckInRules.validate(draft(now, stress = null), now))
        assertTrue(CheckInProblem.RATING_OUT_OF_RANGE in CheckInRules.validate(draft(now, stress = 6), now))
        assertTrue(CheckInProblem.TIME_IN_FUTURE in CheckInRules.validate(draft(now.plusSeconds(6 * 60)), now))
        assertTrue(CheckInRules.validate(draft(now.plusSeconds(4 * 60)), now).isEmpty())
        assertTrue(CheckInProblem.TIME_TOO_OLD in CheckInRules.validate(draft(now.minusSeconds(8 * 24 * 3600)), now))
        assertTrue(CheckInProblem.NOTE_TOO_LONG in CheckInRules.validate(draft(now, note = "a".repeat(2001)), now))
        assertEquals(app.kompas.domain.checkin.SleepWarning.UNUSUALLY_SHORT, CheckInRules.sleepWarning(119))
        assertNull(CheckInRules.sleepWarning(120))
        assertEquals(app.kompas.domain.checkin.SleepWarning.UNUSUALLY_LONG, CheckInRules.sleepWarning(841))
        val assembled = CheckInRules.assemble(draft(now.minusSeconds(3 * 3600)), "id", now)
        assertTrue(assembled.isRetrospective)
        assertEquals(ValueProvenance.SELECTED, assembled.stressProv)
    }

    @Test
    fun occurrenceGeneratorHonoursGapAndStopped() {
        val version = ScheduleVersion(
            id = "v1", medicationId = "m", versionNo = 1, effectiveFromDate = LocalDate.of(2026, 3, 1),
            kind = ScheduleKind.SCHEDULED, doseAmount = "1", doseUnit = "tablet", daysOfWeekMask = WeekMask.ALL_DAYS,
            zonePolicy = ZonePolicy.FIXED_ZONE, fixedZoneId = warsaw, importance = MedImportance.STANDARD,
            instructions = null, createdAtUtc = Instant.EPOCH, times = listOf(LocalTime.of(2, 30)),
        )
        val planned = OccurrenceGenerator.plan(listOf(version), LocalDate.of(2026, 3, 29), warsaw)
        val gap = planned.first { it.localDate == LocalDate.of(2026, 3, 29) }
        assertEquals(DstResolution.GAP_SHIFTED_FORWARD, gap.dstResolution)
        val stopped = version.copy(id = "v2", versionNo = 2, effectiveFromDate = LocalDate.of(2026, 3, 30), kind = ScheduleKind.STOPPED, times = emptyList())
        val afterStop = OccurrenceGenerator.plan(listOf(version, stopped), LocalDate.of(2026, 3, 29), warsaw)
        assertTrue(afterStop.none { it.localDate >= LocalDate.of(2026, 3, 30) })
    }

    @Test
    fun doseOutcomeIgnoresSupersededAndSnooze() {
        val zone = ZoneOffset.UTC
        fun event(id: String, status: DoseStatus, at: Long, supersedes: String? = null) = DoseEvent(
            id, "a-$id", "occ", "m", status, null, Instant.ofEpochMilli(at), zone, null, null, null, DoseSource.APP, supersedes,
        )
        val snooze = event("s", DoseStatus.SNOOZED, 1)
        assertEquals(DoseOutcomeKind.NO_RECORD, DoseOutcomeResolver.resolve(listOf(snooze)).kind)
        val taken = event("t", DoseStatus.TAKEN, 2)
        val correction = event("c", DoseStatus.SKIPPED, 3, supersedes = "t")
        assertEquals(DoseOutcomeKind.SKIPPED, DoseOutcomeResolver.resolve(listOf(taken, correction)).kind)
    }

    @Test
    fun quietHoursNeverMoveMedication() {
        val quiet = QuietHours(enabled = true, startLocal = LocalTime.of(22, 0), endLocal = LocalTime.of(7, 0))
        val evening = Instant.parse("2026-01-05T22:30:00Z").atZone(ZoneOffset.UTC).toInstant()
        val adjusted = QuietHoursPolicy.adjustCheckIn(evening, LocalDate.of(2026, 1, 5), LocalTime.of(22, 30), quiet, ZoneOffset.UTC)
        assertNull(adjusted)
        val reminder = CheckInReminder("r", LocalTime.of(23, 0), WeekMask.ALL_DAYS, true, null, Instant.EPOCH)
        val planned = ReminderPlanner.plan(
            now = Instant.parse("2026-01-05T00:00:00Z"),
            zone = ZoneOffset.UTC,
            reminders = listOf(reminder),
            quietHours = quiet,
            occurrences = emptyList(),
            snoozes = emptyList(),
            alreadyFiredKeys = emptySet(),
            remindersPaused = false,
        )
        assertTrue(planned.none { it.items.any { item -> item.refId == "r" && item.kind.name == "CHECK_IN" } })
    }

    @Test
    fun analyticsFixtures() {
        assertEquals(1.75, Quantiles.type7(listOf(1.0, 2.0, 3.0, 4.0), 0.25), 1e-9)
        assertEquals(3, roundHalfTowardNeutral(3.5))
        assertEquals(3, roundHalfTowardNeutral(2.5))
        assertEquals(3.0, DayAggregator.dayValue(listOf(2, 4)))
        val points = (0 until 14).map { DayPoint(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), if (it < 3) null else 3.0) }
        assertNull(Baseline.rollingMedian(points).first { it.date == LocalDate.of(2026, 1, 3) }.value)
        val summary = WeeklySummary.summarize(TileKind.STRESS, listOf(4.0, 4.0, 4.0, 4.0), listOf(2.0, 2.0, 2.0, 2.0))
        assertEquals(WeekRelation.HIGHER, summary.relation)
        val sleep = WeeklySummary.summarize(TileKind.SLEEP, List(4) { 480.0 }, List(4) { 400.0 })
        assertEquals(WeekRelation.HIGHER, sleep.relation)
        val ranks = SleepSpearman.averageRanks(listOf(10.0, 20.0, 20.0, 40.0))
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), ranks)
        val rho = SleepSpearman.pearson(ranks, listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(0.948683, rho, 1e-4)
        val dates = List(30) { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
        val blocked = SleepSpearman.evaluate(List(29) { 400.0 }, List(29) { 3.0 }, dates.take(29))
        assertTrue(blocked is SpearmanResult.Insufficient && blocked.gate == SpearmanGate.TOO_FEW)
    }

    @Test
    fun backupHeaderAndFakeRoundTrip() {
        val cipher = FakeCipher()
        val file = BackupCodec.write(cipher, "correct horse".toCharArray(), "hello".encodeToByteArray())
        val header = BackupFormat.parseHeader(file)
        assertTrue(header is app.kompas.export.HeaderParse.Ok)
        val read = BackupCodec.read(cipher, "correct horse".toCharArray(), file)
        assertTrue(read is ReadBackup.Plaintext && String((read as ReadBackup.Plaintext).bytes) == "hello")
        val truncated = BackupCodec.read(cipher, "correct horse".toCharArray(), file.copyOf(20))
        assertTrue(truncated is ReadBackup.Failed)
        val flipped = file.clone()
        flipped[4] = 9
        val newer = BackupCodec.read(cipher, "correct horse".toCharArray(), flipped)
        assertTrue(newer is ReadBackup.Failed)
    }

    @Test
    fun rawKeyShapeSkipsKdfSyntax() {
        val key = ByteArray(32) { it.toByte() }
        val encoded = SqlCipherKeys.rawKeyPassphrase(key).toString(Charsets.US_ASCII)
        assertTrue(encoded.matches(Regex("x'[0-9a-f]{64}'")))
    }

    @Test
    fun phrasingGuard() {
        val candidates = listOf(
            java.io.File("src/main/res"),
            java.io.File("app/src/main/res"),
        )
        val res = candidates.firstOrNull { java.io.File(it, "values/strings.xml").exists() }
            ?: error("strings.xml not found from ${java.io.File("").absolutePath}")
        val files = listOf(java.io.File(res, "values/strings.xml"), java.io.File(res, "values-pl/strings.xml"))
        val forbidden = listOf(
            Regex("\\bcauses\\b", RegexOption.IGNORE_CASE),
            Regex("\\bbecause of\\b", RegexOption.IGNORE_CASE),
            Regex("\\bmeans you have\\b", RegexOption.IGNORE_CASE),
            Regex("\\brisk\\b", RegexOption.IGNORE_CASE),
            Regex("\\bdiagnosis\\b", RegexOption.IGNORE_CASE),
            Regex("\\bnormal\\b", RegexOption.IGNORE_CASE),
            Regex("\\babnormal\\b", RegexOption.IGNORE_CASE),
            Regex("\\byou should\\b", RegexOption.IGNORE_CASE),
            Regex("ryzyk", RegexOption.IGNORE_CASE),
            Regex("powoduj", RegexOption.IGNORE_CASE),
            Regex("nieprawidłow", RegexOption.IGNORE_CASE),
            Regex("powinien", RegexOption.IGNORE_CASE),
            Regex("powinna", RegexOption.IGNORE_CASE),
        )
        val hits = files.flatMap { file ->
            file.readLines().filter { line ->
                val denial = line.contains("does not diagnose") || line.contains("nie stawia") || line.contains("nie daje")
                !denial && forbidden.any { it.containsMatchIn(line) }
            }.map { file.name + ": " + it.trim() }
        }
        assertTrue(hits.joinToString(), hits.isEmpty())
    }
}

private class FakeCipher : ExportCipher {
    override fun randomBytes(size: Int): ByteArray = ByteArray(size) { 7 }

    override fun deriveKey(passphrase: ByteArray, salt: ByteArray, opslimit: Int, memlimitBytes: Long): ByteArray =
        ByteArray(32) { index -> (passphrase.getOrElse(index) { 0 } + salt[index % salt.size]).toByte() }

    override fun encrypt(
        key: ByteArray,
        chunks: List<ByteArray>,
        additionalDataFor: (streamHeader: ByteArray) -> ByteArray,
    ): EncryptedPayload {
        val header = ByteArray(24) { 3 }
        val ad = additionalDataFor(header)
        val encrypted = chunks.mapIndexed { index, plain ->
            val tag = if (index == chunks.lastIndex) 1 else 0
            CipherChunk(byteArrayOf(tag.toByte(), ad.size.toByte()) + plain.map { (it.toInt() xor key[0].toInt()).toByte() }.toByteArray())
        }
        return EncryptedPayload(header, encrypted)
    }

    override fun decrypt(key: ByteArray, payload: EncryptedPayload, additionalData: ByteArray): DecryptOutcome {
        var sawFinal = false
        val out = java.io.ByteArrayOutputStream()
        payload.chunks.forEach { chunk ->
            if (sawFinal) return DecryptOutcome.Trailing
            if (chunk.ciphertext.size < 2) return DecryptOutcome.AuthFailed
            if (chunk.ciphertext[0].toInt() == 1) sawFinal = true
            chunk.ciphertext.drop(2).forEach { out.write(it.toInt() xor key[0].toInt()) }
        }
        return if (sawFinal) DecryptOutcome.Ok(out.toByteArray()) else DecryptOutcome.MissingFinal
    }
}
