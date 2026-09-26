package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: FitnessDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = FitnessDatabase(context)
        database.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun manualDraftCommitsExactlyOnceAndCanBeDeleted() {
        val date = LocalDate.of(2026, 8, 27)
        val draft = validManualDraft()
        database.saveDraft(draft)

        assertEquals(0, database.mealsForDate(date).size)
        assertEquals(draft.id, database.latestDraft()?.id)

        val firstId = database.commitDraft(draft, date)
        val retryId = database.commitDraft(draft, date)

        assertEquals(firstId, retryId)
        assertNull(database.latestDraft())
        assertEquals(1, database.mealsForDate(date).size)
        assertEquals(165.0, database.nutritionForDate(date).kcal, 0.001)

        database.deleteMeal(firstId)
        assertEquals(0, database.mealsForDate(date).size)
        assertEquals(0.0, database.nutritionForDate(date).kcal, 0.001)
    }

    @Test
    fun persistedDraftCannotCommitToFutureLedgerAfterCalendarRollback() {
        val futureDate = LocalDate.now().plusDays(1)
        val draft = validManualDraft().copy(targetDate = futureDate)
        database.saveDraft(draft)
        database.close()
        database = FitnessDatabase(context)

        assertThrows(IllegalArgumentException::class.java) {
            database.commitDraft(requireNotNull(database.latestDraft()), futureDate)
        }

        assertEquals(draft.id, database.latestDraft()?.id)
        assertTrue(database.mealsForDate(futureDate).isEmpty())
        assertTrue(database.listSavedFoods().isEmpty())
    }

    @Test
    fun correctionCannotRecommitPersistedMealIntoFutureLedgerAfterCalendarRollback() {
        val futureDate = LocalDate.now().plusDays(1)
        val db = database.writableDatabase
        val mealId = db.compileStatement(
            "INSERT INTO meals(commit_id,meal_date,title,kcal,carbs_g,protein_g,fat_g,evidence_tier,confirmed_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
        ).use { statement ->
            statement.bindString(1, "pre-rollback-meal")
            statement.bindString(2, futureDate.toString())
            statement.bindString(3, "回拨前餐食")
            statement.bindDouble(4, 165.0)
            statement.bindDouble(5, 10.0)
            statement.bindDouble(6, 20.0)
            statement.bindDouble(7, 5.0)
            statement.bindString(8, EvidenceTier.C.name)
            statement.bindLong(9, System.currentTimeMillis())
            statement.executeInsert()
        }
        db.execSQL(
            "INSERT INTO meal_items(meal_id,item_name,grams,kcal_per_100g,carbs_per_100g," +
                "protein_per_100g,fat_per_100g,source_name,portion_basis,evidence_tier,calorie_source) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                mealId, "回拨前食物", 100.0, 165.0, 10.0, 20.0, 5.0, "包装标签",
                PortionBasis.USER_WEIGHT.name, EvidenceTier.C.name, CalorieSource.LABEL_OR_DATABASE.name,
            ),
        )
        val correction = database.draftFromMeal(mealId, futureDate, replaceOriginal = true).copy(
            state = DraftState.READY_TO_CONFIRM,
            userReviewed = true,
        )
        database.saveDraft(correction)

        assertThrows(IllegalArgumentException::class.java) {
            database.commitDraft(correction, futureDate)
        }

        assertEquals(listOf(mealId), database.mealsForDate(futureDate).map { it.id })
        assertEquals(correction.id, database.latestDraft()?.id)
    }

    @Test
    fun onDeviceCandidateDraftSurvivesDatabaseReopenWithoutBecomingFood() {
        val suggestion = validManualDraft().items.single().copy(
            name = "白米饭",
            grams = 160.0,
            gramsMin = 100.0,
            gramsMax = 300.0,
            per100g = Nutrition(kcal = 129.0, carbsG = 28.0, proteinG = 2.67, fatG = 0.28),
            sourceName = "USDA FNDDS 2021–2023 · FDC 2708408",
            portionBasis = PortionBasis.AI_SINGLE_PHOTO,
            riskFlags = setOf(RiskFlag.UNKNOWN_PORTION),
        )
        val draft = MealDraft(
            photoUri = "content://test/local-food.jpg",
            items = emptyList(),
            evidenceTier = EvidenceTier.D,
            evidenceReason = "本机候选待选择",
            unresolvedFlags = emptySet(),
            providerLabel = "本机 Google AIY Food V1",
            analysisMode = AnalysisMode.ON_DEVICE_AI,
            hypotheses = listOf(
                FoodHypothesis(
                    labelId = 572,
                    rawLabel = "White rice",
                    displayName = "白米饭",
                    modelScore = 0.54,
                    canonicalKey = "white_rice",
                    suggestedItem = suggestion,
                ),
            ),
        )

        assertTrue(database.saveDraft(draft))
        database.close()
        database = FitnessDatabase(context)

        val restored = requireNotNull(database.latestDraft())
        assertTrue(restored.items.isEmpty())
        assertEquals(AnalysisMode.ON_DEVICE_AI, restored.analysisMode)
        assertEquals(1, restored.hypotheses.size)
        assertEquals(572, restored.hypotheses.single().labelId)
        assertEquals(0.54, restored.hypotheses.single().modelScore, 0.0)
        assertEquals(129.0, requireNotNull(restored.hypotheses.single().suggestedItem).per100g.kcal, 0.0)
        assertNotNull(restored.commitValidationError())
    }

    @Test
    fun bodyMeasurementForSameDateIsUpdatedInsteadOfDuplicated() {
        val date = LocalDate.of(2026, 8, 27)
        database.addBodyMeasurement(BodyMeasurement(date = date, weightKg = 80.0, waistCm = 88.0))
        database.addBodyMeasurement(BodyMeasurement(date = date, weightKg = 79.5, waistCm = 87.5))

        val stored = database.listBodyMeasurements()
        assertEquals(1, stored.size)
        assertEquals(79.5, stored.single().weightKg, 0.001)
        assertEquals(87.5, stored.single().waistCm ?: 0.0, 0.001)
    }

    @Test
    fun bodyHistoryCanBeBackdatedEditedAndDeletedWithoutChangingAnotherDate() {
        val olderDate = LocalDate.now().minusDays(20)
        val newerDate = LocalDate.now().minusDays(5)
        val olderId = database.saveBodyMeasurement(BodyMeasurement(date = olderDate, weightKg = 82.0, waistCm = 90.0))
        val newerId = database.saveBodyMeasurement(BodyMeasurement(date = newerDate, weightKg = 80.0, waistCm = 87.0))

        database.saveBodyMeasurement(
            BodyMeasurement(id = olderId, date = olderDate.minusDays(1), weightKg = 81.5, waistCm = null),
        )

        val edited = database.listBodyMeasurements().first { it.id == olderId }
        assertEquals(olderDate.minusDays(1), edited.date)
        assertEquals(81.5, edited.weightKg, 0.001)
        assertNull(edited.waistCm)
        assertThrows(IllegalArgumentException::class.java) {
            database.saveBodyMeasurement(edited.copy(date = newerDate))
        }
        assertTrue(database.deleteBodyMeasurement(newerId))
        assertEquals(listOf(olderId), database.listBodyMeasurements().map { it.id })
    }

    @Test
    fun persistedBodyRecordRemainsReadableWhenTheLocalDateMovesBehindIt() {
        // Model a record accepted on UTC+14 date D, then read after moving to
        // GMT where the local calendar is still D-1. Use tomorrow relative to
        // the runner so the old read-time business validation would drop it.
        val kiritimatiDate = LocalDate.now().plusDays(1)
        val gmtDate = kiritimatiDate.minusDays(1)
        val measurement = BodyMeasurement(
            date = kiritimatiDate,
            weightKg = 83.0,
            waistCm = 88.0,
        )
        assertNull(measurement.validationError(currentDate = kiritimatiDate))
        assertNotNull(measurement.validationError(currentDate = gmtDate))
        assertThrows(IllegalArgumentException::class.java) {
            database.saveBodyMeasurement(measurement)
        }

        val id = database.writableDatabase.compileStatement(
            "INSERT INTO body_measurements(recorded_date,weight_kg,waist_cm) VALUES(?,?,?)",
        ).use { statement ->
            statement.bindString(1, measurement.date.toString())
            statement.bindDouble(2, measurement.weightKg)
            statement.bindDouble(3, measurement.waistCm!!)
            statement.executeInsert()
        }
        database.close()
        database = FitnessDatabase(context)

        val restored = database.listBodyMeasurements().single()
        assertEquals(id, restored.id)
        assertEquals(kiritimatiDate, restored.date)
        assertEquals(83.0, restored.weightKg, 0.0)
        assertEquals(88.0, restored.waistCm ?: 0.0, 0.0)
        assertNull(restored.structuralValidationError())
        assertNotNull(restored.validationError(currentDate = gmtDate))
        assertEquals(1, database.listBodyMeasurements().size)
    }

    @Test
    fun savingReplacementDraftRemovesPreviousDraft() {
        val first = validManualDraft()
        val replacement = validManualDraft()

        database.saveDraft(first)
        database.saveDraft(replacement)

        assertEquals(replacement.id, database.latestDraft()?.id)
        database.discardDraft(replacement.id)
        assertNull(database.latestDraft())
    }

    @Test
    fun labelCaloriesAndRecentFavoriteFoodPersist() {
        val date = LocalDate.of(2026, 8, 26)
        val labelDraft = validManualDraft().copy(
            items = listOf(
                validManualDraft().items.single().copy(
                    name = "Oats",
                    grams = 75.0,
                    gramsMin = 75.0,
                    gramsMax = 75.0,
                    per100g = Nutrition(kcal = 380.0, carbsG = 66.3, proteinG = 16.9, fatG = 6.9),
                    sourceName = "包装标签",
                    calorieSource = CalorieSource.LABEL_OR_DATABASE,
                ),
            ),
            targetDate = date,
        )

        database.commitDraft(labelDraft, date)

        assertEquals(285.0, database.nutritionForDate(date).kcal, 0.001)
        val saved = database.listSavedFoods().single()
        assertEquals("Oats", saved.name)
        assertEquals(75.0, saved.defaultGrams, 0.001)
        assertTrue(database.setFoodFavorite(saved.id, true))

        database.close()
        database = FitnessDatabase(context)
        assertTrue(database.listSavedFoods().single().isFavorite)
        assertEquals(380.0, database.listSavedFoods().single().per100g.kcal, 0.001)
    }

    @Test
    fun sameFoodNameFromDifferentSourcesDoesNotOverwriteLibraryEntry() {
        val date = LocalDate.of(2026, 8, 27)
        val first = validManualDraft().copy(
            items = listOf(validManualDraft().items.single().copy(name = "燕麦", sourceName = "品牌 A")),
        )
        val second = validManualDraft().copy(
            items = listOf(
                validManualDraft().items.single().copy(
                    name = "燕麦",
                    sourceName = "品牌 B",
                    per100g = Nutrition(kcal = 185.0, carbsG = 15.0, proteinG = 20.0, fatG = 5.0),
                ),
            ),
        )

        database.commitDraft(first, date)
        database.commitDraft(second, date)

        val saved = database.listSavedFoods()
        assertEquals(2, saved.size)
        assertEquals(setOf("品牌 A", "品牌 B"), saved.map { it.sourceName }.toSet())
    }

    @Test
    fun editingMealAtomicallyReplacesItAndCopyCanTargetAnotherDate() {
        val firstDate = LocalDate.of(2026, 8, 26)
        val secondDate = LocalDate.of(2026, 8, 27)
        val originalId = database.commitDraft(validManualDraft(), firstDate)
        val edit = database.draftFromMeal(originalId, firstDate, replaceOriginal = true)
        val edited = edit.copy(
            state = DraftState.READY_TO_CONFIRM,
            userReviewed = true,
            items = edit.items.map { it.withGrams(90.0) },
        )

        val replacementId = database.commitDraft(edited, firstDate)

        assertNotEquals(originalId, replacementId)
        assertEquals(1, database.mealsForDate(firstDate).size)
        assertEquals(replacementId, database.mealsForDate(firstDate).single().id)
        assertEquals(148.5, database.nutritionForDate(firstDate).kcal, 0.001)

        val copy = database.draftFromMeal(replacementId, secondDate, replaceOriginal = false).copy(
            state = DraftState.READY_TO_CONFIRM,
            userReviewed = true,
        )
        database.commitDraft(copy, secondDate)

        assertEquals(1, database.mealsForDate(firstDate).size)
        assertEquals(1, database.mealsForDate(secondDate).size)
        assertEquals(148.5, database.nutritionForDate(secondDate).kcal, 0.001)
    }

    @Test
    fun correctingMealKeepsSavedFoodUsageExactForSameAndChangedIdentity() {
        val date = LocalDate.now().minusDays(1)
        val originalId = database.commitDraft(validManualDraft(), date)
        val originalSaved = database.listSavedFoods().single()
        assertEquals(1, originalSaved.useCount)
        // Model a count already inflated by alpha11's correction behaviour;
        // the first alpha12 correction must reconcile it to active meal items.
        database.writableDatabase.execSQL(
            "UPDATE saved_foods SET use_count = 9 WHERE id = ?",
            arrayOf(originalSaved.id),
        )

        val sameIdentity = database.draftFromMeal(originalId, date, replaceOriginal = true).copy(
            state = DraftState.READY_TO_CONFIRM,
            userReviewed = true,
            items = database.draftFromMeal(originalId, date, replaceOriginal = true).items.map { it.withGrams(90.0) },
        )
        val sameIdentityReplacementId = database.commitDraft(sameIdentity, date)
        assertEquals(1, database.listSavedFoods().single { it.id == originalSaved.id }.useCount)

        val changedIdentity = database.draftFromMeal(sameIdentityReplacementId, date, replaceOriginal = true).let { draft ->
            draft.copy(
                state = DraftState.READY_TO_CONFIRM,
                userReviewed = true,
                items = draft.items.map {
                    it.copy(name = "糙米", sourceName = "另一来源", per100g = Nutrition(141.0, 30.0, 3.0, 1.0))
                },
            )
        }
        database.commitDraft(changedIdentity, date)

        val saved = database.listSavedFoods()
        assertEquals(0, saved.single { it.id == originalSaved.id }.useCount)
        assertEquals(1, saved.single { it.name == "糙米" && it.sourceName == "另一来源" }.useCount)
        assertEquals(1, database.mealsForDate(date).size)
    }

    @Test
    fun versionThreeMigrationPreservesMealItemsAndCreatesFoodLibrary() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE meal_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    meal_id INTEGER NOT NULL,
                    item_name TEXT NOT NULL,
                    grams REAL NOT NULL,
                    kcal_per_100g REAL NOT NULL,
                    carbs_per_100g REAL NOT NULL,
                    protein_per_100g REAL NOT NULL,
                    fat_per_100g REAL NOT NULL,
                    source_name TEXT NOT NULL,
                    portion_basis TEXT NOT NULL,
                    evidence_tier TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE body_measurements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_date TEXT NOT NULL,
                    weight_kg REAL NOT NULL,
                    waist_cm REAL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO meal_items(meal_id,item_name,grams,kcal_per_100g,carbs_per_100g," +
                    "protein_per_100g,fat_per_100g,source_name,portion_basis,evidence_tier) " +
                    "VALUES(7,'旧版燕麦',75,380,66.3,16.9,6.9,'旧包装','USER_WEIGHT','C')",
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    status TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    exercise_id INTEGER NOT NULL,
                    set_order INTEGER NOT NULL,
                    load_grams INTEGER NOT NULL,
                    reps INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL,
                    is_warmup INTEGER NOT NULL,
                    rpe REAL,
                    batch_id TEXT
                )
                """.trimIndent(),
            )
            legacy.version = 3
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase
        upgraded.rawQuery("SELECT item_name, calorie_source FROM meal_items WHERE meal_id = 7", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧版燕麦", cursor.getString(0))
            assertEquals(CalorieSource.LABEL_OR_DATABASE.name, cursor.getString(1))
        }
        upgraded.rawQuery("SELECT normalized_source FROM saved_foods", null).use { cursor ->
            assertEquals(1, cursor.columnCount)
        }
    }

    @Test
    fun versionEightMigrationPreservesMealsAndAddsMatchingNutritionReadIndexes() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE meals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    commit_id TEXT NOT NULL UNIQUE,
                    meal_date TEXT NOT NULL,
                    title TEXT NOT NULL,
                    kcal REAL NOT NULL,
                    carbs_g REAL NOT NULL,
                    protein_g REAL NOT NULL,
                    fat_g REAL NOT NULL,
                    evidence_tier TEXT NOT NULL,
                    confirmed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE meal_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    meal_id INTEGER NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
                    item_name TEXT NOT NULL,
                    grams REAL NOT NULL,
                    kcal_per_100g REAL NOT NULL,
                    carbs_per_100g REAL NOT NULL,
                    protein_per_100g REAL NOT NULL,
                    fat_per_100g REAL NOT NULL,
                    source_name TEXT NOT NULL,
                    portion_basis TEXT NOT NULL,
                    evidence_tier TEXT NOT NULL,
                    calorie_source TEXT NOT NULL DEFAULT 'LABEL_OR_DATABASE'
                )
                """.trimIndent(),
            )
            // A real v8 database always contains this table. Keep the fixture
            // structurally faithful so the v9 form-commit FK migration is exercised.
            legacy.execSQL(
                """
                CREATE TABLE body_measurements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_date TEXT NOT NULL,
                    weight_kg REAL NOT NULL,
                    waist_cm REAL
                )
                """.trimIndent(),
            )
            legacy.execSQL("CREATE INDEX idx_meals_date ON meals(meal_date)")
            legacy.execSQL(
                "INSERT INTO meals(id,commit_id,meal_date,title,kcal,carbs_g,protein_g,fat_g,evidence_tier,confirmed_at) " +
                    "VALUES(41,'legacy-meal-41','2026-08-29','旧版餐食',165,10,20,5,'C',12345)",
            )
            legacy.execSQL(
                "INSERT INTO meal_items(id,meal_id,item_name,grams,kcal_per_100g,carbs_per_100g," +
                    "protein_per_100g,fat_per_100g,source_name,portion_basis,evidence_tier,calorie_source) " +
                    "VALUES(51,41,'旧版食物',100,165,10,20,5,'旧标签','USER_WEIGHT','C','LABEL_OR_DATABASE')",
            )
            legacy.version = 8
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase

        assertEquals(11, upgraded.version)
        assertEquals(listOf(41L), database.mealsForDate(LocalDate.of(2026, 8, 29)).map { it.id })
        assertEquals("旧版食物", database.draftFromMeal(41, LocalDate.of(2026, 8, 29), false).items.single().name)
        assertEquals(
            listOf("meal_date", "confirmed_at", "id"),
            upgraded.rawQuery("PRAGMA index_info(idx_meals_date_confirmed_v9)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            },
        )
        assertEquals(
            listOf("meal_id", "id"),
            upgraded.rawQuery("PRAGMA index_info(idx_meal_items_meal_id_id_v9)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            },
        )
        val mealPlan = queryPlanDetails(
            upgraded,
            "SELECT * FROM meals INDEXED BY idx_meals_date_confirmed_v9 " +
                "WHERE meal_date = ? ORDER BY confirmed_at DESC, id DESC",
            arrayOf("2026-08-29"),
        )
        assertTrue(mealPlan.any { it.contains("idx_meals_date_confirmed_v9") })
        assertTrue(mealPlan.none { it.contains("TEMP B-TREE", ignoreCase = true) })
        val itemPlan = queryPlanDetails(
            upgraded,
            "SELECT * FROM meal_items INDEXED BY idx_meal_items_meal_id_id_v9 " +
                "WHERE meal_id = ? ORDER BY id ASC",
            arrayOf("41"),
        )
        assertTrue(itemPlan.any { it.contains("idx_meal_items_meal_id_id_v9") })
        assertTrue(itemPlan.none { it.contains("TEMP B-TREE", ignoreCase = true) })
        assertFalse(
            upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_meals_date'",
                null,
            ).use { it.moveToFirst() },
        )
        assertTrue(
            upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'body_measurement_form_commits'",
                null,
            ).use { it.moveToFirst() },
        )
        assertTrue(
            upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'manual_food_form_conversions'",
                null,
            ).use { it.moveToFirst() },
        )
    }

    private fun queryPlanDetails(
        database: android.database.sqlite.SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): List<String> = database.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
        buildList {
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) add(cursor.getString(detailIndex))
        }
    }

    private fun validManualDraft() = MealDraft(
        photoUri = "",
        state = DraftState.READY_TO_CONFIRM,
        items = listOf(
            FoodDraftItem(
                name = "手工食物",
                grams = 100.0,
                gramsMin = 100.0,
                gramsMax = 100.0,
                per100g = Nutrition(kcal = 165.0, carbsG = 10.0, proteinG = 20.0, fatG = 5.0),
                sourceName = "用户手工输入",
                portionBasis = PortionBasis.USER_WEIGHT,
                evidenceTier = EvidenceTier.C,
                userModified = true,
                calorieSource = CalorieSource.DERIVED_FROM_MACROS,
            ),
        ),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "手工测试",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "手工",
        analysisMode = AnalysisMode.MANUAL,
    )

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
    }
}
