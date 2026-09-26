package com.personal.fitnessledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.CalorieSource
import com.personal.fitnessledger.data.MealFoodRecord
import com.personal.fitnessledger.data.MealRecord
import com.personal.fitnessledger.data.PortionBasis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun mealFoodSummary(meal: MealRecord): String =
    if (meal.items.isEmpty()) meal.title else meal.items.take(3).joinToString(" · ") {
        "${it.name} ${formatOne(it.grams)} g"
    } + if (meal.items.size > 3) " 等 ${meal.items.size} 项" else ""

internal fun mealRecordedAtLabel(meal: MealRecord, zone: ZoneId = ZoneId.systemDefault()): String {
    val recorded = Instant.ofEpochMilli(meal.confirmedAtMillis).atZone(zone)
    val pattern = if (recorded.toLocalDate() == meal.date) "HH:mm" else "yyyy-MM-dd HH:mm"
    return "入账时间 ${recorded.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))}"
}

@Composable
internal fun MealHistoryCard(
    meal: MealRecord,
    canMutate: Boolean,
    onOpenDetails: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("meal-record-${meal.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mealRecordedAtLabel(meal), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(mealFoodSummary(meal), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${formatWhole(meal.nutrition.kcal)} kcal", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(meal.nutrition.compactLabel(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meal.itemDetailsIncomplete) {
                Text("部分食物明细无法读取，合计保留原记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenDetails,
                    modifier = Modifier.weight(1f).testTag("open-meal-details-${meal.id}")) {
                    Text("查看明细")
                }
                MealActionsMenu(meal.id, canMutate, "meal-card-menu-${meal.id}", onEdit, onCopy, onDelete,
                    allowRecreate = meal.items.isNotEmpty() && !meal.itemDetailsIncomplete)
            }
        }
    }
}

@Composable
internal fun MealDetailsScreen(
    meal: MealRecord,
    canMutate: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("meal-details-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("meal-detail-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回饮食记录")
            }
            Text("餐食明细", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            MealActionsMenu(meal.id, canMutate, "meal-details-menu-${meal.id}", onEdit, onCopy, onDelete,
                allowRecreate = meal.items.isNotEmpty() && !meal.itemDetailsIncomplete)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp).testTag("meal-details-list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("记录日期 · ${meal.date}", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(mealRecordedAtLabel(meal), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("这一餐合计", style = MaterialTheme.typography.titleMedium)
                        Text("${formatWhole(meal.nutrition.kcal)} kcal", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("meal-detail-total"))
                        Text(meal.nutrition.compactLabel(), style = MaterialTheme.typography.bodyLarge)
                        Text("${meal.evidenceTier.label} · 已核对入账", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (meal.itemDetailsIncomplete) {
                item {
                    Text("部分食物明细无法读取。以下仅显示可读取的内容；合计仍为当时入账的值。为避免覆盖原记录，暂不能更正或复制。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("meal-detail-partial-warning"))
                }
            }
            if (meal.items.isEmpty()) {
                item {
                    Text("这条记录没有可读取的食物明细。原有营养合计仍保留，不会将缺失内容显示为零；暂不能更正或复制。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("meal-detail-missing"))
                }
            } else {
                item {
                    Text(if (meal.itemDetailsIncomplete) "可读取的食物 · ${meal.items.size} 项" else "这餐吃了 · ${meal.items.size} 项",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(meal.items, key = { it.id }) { item -> MealFoodDetailCard(item) }
            }
            item {
                Text("这里展示的是已保存的记录，查看不会重新识别、创建草稿或重复计入。照片估算仍有误差。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("此版本未保留已确认餐食的照片；入账时间不等于实际进餐时间。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MealFoodDetailCard(item: MealFoodRecord) {
    var showNutritionBasis by rememberSaveable(item.id) { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("meal-detail-item-${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("本次份量 ${formatOne(item.grams)} g", style = MaterialTheme.typography.bodyLarge)
            Text("本次摄入 ${formatWhole(item.nutrition.kcal)} kcal", fontWeight = FontWeight.SemiBold)
            Text(item.nutrition.compactLabel(), style = MaterialTheme.typography.bodyMedium)
            // Keep uncertainty visible even when the secondary reference facts are collapsed.
            Text("份量依据：${portionBasisLabel(item.portionBasis)} · ${item.evidenceTier.label}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { showNutritionBasis = !showNutritionBasis },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("meal-detail-basis-${item.id}")
                    .semantics { stateDescription = if (showNutritionBasis) "已展开" else "已收起" },
            ) {
                Text(if (showNutritionBasis) "收起营养依据" else "查看营养依据", modifier = Modifier.weight(1f))
                Icon(if (showNutritionBasis) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null)
            }
            if (showNutritionBasis) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text("每 100 g · ${formatOne(item.per100g.kcal)} kcal", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.per100g.compactLabel(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("营养来源：${item.sourceName}", style = MaterialTheme.typography.bodySmall)
                Text("热量口径：" + if (item.calorieSource == CalorieSource.DERIVED_FROM_MACROS) "由三大营养素折算" else "标签或营养资料",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun portionBasisLabel(basis: PortionBasis): String = when (basis) {
    PortionBasis.AI_SINGLE_PHOTO -> "单张照片估算"
    PortionBasis.AI_REFERENCE_OBJECT -> "照片参照物估算"
    PortionBasis.USER_ESTIMATE -> "自行估算"
    PortionBasis.USER_WEIGHT -> "自行称重"
    PortionBasis.PACKAGE_WEIGHT -> "包装标示重量"
    PortionBasis.STANDARD_PORTION -> "标准份量"
    PortionBasis.SAVED_RECIPE -> "已保存食谱"
}

@Composable
private fun MealActionsMenu(
    mealId: Long,
    enabled: Boolean,
    tag: String,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    allowRecreate: Boolean,
) {
    var expanded by remember(mealId) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(Icons.Default.MoreVert, contentDescription = "这餐的更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("更正这餐") }, enabled = enabled && allowRecreate,
                onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text("复制为草稿") }, enabled = enabled && allowRecreate,
                onClick = { expanded = false; onCopy() })
            DropdownMenuItem(text = { Text("删除这餐", color = MaterialTheme.colorScheme.error) }, enabled = enabled,
                onClick = { expanded = false; onDelete() })
        }
    }
}
