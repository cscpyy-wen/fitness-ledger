package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import com.personal.fitnessledger.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InitialSetupComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryCtaIsVisibleWithoutScrollingAtTypicalPhoneSize() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(393.dp).height(800.dp).testTag("initial-setup-viewport")) {
                    InitialSetupScreen(
                        profile = UserProfile(),
                        isSaving = false,
                        onSaveProfile = {},
                    )
                }
            }
        }

        val cta = composeRule.onNodeWithTag("initial-setup-confirm").fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("initial-setup-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("setup CTA should remain in the first viewport", cta.bottom <= viewport.bottom)
        val minTouchPx = viewport.width * (48f / 393f)
        assertTrue("setup CTA must keep a 48dp touch target", cta.height >= minTouchPx)
        composeRule.onNodeWithTag("initial-setup-confirm").assertIsNotEnabled()
    }

    @Test
    fun fieldsStackAt320DpWithLargeTextAndCtaRemainsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(760.dp).testTag("initial-setup-viewport")) {
                        InitialSetupScreen(
                            profile = UserProfile(),
                            isSaving = false,
                            onSaveProfile = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("initial-setup-confirm").assertIsDisplayed()
        val cta = composeRule.onNodeWithTag("initial-setup-confirm").fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("initial-setup-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("large-text CTA remains within viewport", cta.bottom <= viewport.bottom)
        composeRule.onNodeWithTag("macro-plan-factors-stacked").performScrollTo().assertExists()
        composeRule.onNodeWithTag("macro-plan-factors-row").assertDoesNotExist()
        composeRule.onNodeWithTag("macro-plan-fat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("initial-setup-confirm").assertIsDisplayed()
    }

    @Test
    fun firstRunRequiresOwnWeightAndOnlySavesAfterConfirmation() {
        val savedProfiles = mutableListOf<UserProfile>()
        composeRule.setContent {
            MaterialTheme {
                InitialSetupScreen(UserProfile(), false, savedProfiles::add)
            }
        }

        composeRule.onNodeWithTag("macro-plan-weight").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        composeRule.onNodeWithTag("initial-setup-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("macro-preset-two-protein").performScrollTo().performClick()
        composeRule.onNodeWithTag("initial-setup-confirm").assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(savedProfiles.isEmpty()) }
        composeRule.onNodeWithTag("macro-plan-weight").performScrollTo().performTextReplacement("70")
        composeRule.onNodeWithTag("initial-setup-confirm").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, savedProfiles.size)
            assertEquals(70.0, savedProfiles.single().referenceWeightKg, 0.0)
            assertEquals(2.0, savedProfiles.single().proteinFactor, 0.0)
            assertEquals(0.8, savedProfiles.single().fatFactor, 0.0)
        }
    }
}
