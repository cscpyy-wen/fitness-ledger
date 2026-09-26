package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.AnalysisMode
import com.personal.fitnessledger.data.EvidenceTier
import com.personal.fitnessledger.data.FoodDraftItem
import com.personal.fitnessledger.data.FoodHypothesis
import com.personal.fitnessledger.data.MealDraft
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PortionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MealDraftComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun candidateStageShowsThreeChoicesInFirstViewportAndNeverAutoAcceptsMappedNutrition() {
        var resolvedCount = 0
        val candidates = (1..5).map { id ->
            FoodHypothesis(
                labelId = id,
                rawLabel = "food_$id",
                displayName = "候选食物 $id",
                modelScore = 0.9 - id * 0.05,
                canonicalKey = "food_$id",
                suggestedItem = validItem(name = "候选食物 $id"),
            )
        }
        val state = AppUiState(
            mealDraft = onDeviceDraft(items = emptyList(), hypotheses = candidates),
        )

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(393.dp).height(800.dp).testTag("meal-candidate-viewport")) {
                    MealDraftScreen(
                        state = state,
                        onUpdateItem = { _, callback -> callback(true) },
                        onRemoveItem = {},
                        onAddItem = { _, callback -> callback(true) },
                        onResolveHypothesis = { _, _, callback -> resolvedCount += 1; callback(true) },
                        onReviewedChange = {},
                        onConfirm = {},
                        onDiscard = {},
                        onRetryAnalysis = {},
                        onChangeTargetDate = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("meal-draft-step-1").assertExists()
        (1..3).forEach { composeRule.onNodeWithTag("meal-candidate-$it").assertExists() }
        val third = composeRule.onNodeWithTag("meal-candidate-3").fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("meal-candidate-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("three candidates should be visible without scrolling", third.bottom <= viewport.bottom)
        composeRule.onNodeWithTag("meal-confirm-entry").assertDoesNotExist()

        composeRule.onNodeWithTag("meal-candidate-1").performClick()
        composeRule.onNodeWithText("按候选补全食物").assertExists()
        composeRule.runOnIdle { assertEquals(0, resolvedCount) }
    }

    @Test
    fun editAndReviewAreSeparateAndConfirmationRequiresExplicitCheckbox() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                var state by remember {
                    mutableStateOf(
                        AppUiState(mealDraft = onDeviceDraft(items = listOf(validItem()), hypotheses = emptyList())),
                    )
                }
                MealDraftScreen(
                    state = state,
                    onUpdateItem = { _, callback -> callback(true) },
                    onRemoveItem = {},
                    onAddItem = { _, callback -> callback(true) },
                    onResolveHypothesis = { _, _, callback -> callback(true) },
                    onReviewedChange = { reviewed ->
                        state = state.copy(mealDraft = state.mealDraft?.copy(userReviewed = reviewed))
                    },
                    onConfirm = { confirmed = true },
                    onDiscard = {},
                    onRetryAnalysis = {},
                    onChangeTargetDate = {},
                )
            }
        }

        composeRule.onNodeWithTag("meal-draft-step-2").assertExists()
        composeRule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("meal-next-review"))
        composeRule.onNodeWithTag("meal-next-review").performClick()
        composeRule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("meal-draft-step-3"))
        composeRule.onNodeWithTag("meal-draft-step-3").assertExists()
        composeRule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("meal-review-section"))
        composeRule.onNodeWithTag("meal-confirm-entry").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("我已核对食材", substring = true).performClick()
        composeRule.onNodeWithTag("meal-confirm-entry").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun progressLabelsDoNotClipAt320DpAndLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme { Box(Modifier.width(320.dp)) { MealDraftProgress(currentStep = 2) } }
            }
        }

        (1..3).forEach { step ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag("meal-draft-step-label-$step", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
            assertEquals(1, results.size)
            assertFalse("step $step must not clip", results.single().hasVisualOverflow)
        }
    }

    @Test
    fun commitReceiptConsumesQueuedTapBeforeReturningToFood() {
        var continued = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    MealCommitReceiptScreen(
                        receipt = MealCommitReceipt("meal-1", "已确认并计入 2026-09-01"),
                        onContinue = { continued += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("meal-commit-receipt").assertExists()
        val continueButton = composeRule.onNodeWithTag("meal-commit-continue")
        continueButton.assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, continued) }

        composeRule.mainClock.advanceTimeBy(901L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        continueButton.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, continued) }
    }

    private fun onDeviceDraft(
        items: List<FoodDraftItem>,
        hypotheses: List<FoodHypothesis>,
    ) = MealDraft(
        photoUri = "",
        items = items,
        evidenceTier = EvidenceTier.C,
        evidenceReason = "本机候选需要用户核对",
        unresolvedFlags = emptySet(),
        providerLabel = "Google AIY Food V1 · 本机",
        analysisMode = AnalysisMode.ON_DEVICE_AI,
        hypotheses = hypotheses,
    )

    private fun validItem(name: String = "白米饭") = FoodDraftItem(
        name = name,
        grams = 150.0,
        gramsMin = 120.0,
        gramsMax = 180.0,
        per100g = Nutrition(kcal = 130.0, carbsG = 28.0, proteinG = 2.7, fatG = 0.3),
        sourceName = "USDA FNDDS",
        portionBasis = PortionBasis.USER_ESTIMATE,
        evidenceTier = EvidenceTier.C,
        userModified = true,
    )
}
