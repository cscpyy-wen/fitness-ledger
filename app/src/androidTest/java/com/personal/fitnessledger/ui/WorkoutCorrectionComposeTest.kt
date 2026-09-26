package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.PersonalRecord
import com.personal.fitnessledger.data.PrEventKind
import com.personal.fitnessledger.data.PrType
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutHistoryDetail
import com.personal.fitnessledger.data.WorkoutHistorySet
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.WorkoutStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class WorkoutCorrectionComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedDetailRequiresSecondConfirmationAndAnnouncesSetAndPrImpact() {
        val detail = detail(status = WorkoutStatus.COMPLETED, correctionRevision = 2)
        var correctionCalls = 0
        var deleteCalls = 0
        setTrainingContent(
            AppUiState(selectedWorkoutHistory = detail),
            onStartCorrection = { correctionCalls += 1 },
            onDeleteHistory = { deleteCalls += 1 },
        )

        composeRule.onNodeWithText("已更正 2 次", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("更正本场训练").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText("将复制 1 组", substring = true).assertTextContains("2 条 PR", substring = true)
        composeRule.onNodeWithText("保持原记录").performClick()
        composeRule.runOnIdle { assertEquals(0, correctionCalls) }

        composeRule.onNodeWithContentDescription("更正本场训练").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("确认更正本场训练").performClick()
        composeRule.runOnIdle { assertEquals(1, correctionCalls) }

        composeRule.onNodeWithContentDescription("删除本场训练历史").performScrollTo().performClick()
        composeRule.onNodeWithText("将删除整场、1 个组和 2 条关联 PR", substring = true).assertExists()
        composeRule.onNodeWithText("取消删除").performClick()
        composeRule.runOnIdle { assertEquals(0, deleteCalls) }
    }

    @Test
    fun cancelledDetailOnlyOffersDeletion() {
        setTrainingContent(AppUiState(selectedWorkoutHistory = detail(status = WorkoutStatus.CANCELLED)))
        composeRule.onNodeWithContentDescription("更正本场训练").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("删除本场训练历史").performScrollTo().assertIsEnabled()
    }

    @Test
    fun activeWorkoutDefensivelyDisablesHistoryCorrectionAndDeletion() {
        setTrainingContent(
            AppUiState(
                selectedWorkoutHistory = detail(status = WorkoutStatus.COMPLETED),
                activeWorkout = WorkoutSession(id = 99, startedAtMillis = 1, title = "其他训练"),
            ),
        )
        composeRule.onNodeWithContentDescription("更正本场训练").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("请先完成或取消进行中的训练", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("删除本场训练历史").assertIsNotEnabled()
    }

    @Test
    fun correctionEditorUsesDistinctBannerSaveAndAbandonLanguage() {
        val original = detail(status = WorkoutStatus.COMPLETED)
        val correction = WorkoutSession(
            id = 50,
            startedAtMillis = 10,
            title = "更正草稿",
            correctionOfSessionId = original.summary.session.id,
        )
        var completeCalls = 0
        var cancelCalls = 0
        setTrainingContent(
            AppUiState(
                exercises = listOf(exercise(1, "卧推"), exercise(2, "深蹲")),
                activeWorkout = correction,
                activeSets = listOf(workoutSet(id = 51, sessionId = 50, exerciseId = 1)),
                workoutCorrectionOriginal = original,
            ),
            onComplete = { completeCalls += 1 },
            onCancel = { cancelCalls += 1 },
        )

        composeRule.onNodeWithTag("workout-correction-banner")
            .assertContentDescriptionContains("原记录在保存更正前保持不变", substring = true)
        composeRule.onNodeWithTag("active-workout-list")
            .performScrollToNode(hasTestTag("workout-correction-save"))
        composeRule.onNodeWithTag("workout-correction-save").performClick()
        composeRule.onNodeWithText("任何失败都会整体回滚", substring = true).assertExists()
        composeRule.onNodeWithText("确认保存更正").performClick()
        composeRule.runOnIdle { assertEquals(1, completeCalls) }

        composeRule.onNodeWithTag("active-workout-list")
            .performScrollToNode(hasContentDescription("放弃本场更正"))
        composeRule.onNodeWithContentDescription("放弃本场更正").performClick()
        composeRule.onNodeWithText("原完成记录、组和 PR 均保持不变", substring = true).assertExists()
        composeRule.onNodeWithText("确认放弃更正").performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }
    }

    @Test
    fun correctionSetEditorCanChangeWrongExerciseBeforeSaving() {
        val bench = exercise(1, "卧推")
        val squat = exercise(2, "深蹲")
        val set = workoutSet(id = 51, sessionId = 50, exerciseId = bench.id)
        var updated: WorkoutSet? = null
        setTrainingContent(
            AppUiState(
                exercises = listOf(bench, squat),
                activeWorkout = WorkoutSession(
                    id = 50,
                    startedAtMillis = 10,
                    title = "动作更正",
                    correctionOfSessionId = 7,
                ),
                activeSets = listOf(set),
            ),
            onUpdateSet = { updated = it },
        )

        composeRule.onNodeWithTag("active-workout-list")
            .performScrollToNode(hasContentDescription("修改该组"))
        composeRule.onNodeWithContentDescription("修改该组").performClick()
        composeRule.onNodeWithContentDescription("把该组动作改为深蹲").performClick()
        composeRule.onNodeWithText("保存").performClick()
        composeRule.runOnIdle {
            assertEquals(squat.id, updated?.exerciseId)
            assertTrue(updated?.commitId?.isNotBlank() == true)
        }
    }

    @Test
    fun narrowLargeTextCorrectionKeepsSetActionsAndCompletionReachable() {
        val bench = exercise(1, "很长名称的杠铃卧推动作")
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TrainingScreen(
                            state = AppUiState(
                                exercises = listOf(bench),
                                activeWorkout = WorkoutSession(
                                    id = 50,
                                    startedAtMillis = 10,
                                    title = "窄屏更正",
                                    correctionOfSessionId = 7,
                                ),
                                activeSets = listOf(
                                    workoutSet(51, 50, bench.id).copy(
                                        note = "用于验证 320dp 与放大字体下仍可换行显示的较长备注",
                                    ),
                                ),
                            ),
                            onStartWorkout = {},
                            onAddSet = { _, _ -> },
                            onAcknowledgeSingleSet = {},
                            onAcknowledgeFiveByFive = {},
                            onStartRestTimer = {},
                            onClearRestTimer = {},
                            onAddFiveByFive = { _, _, _ -> },
                            onUpdateSet = {},
                            onDeleteSet = {},
                            onDeleteSetBatch = {},
                            onCancelWorkout = {},
                            onCompleteWorkout = {},
                            onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                            onTogglePrimary = {},
                            onOpenWorkoutHistory = {},
                            onLoadMoreWorkoutHistory = {},
                            onCloseWorkoutHistory = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("active-workout-list")
            .performScrollToNode(hasContentDescription("修改该组"))
        composeRule.onNodeWithContentDescription("修改该组").assertExists()
        composeRule.onNodeWithContentDescription("删除该组").assertExists()
        composeRule.onNodeWithTag("workout-correction-save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun barbellTotalBelowBarDisablesSingleSetAndFiveByFiveSubmission() {
        val barbell = exercise(1, "杠铃卧推")
        setTrainingContent(
            AppUiState(
                exercises = listOf(barbell),
                activeWorkout = WorkoutSession(id = 50, startedAtMillis = 10, title = "训练"),
                barbellBarWeightKg = 20.0,
            ),
        )

        composeRule.onNodeWithTag("workout-weight-input").performScrollTo().performTextReplacement("15")
        composeRule.onNodeWithTag("workout-add-set").assertIsNotEnabled()
        composeRule.onNodeWithTag("workout-more-options").performScrollTo().performClick()
        composeRule.onNodeWithText("总重量不能小于杆重 20 kg").performScrollTo().assertExists()
        composeRule.onNodeWithTag("active-workout-list")
            .performScrollToNode(hasTestTag("workout-five-by-five-submit"))
        composeRule.onNodeWithTag("workout-five-by-five-submit").assertIsNotEnabled()
    }

    private fun setTrainingContent(
        state: AppUiState,
        onStartCorrection: (Long) -> Unit = {},
        onDeleteHistory: (Long) -> Unit = {},
        onComplete: () -> Unit = {},
        onCancel: () -> Unit = {},
        onUpdateSet: (WorkoutSet) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                TrainingScreen(
                    state = state,
                    onStartWorkout = {},
                    onAddSet = { _, _ -> },
                    onAcknowledgeSingleSet = {},
                    onAcknowledgeFiveByFive = {},
                    onStartRestTimer = {},
                    onClearRestTimer = {},
                    onAddFiveByFive = { _, _, _ -> },
                    onUpdateSet = onUpdateSet,
                    onDeleteSet = {},
                    onDeleteSetBatch = {},
                    onCancelWorkout = onCancel,
                    onCompleteWorkout = onComplete,
                    onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                    onTogglePrimary = {},
                    onOpenWorkoutHistory = {},
                    onLoadMoreWorkoutHistory = {},
                    onCloseWorkoutHistory = {},
                    onStartWorkoutCorrection = onStartCorrection,
                    onDeleteWorkoutHistory = onDeleteHistory,
                )
            }
        }
    }

    private fun detail(
        status: WorkoutStatus,
        correctionRevision: Int = 0,
    ): WorkoutHistoryDetail {
        val exercise = exercise(1, "卧推")
        val set = workoutSet(id = 11, sessionId = 7, exerciseId = exercise.id)
        val session = WorkoutSession(
            id = 7,
            startedAtMillis = 100,
            endedAtMillis = 200,
            title = "历史场次",
            status = status,
            recordedLocalDate = LocalDate.of(2026, 8, 30),
            recordedZoneId = "Asia/Shanghai",
            correctionRevision = correctionRevision,
            correctedAtMillis = if (correctionRevision > 0) 300 else null,
        )
        return WorkoutHistoryDetail(
            summary = WorkoutHistorySummary(
                session = session,
                exerciseNames = listOf(exercise.name),
                completedSetCount = 1,
                totalVolumeKg = 400.0,
            ),
            sets = listOf(WorkoutHistorySet(set, exercise)),
            personalRecords = if (status == WorkoutStatus.COMPLETED) listOf(
                personalRecord(21, exercise.id, set.id, PrType.MAX_WEIGHT),
                personalRecord(22, exercise.id, set.id, PrType.ESTIMATED_1RM),
            ) else emptyList(),
        )
    }

    private fun personalRecord(id: Long, exerciseId: Long, setId: Long, type: PrType) = PersonalRecord(
        id = id,
        exerciseId = exerciseId,
        setId = setId,
        type = type,
        value = 80.0,
        weightKg = 80.0,
        reps = 5,
        achievedAtMillis = 200,
        eventKind = PrEventKind.BASELINE,
    )

    private fun exercise(id: Long, name: String) = Exercise(
        id = id,
        name = name,
        category = "测试",
        isCustom = false,
        isPrimary = true,
        trackingType = TrackingType.WEIGHT_REPS,
    )

    private fun workoutSet(id: Long, sessionId: Long, exerciseId: Long) = WorkoutSet(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        setOrder = 1,
        loadGrams = 80_000,
        reps = 5,
        completed = true,
        commitId = "set-$id",
    )
}
