package com.personal.fitnessledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnDeviceFoodPostprocessorTest {
    private val rice = reference(
        canonicalKey = "white_rice",
        displayName = "白米饭",
        fdcId = 2_708_408L,
        minimumScore = 0.65,
        minimumMargin = 0.20,
    )
    private val friedRice = reference(
        canonicalKey = "fried_rice",
        displayName = "炒饭",
        fdcId = 2_708_952L,
        minimumScore = 0.78,
        minimumMargin = 0.30,
    )
    private val egg = reference(
        canonicalKey = "egg",
        displayName = "鸡蛋",
        fdcId = 2_707_154L,
        minimumScore = 0.70,
        minimumMargin = 0.25,
    )
    private val assets = OnDeviceFoodAssets(
        labels = listOf(
            "__background__",
            "White rice",
            "Fried rice",
            "Hot pot",
            "Pizza",
            "Pasta salad",
            "/g/opaque-food-label",
            "Tea egg",
        ),
        referencesByLabelId = mapOf(
            1 to rice,
            2 to friedRice,
            7 to egg,
        ),
    )
    private val postprocessor = OnDeviceFoodPostprocessor(assets)

    @Test
    fun `rice auto mapping requires both score and margin thresholds`() {
        val accepted = draft(scores(rice = 0.80, hotPot = 0.59))
        val belowScore = draft(scores(rice = 0.64, hotPot = 0.10))
        val belowMargin = draft(scores(rice = 0.80, hotPot = 0.61))

        assertEquals(listOf("白米饭"), accepted.items.map(FoodDraftItem::name))
        assertEquals(EvidenceTier.C, accepted.evidenceTier)
        assertTrue(belowScore.items.isEmpty())
        assertTrue(belowMargin.items.isEmpty())
        assertEquals(EvidenceTier.D, belowScore.evidenceTier)
        assertEquals(EvidenceTier.D, belowMargin.evidenceTier)
    }

    @Test
    fun `fried rice uses its stricter automatic mapping threshold`() {
        val accepted = draft(scores(friedRice = 0.81, hotPot = 0.50))
        val belowScore = draft(scores(friedRice = 0.77, hotPot = 0.10))
        val belowMargin = draft(scores(friedRice = 0.81, hotPot = 0.52))

        assertEquals(listOf("炒饭"), accepted.items.map(FoodDraftItem::name))
        assertTrue(belowScore.items.isEmpty())
        assertTrue(belowMargin.items.isEmpty())
    }

    @Test
    fun `unmapped top one never promotes a mapped top two`() {
        val result = draft(scores(rice = 0.90, hotPot = 0.95))

        assertTrue(result.items.isEmpty())
        assertEquals("火锅", result.hypotheses.first().displayName)
        assertEquals("白米饭", result.hypotheses[1].displayName)
        assertTrue(result.hypotheses.all { it.suggestedItem == null })
        assertEquals(EvidenceTier.D, result.evidenceTier)
    }

    @Test
    fun `opaque raw top forces rejection even when visible rice score is high`() {
        val result = draft(scores(rice = 0.90, opaque = 0.99, hotPot = 0.05))

        assertTrue(result.items.isEmpty())
        assertEquals("白米饭", result.hypotheses.first().displayName)
        assertTrue(result.hypotheses.none { it.rawLabel.startsWith("/g/") })
        assertTrue(result.hypotheses.all { it.suggestedItem == null })
    }

    @Test
    fun `top five hypotheses can create at most one draft item`() {
        val result = draft(
            scores(
                rice = 0.96,
                friedRice = 0.50,
                hotPot = 0.40,
                pizza = 0.30,
                pastaSalad = 0.20,
                egg = 0.10,
            ),
        )

        assertEquals(5, result.hypotheses.size)
        assertEquals(1, result.items.size)
        assertEquals("白米饭", result.items.single().name)
        assertEquals(1, result.hypotheses.count { it.suggestedItem != null })
    }

    @Test
    fun `low score mapped candidates stay visible but never prefill nutrition`() {
        val result = draft(scores(rice = 0.64, friedRice = 0.63, hotPot = 0.10))

        assertTrue(result.items.isEmpty())
        assertTrue(result.hypotheses.all { it.suggestedItem == null })
        assertTrue(result.evidenceReason.contains("无法从这张单图可靠确认"))
        assertTrue(result.providerLabel.contains("无法可靠预填营养"))
    }

    @Test
    fun `audited labels are Chinese and unknown labels never leak raw English as dish names`() {
        assertEquals("美式空心松饼", localizedFoodCandidateLabel(1145, "Popover"))
        assertEquals("果冻豆糖", localizedFoodCandidateLabel(232, "Jelly bean"))
        assertEquals("华夫饼", localizedFoodCandidateLabel(267, "Waffle"))
        assertEquals("便当", localizedFoodCandidateLabel(205, "Bento"))
        val unknown = localizedFoodCandidateLabel(999, "Untranslated raw label")
        assertEquals("未收录候选 #999（需手工命名）", unknown)
        assertTrue(!unknown.contains("Untranslated"))
    }

    @Test
    fun `untranslated raw label stays internal while user sees fail closed Chinese`() {
        val localAssets = OnDeviceFoodAssets(
            labels = listOf("__background__", "Untranslated raw label", "White rice"),
            referencesByLabelId = mapOf(2 to rice),
        )
        val result = OnDeviceFoodPostprocessor(localAssets).createDraft(
            TEST_PHOTO_URI,
            doubleArrayOf(0.0, 0.95, 0.90),
        )

        assertTrue(result.items.isEmpty())
        assertEquals("Untranslated raw label", result.hypotheses.first().rawLabel)
        assertEquals("未收录候选 #1（需手工命名）", result.hypotheses.first().displayName)
        assertTrue(result.hypotheses.none { it.displayName.contains("Untranslated") })
        assertTrue(result.hypotheses.first().suggestedItem == null)
    }

    @Test
    fun `invalid score vector length fails closed`() {
        expectIllegalArgument {
            postprocessor.createDraft(TEST_PHOTO_URI, DoubleArray(assets.labels.size - 1))
        }
        expectIllegalArgument {
            postprocessor.createDraft(TEST_PHOTO_URI, DoubleArray(assets.labels.size + 1))
        }
    }

    @Test
    fun `non finite and out of range scores fail closed`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01).forEach { invalid ->
            val scores = DoubleArray(assets.labels.size).also { it[1] = invalid }
            expectIllegalArgument { postprocessor.createDraft(TEST_PHOTO_URI, scores) }
        }
    }

    private fun draft(scores: DoubleArray): MealDraft = postprocessor.createDraft(TEST_PHOTO_URI, scores)

    private fun scores(
        rice: Double = 0.0,
        friedRice: Double = 0.0,
        hotPot: Double = 0.0,
        pizza: Double = 0.0,
        pastaSalad: Double = 0.0,
        opaque: Double = 0.0,
        egg: Double = 0.0,
    ): DoubleArray = doubleArrayOf(
        0.0,
        rice,
        friedRice,
        hotPot,
        pizza,
        pastaSalad,
        opaque,
        egg,
    )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid model output must never produce a user-visible estimate.
        }
    }

    private fun reference(
        canonicalKey: String,
        displayName: String,
        fdcId: Long,
        minimumScore: Double,
        minimumMargin: Double,
    ) = OnDeviceFoodReference(
        canonicalKey = canonicalKey,
        displayName = displayName,
        fdcId = fdcId,
        per100g = Nutrition(kcal = 130.0, carbsG = 25.0, proteinG = 4.0, fatG = 1.0),
        defaultGrams = 150.0,
        gramsMin = 100.0,
        gramsMax = 300.0,
        minimumScore = minimumScore,
        minimumMargin = minimumMargin,
        riskFlags = setOf(RiskFlag.UNKNOWN_PORTION),
    )

    private companion object {
        const val TEST_PHOTO_URI = "content://test/food.jpg"
    }
}
