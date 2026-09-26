package com.personal.fitnessledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class XiaomiLedgerStatus(val accountKey: String? = null, val lastSyncMillis: Long? = null, val partial: Boolean = false)
data class XiaomiImportResult(val inserted: Int, val updated: Int, val unchanged: Int, val rejected: Int, val partial: Boolean)

/** Separate originals: never overwrite manual weight/waist, profile or nutrition targets. */
internal object XiaomiWeightLedger {
    val columns = linkedMapOf(
        "xiaomi_sync" to listOf("id", "account_key", "last_sync_at", "partial"),
        "xiaomi_weights" to listOf("id", "account_key", "source_key", "measured_at", "recorded_date", "offset_seconds", "weight_kg", "body_fat_percent", "water_percent", "hidden"),
    )
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS xiaomi_sync (
            id INTEGER PRIMARY KEY CHECK(id=1), account_key TEXT NOT NULL UNIQUE CHECK(length(account_key)=64),
            last_sync_at INTEGER CHECK(last_sync_at IS NULL OR last_sync_at>0),
            partial INTEGER NOT NULL DEFAULT 0 CHECK(partial IN (0,1)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS xiaomi_weights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_key TEXT NOT NULL REFERENCES xiaomi_sync(account_key),
            source_key TEXT NOT NULL CHECK(length(source_key) BETWEEN 1 AND 256),
            measured_at INTEGER NOT NULL CHECK(measured_at>0),
            recorded_date TEXT NOT NULL CHECK(length(recorded_date)=10),
            offset_seconds INTEGER NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),
            weight_kg REAL NOT NULL CHECK(weight_kg BETWEEN 1 AND 500),
            body_fat_percent REAL CHECK(body_fat_percent IS NULL OR (body_fat_percent>0 AND body_fat_percent<=100)),
            water_percent REAL CHECK(water_percent IS NULL OR (water_percent>0 AND water_percent<=100)),
            hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN (0,1)),
            UNIQUE(account_key,source_key,measured_at))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_xiaomi_weight_time ON xiaomi_weights(measured_at DESC)")
    }

    fun status(db: SQLiteDatabase): XiaomiLedgerStatus = db.rawQuery("SELECT account_key,last_sync_at,partial FROM xiaomi_sync WHERE id=1", null).use {
        if (!it.moveToFirst()) XiaomiLedgerStatus() else XiaomiLedgerStatus(it.getString(0), if(it.isNull(1)) null else it.getLong(1), it.getInt(2)==1)
    }

    fun import(db: SQLiteDatabase, account: String, result: WeightReadResult, now: Long, zone: ZoneId = ZoneId.systemDefault()): XiaomiImportResult {
        require(account.matches(Regex("[0-9a-f]{64}")))
        require(now > 0)
        var inserted = 0; var updated = 0; var unchanged = 0
        db.beginTransaction()
        try {
            val existing = status(db).accountKey
            check(existing == null || existing == account) { "当前账本已绑定另一个小米账号，请用原账号连接，避免混入他人记录。" }
            if (existing == null) db.insertOrThrow("xiaomi_sync", null, ContentValues().apply { put("id",1); put("account_key", account) })
            for (record in result.records) {
                require(record.measuredAt.epochSecond in 1..(now / 1000))
                require(record.weightKg.isFinite() && record.weightKg in 1.0..500.0)
                require(listOfNotNull(record.bodyFatPercent, record.waterPercent).all { it.isFinite() && it>0 && it<=100 })
                require(record.source.length in 1..256 && record.source.none { it.isISOControl() })
                val args = arrayOf(account, record.source, record.measuredAt.epochSecond.toString())
                val prior = db.rawQuery("SELECT id,weight_kg,body_fat_percent,water_percent FROM xiaomi_weights WHERE account_key=? AND source_key=? AND measured_at=?", args).use { c ->
                    if(!c.moveToFirst()) null else Pair(c.getLong(0), listOf(c.getDouble(1), if(c.isNull(2)) null else c.getDouble(2), if(c.isNull(3)) null else c.getDouble(3)))
                }
                val values = ContentValues().apply {
                    put("weight_kg",record.weightKg)
                    if(record.bodyFatPercent==null) putNull("body_fat_percent") else put("body_fat_percent",record.bodyFatPercent)
                    if(record.waterPercent==null) putNull("water_percent") else put("water_percent",record.waterPercent)
                }
                if (prior == null) {
                    val local = record.measuredAt.atZone(zone)
                    values.put("account_key",account); values.put("source_key",record.source)
                    values.put("measured_at",record.measuredAt.epochSecond); values.put("recorded_date",local.toLocalDate().toString())
                    values.put("offset_seconds",local.offset.totalSeconds)
                    db.insertOrThrow("xiaomi_weights",null,values); inserted++
                } else if(prior.second != listOf(record.weightKg,record.bodyFatPercent,record.waterPercent)) {
                    // Keep original local date and local hide tombstone even when the upstream value changes.
                    db.update("xiaomi_weights",values,"id=?",arrayOf(prior.first.toString())); updated++
                } else unchanged++
            }
            db.update("xiaomi_sync",ContentValues().apply { put("last_sync_at",now); put("partial",if(result.incomplete) 1 else 0) },"id=1",null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return XiaomiImportResult(inserted,updated,unchanged,result.rejectedCount,result.incomplete)
    }

    fun list(db: SQLiteDatabase): List<BodyMeasurement> = db.rawQuery("SELECT id,recorded_date,weight_kg,measured_at,offset_seconds,body_fat_percent,water_percent FROM xiaomi_weights WHERE hidden=0 ORDER BY recorded_date,measured_at,id",null).use { c ->
        buildList { while(c.moveToNext()) add(BodyMeasurement(c.getLong(0),LocalDate.parse(c.getString(1)),c.getDouble(2),null,c.getLong(3),c.getInt(4),if(c.isNull(5)) null else c.getDouble(5),if(c.isNull(6)) null else c.getDouble(6))) }
    }
    fun hide(db: SQLiteDatabase,id:Long): Boolean {
        require(id>0)
        return db.update("xiaomi_weights",ContentValues().apply{put("hidden",1)},"id=?",arrayOf(id.toString()))==1
    }
    fun validateRestored(db: SQLiteDatabase) {
        val bound = status(db).accountKey
        require(bound==null || bound.matches(Regex("[0-9a-f]{64}")))
        db.rawQuery("SELECT account_key,source_key,measured_at,recorded_date,offset_seconds FROM xiaomi_weights",null).use { c ->
            while(c.moveToNext()) {
                require(c.getString(0)==bound)
                require(c.getString(1).none { it.isISOControl() })
                val originalDate=Instant.ofEpochSecond(c.getLong(2)).atOffset(ZoneOffset.ofTotalSeconds(c.getInt(4))).toLocalDate()
                require(originalDate==LocalDate.parse(c.getString(3)))
            }
        }
    }
}
