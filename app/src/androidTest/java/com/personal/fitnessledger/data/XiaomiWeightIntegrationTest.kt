package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.fitnessledger.xiaomiprobe.XiaomiSession
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightRecord
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Synthetic records only. Nothing in this suite authenticates to Xiaomi. */
@RunWith(AndroidJUnit4::class)
class XiaomiWeightIntegrationTest {
    private lateinit var context:Context
    private lateinit var database:FitnessDatabase
    private val account="a".repeat(64)
    private val now=Instant.parse("2026-09-21T12:00:00Z").toEpochMilli()
    private fun record(seconds:Long=now/1000-60, weight:Double=70.25, fat:Double?=19.25, water:Double?=null)=WeightRecord(Instant.ofEpochSecond(seconds),weight,fat,water,"synthetic-S400")
    private fun result(vararg records:WeightRecord)=WeightReadResult(records.toList(),0)

    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        LedgerBackupManager.recoverInterruptedRestore(context)
        XiaomiSyncScheduler.cancel(context)
        XiaomiSessionStore(context).clear()
        context.deleteDatabase("fitness_ledger.db")
        context.getSharedPreferences("fitness_settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("workout_planner",Context.MODE_PRIVATE).edit().clear().commit()
        database=FitnessDatabase(context)
    }
    @After fun cleanup() { database.close(); XiaomiSessionStore(context).clear(); XiaomiSyncScheduler.cancel(context); context.deleteDatabase("fitness_ledger.db") }

