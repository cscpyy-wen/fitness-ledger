package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.personal.fitnessledger.data.*
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WholeMealComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connectingRequiresExplicitUploadConsentAndDoesNotCallModel() {
        var saves = 0
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(AppUiState(), onSave = { address, model, key, done ->
                    assertEquals("https://api.example/v1", address)
                    assertEquals("qwen3-vl-plus", model)
                    assertEquals("synthetic-key", key)
                    saves++; done(true)
                }, onClear = {})
            }
        } }
        capture("wholemeal-settings.png")
        rule.onNodeWithTag("vision-endpoint").performScrollTo().performTextInput("https://api.example/v1")
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-key")
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().performClick()
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, saves) }
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
    }

    @Test fun growingRecentFoodsNeverPushesPrimaryMealEntryBelowTheFirstViewport() {
        val foods = (1L..8L).map { id -> SavedFood(id, "合成常用食物$id", Nutrition(100.0, 20.0, 5.0, 0.0),
            "合成测试", CalorieSource.DERIVED_FROM_MACROS, 100.0, false, 1, id) }
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Box(Modifier.width(393.dp).height(760.dp)) {
                FoodScreen(
                    state = AppUiState(savedFoods = foods, analysisTokenConfigured = true),
                    onAnalyzePhoto = {}, onOpenManualEntry = {}, onUpdateManualEntry = { _, _ -> },
                    onKeepManualEntry = {}, onDiscardManualEntry = {}, onCreateManualEntry = {},
                    onSelectDate = {}, onEditMeal = {}, onCopyMeal = {}, onDeleteMeal = {},
                    onToggleFoodFavorite = {}, onRequestCameraCapture = {},
                    onCameraLaunchHandled = { _, _ -> }, onCameraCaptureResult = {},
                )
            }
        } }
        rule.onNodeWithText("拍整餐并识别").assertIsDisplayed()
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasText("最近与收藏"))
        rule.onNodeWithText("最近与收藏").assertIsDisplayed()
    }

    @Test fun changingAddressRequiresNewKeyAndNewUploadConsent() {
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(AppUiState(analysisTransport = AnalysisTransport.VISION_API,
                    analysisEndpoint = "https://one.example/v1/chat/completions", analysisTokenConfigured = true,
                    analysisModel = "vision-model"), onSave = { _, _, _, _ -> }, onClear = {})
            }
        } }
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("vision-endpoint").performScrollTo().performTextReplacement("https://two.example/v1")
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-new-key")
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().performClick()
        rule.onNodeWithTag("save-vision-config").performScrollTo().assertIsEnabled()
    }

    @Test fun foodServiceMenuSwitchesWithoutAnalyzingOrOpeningCamera() {
        var selected: String? = null
        var captures = 0
        var analyses = 0
        val profiles = listOf(
            AnalysisServiceProfile("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-vl-plus", true),
            AnalysisServiceProfile("glm", "GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6v", true),
        )
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            FoodScreen(state = AppUiState(analysisProfiles = profiles, activeAnalysisProfileId = "qwen", analysisTokenConfigured = true),
                onAnalyzePhoto = { analyses++ }, onOpenManualEntry = {}, onUpdateManualEntry = { _, _ -> },
                onKeepManualEntry = {}, onDiscardManualEntry = {}, onCreateManualEntry = {},
                onSelectDate = {}, onEditMeal = {}, onCopyMeal = {}, onDeleteMeal = {},
                onToggleFoodFavorite = {}, onRequestCameraCapture = { captures++ },
                onCameraLaunchHandled = { _, _ -> }, onCameraCaptureResult = {},
                onSelectAnalysisProfile = { selected = it })
        } }
        rule.onNodeWithTag("food-record-list").performScrollToNode(hasTestTag("food-select-service"))
        rule.onNodeWithTag("food-select-service").performClick()
        rule.onNodeWithTag("food-service-glm").performClick()
        rule.runOnIdle { assertEquals("glm", selected); assertEquals(0, captures); assertEquals(0, analyses) }
    }

    @Test fun wholeMealNeedsNoCandidateSelectionAndPortionChangeIsReflectedBeforeConfirmation() {
        var savedItem: FoodDraftItem? = null
        var confirmed = false
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            var draft by remember { mutableStateOf(MealDraft(photoUri = "", items = listOf(
                item("rice", "米饭", 200.0), item("chicken", "鸡肉", 300.0), item("greens", "蔬菜", 150.0)),
                evidenceTier = EvidenceTier.C, evidenceReason = "估算范围仅为份量参考", unresolvedFlags = emptySet(),
                providerLabel = "合成测试 · 不是真实识别", analysisMode = AnalysisMode.REMOTE_AI)) }
            MealDraftScreen(state = AppUiState(mealDraft = draft),
                onUpdateItem = { item, done -> savedItem = item; draft = draft.copy(items = draft.items.map { if (it.id == item.id) item else it }, userReviewed = false); done(true) },
                onRemoveItem = {}, onAddItem = { _, _ -> }, onResolveHypothesis = { _, _, _ -> },
                onReviewedChange = { draft = draft.copy(userReviewed = it) }, onConfirm = { confirmed = true },
                onDiscard = {}, onRetryAnalysis = {}, onChangeTargetDate = {})
        } }
        rule.onNodeWithText("选择最像的一项").assertDoesNotExist()
        capture("wholemeal-summary.png")
        rule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("photo-portion-chicken"))
        rule.onNodeWithTag("photo-portion-chicken").performClick()
        rule.onNodeWithTag("portion-0.5").performClick()
        capture("wholemeal-portion.png")
        rule.onNodeWithTag("save-photo-portion").performClick()
        rule.runOnIdle { assertEquals(150.0, savedItem!!.grams, 0.0); assertFalse(confirmed) }
        rule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("meal-next-review"))
        rule.onNodeWithTag("meal-next-review").performClick()
        rule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("meal-review-section"))
        rule.onNodeWithTag("meal-confirm-entry").assertIsNotEnabled()
        rule.onNodeWithContentDescription("我已核对食材", substring = true).performClick()
        capture("wholemeal-review.png")
        rule.onNodeWithTag("meal-confirm-entry").assertIsEnabled().performClick()
        rule.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun failedDraftOffersSettingsWithoutDiscardingItsPhoto() {
        var opened = false
        var discarded = false
        rule.setContent { MaterialTheme {
            MealDraftScreen(state = AppUiState(mealDraft = MealDraft(photoUri = "", items = emptyList(),
                state = DraftState.ANALYSIS_FAILED, evidenceTier = EvidenceTier.D, evidenceReason = "密钥失效",
                unresolvedFlags = emptySet(), providerLabel = "失败", analysisMode = AnalysisMode.MANUAL)),
                onUpdateItem = { _, _ -> }, onRemoveItem = {}, onAddItem = { _, _ -> }, onResolveHypothesis = { _, _, _ -> },
                onReviewedChange = {}, onConfirm = {}, onDiscard = { discarded = true }, onRetryAnalysis = {},
                onChangeTargetDate = {}, onOpenPhotoSettings = { opened = true })
        } }
        rule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("draft-photo-settings"))
        rule.onNodeWithTag("draft-photo-settings").performClick()
        rule.runOnIdle { assertTrue(opened); assertFalse(discarded) }
    }

    @Test fun changingServiceLocksPaidRetryUntilTheConfigurationIsCommitted() {
        rule.setContent { MaterialTheme {
            MealDraftScreen(state = AppUiState(isSavingAnalysisConfig = true, analysisTokenConfigured = true,
                mealDraft = MealDraft(photoUri = "content://synthetic/photo.jpg", items = emptyList(),
                    state = DraftState.ANALYSIS_FAILED, evidenceTier = EvidenceTier.D, evidenceReason = "合成失败",
                    unresolvedFlags = emptySet(), providerLabel = "合成测试", analysisMode = AnalysisMode.MANUAL)),
                onUpdateItem = { _, _ -> }, onRemoveItem = {}, onAddItem = { _, _ -> }, onResolveHypothesis = { _, _, _ -> },
                onReviewedChange = {}, onConfirm = {}, onDiscard = {}, onRetryAnalysis = { error("must not retry while selecting service") },
                onChangeTargetDate = {}, onOpenPhotoSettings = {})
        } }
        rule.onNodeWithTag("meal-draft-list").performScrollToNode(hasTestTag("draft-photo-settings"))
        rule.onNodeWithTag("draft-photo-settings").assertIsNotEnabled()
        rule.onNodeWithText("重新尝试识别").assertIsNotEnabled()
    }

    private fun item(id: String, name: String, grams: Double) = FoodDraftItem(id = id, name = name, grams = grams,
        gramsMin = grams * .75, gramsMax = grams * 1.25, per100g = Nutrition(150.0, 20.0, 10.0, 3.0),
        sourceName = "合成测试", portionBasis = PortionBasis.AI_SINGLE_PHOTO, evidenceTier = EvidenceTier.C)

    private fun capture(name: String) {
        rule.waitForIdle()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(context.cacheDir, name).outputStream().use {
            // Dialogs introduce a second Compose root; capture the topmost one.
            rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
