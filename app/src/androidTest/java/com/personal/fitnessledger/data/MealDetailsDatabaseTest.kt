package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealDetailsDatabaseTest {
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
    fun confirmedFoodFactsSurviveReopenAndViewingDoesNotMutateTheLedgerOrDraft() {
        val date = LocalDate.now().minusDays(2)
        val foods = listOf(
            food("包装燕麦", 75.0).copy(
                per100g = Nutrition(380.0, 66.3, 16.9, 6.9),
                sourceName = "实物包装营养表",
                portionBasis = PortionBasis.PACKAGE_WEIGHT,
                evidenceTier = EvidenceTier.B,
            ),
            food("估算鸡肉", 150.0).copy(
                per100g = Nutrition(165.0, 10.0, 20.0, 5.0),
                sourceName = "用户填写三大营养素",
                portionBasis = PortionBasis.USER_ESTIMATE,
                calorieSource = CalorieSource.DERIVED_FROM_MACROS,
            ),
        )
        val mealId = database.commitDraft(draft(foods), date)
        database.saveDraft(draft(listOf(food("尚未确认的草稿", 90.0))))
        database.close()
        database = FitnessDatabase(context)
        database.writableDatabase
        val beforeViewing = ledgerSnapshot()

        val meal = database.mealsForDate(date).single()
        assertEquals(mealId, meal.id)
        assertEquals(2, meal.items.size)
        assertFalse(meal.itemDetailsIncomplete)
        foods.zip(meal.items).forEach { (expected, actual) ->
            assertTrue(actual.id > 0L)
            assertEquals(expected.name, actual.name)
            assertEquals(expected.grams, actual.grams, 0.0)
            assertEquals(expected.per100g, actual.per100g)
            assertEquals(expected.sourceName, actual.sourceName)
            assertEquals(expected.portionBasis, actual.portionBasis)
            assertEquals(expected.evidenceTier, actual.evidenceTier)
            assertEquals(expected.calorieSource, actual.calorieSource)
        }
        assertEquals(285.0, meal.items[0].nutrition.kcal, 0.001)
        assertEquals(49.725, meal.items[0].nutrition.carbsG, 0.001)
        assertEquals(12.675, meal.items[0].nutrition.proteinG, 0.001)
        assertEquals(5.175, meal.items[0].nutrition.fatG, 0.001)
        assertEquals(247.5, meal.items[1].nutrition.kcal, 0.001)
        assertEquals(532.5, meal.nutrition.kcal, 0.001)
        assertEquals(meal, database.mealsForDate(date).single())
        assertEquals(beforeViewing, ledgerSnapshot())
    }

    @Test
    fun multipleDatesAndTiedConfirmationTimesKeepEachMealsFoodsInStoredOrder() {
        val firstDate = LocalDate.now().minusDays(5)
        val secondDate = firstDate.plusDays(1)
        val firstId = database.commitDraft(draft(listOf(food("同名", 101.0), food("第一餐第二项", 102.0))), firstDate)
        val otherDateId = database.commitDraft(draft(listOf(food("同名", 201.0))), secondDate)
        val secondId = database.commitDraft(draft(listOf(food("第二餐第一项", 301.0), food("同名", 302.0))), firstDate)
        val olderId = database.commitDraft(draft(listOf(food("最后写入但较早入账", 401.0))), firstDate)
        setConfirmedAt(firstId, 10_000L)
        setConfirmedAt(secondId, 10_000L)
        setConfirmedAt(olderId, 9_000L)

        val firstRead = database.mealsForDate(firstDate)
        assertEquals(listOf(secondId, firstId, olderId), firstRead.map { it.id })
        assertEquals(listOf(301.0, 302.0), firstRead[0].items.map { it.grams })
        assertEquals(listOf(101.0, 102.0), firstRead[1].items.map { it.grams })
        firstRead.forEach { meal ->
            assertEquals(meal.items.map { it.id }.sorted(), meal.items.map { it.id })
            assertFalse(meal.itemDetailsIncomplete)
        }
        val otherDateMeal = database.mealsForDate(secondDate).single()
        assertEquals(otherDateId, otherDateMeal.id)
        assertEquals(listOf(201.0), otherDateMeal.items.map { it.grams })
        assertTrue(database.mealsForDate(firstDate.minusDays(1)).isEmpty())
        assertEquals(firstRead, database.mealsForDate(firstDate))
    }

    @Test
    fun missingHistoricalFoodsAndDifferentStoredTotalsRemainVisibleWithoutRecalculation() {
        val date = LocalDate.now().minusDays(10)
        val missingId = database.commitDraft(draft(listOf(food("历史缺失明细", 100.0))), date)
        val differentTotalId = database.commitDraft(draft(listOf(food("仍有明细", 100.0))), date)
        database.writableDatabase.delete("meal_items", "meal_id = ?", arrayOf(missingId.toString()))
        database.writableDatabase.execSQL(
            "UPDATE meals SET kcal = 999, carbs_g = 88 WHERE id = ?",
            arrayOf(differentTotalId),
        )
        val beforeViewing = ledgerSnapshot()

        val meals = database.mealsForDate(date).associateBy { it.id }
        val missing = meals.getValue(missingId)
        assertTrue(missing.items.isEmpty())
        assertFalse(missing.itemDetailsIncomplete)
        assertEquals(165.0, missing.nutrition.kcal, 0.0)
        val different = meals.getValue(differentTotalId)
        assertEquals(165.0, different.items.single().nutrition.kcal, 0.0)
        assertEquals(999.0, different.nutrition.kcal, 0.0)
        assertEquals(88.0, different.nutrition.carbsG, 0.0)
        assertFalse(different.itemDetailsIncomplete)
        assertEquals(beforeViewing, ledgerSnapshot())
    }

    @Test
    fun invalidStoredFoodsAreExplicitlyFlaggedWithoutDroppingValidNeighborsOrChangingTotals() {
        val date = LocalDate.now().minusDays(1)
        val mealId = database.commitDraft(
            draft(listOf(food("无法识别的口径", 100.0), food("有效中间项", 200.0), food("无效克重", 300.0))),
            date,
        )
        // Model an old or externally damaged row, bypassing the normal write guards.
        database.writableDatabase.execSQL("DROP TRIGGER validate_meal_items_values_v6_update")
        database.writableDatabase.execSQL(
            "UPDATE meal_items SET portion_basis = 'UNKNOWN_OLD_VALUE' WHERE meal_id = ? AND item_name = ?",
            arrayOf<Any>(mealId, "无法识别的口径"),
        )
        database.writableDatabase.execSQL(
            "UPDATE meal_items SET grams = 0 WHERE meal_id = ? AND item_name = ?",
            arrayOf<Any>(mealId, "无效克重"),
        )
        val beforeViewing = ledgerSnapshot()

        val meal = database.mealsForDate(date).single()
        assertTrue(meal.itemDetailsIncomplete)
        assertEquals(listOf("有效中间项"), meal.items.map { it.name })
        assertEquals(990.0, meal.nutrition.kcal, 0.0)
        assertEquals(330.0, meal.items.single().nutrition.kcal, 0.0)
        assertEquals(beforeViewing, ledgerSnapshot())

        database.writableDatabase.execSQL(
            "UPDATE meal_items SET evidence_tier = 'UNKNOWN_OLD_VALUE' WHERE meal_id = ?",
            arrayOf(mealId),
        )
        val allInvalid = database.mealsForDate(date).single()
        assertTrue(allInvalid.itemDetailsIncomplete)
        assertTrue(allInvalid.items.isEmpty())
        assertEquals(990.0, allInvalid.nutrition.kcal, 0.0)
    }

    @Test
    fun correctingMealPreservesOriginalConfirmationTimeAndCopyGetsANewConfirmation() {
        val date = LocalDate.now().minusDays(3)
        val originalId = database.commitDraft(draft(listOf(food("更正份量", 100.0))), date)
        val newerId = database.commitDraft(draft(listOf(food("较新餐食", 100.0))), date)
        setConfirmedAt(originalId, 1_234L)
        setConfirmedAt(newerId, 5_678L)
        val edit = database.draftFromMeal(originalId, date, replaceOriginal = true).let { original ->
            original.copy(
                state = DraftState.READY_TO_CONFIRM,
                userReviewed = true,
                items = original.items.map { it.withGrams(90.0) },
            )
        }
        val beforeCorrection = System.currentTimeMillis()

        val replacementId = database.commitDraft(edit, date)
        assertNotEquals(originalId, replacementId)
        assertEquals(replacementId, database.commitDraft(edit, date))
        val meals = database.mealsForDate(date)
        assertEquals(listOf(newerId, replacementId), meals.map { it.id })
        assertEquals(1_234L, meals.last().confirmedAtMillis)
        assertEquals(90.0, meals.last().items.single().grams, 0.0)
        assertEquals(148.5, meals.last().nutrition.kcal, 0.001)
        val saved = database.listSavedFoods().single { it.name == "更正份量" }
        assertEquals(1, saved.useCount)
        assertTrue(saved.lastUsedAtMillis >= beforeCorrection)

        val secondEdit = database.draftFromMeal(replacementId, date, replaceOriginal = true)
            .copy(state = DraftState.READY_TO_CONFIRM, userReviewed = true)
        val secondReplacementId = database.commitDraft(secondEdit, date)
        assertEquals(listOf(newerId, secondReplacementId), database.mealsForDate(date).map { it.id })
        assertEquals(1_234L, database.mealsForDate(date).last().confirmedAtMillis)

        val copyDate = date.plusDays(1)
        val copy = database.draftFromMeal(secondReplacementId, copyDate, replaceOriginal = false)
            .copy(state = DraftState.READY_TO_CONFIRM, userReviewed = true)
        val beforeCopy = System.currentTimeMillis()
        val copyId = database.commitDraft(copy, copyDate)
        val copiedMeal = database.mealsForDate(copyDate).single()
        assertEquals(copyId, copiedMeal.id)
        assertTrue(copiedMeal.confirmedAtMillis >= beforeCopy)
        assertEquals(90.0, copiedMeal.items.single().grams, 0.0)
        assertEquals(1_234L, database.mealsForDate(date).last().confirmedAtMillis)
    }

    @Test
    fun incompleteOrMissingFoodsCannotCreateCorrectionOrCopyOrChangeAnExistingDraft() {
        val date = LocalDate.now().minusDays(2)
        val original = draft(listOf(food("有效食物", 100.0), food("不可读取食物", 200.0)))
        val mealId = database.commitDraft(original, date)
        val pending = draft(listOf(food("用户尚未提交的草稿", 80.0)))
        database.saveDraft(pending)
        database.writableDatabase.execSQL("DROP TRIGGER validate_meal_items_values_v6_update")
        database.writableDatabase.execSQL(
            "UPDATE meal_items SET portion_basis = 'UNKNOWN_OLD_VALUE' WHERE meal_id = ? AND item_name = ?",
            arrayOf<Any>(mealId, "不可读取食物"),
        )
        val beforeRejectedDrafts = ledgerSnapshot()

        listOf(true, false).forEach { replaceOriginal ->
            assertThrows(IllegalArgumentException::class.java) {
                database.draftFromMeal(mealId, date, replaceOriginal)
            }
        }
        assertEquals(beforeRejectedDrafts, ledgerSnapshot())
        assertEquals(original.total, database.nutritionForDate(date))
        assertEquals(pending.id, database.latestDraft()?.id)
        assertTrue(database.mealsForDate(date).single().itemDetailsIncomplete)
        assertEquals(listOf("有效食物"), database.mealsForDate(date).single().items.map { it.name })

        database.writableDatabase.delete("meal_items", "meal_id = ?", arrayOf(mealId.toString()))
        val beforeMissingDrafts = ledgerSnapshot()
        listOf(true, false).forEach { replaceOriginal ->
            assertThrows(IllegalArgumentException::class.java) {
                database.draftFromMeal(mealId, date, replaceOriginal)
            }
        }
        assertEquals(beforeMissingDrafts, ledgerSnapshot())
        assertEquals(original.total, database.nutritionForDate(date))
        assertEquals(pending.id, database.latestDraft()?.id)
        assertTrue(database.mealsForDate(date).single().items.isEmpty())
    }

    private fun setConfirmedAt(mealId: Long, confirmedAt: Long) {
        database.writableDatabase.execSQL("UPDATE meals SET confirmed_at = ? WHERE id = ?", arrayOf(confirmedAt, mealId))
    }

    private fun ledgerSnapshot(): Map<String, List<List<String?>>> =
        listOf("meals", "meal_items", "saved_foods", "photo_drafts").associateWith { table ->
            database.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(List(cursor.columnCount) { index -> if (cursor.isNull(index)) null else cursor.getString(index) })
                    }
                }
            }
        }

    private fun food(name: String, grams: Double) = FoodDraftItem(
        name = name,
        grams = grams,
        gramsMin = grams,
        gramsMax = grams,
        per100g = Nutrition(165.0, 10.0, 20.0, 5.0),
        sourceName = "测试来源",
        portionBasis = PortionBasis.USER_WEIGHT,
        evidenceTier = EvidenceTier.C,
        calorieSource = CalorieSource.LABEL_OR_DATABASE,
    )

    private fun draft(items: List<FoodDraftItem>) = MealDraft(
        photoUri = "",
        state = DraftState.READY_TO_CONFIRM,
        items = items,
        evidenceTier = EvidenceTier.C,
        evidenceReason = "餐食明细读取测试",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "手工",
        analysisMode = AnalysisMode.MANUAL,
    )

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
    }
}