    @Test fun repeatedSyncIsIdempotentAndSameDayMeasurementsRemainSeparate() {
        val rows=result(record(),record(now/1000-3600,71.0))
        val first=XiaomiWeightLedger.import(database.writableDatabase,account,rows,now,ZoneOffset.UTC)
        val second=XiaomiWeightLedger.import(database.writableDatabase,account,rows,now,ZoneOffset.UTC)
        assertEquals(2,first.inserted); assertEquals(0,second.inserted); assertEquals(2,second.unchanged)
        val saved=XiaomiWeightLedger.list(database.readableDatabase)
        assertEquals(2,saved.size); assertEquals(saved[0].date,saved[1].date)
        assertEquals(70.25,saved.last().weightKg,0.0); assertNull(saved.last().waterPercent)
    }
    @Test fun cloudRevisionUpdatesOriginalButKeepsManualWaistAndProfile() {
        val reference=database.getProfile()
        database.saveBodyMeasurement(BodyMeasurement(date=LocalDate.of(2026,9,21),weightKg=73.0,waistCm=85.0))
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record()),now,ZoneOffset.UTC)
        val changed=XiaomiWeightLedger.import(database.writableDatabase,account,result(record(weight=70.3,fat=null,water=52.1)),now,ZoneOffset.UTC)
        assertEquals(1,changed.updated)
        assertEquals(85.0,database.listBodyMeasurements().single().waistCm!!,0.0)
        assertEquals(73.0,database.listBodyMeasurements().single().weightKg,0.0)
        assertEquals(reference,database.getProfile())
        assertNull(XiaomiWeightLedger.list(database.readableDatabase).single().bodyFatPercent)
    }
    @Test fun hiddenRecordDoesNotReappearOnDuplicateOrRevision() {
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record()),now)
        val id=XiaomiWeightLedger.list(database.readableDatabase).single().id
        assertTrue(XiaomiWeightLedger.hide(database.writableDatabase,id))
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record(weight=70.4)),now)
        assertTrue(XiaomiWeightLedger.list(database.readableDatabase).isEmpty())
    }
    @Test fun accountSwitchCannotMixLedgers() {
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record()),now)
        assertThrows(IllegalStateException::class.java) { XiaomiWeightLedger.import(database.writableDatabase,"b".repeat(64),result(record(weight=99.0)),now) }
        assertEquals(account,XiaomiWeightLedger.status(database.readableDatabase).accountKey)
        assertEquals(70.25,XiaomiWeightLedger.list(database.readableDatabase).single().weightKg,0.0)
    }
    @Test fun invalidSecondRowRollsBackWholeBatchAndSyncStatus() {
        assertThrows(IllegalArgumentException::class.java) { XiaomiWeightLedger.import(database.writableDatabase,account,result(record(),record(now/1000+10)),now) }
        assertNull(XiaomiWeightLedger.status(database.readableDatabase).accountKey)
        assertTrue(XiaomiWeightLedger.list(database.readableDatabase).isEmpty())
    }
    @Test fun storedLocalDateIsStableWhenImportTimeZoneChanges() {
        val nearMidnight=record(Instant.parse("2026-09-20T23:50:00Z").epochSecond)
        XiaomiWeightLedger.import(database.writableDatabase,account,result(nearMidnight),now,ZoneOffset.ofHours(8))
        XiaomiWeightLedger.import(database.writableDatabase,account,result(nearMidnight),now,ZoneOffset.ofHours(-8))
        val saved=XiaomiWeightLedger.list(database.readableDatabase).single()
        assertEquals(LocalDate.of(2026,9,21),saved.date); assertEquals(28800,saved.cloudOffsetSeconds)
    }
    @Test fun v10DatabaseUpgradePreservesManualRows() {
        database.saveBodyMeasurement(BodyMeasurement(date=LocalDate.of(2026,9,20),weightKg=75.0,waistCm=84.0))
        database.writableDatabase.execSQL("DROP TABLE xiaomi_weights")
        database.writableDatabase.execSQL("DROP TABLE xiaomi_sync")
        database.writableDatabase.version=10
        database.close(); database=FitnessDatabase(context)
        assertEquals(11,database.readableDatabase.version)
        assertEquals(84.0,database.listBodyMeasurements().single().waistCm!!,0.0)
        assertTrue(XiaomiWeightLedger.list(database.readableDatabase).isEmpty())
    }
    @Test fun credentialsEncryptedAndDisconnectInvalidatesLateWriter() {
        val store=XiaomiSessionStore(context)
        val marker="synthetic-service-token-never-log"
        store.save(XiaomiSession(marker,java.util.Base64.getEncoder().encodeToString(ByteArray(32)),"123456","synthetic-cuser",Any()),false)
        val saved=requireNotNull(store.read())
        assertTrue(saved.payload.contains(marker))
        assertFalse(context.getSharedPreferences("xiaomi_cloud_credentials",Context.MODE_PRIVATE).all.toString().contains(marker))
        assertFalse(saved.toString().contains(marker))
        store.clear()
        assertNull(store.read())
        assertThrows(IllegalStateException::class.java) { store.whileCurrent(saved.generation) { error("Must not enter") } }
    }
    @Test fun cancelledLoginCannotPersistAfterEncryption() {
        val store=XiaomiSessionStore(context)
        assertThrows(IllegalStateException::class.java) {
            store.save(XiaomiSession("synthetic","synthetic","123456","cuser",Any()),false,"cancelled") { false }
        }
        assertNull(store.read())
    }
    @Test fun backupRoundTripKeepsCloudOriginalsAndTombstonesButClearsCredentials() {
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record(),record(now/1000-3600)),now)
        XiaomiWeightLedger.hide(database.writableDatabase,XiaomiWeightLedger.list(database.readableDatabase).first().id)
        val store=XiaomiSessionStore(context)
        store.save(XiaomiSession("synthetic-service","synthetic-security","123456","synthetic-cuser",Any()),false)
        val pass="synthetic-passphrase".toCharArray()
        val output=ByteArrayOutputStream()
        LedgerBackupManager(context).use { it.exportEncrypted(output,pass) }
        val generation=FitnessDatabase.currentRestoreGeneration()
        LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(output.toByteArray()),pass) }
        assertTrue(FitnessDatabase.currentRestoreGeneration()>generation)
        assertEquals(1,XiaomiWeightLedger.list(database.readableDatabase).size)
        assertEquals(19.25,XiaomiWeightLedger.list(database.readableDatabase).single().bodyFatPercent!!,0.0)
        assertNull(store.read())
        XiaomiWeightLedger.import(database.writableDatabase,account,result(record(),record(now/1000-3600)),now)
        assertEquals(1,XiaomiWeightLedger.list(database.readableDatabase).size)
    }
}
