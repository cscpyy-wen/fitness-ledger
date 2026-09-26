package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.personal.fitnessledger.data.MAX_ADDITIONAL_MEAL_PROMPT_LENGTH
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AdditionalPromptComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun exampleOnlyFillsTheEditorAndNeedsExplicitSave() {
        var saves = 0
        show(AppUiState(), onSave = { _, done -> saves++; done(true) })
        rule.onNodeWithTag("additional-prompt-status").assertTextContains("未设置", substring = true)
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("save-additional-prompt").assertIsNotEnabled()
        rule.onNodeWithTag("fill-canteen-prompt-example").performScrollTo().performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains(CANTEEN_TRAY_PROMPT_EXAMPLE)
        rule.onNodeWithTag("additional-prompt-count", useUnmergedTree = true).performScrollTo()
            .assertTextContains("${CANTEEN_TRAY_PROMPT_EXAMPLE.length} / $MAX_ADDITIONAL_MEAL_PROMPT_LENGTH 字符")
        rule.runOnIdle { assertEquals(0, saves) }
        rule.onNodeWithTag("save-additional-prompt").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, saves) }
        rule.onNodeWithTag("additional-prompt-editor").assertDoesNotExist()
    }

    @Test fun savingMultilinePromptUpdatesSavedStateOnlyAfterSuccess() {
        var displayed by mutableStateOf(AppUiState())
        var saved: String? = null
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            AnalysisAdditionalPromptCard(displayed) { value, done ->
                saved = value
                displayed = displayed.copy(analysisAdditionalPrompt = value)
                done(true)
            }
        } }
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextInput("  这是合成背景\n请标注不确定份量  ")
        rule.onNodeWithTag("save-additional-prompt").performClick()
        rule.runOnIdle { assertEquals("这是合成背景\n请标注不确定份量", saved) }
        rule.onNodeWithTag("additional-prompt-status").assertTextContains("已保存", substring = true)
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("这是合成背景\n请标注不确定份量")
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("这是合成背景\n请标注不确定份量")
        rule.onNodeWithTag("save-additional-prompt").assertIsNotEnabled()
    }

    @Test fun cancelWarnsAboutUnsavedChangesAndPreservesTheSavedPrompt() {
        show(AppUiState(analysisAdditionalPrompt = "原有合成说明"))
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("未保存说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("keep-editing-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("未保存说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("原有合成说明")
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("原有合成说明")
    }

    @Test fun clearingSavedPromptRequiresConfirmationAndPassesAnEmptyString() {
        var saved: String? = null
        show(AppUiState(analysisAdditionalPrompt = "合成说明"), onSave = { value, done -> saved = value; done(true) })
        rule.onNodeWithTag("clear-additional-prompt").performClick()
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithTag("cancel-clear-additional-prompt").performClick()
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithTag("clear-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-clear-additional-prompt").performClick()
        rule.runOnIdle { assertEquals("", saved) }
    }

    @Test fun erasingTextAndSavingCannotBypassTheClearConfirmation() {
        var saved: String? = null
        show(AppUiState(analysisAdditionalPrompt = "合成说明"), onSave = { value, done -> saved = value; done(true) })
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("")
        rule.onNodeWithTag("save-additional-prompt").performClick()
        rule.runOnIdle { assertNull(saved) }
        rule.onNodeWithTag("cancel-clear-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-editor").assertExists()
        rule.onNodeWithTag("save-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-clear-additional-prompt").performClick()
        rule.runOnIdle { assertEquals("", saved) }
    }

    @Test fun lengthLimitShowsCountAndPreventsSavingUntilTheUserShortensText() {
        show(AppUiState())
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo()
            .performTextReplacement("a".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH + 1))
        rule.onNodeWithTag("additional-prompt-count", useUnmergedTree = true).performScrollTo()
            .assertTextContains("2001 / 2000 字符")
        rule.onNodeWithTag("additional-prompt-validation", useUnmergedTree = true).performScrollTo().assertExists()
        rule.onNodeWithTag("save-additional-prompt").assertIsNotEnabled()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("两行\n合成说明")
        rule.onNodeWithTag("additional-prompt-validation", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("save-additional-prompt").assertIsEnabled()
    }

    @Test fun replacingExistingTextWithExampleRequiresConfirmationAndDoesNotSave() {
        show(AppUiState(analysisAdditionalPrompt = "已有合成说明"))
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("fill-canteen-prompt-example").performScrollTo().performClick()
        rule.onNodeWithTag("keep-editing-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("已有合成说明")
        rule.onNodeWithTag("fill-canteen-prompt-example").performScrollTo().performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains(CANTEEN_TRAY_PROMPT_EXAMPLE)
        rule.onNodeWithTag("save-additional-prompt").assertIsEnabled()
    }

    @Test fun failedSaveKeepsDraftOpenWithoutChangingTheSavedPreview() {
        show(AppUiState(analysisAdditionalPrompt = "已保存合成说明"), onSave = { _, done -> done(false) })
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("暂未保存的新说明")
        rule.onNodeWithTag("save-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-save-error").performScrollTo().assertExists()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("暂未保存的新说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("已保存合成说明")
    }

    @Test fun providerChangesKeepTheGlobalSavedPromptAndCurrentDraft() {
        var displayed by mutableStateOf(AppUiState(analysisAdditionalPrompt = "全局合成说明", activeAnalysisProfileId = "qwen"))
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            AnalysisAdditionalPromptCard(displayed) { _, _ -> error("Provider change must not save prompt") }
        } }
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("全局未保存说明")
        rule.runOnIdle { displayed = displayed.copy(activeAnalysisProfileId = "deepseek") }
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("全局未保存说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("全局合成说明")
    }

    @Test fun busyStateDisablesSavedPromptActionsAndAnAlreadyOpenEditor() {
        var displayed by mutableStateOf(AppUiState(analysisAdditionalPrompt = "合成说明", isAnalyzingPhoto = true))
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            AnalysisAdditionalPromptCard(displayed) { _, _ -> error("Busy state must not save prompt") }
        } }
        rule.onNodeWithTag("edit-additional-prompt").assertIsNotEnabled()
        rule.onNodeWithTag("clear-additional-prompt").assertIsNotEnabled()
        rule.runOnIdle { displayed = displayed.copy(isAnalyzingPhoto = false) }
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("修改后的合成说明")
        rule.runOnIdle { displayed = displayed.copy(isImportingBackup = true) }
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("fill-canteen-prompt-example").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("save-additional-prompt").assertIsNotEnabled()
    }

    @Test fun settingsScreenWiresTheDedicatedPromptSaveCallback() {
        var saved: String? = null
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            SettingsScreen(AppUiState(), onSaveProfile = {}, onSaveAnalysisConfig = { _, _, _ -> error("Wrong callback") },
                onClearAnalysisToken = { error("Wrong callback") }, onExportBackup = { _, _ -> }, onImportBackup = { _, _ -> },
                onSaveAnalysisProfile = { _, _, _, _, _, _ -> error("Wrong callback") },
                onSaveAnalysisAdditionalPrompt = { text, done -> saved = text; done(true) })
        } }
        rule.onNodeWithTag("edit-additional-prompt").performScrollTo().performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextInput("合成附加说明")
        rule.onNodeWithTag("save-additional-prompt").performClick()
        rule.runOnIdle { assertEquals("合成附加说明", saved) }
    }

    @Test fun repositoryRefreshDoesNotOverwriteAnUnsavedEditingSession() {
        var displayed by mutableStateOf(AppUiState(analysisAdditionalPrompt = "打开时的已保存说明"))
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            AnalysisAdditionalPromptCard(displayed) { _, _ -> error("Refresh must not save prompt") }
        } }
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("本次未保存说明")
        rule.runOnIdle { displayed = displayed.copy(analysisAdditionalPrompt = "后台刷新的已保存说明") }
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("本次未保存说明")
        rule.onNodeWithTag("additional-prompt-saved-change").performScrollTo().assertExists()
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("后台刷新的已保存说明")
    }

    @Test fun restoringUiKeepsUnsavedPromptAndDiscardConfirmation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FitnessLedgerTheme(darkTheme = false) {
            AnalysisAdditionalPromptCard(AppUiState(analysisAdditionalPrompt = "原有说明")) { _, _ -> error("Restore must not save prompt") }
        } }
        rule.onNodeWithTag("edit-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().performTextReplacement("恢复前未保存说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("keep-editing-additional-prompt").performClick()
        rule.onNodeWithTag("additional-prompt-input").performScrollTo().assertTextContains("恢复前未保存说明")
        rule.onNodeWithTag("cancel-additional-prompt").performClick()
        rule.onNodeWithTag("confirm-additional-prompt-discard").performClick()
        rule.onNodeWithTag("additional-prompt-preview").assertTextContains("原有说明")
    }

    private fun show(state: AppUiState, onSave: (String, (Boolean) -> Unit) -> Unit = { _, _ -> error("Unexpected save") }) {
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AnalysisAdditionalPromptCard(state, onSave)
            }
        } }
    }
}
