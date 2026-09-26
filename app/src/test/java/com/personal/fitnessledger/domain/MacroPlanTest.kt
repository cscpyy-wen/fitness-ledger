package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.UserProfile
import com.personal.fitnessledger.data.validationError
import org.junit.Assert.*
import org.junit.Test

class MacroPlanTest {
    @Test fun threeTimesMeansGramsOfNutrientPerKgNotGramsOfFood() {
        val target = MacroPlanPresets.entries.first().applyTo(UserProfile(referenceWeightKg = 80.0)).dailyTarget
        assertEquals(240.0, target.carbsG, 0.000001)
        assertEquals(128.0, target.proteinG, 0.000001)
        assertEquals(56.0, target.fatG, 0.000001)
        assertEquals(1976.0, target.kcal, 0.000001)
    }

    @Test fun applyingPresetPreservesBodyReferenceAndDoesNotMutateOriginal() {
        val previous = UserProfile(heightCm = 183.0, referenceWeightKg = 73.45, waistCm = 81.2, carbFactor = 2.75)
        val updated = MacroPlanPresets.entries.last().applyTo(previous)
        assertEquals(73.45, updated.referenceWeightKg, 0.0)
        assertEquals(183.0, updated.heightCm, 0.0)
        assertEquals(81.2, updated.waistCm, 0.0)
        assertEquals(2.75, previous.carbFactor, 0.0)
        assertEquals(146.9, updated.dailyTarget.proteinG, 0.000001)
    }

    @Test fun customAndZeroCarbPlansRemainSupported() {
        val profile = UserProfile(carbFactor = 0.0, proteinFactor = 1.85, fatFactor = 0.65)
        assertNull(profile.validationError())
        assertEquals("自定义方案", MacroPlanPresets.titleFor(profile))
        assertEquals(0.0, profile.dailyTarget.carbsG, 0.0)
        assertEquals("1.85", factorInput(profile.proteinFactor))
    }

    @Test fun presetsStayInsideSoftwareValidationAndMatchAllThreeFactors() {
        MacroPlanPresets.entries.forEach {
            val profile = it.applyTo(UserProfile())
            assertNull(profile.validationError())
            assertTrue(it.matches(profile))
            assertFalse(it.matches(profile.copy(fatFactor = profile.fatFactor + 0.1)))
        }
    }

    @Test fun preciseInputRoundTripsWithoutChangingExistingGoals() {
        listOf(3.0, 2.75, 1.85, 0.675, 79.3456).forEach {
            assertEquals(it, factorInput(it).toDouble(), 0.0)
        }
        assertEquals("NaN", factorInput(Double.NaN))
        assertEquals("Infinity", factorInput(Double.POSITIVE_INFINITY))
    }

    @Test fun foodPortionConversionUsesPer100gAndRejectsImpossibleInputs() {
        assertEquals(231.6602, foodGramsForNutrient(60.0, 25.9)!!, 0.0001)
        assertEquals(150.0, foodGramsForNutrient(30.0, 20.0)!!, 0.0)
        assertNull(foodGramsForNutrient(0.0, 25.9))
        assertNull(foodGramsForNutrient(-10.0, 25.9))
        assertNull(foodGramsForNutrient(60.0, 0.0))
        assertNull(foodGramsForNutrient(60.0, 101.0))
        assertNull(foodGramsForNutrient(Double.NaN, 25.9))
        assertNull(foodGramsForNutrient(60.0, Double.POSITIVE_INFINITY))
    }
}
