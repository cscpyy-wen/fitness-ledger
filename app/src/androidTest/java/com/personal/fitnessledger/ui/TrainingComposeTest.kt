package com.personal.fitnessledger.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutSetInput
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.WorkoutHistoryDetail
import com.personal.fitnessledger.data.WorkoutHistorySet
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrainingComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun labeledSwitchRow_hasOneActionAndExposesRoleCheckedStateAndLabel() {
        var callbackCount = 0

        composeRule.setContent {
            MaterialTheme {
                var checked by remember { mutableStateOf(true) }
                LabeledToggleRow(
                    label = "自动休息",
                    supportingText = "记录后开始计时",
                    checked = checked,
                    onCheckedChange = {
                        callbackCount += 1
                        checked = it
                    },
                    visual = ToggleVisual.SWITCH,
                    testTag = "switch-row",
                )
            }
        }

        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("switch-row")
            .assertHasClickAction()
            .assertIsOn()
            .assertTextContains("自动休息")
            .assertTextContains("记录后开始计时")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()
            .assertIsOff()

        composeRule.runOnIdle { assertEquals(1, callbackCount) }
    }

    @Test
    fun labeledCheckboxRow_hasOneActionAndExposesRoleCheckedStateAndLabel() {
        var callbackValue: Boolean? = null

        composeRule.setContent {
            MaterialTheme {
                var checked by remember { mutableStateOf(false) }
                LabeledToggleRow(
                    label = "热身组（不计 PR）",
                    checked = checked,
                    onCheckedChange = {
                        callbackValue = it
                        checked = it
                    },
                    visual = ToggleVisual.CHECKBOX,
                    testTag = "checkbox-row",
                )
            }
        }

        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("checkbox-row")
            .assertHasClickAction()
            .assertIsOff()
            .assertTextContains("热身组（不计 PR）")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
            .assertIsOn()

        composeRule.runOnIdle { assertEquals(true, callbackValue) }
    }

    @Test
    fun activeWorkoutForm_restoresBenchNinetyThreeByFourAndCommitsRestoredIntent() {
        val restorationTester = StateRestorationTester(composeRule)
        val state = AppUiState(
            exercises = listOf(
                exercise(id = 1, name = "深蹲", isPrimary = true),
                exercise(id = 2, name = "卧推"),
            ),
            activeWorkout = WorkoutSession(
                id = 42,
                startedAtMillis = System.currentTimeMillis(),
                title = "状态恢复测试",
                restDurationSeconds = 120,
            ),
        )
        var committedExercise: Exercise? = null
        var committedInput: WorkoutSetInput? = null

        restorationTester.setContent {
            MaterialTheme {
                TrainingScreen(
                    state = state,
                    onStartWorkout = {},
                    onAddSet = { exercise, input ->
                        committedExercise = exercise
                        committedInput = input
                    },
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

        composeRule.onNodeWithTag("workout-change-exercise").performScrollTo().performClick()
        composeRule.onNodeWithTag("workout-exercise-2").performScrollTo().performClick()
        replaceText("workout-weight-input", "93")
        replaceText("workout-reps-input", "4")
        composeRule.onNodeWithTag("workout-more-options").performScrollTo().performClick()
        replaceText("workout-rpe-input", "8.5")
        replaceText("workout-rir-input", "1")
        replaceText("workout-note-input", "慢下快上，停顿一秒")
        composeRule.onNodeWithTag("workout-warmup-toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("workout-auto-rest-toggle").performScrollTo().performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        assertFieldText("workout-weight-input", "93")
        assertFieldText("workout-reps-input", "4")
        assertFieldText("workout-rpe-input", "8.5")
        assertFieldText("workout-rir-input", "1")
        assertFieldText("workout-note-input", "慢下快上，停顿一秒")
        composeRule.onNodeWithTag("workout-warmup-toggle").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("workout-failed-toggle").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("workout-auto-rest-toggle").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("workout-add-set").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("卧推", committedExercise?.name)
            val input = requireNotNull(committedInput)
            assertEquals(93.0, input.weightKg, 0.0)
            assertEquals(4, input.reps)
            assertEquals(8.5, input.rpe ?: Double.NaN, 0.0)
            assertEquals(1.0, input.rir ?: Double.NaN, 0.0)
            assertEquals("慢下快上，停顿一秒", input.note)
            assertTrue(input.completed)
            assertTrue(input.isWarmup)
            assertFalse(input.commitId.isBlank())
            assertTrue(input.commitId.length <= 128)
            assertNull(input.restSecondsAfter)
        }
    }

    @Test
    fun creatingCustomExerciseInsideWorkoutSelectsItBeforeFirstSetCanBeRecorded() {
        val original = exercise(id = 1, name = "传统硬拉", isPrimary = true)
        val created = Exercise(
            id = 99,
            name = "AuditLift",
            category = "背",
            isCustom = true,
            isPrimary = false,
            trackingType = TrackingType.WEIGHT_REPS,
        )
        var recordedExercise: Exercise? = null

        composeRule.setContent {
            MaterialTheme {
                var state by remember {
                    mutableStateOf(
                        AppUiState(
                            exercises = listOf(original),
                            exerciseCatalog = listOf(original),
                            activeWorkout = WorkoutSession(
                                id = 88,
                                startedAtMillis = System.currentTimeMillis(),
                                title = "创建后选中测试",
                                restDurationSeconds = 120,
                            ),
                        ),
                    )
                }
                TrainingScreen(
                    state = state,
                    onStartWorkout = {},
                    onAddSet = { exercise, _ -> recordedExercise = exercise },
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
                    onAddCustomExercise = { _, _, _, _, callback ->
                        state = state.copy(
                            exercises = state.exercises + created,
                            exerciseCatalog = state.exerciseCatalog + created,
                        )
                        callback(created)
                    },
                    onTogglePrimary = {},
                    onOpenWorkoutHistory = {},
                    onLoadMoreWorkoutHistory = {},
                    onCloseWorkoutHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag("workout-change-exercise").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("自定义动作").performScrollTo().performClick()
        composeRule.onNodeWithTag("custom-exercise-name").performTextReplacement("AuditLift")
        composeRule.onNodeWithTag("custom-exercise-category").performTextReplacement("背")
        composeRule.onNodeWithTag("custom-exercise-create").performClick()

        composeRule.onNodeWithTag("workout-exercise-99").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("workout-add-set").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(99L, recordedExercise?.id) }
    }

    @Test
    fun customExerciseManagerExplainsAndUsesSafeArchiveInsteadOfDeletion() {
        val custom = Exercise(
            id = 77,
            name = "拼写待修正动作",
            category = "背",
            isCustom = true,
            isPrimary = true,
            trackingType = TrackingType.WEIGHT_REPS,
        )
        var archivedId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                TrainingScreen(
                    state = AppUiState(exercises = listOf(custom), exerciseCatalog = listOf(custom)),
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
                    onSetCustomExerciseArchived = { exercise, archived, callback ->
                        if (archived) archivedId = exercise.id
                        callback(true)
                    },
                    onOpenWorkoutHistory = {},
                    onLoadMoreWorkoutHistory = {},
                    onCloseWorkoutHistory = {},
                )
            }
        }

        composeRule.onNodeWithText("管理").performScrollTo().performClick()
        composeRule.onNodeWithTag("custom-exercise-archive-77").performScrollTo().performClick()
        composeRule.onNodeWithText("归档“拼写待修正动作”？").assertExists()
        composeRule.onNodeWithText("确认归档").performClick()
        composeRule.runOnIdle { assertEquals(77L, archivedId) }
    }

    @Test
    fun templateActionsStackWithoutTextOverflowAt320DpAndLargeFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TemplateActionButtons(
                            templateId = "large-font",
                            enabled = true,
                            onEdit = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("template-actions-stacked-large-font").assertExists()
        composeRule.onNodeWithTag("template-actions-row-large-font").assertDoesNotExist()
        assertNoVisualOverflow(
            "template-edit-label-large-font",
            "template-delete-label-large-font",
        )
        val edit = composeRule.onNodeWithTag("template-edit-large-font").fetchSemanticsNode().boundsInRoot
        val delete = composeRule.onNodeWithTag("template-delete-large-font").fetchSemanticsNode().boundsInRoot
        assertTrue(delete.top >= edit.bottom)
        assertTrue(edit.height >= 48f)
        assertTrue(delete.height >= 48f)
    }

    @Test
    fun activeWorkout_fastFormKeepsPrimaryInputsVisibleAndAdvancedFieldsCollapsedAt320DpLargeText() {
        val squat = exercise(id = 1, name = "深蹲", isPrimary = true)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        TrainingScreen(
                            state = AppUiState(
                                exercises = listOf(squat),
                                exerciseCatalog = listOf(squat),
                                activeWorkout = WorkoutSession(id = 101, startedAtMillis = System.currentTimeMillis(), title = "快速记录"),
                            ),
                            onStartWorkout = {}, onAddSet = { _, _ -> }, onAcknowledgeSingleSet = {},
                            onAcknowledgeFiveByFive = {}, onStartRestTimer = {}, onClearRestTimer = {},
                            onAddFiveByFive = { _, _, _ -> }, onUpdateSet = {}, onDeleteSet = {},
                            onDeleteSetBatch = {}, onCancelWorkout = {}, onCompleteWorkout = {},
                            onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                            onTogglePrimary = {}, onOpenWorkoutHistory = {}, onLoadMoreWorkoutHistory = {},
                            onCloseWorkoutHistory = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("workout-weight-input").assertExists()
        composeRule.onNodeWithTag("workout-reps-input").assertExists()
        composeRule.onNodeWithTag("workout-add-set").assertExists().assertIsEnabled()
        composeRule.onNodeWithTag("workout-more-options").assertExists()
        composeRule.onNodeWithTag("workout-rpe-input").assertDoesNotExist()
        val button = composeRule.onNodeWithTag("workout-add-set").fetchSemanticsNode().boundsInRoot
        assertTrue("primary set button must be visible without scrolling", button.bottom <= 640f)
        assertTrue("primary set button must keep a 48dp target", button.height >= 48f)
    }

    @Test
    fun activeWorkout_repeatUsesCurrentExercisesLastCompletedSetWhileUndoUsesLatestWrite() {
        val squat = exercise(id = 1, name = "深蹲", isPrimary = true)
        val bench = exercise(id = 2, name = "卧推")
        val last = WorkoutSet(
            id = 12,
            sessionId = 202,
            exerciseId = bench.id,
            setOrder = 2,
            loadGrams = 92_500,
            reps = 5,
            rpe = 8.5,
            note = "停顿",
            commitId = "original-last-set",
        )
        val previousSquat = last.copy(id = 10, exerciseId = squat.id, loadGrams = 70_000, reps = 6)
        val failedSquat = previousSquat.copy(id = 13, loadGrams = 75_000, completed = false)
        var repeatedExercise: Exercise? = null
        var repeatedInput: WorkoutSetInput? = null
        var deletedSetId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                TrainingScreen(
                    state = AppUiState(
                        exercises = listOf(squat, bench),
                        exerciseCatalog = listOf(squat, bench),
                        activeWorkout = WorkoutSession(id = 202, startedAtMillis = System.currentTimeMillis(), title = "快速操作"),
                        activeSets = listOf(last, failedSquat, previousSquat),
                    ),
                    onStartWorkout = {},
                    onAddSet = { exercise, input -> repeatedExercise = exercise; repeatedInput = input },
                    onAcknowledgeSingleSet = {}, onAcknowledgeFiveByFive = {}, onStartRestTimer = {},
                    onClearRestTimer = {}, onAddFiveByFive = { _, _, _ -> }, onUpdateSet = {},
                    onDeleteSet = { deletedSetId = it }, onDeleteSetBatch = {}, onCancelWorkout = {},
                    onCompleteWorkout = {}, onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                    onTogglePrimary = {}, onOpenWorkoutHistory = {}, onLoadMoreWorkoutHistory = {},
                    onCloseWorkoutHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag("workout-undo-last-set").performScrollTo().performClick()
        composeRule.onNodeWithTag("workout-repeat-last-set").performScrollTo()
            .assertTextContains("重复 深蹲 · 70 kg × 6").performClick()
        composeRule.runOnIdle {
            assertEquals(failedSquat.id, deletedSetId)
            assertEquals(squat.id, repeatedExercise?.id)
            val input = requireNotNull(repeatedInput)
            assertEquals(70.0, input.weightKg, 0.0)
            assertEquals(6, input.reps)
            assertTrue(input.completed)
            assertEquals(8.5, input.rpe ?: Double.NaN, 0.0)
            assertEquals("停顿", input.note)
            assertTrue(input.commitId != last.commitId)
        }
    }

    @Test
    fun activeWorkout_repeatIsHiddenForNewExerciseAndShowsDurationWhenReturningToRecordedExercise() {
        val bench = exercise(id = 1, name = "卧推", isPrimary = true)
        val plank = exercise(id = 2, name = "平板支撑").copy(trackingType = TrackingType.DURATION)
        val last = WorkoutSet(
            id = 20, sessionId = 203, exerciseId = plank.id, setOrder = 1,
            loadGrams = 0, reps = 0, durationSeconds = 75,
        )
        var repeatedExercise: Exercise? = null
        var repeatedInput: WorkoutSetInput? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TrainingScreen(
                            state = AppUiState(
                                exercises = listOf(bench, plank),
                                exerciseCatalog = listOf(bench, plank),
                                activeWorkout = WorkoutSession(id = 203, startedAtMillis = System.currentTimeMillis(), title = "切换动作"),
                                activeSets = listOf(last),
                            ),
                            onStartWorkout = {},
                            onAddSet = { exercise, input -> repeatedExercise = exercise; repeatedInput = input },
                            onAcknowledgeSingleSet = {}, onAcknowledgeFiveByFive = {}, onStartRestTimer = {},
                            onClearRestTimer = {}, onAddFiveByFive = { _, _, _ -> }, onUpdateSet = {},
                            onDeleteSet = {}, onDeleteSetBatch = {}, onCancelWorkout = {},
                            onCompleteWorkout = {}, onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                            onTogglePrimary = {}, onOpenWorkoutHistory = {}, onLoadMoreWorkoutHistory = {},
                            onCloseWorkoutHistory = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("workout-repeat-last-set").assertDoesNotExist()
        composeRule.onNodeWithTag("workout-undo-last-set").performScrollTo().assertExists()
        composeRule.onNodeWithTag("workout-change-exercise").performScrollTo().performClick()
        composeRule.onNodeWithTag("workout-exercise-2").performScrollTo().performClick()
        composeRule.onNodeWithTag("workout-repeat-last-set").performScrollTo()
            .assertTextContains("重复 平板支撑 · 1 分 15 秒")
        assertNoVisualOverflow("workout-repeat-last-set-label")
        composeRule.onNodeWithTag("workout-repeat-last-set").performClick()
        composeRule.runOnIdle {
            assertEquals(plank.id, repeatedExercise?.id)
            assertEquals(75, repeatedInput?.durationSeconds)
            assertEquals(0, repeatedInput?.reps)
            assertEquals(0.0, requireNotNull(repeatedInput).weightKg, 0.0)
            assertTrue(repeatedInput?.commitId != last.commitId)
        }
    }

    @Test
    fun activeWorkout_quickUndoLocksImmediatelyAndAllowsOneDeliberateLaterUndo() {
        val bench = exercise(id = 2, name = "卧推")
        val last = WorkoutSet(
            id = 12,
            sessionId = 202,
            exerciseId = bench.id,
            setOrder = 2,
            loadGrams = 92_500,
            reps = 5,
            commitId = "undo-gate-set",
        )
        var undoCount = 0

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                TrainingScreen(
                    state = AppUiState(
                        exercises = listOf(bench),
                        exerciseCatalog = listOf(bench),
                        activeWorkout = WorkoutSession(id = 202, startedAtMillis = System.currentTimeMillis(), title = "撤销门闩"),
                        activeSets = listOf(last),
                    ),
                    onStartWorkout = {}, onAddSet = { _, _ -> }, onAcknowledgeSingleSet = {},
                    onAcknowledgeFiveByFive = {}, onStartRestTimer = {}, onClearRestTimer = {},
                    onAddFiveByFive = { _, _, _ -> }, onUpdateSet = {}, onDeleteSet = { undoCount += 1 },
                    onDeleteSetBatch = {}, onCancelWorkout = {}, onCompleteWorkout = {},
                    onAddCustomExercise = { _, _, _, _, callback -> callback(null) },
                    onTogglePrimary = {}, onOpenWorkoutHistory = {}, onLoadMoreWorkoutHistory = {},
                    onCloseWorkoutHistory = {},
                )
            }
        }

        val undo = composeRule.onNodeWithTag("workout-undo-last-set").performScrollTo()
        undo.performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        undo.assertIsNotEnabled().assertTextContains("已发送一次撤销")
        composeRule.onNodeWithTag("workout-undo-guard-receipt").assertExists()
        composeRule.runOnIdle { assertEquals(1, undoCount) }

        composeRule.mainClock.advanceTimeBy(1_201L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("workout-undo-guard-receipt").assertDoesNotExist()
        undo.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(2, undoCount) }
    }

    @Test
    fun workoutHistoryBackStaysVisibleAtEndOfLongListWithLargeText() {
        val detail = longWorkoutHistory()
        var backCount = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(640.dp).testTag("workout-history-viewport")) {
                        WorkoutHistoryHarness(detail, onBack = { backCount++ })
                    }
                }
            }
        }

        val initialBackBounds = composeRule.onNodeWithTag("workout-history-back")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("workout-history-detail-list")
            .performScrollToNode(hasText(detail.sets.last().set.note))
        composeRule.onNodeWithText(detail.sets.last().set.note).assertIsDisplayed()
        assertNoVisualOverflow("workout-history-title")
        val back = composeRule.onNodeWithTag("workout-history-back").assertIsDisplayed().assertIsEnabled()
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("workout-history-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must not move the return control", initialBackBounds, backBounds)
        assertTrue("Back must remain inside the viewport", backBounds.top >= viewport.top && backBounds.bottom <= viewport.bottom)
        assertTrue("Back keeps a 48dp target", backBounds.width >= 48f && backBounds.height >= 48f)
        back.performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    @Test
    fun workoutHistorySystemBackStillReturnsWhileMutationIsDisabled() {
        val detail = longWorkoutHistory()
        var backCount = 0
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    WorkoutHistoryHarness(detail, isSaving = true, onBack = { backCount++ })
                }
            }
        }

        composeRule.onNodeWithTag("workout-history-correct").assertIsNotEnabled()
        composeRule.onNodeWithTag("workout-history-detail-list")
            .performScrollToNode(hasText(detail.sets.last().set.note))
        composeRule.onNodeWithText(detail.sets.last().set.note).assertIsDisplayed()
        composeRule.onNodeWithTag("workout-history-back").assertIsDisplayed().assertIsEnabled()
        composeRule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    @Composable
    private fun WorkoutHistoryHarness(detail: WorkoutHistoryDetail, isSaving: Boolean = false, onBack: () -> Unit) {
        TrainingScreen(
            state = AppUiState(selectedWorkoutHistory = detail, isSavingWorkout = isSaving),
            onStartWorkout = { error("Viewing history must not start a workout") },
            onAddSet = { _, _ -> error("Viewing history must not add a set") },
            onAcknowledgeSingleSet = {}, onAcknowledgeFiveByFive = {},
            onStartRestTimer = {}, onClearRestTimer = {}, onAddFiveByFive = { _, _, _ -> },
            onUpdateSet = {}, onDeleteSet = {}, onDeleteSetBatch = {},
            onCancelWorkout = {}, onCompleteWorkout = {},
            onAddCustomExercise = { _, _, _, _, _ -> error("Viewing history must not create an exercise") },
            onTogglePrimary = {}, onOpenWorkoutHistory = {}, onLoadMoreWorkoutHistory = {},
            onCloseWorkoutHistory = onBack,
            onStartWorkoutCorrection = { error("Returning must not correct history") },
            onDeleteWorkoutHistory = { error("Returning must not delete history") },
            onSaveHistoryAsTemplate = { _, _ -> error("Returning must not create a template") },
        )
    }

    private fun longWorkoutHistory(): WorkoutHistoryDetail {
        val exercise = exercise(1L, "杠铃卧推与较长中文名称的动作记录")
        val session = WorkoutSession(
            id = 7L, startedAtMillis = 1_000L, endedAtMillis = 3_601_000L,
            title = "长列表训练", status = WorkoutStatus.COMPLETED,
            recordedLocalDate = LocalDate.of(2026, 9, 26), recordedZoneId = "Asia/Shanghai",
        )
        val sets = (1..24).map { order ->
            WorkoutHistorySet(
                set = WorkoutSet(
                    id = order.toLong(), sessionId = session.id, exerciseId = exercise.id,
                    setOrder = order, loadGrams = 80_000L, reps = 5,
                    note = "第 $order 组备注：保留完整训练记录并核对本组动作和次数",
                ),
                exercise = exercise,
            )
        }
        return WorkoutHistoryDetail(
            summary = WorkoutHistorySummary(session, listOf(exercise.name), sets.size, sets.size * 400.0),
            sets = sets,
        )
    }

    private fun replaceText(tag: String, value: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun assertFieldText(tag: String, value: String) {
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun assertNoVisualOverflow(vararg tags: String) {
        val failures = tags.mapNotNull { tag ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
            assertEquals("expected one text layout result for $tag", 1, results.size)
            results.single().takeIf(TextLayoutResult::hasVisualOverflow)?.let { result ->
                val ellipsizedLines = (0 until result.lineCount).filter(result::isLineEllipsized)
                "$tag: size=${result.size.width}x${result.size.height}, " +
                    "paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
                    "lines=${result.lineCount}, didOverflowWidth=${result.didOverflowWidth}, " +
                    "didOverflowHeight=${result.didOverflowHeight}, ellipsizedLines=$ellipsizedLines"
            }
        }
        assertTrue("labels must not clip or ellipsize: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    private fun exercise(
        id: Long,
        name: String,
        isPrimary: Boolean = false,
    ) = Exercise(
        id = id,
        name = name,
        category = "测试",
        isCustom = false,
        isPrimary = isPrimary,
        trackingType = TrackingType.WEIGHT_REPS,
    )
}
