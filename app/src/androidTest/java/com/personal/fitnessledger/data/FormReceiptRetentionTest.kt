package com.personal.fitnessledger.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormReceiptRetentionTest {
    private lateinit var context: Context
    private lateinit var database: FitnessDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = FitnessDatabase(context)
        database.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun receiptRowsKeepOnlyOpaqueMetadataAfterBusinessContentIsDeleted() {
        val bodyId = database.saveBodyMeasurement(
            BodyMeasurement(
                date = LocalDate.of(2000, 1, 1),
                weightKg = 83.25,
                waistCm = 88.75,
            ),
            formCommitId = "opaque-body-form",
        )
        val manualDraft = privateMealDraft()
        assertTrue(database.saveDraft(manualDraft, manualFormId = "opaque-manual-form"))

        assertTrue(database.deleteBodyMeasurement(bodyId))
        database.discardDraft(manualDraft.id)
        assertTrue(database.listBodyMeasurements().isEmpty())
        assertNull(database.latestDraft())

        val db = database.writableDatabase
        assertEquals(
            setOf("form_id", "measurement_id", "committed_at", "cleanup_confirmed"),
            tableColumns(db, "body_measurement_form_commits"),
        )
        assertEquals(
            setOf("form_id", "draft_id", "converted_at", "cleanup_confirmed"),
            tableColumns(db, "manual_food_form_conversions"),
        )
        val receiptText = buildList<String> {
            db.rawQuery("SELECT * FROM body_measurement_form_commits", null).use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { column -> add(cursor.getString(column)) }
                }
            }
            db.rawQuery("SELECT * FROM manual_food_form_conversions", null).use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { column -> add(cursor.getString(column)) }
                }
            }
        }.joinToString("|")
        assertFalse(receiptText.contains("83.25"))
        assertFalse(receiptText.contains("88.75"))
        assertFalse(receiptText.contains(PRIVATE_FOOD_NAME))
        assertFalse(receiptText.contains("321.5"))

        val expiredAt = System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L
        db.execSQL(
            "UPDATE body_measurement_form_commits SET committed_at = ?",
            arrayOf<Any?>(expiredAt),
        )
        db.execSQL(
            "UPDATE manual_food_form_conversions SET converted_at = ?",
            arrayOf<Any?>(expiredAt),
        )
        val repository = FitnessRepository(context)
        assertNull(repository.bodyMeasurementFormDraft())
        assertNull(repository.manualFoodFormDraft())
        assertEquals(0, rowCount(db, "body_measurement_form_commits"))
        assertEquals(0, rowCount(db, "manual_food_form_conversions"))
    }

    @Test
    fun receiptWritesEnforceHardCapacityAndRetainTheCurrentForm() {
        val db = database.writableDatabase
        val bodyInsert = db.compileStatement(
            "INSERT INTO body_measurement_form_commits(form_id,measurement_id,committed_at) VALUES(?,?,?)",
        )
        val manualInsert = db.compileStatement(
            "INSERT INTO manual_food_form_conversions(form_id,draft_id,converted_at) VALUES(?,?,?)",
        )
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            repeat(FORM_RECEIPT_MAX_ROWS_PER_TABLE) { index ->
                val timestamp = now - FORM_RECEIPT_MAX_ROWS_PER_TABLE + index
                bodyInsert.clearBindings()
                bodyInsert.bindString(1, "body-fill-$index")
                bodyInsert.bindLong(2, index + 1L)
                bodyInsert.bindLong(3, timestamp)
                bodyInsert.executeInsert()

                manualInsert.clearBindings()
                manualInsert.bindString(1, "manual-fill-$index")
                manualInsert.bindString(2, "draft-fill-$index")
                manualInsert.bindLong(3, timestamp)
                manualInsert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            bodyInsert.close()
            manualInsert.close()
        }

        val currentBodyId = database.saveBodyMeasurement(
            BodyMeasurement(date = LocalDate.of(2000, 1, 2), weightKg = 80.0, waistCm = null),
            formCommitId = "body-current",
        )
        val currentManualDraft = privateMealDraft().copy(id = "manual-current-draft", commitId = "manual-current-commit")
        assertTrue(database.saveDraft(currentManualDraft, manualFormId = "manual-current"))

        assertEquals(
            FORM_RECEIPT_MAX_ROWS_PER_TABLE,
            rowCount(db, "body_measurement_form_commits"),
        )
        assertEquals(
            FORM_RECEIPT_MAX_ROWS_PER_TABLE,
            rowCount(db, "manual_food_form_conversions"),
        )
        assertEquals(currentBodyId, database.bodyMeasurementCommitId("body-current"))
        assertEquals(currentManualDraft.id, database.manualFoodFormConversionDraftId("manual-current"))
        assertNull(database.bodyMeasurementCommitId("body-fill-0"))
        assertNull(database.manualFoodFormConversionDraftId("manual-fill-0"))
    }

    @Test
    fun successfulCleanupConfirmsOnlyTheNamedReceipt() {
        val now = System.currentTimeMillis()
        val db = database.writableDatabase
        db.execSQL(
            "INSERT INTO body_measurement_form_commits(form_id,measurement_id,committed_at) " +
                "VALUES('body-a',1,?),('body-b',2,?)",
            arrayOf<Any?>(now, now),
        )
        db.execSQL(
            "INSERT INTO manual_food_form_conversions(form_id,draft_id,converted_at) " +
                "VALUES('manual-a','draft-a',?),('manual-b','draft-b',?)",
            arrayOf<Any?>(now, now),
        )

        database.confirmBodyMeasurementRawFormCleanup("body-a")
        database.confirmManualFoodRawFormCleanup("manual-a")

        assertEquals(1, cleanupState("body_measurement_form_commits", "body-a"))
        assertEquals(0, cleanupState("body_measurement_form_commits", "body-b"))
        assertEquals(1, cleanupState("manual_food_form_conversions", "manual-a"))
        assertEquals(0, cleanupState("manual_food_form_conversions", "manual-b"))
    }

    @Test
    fun capacityKeepsNewestUnconfirmedRowidWhenItsClockTimestampMovedBackwards() {
        val db = database.writableDatabase
        val insert = db.compileStatement(
            "INSERT INTO body_measurement_form_commits(form_id,measurement_id,committed_at) VALUES(?,?,?)",
        )
        db.beginTransaction()
        try {
            repeat(FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1) { index ->
                insert.clearBindings()
                insert.bindString(1, "unconfirmed-$index")
                insert.bindLong(2, index + 1L)
                insert.bindLong(
                    3,
                    if (index == FORM_RECEIPT_MAX_ROWS_PER_TABLE) 1L else 10_000L + index,
                )
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            insert.close()
        }

        database.pruneBodyMeasurementCommitReceipts()

        assertEquals(FORM_RECEIPT_MAX_ROWS_PER_TABLE, rowCount(db, "body_measurement_form_commits"))
        assertEquals(
            (FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1).toLong(),
            database.bodyMeasurementCommitId("unconfirmed-${FORM_RECEIPT_MAX_ROWS_PER_TABLE}"),
        )
        assertNull(database.bodyMeasurementCommitId("unconfirmed-0"))
    }

    @Test
    fun openingDatabaseNeverExpiresReceiptsBeforeRawFormCleanupIsKnown() {
        val oldTimestamp = System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L
        database.writableDatabase.execSQL(
            "INSERT INTO body_measurement_form_commits(form_id,measurement_id,committed_at) VALUES(?,?,?)",
            arrayOf<Any?>("body-sleep", 41L, oldTimestamp),
        )
        database.writableDatabase.execSQL(
            "INSERT INTO manual_food_form_conversions(form_id,draft_id,converted_at) VALUES(?,?,?)",
            arrayOf<Any?>("manual-sleep", "draft-sleep", oldTimestamp),
        )
        database.close()
        database = FitnessDatabase(context)

        assertEquals(41L, database.bodyMeasurementCommitId("body-sleep"))
        assertEquals("draft-sleep", database.manualFoodFormConversionDraftId("manual-sleep"))

        val repository = FitnessRepository(context)
        assertNull(repository.bodyMeasurementFormDraft())
        assertNull(repository.manualFoodFormDraft())
        assertNull(database.bodyMeasurementCommitId("body-sleep"))
        assertNull(database.manualFoodFormConversionDraftId("manual-sleep"))
    }

    @Test
    fun failedPreferenceCleanupRestoresRawFormsAcrossRepeatedReadsAndProcessRecreation() {
        val preferences = DivergingSharedPreferences()
        val repository = FitnessRepository(PreferencesContext(context, preferences))
        val bodyForm = bodyForm(id = "body-failed-cleanup")
        val manualForm = manualForm(id = "manual-failed-cleanup")
        assertTrue(repository.saveBodyMeasurementFormDraft(bodyForm))
        assertTrue(repository.saveManualFoodFormDraft(manualForm))

        val bodyId = repository.saveBodyMeasurement(
            BodyMeasurement(date = bodyForm.date, weightKg = 78.0, waistCm = 84.0),
            formCommitId = bodyForm.id,
        )
        val converted = privateMealDraft().copy(
            id = "failed-cleanup-draft",
            commitId = "failed-cleanup-draft-commit",
        )
        assertTrue(repository.saveManualDraftFromForm(converted, manualForm.id))
        repository.discardDraft(converted)

        val expiredAt = System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L
        database.writableDatabase.execSQL(
            "UPDATE body_measurement_form_commits SET committed_at = ? WHERE form_id = ?",
            arrayOf<Any?>(expiredAt, bodyForm.id),
        )
        database.writableDatabase.execSQL(
            "UPDATE manual_food_form_conversions SET converted_at = ? WHERE form_id = ?",
            arrayOf<Any?>(expiredAt, manualForm.id),
        )

        // Android can expose remove/tombstone in memory even when commit() says
        // disk persistence failed. Exercise the same repository twice to prove
        // neither the transient null nor the old age acknowledges the receipts.
        repeat(2) {
            preferences.failNextCommit()
            assertNull(repository.bodyMeasurementFormDraft())
            assertTrue(preferences.containsStringValue(bodyForm.id))
            assertEquals(0, cleanupState("body_measurement_form_commits", bodyForm.id))

            preferences.failNextCommit()
            assertNull(repository.manualFoodFormDraft())
            assertTrue(preferences.containsStringValue(manualForm.id))
            assertEquals(0, cleanupState("manual_food_form_conversions", manualForm.id))
        }

        // A new preferences instance reads only the fake's durable snapshot,
        // modelling process recreation after the failed clear.
        val recreatedPreferences = DivergingSharedPreferences(preferences.durableSnapshot())
        val recreated = FitnessRepository(PreferencesContext(context, recreatedPreferences))
        recreatedPreferences.failNextCommit()
        assertNull(recreated.bodyMeasurementFormDraft())
        recreatedPreferences.failNextCommit()
        assertNull(recreated.manualFoodFormDraft())
        assertEquals(0, cleanupState("body_measurement_form_commits", bodyForm.id))
        assertEquals(0, cleanupState("manual_food_form_conversions", manualForm.id))

        val invalidRetry = BodyMeasurement(
            date = LocalDate.now().plusDays(1),
            weightKg = 1.0,
            waistCm = null,
        )
        assertTrue(invalidRetry.validationError() != null)
        assertEquals(bodyId, recreated.saveBodyMeasurement(invalidRetry, formCommitId = bodyForm.id))
        assertFalse(
            recreated.saveManualDraftFromForm(
                privateMealDraft().copy(id = "duplicate-draft", commitId = "duplicate-commit"),
                manualForm.id,
            ),
        )
        assertEquals(1, database.listBodyMeasurements().size)
        assertNull(database.latestDraft())
    }

    @Test
    fun v9MigrationPreservesNewestRawReceiptBeforeRepositoryCoordinationAndThenCapsIt() {
        val preferences = DivergingSharedPreferences()
        val wrappedContext = PreferencesContext(context, preferences)
        val currentRaw = bodyForm(id = "v9-current-raw")
        // This writes preferences only; the repository's helper DB stays unopened.
        assertTrue(FitnessRepository(wrappedContext).saveBodyMeasurementFormDraft(currentRaw))

        database.close()
        assertTrue(context.deleteDatabase(DATABASE_NAME))
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DATABASE_NAME), null)
        legacy.execSQL(
            "CREATE TABLE body_measurement_form_commits(" +
                "form_id TEXT PRIMARY KEY,measurement_id INTEGER NOT NULL,committed_at INTEGER NOT NULL)",
        )
        legacy.execSQL(
            "CREATE TABLE manual_food_form_conversions(" +
                "form_id TEXT PRIMARY KEY,draft_id TEXT NOT NULL,converted_at INTEGER NOT NULL)",
        )
        val insert = legacy.compileStatement(
            "INSERT INTO body_measurement_form_commits(form_id,measurement_id,committed_at) VALUES(?,?,?)",
        )
        legacy.beginTransaction()
        try {
            repeat(FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1) { index ->
                val isCurrentRaw = index == FORM_RECEIPT_MAX_ROWS_PER_TABLE
                insert.clearBindings()
                insert.bindString(1, if (isCurrentRaw) currentRaw.id else "v9-receipt-$index")
                insert.bindLong(2, index + 1L)
                // The current raw is newest by rowid but deliberately oldest by
                // wall clock, covering clock rollback during the v9 lifetime.
                insert.bindLong(
                    3,
                    if (isCurrentRaw) {
                        System.currentTimeMillis() - 1_000L
                    } else {
                        System.currentTimeMillis() + index
                    },
                )
                insert.executeInsert()
            }
            legacy.setTransactionSuccessful()
        } finally {
            legacy.endTransaction()
            insert.close()
        }
        legacy.version = 9
        legacy.close()

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase
        assertEquals(11, upgraded.version)
        assertTrue("cleanup_confirmed" in tableColumns(upgraded, "body_measurement_form_commits"))
        assertTrue("cleanup_confirmed" in tableColumns(upgraded, "manual_food_form_conversions"))
        // onOpen deliberately does not prune before SharedPreferences is read.
        assertEquals(FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1, rowCount(upgraded, "body_measurement_form_commits"))
        assertEquals(0, cleanupState("body_measurement_form_commits", currentRaw.id))
        assertEquals(
            (FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1).toLong(),
            database.bodyMeasurementCommitId(currentRaw.id),
        )

        assertNull(FitnessRepository(wrappedContext).bodyMeasurementFormDraft())
        assertEquals(FORM_RECEIPT_MAX_ROWS_PER_TABLE, rowCount(upgraded, "body_measurement_form_commits"))
        assertEquals(
            (FORM_RECEIPT_MAX_ROWS_PER_TABLE + 1).toLong(),
            database.bodyMeasurementCommitId(currentRaw.id),
        )
    }

    private fun privateMealDraft() = MealDraft(
        id = "opaque-draft-id",
        commitId = "opaque-draft-commit",
        photoUri = "",
        state = DraftState.EDITING,
        items = listOf(
            FoodDraftItem(
                name = PRIVATE_FOOD_NAME,
                grams = 100.0,
                gramsMin = 100.0,
                gramsMax = 100.0,
                per100g = Nutrition(kcal = 321.5, carbsG = 30.0, proteinG = 20.0, fatG = 10.0),
                sourceName = "private-source",
                portionBasis = PortionBasis.USER_WEIGHT,
                evidenceTier = EvidenceTier.C,
            ),
        ),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "private-reason",
        unresolvedFlags = emptySet(),
        providerLabel = "private-provider",
        analysisMode = AnalysisMode.MANUAL,
    )

    private fun bodyForm(id: String) = BodyMeasurementFormDraft(
        id = id,
        measurementId = 0L,
        date = LocalDate.now().minusDays(1),
        weightText = "78.0",
        waistText = "84.0",
        revision = 1L,
        updatedAtMillis = 1_000L,
    )

    private fun manualForm(id: String) = ManualFoodFormDraft(
        id = id,
        itemId = "$id-item",
        targetDate = LocalDate.now(),
        initialGrams = 100.0,
        initialGramsMin = 80.0,
        initialGramsMax = 120.0,
        initialPortionBasis = PortionBasis.USER_ESTIMATE,
        name = "进程恢复测试食物",
        gramsText = "100",
        kcalText = "321.5",
        carbsText = "30",
        proteinText = "20",
        fatText = "10",
        sourceName = "用户手工输入",
        weighed = false,
        useLabelKcal = true,
        isDirty = true,
        revision = 1L,
        updatedAtMillis = 1_000L,
    )

    private fun cleanupState(table: String, formId: String): Int =
        database.writableDatabase.rawQuery(
            "SELECT cleanup_confirmed FROM $table WHERE form_id = ?",
            arrayOf(formId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun tableColumns(
        db: android.database.sqlite.SQLiteDatabase,
        table: String,
    ): Set<String> = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    private fun rowCount(db: android.database.sqlite.SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
        const val PRIVATE_FOOD_NAME = "不应进入回执的私密食物名"
    }
}

private class PreferencesContext(
    base: Context,
    private val settings: SharedPreferences,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name == "fitness_settings") settings else super.getSharedPreferences(name, mode)
}

