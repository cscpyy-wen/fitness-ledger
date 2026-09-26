package com.personal.fitnessledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionTest {
    @Test
    fun `macro targets are calculated from editable grams per kilogram factors`() {
        val profile = UserProfile(
            referenceWeightKg = 80.0,
            carbFactor = 3.0,
            proteinFactor = 1.6,
            fatFactor = 0.7,
        )

        assertEquals(240.0, profile.dailyTarget.carbsG, 0.001)
        assertEquals(128.0, profile.dailyTarget.proteinG, 0.001)
        assertEquals(56.0, profile.dailyTarget.fatG, 0.001)
        assertEquals(1976.0, profile.dailyTarget.kcal, 0.001)
    }

    @Test
    fun `food nutrition is deterministic from grams and per100g values`() {
        val item = FoodDraftItem(
            name = "米饭",
            grams = 180.0,
            gramsMin = 180.0,
            gramsMax = 180.0,
            per100g = Nutrition(116.0, 25.9, 2.6, 0.3),
            sourceName = "test",
            portionBasis = PortionBasis.USER_WEIGHT,
            evidenceTier = EvidenceTier.A,
        )

        assertEquals(208.8, item.nutrition.kcal, 0.001)
        assertEquals(46.62, item.nutrition.carbsG, 0.001)
        assertEquals(4.68, item.nutrition.proteinG, 0.001)
        assertEquals(0.54, item.nutrition.fatG, 0.001)
    }

    @Test
    fun `interactive demo can never be committed to the real ledger`() {
        val draft = validDraft().copy(analysisMode = AnalysisMode.INTERACTIVE_DEMO)

        assertNotNull(draft.commitValidationError())
    }

    @Test
    fun `reviewed remote draft with valid values can be committed`() {
        assertNull(validDraft().commitValidationError())
    }

    @Test
    fun `on device hypotheses must be resolved before review or commit`() {
        val candidateOnly = validDraft().copy(
            items = emptyList(),
            evidenceTier = EvidenceTier.D,
            userReviewed = false,
            analysisMode = AnalysisMode.ON_DEVICE_AI,
            hypotheses = listOf(
                FoodHypothesis(
                    labelId = 572,
                    rawLabel = "White rice",
                    displayName = "白米饭",
                    modelScore = 0.54,
                    canonicalKey = "white_rice",
                ),
            ),
        )

        assertEquals(
            "请先从本机模型候选中选择食物；候选本身不是营养记录",
            candidateOnly.commitValidationError(),
        )
        assertEquals(
            "请先从本机模型候选中选择食物；候选本身不是营养记录",
            candidateOnly.copy(userReviewed = true).commitValidationError(),
        )
    }

    @Test
    fun `reviewed on device estimate rejects all zero nutrition`() {
        val zero = validDraft().copy(
            analysisMode = AnalysisMode.ON_DEVICE_AI,
            items = listOf(validDraft().items.single().copy(per100g = Nutrition())),
        )

        assertEquals("本机候选尚未填写营养值，请补全后再入账", zero.commitValidationError())
        assertNull(validDraft().copy(analysisMode = AnalysisMode.ON_DEVICE_AI).commitValidationError())
    }

    @Test
    fun `meal cannot hide a d tier item behind a higher aggregate tier`() {
        val invalid = validDraft().copy(
            evidenceTier = EvidenceTier.A,
            items = listOf(validDraft().items.single().copy(evidenceTier = EvidenceTier.D)),
        )

        assertNotNull(invalid.commitValidationError())
    }

    @Test
    fun `point estimate must remain inside portion interval`() {
        val invalid = validDraft().copy(
            items = listOf(validDraft().items.single().copy(grams = 500.0, gramsMin = 100.0, gramsMax = 200.0)),
        )

        assertNotNull(invalid.commitValidationError())
    }

    @Test
    fun `changing grams shifts the uncertainty interval`() {
        val moved = validDraft().items.single().withGrams(250.0)

        assertEquals(230.0, moved.gramsMin, 0.001)
        assertEquals(270.0, moved.gramsMax, 0.001)
    }

    @Test
    fun `manual meal requires explicit review before commit`() {
        assertNotNull(validManualDraft().copy(userReviewed = false).commitValidationError())
        assertNull(validManualDraft().commitValidationError())
    }

    @Test
    fun `manual meal rejects kcal that does not match macro calculation`() {
        val item = validManualDraft().items.single()
        val invalid = validManualDraft().copy(items = listOf(item.copy(per100g = item.per100g.copy(kcal = 999.0))))

        assertNotNull(invalid.commitValidationError())
    }

    @Test
    fun `manual meal accepts explicit label calories without changing macros`() {
        val item = validManualDraft().items.single().copy(
            per100g = Nutrition(kcal = 380.0, carbsG = 66.3, proteinG = 16.9, fatG = 6.9),
            grams = 75.0,
            gramsMin = 75.0,
            gramsMax = 75.0,
            calorieSource = CalorieSource.LABEL_OR_DATABASE,
        )
        val draft = validManualDraft().copy(items = listOf(item))

        assertNull(draft.commitValidationError())
        assertEquals(285.0, draft.total.kcal, 0.001)
        assertEquals(49.725, draft.total.carbsG, 0.001)
    }

    @Test
    fun `label calories cannot be zero when macros contain energy`() {
        val item = validManualDraft().items.single().copy(
            per100g = Nutrition(kcal = 0.0, carbsG = 20.0, proteinG = 5.0, fatG = 2.0),
            calorieSource = CalorieSource.LABEL_OR_DATABASE,
        )

        assertNotNull(validManualDraft().copy(items = listOf(item)).commitValidationError())
    }

    @Test
    fun `manual meal rejects impossible macro mass`() {
        val item = validManualDraft().items.single()
        val invalid = validManualDraft().copy(
            items = listOf(item.copy(per100g = Nutrition(kcal = 560.0, carbsG = 80.0, proteinG = 40.0, fatG = 8.8888889))),
        )

        assertNotNull(invalid.commitValidationError())
    }

    @Test
    fun `profile and body inputs are validated at domain boundary`() {
        val currentDate = java.time.LocalDate.of(2026, 8, 29)
        val nextDate = currentDate.plusDays(1)
        assertNull(UserProfile().validationError())
        assertNotNull(UserProfile(proteinFactor = 0.0).validationError())
        assertNull(BodyMeasurement(date = currentDate, weightKg = 80.0, waistCm = 85.0).validationError(currentDate))
        assertNotNull(BodyMeasurement(date = currentDate, weightKg = Double.POSITIVE_INFINITY, waistCm = null).validationError(currentDate))

        val persistedAcrossDateLine = BodyMeasurement(date = nextDate, weightKg = 80.0, waistCm = null)
        assertNull(persistedAcrossDateLine.structuralValidationError())
        assertNotNull(persistedAcrossDateLine.validationError(currentDate))
    }

    @Test
    fun `failed workout set may record zero result while completed set may not`() {
        val failed = WorkoutSetInput(
            weightKg = 100.0,
            reps = 0,
            completed = false,
            rpe = 10.0,
            rir = 0.0,
            note = "卧推离胸失败",
            supersetId = "A",
            restSecondsAfter = 120,
        )

        assertNull(failed.validationError(TrackingType.WEIGHT_REPS))
        assertNotNull(failed.copy(completed = true).validationError(TrackingType.WEIGHT_REPS))
        assertNotNull(failed.copy(rpe = 10.5).validationError(TrackingType.WEIGHT_REPS))
        assertNotNull(failed.copy(rir = -1.0).validationError(TrackingType.WEIGHT_REPS))
        assertNotNull(failed.copy(note = "x".repeat(501)).validationError(TrackingType.WEIGHT_REPS))
    }

    @Test
    fun `analysis endpoint requires safe https url and a proxy token`() {
        assertNull(analysisEndpointValidationError("https://proxy.example.com/analyze-meal"))
        assertNotNull(analysisEndpointValidationError("http://proxy.example.com/analyze-meal"))
        assertNotNull(analysisEndpointValidationError("https://user:pass@proxy.example.com/analyze-meal"))
        assertNotNull(analysisEndpointValidationError("https://proxy.example.com/analyze-meal?token=secret"))
        assertNotNull(analysisEndpointValidationError("https://proxy.example.com/analyze-meal?"))
        assertNotNull(analysisEndpointValidationError("https://proxy.example.com/analyze-meal#secret"))
        assertEquals(false, AnalysisServiceConfig("https://proxy.example.com/analyze-meal", "").isConfigured)
        assertEquals(true, AnalysisServiceConfig("https://proxy.example.com/analyze-meal", "token").isConfigured)
    }

    private fun validManualDraft(): MealDraft = MealDraft(
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
        evidenceReason = "测试手工记录",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "手工",
        analysisMode = AnalysisMode.MANUAL,
    )

    private fun validDraft(): MealDraft = MealDraft(
        photoUri = "content://test/meal.jpg",
        items = listOf(
            FoodDraftItem(
                name = "米饭",
                grams = 180.0,
                gramsMin = 160.0,
                gramsMax = 200.0,
                per100g = Nutrition(116.0, 25.9, 2.6, 0.3),
                sourceName = "测试食物库",
                portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                evidenceTier = EvidenceTier.C,
            ),
        ),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "测试",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "测试识别服务",
        analysisMode = AnalysisMode.REMOTE_AI,
    )
}
