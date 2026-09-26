package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ManualFoodFormJournalTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun rawTemporarilyInvalidNumericStringsRoundTripWithoutParsing() {
        val repository = FitnessRepository(context)
        val draft = manualDraft(
            gramsText = "12.",
            kcalText = "-",
            carbsText = "not-a-number",
            proteinText = "1e",
            fatText = "∞",
        )

        assertTrue(repository.saveManualFoodFormDraft(draft))

        assertEquals(draft, repository.manualFoodFormDraft())
    }

    @Test
    fun journalSurvivesRepositoryRecreation() {
        val draft = manualDraft(
            id = "recreated-form",
            itemId = "recreated-item",
            revision = 7L,
            updatedAtMillis = 7_000L,
            name = "进程重建前尚未保存的食物",
        )
        assertTrue(FitnessRepository(context).saveManualFoodFormDraft(draft))

        val restored = FitnessRepository(context).manualFoodFormDraft()

        assertEquals(draft, restored)
    }

    @Test
    fun olderRevisionCannotOverwriteNewerRevision() {
        val repository = FitnessRepository(context)
        val newest = manualDraft(
            id = "revision-form",
            revision = 9L,
            updatedAtMillis = 9_000L,
            name = "较新内容",
            gramsText = "180",
        )
        val lateOlderWrite = newest.copy(
            revision = 8L,
            updatedAtMillis = 10_000L,
            name = "迟到的旧内容",
            gramsText = "90",
        )

        assertTrue(repository.saveManualFoodFormDraft(newest))
        assertFalse(repository.saveManualFoodFormDraft(lateOlderWrite))

        assertEquals(newest, FitnessRepository(context).manualFoodFormDraft())
    }

    @Test
    fun clearTombstonePreventsLateWriteFromResurrectingSameForm() {
        val original = manualDraft(
            id = "cleared-form",
            revision = 4L,
            updatedAtMillis = 4_000L,
        )
        val repository = FitnessRepository(context)
        assertTrue(repository.saveManualFoodFormDraft(original))

        repository.clearManualFoodFormDraft(original.id)

        assertNull(repository.manualFoodFormDraft())
        val recreated = FitnessRepository(context)
        assertFalse(
            recreated.saveManualFoodFormDraft(
                original.copy(revision = 5L, updatedAtMillis = 5_000L, name = "迟到写入"),
            ),
        )
        assertNull(recreated.manualFoodFormDraft())
    }

    @Test
    fun differentNewFormIdCanBeSavedAfterPreviousFormWasCleared() {
        val repository = FitnessRepository(context)
        val oldDraft = manualDraft(id = "old-form", itemId = "old-item")
        assertTrue(repository.saveManualFoodFormDraft(oldDraft))
        repository.clearManualFoodFormDraft(oldDraft.id)

        val newDraft = manualDraft(
            id = "new-form",
            itemId = "new-item",
            revision = 1L,
            updatedAtMillis = 2_000L,
            name = "新的手工食物",
        )

        assertTrue(FitnessRepository(context).saveManualFoodFormDraft(newDraft))
        assertEquals(newDraft, FitnessRepository(context).manualFoodFormDraft())
    }

    @Test
    fun sqliteConversionReceiptPreventsRawFormResurrectionAfterResultingDraftIsDiscarded() {
        val repository = FitnessRepository(context)
        val raw = manualDraft(id = "converted-form", itemId = "converted-item")
        assertTrue(repository.saveManualFoodFormDraft(raw))
        val converted = MealDraft(
            photoUri = "",
            state = DraftState.EDITING,
            items = listOf(raw.toFoodDraftItem()),
            evidenceTier = EvidenceTier.C,
            evidenceReason = "测试手工转换回执",
            unresolvedFlags = emptySet(),
            providerLabel = "手工记录 · 尚未计入账本",
            analysisMode = AnalysisMode.MANUAL,
            targetDate = raw.targetDate,
        )

        assertTrue(repository.saveManualDraftFromForm(converted, raw.id))
        // Simulate process death before the SharedPreferences form is cleared,
        // followed by the user discarding the already-created meal draft.
        FitnessDatabase(context).discardDraft(converted.id)

        assertFalse(
            FitnessRepository(context).saveManualFoodFormDraft(
                raw.copy(revision = 2L, updatedAtMillis = 2_000L, name = "不应复活"),
            ),
        )
        val retry = converted.copy(id = "converted-retry", commitId = "converted-retry-commit")
        assertFalse(FitnessRepository(context).saveManualDraftFromForm(retry, raw.id))
        assertNull(FitnessRepository(context).manualFoodFormDraft())
        assertNull(FitnessRepository(context).latestDraft())
    }

    @Test
    fun expiredConversionReceiptRemainsIdempotentWhileRawFormCleanupHasFailed() {
        val repository = FitnessRepository(context)
        val raw = manualDraft(id = "manual-expired-protected", itemId = "manual-expired-item")
        assertTrue(repository.saveManualFoodFormDraft(raw))
        val converted = convertedDraft(raw, "manual-expired-draft", "manual-expired-commit")
        assertTrue(repository.saveManualDraftFromForm(converted, raw.id))
        repository.discardDraft(converted)

        val inspector = FitnessDatabase(context)
        inspector.writableDatabase.execSQL(
            "UPDATE manual_food_form_conversions SET converted_at = ? WHERE form_id = ?",
            arrayOf<Any?>(
                System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L,
                raw.id,
            ),
        )
        inspector.pruneManualFoodFormConversionReceipts(protectedFormId = raw.id)
        assertEquals(converted.id, inspector.manualFoodFormConversionDraftId(raw.id))
        inspector.close()

        val retry = convertedDraft(raw, "manual-expired-retry", "manual-expired-retry-commit")
        assertFalse(FitnessRepository(context).saveManualDraftFromForm(retry, raw.id))
        assertNull(FitnessRepository(context).latestDraft())
    }

    @Test
    fun expiredConversionReceiptIsRemovedOnlyAfterRawFormCleanupSucceeds() {
        val repository = FitnessRepository(context)
        val raw = manualDraft(id = "manual-expired-cleared", itemId = "manual-cleared-item")
        assertTrue(repository.saveManualFoodFormDraft(raw))
        val converted = convertedDraft(raw, "manual-cleared-draft", "manual-cleared-commit")
        assertTrue(repository.saveManualDraftFromForm(converted, raw.id))
        FitnessDatabase(context).use { inspector ->
            inspector.writableDatabase.execSQL(
                "UPDATE manual_food_form_conversions SET converted_at = ? WHERE form_id = ?",
                arrayOf<Any?>(
                    System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L,
                    raw.id,
                ),
            )
        }

        assertNull(FitnessRepository(context).manualFoodFormDraft())
        FitnessDatabase(context).use { inspector ->
            assertNull(inspector.manualFoodFormConversionDraftId(raw.id))
        }
    }

    private fun convertedDraft(
        raw: ManualFoodFormDraft,
        id: String,
        commitId: String,
    ) = MealDraft(
        id = id,
        commitId = commitId,
        photoUri = "",
        state = DraftState.EDITING,
        items = listOf(raw.toFoodDraftItem()),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "测试手工转换回执",
        unresolvedFlags = emptySet(),
        providerLabel = "手工记录 · 尚未计入账本",
        analysisMode = AnalysisMode.MANUAL,
        targetDate = raw.targetDate,
    )

    private fun manualDraft(
        id: String = "manual-form",
        itemId: String = "manual-item",
        revision: Long = 1L,
        updatedAtMillis: Long = 1_000L,
        name: String = "手工食物",
        gramsText: String = "100",
        kcalText: String = "250",
        carbsText: String = "30",
        proteinText: String = "20",
        fatText: String = "8",
    ) = ManualFoodFormDraft(
        id = id,
        itemId = itemId,
        targetDate = LocalDate.of(2026, 8, 29),
        initialGrams = 100.0,
        initialGramsMin = 80.0,
        initialGramsMax = 120.0,
        initialPortionBasis = PortionBasis.USER_ESTIMATE,
        name = name,
        gramsText = gramsText,
        kcalText = kcalText,
        carbsText = carbsText,
        proteinText = proteinText,
        fatText = fatText,
        sourceName = "用户手工输入",
        weighed = false,
        useLabelKcal = true,
        isDirty = true,
        revision = revision,
        updatedAtMillis = updatedAtMillis,
    )

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
    }
}
