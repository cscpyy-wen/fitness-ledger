package com.personal.fitnessledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.personal.fitnessledger.data.WorkoutSession
import org.junit.Rule
import org.junit.Test

class SettingsBackupComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backupCardExplainsCoverageAndOffersBothActions() {
        showSettings(AppUiState(isInitialized = true))

        composeRule.onNodeWithTag("encrypted-backup-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出身体、饮食、训练、草稿、模板与账本照片。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("export-encrypted-backup").assertIsEnabled()
        composeRule.onNodeWithTag("import-encrypted-backup").assertIsEnabled()
    }

    @Test
    fun unfinishedWorkoutDisablesDestructiveRestoreButKeepsExportAvailable() {
        showSettings(
            AppUiState(
                isInitialized = true,
                activeWorkout = WorkoutSession(id = 9L, startedAtMillis = 100L, title = "推"),
            ),
        )

        composeRule.onNodeWithTag("encrypted-backup-card").performScrollTo()
        composeRule.onNodeWithTag("export-encrypted-backup").assertIsEnabled()
        composeRule.onNodeWithTag("import-encrypted-backup").assertIsNotEnabled()
        composeRule.onNodeWithText("请先完成或取消当前训练", substring = true).assertIsDisplayed()
    }

    private fun showSettings(state: AppUiState) {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state,
                    onSaveProfile = {},
                    onSaveAnalysisConfig = { _, _, _ -> },
                    onClearAnalysisToken = {},
                    onExportBackup = { _, passphrase -> passphrase.fill('\u0000') },
                    onImportBackup = { _, passphrase -> passphrase.fill('\u0000') },
                )
            }
        }
    }
}
