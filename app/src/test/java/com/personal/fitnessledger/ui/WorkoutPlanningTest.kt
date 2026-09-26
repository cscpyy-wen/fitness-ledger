package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutEditorDraft
import com.personal.fitnessledger.data.WorkoutPlanItem
import com.personal.fitnessledger.data.WorkoutTemplate
import com.personal.fitnessledger.data.afterCommittedSet
import com.personal.fitnessledger.data.freshCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlanningTest {
    @Test
    fun rawEditorMergePersistsEveryUserFieldWithoutChangingSession() {
        val original = editorDraft()
        val updated = requireNotNull(
            mergeWorkoutEditorUpdate(
                current = original,
                expectedSessionId = 41L,
                transform = {
                    it.copy(
                        selectedExerciseId = 8L,
                        weightText = "77.5",
                        repsText = "7",
                        durationText = "45",
                        rpeText = "9.5",
                        rirText = "1",
                        noteText = "Top set",
                        isWarmup = true,
                        isFailed = false,
                        autoRest = false,
                        restSeconds = 180,
                        selectedSupersetId = "B",
                        commitId = "planned-8",
                    )
                },
                updatedAtMillis = 2_000L,
            ),
        )

        assertEquals(41L, updated.sessionId)
        assertEquals(8L, updated.selectedExerciseId)
        assertEquals("77.5", updated.weightText)
        assertEquals("7", updated.repsText)
        assertEquals("45", updated.durationText)
        assertEquals("9.5", updated.rpeText)
        assertEquals("1", updated.rirText)
        assertEquals("Top set", updated.noteText)
        assertTrue(updated.isWarmup)
        assertFalse(updated.isFailed)
        assertFalse(updated.autoRest)
        assertEquals(180, updated.restSeconds)
        assertEquals("B", updated.selectedSupersetId)
        assertEquals("planned-8", updated.commitId)
        assertEquals(original.revision + 1L, updated.revision)
        assertEquals(2_000L, updated.updatedAtMillis)
    }

    @Test
    fun completedDraftGetsNewBusinessIdButKeepsNextSetLoadAndReps() {
        val original = editorDraft().copy(
            weightText = "102.5",
            repsText = "4",
            rpeText = "9",
            noteText = "done",
            isWarmup = true,
            selectedSupersetId = "A",
        )
        val next = original.afterCommittedSet(nowMillis = 3_000L)

        assertEquals("102.5", next.weightText)
        assertEquals("4", next.repsText)
        assertEquals("", next.rpeText)
        assertEquals("", next.noteText)
        assertFalse(next.isWarmup)
        assertEquals(null, next.selectedSupersetId)
        assertNotEquals(original.commitId, next.commitId)
    }

    @Test
    fun startingAnotherPlanClonesBothUiAndCommitIdentities() {
        val original = planItem()
        val cloned = original.freshCopy()

        assertNotEquals(original.id, cloned.id)
        assertNotEquals(original.plannedCommitId, cloned.plannedCommitId)
        assertEquals(original.copy(id = cloned.id, plannedCommitId = cloned.plannedCommitId), cloned)
    }

    @Test
    fun barbellTotalConvertsToExactPerSidePlateBreakdown() {
        val result = barbellLoadingBreakdown(totalWeightKg = 77.5, barWeightKg = 20.0)

        assertTrue(result.isValid)
        assertEquals(28.75, requireNotNull(result.perSideKg), 0.0001)
        assertEquals(listOf(25.0, 2.5, 1.25), result.platesPerSideKg)
        assertTrue(result.displayLabel.contains("每侧需加载 28.75 kg"))
        assertTrue(result.displayLabel.contains("25 + 2.5 + 1.25"))
    }

    @Test
    fun barbellTotalBelowConfiguredBarIsRejected() {
        val result = barbellLoadingBreakdown(totalWeightKg = 15.0, barWeightKg = 20.0)
        assertFalse(result.isValid)
        assertTrue(result.displayLabel.contains("不能小于杆重"))
    }

    @Test
    fun sharedBarbellValidationBlocksImpossibleTotalForSetFiveByFiveEditAndPlanForms() {
        val barbell = Exercise(
            1,
            "杠铃卧推",
            "胸",
            listOf("bench"),
            false,
            true,
            TrackingType.WEIGHT_REPS,
        )
        val error = barbellTotalValidationError(barbell, totalWeightKg = 15.0, barWeightKg = 20.0)

        assertTrue(error.orEmpty().contains("不能小于杆重"))
        assertEquals(null, barbellTotalValidationError(barbell, 20.0, 20.0))
        assertEquals(
            null,
            barbellTotalValidationError(
                Exercise(2, "哑铃卧推", "胸", emptyList(), false, false, TrackingType.WEIGHT_REPS),
                15.0,
                20.0,
            ),
        )
    }

    @Test
    fun onlyWeightRepBarbellMovementsUseTotalWeightWording() {
        assertTrue(
            isBarbellExercise(
                Exercise(1, "杠铃卧推", "胸", listOf("卧推", "bench"), false, true, TrackingType.WEIGHT_REPS),
            ),
        )
        assertTrue(
            isBarbellExercise(
                Exercise(2, "站姿推举", "肩", listOf("ohp"), false, true, TrackingType.WEIGHT_REPS),
            ),
        )
        assertTrue(
            isBarbellExercise(
                Exercise(3, "传统硬拉", "背", listOf("deadlift"), false, true, TrackingType.WEIGHT_REPS),
            ),
        )
        assertFalse(
            isBarbellExercise(
                Exercise(4, "上斜哑铃卧推", "胸", listOf("incline press"), false, false, TrackingType.WEIGHT_REPS),
            ),
        )
        assertFalse(
            isBarbellExercise(
                Exercise(5, "引体向上", "背", emptyList(), false, false, TrackingType.BODYWEIGHT_REPS),
            ),
        )
    }

    @Test
    fun templateSummaryNamesArchivedActionAndRequiresRestoreOrReplacement() {
        val archived = Exercise(
            id = 7L,
            name = "旧版划船",
            category = "背",
            isCustom = true,
            isPrimary = false,
            trackingType = TrackingType.WEIGHT_REPS,
            isArchived = true,
        )
        val template = WorkoutTemplate(
            id = "archived-template",
            name = "背部",
            items = listOf(planItem()),
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

        assertEquals(
            "1 个计划组 · 旧版划船 · 1 项已归档，需恢复或替换",
            templateSummaryLabel(template, listOf(archived)),
        )
    }

    private fun editorDraft() = WorkoutEditorDraft(
        sessionId = 41L,
        selectedExerciseId = 7L,
        weightText = "80",
        repsText = "8",
        durationText = "60",
        rpeText = "",
        rirText = "",
        noteText = "",
        isWarmup = false,
        isFailed = false,
        autoRest = true,
        restSeconds = 120,
        selectedSupersetId = null,
        commitId = "draft-7",
        revision = 4L,
        updatedAtMillis = 1_000L,
    )

    private fun planItem() = WorkoutPlanItem(
        id = "template-item",
        plannedCommitId = "template-commit",
        exerciseId = 7L,
        weightKg = 80.0,
        reps = 8,
        durationSeconds = 0,
        isWarmup = false,
        rpe = 8.0,
        rir = 2.0,
        note = "",
        supersetId = null,
        autoRest = true,
        restSeconds = 120,
    )
}
