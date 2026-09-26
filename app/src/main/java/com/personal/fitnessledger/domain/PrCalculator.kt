package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.PrEvaluation
import com.personal.fitnessledger.data.PrDecision
import com.personal.fitnessledger.data.PrEventKind
import com.personal.fitnessledger.data.PrMetric
import com.personal.fitnessledger.data.PrType
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutSet
import kotlin.math.max

object PrCalculator {
    fun estimatedOneRepMax(weightKg: Double, reps: Int): Double {
        if (weightKg <= 0.0 || reps <= 0) return 0.0
        if (reps == 1) return weightKg
        if (reps > 12) return 0.0
        return weightKg * (1.0 + reps / 30.0)
    }

    fun evaluate(candidate: WorkoutSet, history: List<WorkoutSet>): PrEvaluation {
        if (!candidate.completed || candidate.isWarmup || candidate.weightKg <= 0.0 || candidate.reps <= 0) {
            return PrEvaluation(estimated1RmKg = 0.0, records = emptyList(), baselines = emptyList())
        }

        val validHistory = history.filter {
            it.completed && !it.isWarmup && it.weightKg > 0.0 && it.reps > 0
        }
        val candidateE1Rm = estimatedOneRepMax(candidate.weightKg, candidate.reps)
        val previousMaxWeight = validHistory.maxOfOrNull { it.weightKg } ?: 0.0
        val previousE1Rm = validHistory.maxOfOrNull { estimatedOneRepMax(it.weightKg, it.reps) } ?: 0.0
        val previousSetVolume = validHistory.maxOfOrNull { it.weightKg * it.reps } ?: 0.0
        val previousWeightAtReps = validHistory
            .filter { it.reps == candidate.reps }
            .maxOfOrNull { it.weightKg } ?: 0.0
        val previousRepsAtWeight = validHistory
            .filter { loadBucket(it.loadGrams) == loadBucket(candidate.loadGrams) }
            .maxOfOrNull { it.reps } ?: 0

        val records = buildList {
            if (validHistory.isNotEmpty() && candidate.weightKg > previousMaxWeight + 0.05) add(PrType.MAX_WEIGHT)
            if (previousWeightAtReps > 0.0 && candidate.weightKg > previousWeightAtReps + 0.05) {
                add(PrType.WEIGHT_AT_REPS)
            }
            if (previousRepsAtWeight > 0 && candidate.reps > previousRepsAtWeight) add(PrType.REP_AT_WEIGHT)
            if (candidateE1Rm > 0.0 && previousE1Rm > 0.0 && candidateE1Rm > previousE1Rm + 0.05) {
                add(PrType.ESTIMATED_1RM)
            }
            if (validHistory.isNotEmpty() && candidate.weightKg * candidate.reps > previousSetVolume + 0.05) {
                add(PrType.SET_VOLUME)
            }
        }
        val baselines = buildList {
            if (validHistory.isEmpty()) {
                add(PrType.MAX_WEIGHT)
                add(PrType.SET_VOLUME)
            }
            if (previousWeightAtReps == 0.0) add(PrType.WEIGHT_AT_REPS)
            if (previousRepsAtWeight == 0) add(PrType.REP_AT_WEIGHT)
            if (candidateE1Rm > 0.0 && previousE1Rm == 0.0) add(PrType.ESTIMATED_1RM)
        }

        return PrEvaluation(
            estimated1RmKg = max(0.0, candidateE1Rm),
            records = records,
            baselines = baselines,
        )
    }

    /**
     * Compares the best set for every independent PR bucket in the current session
     * against history that existed before the session. This guarantees that every PR
     * in a user's first session is labelled as a baseline, even when later sets are
     * better than earlier sets in the same workout.
     */
    fun evaluateSession(
        trackingType: TrackingType,
        currentSets: List<WorkoutSet>,
        historicalSets: List<WorkoutSet>,
    ): List<PrDecision> {
        val current = currentSets.flatMap { set -> metricsFor(trackingType, set).map { set to it } }
        val history = historicalSets.flatMap { metricsFor(trackingType, it) }
        val historyBest = history.groupBy { it.type to it.bucketKey }.mapValues { (_, metrics) ->
            metrics.reduce(::betterMetric)
        }
        val currentBest = current.groupBy { it.second.type to it.second.bucketKey }.mapValues { (_, candidates) ->
            candidates.reduce { left, right ->
                if (isBetter(right.second, left.second)) right else left
            }
        }

        return currentBest.values.mapNotNull { (set, metric) ->
            val previous = historyBest[metric.type to metric.bucketKey]
            when {
                previous == null -> PrDecision(set, metric, PrEventKind.BASELINE)
                isMeaningfulImprovement(metric, previous) -> PrDecision(set, metric, PrEventKind.BROKEN)
                else -> null
            }
        }.sortedWith(compareBy({ it.set.setOrder }, { it.metric.type.ordinal }, { it.metric.bucketKey }))
    }

