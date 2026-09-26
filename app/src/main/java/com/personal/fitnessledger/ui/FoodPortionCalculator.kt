package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.domain.factorInput
import com.personal.fitnessledger.domain.foodGramsForNutrient
import java.time.LocalDate

@Composable
internal fun FoodPortionCalculator(
    target: Nutrition,
    consumed: Nutrition,
    date: LocalDate,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(date.toString()) { mutableStateOf(0) }
    val labels = listOf("碳水", "蛋白质", "脂肪")
    val remaining = listOf(target.carbsG - consumed.carbsG, target.proteinG - consumed.proteinG, target.fatG - consumed.fatG)
    var amount by rememberSaveable(selected, date.toString()) {
        mutableStateOf(if (remaining[selected] > 0) factorInput(remaining[selected]) else "")
    }
    var per100 by rememberSaveable(selected, date.toString()) { mutableStateOf("") }
    var food by rememberSaveable(date.toString()) { mutableStateOf("") }
    val amountError = boundedDecimalError(amount, "希望换算的营养素", 0.1, 5000.0, "0.1–5000 g")
    val per100Error = boundedDecimalError(per100, "每100g含量", 0.01, 100.0, "大于0且不超过100 g")
    fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()
    val grams = if (amountError == null && per100Error == null) {
        foodGramsForNutrient(requireNotNull(number(amount)), requireNotNull(number(per100)))
    } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换算食物份量") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag("portion-calculator"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$date · 按当前方案对照，不会自动记入饮食。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { selected = (selected + 1) % labels.size }, modifier = Modifier.fillMaxWidth().testTag("portion-nutrient")) {
                    Text("换算${labels[selected]} · 点击切换")
                }
                Text(
                    if (remaining[selected] > 0) "当天已记录摄入距目标还差 ${formatOne(remaining[selected])} g"
                    else "该项已达到或超过目标，无需为了凑数额外进食。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = food,
                    onValueChange = { if (it.length <= 80) food = it },
                    label = { Text("食物与状态（如：熟米饭）") },
                    modifier = Modifier.fillMaxWidth().testTag("portion-food"),
                    singleLine = true,
                )
                DecimalTextField(amount, { amount = it }, "想换算多少克${labels[selected]}",
                    modifier = Modifier.fillMaxWidth().testTag("portion-amount"),
                    errorMessage = if (amount.isBlank()) null else amountError)
                DecimalTextField(per100, { per100 = it }, "该食物每100g含${labels[selected]}（g）",
                    modifier = Modifier.fillMaxWidth().testTag("portion-per100"),
                    errorMessage = if (per100.isBlank()) null else per100Error)
                Text("查包装或食物数据填写；生重数据配生重，熟重数据配熟重，不要混用。", style = MaterialTheme.typography.bodySmall)
                if (grams != null && food.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("约 ${formatOne(grams)} g ${food.trim()}", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("portion-result"))
                            Text("算法：${amount.trim()} × 100 ÷ ${per100.trim()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("只按一项营养素做数学换算，不是建议食用量。这份食物的其他营养素、配料和烹调油仍要一起记录；也不要求用一种食物补齐缺口。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成换算") } },
    )
}
