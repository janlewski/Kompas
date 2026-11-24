package app.feelings.tracker.domain.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.atanh
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tanh

enum class TileKind { STRESS, PHYSICAL, MENTAL, SLEEP }

object Quantiles {
    /** Hyndman–Fan type 7. [sorted] must be non-empty and in ascending order. */
    fun type7(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty())
        require(p in 0.0..1.0)
        if (sorted.size == 1) return sorted[0]
        val h = (sorted.size - 1) * p + 1
        val index = floor(h).toInt()
        val fraction = h - index
        val lower = sorted[index - 1]
        if (fraction == 0.0) return lower
        return lower + fraction * (sorted[index] - lower)
    }

    fun median(sorted: List<Double>): Double = type7(sorted, 0.5)
}

fun roundHalfTowardNeutral(value: Double): Int {
    val base = floor(value).toInt()
    val fraction = value - base
    if (fraction < 0.5) return base
    if (fraction > 0.5) return base + 1
    val down = base
    val up = base + 1
    return if (kotlin.math.abs(down - 3) <= kotlin.math.abs(up - 3)) down else up
}

data class DayPoint(val date: LocalDate, val value: Double?)

object DayAggregator {
    fun dayValue(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.map { it.toDouble() }.sorted()
        return Quantiles.median(sorted)
    }

    /** Even counts use the mean of the two central values, which [Quantiles.median] already does. */
    fun series(entriesByDay: Map<LocalDate, List<Int>>, start: LocalDate, end: LocalDate): List<DayPoint> {
        val points = mutableListOf<DayPoint>()
        var date = start
        while (!date.isAfter(end)) {
            points += DayPoint(date, dayValue(entriesByDay[date].orEmpty()))
            date = date.plusDays(1)
        }
        return points
    }
}

data class UsualRange(
    val observed: Int,
    val needed: Int,
    val median: Double?,
    val low: Double?,
    val high: Double?,
)

object Baseline {
    const val WINDOW_DAYS = 28
    const val MIN_OBSERVED = 7
    const val ROLLING_DAYS = 14
    const val ROLLING_MIN = 4

    fun usualRange(dayValues: List<Double>): UsualRange {
        if (dayValues.size < MIN_OBSERVED) {
            return UsualRange(dayValues.size, MIN_OBSERVED, null, null, null)
        }
        val sorted = dayValues.sorted()
        return UsualRange(
            observed = dayValues.size,
            needed = MIN_OBSERVED,
            median = Quantiles.median(sorted),
            low = Quantiles.type7(sorted, 0.25),
            high = Quantiles.type7(sorted, 0.75),
        )
    }

    fun rollingMedian(points: List<DayPoint>): List<DayPoint> = points.map { point ->
        val start = point.date.minusDays((ROLLING_DAYS - 1).toLong())
        val window = points.filter { !it.date.isBefore(start) && !it.date.isAfter(point.date) }.mapNotNull { it.value }
        val value = if (window.size >= ROLLING_MIN) Quantiles.median(window.sorted()) else null
        DayPoint(point.date, value)
    }
}

enum class WeekRelation { SIMILAR, HIGHER, LOWER, BETTER, WORSE, NOT_ENOUGH }

data class WeekSummary(
    val observed: Int,
    val median: Double?,
    val minimum: Double?,
    val maximum: Double?,
    val counts: Map<Int, Int>,
    val relation: WeekRelation,
    val sleepDeltaMinutes: Int?,
)

object WeeklySummary {
    const val MIN_DAYS = 4

    fun summarize(
        tile: TileKind,
        thisWeek: List<Double>,
        previousWeek: List<Double>,
    ): WeekSummary {
        val median = thisWeek.takeIf { it.isNotEmpty() }?.let { Quantiles.median(it.sorted()) }
        val comparable = thisWeek.size >= MIN_DAYS && previousWeek.size >= MIN_DAYS
        val previousMedian = previousWeek.takeIf { it.isNotEmpty() }?.let { Quantiles.median(it.sorted()) }
        val (relation, delta) = if (!comparable || median == null || previousMedian == null) {
            WeekRelation.NOT_ENOUGH to null
        } else {
            val diff = median - previousMedian
            if (tile == TileKind.SLEEP) {
                val rounded = roundTo15(diff)
                val relation = when {
                    kotlin.math.abs(diff) < 30.0 -> WeekRelation.SIMILAR
                    diff > 0 -> WeekRelation.HIGHER
                    else -> WeekRelation.LOWER
                }
                relation to rounded
            } else {
                val relation = when {
                    kotlin.math.abs(diff) < 1.0 -> WeekRelation.SIMILAR
                    diff >= 1.0 && tile == TileKind.STRESS -> WeekRelation.HIGHER
                    diff <= -1.0 && tile == TileKind.STRESS -> WeekRelation.LOWER
                    diff >= 1.0 -> WeekRelation.BETTER
                    else -> WeekRelation.WORSE
                }
                relation to null
            }
        }
        val counts = thisWeek.groupingBy { roundHalfTowardNeutral(it) }.eachCount()
        return WeekSummary(
            observed = thisWeek.size,
            median = median,
            minimum = thisWeek.minOrNull(),
            maximum = thisWeek.maxOrNull(),
            counts = counts,
            relation = relation,
            sleepDeltaMinutes = delta,
        )
    }

