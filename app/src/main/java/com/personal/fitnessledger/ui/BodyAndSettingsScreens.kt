package com.personal.fitnessledger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.fitnessledger.data.BodyMeasurement
import com.personal.fitnessledger.data.BodyMeasurementFormDraft
import com.personal.fitnessledger.data.isXiaomi
import com.personal.fitnessledger.data.UserProfile
import com.personal.fitnessledger.data.analysisEndpointValidationError
import com.personal.fitnessledger.domain.BodyTrendCalculator
import com.personal.fitnessledger.domain.BodyTrendSummary
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

@Composable
fun BodyScreen(
    state: AppUiState,
    onOpenMeasurement: (BodyMeasurement, String?) -> Unit,
    onUpdateMeasurement: (String, BodyMeasurementFormTransform) -> Unit,
    onSaveMeasurement: (String) -> Unit,
    onDiscardMeasurement: (String) -> Unit,
    onDeleteMeasurement: (Long) -> Unit,
    newMeasurementRequestId: String? = null,
    onHideXiaomiMeasurement: (Long) -> Unit = {},
    connectionCard: @Composable () -> Unit = {},
) {
    var windowDays by rememberSaveable { mutableIntStateOf(28) }
    var pendingDelete by remember { mutableStateOf<BodyMeasurement?>(null) }
    var pendingDiscardMeasurementFormId by rememberSaveable { mutableStateOf<String?>(null) }
    val latest = state.measurements.lastOrNull()
    val latestWaist = state.measurements.lastOrNull { it.waistCm != null }?.waistCm
    val measurementContext = when {
        latest == null ->
            "尚无身体测量；参考体重 ${formatOne(state.profile.referenceWeightKg)} kg 用于营养目标。记录时请核对本次体重，未测腰围留空。"
        latestWaist == null ->
            "尚无腰围测量，未测时留空即可。身体记录不会自动改变宏量目标。"
        else ->
            "最近腰围 ${formatOne(latestWaist)} cm。宏量目标只使用方案中确认的参考体重；身体记录不会自动改变目标。"
    }
    val trend = remember(state.measurements, state.loadedDate, windowDays) {
        BodyTrendCalculator.summarize(state.measurements, state.loadedDate, windowDays)
    }

    fun newMeasurement(): BodyMeasurement {
        val today = LocalDate.now()
        // One manual record per day: reopen it instead of accidentally erasing
        // today's measured waist when the user only wants to update their weight.
        return state.measurements.lastOrNull { !it.isXiaomi && it.date == today }
            ?: BodyMeasurement(date = today, weightKg = latest?.weightKg ?: state.profile.referenceWeightKg, waistCm = null)
    }

    LaunchedEffect(
        newMeasurementRequestId,
        state.isInitialized,
        state.bodyMeasurementFormDraft?.id,
        state.bodyMeasurementFormDraft?.shortcutRequestId,
        state.resolvedBodyMeasurementShortcutRequestId,
    ) {
        if (newMeasurementRequestId != null && state.isInitialized &&
            state.bodyMeasurementFormDraft?.shortcutRequestId != newMeasurementRequestId &&
            state.resolvedBodyMeasurementShortcutRequestId != newMeasurementRequestId
        ) {
            onOpenMeasurement(newMeasurement(), newMeasurementRequestId)
        }
    }

    LaunchedEffect(state.bodyMeasurementFormDraft?.id) {
        val pendingId = pendingDiscardMeasurementFormId
        if (pendingId != null && state.bodyMeasurementFormDraft?.id != pendingId) {
            pendingDiscardMeasurementFormId = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp).testTag("body-history-list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("身体趋势", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("称重原值与 7 日均重 · 本机保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { connectionCard() }
        state.measurements.lastOrNull { it.isXiaomi }?.let { cloud ->
            item {
                Card(shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("小米报告 · ${cloud.date}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Metric("体脂率",cloud.bodyFatPercent?.let { "${formatCloudNumber(it)}%" } ?: "—")
                            Metric("体水分",cloud.waterPercent?.let { "${formatCloudNumber(it)}%" } ?: "—")
                        }
                        Text("仅显示这次称重的原值，缺失不估算。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = windowDays == 28, onClick = { windowDays = 28 }, label = { Text("近 4 周") })
                FilterChip(selected = windowDays == 56, onClick = { windowDays = 56 }, label = { Text("近 8 周") })
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("当前体重", latest?.let { "${formatCloudNumber(it.weightKg)} kg" } ?: "—")
                        Metric("最新 7 日均重", trend.latestSevenDayAverageKg?.let { "${formatOne(it)} kg" } ?: "—")
                        Metric("较前 7 日", signedValue(trend.sevenDayAverageChangeKg, "kg"))
                    }
                    if (trend.points.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(190.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (state.measurements.isEmpty()) "尚无称重记录" else "所选时间范围内无称重记录",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (state.measurements.isEmpty()) "保存第一条身体数据后开始绘制趋势" else "可切换时间范围或补记该阶段数据",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        WeightTrendChart(trend)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChartLegend(MaterialTheme.colorScheme.onSurfaceVariant, "每日体重")
                            ChartLegend(MaterialTheme.colorScheme.primary, "7 日移动平均")
                        }
                        Text("每日优先使用手工体重，否则取当天最早一次小米称重；均值只计算有记录的日期，不把缺测当作 0。最新窗口覆盖 ${trend.latestSevenDaySampleDays}/7 天。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrendCard(Modifier.weight(1f), "${windowDays / 7} 周体重", trend.windowWeightChangeKg, "kg")
                TrendCard(Modifier.weight(1f), "${windowDays / 7} 周腰围", trend.windowWaistChangeCm, "cm")
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            measurementContext,
                            modifier = Modifier.padding(start = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { onOpenMeasurement(newMeasurement(), null) },
                enabled = !state.isSavingMeasurement,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(if (state.isSavingMeasurement) "正在保存…" else "记录或补记身体数据", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
        }
        if (state.measurements.isNotEmpty()) {
            item { Text("全部历史记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(state.measurements.asReversed(), key = { "${it.isXiaomi}:${it.id}" }) { measurement ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(measurement.date.toString() + if(measurement.isXiaomi) " · " + java.time.Instant.ofEpochSecond(measurement.cloudMeasuredAtSeconds!!).atOffset(java.time.ZoneOffset.ofTotalSeconds(measurement.cloudOffsetSeconds)).toLocalTime().toString().take(5) + " · 小米" else " · 手工", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${formatCloudNumber(measurement.weightKg)} kg" + (measurement.waistCm?.let { " · 腰围 ${formatOne(it)} cm" } ?: if(measurement.isXiaomi) "" else " · 未记录腰围"),
                                fontWeight = FontWeight.SemiBold,
                            )
                            if(measurement.isXiaomi) Text("体脂 ${measurement.bodyFatPercent?.let { formatCloudNumber(it)+"%" } ?: "—"} · 水分 ${measurement.waterPercent?.let { formatCloudNumber(it)+"%" } ?: "—"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(!measurement.isXiaomi) {
                        IconButton(onClick = { onOpenMeasurement(measurement, null) }, enabled = !state.isSavingMeasurement) {
                            Icon(Icons.Default.Edit, contentDescription = "修改 ${measurement.date} 身体记录")
                        }
                        }
                        IconButton(onClick = { pendingDelete = measurement }, enabled = !state.isSavingMeasurement) {
                            Icon(Icons.Default.Delete, contentDescription = if(measurement.isXiaomi) "隐藏 ${measurement.date} 小米记录" else "删除 ${measurement.date} 身体记录")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    if (state.isBodyMeasurementFormVisible) {
        state.bodyMeasurementFormDraft?.let { draft ->
            MeasurementDialog(
                draft = draft,
                isSaving = state.isSavingMeasurement,
                isAutoSaving = state.isAutoSavingBodyMeasurementFormDraft,
                isDurable = state.isBodyMeasurementFormDraftDurable,
                saveError = state.bodyMeasurementFormSaveError,
                onUpdate = { transform -> onUpdateMeasurement(draft.id, transform) },
                onDismissRequest = { pendingDiscardMeasurementFormId = draft.id },
                onSave = { onSaveMeasurement(draft.id) },
            )
        }
    }
    val discardFormId = pendingDiscardMeasurementFormId
    if (discardFormId != null && state.bodyMeasurementFormDraft?.id == discardFormId) {
        AlertDialog(
            onDismissRequest = { if (!state.isSavingMeasurement) pendingDiscardMeasurementFormId = null },
            title = { Text("丢弃这份身体记录？") },
            text = { Text("日期、体重和腰围已在本机保留。只有明确丢弃才会清除，且不会写入账本。") },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingMeasurement,
                    onClick = {
                        pendingDiscardMeasurementFormId = null
                        onDiscardMeasurement(discardFormId)
                    },
                ) { Text("丢弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isSavingMeasurement,
                    onClick = { pendingDiscardMeasurementFormId = null },
                ) { Text("继续填写") }
            },
        )
    }
    pendingDelete?.let { measurement ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if(measurement.isXiaomi) "隐藏这条小米记录？" else "删除 ${measurement.date} 的记录？") },
            text = { Text(if(measurement.isXiaomi) "仅从本机趋势隐藏；不会删除小米原始记录，后续同步也不会让它重新出现。" else "将删除体重 ${formatOne(measurement.weightKg)} kg 的这条历史记录，趋势会随之重新计算。") },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingMeasurement,
                    onClick = {
                        if(measurement.isXiaomi) onHideXiaomiMeasurement(measurement.id) else onDeleteMeasurement(measurement.id)
                        pendingDelete = null
                    },
                ) { Text(if(measurement.isXiaomi) "隐藏" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("保留") } },
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 19.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendCard(modifier: Modifier, label: String, change: Double?, unit: String) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                signedValue(change, unit, emptyLabel = "记录不足"),
                fontWeight = FontWeight.Bold,
                fontSize = if (change == null) 16.sp else 22.sp,
                color = if (change == null || change <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun WeightTrendChart(summary: BodyTrendSummary) {
    val points = summary.points
    val values = points.flatMap { listOf(it.measurement.weightKg, it.sevenDayAverageKg) }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 0.0
    val padding = max(0.5, (high - low) * 0.15)
    val chartLow = low - padding
    val chartHigh = high + padding
    val chartRange = max(1.0, chartHigh - chartLow)
    val averageColor = MaterialTheme.colorScheme.primary
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = bodyTrendAccessibilityText(summary) }
            .testTag("body-weight-trend-chart"),
    ) {
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        fun xFor(date: LocalDate): Float {
            val dayOffset = date.toEpochDay() - summary.windowStart.toEpochDay()
            return if (summary.windowDays <= 1) size.width / 2f else size.width * dayOffset.toFloat() / (summary.windowDays - 1).toFloat()
        }
        fun yFor(value: Double): Float = size.height * (1f - ((value - chartLow) / chartRange).toFloat())
        val rawPath = Path()
        val averagePath = Path()
        points.forEachIndexed { index, point ->
            val x = xFor(point.measurement.date)
            val rawY = yFor(point.measurement.weightKg)
            val averageY = yFor(point.sevenDayAverageKg)
            if (index == 0) {
                rawPath.moveTo(x, rawY)
                averagePath.moveTo(x, averageY)
            } else {
                rawPath.lineTo(x, rawY)
                averagePath.lineTo(x, averageY)
            }
            drawCircle(rawColor, radius = 3.dp.toPx(), center = Offset(x, rawY))
        }
        drawPath(rawPath, rawColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        drawPath(averagePath, averageColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
    }
}

internal fun bodyTrendAccessibilityText(summary: BodyTrendSummary): String {
    val headline = buildString {
        append("体重趋势，近 ")
        append(summary.windowDays)
        append("天，共 ")
        append(summary.points.size)
        append("条记录")
        summary.latestSevenDayAverageKg?.let {
            append("，最新7日均值 ")
            append(formatOne(it))
            append(" kg")
        }
        summary.sevenDayAverageChangeKg?.let {
            append("，较前7日 ")
            append(signedValue(it, "kg"))
        }
    }
    val rows = summary.points.joinToString(separator = "；") { point ->
        "${point.measurement.date}，原始体重 ${formatCloudNumber(point.measurement.weightKg)} kg，" +
            "7日均值 ${formatOne(point.sevenDayAverageKg)} kg"
    }
    return if (rows.isBlank()) headline else "$headline。每日数据：$rows"
}

@Composable
private fun ChartLegend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun signedValue(value: Double?, unit: String, emptyLabel: String = "—"): String =
    value?.let { "${if (it > 0) "+" else ""}${formatOne(it)} $unit" } ?: emptyLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDialog(
    draft: BodyMeasurementFormDraft,
    isSaving: Boolean,
    isAutoSaving: Boolean,
    isDurable: Boolean,
    saveError: String?,
    onUpdate: (BodyMeasurementFormTransform) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    var showDatePicker by rememberSaveable(draft.id) { mutableStateOf(false) }
    val parsedWeight = draft.weightText.trim().replace(',', '.').toDoubleOrNull()
    val parsedWaist = draft.waistText.trim().replace(',', '.').toDoubleOrNull()
    val weightError = boundedDecimalError(draft.weightText, "体重", 30.0, 300.0, "30–300 kg")
    val waistError = if (draft.waistText.isBlank()) null else {
        boundedDecimalError(draft.waistText, "腰围", 30.0, 250.0, "30–250 cm")
    }
    val futureDate = draft.date > LocalDate.now()
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        title = { Text(if (draft.measurementId > 0L) "修改身体记录" else "记录身体数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("body-date-input")
                        .then(
                            if (!futureDate) Modifier else Modifier.semantics {
                                error("不能记录未来日期")
                            },
                        ),
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Text(draft.date.toString(), modifier = Modifier.padding(start = 8.dp))
                }
                DecimalTextField(
                    draft.weightText,
                    { value -> onUpdate { it.copy(weightText = value) } },
                    "体重 kg",
                    modifier = Modifier.fillMaxWidth().testTag("body-weight-input"),
                    errorMessage = weightError,
                    rangeHint = "合法范围：30–300 kg",
                    enabled = !isSaving,
                )
                DecimalTextField(
                    draft.waistText,
                    { value -> onUpdate { it.copy(waistText = value) } },
                    "腰围 cm（可选）",
                    modifier = Modifier.fillMaxWidth().testTag("body-waist-input"),
                    errorMessage = waistError,
                    rangeHint = "留空或填写 30–250 cm",
                    enabled = !isSaving,
                )
                if (futureDate) Text("不能记录未来日期", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (draft.measurementId == 0L) Text("同一天再次保存会更新当天记录。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Column(
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("body-form-save-status"),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val durabilityWarning = !isSaving && !isAutoSaving && !isDurable
                    Text(
                        when {
                            isSaving -> "正在写入本机账本…"
                            isAutoSaving -> "正在自动保存原始输入…"
                            durabilityWarning -> "当前原始输入尚未保存到本机，请勿强制关闭；请修改任一字段重试。"
                            else -> "原始日期、体重和腰围已保留；强制关闭后也可恢复。"
                        },
                        color = if (durabilityWarning) MaterialTheme.colorScheme.error else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 11.sp,
                    )
                    if (saveError != null) {
                        Text(saveError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && !futureDate && weightError == null && waistError == null &&
                    parsedWeight != null && (draft.waistText.isBlank() || parsedWaist != null),
                onClick = onSave,
            ) { Text(if (isSaving) "正在保存…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismissRequest) { Text("关闭或丢弃") }
        },
    )
    if (showDatePicker) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = draft.date.toEpochDay() * BODY_MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                val selectedDate = pickerState.selectedDateMillis?.let { LocalDate.ofEpochDay(it / BODY_MILLIS_PER_DAY) }
                TextButton(
                    enabled = selectedDate != null && selectedDate <= LocalDate.now(),
                    onClick = {
                        val chosen = requireNotNull(selectedDate)
                        onUpdate { it.copy(date = chosen) }
                        showDatePicker = false
                    },
                ) { Text("选择") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}

private const val BODY_MILLIS_PER_DAY = 86_400_000L

@Composable
fun InitialSetupScreen(
    profile: UserProfile,
    isSaving: Boolean,
    onSaveProfile: (UserProfile) -> Unit,
) {
    MacroPlanEditor(
        profile = profile,
        isSaving = isSaving,
        isInitialSetup = true,
        onSaveProfile = onSaveProfile,
        modifier = Modifier.safeDrawingPadding(),
    )
}

private enum class BackupDialogAction { EXPORT, IMPORT }

@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveProfile: (UserProfile) -> Unit,
    onSaveAnalysisConfig: (String, String, (Boolean) -> Unit) -> Unit,
    onClearAnalysisToken: ((Boolean) -> Unit) -> Unit,
    onExportBackup: (Uri, CharArray) -> Unit,
    onImportBackup: (Uri, CharArray) -> Unit,
    onBack: (() -> Unit)? = null,
    onSaveVisionConfig: (String, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onSaveAnalysisProfile: ((String?, String, String, String, String, (Boolean) -> Unit) -> Unit)? = null,
    onSelectAnalysisProfile: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onDeleteAnalysisProfile: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onClearActiveAnalysisConnection: ((Boolean) -> Unit) -> Unit = { done -> done(false) },
    onSaveAnalysisAdditionalPrompt: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
) {
    var endpoint by rememberSaveable(state.analysisEndpoint) {
        mutableStateOf(if (state.analysisTransport == com.personal.fitnessledger.data.AnalysisTransport.MEAL_PROXY) state.analysisEndpoint else "")
    }
    var showProxy by rememberSaveable { mutableStateOf(false) }
    var accessToken by remember { mutableStateOf("") }
    var pendingClearProxyEndpoint by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClearProxyId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupDialogAction by rememberSaveable { mutableStateOf<BackupDialogAction?>(null) }
    var pendingBackupUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember(backupDialogAction, pendingBackupUri) { mutableStateOf("") }
    var backupPasswordConfirmation by remember(backupDialogAction, pendingBackupUri) { mutableStateOf("") }
    var restoreConfirmation by remember(backupDialogAction, pendingBackupUri) { mutableStateOf("") }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingBackupUri = uri.toString()
            backupDialogAction = BackupDialogAction.EXPORT
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingBackupUri = uri.toString()
            backupDialogAction = BackupDialogAction.IMPORT
        }
    }

    fun closeBackupDialog() {
        backupPassword = ""
        backupPasswordConfirmation = ""
        restoreConfirmation = ""
        pendingBackupUri = null
        backupDialogAction = null
    }

    val normalizedEndpoint = endpoint.trim()
    val hasCurrentProxy = state.analysisTransport == com.personal.fitnessledger.data.AnalysisTransport.MEAL_PROXY &&
        state.analysisEndpoint.isNotBlank()
    val proxyBusy = state.isSavingAnalysisConfig || state.isAnalyzingPhoto || state.isImportingBackup ||
        state.isExportingBackup || state.restoreRecoveryRequired || state.analysisProfilesUnreadable
    val endpointError = analysisEndpointValidationError(normalizedEndpoint)
    val endpointChanged = normalizedEndpoint != state.analysisEndpoint.trim() ||
        state.analysisTransport != com.personal.fitnessledger.data.AnalysisTransport.MEAL_PROXY
    val tokenRequired = normalizedEndpoint.isNotBlank() && (endpointChanged || !state.analysisTokenConfigured)
    val analysisConfigValid = when {
        normalizedEndpoint.isBlank() -> hasCurrentProxy && accessToken.isBlank()
        endpointError != null -> false
        tokenRequired -> accessToken.isNotBlank()
        else -> true
    }
    val backupBusy = state.isExportingBackup || state.isImportingBackup
    val exportBlocked = backupWriteInProgress(state)
    val restoreBlockReason = backupRestoreBlockReason(state)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            Text("设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }

        VisionSettingsCard(state, onSaveVisionConfig, onClearAnalysisToken,
            onSaveProfile = onSaveAnalysisProfile,
            onSelectProfile = onSelectAnalysisProfile,
            onDeleteProfile = onDeleteAnalysisProfile)
        AnalysisAdditionalPromptCard(state, onSaveAnalysisAdditionalPrompt)
        TextButton(onClick = { showProxy = !showProxy }, modifier = Modifier.testTag("toggle-proxy-settings")) {
            Text(if (showProxy) "收起高级代理设置" else "高级：使用自建识别代理")
        }
        if (showProxy) Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("自建识别代理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "供已经部署本项目代理服务的用户使用。这里填写代理令牌，不是大模型 API Key；普通使用请选上方直连方式。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "清空地址后保存会先确认，再删除当前代理连接和令牌并停用上传；其他服务会保留。",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    "启用可选代理后，去除 EXIF 并压缩的照片与已保存的附加说明会发给代理及其上游视觉模型。" +
                        "使用项目附带的代理协调库时，默认会以明文保留完整识别响应 7 天；" +
                        "原图不会写入该协调库。部署者应为协调库启用静态加密，并尽量缩短或关闭响应留存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis-endpoint-input")
                        .then(
                            if (endpointError == null) Modifier else Modifier.semantics {
                                error(endpointError)
                            },
                        ),
                    label = { Text("HTTPS 识别代理地址") },
                    placeholder = { Text("https://example.com/analyze-meal") },
                    supportingText = if (endpointError == null) null else {
                        { Text(endpointError) }
                    },
                    isError = endpointError != null,
                    minLines = 2,
                    enabled = !proxyBusy,
                )
                OutlinedTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            analysisTokenFieldLabel(
                                draftEndpoint = normalizedEndpoint,
                                savedEndpoint = state.analysisEndpoint,
                                tokenConfigured = state.analysisTokenConfigured,
                            ),
                        )
                    },
                    supportingText = {
                        Text(
                            when {
                                normalizedEndpoint.isBlank() -> "空地址仅用于停用当前代理，确认后删除该连接和令牌；不要填写视觉模型 API Key"
                                endpointChanged && state.analysisEndpoint.isNotBlank() -> "代理地址已更改，必须输入该地址的新令牌，旧令牌不会发送到新地址"
                                endpointChanged -> "填写代理地址时必须同时输入访问令牌；不要填写视觉模型 API Key"
                                state.analysisTokenConfigured -> "已有令牌已由 Android Keystore 加密；地址不变时留空表示保持不变"
                                else -> "当前地址尚未配置令牌，保存前必须输入；不要填写视觉模型 API Key"
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !proxyBusy,
                )
                OutlinedButton(
                    onClick = {
                        if (normalizedEndpoint.isBlank()) {
                            pendingClearProxyEndpoint = state.analysisEndpoint
                            pendingClearProxyId = state.activeAnalysisProfileId
                        } else {
                            onSaveAnalysisConfig(endpoint, accessToken) { success ->
                                if (success) accessToken = ""
                            }
                        }
                    },
                    enabled = analysisConfigValid && !proxyBusy,
                    modifier = Modifier.fillMaxWidth().testTag("save-proxy-config"),
                ) { Text(when {
                    state.isSavingAnalysisConfig -> "正在保存识别服务…"
                    normalizedEndpoint.isBlank() && hasCurrentProxy -> "停用并删除当前代理连接…"
                    else -> "保存识别服务设置"
                }) }
                if (hasCurrentProxy) {
                    TextButton(
                        onClick = {
                            pendingClearProxyEndpoint = state.analysisEndpoint
                            pendingClearProxyId = state.activeAnalysisProfileId
                        },
                        enabled = !proxyBusy,
                        modifier = Modifier.fillMaxWidth().testTag("clear-current-proxy"),
                    ) { Text("停用上传并删除当前代理连接") }
                }
            }
        }

        OutlinedCard(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().testTag("encrypted-backup-card"),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("加密备份与恢复", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "导出身体、饮食、训练、草稿、模板与账本照片。文件使用口令加密；识别服务配置、API Key、代理令牌和临时相机文件不会写入备份。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "请把备份和口令分开保管：忘记口令无法找回。恢复会先完整验真，成功后替换本机账本；任何失败都会保留现有数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = {
                        exportBackupLauncher.launch("健身账本-加密备份-${LocalDate.now()}.fitnessbackup")
                    },
                    enabled = !exportBlocked,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("export-encrypted-backup"),
                ) {
                    Text(if (state.isExportingBackup) "正在导出…" else "导出加密备份")
                }
                OutlinedButton(
                    onClick = { importBackupLauncher.launch(arrayOf("*/*")) },
                    enabled = restoreBlockReason == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("import-encrypted-backup"),
                ) {
                    Text(if (state.isImportingBackup) "正在恢复…" else "从备份恢复")
                }
                when {
                    backupBusy -> Text(
                        if (state.isImportingBackup) "正在验证并恢复，请勿关闭 App" else "正在生成加密文件，请勿关闭 App",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    restoreBlockReason != null -> Text(
                        "$restoreBlockReason，之后才能恢复；导出会在当前写入完成后可用。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("backup-block-reason"),
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("隐私边界", fontWeight = FontWeight.Bold)
                Text(
                    if (!state.analysisTokenConfigured) {
                        "当前照片上传未启用。身体、饮食和训练保存在本机；小米同步使用其独立授权。只有你核对并确认的餐食才进入统计。"
                    } else if (state.analysisTransport == com.personal.fitnessledger.data.AnalysisTransport.VISION_API) {
                        "整餐照片去除 EXIF 并压缩后，与已保存的附加说明一起发送至所选模型服务；不会自动附带身体、训练或小米账本数据。API Key 仅在本机加密保存，不会内置到安装包。服务端留存由供应商政策决定。"
                    } else {
                        "身体、饮食和训练数据默认保存在本机；当前已启用云端代理，照片去除 EXIF 并压缩后，与已保存的附加说明一起发送给代理及所选视觉模型供应商。请同时确认代理协调库与供应商的留存、加密和删除政策。只有你确认的餐食才进入统计。"
                    },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    pendingClearProxyEndpoint?.let { savedEndpoint ->
        val stillCurrent = hasCurrentProxy && state.analysisEndpoint == savedEndpoint &&
            state.activeAnalysisProfileId == pendingClearProxyId
        AlertDialog(
            onDismissRequest = { pendingClearProxyEndpoint = null; pendingClearProxyId = null },
            title = { Text("删除当前代理连接和令牌？") },
            text = { Text(if (stillCurrent)
                "将删除当前代理的名称、地址和令牌，并停用照片上传。其他已保存服务会保留，不会自动启用。"
                else "当前服务已更改，请取消后重新选择要删除的代理连接。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearActiveAnalysisConnection { success ->
                        if (success) {
                            accessToken = ""
                            endpoint = ""
                            pendingClearProxyEndpoint = null
                            pendingClearProxyId = null
                        }
                    }
                }, enabled = !proxyBusy && stillCurrent,
                    modifier = Modifier.testTag("confirm-clear-current-proxy")) { Text("删除当前代理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearProxyEndpoint = null; pendingClearProxyId = null },
                    modifier = Modifier.testTag("cancel-clear-current-proxy")) { Text("取消") }
            },
        )
    }

    val backupAction = backupDialogAction
    val backupUri = pendingBackupUri
    if (backupAction != null && backupUri != null) {
        val passwordLongEnough = backupPassword.length >= 8
        val canConfirm = when (backupAction) {
            BackupDialogAction.EXPORT ->
                passwordLongEnough && backupPassword == backupPasswordConfirmation
            BackupDialogAction.IMPORT ->
                passwordLongEnough && restoreConfirmation.trim() == "恢复本机账本"
        }
        AlertDialog(
            onDismissRequest = ::closeBackupDialog,
            title = {
                Text(if (backupAction == BackupDialogAction.EXPORT) "设置备份口令" else "确认替换本机账本")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (backupAction == BackupDialogAction.EXPORT) {
                            "口令至少 8 个字符。App 不保存口令，遗忘后无法恢复这个备份。"
                        } else {
                            "恢复前会验证口令、文件完整性与版本。验证成功后，当前本机账本会被备份内容整体替换，识别代理需要重新配置。"
                        },
                    )
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                        label = { Text(if (backupAction == BackupDialogAction.EXPORT) "新备份口令" else "备份口令") },
                        supportingText = {
                            Text(if (passwordLongEnough) "长度符合要求" else "至少 8 个字符")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    if (backupAction == BackupDialogAction.EXPORT) {
                        OutlinedTextField(
                            value = backupPasswordConfirmation,
                            onValueChange = { backupPasswordConfirmation = it },
                            modifier = Modifier.fillMaxWidth().testTag("backup-password-confirmation"),
                            label = { Text("再次输入口令") },
                            supportingText = if (
                                backupPasswordConfirmation.isNotEmpty() &&
                                backupPassword != backupPasswordConfirmation
                            ) {
                                { Text("两次口令不一致") }
                            } else {
                                null
                            },
                            isError = backupPasswordConfirmation.isNotEmpty() &&
                                backupPassword != backupPasswordConfirmation,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                        )
                    } else {
                        OutlinedTextField(
                            value = restoreConfirmation,
                            onValueChange = { restoreConfirmation = it },
                            modifier = Modifier.fillMaxWidth().testTag("restore-confirmation"),
                            label = { Text("输入“恢复本机账本”继续") },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val destination = Uri.parse(backupUri)
                        val passphrase = backupPassword.toCharArray()
                        closeBackupDialog()
                        if (backupAction == BackupDialogAction.EXPORT) {
                            onExportBackup(destination, passphrase)
                        } else {
                            onImportBackup(destination, passphrase)
                        }
                    },
                    enabled = canConfirm,
                    modifier = Modifier.testTag("backup-dialog-confirm"),
                ) {
                    Text(if (backupAction == BackupDialogAction.EXPORT) "加密并导出" else "验证并恢复")
                }
            },
            dismissButton = { TextButton(onClick = ::closeBackupDialog) { Text("取消") } },
        )
    }
}

@Composable
internal fun DecimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorMessage: String? = null,
    rangeHint: String? = null,
    enabled: Boolean = true,
) {
    val fieldModifier = if (errorMessage != null) {
        modifier.semantics { error(errorMessage) }
    } else {
        modifier
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (errorMessage != null || rangeHint != null) {
            { Text(requireNotNull(errorMessage ?: rangeHint)) }
        } else {
            null
        },
        isError = errorMessage != null,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = fieldModifier,
    )
}

internal fun boundedDecimalError(
    value: String,
    fieldName: String,
    minimum: Double,
    maximum: Double,
    legalRangeLabel: String,
): String? {
    val normalized = value.trim().replace(',', '.')
    val parsed = normalized.toDoubleOrNull()
    return when {
        normalized.isEmpty() -> "请输入$fieldName；合法范围：$legalRangeLabel"
        parsed == null || !parsed.isFinite() -> "${fieldName}必须是有效数字；合法范围：$legalRangeLabel"
        parsed !in minimum..maximum -> "${fieldName}超出范围；合法范围：$legalRangeLabel"
        else -> null
    }
}

internal fun analysisTokenFieldLabel(
    draftEndpoint: String,
    savedEndpoint: String,
    tokenConfigured: Boolean,
): String {
    val normalizedDraft = draftEndpoint.trim()
    val normalizedSaved = savedEndpoint.trim()
    return when {
        normalizedDraft.isBlank() -> "代理访问令牌（无需填写）"
        normalizedDraft != normalizedSaved && normalizedSaved.isNotBlank() ->
            "新的代理访问令牌（更换地址时必填）"
        normalizedDraft != normalizedSaved -> "代理访问令牌（填写地址时必填）"
        tokenConfigured -> "新的代理访问令牌（可留空保留）"
        else -> "代理访问令牌（当前未配置，必填）"
    }
}
