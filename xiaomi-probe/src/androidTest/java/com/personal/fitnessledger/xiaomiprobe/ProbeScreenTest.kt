// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightRecord
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProbeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitAccountConsentIsRequiredBeforeConnecting() {
        var state by mutableStateOf(ProbeUiState())
        var connected = false
        compose.setContent {
            ProbeTheme {
                ProbeScreen(state, { state = state.copy(consentAccepted = it) }, { connected = true }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("connect").assertIsNotEnabled()
        compose.onNodeWithTag("consent").performScrollTo().performClick()
        compose.onNodeWithText("这并非官方限权 OAuth 授权", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("connect").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(connected) }
    }

    @Test fun waitingLoginOffersExplicitBrowserActionAndCancellation() {
        var opened = false
        var cancelled = false
        compose.setContent {
            ProbeTheme {
                ProbeScreen(ProbeUiState(stage = ProbeStage.AWAITING_LOGIN), {}, {}, { opened = true }, {}, { cancelled = true })
            }
        }
        compose.onNodeWithTag("open-login").performClick()
        compose.onNodeWithTag("disconnect").performClick()
        compose.runOnIdle { assertTrue(opened && cancelled) }
    }

    @Test fun recordsKeepMissingMetricsAndUnverifiedSourceVisible() {
        compose.setContent {
            ProbeTheme {
                ProbeScreen(
                    ProbeUiState(
                        stage = ProbeStage.RESULTS,
                        records = listOf(WeightRecord(Instant.parse("2026-09-20T01:02:00Z"), 70.2, null, null, "unknown")),
                    ), {}, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag("verification-boundary").assertIsDisplayed()
        compose.onNodeWithTag("weight-record-0").performScrollTo()
        compose.onNodeWithText("70.2").assertIsDisplayed()
        compose.onAllNodesWithText("—").assertCountEquals(2)
        compose.onNodeWithTag("disconnect").assertIsDisplayed()
    }

    @Test fun emptyAndPartialResultDoesNotClaimTheAccountHasNoData() {
        compose.setContent {
            ProbeTheme(darkTheme = true) {
                ProbeScreen(ProbeUiState(stage = ProbeStage.RESULTS, rejectedCount = 2, incomplete = true), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("incomplete-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rejected-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("此结果不代表你的小米报告为空", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun visualCaptureIntroWithSyntheticState() {
        // The Compose test host never instantiates the production secure Activity or gateway.
        compose.setContent {
            ProbeTheme(darkTheme = false) {
                ProbeScreen(ProbeUiState(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("connect").assertIsNotEnabled()
        saveSyntheticScreenshot("probe-visual-intro.png")
        compose.onNodeWithTag("consent").performScrollTo().assertIsDisplayed()
    }

    @Test fun visualCaptureThreeSyntheticRecords() {
        compose.setContent {
            ProbeTheme(darkTheme = false) {
                ProbeScreen(
                    ProbeUiState(
                        stage = ProbeStage.RESULTS,
                        records = listOf(
                            WeightRecord(Instant.parse("2026-09-21T00:12:00Z"), 68.4, 18.2, 57.1, "synthetic-fixture"),
                            WeightRecord(Instant.parse("2026-09-20T00:18:00Z"), 68.9, null, 56.8, "synthetic-fixture"),
                            WeightRecord(Instant.parse("2026-09-19T00:09:00Z"), 69.1, 18.6, null, "synthetic-fixture"),
                        ),
                    ), {}, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag("verification-boundary").assertIsDisplayed()
        saveSyntheticScreenshot("probe-visual-results.png")
        compose.onNodeWithTag("probe-content").performScrollToNode(hasTestTag("weight-record-2"))
        compose.onNodeWithTag("weight-record-2").assertIsDisplayed()
        compose.onNodeWithTag("disconnect").assertIsDisplayed()
    }

    private fun saveSyntheticScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, name).outputStream().use { output ->
            assertTrue("Synthetic screenshot must encode as PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
