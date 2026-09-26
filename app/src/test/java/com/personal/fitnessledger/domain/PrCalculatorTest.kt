package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.PrType
import com.personal.fitnessledger.data.PrEventKind
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrCalculatorTest {
    @Test
    fun `first valid set establishes baselines instead of breaking records`() {
        val result = PrCalculator.evaluate(set(80.0, 5), emptyList())

        assertTrue(result.records.isEmpty())
        assertTrue(PrType.MAX_WEIGHT in result.baselines)
        assertTrue(PrType.WEIGHT_AT_REPS in result.baselines)
        assertTrue(PrType.ESTIMATED_1RM in result.baselines)
    }

    @Test
    fun `102 point 5 by 5 breaks five rep weight record over 100 by 5`() {
        val result = PrCalculator.evaluate(set(102.5, 5), listOf(set(100.0, 5)))

        assertTrue(PrType.WEIGHT_AT_REPS in result.records)
        assertTrue(PrType.MAX_WEIGHT in result.records)
    }

    @Test
    fun `100 by 6 breaks reps at same weight`() {
        val result = PrCalculator.evaluate(set(100.0, 6), listOf(set(100.0, 5)))

        assertTrue(PrType.REP_AT_WEIGHT in result.records)
        assertFalse(PrType.MAX_WEIGHT in result.records)
    }

    @Test
    fun `95 by 8 can break e1rm without breaking max weight`() {
        val result = PrCalculator.evaluate(set(95.0, 8), listOf(set(100.0, 5)))

        assertTrue(PrType.ESTIMATED_1RM in result.records)
        assertFalse(PrType.MAX_WEIGHT in result.records)
        assertEquals(120.333, result.estimated1RmKg, 0.01)
    }

    @Test
    fun `sets over twelve reps do not calculate e1rm`() {
        assertEquals(0.0, PrCalculator.estimatedOneRepMax(60.0, 13), 0.0)
    }

    @Test
    fun `warmup and zero rep sets cannot create records`() {
        val warmup = set(100.0, 5).copy(isWarmup = true)
        val failed = set(100.0, 0)

        assertTrue(PrCalculator.evaluate(warmup, emptyList()).records.isEmpty())
        assertTrue(PrCalculator.evaluate(warmup, emptyList()).baselines.isEmpty())
        assertTrue(PrCalculator.evaluate(failed, emptyList()).baselines.isEmpty())
    }

    @Test
    fun `first workout with ascending sets remains baseline only`() {
        val decisions = PrCalculator.evaluateSession(
            TrackingType.WEIGHT_REPS,
            currentSets = listOf(set(80.0, 5).copy(setOrder = 1), set(82.5, 5).copy(setOrder = 2)),
            historicalSets = emptyList(),
        )

        assertTrue(decisions.isNotEmpty())
        assertTrue(decisions.all { it.eventKind == PrEventKind.BASELINE })
        assertEquals(82.5, decisions.first { it.metric.type == PrType.MAX_WEIGHT }.metric.value, 0.001)
    }

    @Test
    fun `weight at reps keeps independent five and ten rep buckets`() {
        val decisions = PrCalculator.evaluateSession(
            TrackingType.WEIGHT_REPS,
            currentSets = listOf(set(100.0, 5), set(80.0, 10)),
            historicalSets = emptyList(),
        )
        val buckets = decisions.filter { it.metric.type == PrType.WEIGHT_AT_REPS }.map { it.metric.bucketKey }.toSet()

        assertEquals(setOf("reps:5", "reps:10"), buckets)
    }

    @Test
    fun `duration and assisted actions use their own PR direction`() {
        val duration = PrCalculator.evaluateSession(
            TrackingType.DURATION,
            currentSets = listOf(set(0.0, 0).copy(durationSeconds = 75)),
            historicalSets = listOf(set(0.0, 0).copy(durationSeconds = 60)),
        )
        val assisted = PrCalculator.evaluateSession(
            TrackingType.ASSISTED_REPS,
            currentSets = listOf(set(25.0, 8)),
            historicalSets = listOf(set(30.0, 8)),
        )

        assertEquals(PrEventKind.BROKEN, duration.single().eventKind)
        assertTrue(assisted.any { it.metric.type == PrType.MIN_ASSISTANCE_AT_REPS && it.eventKind == PrEventKind.BROKEN })
    }

    @Test
    fun `incomplete current and historical sets never enter PR comparison`() {
        val decisions = PrCalculator.evaluateSession(
            TrackingType.WEIGHT_REPS,
            currentSets = listOf(
                set(120.0, 5).copy(completed = false),
                set(80.0, 5).copy(completed = true),
            ),
            historicalSets = listOf(set(200.0, 5).copy(completed = false)),
        )

        assertTrue(decisions.isNotEmpty())
        assertTrue(decisions.all { it.eventKind == PrEventKind.BASELINE })
        assertEquals(80.0, decisions.first { it.metric.type == PrType.MAX_WEIGHT }.metric.value, 0.001)
    }

    private fun set(weightKg: Double, reps: Int) = WorkoutSet(
        exerciseId = 1,
        setOrder = 1,
        loadGrams = (weightKg * 1000).toLong(),
        reps = reps,
    )
}
