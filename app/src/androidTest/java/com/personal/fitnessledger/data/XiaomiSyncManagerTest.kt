package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.fitnessledger.xiaomiprobe.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class XiaomiSyncManagerTest {
    private lateinit var context:Context
    private lateinit var manager:XiaomiSyncManager
    private lateinit var transport:FakeTransport
    private val now=System.currentTimeMillis()/1000
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        LedgerBackupManager.recoverInterruptedRestore(context)
        XiaomiSyncScheduler.cancel(context)
        context.deleteDatabase("fitness_ledger.db")
        for(name in listOf("fitness_settings","workout_planner","xiaomi_cloud_credentials")) context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()
        XiaomiSessionStore(context).save(XiaomiSession("synthetic-token",Base64.getEncoder().encodeToString(ByteArray(32)),"123456","synthetic-cuser",Any()),false)
        transport=FakeTransport()
        XiaomiSyncManager.gatewayFactory={ XiaomiCloudGateway(transport) }
        manager=XiaomiSyncManager(context)
    }
    @After fun cleanup() {
        XiaomiSyncManager.gatewayFactory={XiaomiCloudGateway()}
        XiaomiSessionStore(context).clear(); XiaomiSyncScheduler.cancel(context)
        context.deleteDatabase("fitness_ledger.db")
    }
    @Test fun encryptedSessionToCloudToLedgerSavesExactlyOneOriginal()=runBlocking {
        manager.sync()
        assertEquals(7,transport.calls)
        FitnessRepository(context).use { assertEquals(70.25,it.listBodyMeasurements().single().weightKg,0.0) }
        assertTrue(manager.state.value.connected)
        assertFalse(manager.state.value.syncing)
        assertEquals(1L,manager.state.value.revision)
    }
    @Test fun disconnectWhileRequestPendingPreventsAnyLateSave()=runBlocking {
        transport.block=true
        val job=launch(Dispatchers.IO) { manager.sync() }
        transport.entered.await()
        manager.disconnect()
        transport.release.complete(Unit); job.join()
        FitnessRepository(context).use { assertTrue(it.listBodyMeasurements().isEmpty()) }
        assertFalse(manager.state.value.connected)
    }
    @Test fun restoringBackupWhileRequestPendingRejectsOldGeneration()=runBlocking {
        val output=ByteArrayOutputStream()
        val password="synthetic-test-backup".toCharArray()
        LedgerBackupManager(context).use { it.exportEncrypted(output,password) }
        transport.block=true
        val job=launch(Dispatchers.IO) { manager.sync() }
        transport.entered.await()
        withContext(Dispatchers.IO) { LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(output.toByteArray()),password) } }
        transport.release.complete(Unit); job.join()
        FitnessRepository(context).use { assertTrue(it.listBodyMeasurements().isEmpty()) }
        assertNull(XiaomiSessionStore(context).read())
    }
    @Test fun authenticationFailureClearsSessionButNeverDeletesSavedRows()=runBlocking {
        manager.sync()
        transport.authFailure=true
        manager.sync()
        assertNull(XiaomiSessionStore(context).read())
        assertFalse(manager.state.value.connected)
        assertTrue(manager.state.value.error.orEmpty().contains("过期"))
        FitnessRepository(context).use { assertEquals(70.25,it.listBodyMeasurements().single().weightKg,0.0) }
    }
    @Test fun backgroundJobDoesNotQueryWhenBackgroundDisabled()=runBlocking {
        manager.sync(backgroundJob=true)
        assertEquals(0,transport.calls)
    }
    private inner class FakeTransport:ProbeTransport {
        var block=false
        var authFailure=false
        var calls=0
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        override suspend fun execute(method:String,url:String,headers:Map<String,String>,body:ByteArray?,timeoutMillis:Int,maximumBytes:Int):HttpReply {
            assertEquals("/app/v1/data/get_fitness_data_by_time",URI(url).path)
            calls++
            if(block && calls==1) { entered.complete(Unit); release.await() }
            if(authFailure) return HttpReply(401,emptyMap(),ByteArray(0))
            val row=JSONObject().put("key","weight").put("time",now-60).put("sid","synthetic")
                .put("value",JSONObject().put("weight",70.25).put("body_fat_rate",19.1))
            return HttpReply(200,emptyMap(),JSONObject().put("code",0).put("result",JSONObject().put("data_list",JSONArray().put(row))).toString().toByteArray())
        }
        override fun close() {}
    }
}