/**
 * Models SharedPreferencesImpl's important failure boundary: commit changes
 * become visible in the process-local map before the disk write reports false.
 */
private class DivergingSharedPreferences(
    durableSeed: Map<String, Any?> = emptyMap(),
) : SharedPreferences {
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val memory = durableSeed.toMutableMap()
    private var durable = durableSeed.toMutableMap()
    private var failNext = false

    @Synchronized
    fun failNextCommit() {
        failNext = true
    }

    @Synchronized
    fun durableSnapshot(): Map<String, Any?> = durable.toMap()

    @Synchronized
    fun containsStringValue(fragment: String): Boolean =
        memory.values.filterIsInstance<String>().any { fragment in it }

    @Synchronized
    override fun getAll(): MutableMap<String, *> = memory.toMutableMap()

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? =
        memory[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (memory[key] as? Set<String>)?.toMutableSet() ?: defValues

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int = memory[key] as? Int ?: defValue

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long = memory[key] as? Long ?: defValue

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float = memory[key] as? Float ?: defValue

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        memory[key] as? Boolean ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = memory.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    @Synchronized
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners += listener
    }

    @Synchronized
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            requireNotNull(key)
            changes[key] = value ?: REMOVED
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            requireNotNull(key)
            changes[key] = values?.toSet() ?: REMOVED
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = REMOVED
        }

        override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }

        override fun commit(): Boolean = applyChanges(writeDurably = true)

        override fun apply() {
            applyChanges(writeDurably = false)
        }

        private fun applyChanges(writeDurably: Boolean): Boolean {
            val changedKeys: Set<String>
            val succeeded: Boolean
            synchronized(this@DivergingSharedPreferences) {
                if (clearFirst) memory.clear()
                changes.forEach { (key, value) ->
                    if (value === REMOVED) memory.remove(key) else memory[key] = value
                }
                changedKeys = changes.keys.toSet()
                succeeded = if (writeDurably && failNext) {
                    failNext = false
                    false
                } else {
                    durable = memory.toMutableMap()
                    true
                }
            }
            changedKeys.forEach { key ->
                listeners.toList().forEach { listener ->
                    listener.onSharedPreferenceChanged(this@DivergingSharedPreferences, key)
                }
            }
            return succeeded
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
