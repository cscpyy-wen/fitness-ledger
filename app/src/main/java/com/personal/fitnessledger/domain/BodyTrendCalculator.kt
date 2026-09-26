package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.BodyMeasurement
import com.personal.fitnessledger.data.isXiaomi
import java.time.LocalDate

data class BodyTrendPoint(
    val measurement: BodyMeasurement,
    val sevenDayAverageKg: Double,
)

data class BodyTrendSummary(
    val windowDays: Int,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val points: List<BodyTrendPoint>,
    val latestSevenDayAverageKg: Double?,
    val previousSevenDayAverageKg: Double?,
    val sevenDayAverageChangeKg: Double?,
    val windowWeightChangeKg: Double?,
    val windowWaistChangeCm: Double?,
    val latestSevenDaySampleDays: Int = 0,
)

object BodyTrendCalculator {
    fun summarize(
        measurements: List<BodyMeasurement>,
        today: LocalDate,
        windowDays: Int,
    ): BodyTrendSummary {
        require(windowDays == 28 || windowDays == 56) { "Trend window must be 28 or 56 days" }
        val normalized = measurements
            .filter { it.date <= today }
            .sortedWith(compareBy<BodyMeasurement> { it.date }.thenBy { it.id })
            .groupBy { it.date }
            .map { (_, sameDay) -> sameDay.lastOrNull { !it.isXiaomi } ?: sameDay.minBy { it.cloudMeasuredAtSeconds!! } }
            .sortedBy { it.date }
        val windowStart = today.minusDays((windowDays - 1).toLong())
        val inWindow = normalized.filter { it.date >= windowStart }
        val points = inWindow.map { measurement ->
            BodyTrendPoint(
                measurement = measurement,
                sevenDayAverageKg = normalized
                    .filter { it.date in measurement.date.minusDays(6)..measurement.date }
                    .map { it.weightKg }
                    .average(),
            )
        }
        val latestDate = normalized.lastOrNull()?.date
        val latestAverage = latestDate?.let { anchor ->
            normalized.filter { it.date in anchor.minusDays(6)..anchor }.map { it.weightKg }.averageOrNull()
        }
        val previousAverage = latestDate?.let { anchor ->
            normalized.filter { it.date in anchor.minusDays(13)..anchor.minusDays(7) }.map { it.weightKg }.averageOrNull()
        }
        val weights = inWindow.map { it.weightKg }
        val waists = inWindow.mapNotNull { measurement -> measurement.waistCm?.let { measurement.date to it } }
        return BodyTrendSummary(
            windowDays = windowDays,
            windowStart = windowStart,
            windowEnd = today,
            points = points,
            latestSevenDayAverageKg = latestAverage,
            previousSevenDayAverageKg = previousAverage,
            sevenDayAverageChangeKg = if (latestAverage != null && previousAverage != null) latestAverage - previousAverage else null,
            windowWeightChangeKg = if (weights.size >= 2) weights.last() - weights.first() else null,
            windowWaistChangeCm = if (waists.size >= 2) waists.last().second - waists.first().second else null,
            latestSevenDaySampleDays = latestDate?.let { anchor -> normalized.count { it.date in anchor.minusDays(6)..anchor } } ?: 0,
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
