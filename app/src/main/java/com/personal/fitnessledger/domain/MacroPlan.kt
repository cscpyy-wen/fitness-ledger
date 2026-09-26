package com.personal.fitnessledger.domain

import com.personal.fitnessledger.data.UserProfile
import kotlin.math.abs

/** Editable examples, not a prescription or an attributed coach's fixed formula. */
data class MacroPlanPreset(
    val id: String,
    val title: String,
    val description: String,
    val carbFactor: Double,
    val proteinFactor: Double,
    val fatFactor: Double,
) {
    fun applyTo(profile: UserProfile): UserProfile = profile.copy(
        carbFactor = carbFactor,
        proteinFactor = proteinFactor,
        fatFactor = fatFactor,
    )

    fun matches(profile: UserProfile): Boolean =
        abs(profile.carbFactor - carbFactor) < 0.000001 &&
            abs(profile.proteinFactor - proteinFactor) < 0.000001 &&
            abs(profile.fatFactor - fatFactor) < 0.000001
}

object MacroPlanPresets {
    val entries = listOf(
        MacroPlanPreset("three-carb", "三倍碳水", "碳水 3 · 蛋白质 1.6 · 脂肪 0.7", 3.0, 1.6, 0.7),
        MacroPlanPreset("two-protein", "蛋白质两倍", "碳水 3 · 蛋白质 2 · 脂肪 0.8", 3.0, 2.0, 0.8),
    )

    fun titleFor(profile: UserProfile): String = entries.firstOrNull { it.matches(profile) }?.title ?: "自定义方案"
}

/** Keep saved precision; display rounding must never silently rewrite a user's plan. */
fun factorInput(value: Double): String = if (value.isFinite()) {
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
} else value.toString()

/** A single-nutrient arithmetic conversion, never a meal recommendation or a ledger write. */
fun foodGramsForNutrient(nutrientGrams: Double, nutrientPer100g: Double): Double? {
    if (!nutrientGrams.isFinite() || nutrientGrams <= 0.0 ||
        !nutrientPer100g.isFinite() || nutrientPer100g <= 0.0 || nutrientPer100g > 100.0
    ) return null
    return (nutrientGrams * 100.0 / nutrientPer100g).takeIf { it.isFinite() }
}
