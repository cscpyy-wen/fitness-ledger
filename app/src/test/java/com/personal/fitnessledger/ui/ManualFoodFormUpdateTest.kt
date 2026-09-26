package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.ManualFoodFormDraft
import com.personal.fitnessledger.data.PortionBasis
import com.personal.fitnessledger.data.toFoodDraftItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ManualFoodFormUpdateTest {
    @Test
    fun `callbacks created from one old composition merge different fields without rollback`() {
        val renderedSnapshot = draft(name = "旧名称", gramsText = "100", revision = 8L)

        // Both callbacks are created while the same old snapshot is on screen.
        // They carry only the field value/event; neither submits that full snapshot.
        val nameValueFromOldComposition = renderedSnapshot.copy(name = "鸡胸肉").name
        val gramsValueFromOldComposition = renderedSnapshot.copy(gramsText = "250").gramsText
        val nameCallback: ManualFoodFormTransform = { latest ->
            latest.copy(name = nameValueFromOldComposition)
        }
        val gramsCallback: ManualFoodFormTransform = { latest ->
            latest.copy(gramsText = gramsValueFromOldComposition)
        }

        val afterName = requireNotNull(
            mergeManualFoodFormUpdate(renderedSnapshot, renderedSnapshot.id, nameCallback, updatedAtMillis = 2_000L),
        )
        val afterGrams = requireNotNull(
            mergeManualFoodFormUpdate(afterName, renderedSnapshot.id, gramsCallback, updatedAtMillis = 2_001L),
        )

        assertEquals("鸡胸肉", afterGrams.name)
        assertEquals("250", afterGrams.gramsText)
        assertEquals(10L, afterGrams.revision)
        assertTrue(afterGrams.isDirty)
    }

    @Test
    fun `later callback cannot replace journal identity or revision from a stale candidate`() {
        val current = draft(name = "最新名称", gramsText = "180", revision = 19L)
        val maliciouslyStaleCandidate = draft(
            id = "old-form",
            itemId = "old-item",
            name = "旧名称",
            gramsText = "93.",
            revision = 2L,
        )

        val updated = requireNotNull(
            mergeManualFoodFormUpdate(
                current = current,
                expectedFormId = current.id,
                transform = { latest ->
                    maliciouslyStaleCandidate.copy(
                        name = latest.name,
                        gramsText = "220",
                    )
                },
                updatedAtMillis = 3_000L,
            ),
        )

        assertEquals(current.id, updated.id)
        assertEquals(current.itemId, updated.itemId)
        assertEquals(current.targetDate, updated.targetDate)
        assertEquals("最新名称", updated.name)
        assertEquals("220", updated.gramsText)
        assertEquals(20L, updated.revision)
        assertEquals(3_000L, updated.updatedAtMillis)
    }

    @Test
    fun `event from a closed form cannot mutate the next form`() {
        val next = draft(id = "form-b", itemId = "item-b", name = "米饭", gramsText = "120", revision = 1L)

        assertNull(
            mergeManualFoodFormUpdate(
                current = next,
                expectedFormId = "form-a",
                transform = { it.copy(name = "旧表单污染") },
            ),
        )
        assertEquals("米饭", next.name)
    }

    @Test
    fun `terminal parsing consumes the latest raw form values`() {
        val latest = draft(name = "鸡胸肉", gramsText = "250", revision = 3L).copy(
            carbsText = "1.2",
            proteinText = "31",
            fatText = "3.6",
            useLabelKcal = false,
            weighed = true,
        )

        val item = latest.toFoodDraftItem()

        assertEquals("鸡胸肉", item.name)
        assertEquals(250.0, item.grams, 0.0)
        assertEquals(250.0, item.gramsMin, 0.0)
        assertEquals(250.0, item.gramsMax, 0.0)
        assertEquals(1.2 * 4.0 + 31.0 * 4.0 + 3.6 * 9.0, item.per100g.kcal, 0.0001)
    }

    private fun draft(
        id: String = "current-form",
        itemId: String = "current-item",
        name: String,
        gramsText: String,
        revision: Long,
    ) = ManualFoodFormDraft(
        id = id,
        itemId = itemId,
        targetDate = LocalDate.of(2026, 8, 30),
        initialGrams = 100.0,
        initialGramsMin = 100.0,
        initialGramsMax = 100.0,
        initialPortionBasis = PortionBasis.USER_WEIGHT,
        name = name,
        gramsText = gramsText,
        kcalText = "165",
        carbsText = "0",
        proteinText = "31",
        fatText = "3.6",
        sourceName = "用户手工输入",
        weighed = true,
        useLabelKcal = false,
        isDirty = revision > 0L,
        revision = revision,
        updatedAtMillis = 1_000L,
    )
}
