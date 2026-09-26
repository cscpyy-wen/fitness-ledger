package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.fitnessledger.data.UserProfile
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.clampedProgress
import com.personal.fitnessledger.domain.MacroPlanPresets
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class TodayWorkoutCardModel(
    val title: String,
    val detail: String,
    val action: String,
)

internal data class TodayBodyQuickActionModel(
    val label: String,
    val opensNewMeasurement: Boolean,
)

internal fun todayBodyQuickActionModel(hasBodyMeasurements: Boolean): TodayBodyQuickActionModel =
    if (hasBodyMeasurements) {
        TodayBodyQuickActionModel(label = "身体趋势", opensNewMeasurement = false)
    } else {
        TodayBodyQuickActionModel(label = "记录身体", opensNewMeasurement = true)
    }

internal fun todayWorkoutCardModel(
    activeWorkout: WorkoutSession?,
    activeSets: List<WorkoutSet>,
    completedToday: List<WorkoutHistorySummary>,
): TodayWorkoutCardModel {
    if (activeWorkout != null) {
        val completedSuffix = if (completedToday.isEmpty()) "" else " · 今日另已完成 ${completedToday.size} 场"
        return TodayWorkoutCardModel(
            title = "训练进行中：${activeWorkout.title}",
            detail = workoutSetSummaryLabel(activeSets) + completedSuffix,
            action = "继续",
        )
    }
    if (completedToday.isEmpty()) {
        return TodayWorkoutCardModel(
            title = "今天：开始一次力量训练",
            detail = "动作库、自定义动作和主要动作 PR 已就绪",
            action = "开始",
        )
    }
    val completedSets = completedToday.sumOf { it.completedSetCount }
    val failedSets = completedToday.sumOf { it.failedSetCount }
    val volumeKg = completedToday.sumOf { it.totalVolumeKg }
    val durationSeconds = completedToday.sumOf { summary ->
        (((summary.session.endedAtMillis ?: summary.session.startedAtMillis) - summary.session.startedAtMillis) / 1_000L)
            .coerceAtLeast(0L)
    }
    val title = if (completedToday.size == 1) {
        "今日训练已完成：${completedToday.single().session.title}"
    } else {
        "今日已完成 ${completedToday.size} 场训练"
    }
    val failurePart = if (failedSets > 0) " · 失败 $failedSets 组" else ""
    return TodayWorkoutCardModel(
        title = title,
        detail = "$completedSets 个正式完成组$failurePart · 负重容量 ${formatOne(volumeKg)} kg · 用时 ${compactDuration(durationSeconds)}",
        action = "查看",
    )
}

private fun compactDuration(totalSeconds: Long): String = when {
    totalSeconds < 60L -> "${totalSeconds}秒"
    totalSeconds < 3_600L -> "${totalSeconds / 60L}分${totalSeconds % 60L}秒"
    else -> "${totalSeconds / 3_600L}时${(totalSeconds % 3_600L) / 60L}分"
}

