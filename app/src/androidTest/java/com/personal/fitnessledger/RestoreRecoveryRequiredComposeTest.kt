package com.personal.fitnessledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RestoreRecoveryRequiredComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recoveryFailureShowsStoppedReadOnlyStateAndExplicitRetry() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                RestoreRecoveryRequiredScreen(onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithTag("restore-recovery-required").assertIsDisplayed()
        composeRule.onNodeWithText("恢复尚未完成").assertIsDisplayed()
        composeRule.onNodeWithText("账本目前保持只读", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("retry-restore-recovery").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }
}
