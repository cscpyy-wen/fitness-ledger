package com.personal.fitnessledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import com.personal.fitnessledger.data.BodyMeasurement
import com.personal.fitnessledger.data.BodyMeasurementFormDraft
import com.personal.fitnessledger.data.UserProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BodyComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstBodyShortcut_waitsForSuccessfulCommitBeforeClosing() {
        var uiState by mutableStateOf(
            AppUiState(
                isInitialized = true,
                profile = UserProfile(referenceWeightKg = 83.5, waistCm = 91.0),
                measurements = emptyList(),
            ),
        )
        var saveAttempts = 0
        var savedFormId: String? = null
        val requestId = "shortcut-first-body"

        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = uiState,
                    onOpenMeasurement = { initial, shortcutRequestId ->
                        uiState = uiState.copy(
                            bodyMeasurementFormDraft = BodyMeasurementFormDraft(
                                id = "first-body-form",
                                measurementId = initial.id,
                                date = initial.date,
                                weightText = formatOne(initial.weightKg),
                                waistText = initial.waistCm?.let(::formatOne).orEmpty(),
                                shortcutRequestId = shortcutRequestId,
                            ),
                            isBodyMeasurementFormVisible = true,
                        )
                    },
                    onUpdateMeasurement = { expectedId, transform ->
                        uiState = uiState.copy(
                            bodyMeasurementFormDraft = uiState.bodyMeasurementFormDraft
                                ?.takeIf { it.id == expectedId }
                                ?.let(transform),
                        )
                    },
                    onSaveMeasurement = { formId ->
                        saveAttempts += 1
                        savedFormId = formId
                    },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                    newMeasurementRequestId = requestId,
                )
            }
        }

        composeRule.onNodeWithText("记录身体数据").assertIsDisplayed()
        composeRule.onNodeWithTag("body-weight-input").assertEditableText("83.5")
        composeRule.onNodeWithTag("body-waist-input").assertEditableText("")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertEquals(1, saveAttempts)
            assertEquals("first-body-form", savedFormId)
        }
        composeRule.onNodeWithTag("body-weight-input").assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(
                bodyMeasurementFormDraft = null,
                isBodyMeasurementFormVisible = false,
                resolvedBodyMeasurementShortcutRequestId = requestId,
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("记录身体数据").assertCountEquals(0)
    }

    @Test
    fun newMeasurementDoesNotCopyHistoricalWaistIntoTodaysMeasurement() {
        var opened: BodyMeasurement? = null
        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(
                        isInitialized = true,
                        measurements = listOf(BodyMeasurement(1L, LocalDate.now().minusDays(1), 80.0, 87.0)),
                    ),
                    onOpenMeasurement = { measurement, _ -> opened = measurement },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = {}, onDiscardMeasurement = {}, onDeleteMeasurement = {},
                    newMeasurementRequestId = "new-measurement-does-not-reuse-waist",
                )
            }
        }
        composeRule.runOnIdle {
            org.junit.Assert.assertNotNull(opened)
            org.junit.Assert.assertNull(opened?.waistCm)
        }
    }

    @Test
    fun todaysManualMeasurementReopensInsteadOfReplacingMeasuredWaistWithBlank() {
        val existing = BodyMeasurement(8L, LocalDate.now(), 79.0, 86.0)
        var opened: BodyMeasurement? = null
        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(isInitialized = true, measurements = listOf(existing)),
                    onOpenMeasurement = { measurement, _ -> opened = measurement },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = {}, onDiscardMeasurement = {}, onDeleteMeasurement = {},
                    newMeasurementRequestId = "today-existing-measurement",
                )
            }
        }
        composeRule.runOnIdle { assertEquals(existing, opened) }
    }

    @Test
    fun restoredNonShortcutDraftIsPromotedToCurrentRequestAndDoesNotReopenAfterResolution() {
        val requestId = "shortcut-promote"
        var openCalls = 0
        var uiState by mutableStateOf(
            AppUiState(
                isInitialized = true,
                bodyMeasurementFormDraft = BodyMeasurementFormDraft(
                    id = "restored-unbound",
                    date = LocalDate.now(),
                    weightText = "80",
                    waistText = "88",
                ),
                isBodyMeasurementFormVisible = true,
                isBodyMeasurementFormDraftDurable = true,
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = uiState,
                    onOpenMeasurement = { _, shortcutRequestId ->
                        openCalls += 1
                        uiState = uiState.copy(
                            bodyMeasurementFormDraft = uiState.bodyMeasurementFormDraft?.copy(
                                shortcutRequestId = shortcutRequestId,
                            ),
                        )
                    },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = { _ -> },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                    newMeasurementRequestId = requestId,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, openCalls)
            assertEquals(requestId, uiState.bodyMeasurementFormDraft?.shortcutRequestId)
            uiState = uiState.copy(
                bodyMeasurementFormDraft = null,
                isBodyMeasurementFormVisible = false,
                resolvedBodyMeasurementShortcutRequestId = requestId,
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, openCalls) }
    }

    @Test
    fun restoredDraftShowsExactRawDateWeightAndWaist() {
        val restoredDate = LocalDate.now().minusDays(9)
        val restored = BodyMeasurementFormDraft(
            id = "restored-body-form",
            measurementId = 22L,
            date = restoredDate,
            weightText = "79.",
            waistText = "84,",
            revision = 7L,
            updatedAtMillis = 7_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(
                        isInitialized = true,
                        bodyMeasurementFormDraft = restored,
                        isBodyMeasurementFormVisible = true,
                        isBodyMeasurementFormDraftDurable = true,
                    ),
                    onOpenMeasurement = { _, _ -> },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = { _ -> },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                )
            }
        }

        composeRule.onNodeWithText("修改身体记录").assertIsDisplayed()
        composeRule.onNodeWithTag("body-date-input").assertTextContains(restoredDate.toString())
        composeRule.onNodeWithTag("body-weight-input").assertEditableText("79.")
        composeRule.onNodeWithTag("body-waist-input").assertEditableText("84,")
    }

    @Test
    fun trendChartExposesDatesRawWeightsAndSevenDayAveragesToTalkBack() {
        val today = LocalDate.now()
        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(
                        isInitialized = true,
                        loadedDate = today,
                        measurements = listOf(
                            BodyMeasurement(1L, today.minusDays(1), 80.0, 87.0),
                            BodyMeasurement(2L, today, 79.5, 86.5),
                        ),
                    ),
                    onOpenMeasurement = { _, _ -> },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = { _ -> },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                )
            }
        }

        composeRule.onNodeWithTag("body-weight-trend-chart")
            .assertContentDescriptionContains(today.minusDays(1).toString(), substring = true)
            .assertContentDescriptionContains("原始体重 80 kg", substring = true)
            .assertContentDescriptionContains("7日均值", substring = true)
            .assertContentDescriptionContains(today.toString(), substring = true)
    }

    @Test
    fun bodyAutosaveFailureWarnsAgainstForceCloseAndUsesPoliteLiveRegion() {
        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(
                        isInitialized = true,
                        bodyMeasurementFormDraft = BodyMeasurementFormDraft(
                            id = "autosave-failed",
                            date = LocalDate.now(),
                            weightText = "79.",
                            waistText = "84",
                        ),
                        isBodyMeasurementFormVisible = true,
                        isBodyMeasurementFormDraftDurable = false,
                        bodyMeasurementFormSaveError = "无法自动保存到本机",
                    ),
                    onOpenMeasurement = { _, _ -> },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = { _ -> },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                )
            }
        }

        composeRule.onNodeWithTag("body-form-save-status")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("当前原始输入尚未保存到本机，请勿强制关闭；请修改任一字段重试。")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("原始日期、体重和腰围已保留；强制关闭后也可恢复。")
            .assertCountEquals(0)
    }

    @Test
    fun futureBodyDateErrorIsAttachedToDateControl() {
        composeRule.setContent {
            MaterialTheme {
                BodyScreen(
                    state = AppUiState(
                        isInitialized = true,
                        bodyMeasurementFormDraft = BodyMeasurementFormDraft(
                            id = "future-date",
                            date = LocalDate.now().plusDays(1),
                            weightText = "79",
                            waistText = "84",
                        ),
                        isBodyMeasurementFormVisible = true,
                        isBodyMeasurementFormDraftDurable = true,
                    ),
                    onOpenMeasurement = { _, _ -> },
                    onUpdateMeasurement = { _, _ -> },
                    onSaveMeasurement = { _ -> },
                    onDiscardMeasurement = { _ -> },
                    onDeleteMeasurement = {},
                )
            }
        }

        composeRule.onNodeWithTag("body-date-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "不能记录未来日期"),
        )
    }

    @Test
    fun invalidHttpProxyErrorIsAttachedToEndpointFieldAndShownAsSupportingText() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = AppUiState(analysisEndpoint = "http://example.com/analyze"),
                    onSaveProfile = {},
                    onSaveAnalysisConfig = { _, _, _ -> },
                    onClearAnalysisToken = {},
                    onExportBackup = { _, passphrase -> passphrase.fill('\u0000') },
                    onImportBackup = { _, passphrase -> passphrase.fill('\u0000') },
                )
            }
        }

        composeRule.onNodeWithTag("toggle-proxy-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("analysis-endpoint-input")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "识别服务必须使用 HTTPS",
                ),
            )
        composeRule.onNodeWithText("识别服务必须使用 HTTPS").assertIsDisplayed()
        composeRule.onNodeWithText("清空地址后保存会先确认", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("默认会以明文保留完整识别响应 7 天", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("原图不会写入该协调库", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableText(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
}