@Composable
fun TodayScreen(
    state: AppUiState,
    onOpenFood: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenBody: () -> Unit,
    onOpenPlan: () -> Unit = {},
) {
    val target = state.profile.dailyTarget
    val consumed = state.todayNutrition
    val hasExceededTarget = consumed.carbsG > target.carbsG ||
        consumed.proteinG > target.proteinG || consumed.fatG > target.fatG
    val formatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
    val workoutCard = todayWorkoutCardModel(
        activeWorkout = state.activeWorkout,
        activeSets = state.activeSets,
        completedToday = state.todayCompletedWorkouts,
    )
    val bodyQuickAction = todayBodyQuickActionModel(
        hasBodyMeasurements = state.measurements.isNotEmpty(),
    )

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp).testTag("today-content"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("今天", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.loadedDate.format(formatter), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        item(key = "today-primary-actions") {
            TodayQuickActions(
                bodyActionLabel = bodyQuickAction.label,
                onOpenFood = onOpenFood,
                onOpenTraining = onOpenTraining,
                onOpenBody = onOpenBody,
            )
        }
        item(key = "today-nutrition") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().testTag("today-nutrition-card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("今日营养", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "热量 · 已记录 ${formatWhole(consumed.kcal)} / 目标 ${formatWhole(target.kcal)} kcal",
                            modifier = Modifier.fillMaxWidth().testTag("today-energy-summary"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MacroProgress("碳水", "carbs", consumed.carbsG, target.carbsG, MaterialTheme.colorScheme.primary)
                    MacroProgress("蛋白质", "protein", consumed.proteinG, target.proteinG, MaterialTheme.colorScheme.primary)
                    MacroProgress("脂肪", "fat", consumed.fatG, target.fatG, MaterialTheme.colorScheme.secondary)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "“已吃”按饮食记录汇总，可能未覆盖全部摄入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasExceededTarget) {
                            Text(
                                "超出仅作记录，不必挨饿补偿。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "today-plan") {
            TodayPlanCard(profile = state.profile, onOpenPlan = onOpenPlan)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(
                            workoutCard.title,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            workoutCard.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenTraining) { Text(workoutCard.action) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TodayPlanCard(profile: UserProfile, onOpenPlan: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("today-plan-card"),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackHeader = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.25f
                if (stackHeader) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TodayPlanTitle(profile)
                        TextButton(onClick = onOpenPlan, modifier = Modifier.testTag("today-open-plan")) { Text("调整方案") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TodayPlanTitle(profile, Modifier.weight(1f))
                        TextButton(onClick = onOpenPlan, modifier = Modifier.testTag("today-open-plan")) { Text("调整方案") }
                    }
                }
            }
            Text(
                "参考体重 ${formatPlanNumber(profile.referenceWeightKg)} kg",
                modifier = Modifier.fillMaxWidth().testTag("today-plan-weight"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "碳水 ${formatPlanNumber(profile.carbFactor, minimumScale = 2)} · 蛋白质 ${formatPlanNumber(profile.proteinFactor, minimumScale = 2)} · 脂肪 ${formatPlanNumber(profile.fatFactor, minimumScale = 2)}",
                modifier = Modifier.fillMaxWidth().testTag("today-plan-factors"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text("每公斤体重 · g/kg/日", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodayPlanTitle(profile: UserProfile, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("当前方案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            MacroPlanPresets.titleFor(profile),
            modifier = Modifier.fillMaxWidth().testTag("today-plan-title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// A saved custom coefficient can have more than two decimal places. Never imply a
// rounded coefficient produced the displayed target when the calculation uses more.
private fun formatPlanNumber(value: Double, minimumScale: Int = 0): String =
    BigDecimal.valueOf(value).stripTrailingZeros().let { decimal ->
        decimal.setScale(maxOf(minimumScale, decimal.scale()), RoundingMode.UNNECESSARY).toPlainString()
    }

private fun formatMacroGrams(value: Double): String =
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable
private fun MacroProgress(label: String, tag: String, current: Double, target: Double, color: Color) {
    val balance = target - current
    val balanceLabel = "${if (balance < 0) "超出" else "还差"} ${formatMacroGrams(abs(balance))} g"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackLabels = maxWidth < with(LocalDensity.current) { 24.sp.toDp() } * 12
            if (stackLabels) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    MacroBalanceText(balanceLabel, tag, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    MacroBalanceText(balanceLabel, tag, Modifier.weight(1f), TextAlign.End)
                }
            }
        }
        Text(
            "已吃 ${formatMacroGrams(current)} g · 目标 ${formatMacroGrams(target)} g",
            modifier = Modifier.fillMaxWidth().testTag("today-$tag-intake"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { clampedProgress(current, target) },
            modifier = Modifier.fillMaxWidth().height(7.dp).testTag("today-$tag-progress"),
            color = color,
            trackColor = color.copy(alpha = 0.10f),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun MacroBalanceText(label: String, tag: String, modifier: Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(
        label,
        modifier = modifier.testTag("today-$tag-balance"),
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
    )
}

@Composable
private fun TodayQuickActions(
    bodyActionLabel: String,
    onOpenFood: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenBody: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widestLabelWidth = with(LocalDensity.current) { 12.sp.toDp() } * 5
        val minimumCardWidth = widestLabelWidth + 20.dp
        val stackActions = maxWidth < minimumCardWidth * 3 + 16.dp
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("today-quick-actions-stacked"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickAction(Modifier.fillMaxWidth(), "拍照记饮食", Icons.Default.CameraAlt, "today-quick-action-photo", true, onOpenFood)
                QuickAction(Modifier.fillMaxWidth(), "记录训练", Icons.Default.FitnessCenter, "today-quick-action-training", true, onOpenTraining)
                QuickAction(Modifier.fillMaxWidth(), bodyActionLabel, Icons.Default.MonitorWeight, "today-quick-action-body", true, onOpenBody)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("today-quick-actions-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickAction(Modifier.weight(1f), "拍照记饮食", Icons.Default.CameraAlt, "today-quick-action-photo", false, onOpenFood)
                QuickAction(Modifier.weight(1f), "记录训练", Icons.Default.FitnessCenter, "today-quick-action-training", false, onOpenTraining)
                QuickAction(Modifier.weight(1f), bodyActionLabel, Icons.Default.MonitorWeight, "today-quick-action-body", false, onOpenBody)
            }
        }
    }
}

@Composable
private fun QuickAction(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    testTag: String,
    horizontal: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = (if (horizontal) modifier else modifier.fillMaxHeight()).testTag(testTag),
        shape = RoundedCornerShape(18.dp),
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    label,
                    modifier = Modifier.weight(1f).testTag("$testTag-label"),
                    fontSize = 12.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth().testTag("$testTag-label"),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
