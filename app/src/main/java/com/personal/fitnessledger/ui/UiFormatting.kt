package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.Nutrition
import java.text.DecimalFormat

private val oneDecimal = DecimalFormat("0.#")
private val noDecimal = DecimalFormat("0")

fun formatOne(value: Double): String = oneDecimal.format(value)
fun formatWhole(value: Double): String = noDecimal.format(value)

fun Nutrition.compactLabel(): String =
    "碳水 ${formatOne(carbsG)} g · 蛋白质 ${formatOne(proteinG)} g · 脂肪 ${formatOne(fatG)} g"
