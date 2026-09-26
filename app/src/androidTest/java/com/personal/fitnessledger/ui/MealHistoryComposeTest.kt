package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.CalorieSource
import com.personal.fitnessledger.data.EvidenceTier
import com.personal.fitnessledger.data.MealFoodRecord
import com.personal.fitnessledger.data.MealRecord
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PortionBasis
import com.personal.fitnessledger.data.SavedFood
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MealHistoryComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun openingMultiFoodMealAndReturningCallsNoMutationOrPhotoActions() {
        val callbacks = mutableListOf<String>()
        val record = meal(items = listOf(food(101L, "鸡肉饭"), food(102L, "配菜青菜")))
        rule.setContent {
            FitnessLedgerTheme(darkTheme = false) {
                Box(Modifier.width(393.dp).height(760.dp)) {
                    FoodHarness(foodState(record)) { callbacks.add(it) }
                }
            }
        }

        openMeal(record.id)
        rule.onNodeWithTag("meal-details-screen").assertIsDisplayed()
        rule.onNodeWithTag("meal-detail-total").assertTextEquals("600 kcal")
        record.items.forEach { item ->
            rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText(item.name))
            rule.onNodeWithText(item.name).assertIsDisplayed()
        }
        expandBasis(record.items.last().id)
        rule.onNodeWithTag("meal-details-list")
            .performScrollToNode(hasText("营养来源：${record.items.last().sourceName}"))
        rule.onNodeWithText("营养来源：${record.items.last().sourceName}").assertIsDisplayed()
        rule.onNodeWithTag("meal-detail-back").assertIsDisplayed().performClick()
        rule.onNodeWithTag("meal-details-screen").assertDoesNotExist()
        rule.onNodeWithTag("food-record-list").assertIsDisplayed()
        rule.runOnIdle { assertTrue("Viewing must not invoke editor, ledger or photo callbacks", callbacks.isEmpty()) }
    }

    @Test
    fun changingDateDuringLoadingNeverShowsPreviousDaysDetailsEvenWhenMealIdsAreReused() {
        val oldMeal = meal(items = listOf(food(101L, "前一天的餐食")))
        val newDate = TEST_DATE.minusDays(1)
        val newMeal = meal(date = newDate, items = listOf(food(202L, "另一天的餐食")))
        var state by mutableStateOf(foodState(oldMeal))
        rule.setContent { MaterialTheme { FoodHarness(state) } }
        openMeal(oldMeal.id)
        rule.onNodeWithTag("meal-details-screen").assertIsDisplayed()

        rule.runOnIdle {
            // The previous query result intentionally remains present while the new date loads.
            state = state.copy(selectedFoodDate = newDate, isLoadingFoodDate = true)
        }
        rule.onNodeWithTag("meal-details-screen").assertDoesNotExist()
        rule.onNodeWithTag("meal-record-${oldMeal.id}").assertDoesNotExist()
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasText("正在读取这一天的记录…"))
        rule.onNodeWithText("正在读取这一天的记录…").assertIsDisplayed()

        rule.runOnIdle { state = state.copy(isLoadingFoodDate = false) }
        rule.onNodeWithTag("meal-record-${oldMeal.id}").assertDoesNotExist()
        rule.onNodeWithTag("meal-details-screen").assertDoesNotExist()
        rule.runOnIdle { state = state.copy(selectedDateMeals = listOf(newMeal), selectedDateNutrition = newMeal.nutrition) }
        rule.onNodeWithTag("meal-details-screen").assertDoesNotExist()
        openMeal(newMeal.id)
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText("另一天的餐食"))
        rule.onNodeWithText("另一天的餐食").assertIsDisplayed()
        rule.onNodeWithText("前一天的餐食").assertDoesNotExist()
        rule.runOnIdle { state = state.copy(isLoadingFoodDate = true) }
        rule.onNodeWithTag("meal-details-screen").assertDoesNotExist()
    }

    @Test
    fun partialAndMissingDetailsShowExplicitWarningsAndKeepTheStoredMealTotal() {
        var record by mutableStateOf(meal(items = listOf(food(101L, "可读取食物"))).copy(
            nutrition = Nutrition(999.0, 88.0, 44.0, 22.0), itemDetailsIncomplete = true,
        ))
        rule.setContent { MaterialTheme { DetailsHarness(record) } }

        rule.onNodeWithTag("meal-detail-total").assertTextEquals("999 kcal")
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-partial-warning"))
        rule.onNodeWithTag("meal-detail-partial-warning").assertIsDisplayed()
            .assertTextContains("合计仍为当时入账的值", substring = true)
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText("可读取的食物 · 1 项"))
        rule.onNodeWithText("可读取的食物 · 1 项").assertIsDisplayed()

        rule.runOnIdle { record = record.copy(items = emptyList(), itemDetailsIncomplete = false) }
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-missing"))
        rule.onNodeWithTag("meal-detail-missing").assertIsDisplayed()
            .assertTextContains("原有营养合计仍保留", substring = true)
        rule.onNodeWithTag("meal-detail-partial-warning").assertDoesNotExist()
        rule.onNodeWithText("这餐吃了 · 0 项").assertDoesNotExist()
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-total"))
        rule.onNodeWithTag("meal-detail-total").assertTextEquals("999 kcal")
    }

    @Test
    fun disabledMutationMenuStillAllowsReadingAndReturning() {
        var returned = 0
        val mutations = mutableListOf<String>()
        val record = meal(items = listOf(food(101L, "锁定状态下仍可阅读")))
        rule.setContent {
            MaterialTheme {
                MealDetailsScreen(
                    meal = record,
                    canMutate = false,
                    onBack = { returned++ },
                    onEdit = { mutations.add("edit") },
                    onCopy = { mutations.add("copy") },
                    onDelete = { mutations.add("delete") },
                )
            }
        }

        rule.onNodeWithTag("meal-details-menu-${record.id}").assertIsNotEnabled()
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText(record.items.single().name))
        rule.onNodeWithText(record.items.single().name).assertIsDisplayed()
        rule.onNodeWithTag("meal-detail-back").assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, returned)
            assertTrue(mutations.isEmpty())
        }
    }

    @Test
    fun incompleteDetailsCannotBeCopiedOrUsedToOverwriteTheOriginalMeal() {
        val record = meal(items = listOf(food(101L, "仅剩的有效食物"))).copy(itemDetailsIncomplete = true)
        var deletions = 0
        rule.setContent { MaterialTheme {
            MealDetailsScreen(record, canMutate = true, onBack = {},
                onEdit = { error("Incomplete food facts must not replace the original") },
                onCopy = { error("Incomplete food facts must not create a partial copy") },
                onDelete = { deletions++ })
        } }
        rule.onNodeWithTag("meal-details-menu-${record.id}").performClick()
        rule.onNodeWithText("更正这餐").assertIsNotEnabled()
        rule.onNodeWithText("复制为草稿").assertIsNotEnabled()
        rule.onNodeWithText("删除这餐").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, deletions) }
    }

    @Test
    fun longChineseFoodDetailsScrollAt320DpAndLargeTextWhileBackRemainsVisible() {
        val foods = (101L..108L).map { id ->
            food(id, "第${id}项有完整中文名称的清蒸鸡胸肉杂粮饭搭配西兰花胡萝卜和少量酱汁").copy(
                sourceName = "用户逐项核对的包装营养标签与食物数据库参考值，保留原始来源说明$id",
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(680.dp).testTag("meal-detail-viewport")) {
                        DetailsHarness(meal(items = foods))
                    }
                }
            }
        }

        rule.onNodeWithTag("meal-detail-back").assertIsDisplayed()
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText(foods.last().name))
        rule.onNodeWithText(foods.last().name).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(foods.last().name)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertFalse("Long food names must wrap instead of clipping", layouts.single().hasVisualOverflow)
        expandBasis(foods.last().id)
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText("营养来源：${foods.last().sourceName}"))
        rule.onNodeWithText("营养来源：${foods.last().sourceName}").assertIsDisplayed()
        val viewport = rule.onNodeWithTag("meal-detail-viewport").fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithTag("meal-detail-back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Back remains within the fixed header", back.top >= viewport.top && back.bottom <= viewport.bottom)
        assertTrue("Back keeps its touch target", back.width >= 48f && back.height >= 48f)
    }

    @Test
    fun eightSavedFoodsFollowMealHistoryAndFirstViewportShortcutReachesConfirmedRecords() {
        val savedFoods = (1L..8L).map { id ->
            SavedFood(id, "合成常用食物$id", Nutrition(100.0, 20.0, 5.0, 0.0),
                "合成测试", CalorieSource.DERIVED_FROM_MACROS, 100.0, false, 1, id)
        }
        val record = meal(items = listOf(food(101L, "已确认的整餐")))
        rule.setContent {
            FitnessLedgerTheme(darkTheme = false) {
                Box(Modifier.width(393.dp).height(760.dp)) {
                    FoodHarness(foodState(record).copy(savedFoods = savedFoods))
                }
            }
        }

        rule.onNodeWithTag("food-view-records").assertIsDisplayed().performClick()
        rule.onNodeWithTag("food-meal-history").assertIsDisplayed()
        rule.onNodeWithTag("open-meal-details-${record.id}").assertIsDisplayed()
        val historyScroll = foodScrollPosition()
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasText("最近与收藏"))
        rule.onNodeWithText("最近与收藏").assertIsDisplayed()
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasText("合成常用食物8"))
        rule.onNodeWithText("合成常用食物8").assertIsDisplayed()
        // The heading can already be visible below a short history, so it need
        // not scroll at all. The eighth food must require forward navigation.
        assertTrue("Saved foods must follow confirmed meal history", foodScrollPosition() > historyScroll)
    }

    @Test
    fun nutritionBasisIsOptionalButPortionUncertaintyAlwaysVisibleAndReadOnly() {
        val record = meal(items = listOf(food(101L, "测试米饭")))
        rule.setContent { FitnessLedgerTheme(darkTheme = true) { DetailsHarness(record) } }
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-basis-101"))
        rule.onNodeWithText("营养来源：合成营养资料101").assertDoesNotExist()
        rule.onNodeWithText("份量依据：自行称重 · 粗略估算").assertIsDisplayed()
        rule.onNodeWithTag("meal-detail-basis-101").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasText("营养来源：合成营养资料101"))
        rule.onNodeWithText("营养来源：合成营养资料101").assertIsDisplayed()
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-basis-101"))
        rule.onNodeWithTag("meal-detail-basis-101").performClick()
        rule.onNodeWithText("营养来源：合成营养资料101").assertDoesNotExist()
        rule.onNodeWithText("份量依据：自行称重 · 粗略估算").assertExists()
    }

    @Test
    fun foodEntryUsesCompactSecondaryActionsAtNormalWidthWithSafeTouchTargets() {
        rule.setContent { FitnessLedgerTheme {
            Box(Modifier.width(393.dp).height(760.dp)) { FoodHarness(foodState(meal(items = listOf(food(101L, "米饭"))))) }
        } }
        rule.onNodeWithTag("food-secondary-row").assertIsDisplayed()
        rule.onNodeWithTag("food-album-action").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("food-manual-action").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("food-view-records").performClick()
        rule.onNodeWithTag("open-meal-details-1").assertIsDisplayed()
    }

    @Test
    fun foodEntryStacksSecondaryActionsAtLargeTextAndKeepsDateArrowsReachable() {
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            FitnessLedgerTheme { Box(Modifier.width(320.dp).height(760.dp)) {
                FoodHarness(foodState(meal(items = listOf(food(101L, "米饭")))))
            } }
        } }
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasTestTag("food-select-date"))
        rule.onNodeWithContentDescription("前一天").assertIsDisplayed()
        rule.onNodeWithContentDescription("后一天").assertIsDisplayed()
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasTestTag("food-manual-action"))
        rule.onNodeWithTag("food-secondary-stacked").assertExists()
        rule.onNodeWithTag("food-manual-action").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("手工记录一餐", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Manual label must not clip: size=${layout.size}, constraints=${layout.layoutInput.constraints}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
    }

    private fun expandBasis(id: Long) {
        rule.onNodeWithTag("meal-details-list").performScrollToNode(hasTestTag("meal-detail-basis-$id"))
        rule.onNodeWithTag("meal-detail-basis-$id").performClick()
    }

    private fun openMeal(id: Long) {
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasTestTag("open-meal-details-$id"))
        rule.onNodeWithTag("open-meal-details-$id").performClick()
    }

    private fun foodScrollPosition(): Float = rule.onNodeWithTag("food-record-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Composable
    private fun FoodHarness(state: AppUiState, unexpected: (String) -> Unit = { error("Unexpected callback: $it") }) {
        FoodScreen(
            state = state,
            onAnalyzePhoto = { unexpected("analyze-photo") },
            onOpenManualEntry = { unexpected("open-manual-entry") },
            onUpdateManualEntry = { _, _ -> unexpected("update-manual-entry") },
            onKeepManualEntry = { unexpected("keep-manual-entry") },
            onDiscardManualEntry = { unexpected("discard-manual-entry") },
            onCreateManualEntry = { unexpected("create-manual-entry") },
            onSelectDate = { unexpected("select-date") },
            onEditMeal = { unexpected("edit-meal") },
            onCopyMeal = { unexpected("copy-meal") },
            onDeleteMeal = { unexpected("delete-meal") },
            onToggleFoodFavorite = { unexpected("toggle-favorite") },
            onRequestCameraCapture = { unexpected("camera-capture") },
            onCameraLaunchHandled = { _, _ -> unexpected("camera-launch") },
            onCameraCaptureResult = { unexpected("camera-result") },
            onOpenPhotoSettings = { unexpected("photo-settings") },
            onSelectAnalysisProfile = { unexpected("select-service") },
        )
    }

    @Composable
    private fun DetailsHarness(record: MealRecord) {
        MealDetailsScreen(record, canMutate = false, onBack = {}, onEdit = {
            error("Viewing must not edit")
        }, onCopy = { error("Viewing must not copy") }, onDelete = { error("Viewing must not delete") })
    }

    private fun foodState(record: MealRecord) = AppUiState(
        loadedDate = TEST_DATE,
        selectedFoodDate = record.date,
        selectedDateMeals = listOf(record),
        selectedDateNutrition = record.nutrition,
        analysisTokenConfigured = true,
    )

    private fun meal(
        id: Long = 1L,
        date: LocalDate = TEST_DATE,
        items: List<MealFoodRecord>,
    ) = MealRecord(
        id = id,
        commitId = "synthetic-meal-$id",
        date = date,
        title = "合成已确认餐食",
        nutrition = Nutrition(600.0, 60.0, 45.0, 20.0),
        evidenceTier = EvidenceTier.C,
        confirmedAtMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 43_200_000L,
        items = items,
    )

    private fun food(id: Long, name: String) = MealFoodRecord(
        id = id,
        name = name,
        grams = 150.0,
        per100g = Nutrition(150.0, 20.0, 10.0, 3.0),
        sourceName = "合成营养资料$id",
        portionBasis = PortionBasis.USER_WEIGHT,
        evidenceTier = EvidenceTier.C,
        calorieSource = CalorieSource.LABEL_OR_DATABASE,
    )

    private companion object {
        val TEST_DATE: LocalDate = LocalDate.of(2026, 9, 20)
    }
}