    fun metricsFor(trackingType: TrackingType, set: WorkoutSet): List<PrMetric> {
        if (!set.completed || set.isWarmup) return emptyList()
        return when (trackingType) {
            TrackingType.WEIGHT_REPS -> weightRepMetrics(set)
            TrackingType.BODYWEIGHT_REPS -> bodyweightMetrics(set)
            TrackingType.ASSISTED_REPS -> assistedMetrics(set)
            TrackingType.DURATION -> durationMetrics(set)
        }
    }

    private fun weightRepMetrics(set: WorkoutSet): List<PrMetric> {
        if (set.loadGrams <= 0L || set.reps <= 0) return emptyList()
        val e1rm = estimatedOneRepMax(set.weightKg, set.reps)
        return buildList {
            add(PrMetric(PrType.MAX_WEIGHT, "", set.weightKg, true))
            add(PrMetric(PrType.WEIGHT_AT_REPS, "reps:${set.reps}", set.weightKg, true))
            add(PrMetric(PrType.REP_AT_WEIGHT, "load_g:${loadBucket(set.loadGrams)}", set.reps.toDouble(), true))
            if (e1rm > 0.0) add(PrMetric(PrType.ESTIMATED_1RM, "", e1rm, true))
            add(PrMetric(PrType.SET_VOLUME, "", set.weightKg * set.reps, true))
        }
    }

    private fun bodyweightMetrics(set: WorkoutSet): List<PrMetric> {
        if (set.loadGrams < 0L || set.reps <= 0) return emptyList()
        return buildList {
            add(PrMetric(PrType.MAX_REPS, "", set.reps.toDouble(), true))
            add(PrMetric(PrType.REP_AT_WEIGHT, "load_g:${loadBucket(set.loadGrams)}", set.reps.toDouble(), true))
            if (set.loadGrams > 0L) {
                add(PrMetric(PrType.MAX_WEIGHT, "", set.weightKg, true))
                add(PrMetric(PrType.WEIGHT_AT_REPS, "reps:${set.reps}", set.weightKg, true))
            }
        }
    }

    private fun assistedMetrics(set: WorkoutSet): List<PrMetric> {
        if (set.loadGrams < 0L || set.reps <= 0) return emptyList()
        return listOf(
            PrMetric(PrType.MIN_ASSISTANCE_AT_REPS, "reps:${set.reps}", set.weightKg, false),
            PrMetric(PrType.REPS_AT_ASSISTANCE, "assist_g:${loadBucket(set.loadGrams)}", set.reps.toDouble(), true),
        )
    }

    private fun durationMetrics(set: WorkoutSet): List<PrMetric> =
        if (set.durationSeconds > 0) listOf(PrMetric(PrType.MAX_DURATION, "", set.durationSeconds.toDouble(), true))
        else emptyList()

    private fun betterMetric(left: PrMetric, right: PrMetric): PrMetric = if (isBetter(left, right)) left else right

    private fun isBetter(candidate: PrMetric, baseline: PrMetric): Boolean =
        if (candidate.higherIsBetter) candidate.value > baseline.value else candidate.value < baseline.value

    private fun isMeaningfulImprovement(candidate: PrMetric, baseline: PrMetric): Boolean {
        val tolerance = when (candidate.type) {
            PrType.MAX_WEIGHT,
            PrType.WEIGHT_AT_REPS,
            PrType.ESTIMATED_1RM,
            PrType.SET_VOLUME,
            PrType.MIN_ASSISTANCE_AT_REPS -> 0.05
            else -> 0.0
        }
        return if (candidate.higherIsBetter) candidate.value > baseline.value + tolerance
        else candidate.value < baseline.value - tolerance
    }

    private fun loadBucket(loadGrams: Long): Long = ((loadGrams + 50L) / 100L) * 100L
}
