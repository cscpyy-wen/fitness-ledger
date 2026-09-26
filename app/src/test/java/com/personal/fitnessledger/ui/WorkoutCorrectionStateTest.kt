package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutHistoryDetail
import com.personal.fitnessledger.data.WorkoutHistorySet
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutEditorDraft
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.WorkoutStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkoutCorrectionStateTest {
    @Test
    fun plannerFailureStillMakesDurableCorrectionDraftImmediatelyVisible() {
        val original = historyDetail()
        val correction = WorkoutSession(
            id = 50,
            startedAtMillis = 300,
            title = "更正草稿",
            correctionOfSessionId = original.summary.session.id,
        )
        val copied = original.sets.single().set.copy(id = 51, sessionId = correction.id)
        val next = AppUiState(
            selectedWorkoutHistory = original,
            isSavingWorkout = true,
        ).withVisibleWorkoutCorrection(
            correction = correction,
            sets = listOf(copied),
            planner = null,
            original = original,
        )

        assertEquals(correction, next.activeWorkout)
        assertEquals(listOf(copied), next.activeSets)
        assertSame(original, next.workoutCorrectionOriginal)
        assertNull(next.selectedWorkoutHistory)
        assertNull(next.workoutEditorDraft)
        assertTrue(next.message.orEmpty().contains("已安全创建"))
        assertTrue(next.message.orEmpty().contains("重新对账"))
    }

    @Test
    fun durableCorrectionClearsRetryableDraftBeforeAnyRefreshResult() {
        val original = historyDetail()
        val correction = WorkoutSession(
            id = 50,
            startedAtMillis = 300,
            title = "更正草稿",
            correctionOfSessionId = original.summary.session.id,
        )
        val oldHistory = listOf(original.summary)
        val next = AppUiState(
            activeWorkout = correction,
            activeSets = listOf(original.sets.single().set.copy(sessionId = correction.id)),
            selectedWorkoutHistory = original,
            workoutCorrectionOriginal = original,
            workoutHistory = oldHistory,
            todayCompletedWorkouts = oldHistory,
            isSavingWorkout = true,
        ).afterDurableWorkoutCorrection(sessionId = original.summary.session.id, revision = 3)

        assertNull(next.activeWorkout)
        assertTrue(next.activeSets.isEmpty())
        assertNull(next.selectedWorkoutHistory)
        assertNull(next.workoutCorrectionOriginal)
        assertTrue(next.workoutHistory.isEmpty())
        assertTrue(next.todayCompletedWorkouts.isEmpty())
        assertTrue(next.lastPrEvents.isEmpty())
        assertTrue(next.prSummaries.isEmpty())
        assertTrue(next.message.orEmpty().contains("更正已保存（第 3 次）"))
    }

    @Test
    fun durableDeleteClosesStaleDetailBeforeAnyRefreshResult() {
        val original = historyDetail()
        val oldHistory = listOf(original.summary)
        val next = AppUiState(
            selectedWorkoutHistory = original,
            workoutHistory = oldHistory,
            todayCompletedWorkouts = oldHistory,
            isSavingWorkout = true,
        ).afterDurableWorkoutHistoryDelete(original.summary.session.id)

        assertNull(next.selectedWorkoutHistory)
        assertTrue(next.workoutHistory.isEmpty())
        assertTrue(next.todayCompletedWorkouts.isEmpty())
        assertTrue(next.lastPrEvents.isEmpty())
        assertTrue(next.prSummaries.isEmpty())
        assertTrue(next.message.orEmpty().contains("整场训练已删除"))
    }

    @Test
    fun addSetFailureRequeuesLatestEditorAndClearsStuckSavingState() {
        assertMutationFailureRequeuesDraft(WorkoutSession(id = 50, startedAtMillis = 1, title = "训练"))
    }

    @Test
    fun completeFailureRequeuesLatestEditorAndClearsStuckSavingState() {
        assertMutationFailureRequeuesDraft(WorkoutSession(id = 51, startedAtMillis = 1, title = "训练"))
    }

    @Test
    fun correctionCommitFailureRequeuesLatestEditorAndKeepsCorrectionRecoverable() {
        assertMutationFailureRequeuesDraft(
            WorkoutSession(id = 52, startedAtMillis = 1, title = "更正", correctionOfSessionId = 7),
        )
    }

    @Test
    fun correctionSetEditorRetainsArchivedHistoricalExerciseDefinition() {
        val active = Exercise(
            id = 2,
            name = "深蹲",
            category = "腿",
            isCustom = false,
            isPrimary = true,
            trackingType = TrackingType.WEIGHT_REPS,
        )
        val archivedDuration = Exercise(
            id = 9,
            name = "历史平板支撑",
            category = "核心",
            isCustom = true,
            isPrimary = false,
            trackingType = TrackingType.DURATION,
        )
        val original = historyDetail().let { detail ->
            detail.copy(
                sets = listOf(
                    WorkoutHistorySet(
                        set = detail.sets.single().set.copy(exerciseId = archivedDuration.id),
                        exercise = archivedDuration,
                    ),
                ),
            )
        }

        val available = correctionSetEditingExercises(listOf(active), original)

        assertEquals(listOf(active.id, archivedDuration.id), available.map { it.id })
        assertEquals(TrackingType.DURATION, available.single { it.id == archivedDuration.id }.trackingType)
    }

    private fun assertMutationFailureRequeuesDraft(session: WorkoutSession) {
        val draft = WorkoutEditorDraft(
            sessionId = session.id,
            selectedExerciseId = 1,
            weightText = "82.5",
            repsText = "5",
            durationText = "60",
            rpeText = "9",
            rirText = "1",
            noteText = "latest",
            isWarmup = false,
            isFailed = false,
            autoRest = true,
            restSeconds = 120,
            selectedSupersetId = null,
            commitId = "draft-${session.id}",
            revision = 9,
            updatedAtMillis = 999,
        )
        val recovery = AppUiState(
            activeWorkout = session,
            workoutEditorDraft = draft,
            isSavingWorkout = true,
            isAutoSavingWorkoutEditorDraft = true,
            workoutEditorDraftSaveError = "stale",
        ).prepareWorkoutEditorFailureRecovery()

        assertSame(draft, recovery.draftToPersist)
        assertTrue(!recovery.state.isSavingWorkout)
        assertTrue(recovery.state.isAutoSavingWorkoutEditorDraft)
        assertNull(recovery.state.workoutEditorDraftSaveError)
        assertSame(session, recovery.state.activeWorkout)
    }

    private fun historyDetail(): WorkoutHistoryDetail {
        val exercise = Exercise(
            id = 1,
            name = "卧推",
            category = "胸",
            isCustom = false,
            isPrimary = true,
            trackingType = TrackingType.WEIGHT_REPS,
        )
        val session = WorkoutSession(
            id = 7,
            startedAtMillis = 100,
            endedAtMillis = 200,
            title = "原场",
            status = WorkoutStatus.COMPLETED,
            recordedLocalDate = LocalDate.of(2026, 8, 30),
            recordedZoneId = "Asia/Shanghai",
        )
        val set = WorkoutSet(
            id = 11,
            sessionId = session.id,
            exerciseId = exercise.id,
            setOrder = 1,
            loadGrams = 80_000,
            reps = 5,
            commitId = "original-set",
        )
        return WorkoutHistoryDetail(
            summary = WorkoutHistorySummary(
                session = session,
                exerciseNames = listOf(exercise.name),
                completedSetCount = 1,
                totalVolumeKg = 400.0,
            ),
            sets = listOf(WorkoutHistorySet(set, exercise)),
        )
    }
}
