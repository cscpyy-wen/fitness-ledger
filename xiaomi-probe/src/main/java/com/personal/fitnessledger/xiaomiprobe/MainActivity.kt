// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Deliberately not a ViewModel: recreation discards the account session. */
class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var gateway: ProbeGateway? = null
    private var challenge: LoginChallenge? = null
    private var session: XiaomiSession? = null
    private var connectionGeneration = 0L
    private var uiState by mutableStateOf(ProbeUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) {
            clearConnection()
            finish()
        }
        setContent {
            ProbeTheme {
                ProbeScreen(
                    state = uiState,
                    onConsentChanged = { accepted ->
                        if (uiState.stage == ProbeStage.INTRO) {
                            uiState = uiState.copy(consentAccepted = accepted)
                        }
                    },
                    onConnect = ::connect,
                    onOpenLogin = ::openLogin,
                    onReadWeights = ::readWeights,
                    onDisconnect = ::clearConnection,
                )
            }
        }
    }

    private fun connect() {
        if (!uiState.consentAccepted || uiState.stage != ProbeStage.INTRO) return
        clearConnection()
        val currentGateway = gatewayFactory()
        val currentGeneration = connectionGeneration
        gateway = currentGateway
        uiState = ProbeUiState(stage = ProbeStage.CONNECTING, consentAccepted = true)
        operation = activityScope.launch {
            try {
                val currentChallenge = currentGateway.beginLogin()
                ensureActive()
                if (!isCurrent(currentGateway, currentGeneration)) return@launch
                challenge = currentChallenge
                val qr = currentChallenge.qrImage?.let { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } else {
                        null // Optional QR may be omitted; the browser action remains available.
                    }
                }
                uiState = uiState.copy(stage = ProbeStage.AWAITING_LOGIN, qrImage = qr)
                val authenticatedSession = currentGateway.awaitLogin(currentChallenge)
                ensureActive()
                if (!isCurrent(currentGateway, currentGeneration)) return@launch
                session = authenticatedSession
                discardChallenge()
                uiState = uiState.copy(stage = ProbeStage.AUTHENTICATED, qrImage = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                ensureActive()
                if (isCurrent(currentGateway, currentGeneration)) fail(failure.problem.userMessage)
            } catch (_: Exception) {
                ensureActive()
                if (isCurrent(currentGateway, currentGeneration)) {
                    fail("本次连接未能完成，已清除会话。请稍后重试。")
                }
            }
        }
    }

    private fun openLogin() {
        val currentChallenge = challenge ?: return
        val currentGeneration = connectionGeneration
        if (uiState.stage != ProbeStage.AWAITING_LOGIN) return
        try {
            // The gateway validates the HTTPS host and address before exposing this URL.
            // Passwords, OTPs and browser cookies never enter the probe's UI.
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(currentChallenge.loginUrl))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        } catch (_: ActivityNotFoundException) {
            if (challenge === currentChallenge && connectionGeneration == currentGeneration) {
                uiState = uiState.copy(errorMessage = "没有可打开登录页面的浏览器。可用另一台设备扫描二维码，或取消连接。")
            }
        } catch (_: SecurityException) {
            if (challenge === currentChallenge && connectionGeneration == currentGeneration) {
                fail("系统未允许打开小米登录页面，已清除本次连接。")
            }
        }
    }

    private fun readWeights() {
        if (uiState.stage != ProbeStage.AUTHENTICATED) return
        val currentGateway = gateway ?: return
        val currentSession = session ?: return
        val currentGeneration = connectionGeneration
        uiState = uiState.copy(stage = ProbeStage.READING, errorMessage = null)
        operation = activityScope.launch {
            try {
                val result = currentGateway.readRecentWeights(currentSession)
                ensureActive()
                if (!isCurrent(currentGateway, currentGeneration)) return@launch
                uiState = uiState.copy(
                    stage = ProbeStage.RESULTS,
                    records = result.records,
                    rejectedCount = result.rejectedCount,
                    incomplete = result.incomplete,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProbeException) {
                ensureActive()
                if (isCurrent(currentGateway, currentGeneration)) fail(failure.problem.userMessage)
            } catch (_: Exception) {
                ensureActive()
                if (isCurrent(currentGateway, currentGeneration)) {
                    fail("本次读取未能完成，已清除会话。没有向正式账本写入任何记录。")
                }
            }
        }
    }

    private fun fail(message: String) {
        clearConnection()
        uiState = uiState.copy(errorMessage = message)
    }

    private fun discardChallenge() {
        challenge?.qrImage?.fill(0)
        challenge = null
    }

    private fun clearConnection() {
        connectionGeneration++
        operation?.cancel()
        operation = null
        val previousGateway = gateway
        gateway = null
        discardChallenge()
        session = null
        uiState = ProbeUiState()
        try {
            previousGateway?.close()
        } catch (_: Exception) {
            // Local references must still be cleared if a transport's close fails.
        }
    }

    private fun isCurrent(expectedGateway: ProbeGateway, expectedGeneration: Long): Boolean =
        gateway === expectedGateway && connectionGeneration == expectedGeneration

    override fun onDestroy() {
        clearConnection()
        activityScope.cancel()
        super.onDestroy()
    }

    // No onStop clearing: official browser login temporarily backgrounds this Activity.
    internal companion object {
        /** Instrumentation replaces this with a fake; production always uses the real gateway. */
        var gatewayFactory: () -> ProbeGateway = { XiaomiCloudGateway() }
    }
}