    fun roundTo15(minutes: Double): Int {
        val sign = if (minutes < 0) -1 else 1
        val magnitude = kotlin.math.abs(minutes)
        val rounded = (kotlin.math.round(magnitude / 15.0) * 15.0).toInt()
        return sign * rounded
    }
}

enum class SpearmanGate { TOO_FEW, SPAN, SLEEP_IQR, FEW_LEVELS, DOMINANT_LEVEL }

data class SpearmanShown(
    val rho: Double,
    val low: Double,
    val high: Double,
    val pairs: Int,
    val spanDays: Long,
    val positive: Boolean,
)

sealed class SpearmanResult {
    data class Pattern(val shown: SpearmanShown) : SpearmanResult()

    data object NoClearPattern : SpearmanResult()

    data class Insufficient(val gate: SpearmanGate, val pairs: Int, val spanDays: Long) : SpearmanResult()
}

object SleepSpearman {
    const val MIN_PAIRS = 30
    const val MIN_SPAN_DAYS = 42L
    const val MIN_SLEEP_IQR = 30.0
    const val MIN_DISTINCT = 3
    const val MAX_DOMINANCE = 0.8
    const val MIN_ABS_RHO = 0.3
    const val CI_COEFFICIENT = 2.394

    fun evaluate(sleep: List<Double>, outcome: List<Double>, dates: List<LocalDate>): SpearmanResult {
        require(sleep.size == outcome.size && outcome.size == dates.size)
        val pairs = sleep.size
        val span = if (dates.isEmpty()) 0L else ChronoUnit.DAYS.between(dates.min(), dates.max())
        if (pairs < MIN_PAIRS) return SpearmanResult.Insufficient(SpearmanGate.TOO_FEW, pairs, span)
        if (span < MIN_SPAN_DAYS) return SpearmanResult.Insufficient(SpearmanGate.SPAN, pairs, span)
        val sleepIqr = iqr(sleep)
        if (sleepIqr < MIN_SLEEP_IQR) return SpearmanResult.Insufficient(SpearmanGate.SLEEP_IQR, pairs, span)
        val distinct = outcome.distinct().size
        if (distinct < MIN_DISTINCT) return SpearmanResult.Insufficient(SpearmanGate.FEW_LEVELS, pairs, span)
        val top = outcome.groupingBy { it }.eachCount().maxOf { it.value }
        if (top.toDouble() / pairs > MAX_DOMINANCE) {
            return SpearmanResult.Insufficient(SpearmanGate.DOMINANT_LEVEL, pairs, span)
        }
        val rho = pearson(averageRanks(sleep), averageRanks(outcome))
        val (low, high) = confidenceInterval(rho, pairs)
        val show = (low > 0.0 || high < 0.0) && kotlin.math.abs(rho) >= MIN_ABS_RHO
        if (!show) return SpearmanResult.NoClearPattern
        return SpearmanResult.Pattern(
            SpearmanShown(rho, low, high, pairs, span, positive = rho > 0),
        )
    }

    fun averageRanks(values: List<Double>): List<Double> {
        val sorted = values.withIndex().sortedBy { it.value }
        val ranks = DoubleArray(values.size)
        var index = 0
        while (index < sorted.size) {
            var end = index
            while (end + 1 < sorted.size && sorted[end + 1].value == sorted[index].value) end++
            val average = ((index + 1) + (end + 1)) / 2.0
            for (cursor in index..end) ranks[sorted[cursor].index] = average
            index = end + 1
        }
        return ranks.toList()
    }

    fun pearson(xs: List<Double>, ys: List<Double>): Double {
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var denX = 0.0
        var denY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            num += dx * dy
            denX += dx * dx
            denY += dy * dy
        }
        if (denX == 0.0 || denY == 0.0) return 0.0
        return num / sqrt(denX * denY)
    }

    fun confidenceInterval(rho: Double, n: Int): Pair<Double, Double> {
        val clamped = rho.coerceIn(-0.999999, 0.999999)
        val z = atanh(clamped)
        val se = sqrt(1.06 / (n - 3))
        val low = tanh(z - CI_COEFFICIENT * se)
        val high = tanh(z + CI_COEFFICIENT * se)
        return low to high
    }

    private fun iqr(values: List<Double>): Double {
        val sorted = values.sorted()
        return Quantiles.type7(sorted, 0.75) - Quantiles.type7(sorted, 0.25)
    }
}

object LateDelivery {
    const val LATE_AFTER_MINUTES = 10L

    fun lateMinutes(triggerAtEpochMs: Long, firedAtEpochMs: Long): Long? {
        val minutes = (firedAtEpochMs - triggerAtEpochMs) / 60_000
        return minutes.takeIf { it > LATE_AFTER_MINUTES }
    }
}
