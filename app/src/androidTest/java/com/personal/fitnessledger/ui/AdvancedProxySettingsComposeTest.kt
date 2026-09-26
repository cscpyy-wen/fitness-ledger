package com.personal.fitnessledger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.personal.fitnessledger.data.AnalysisServiceProfile
import com.personal.fitnessledger.data.AnalysisTransport
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdvancedProxySettingsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val proxy = AnalysisServiceProfile("proxy-current", "当前代理", "https://proxy.example/analyze-meal",
        "", true, AnalysisTransport.MEAL_PROXY)
    private val vision = AnalysisServiceProfile("vision-other", "保留视觉服务", "https://vision.example/v1/chat/completions",
        "vision-model", true)
    private val proxyState = AppUiState(
        analysisEndpoint = proxy.endpointUrl, analysisTransport = AnalysisTransport.MEAL_PROXY,
        analysisTokenConfigured = true, analysisProfiles = listOf(proxy, vision), activeAnalysisProfileId = proxy.id,
    )

    @Test fun clearingCurrentProxyRequiresConfirmationAndNeverCallsTheGlobalClear() {
        var activeClears = 0
        show(proxyState, onClearActive = { done -> activeClears++; done(true) })
        rule.onNodeWithTag("toggle-proxy-settings").performScrollTo().performClick()
        rule.onNodeWithTag("clear-current-proxy").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, activeClears) }
        rule.onNodeWithText("将删除当前代理的名称、地址和令牌，并停用照片上传。其他已保存服务会保留，不会自动启用。").assertExists()
        rule.onNodeWithTag("cancel-clear-current-proxy").performClick()
        rule.runOnIdle { assertEquals(0, activeClears) }
        rule.onNodeWithTag("clear-current-proxy").performScrollTo().performClick()
        rule.onNodeWithTag("confirm-clear-current-proxy").performClick()
        rule.runOnIdle { assertEquals(1, activeClears) }
    }

    @Test fun savingAnEmptyProxyAddressUsesTheSameConfirmedCurrentOnlyClear() {
        var activeClears = 0
        show(proxyState, onClearActive = { done -> activeClears++; done(true) })
        rule.onNodeWithTag("toggle-proxy-settings").performScrollTo().performClick()
        rule.onNodeWithTag("analysis-endpoint-input").performScrollTo().performTextReplacement("")
        rule.onNodeWithTag("save-proxy-config").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(0, activeClears) }
        rule.onNodeWithTag("confirm-clear-current-proxy").performClick()
        rule.runOnIdle { assertEquals(1, activeClears) }
    }

    @Test fun proxyControlsCannotClearAnActiveDirectVisionProfile() {
        show(proxyState.copy(analysisEndpoint = vision.endpointUrl, analysisModel = vision.modelName,
            analysisTransport = AnalysisTransport.VISION_API, activeAnalysisProfileId = vision.id))
        rule.onNodeWithTag("toggle-proxy-settings").performScrollTo().performClick()
        rule.onNodeWithTag("clear-current-proxy").assertDoesNotExist()
        rule.onNodeWithTag("save-proxy-config").performScrollTo().assertIsNotEnabled()
    }

    @Test fun confirmationCannotDeleteAProfileSelectedAfterTheDialogWasOpened() {
        var displayed by mutableStateOf(proxyState)
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            SettingsScreen(displayed, onSaveProfile = {}, onSaveAnalysisConfig = { _, _, _ -> error("Must not save") },
                onClearAnalysisToken = { error("Must not clear all services") }, onExportBackup = { _, _ -> },
                onImportBackup = { _, _ -> }, onSaveAnalysisProfile = { _, _, _, _, _, _ -> },
                onClearActiveAnalysisConnection = { error("Changed target must not be cleared") })
        } }
        rule.onNodeWithTag("toggle-proxy-settings").performScrollTo().performClick()
        rule.onNodeWithTag("clear-current-proxy").performScrollTo().performClick()
        rule.runOnIdle { displayed = displayed.copy(analysisEndpoint = vision.endpointUrl,
            analysisTransport = AnalysisTransport.VISION_API, activeAnalysisProfileId = vision.id) }
        rule.onNodeWithTag("confirm-clear-current-proxy").assertIsNotEnabled()
        rule.onNodeWithText("当前服务已更改，请取消后重新选择要删除的代理连接。").assertExists()
    }

    private fun show(
        state: AppUiState,
        onClearActive: ((Boolean) -> Unit) -> Unit = { error("Must not clear the current service") },
    ) {
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            SettingsScreen(state, onSaveProfile = {}, onSaveAnalysisConfig = { _, _, _ -> error("Must not save empty configuration") },
                onClearAnalysisToken = { error("Must not clear all services") }, onExportBackup = { _, _ -> },
                onImportBackup = { _, _ -> }, onSaveAnalysisProfile = { _, _, _, _, _, _ -> },
                onClearActiveAnalysisConnection = onClearActive)
        } }
    }
}
