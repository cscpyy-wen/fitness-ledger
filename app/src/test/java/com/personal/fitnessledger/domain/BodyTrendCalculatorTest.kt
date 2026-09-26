package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.BodyMeasurement
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyTrendCalculatorTest {
    private val today = LocalDate.of(2026, 8, 27)

    @Test fun `many same day cloud readings count once and manual retains priority`() {
        val early=BodyMeasurement(1,today,70.25,null,1000)
        val late=BodyMeasurement(2,today,71.0,null,2000)
        val previous=BodyMeasurement(3,today.minusDays(1),69.0,null,500)
        val cloud=BodyTrendCalculator.summarize(listOf(late,previous,early),today,28)
        assertEquals(2,cloud.latestSevenDaySampleDays)
        assertEquals(69.625,cloud.latestSevenDayAverageKg!!,0.0001)
        val manual=BodyMeasurement(4,today,72.0,82.0)
        val merged=BodyTrendCalculator.summarize(listOf(early,late,manual,previous),today,28)
        assertEquals(70.5,merged.latestSevenDayAverageKg!!,0.0001)
        assertEquals(82.0,merged.points.last().measurement.waistCm!!,0.0001)
    }

    @Test
    fun `four week summary uses trailing seven day averages and previous week`() {
        val measurements = (0L..13L).map { offset ->
            BodyMeasurement(
                id = offset + 1,
                date = today.minusDays(13 - offset),
                weightKg = 80.0 - offset * 0.1,
                waistCm = 90.0 - offset * 0.05,
            )
        }

        val summary = BodyTrendCalculator.summarize(measurements, today, 28)

        assertEquals(14, summary.points.size)
        assertEquals(79.0, summary.latestSevenDayAverageKg ?: 0.0, 0.0001)
        assertEquals(79.7, summary.previousSevenDayAverageKg ?: 0.0, 0.0001)
        assertEquals(-0.7, summary.sevenDayAverageChangeKg ?: 0.0, 0.0001)
        assertEquals(-1.3, summary.windowWeightChangeKg ?: 0.0, 0.0001)
        assertEquals(-0.65, summary.windowWaistChangeCm ?: 0.0, 0.0001)
    }

    @Test
    fun `eight week summary excludes older and future measurements`() {
        val measurements = listOf(
            BodyMeasurement(id = 1, date = today.minusDays(70), weightKg = 90.0, waistCm = null),
            BodyMeasurement(id = 2, date = today.minusDays(55), weightKg = 82.0, waistCm = null),
            BodyMeasurement(id = 3, date = today, weightKg = 80.0, waistCm = null),
            BodyMeasurement(id = 4, date = today.plusDays(1), weightKg = 60.0, waistCm = null),
        )

        val summary = BodyTrendCalculator.summarize(measurements, today, 56)

        assertEquals(listOf(2L, 3L), summary.points.map { it.measurement.id })
        assertEquals(-2.0, summary.windowWeightChangeKg ?: 0.0, 0.0001)
        assertNull(summary.previousSevenDayAverageKg)
        assertNull(summary.windowWaistChangeCm)
    }
}
