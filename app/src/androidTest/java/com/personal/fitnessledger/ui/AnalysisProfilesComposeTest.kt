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
import com.personal.fitnessledger.data.AnalysisServiceProfile
import com.personal.fitnessledger.data.AnalysisTransport
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AnalysisProfilesComposeTest {
    @get:Rule val rule = createComposeRule()

    private val first = AnalysisServiceProfile(
        id = "qwen-one", name = "Qwen 个人", endpointUrl = "https://one.example/v1/chat/completions",
        modelName = "vision-one", tokenConfigured = true,
    )
    private val second = AnalysisServiceProfile(
        id = "glm-two", name = "GLM 备用", endpointUrl = "https://two.example/v4/chat/completions",
        modelName = "vision-two", tokenConfigured = true,
    )
    private fun state(profiles: List<AnalysisServiceProfile> = listOf(first, second)) = AppUiState(
        analysisProfiles = profiles, activeAnalysisProfileId = first.id,
        analysisEndpoint = first.endpointUrl, analysisModel = first.modelName,
        analysisTransport = AnalysisTransport.VISION_API, analysisTokenConfigured = true,
    )

    @Test fun selectingSavedProfileUsesItsIdAndUpdatesTheCurrentLabel() {
        var displayed by mutableStateOf(state())
        var selected: String? = null
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(displayed, onSave = { _, _, _, _ -> error("Legacy save must not run") }, onClear = {},
                    onSaveProfile = { _, _, _, _, _, done -> done(false) },
                    onSelectProfile = { id, done -> selected = id; displayed = displayed.copy(activeAnalysisProfileId = id); done(true) })
            }
        } }
        rule.onNodeWithTag("vision-active-profile").assertTextContains("当前启用：Qwen 个人", substring = true)
        rule.onNodeWithTag("vision-select-${first.id}").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-select-${second.id}").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(second.id, selected) }
        rule.onNodeWithTag("vision-active-profile").performScrollTo().assertTextContains("当前启用：GLM 备用", substring = true)
    }

    @Test fun presetsFillEditableAddressesAndModelsWithoutSavingOrGrantingConsent() {
        var saves = 0
        show(AppUiState(), onSave = { _, _, _, _, _, _ -> saves++ })
        listOf(
            Triple("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-plus"),
            Triple("deepseek", "https://api.deepseek.com", "deepseek-flash"),
            Triple("glm", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6v"),
        ).forEach { (id, endpoint, model) ->
            rule.onNodeWithTag("vision-add-$id").performScrollTo().performClick()
            rule.onNodeWithTag("vision-endpoint").performScrollTo().assertTextContains(endpoint)
            rule.onNodeWithTag("vision-model").performScrollTo().assertTextContains(model)
            rule.onNodeWithTag("vision-upload-consent").performScrollTo().assertIsOff()
            rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
            rule.onNodeWithTag("vision-cancel-editor").performClick()
        }
        rule.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun creatingNewProfileCallsOnlyTheNewCallbackWithItsOwnKey() {
        var saved: List<String?>? = null
        show(AppUiState(), onSave = { id, name, endpoint, model, key, done ->
            saved = listOf(id, name, endpoint, model, key); done(true)
        })
        rule.onNodeWithTag("vision-add-qwen").performScrollTo().performClick()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-qwen-key")
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().performClick()
        rule.onNodeWithTag("save-vision-config").performClick()
        rule.runOnIdle {
            assertEquals(listOf(null, "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-plus", "synthetic-qwen-key"), saved)
        }
        rule.onNodeWithTag("vision-profile-editor").assertDoesNotExist()
    }

    @Test fun editingInactiveProfileWithoutKeyCannotBorrowTheActiveProfilesKey() {
        var savedId: String? = null
        var savedKey: String? = null
        show(state(listOf(first, second.copy(tokenConfigured = false))), onSave = { id, _, _, _, key, done ->
            savedId = id; savedKey = key; done(true)
        })
        rule.onNodeWithTag("vision-edit-${second.id}").performScrollTo().performClick()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().performClick()
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-own-key")
        rule.onNodeWithTag("save-vision-config").performClick()
        rule.runOnIdle { assertEquals(second.id, savedId); assertEquals("synthetic-own-key", savedKey) }
    }

    @Test fun editingInactiveProfileAtItsOriginalAddressRetainsOnlyItsOwnKey() {
        var savedId: String? = null
        var savedKey: String? = null
        show(state(), onSave = { id, _, _, _, key, done -> savedId = id; savedKey = key; done(true) })
        rule.onNodeWithTag("vision-edit-${second.id}").performScrollTo().performClick()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().assertIsOn()
        rule.onNodeWithTag("save-vision-config").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(second.id, savedId); assertEquals("", savedKey) }
    }

    @Test fun changingAddressClearsEnteredKeyAndRequiresFreshUploadConsent() {
        show(state())
        rule.onNodeWithTag("vision-edit-${second.id}").performScrollTo().performClick()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-old-key")
        rule.onNodeWithTag("vision-endpoint").performScrollTo().performTextReplacement("https://new.example/v1")
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-new-key")
        rule.onNodeWithTag("save-vision-config").assertIsEnabled()
    }

    @Test fun switchingPresetWarnsBeforeDiscardingAndNeverCarriesTheEnteredKey() {
        show(AppUiState())
        rule.onNodeWithTag("vision-add-qwen").performScrollTo().performClick()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-old-key")
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().performClick()
        rule.onNodeWithTag("vision-preset-glm").performScrollTo().performClick()
        rule.onNodeWithTag("vision-keep-editing").performClick()
        rule.onNodeWithTag("vision-model").performScrollTo().assertTextContains("qwen3-vl-plus")
        rule.onNodeWithTag("vision-preset-glm").performScrollTo().performClick()
        rule.onNodeWithTag("vision-confirm-discard").performClick()
        rule.onNodeWithTag("vision-model").performScrollTo().assertTextContains("glm-4.6v")
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().assertIsOff().performClick()
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
    }

    @Test fun cancellingUnsavedEditWarnsAndOpeningAnotherProfileDoesNotReuseItsKey() {
        var savedKey: String? = null
        show(state(), onSave = { _, _, _, _, key, done -> savedKey = key; done(true) })
        rule.onNodeWithTag("vision-edit-${second.id}").performScrollTo().performClick()
        rule.onNodeWithTag("vision-key").performScrollTo().performTextInput("synthetic-temporary-key")
        rule.onNodeWithTag("vision-cancel-editor").performClick()
        rule.onNodeWithTag("vision-keep-editing").performClick()
        rule.onNodeWithTag("vision-cancel-editor").performClick()
        rule.onNodeWithTag("vision-confirm-discard").performClick()
        rule.onNodeWithTag("vision-edit-${first.id}").performScrollTo().performClick()
        rule.onNodeWithTag("save-vision-config").performClick()
        rule.runOnIdle { assertEquals("", savedKey) }
    }

    @Test fun deletingCurrentProfileAndClearingAllKeysRequireSeparateConfirmation() {
        var deleted: String? = null
        var cleared = 0
        show(state(), onDelete = { id, done -> deleted = id; done(true) }, onClear = { done -> cleared++; done(true) })
        rule.onNodeWithTag("vision-delete-${first.id}").performScrollTo().performClick()
        rule.onNodeWithText("将删除此服务及其密钥，并停用上传。不会自动启用其他服务。").assertExists()
        rule.runOnIdle { assertNull(deleted) }
        rule.onNodeWithTag("vision-confirm-delete").performClick()
        rule.runOnIdle { assertEquals(first.id, deleted) }
        rule.onNodeWithTag("vision-clear-all").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, cleared) }
        rule.onNodeWithTag("vision-confirm-clear-all").performClick()
        rule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun analysisAndBackupBusyStatesDisableProviderChanges() {
        var displayed by mutableStateOf(state().copy(isAnalyzingPhoto = true))
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(displayed, onSave = { _, _, _, _ -> error("Legacy save must not run") }, onClear = {},
                    onSaveProfile = { _, _, _, _, _, _ -> error("Busy save must not run") })
            }
        } }
        listOf(
            state().copy(isAnalyzingPhoto = true), state().copy(isImportingBackup = true),
            state().copy(isExportingBackup = true), state().copy(restoreRecoveryRequired = true),
        ).forEach { next ->
            rule.runOnIdle { displayed = next }
            rule.onNodeWithTag("vision-select-${second.id}").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("vision-edit-${first.id}").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("vision-delete-${first.id}").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("vision-add-qwen").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun migratedProxyCanBeSelectedButCannotBeEditedAsADirectVisionService() {
        val proxy = second.copy(transport = AnalysisTransport.MEAL_PROXY, modelName = "")
        show(state(listOf(first, proxy)))
        rule.onNodeWithTag("vision-select-${proxy.id}").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("vision-edit-${proxy.id}").assertDoesNotExist()
        rule.onNodeWithText("请在下方高级代理设置中编辑此服务。").performScrollTo().assertExists()
    }

    @Test fun importStartingDuringEditDisablesFieldsConsentAndSave() {
        var displayed by mutableStateOf(state())
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(displayed, onSave = { _, _, _, _ -> error("Legacy save must not run") }, onClear = {},
                    onSaveProfile = { _, _, _, _, _, _ -> error("Busy save must not run") })
            }
        } }
        rule.onNodeWithTag("vision-edit-${second.id}").performScrollTo().performClick()
        rule.runOnIdle { displayed = displayed.copy(isImportingBackup = true) }
        rule.onNodeWithTag("vision-profile-name").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-endpoint").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-model").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-key").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-upload-consent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("save-vision-config").assertIsNotEnabled()
    }

    @Test fun unreadableSavedProfilesBlockNewConfigurationButAllowConfirmedRecovery() {
        var clears = 0
        show(AppUiState(analysisProfilesUnreadable = true), onClear = { done -> clears++; done(true) })
        rule.onNodeWithTag("vision-active-profile").assertTextContains("已保存服务暂时无法读取", substring = true)
        rule.onNodeWithTag("vision-add-qwen").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-add-custom").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("vision-clear-all").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(0, clears) }
        rule.onNodeWithText("将删除本机暂时无法读取的全部服务连接和密钥，之后可以重新添加服务。").assertExists()
        rule.onNodeWithTag("vision-confirm-clear-all").performClick()
        rule.runOnIdle { assertEquals(1, clears) }
    }

    private fun show(
        state: AppUiState,
        onSave: (String?, String, String, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, _, _, done -> done(false) },
        onDelete: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
        onClear: ((Boolean) -> Unit) -> Unit = { done -> done(false) },
    ) {
        rule.setContent { FitnessLedgerTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                VisionSettingsCard(state, onSave = { _, _, _, _ -> error("Legacy save must not run") }, onClear = onClear,
                    onSaveProfile = onSave, onDeleteProfile = onDelete)
            }
        } }
    }
}
