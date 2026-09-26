package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MacroPlanComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingExampleOnlyChangesDraftAndPreviewUntilExplicitSave() {
        val saves = mutableListOf<UserProfile>()
        showPlan(onSave = saves::add)

        composeRule.onNodeWithTag("macro-preset-two-protein").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
        editable("macro-plan-protein", "2")
        editable("macro-plan-fat", "0.8")
        composeRule.onNodeWithTag("macro-plan-preview-kcal").performScrollTo().assertTextEquals("2176 kcal")
        composeRule.onNodeWithTag("macro-plan-preview-carbs").performScrollTo().assertTextEquals("240 g")
        composeRule.onNodeWithTag("macro-plan-preview-protein").performScrollTo().assertTextEquals("160 g")
        composeRule.onNodeWithTag("macro-plan-preview-fat").performScrollTo().assertTextEquals("64 g")
        composeRule.onNodeWithTag("macro-plan-save").performClick()
        composeRule.runOnIdle {
            assertEquals(1, saves.size)
            assertEquals(2.0, saves.single().proteinFactor, 0.0)
            assertEquals(0.8, saves.single().fatFactor, 0.0)
        }
    }

    @Test
    fun firstExampleRestoresOnlyFactorsAndRetainsCustomWeight() {
        val saves = mutableListOf<UserProfile>()
        showPlan(UserProfile(referenceWeightKg = 70.25, carbFactor = 2.2, proteinFactor = 2.3, fatFactor = 0.9), saves::add)
        composeRule.onNodeWithTag("macro-preset-three-carb").performScrollTo().performClick()

        editable("macro-plan-weight", "70.25")
        editable("macro-plan-carb", "3")
        editable("macro-plan-protein", "1.6")
        editable("macro-plan-fat", "0.7")
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test
    fun customFactorsUpdateAllPreviewValuesAndDoNotSaveAutomatically() {
        val saves = mutableListOf<UserProfile>()
        showPlan(onSave = saves::add)
        replace("macro-plan-weight", "70")
        replace("macro-plan-carb", "2.5")
        replace("macro-plan-protein", "1.8")
        replace("macro-plan-fat", "0.9")

        composeRule.onNodeWithTag("macro-plan-preview-kcal").performScrollTo().assertTextEquals("1771 kcal")
        composeRule.onNodeWithTag("macro-plan-preview-carbs").performScrollTo().assertTextEquals("175 g")
        composeRule.onNodeWithTag("macro-plan-preview-protein").performScrollTo().assertTextEquals("126 g")
        composeRule.onNodeWithTag("macro-plan-preview-fat").performScrollTo().assertTextEquals("63 g")
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
        composeRule.onNodeWithTag("macro-plan-save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(UserProfile(referenceWeightKg = 70.0, carbFactor = 2.5, proteinFactor = 1.8, fatFactor = 0.9), saves.single())
        }
    }

    @Test
    fun savedCustomPrecisionIsNotRoundedByOpeningAndSavingEditor() {
        val original = UserProfile(heightCm = 175.125, referenceWeightKg = 70.125, waistCm = 80.125, carbFactor = 2.345, proteinFactor = 1.625, fatFactor = 0.725)
        val saves = mutableListOf<UserProfile>()
        showPlan(original, saves::add)

        editable("macro-plan-weight", "70.125")
        editable("macro-plan-carb", "2.345")
        editable("macro-plan-protein", "1.625")
        editable("macro-plan-fat", "0.725")
        composeRule.onNodeWithTag("macro-plan-save").performClick()
        composeRule.runOnIdle { assertEquals(original, saves.single()) }
    }

    @Test
    fun invalidEmptyNonFiniteAndOutOfRangeFactorsCannotBeSaved() {
        val saves = mutableListOf<UserProfile>()
        showPlan(onSave = saves::add)

        listOf("", "NaN", "Infinity", "-1", "10.01").forEach { invalid ->
            replace("macro-plan-carb", invalid)
            composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
            composeRule.onNodeWithTag("macro-plan-preview-kcal").assertDoesNotExist()
        }
        replace("macro-plan-carb", "3")
        replace("macro-plan-protein", "0.49")
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        replace("macro-plan-protein", "4.01")
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        replace("macro-plan-protein", "1.6")
        replace("macro-plan-fat", "0.09")
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        replace("macro-plan-fat", "5.01")
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test
    fun invalidWeightAndCollapsedBodyDetailsBlockSave() {
        showPlan()
        listOf("29.99", "300.01", "Infinity").forEach { invalid ->
            replace("macro-plan-weight", invalid)
            composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        }
        replace("macro-plan-weight", "80")
        composeRule.onNodeWithTag("macro-plan-body-details").performScrollTo().performClick()
        replace("macro-plan-height", "99")
        composeRule.onNodeWithTag("macro-plan-body-details").performScrollTo().performClick()
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        composeRule.onNodeWithText("请展开并修正身体资料中的数值").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun draftSurvivesSavedStateRestorationWithoutBecomingSaved() {
        val saves = mutableListOf<UserProfile>()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                MacroPlanScreen(AppUiState(profileConfigured = true), saves::add, {}, {})
            }
        }
        replace("macro-plan-carb", "2.375")
        replace("macro-plan-weight", "71.")
        restoration.emulateSavedInstanceStateRestore()

        editable("macro-plan-carb", "2.375")
        editable("macro-plan-weight", "71.")
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test
    fun offscreenProfileReplacementDiscardsAllDraftFieldsBeforeSave() {
        var showPlan by mutableStateOf(true)
        var profile by mutableStateOf(UserProfile())
        val saves = mutableListOf<UserProfile>()
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (showPlan) {
                    holder.SaveableStateProvider("plan") {
                        MacroPlanScreen(
                            AppUiState(profile = profile, profileConfigured = true),
                            saves::add,
                            { showPlan = false },
                            {},
                        )
                    }
                }
            }
        }
        replace("macro-plan-weight", "71.25")
        replace("macro-plan-carb", "2.55")
        replace("macro-plan-protein", "1.85")
        replace("macro-plan-fat", "0.65")
        composeRule.onNodeWithTag("macro-plan-body-details").performScrollTo().performClick()
        replace("macro-plan-height", "180.125")
        replace("macro-plan-waist", "90.125")
        composeRule.onNodeWithTag("macro-plan-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("macro-plan-save").assertDoesNotExist()

        // Simulate a backup imported from Settings. Several baseline fields are
        // unchanged, but their unsaved edits must be discarded together too.
        val imported = UserProfile(proteinFactor = 2.0, fatFactor = 0.8)
        composeRule.runOnIdle { profile = imported }
        composeRule.runOnIdle { showPlan = true }
        editable("macro-plan-weight", "80")
        editable("macro-plan-carb", "3")
        editable("macro-plan-protein", "2")
        editable("macro-plan-fat", "0.8")
        editable("macro-plan-height", "175")
        editable("macro-plan-waist", "88")
        composeRule.onNodeWithTag("macro-plan-save").performClick()
        composeRule.runOnIdle { assertEquals(listOf(imported), saves) }
    }

    @Test
    fun offscreenReturnWithUnchangedProfileRetainsUnsavedDraft() {
        var showPlan by mutableStateOf(true)
        val saves = mutableListOf<UserProfile>()
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (showPlan) {
                    holder.SaveableStateProvider("plan") {
                        MacroPlanScreen(
                            AppUiState(profileConfigured = true),
                            saves::add,
                            { showPlan = false },
                            {},
                        )
                    }
                }
            }
        }
        replace("macro-plan-weight", "71.")
        replace("macro-plan-carb", "2.375")
        composeRule.onNodeWithTag("macro-plan-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("macro-plan-save").assertDoesNotExist()
        composeRule.runOnIdle { showPlan = true }

        editable("macro-plan-weight", "71.")
        editable("macro-plan-carb", "2.375")
        composeRule.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test
    fun fixedSaveButtonIsVisibleAtNarrowWidthWithLargeFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(680.dp).testTag("plan-viewport")) {
                        MacroPlanScreen(AppUiState(profileConfigured = true), {}, {}, {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("macro-plan-save").assertIsDisplayed()
        composeRule.onNodeWithTag("macro-plan-factors-stacked").performScrollTo().assertExists()
        composeRule.onNodeWithTag("macro-plan-fat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("macro-plan-save").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("plan-viewport").fetchSemanticsNode().boundsInRoot
        val cta = composeRule.onNodeWithTag("macro-plan-save").fetchSemanticsNode().boundsInRoot
        assertTrue(cta.top >= viewport.top && cta.bottom <= viewport.bottom && cta.height >= 48f)
    }

    @Test
    fun busySaveLocksEditorAndPresets() {
        composeRule.setContent {
            MaterialTheme { MacroPlanScreen(AppUiState(isSavingProfile = true), {}, {}, {}) }
        }
        composeRule.onNodeWithTag("macro-plan-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("macro-plan-weight").assertIsNotEnabled()
        composeRule.onNodeWithTag("macro-preset-three-carb").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun bodyAndSettingsLinksInvokeTheirNavigationCallbacks() {
        var bodyOpened = false
        var settingsOpened = false
        composeRule.setContent {
            MaterialTheme {
                MacroPlanScreen(AppUiState(), {}, { settingsOpened = true }, { bodyOpened = true })
            }
        }
        composeRule.onNodeWithTag("macro-plan-settings").performClick()
        composeRule.onNodeWithTag("macro-plan-body").performClick()
        composeRule.runOnIdle { assertTrue(bodyOpened && settingsOpened) }
    }

    private fun showPlan(profile: UserProfile = UserProfile(), onSave: (UserProfile) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme { MacroPlanScreen(AppUiState(profile = profile, profileConfigured = true), onSave, {}, {}) }
        }
    }

    private fun replace(tag: String, value: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun editable(tag: String, value: String) {
        composeRule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }
}
