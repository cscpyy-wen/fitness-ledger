package com.personal.fitnessledger.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.personal.fitnessledger.data.BodyMeasurement
import com.personal.fitnessledger.data.XiaomiSyncState
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Synthetic UI fixtures only: never connects an account or queries Xiaomi. */
class XiaomiBodyComposeTest {
    @get:Rule val composeRule=createComposeRule()
    private val today=LocalDate.of(2026,9,21)
    private val epoch=today.atTime(7,30).toEpochSecond(ZoneOffset.ofHours(8))
    private val cloud=BodyMeasurement(id=7,date=today,weightKg=70.25,waistCm=null,
        cloudMeasuredAtSeconds=epoch,cloudOffsetSeconds=28800,bodyFatPercent=19.15,waterPercent=null)

    @Test fun connectionRequiresExplicitConsent() {
        var connections=0
        composeRule.setContent {
            FitnessLedgerTheme { XiaomiConnectionCard(XiaomiSyncState(),false,false,null,
                onConnect={connections++},onLogin={},onCancelLogin={},onSync={},onBackground={},onDisconnect={}) }
        }
        composeRule.onNodeWithTag("xiaomi-connect").performClick()
        assertEquals(0,connections)
        composeRule.onNodeWithText("连接你的小米账号").assertIsDisplayed()
        composeRule.onNodeWithText("暂不连接").performClick()
        assertEquals(0,connections)
        composeRule.onNodeWithTag("xiaomi-connect").performClick()
        composeRule.onNodeWithTag("xiaomi-consent-confirm").performClick()
        assertEquals(1,connections)
    }

    @Test fun settingsAreCollapsedAndDisconnectRequiresConfirmation() {
        var syncDays=0
        var disconnected=false
        composeRule.setContent {
            FitnessLedgerTheme { XiaomiConnectionCard(XiaomiSyncState(connected=true,background=true),false,false,null,
                onConnect={},onLogin={},onCancelLogin={},onSync={syncDays=it},onBackground={},onDisconnect={disconnected=true}) }
        }
        composeRule.onNodeWithTag("xiaomi-background").assertDoesNotExist()
        composeRule.onNodeWithTag("xiaomi-sync-now").performClick()
        assertEquals(7,syncDays)
        composeRule.onNodeWithText("同步设置").performClick()
        composeRule.onNodeWithTag("xiaomi-background").assertIsDisplayed()
        composeRule.onNodeWithText("补齐近 8 周").performClick()
        assertEquals(56,syncDays)
        composeRule.onNodeWithText("断开连接").performClick()
        assertFalse(disconnected)
        composeRule.onNodeWithText("断开").performClick()
        assertTrue(disconnected)
    }

    @Test fun originalPrecisionMissingWaterAndHideStayExplicit() {
        var hidden=0L
        setBody(onHide={hidden=it})
        composeRule.onNodeWithText("19.15%").assertIsDisplayed()
        composeRule.onNodeWithText("—").assertExists()
        composeRule.onNodeWithTag("body-history-list").performScrollToNode(hasContentDescription("隐藏 $today 小米记录"))
        composeRule.onNodeWithContentDescription("隐藏 $today 小米记录").performClick()
        assertEquals(0L,hidden)
        composeRule.onNodeWithText("隐藏这条小米记录？").assertIsDisplayed()
        composeRule.onNodeWithText("隐藏").performClick()
        assertEquals(7L,hidden)
        composeRule.onNodeWithTag("body-history-list").performScrollToNode(hasTestTag("body-weight-trend-chart"))
        composeRule.onNodeWithTag("body-weight-trend-chart")
            .assertContentDescriptionContains("原始体重 70.25 kg",substring=true)
    }

    @Test fun visualLightConnectedBody() {
        setBody()
        capture("xiaomi-main-light.png")
        composeRule.onNodeWithTag("body-history-list").performScrollToNode(hasTestTag("body-weight-trend-chart"))
        capture("xiaomi-main-trend.png")
    }

    @Test fun visualDarkLargerTextBody() {
        setBody(dark=true,fontScale=1.3f)
        composeRule.onNodeWithTag("xiaomi-sync-now").assertIsDisplayed()
        capture("xiaomi-main-dark-large.png")
    }

    private fun setBody(dark:Boolean=false,fontScale:Float=1f,onHide:(Long)->Unit={}) {
        val rows=(13 downTo 1).map { day -> cloud.copy(id=100L+day,date=today.minusDays(day.toLong()),
            weightKg=(7025+day*7)/100.0,cloudMeasuredAtSeconds=epoch-day*86400) }+cloud
        composeRule.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                FitnessLedgerTheme(darkTheme=dark) {
                    Surface(Modifier.fillMaxSize()) {
                        BodyScreen(AppUiState(isInitialized=true,loadedDate=today,measurements=rows),
                            onOpenMeasurement={_,_->},onUpdateMeasurement={_,_->},onSaveMeasurement={},
                            onDiscardMeasurement={},onDeleteMeasurement={},onHideXiaomiMeasurement=onHide,
                            connectionCard={XiaomiConnectionCard(XiaomiSyncState(connected=true,background=true,lastSyncMillis=epoch*1000),
                                false,false,null,onConnect={},onLogin={},onCancelLogin={},onSync={},onBackground={},onDisconnect={})})
                    }
                }
            }
        }
    }

    private fun capture(name:String) {
        composeRule.waitForIdle()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val bitmap=composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
