package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TodayComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quickActionsStackAndKeepFullLabelsAt320DpWithLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TodayScreen(
                            state = AppUiState(profileConfigured = true),
                            onOpenFood = {},
                            onOpenTraining = {},
                            onOpenBody = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("today-quick-actions-stacked").performScrollTo().assertExists()
        composeRule.onNodeWithTag("today-quick-actions-row").assertDoesNotExist()
        composeRule.onNodeWithText("拍照记饮食").assertExists()
        composeRule.onNodeWithText("记录训练").assertExists()
        composeRule.onNodeWithText("记录身体").assertExists()
        assertNoVisualOverflow("today-quick-action-photo-label")
        assertNoVisualOverflow("today-quick-action-training-label")
        assertNoVisualOverflow("today-quick-action-body-label")

        val photo = composeRule.onNodeWithTag("today-quick-action-photo").fetchSemanticsNode().boundsInRoot
        val training = composeRule.onNodeWithTag("today-quick-action-training").fetchSemanticsNode().boundsInRoot
        val body = composeRule.onNodeWithTag("today-quick-action-body").fetchSemanticsNode().boundsInRoot
        assertTrue("training action must be below photo action", training.top >= photo.bottom)
        assertTrue("body action must be below training action", body.top >= training.bottom)
    }

    @Test
    fun quickActionLabelsStillDoNotOverflowAt262DpWithLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(262.dp)) {
                        TodayScreen(
                            state = AppUiState(profileConfigured = true),
                            onOpenFood = {},
                            onOpenTraining = {},
                            onOpenBody = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("today-quick-actions-stacked").performScrollTo().assertExists()
        assertNoVisualOverflow("today-quick-action-photo-label")
        assertNoVisualOverflow("today-quick-action-training-label")
        assertNoVisualOverflow("today-quick-action-body-label")
    }

    @Test
    fun defaultPlanShowsAllThreeDailyGramTargetsAndCoefficients() {
        showToday()

        scrollToTag("today-plan-weight").assertTextEquals("参考体重 80 kg")
        scrollToTag("today-plan-title").assertTextEquals("三倍碳水")
        scrollToTag("today-plan-factors").assertTextEquals("碳水 3.00 · 蛋白质 1.60 · 脂肪 0.70")
        scrollToTag("today-carbs-intake").assertTextEquals("已吃 0 g · 目标 240 g")
        scrollToTag("today-carbs-balance").assertTextEquals("还差 240 g")
        scrollToTag("today-protein-intake").assertTextEquals("已吃 0 g · 目标 128 g")
        scrollToTag("today-protein-balance").assertTextEquals("还差 128 g")
        scrollToTag("today-fat-intake").assertTextEquals("已吃 0 g · 目标 56 g")
        scrollToTag("today-fat-balance").assertTextEquals("还差 56 g")
        scrollToTag("today-energy-summary").assertTextEquals("热量 · 已记录 0 / 目标 1976 kcal")
        composeRule.onNodeWithText("“已吃”按饮食记录汇总，可能未覆盖全部摄入。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun zeroCarbTargetAndEmptyIntakeHaveFiniteEmptyProgress() {
        showToday(AppUiState(profile = UserProfile(carbFactor = 0.0), profileConfigured = true))

        scrollToTag("today-carbs-intake").assertTextEquals("已吃 0 g · 目标 0 g")
        scrollToTag("today-carbs-balance").assertTextEquals("还差 0 g")
        assertProgress("today-carbs-progress", 0f)
        scrollToTag("today-protein-intake").assertTextEquals("已吃 0 g · 目标 128 g")
    }

    @Test
    fun recordedExcessShowsPositiveGramsAndNeutralGuidance() {
        showToday(
            AppUiState(
                profileConfigured = true,
                todayNutrition = Nutrition(kcal = 2200.0, carbsG = 260.0, proteinG = 140.5, fatG = 61.0),
            ),
        )

        scrollToTag("today-carbs-balance").assertTextEquals("超出 20 g")
        scrollToTag("today-protein-balance").assertTextEquals("超出 12.5 g")
        scrollToTag("today-fat-balance").assertTextEquals("超出 5 g")
        assertProgress("today-carbs-progress", 1f)
        assertProgress("today-protein-progress", 1f)
        assertProgress("today-fat-progress", 1f)
        scrollToTag("today-energy-summary").assertTextEquals("热量 · 已记录 2200 / 目标 1976 kcal")
        composeRule.onNodeWithText("超出仅作记录，不必挨饿补偿。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun adjustPlanInvokesPlanDestination() {
        var openCount = 0
        showToday(onOpenPlan = { openCount++ })

        scrollToTag("today-open-plan").performClick()

        composeRule.runOnIdle { assertEquals(1, openCount) }
    }

    @Test
    fun customCoefficientsKeepSavedPrecisionOnThePlanCard() {
        showToday(
            AppUiState(
                profile = UserProfile(carbFactor = 2.875, proteinFactor = 1.625, fatFactor = 0.735),
                profileConfigured = true,
            ),
        )

        scrollToTag("today-plan-title").assertTextEquals("自定义方案")
        scrollToTag("today-plan-factors").assertTextEquals("碳水 2.875 · 蛋白质 1.625 · 脂肪 0.735")
        scrollToTag("today-carbs-intake").assertTextEquals("已吃 0 g · 目标 230 g")
        scrollToTag("today-protein-intake").assertTextEquals("已吃 0 g · 目标 130 g")
        scrollToTag("today-fat-intake").assertTextEquals("已吃 0 g · 目标 58.8 g")
    }

    @Test
    fun planAndMacroLabelsDoNotClipAt320DpWith150PercentText() {
        showToday(
            state = AppUiState(
                profileConfigured = true,
                todayNutrition = Nutrition(carbsG = 260.0, proteinG = 75.5, fatG = 61.0),
            ),
            fontScale = 1.5f,
        )

        listOf(
            "today-quick-action-photo-label",
            "today-quick-action-training-label",
            "today-quick-action-body-label",
            "today-plan-title",
            "today-plan-weight",
            "today-plan-factors",
            "today-carbs-balance",
            "today-carbs-intake",
            "today-protein-balance",
            "today-protein-intake",
            "today-fat-balance",
            "today-fat-intake",
            "today-energy-summary",
        ).forEach { tag ->
            scrollToTag(tag)
            assertNoVisualOverflow(tag)
            val viewport = composeRule.onNodeWithTag("today-test-viewport").fetchSemanticsNode().boundsInRoot
            val label = labelNode(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must stay inside the left edge", label.left >= viewport.left)
            assertTrue("$tag must stay inside the right edge", label.right <= viewport.right)
        }
        scrollToTag("today-open-plan").assertExists()
    }

    @Test
    fun dailyNutritionPrecedesPlanAndEnergyPrecedesAllMacroProgress() {
        showToday(viewportWidth = 360.dp, viewportHeight = 1000.dp)

        val bodyAction = composeRule.onNodeWithTag("today-quick-action-body").fetchSemanticsNode().boundsInRoot
        val nutrition = composeRule.onNodeWithTag("today-nutrition-card").fetchSemanticsNode().boundsInRoot
        val plan = composeRule.onNodeWithTag("today-plan-card").fetchSemanticsNode().boundsInRoot
        val energy = labelNode("today-energy-summary").fetchSemanticsNode().boundsInRoot
        val carbs = labelNode("today-carbs-balance").fetchSemanticsNode().boundsInRoot
        val protein = labelNode("today-protein-balance").fetchSemanticsNode().boundsInRoot
        val fat = labelNode("today-fat-balance").fetchSemanticsNode().boundsInRoot

        assertTrue("the three shortcuts must remain above daily nutrition", bodyAction.bottom <= nutrition.top)
        assertTrue("daily nutrition must be complete before the current plan", nutrition.bottom <= plan.top)
        assertTrue("energy belongs above the macro summaries", energy.bottom <= carbs.top)
        assertTrue("carbohydrate and protein summaries must not overlap", carbs.bottom <= protein.top)
        assertTrue("protein and fat summaries must not overlap", protein.bottom <= fat.top)
    }

    @Test
    fun regularPhoneShowsEnergyAndAllThreeMacrosWithoutScrolling() {
        showToday(
            state = AppUiState(
                profileConfigured = true,
                todayNutrition = Nutrition(kcal = 180.0, carbsG = 25.0, proteinG = 20.0, fatG = 0.0),
            ),
            viewportWidth = 360.dp,
            viewportHeight = 640.dp,
        )

        composeRule.onNodeWithTag("today-quick-action-photo").assertIsDisplayed()
        composeRule.onNodeWithTag("today-quick-action-training").assertIsDisplayed()
        composeRule.onNodeWithTag("today-quick-action-body").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("today-test-viewport").fetchSemanticsNode().boundsInRoot
        val tags = listOf("today-energy-summary") + listOf("carbs", "protein", "fat").flatMap {
            listOf("today-$it-balance", "today-$it-intake", "today-$it-progress")
        }
        tags.forEach { tag ->
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must remain inside the first screen", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            if (!tag.endsWith("-progress")) assertNoVisualOverflow(tag)
        }
    }

    @Test
    fun largeTextKeepsMacrosReadableAndShortcutTouchTargetsAtLeast48Dp() {
        showToday(fontScale = 1.5f, viewportWidth = 320.dp, viewportHeight = 640.dp)

        listOf("photo", "training", "body").forEach { action ->
            val bounds = scrollToTag("today-quick-action-$action").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            // This fixture provides density=1, so px and dp have the same size.
            assertTrue("$action must keep a 48 dp touch target", bounds.width >= 48f && bounds.height >= 48f)
            assertNoVisualOverflow("today-quick-action-$action-label")
        }
        val macroTags = listOf("today-energy-summary") + listOf("carbs", "protein", "fat").flatMap {
            listOf("today-$it-balance", "today-$it-intake")
        }
        macroTags.forEach { tag ->
            scrollToTag(tag).assertIsDisplayed()
            assertNoVisualOverflow(tag)
        }
        val planAction = scrollToTag("today-open-plan").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("adjusting the plan must remain a 48 dp touch target", planAction.width >= 48f && planAction.height >= 48f)
    }

    private fun showToday(
        state: AppUiState = AppUiState(profileConfigured = true),
        fontScale: Float = 1f,
        onOpenPlan: () -> Unit = {},
        viewportWidth: Dp = 320.dp,
        viewportHeight: Dp? = null,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(viewportWidth)
                        .then(if (viewportHeight != null) Modifier.height(viewportHeight) else Modifier)
                        .testTag("today-test-viewport")) {
                        TodayScreen(
                            state = state,
                            onOpenFood = {},
                            onOpenTraining = {},
                            onOpenBody = {},
                            onOpenPlan = onOpenPlan,
                        )
                    }
                }
            }
        }
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("today-content", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
    }

    private fun assertProgress(tag: String, expected: Float) {
        val progress = scrollToTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertTrue("$tag must have a finite progress", progress.current.isFinite())
        assertEquals(expected, progress.current, 0.0001f)
    }

    private fun assertNoVisualOverflow(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        labelNode(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(results)
        }
        assertTrue("expected one text layout result for $tag", results.size == 1)
        val layout = results.single()
        assertFalse(
            "$tag must not clip or ellipsize: text=${layout.layoutInput.text.text}; " +
                "size=${layout.size}; constraints=${layout.layoutInput.constraints}; " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; " +
                "lineCount=${layout.lineCount}; fontSize=${layout.layoutInput.style.fontSize}; " +
                "lineHeight=${layout.layoutInput.style.lineHeight}",
            layout.hasVisualOverflow,
        )
    }

    private fun labelNode(tag: String): SemanticsNodeInteraction =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true)
}
