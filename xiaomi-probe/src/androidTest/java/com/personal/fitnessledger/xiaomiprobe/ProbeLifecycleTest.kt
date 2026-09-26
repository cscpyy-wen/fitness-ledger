// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightRecord
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Entirely synthetic: these tests never open a browser or contact Xiaomi. */
class ProbeLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        MainActivity.gatewayFactory = { XiaomiCloudGateway() }
    }

    @Test fun browserBackgroundKeepsSessionAndReadingRequiresSeparateAction() {
        val fake = FakeGateway()
        launch(fake)
        scenario!!.onActivity { activity ->
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        connect()
        compose.onNodeWithTag("open-login").assertIsDisplayed()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        assertFalse(fake.closed)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        fake.loginReady.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("read-weights").assertIsDisplayed()
        assertEquals(0, fake.reads)
        compose.onNodeWithTag("read-weights").performClick()
        compose.onNodeWithTag("weight-record-0").performScrollTo().assertIsDisplayed()
        assertEquals(1, fake.reads)
        compose.onNodeWithTag("disconnect").performClick()
        assertTrue(fake.closed)
        compose.onNodeWithTag("connect").assertIsNotEnabled()
        compose.onNodeWithTag("weight-record-0").assertDoesNotExist()
    }

    @Test fun recreationClosesGatewayAndDiscardsConsentAndResults() {
        val fake = FakeGateway()
        launch(fake)
        connect()
        fake.loginReady.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("read-weights").performClick()
        scenario!!.recreate()
        assertTrue(fake.closed)
        compose.onNodeWithTag("connect").assertIsNotEnabled()
        compose.onNodeWithTag("weight-record-0").assertDoesNotExist()
        compose.onNodeWithTag("read-weights").assertDoesNotExist()
    }

    @Test fun cancelledOldLoginCannotClearAReplacementConnection() {
        val old = FakeGateway(lateFailure = true)
        val replacement = FakeGateway()
        var created = 0
        MainActivity.gatewayFactory = { if (created++ == 0) old else replacement }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        connect()
        compose.onNodeWithTag("disconnect").performClick()
        connect()
        old.loginReady.complete(Unit)
        compose.waitForIdle()
        assertTrue(old.closed)
        assertFalse(replacement.closed)
        compose.onNodeWithTag("open-login").assertIsDisplayed()
        compose.onNodeWithTag("probe-error").assertDoesNotExist()
        replacement.loginReady.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("read-weights").assertIsDisplayed()
    }

    @Test fun backClosesTheConnectionAndActivity() {
        val fake = FakeGateway()
        launch(fake)
        connect()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // finish() schedules a platform lifecycle transition outside Compose's idle clock.
        compose.waitUntil(timeoutMillis = 5_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        assertTrue(fake.closed)
        assertEquals(Lifecycle.State.DESTROYED, scenario!!.state)
    }

    private fun launch(fake: FakeGateway) {
        MainActivity.gatewayFactory = { fake }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun connect() {
        compose.onNodeWithTag("consent").performScrollTo().performClick()
        compose.onNodeWithTag("connect").performClick()
        compose.onNodeWithTag("open-login").assertIsDisplayed()
    }

    private class FakeGateway(private val lateFailure: Boolean = false) : ProbeGateway {
        val loginReady = CompletableDeferred<Unit>()
        var closed = false
        var reads = 0

        override suspend fun beginLogin() = LoginChallenge(
            loginUrl = "https://account.xiaomi.com/fixture",
            qrImage = null,
            expiresAtElapsedMillis = SystemClock.elapsedRealtime() + 60_000L,
            pollingUrl = "https://account.xiaomi.com/fixture-poll",
            owner = this,
        )

        override suspend fun awaitLogin(challenge: LoginChallenge): XiaomiSession {
            if (lateFailure) {
                withContext(NonCancellable) {
                    loginReady.await()
                    throw ProbeException(ProbeProblem.NETWORK)
                }
            }
            loginReady.await()
            return XiaomiSession("fixture", "fixture", "fixture", "fixture", this)
        }

        override suspend fun readRecentWeights(session: XiaomiSession): WeightReadResult {
            reads++
            return WeightReadResult(
                records = listOf(WeightRecord(Instant.parse("2026-09-20T01:02:00Z"), 70.2, null, 55.1, "fixture")),
                rejectedCount = 0,
            )
        }

        override fun close() { closed = true }
    }
}
