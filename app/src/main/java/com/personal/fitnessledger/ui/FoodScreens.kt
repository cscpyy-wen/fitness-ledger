package com.personal.fitnessledger.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.fitnessledger.data.EvidenceTier
import com.personal.fitnessledger.data.AnalysisMode
import com.personal.fitnessledger.data.CalorieSource
import com.personal.fitnessledger.data.FoodDraftItem
import com.personal.fitnessledger.data.FoodHypothesis
import com.personal.fitnessledger.data.ManualFoodFormDraft
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PortionBasis
import com.personal.fitnessledger.data.SavedFood
import com.personal.fitnessledger.data.commitValidationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

private const val MEAL_COMMIT_TOUCH_GUARD_MILLIS = 900L

@Composable
private fun FoodSecondaryActions(
    resumeManual: Boolean,
    photoEnabled: Boolean,
    manualEnabled: Boolean,
    onAlbum: () -> Unit,
    onManual: () -> Unit,
) {
    val album: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onAlbum, enabled = photoEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = modifier.heightIn(min = 48.dp).testTag("food-album-action")) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("从相册选择", modifier = Modifier.weight(1f).padding(start = 6.dp))
        }
    }
    val manual: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onManual, enabled = manualEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = modifier.heightIn(min = 48.dp).testTag("food-manual-action")) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(if (resumeManual) "继续未完成手工记录" else "手工记录一餐", modifier = Modifier.weight(1f).padding(start = 6.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A saved form has a longer label. Stack it and large text instead of
        // squeezing text or reducing touch targets to make the row fit.
        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.15f || resumeManual) {
            Column(Modifier.fillMaxWidth().testTag("food-secondary-stacked"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                album(Modifier.fillMaxWidth())
                manual(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().testTag("food-secondary-row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                album(Modifier.weight(1f))
                manual(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MealCommitReceiptScreen(
    receipt: MealCommitReceipt,
    onContinue: () -> Unit,
) {
    var continueEnabled by rememberSaveable(receipt.id) { mutableStateOf(false) }
    LaunchedEffect(receipt.id) {
        // Consume the tail of a double tap before any control can dismiss this
        // screen and reveal bottom navigation at the same coordinates.
        delay(MEAL_COMMIT_TOUCH_GUARD_MILLIS)
        continueEnabled = true
    }
    BackHandler(enabled = true) {
        if (continueEnabled) onContinue()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .testTag("meal-commit-receipt"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Text("已计入饮食账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                receipt.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Button(
                onClick = onContinue,
                enabled = continueEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("meal-commit-continue"),
            ) {
                Text(if (continueEnabled) "返回饮食" else "正在确认写入…")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodScreen(
    state: AppUiState,
    onAnalyzePhoto: (Uri) -> Unit,
    onOpenManualEntry: (FoodDraftItem) -> Unit,
    onUpdateManualEntry: (String, ManualFoodFormTransform) -> Unit,
    onKeepManualEntry: (String) -> Unit,
    onDiscardManualEntry: (String) -> Unit,
    onCreateManualEntry: (String) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onEditMeal: (Long) -> Unit,
    onCopyMeal: (Long) -> Unit,
    onDeleteMeal: (Long) -> Unit,
    onToggleFoodFavorite: (SavedFood) -> Unit,
    onRequestCameraCapture: () -> Unit,
    onCameraLaunchHandled: (Long, Boolean) -> Unit,
    onCameraCaptureResult: (Boolean) -> Unit,
    onOpenPhotoSettings: () -> Unit = {},
    onSelectAnalysisProfile: (String) -> Unit = {},
) {
    var showServiceMenu by remember { mutableStateOf(false) }
    var mealPendingDelete by rememberSaveable(state.selectedFoodDate.toString()) { mutableStateOf<Long?>(null) }
    var mealDetailId by rememberSaveable(state.selectedFoodDate.toString()) { mutableStateOf<Long?>(null) }
    val foodListState = rememberLazyListState()
    val foodScope = rememberCoroutineScope()
    val selectedMeals = if (state.isLoadingFoodDate) emptyList() else
        state.selectedDateMeals.filter { it.date == state.selectedFoodDate }
    val viewedMeal = selectedMeals.firstOrNull { it.id == mealDetailId }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAllSavedFoods by rememberSaveable { mutableStateOf(false) }
    var showPortionCalculator by rememberSaveable { mutableStateOf(false) }
    var pendingManualCloseFormId by rememberSaveable { mutableStateOf<String?>(null) }
    val foodOperationLocked = state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal ||
        state.isLoadingFoodDate || state.isPreparingCameraCapture || state.isHandlingCameraResult ||
        state.cameraLaunchRequest != null || state.isSavingManualFoodFormDraft || state.isSavingAnalysisConfig
    val foodMutationLocked = foodOperationLocked || state.manualFoodFormDraft != null
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = onCameraCaptureResult,
    )
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAnalyzePhoto(uri)
    }

    LaunchedEffect(state.cameraLaunchRequest) {
        state.cameraLaunchRequest?.let { request ->
            val launched = runCatching { cameraLauncher.launch(request.uri) }.isSuccess
            onCameraLaunchHandled(request.id, launched)
        }
    }

    if (showPortionCalculator) {
        FoodPortionCalculator(
            target = state.profile.dailyTarget,
            consumed = state.selectedDateNutrition,
            date = state.selectedFoodDate,
            onDismiss = { showPortionCalculator = false },
        )
    }

    if (viewedMeal != null) {
        MealDetailsScreen(
            meal = viewedMeal,
            canMutate = !foodMutationLocked && state.mealDraft == null,
            onBack = { mealDetailId = null },
            onEdit = { mealDetailId = null; onEditMeal(viewedMeal.id) },
            onCopy = { mealDetailId = null; onCopyMeal(viewedMeal.id) },
            onDelete = { mealPendingDelete = viewedMeal.id },
        )
    } else {
        LazyColumn(
            state = foodListState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp).testTag("food-record-list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.height(10.dp))
                Text("饮食记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (selectedMeals.isNotEmpty()) {
                    TextButton(onClick = { foodScope.launch { foodListState.animateScrollToItem(3) } },
                        modifier = Modifier.testTag("food-view-records")) {
                        Text("已记录 ${selectedMeals.size} 餐 · 查看记录")
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = { onSelectDate(state.selectedFoodDate.minusDays(1)) },
                            enabled = !foodMutationLocked,
                        ) { Icon(Icons.Default.ChevronLeft, contentDescription = "前一天") }
                        TextButton(onClick = { showDatePicker = true }, enabled = !foodMutationLocked,
                            modifier = Modifier.weight(1f).testTag("food-select-date")) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Text(
                                if (state.selectedFoodDate == state.loadedDate) "今天 · ${state.selectedFoodDate}" else state.selectedFoodDate.toString(),
                                modifier = Modifier.padding(start = 8.dp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(
                            onClick = { onSelectDate(state.selectedFoodDate.plusDays(1)) },
                            enabled = !foodMutationLocked && state.selectedFoodDate < state.loadedDate,
                        ) { Icon(Icons.Default.ChevronRight, contentDescription = "后一天") }
                    }
                }
                if (state.selectedFoodDate != state.loadedDate) {
                    TextButton(
                        onClick = { onSelectDate(state.loadedDate) },
                        enabled = !foodMutationLocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("回到今天") }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text("整餐拍一次", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("food-entry-actions"))
                            Box {
                                val selectedService = state.analysisProfiles.firstOrNull { it.id == state.activeAnalysisProfileId }
                                TextButton(onClick = { showServiceMenu = true }, enabled = !foodMutationLocked,
                                    modifier = Modifier.testTag("food-select-service")) {
                                    Text((selectedService?.name?.take(12) ?: if (state.analysisTokenConfigured) "当前连接" else "选择服务") + " ▾")
                                }
                                DropdownMenu(expanded = showServiceMenu, onDismissRequest = { showServiceMenu = false }) {
                                    state.analysisProfiles.forEach { service ->
                                        DropdownMenuItem(text = { Text((if (service.id == state.activeAnalysisProfileId) "✓ " else "") + service.name) },
                                            onClick = { showServiceMenu = false; onSelectAnalysisProfile(service.id) },
                                            enabled = !foodMutationLocked && service.tokenConfigured,
                                            modifier = Modifier.testTag("food-service-${service.id}"))
                                    }
                                    DropdownMenuItem(text = { Text("管理模型服务") }, onClick = {
                                        showServiceMenu = false; onOpenPhotoSettings()
                                    }, enabled = !foodMutationLocked)
                                }
                            }
                        }
                        Text(
                            if (!state.analysisTokenConfigured)
                                "可直接手工记录；拍照需先连接视觉模型。"
                            else "主食、菜和饮品一起拍，核对份量后入账。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = if (state.analysisTokenConfigured) onRequestCameraCapture else onOpenPhotoSettings,
                                enabled = !foodMutationLocked,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food-camera-action"),
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Text(
                                    if (!state.analysisTokenConfigured) "连接外置大模型" else
                                        if (state.pendingCameraUri == null) "拍整餐并识别" else "继续上次照片",
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        FoodSecondaryActions(
                            resumeManual = state.manualFoodFormDraft != null,
                            photoEnabled = !foodMutationLocked,
                            manualEnabled = !foodOperationLocked,
                            onAlbum = {
                                if (state.analysisTokenConfigured) pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                else onOpenPhotoSettings()
                            },
                            onManual = { onOpenManualEntry(newManualFoodDraftItem()) },
                        )
                        if (state.isAnalyzingPhoto) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("正在识别整餐与估算份量…请勿重复提交", modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
            item(key = "meal-history-heading") {
                Text(
                    if (state.selectedFoodDate == state.loadedDate) "今天已确认" else "${state.selectedFoodDate} 已确认",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("food-meal-history"),
                )
                if (state.isLoadingFoodDate) {
                    Text("正在读取这一天的记录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${selectedMeals.size} 餐 · ${formatWhole(state.selectedDateNutrition.kcal)} kcal",
                        style = MaterialTheme.typography.titleMedium)
                    Text(state.selectedDateNutrition.compactLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.isLoadingFoodDate && selectedMeals.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("这一天还没有已确认的饮食记录。拍照或手工记录后，每餐都会出现在这里。",
                            modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(selectedMeals, key = { "meal-${it.id}" }) { meal ->
                    MealHistoryCard(
                        meal = meal,
                        canMutate = !foodMutationLocked && state.mealDraft == null,
                        onOpenDetails = { mealDetailId = meal.id },
                        onEdit = { onEditMeal(meal.id) },
                        onCopy = { onCopyMeal(meal.id) },
                        onDelete = { mealPendingDelete = meal.id },
                    )
                }
            }
            item {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("按当前方案对照", fontWeight = FontWeight.SemiBold)
                        if (!state.isLoadingFoodDate) {
                            val remaining = state.profile.dailyTarget - state.selectedDateNutrition
                            Text(listOf("碳水" to remaining.carbsG, "蛋白质" to remaining.proteinG, "脂肪" to remaining.fatG)
                                .joinToString(" · ") { (name, grams) -> "$name${if (grams >= 0) "还差" else "超出"} ${formatOne(abs(grams))} g" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.selectedFoodDate != state.loadedDate) {
                            Text("这里使用现在的目标，并非当日历史目标。", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showPortionCalculator = true }, enabled = !foodMutationLocked,
                            modifier = Modifier.testTag("open-portion-calculator")) { Text("换算食物份量") }
                    }
                }
            }
            if (state.savedFoods.isNotEmpty()) {
                item {
                    Text("最近与收藏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("点食物后只需调整本次克重；星标会长期置顶", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                val visibleSavedFoods = if (showAllSavedFoods) state.savedFoods else state.savedFoods.take(8)
                items(visibleSavedFoods, key = { "saved-food-${it.id}" }) { food ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onOpenManualEntry(food.toDraftItem()) },
                                enabled = !foodMutationLocked,
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(food.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "默认 ${formatOne(food.defaultGrams)} g · ${formatWhole(food.per100g.kcal)} kcal/100 g · 用过 ${food.useCount} 次",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { onToggleFoodFavorite(food) }, enabled = !foodMutationLocked) {
                                Icon(
                                    if (food.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (food.isFavorite) "取消收藏" else "收藏食物",
                                    tint = if (food.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.savedFoods.size > 8) {
                    item {
                        TextButton(
                            onClick = { showAllSavedFoods = !showAllSavedFoods },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (showAllSavedFoods) "收起常用食物" else "查看全部 ${state.savedFoods.size} 项") }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val manualForm = state.manualFoodFormDraft
    LaunchedEffect(manualForm?.id) {
        val pendingId = pendingManualCloseFormId
        if (pendingId != null && manualForm?.id != pendingId) {
            pendingManualCloseFormId = null
        }
    }
    if (state.isManualFoodFormVisible && manualForm != null && pendingManualCloseFormId != manualForm.id) {
        FoodEditorDialog(
            title = "手工记录食物",
            initial = manualForm.toInitialFoodDraftItem(),
            deriveKcalFromMacros = true,
            isSaving = state.isSavingMealDraft || state.isSavingManualFoodFormDraft,
            saveError = state.manualFoodFormSaveError,
            saveStatus = when {
                state.isAutoSavingManualFoodFormDraft -> "正在自动保存到本机…"
                state.manualFoodFormSaveError != null -> null
                manualForm.isDirty -> "已自动保存到本机；此时强制关闭也可恢复"
                else -> "开始填写后会自动保存，确认前不会计入账本"
            },
            manualForm = manualForm,
            onManualFormChange = { transform -> onUpdateManualEntry(manualForm.id, transform) },
            onDismiss = {
                if (!state.isSavingMealDraft && !state.isSavingManualFoodFormDraft) {
                    // Always ask while a form exists. The callback may belong to
                    // the composition immediately before the latest keystroke;
                    // terminal actions below carry the current form ID.
                    pendingManualCloseFormId = manualForm.id
                }
            },
            onSave = { onCreateManualEntry(manualForm.id) },
        )
    }

    val closeFormId = pendingManualCloseFormId
    if (closeFormId != null && manualForm?.id == closeFormId) {
        AlertDialog(
            onDismissRequest = { if (!state.isSavingManualFoodFormDraft) pendingManualCloseFormId = null },
            title = { Text("保留未完成的手工记录？") },
            text = { Text("原始输入会保存在本机，尚未计入账本。你可以稍后继续，或明确丢弃。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = !state.isSavingManualFoodFormDraft,
                        onClick = {
                            onDiscardManualEntry(closeFormId)
                            pendingManualCloseFormId = null
                        },
                    ) { Text("丢弃草稿", color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !state.isSavingManualFoodFormDraft,
                        onClick = {
                            onKeepManualEntry(closeFormId)
                            pendingManualCloseFormId = null
                        },
                    ) { Text("保留并关闭") }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isSavingManualFoodFormDraft,
                    onClick = { pendingManualCloseFormId = null },
                ) { Text("继续编辑") }
            },
        )
    }

    mealPendingDelete?.let { mealId ->
        AlertDialog(
            onDismissRequest = { mealPendingDelete = null },
            title = { Text("删除这餐？") },
            text = { Text((selectedMeals.firstOrNull { it.id == mealId }?.let { mealFoodSummary(it) + "\n\n" } ?: "") +
                "删除后会重新计算所选日期摄入。若只是克重或食材有误，可在更多操作中选择“更正这餐”。") },
            confirmButton = {
                TextButton(
                    enabled = !foodMutationLocked,
                    onClick = {
                        onDeleteMeal(mealId)
                        mealPendingDelete = null
                        mealDetailId = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { mealPendingDelete = null }) { Text("取消") } },
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedFoodDate.toEpochDay() * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        val epochDay = requireNotNull(datePickerState.selectedDateMillis) / MILLIS_PER_DAY
                        onSelectDate(LocalDate.ofEpochDay(epochDay))
                        showDatePicker = false
                    },
                ) { Text("选择") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDraftScreen(
    state: AppUiState,
    onUpdateItem: (FoodDraftItem, (Boolean) -> Unit) -> Unit,
    onRemoveItem: (String) -> Unit,
    onAddItem: (FoodDraftItem, (Boolean) -> Unit) -> Unit,
    onResolveHypothesis: (FoodHypothesis, FoodDraftItem, (Boolean) -> Unit) -> Unit,
    onReviewedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onChangeTargetDate: (LocalDate) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    onOpenPhotoSettings: () -> Unit = {},
) {
    val draft = requireNotNull(state.mealDraft)
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var portionItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var portionFraction by rememberSaveable { mutableStateOf(1.0) }
    var showReplaceAnalysis by rememberSaveable { mutableStateOf(false) }
    var addingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingHypothesisId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingEditSignature by rememberSaveable { mutableStateOf<String?>(null) }
    var editSaveObservedBusy by rememberSaveable { mutableStateOf(false) }
    var showReviewStep by rememberSaveable(draft.id) { mutableStateOf(draft.userReviewed) }
    val total = draft.total
    val range = draft.totalRange
    val commitError = draft.commitValidationError()
    val targetDate = draft.targetDate ?: state.selectedFoodDate
    val replacedNutrition = draft.replacesMealId?.let { replacedId ->
        state.selectedDateMeals.firstOrNull { it.id == replacedId }?.nutrition
    } ?: Nutrition()
    val previewNutrition = state.selectedDateNutrition - replacedNutrition + total
    val draftLocked = state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal ||
        state.isLoadingFoodDate || state.isSavingAnalysisConfig
    val reviewGuidance = when (draft.analysisMode) {
        AnalysisMode.INTERACTIVE_DEMO -> "演示草稿仅用于体验界面，不能计入账本"
        AnalysisMode.MANUAL -> "按包装标签、食物数据库或实际称重结果核对"
        AnalysisMode.REMOTE_AI -> "勾选前不会计入摄入统计"
        AnalysisMode.ON_DEVICE_AI -> "模型分数不是准确率；模型只判断食物像什么，营养与克重仍需你核对"
    }
    val reviewEnabled = draft.analysisMode != AnalysisMode.INTERACTIVE_DEMO &&
        !(draft.analysisMode == AnalysisMode.ON_DEVICE_AI && draft.items.isEmpty() && draft.hypotheses.isNotEmpty()) &&
        !draftLocked
    val choosingCandidate = draft.analysisMode == AnalysisMode.ON_DEVICE_AI &&
        draft.items.isEmpty() && draft.hypotheses.isNotEmpty()
    val visibleStep = when {
        choosingCandidate -> 1
        showReviewStep -> 3
        else -> 2
    }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    val requestDiscardConfirmation = { showDiscardConfirmation = true }

    BackHandler(onBack = requestDiscardConfirmation)

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            draft.state == com.personal.fitnessledger.data.DraftState.ANALYSIS_FAILED -> "识别失败 · 手工补录"
                            draft.analysisMode == AnalysisMode.INTERACTIVE_DEMO -> "交互演示 · 不可入账"
                            draft.analysisMode == AnalysisMode.ON_DEVICE_AI && draft.items.isEmpty() -> "本机候选 · 请选择食物"
                            draft.analysisMode == AnalysisMode.ON_DEVICE_AI -> "本机估算这顿饭"
                            draft.replacesMealId != null -> "更正已入账餐食"
                            draft.analysisMode == AnalysisMode.MANUAL -> "手工记录这顿饭"
                            else -> "识别这顿饭"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = requestDiscardConfirmation) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回并处理草稿")
                    }
                },
                actions = {
                    TextButton(onClick = requestDiscardConfirmation) { Text("丢弃") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 18.dp)
                .imePadding()
                .testTag("meal-draft-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "meal-draft-progress") {
                MealDraftProgress(currentStep = visibleStep, wholeMeal = draft.analysisMode == AnalysisMode.REMOTE_AI)
            }
            if (!choosingCandidate) item(key = "meal-draft-date") {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    if (draft.replacesMealId != null) {
                        Column(Modifier.padding(16.dp)) {
                            Text("更正日期", fontWeight = FontWeight.SemiBold)
                            Text("$targetDate · 更正不会跨日移动原餐食", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                onClick = { onChangeTargetDate(targetDate.minusDays(1)) },
                                enabled = !draftLocked,
                            ) { Icon(Icons.Default.ChevronLeft, contentDescription = "草稿前一天") }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("计入日期", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(targetDate.toString(), fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { onChangeTargetDate(targetDate.plusDays(1)) },
                                enabled = !draftLocked && targetDate < state.loadedDate,
                            ) { Icon(Icons.Default.ChevronRight, contentDescription = "草稿后一天") }
                        }
                    }
                }
            }
            item(key = "meal-draft-photo") {
                if (draft.photoUri.isNotBlank()) {
                    DraftPhoto(
                        uri = Uri.parse(draft.photoUri),
                        height = if (choosingCandidate) 96.dp else 180.dp,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                draft.state == com.personal.fitnessledger.data.DraftState.ANALYSIS_FAILED -> "识别失败 · 可重试或手工补录"
                                draft.analysisMode == AnalysisMode.INTERACTIVE_DEMO -> "交互演示 · 不可入账"
                                draft.analysisMode == AnalysisMode.MANUAL -> "手工输入 · 待确认"
                                draft.analysisMode == AnalysisMode.ON_DEVICE_AI && draft.items.isEmpty() -> "本机模型候选 · 尚未估算"
                                draft.analysisMode == AnalysisMode.ON_DEVICE_AI -> "本机模型估算 · 待确认"
                                else -> "AI估算 · 待确认"
                            },
                        )
                    },
                    modifier = Modifier.padding(top = if (choosingCandidate) 4.dp else 8.dp),
                )
                if (draft.state == com.personal.fitnessledger.data.DraftState.ANALYSIS_FAILED || draft.analysisMode == AnalysisMode.ON_DEVICE_AI) {
                    if (draft.state == com.personal.fitnessledger.data.DraftState.ANALYSIS_FAILED) {
                        Text(draft.evidenceReason, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("photo-analysis-error"))
                    }
                    OutlinedButton(
                        onClick = { if (draft.items.isEmpty()) onRetryAnalysis() else showReplaceAnalysis = true },
                        enabled = !draftLocked && state.analysisTokenConfigured,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isAnalyzingPhoto) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在重试…", modifier = Modifier.padding(start = 8.dp))
                        } else {
                            Text(if (draft.analysisMode == AnalysisMode.ON_DEVICE_AI) "用外置大模型重新识别整餐" else "重新尝试识别")
                        }
                    }
                    TextButton(onClick = onOpenPhotoSettings, enabled = !draftLocked,
                        modifier = Modifier.fillMaxWidth().testTag("draft-photo-settings")) {
                        Text("连接 / 修改大模型设置（保留照片）")
                    }
                }
            }
            if (!choosingCandidate && draft.items.isNotEmpty()) item(key = "meal-draft-summary") {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本餐预估", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (draft.analysisMode == AnalysisMode.REMOTE_AI) Text(
                            "已识别 ${draft.items.size} 项 · 以下为照片中可见份量，不一定是你吃的量",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(formatWhole(total.kcal), fontSize = 42.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(" kcal", modifier = Modifier.padding(bottom = 7.dp))
                        }
                        Text(
                            "约 ${formatWhole(range.minimum.kcal)}–${formatWhole(range.maximum.kcal)} kcal",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(total.compactLabel(), fontWeight = FontWeight.Medium)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("可靠性：${draft.evidenceTier.label}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                        Text(draft.evidenceReason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(draft.providerLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        if (draft.analysisMode == AnalysisMode.REMOTE_AI) Text(
                            "合餐请逐项改成自己吃的量；估算范围仅反映份量，不涵盖所有配方误差。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (draft.items.isEmpty() && draft.hypotheses.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("选择最像的一项", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "Top ${draft.hypotheses.size} 是同一张照片的可能菜名，分数不是准确率。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            Text(
                                "无法从单张照片可靠确认食物或营养。未收录候选必须由你手工命名；未通过门槛时不会预填营养。",
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            draft.hypotheses.forEach { hypothesis ->
                                OutlinedButton(
                                    onClick = {
                                        // Every model choice must pass through the editable
                                        // nutrition dialog. A mapped database row is only a
                                        // starting value, never an auto-confirmed estimate.
                                        editingHypothesisId = hypothesis.labelId
                                    },
                                    enabled = !draftLocked,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .testTag("meal-candidate-${hypothesis.labelId}"),
                                ) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                        Text(hypothesis.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "模型分数 ${formatModelScore(hypothesis.modelScore)} · " +
                                                if (hypothesis.suggestedItem == null) {
                                                    "未通过营养预填门槛；下一步手工填写"
                                                } else {
                                                    "下一步编辑 USDA 预填值"
                                                },
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(draft.items, key = { it.id }) { item ->
                DraftItemCard(
                    item = item,
                    enabled = !draftLocked,
                    onEdit = { editingItemId = item.id },
                    onRemove = { onRemoveItem(item.id) },
                    onPortion = if (draft.analysisMode == AnalysisMode.REMOTE_AI) {
                        { portionItemId = item.id; portionFraction = 1.0 }
                    } else null,
                )
            }
            item {
                OutlinedButton(
                    onClick = { addingItemId = UUID.randomUUID().toString() },
                    enabled = !draftLocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        if (draft.analysisMode == AnalysisMode.MANUAL) "继续添加食物" else "补充漏识别的食材、油或酱汁",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (!choosingCandidate && !showReviewStep) {
                item(key = "meal-next-review") {
                    Button(
                        onClick = { showReviewStep = true },
                        enabled = draft.items.isNotEmpty() && !draftLocked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("meal-next-review"),
                    ) { Text("下一步：核对并确认", fontWeight = FontWeight.Bold) }
                }
            }
            if (!choosingCandidate && showReviewStep) {
                item(key = "meal-review-confirm") {
                    Column(
                        modifier = Modifier.testTag("meal-review-section"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("最后核对", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    "${targetDate} 将变为 ${previewNutrition.compactLabel()}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (draft.userReviewed) onReviewedChange(false)
                                    showReviewStep = false
                                },
                                enabled = !draftLocked,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("返回编辑") }
                        }
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (draft.userReviewed) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("meal-review-checkbox")
                                    .clearAndSetSemantics {
                                        contentDescription = "我已核对食材、克重、烹调油和酱汁；$reviewGuidance"
                                        role = Role.Checkbox
                                        toggleableState = if (draft.userReviewed) ToggleableState.On else ToggleableState.Off
                                        if (reviewEnabled) {
                                            onClick(label = "切换餐食核对状态") {
                                                onReviewedChange(!draft.userReviewed)
                                                true
                                            }
                                        } else {
                                            disabled()
                                        }
                                    }
                                    .toggleable(
                                        value = draft.userReviewed,
                                        enabled = reviewEnabled,
                                        role = Role.Checkbox,
                                        onValueChange = onReviewedChange,
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = draft.userReviewed,
                                    onCheckedChange = null,
                                    enabled = reviewEnabled,
                                )
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text("我已核对食材、克重、烹调油和酱汁", fontWeight = FontWeight.SemiBold)
                                    Text(reviewGuidance, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (commitError != null) {
                            Text(commitError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = onConfirm,
                            enabled = commitError == null && !draftLocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("meal-confirm-entry"),
                        ) {
                            if (state.isCommittingMeal) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("正在安全入账…", modifier = Modifier.padding(start = 8.dp))
                            } else {
                                Text(
                                    if (draft.replacesMealId != null) "确认并原子替换原餐食" else "确认并计入 ${targetDate}",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showReplaceAnalysis) AlertDialog(
        onDismissRequest = { showReplaceAnalysis = false },
        title = { Text("重新识别整餐？") },
        text = { Text("将替换当前草稿中的食物与修改，照片和计入日期保留；模型调用可能计费。") },
        confirmButton = { TextButton(onClick = { showReplaceAnalysis = false; onRetryAnalysis() }, enabled = !draftLocked) { Text("重新识别") } },
        dismissButton = { TextButton(onClick = { showReplaceAnalysis = false }) { Text("保留草稿") } },
    )

    draft.items.firstOrNull { it.id == portionItemId }?.let { item ->
        val adjusted = runCatching { item.scaledPortion(portionFraction) }.getOrNull()
        AlertDialog(
            onDismissRequest = { if (!draftLocked) portionItemId = null },
            title = { Text("我吃了多少 · ${item.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("以当前 ${formatOne(item.grams)} g 为基准调整。没有吃这道菜可直接删除。")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0.25 to "¼", 0.5 to "½", 0.75 to "¾", 1.0 to "全部").forEach { (fraction, label) ->
                            FilterChip(selected = portionFraction == fraction,
                                onClick = { portionFraction = fraction }, label = { Text(label) },
                                enabled = !draftLocked, modifier = Modifier.testTag("portion-$fraction"))
                        }
                    }
                    Text(adjusted?.let { "本次记 ${formatOne(it.grams)} g · ${formatWhole(it.nutrition.kcal)} kcal" }
                        ?: "份量过小，请用编辑按钮手动调整克重")
                    Text("这里只改变个人份量，不会再次调用模型。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { adjusted?.let { onUpdateItem(it) { saved -> if (saved) portionItemId = null } } },
                    enabled = adjusted != null && !draftLocked, modifier = Modifier.testTag("save-photo-portion")) { Text("应用份量") }
            },
            dismissButton = { TextButton(onClick = { portionItemId = null }, enabled = !draftLocked) { Text("取消") } },
        )
    }

    draft.items.firstOrNull { it.id == editingItemId }?.let { item ->
        FoodEditorDialog(
            title = "修改识别结果",
            initial = item,
            deriveKcalFromMacros = draft.analysisMode == AnalysisMode.MANUAL || item.calorieSource == CalorieSource.DERIVED_FROM_MACROS,
            isSaving = state.isSavingMealDraft,
            saveError = state.message?.takeIf { it.startsWith("无法保存草稿修改") },
            onDismiss = {
                if (!state.isSavingMealDraft) {
                    editingItemId = null
                    pendingEditSignature = null
                    editSaveObservedBusy = false
                }
            },
            onSave = {
                pendingEditSignature = it.persistedFormSignature()
                editSaveObservedBusy = false
                onUpdateItem(it) { saved ->
                    if (saved) {
                        editingItemId = null
                        pendingEditSignature = null
                        editSaveObservedBusy = false
                    }
                }
            },
        )
    }
    draft.hypotheses.firstOrNull { it.labelId == editingHypothesisId }?.let { hypothesis ->
        val initial = hypothesis.suggestedItem ?: FoodDraftItem(
            name = hypothesis.displayName.takeUnless { it.startsWith("未收录候选 #") }.orEmpty(),
            grams = 100.0,
            gramsMin = 80.0,
            gramsMax = 120.0,
            per100g = Nutrition(),
            sourceName = "用户根据本机候选补录",
            portionBasis = PortionBasis.USER_ESTIMATE,
            evidenceTier = EvidenceTier.D,
            alternatives = draft.hypotheses
                .filterNot { it.labelId == hypothesis.labelId }
                .map(FoodHypothesis::displayName),
            calorieSource = CalorieSource.DERIVED_FROM_MACROS,
        )
        FoodEditorDialog(
            title = if (initial.name.isBlank()) "未收录：请手工命名" else "按候选补全食物",
            initial = initial,
            deriveKcalFromMacros = true,
            requireNonZeroNutrition = true,
            isSaving = state.isSavingMealDraft,
            saveError = state.message?.takeIf { it.startsWith("无法") || it.startsWith("请为") },
            onDismiss = { if (!state.isSavingMealDraft) editingHypothesisId = null },
            onSave = { resolved ->
                onResolveHypothesis(hypothesis, resolved) { saved ->
                    if (saved) editingHypothesisId = null
                }
            },
        )
    }
    addingItemId?.let { pendingItemId ->
        FoodEditorDialog(
            title = "补充食材",
            initial = FoodDraftItem(
                id = pendingItemId,
                name = "",
                grams = 100.0,
                gramsMin = 80.0,
                gramsMax = 120.0,
                per100g = Nutrition(),
                sourceName = "用户输入",
                portionBasis = PortionBasis.USER_ESTIMATE,
                evidenceTier = EvidenceTier.C,
                userModified = true,
                calorieSource = if (draft.analysisMode == AnalysisMode.MANUAL) {
                    CalorieSource.DERIVED_FROM_MACROS
                } else {
                    CalorieSource.LABEL_OR_DATABASE
                },
            ),
            deriveKcalFromMacros = draft.analysisMode == AnalysisMode.MANUAL,
            isSaving = state.isSavingMealDraft,
            saveError = state.message?.takeIf { it.startsWith("无法保存草稿修改") },
            onDismiss = { if (!state.isSavingMealDraft) addingItemId = null },
            onSave = {
                onAddItem(it) { saved ->
                    if (saved) addingItemId = null
                }
            },
        )
    }

    LaunchedEffect(state.isSavingMealDraft, pendingEditSignature, editingItemId, draft.items) {
        if (pendingEditSignature != null && state.isSavingMealDraft) {
            editSaveObservedBusy = true
        } else if (pendingEditSignature != null && editSaveObservedBusy && !state.isSavingMealDraft) {
            val persisted = draft.items.firstOrNull { it.id == editingItemId }
            if (persisted?.persistedFormSignature() == pendingEditSignature) {
                editingItemId = null
                pendingEditSignature = null
                editSaveObservedBusy = false
            }
        }
    }
    LaunchedEffect(addingItemId, draft.items) {
        if (addingItemId != null && draft.items.any { it.id == addingItemId }) {
            addingItemId = null
        }
    }
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("要丢弃这份草稿吗？") },
            text = { Text("草稿尚未计入账本。继续编辑会完整保留当前内容；丢弃后将删除这份草稿，且无法撤销。") },
            confirmButton = {
                TextButton(
                    enabled = !draftLocked,
                    onClick = {
                        showDiscardConfirmation = false
                        onDiscard()
                    },
                ) { Text("丢弃草稿", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun DraftItemCard(item: FoodDraftItem, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit, onPortion: (() -> Unit)? = null) {
    val nutrition = item.nutrition
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "${formatOne(item.grams)} g（估计 ${formatOne(item.gramsMin)}–${formatOne(item.gramsMax)} g）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Default.Edit, contentDescription = "修改") }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Default.Delete, contentDescription = "删除") }
            }
            Text(nutrition.compactLabel(), fontSize = 13.sp)
            Text("${formatWhole(nutrition.kcal)} kcal · 来源：${item.sourceName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onPortion != null) TextButton(onClick = onPortion, enabled = enabled,
                modifier = Modifier.testTag("photo-portion-${item.id}")) { Text("我吃了多少？") }
            if (item.alternatives.isNotEmpty()) {
                Text("候选：${item.alternatives.joinToString("、")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.riskFlags.forEach { flag ->
                Text("需确认：${flag.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
internal fun MealDraftProgress(currentStep: Int, wholeMeal: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("meal-draft-progress"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (if (wholeMeal) listOf("拍整餐", "看估算", "确认入账") else listOf("选候选", "改营养", "核对入账")).forEachIndexed { index, label ->
            val step = index + 1
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (step == currentStep) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).testTag("meal-draft-step-$step"),
            ) {
                Text(
                    "$step  $label",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                        .testTag("meal-draft-step-label-$step"),
                    color = if (step == currentStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (step == currentStep) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun DraftPhoto(uri: Uri, height: androidx.compose.ui.unit.Dp = 230.dp) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(52.dp))
        } else {
            Image(
                bitmap = requireNotNull(bitmap),
                contentDescription = "餐食照片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun FoodEditorDialog(
    title: String,
    initial: FoodDraftItem,
    deriveKcalFromMacros: Boolean = false,
    requireNonZeroNutrition: Boolean = false,
    isSaving: Boolean = false,
    saveError: String? = null,
    saveStatus: String? = null,
    manualForm: ManualFoodFormDraft? = null,
    onManualFormChange: ((ManualFoodFormTransform) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (FoodDraftItem) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var grams by rememberSaveable { mutableStateOf(formatOne(initial.grams)) }
    var kcal by rememberSaveable { mutableStateOf(formatOne(initial.per100g.kcal)) }
    var carbs by rememberSaveable { mutableStateOf(formatOne(initial.per100g.carbsG)) }
    var protein by rememberSaveable { mutableStateOf(formatOne(initial.per100g.proteinG)) }
    var fat by rememberSaveable { mutableStateOf(formatOne(initial.per100g.fatG)) }
    var sourceName by rememberSaveable { mutableStateOf(initial.sourceName) }
    var weighed by rememberSaveable { mutableStateOf(initial.portionBasis == PortionBasis.USER_WEIGHT) }
    var useLabelKcal by rememberSaveable {
        mutableStateOf(initial.calorieSource == CalorieSource.LABEL_OR_DATABASE)
    }
    val shownName = manualForm?.name ?: name
    val shownGrams = manualForm?.gramsText ?: grams
    val shownKcal = manualForm?.kcalText ?: kcal
    val shownCarbs = manualForm?.carbsText ?: carbs
    val shownProtein = manualForm?.proteinText ?: protein
    val shownFat = manualForm?.fatText ?: fat
    val shownSourceName = manualForm?.sourceName ?: sourceName
    val shownWeighed = manualForm?.weighed ?: weighed
    val shownUseLabelKcal = manualForm?.useLabelKcal ?: useLabelKcal
    fun updateManual(transform: (ManualFoodFormDraft) -> ManualFoodFormDraft, localUpdate: () -> Unit) {
        if (manualForm != null) {
            requireNotNull(onManualFormChange)(transform)
        } else {
            localUpdate()
        }
    }

    fun number(value: String) = value.replace(',', '.').toDoubleOrNull()
    val parsedGrams = number(shownGrams)
    val parsedCarbs = number(shownCarbs)
    val parsedProtein = number(shownProtein)
    val parsedFat = number(shownFat)
    val macroKcal = (parsedCarbs ?: 0.0) * 4.0 + (parsedProtein ?: 0.0) * 4.0 + (parsedFat ?: 0.0) * 9.0
    val derivingKcal = deriveKcalFromMacros && !shownUseLabelKcal
    val parsedKcal = if (derivingKcal) macroKcal else number(shownKcal)
    fun numericError(value: String, label: String, minimum: Double, maximum: Double, range: String): String? {
        val parsed = number(value)
        return when {
            value.isBlank() -> "$label 不能为空；合法范围：$range"
            parsed == null || !parsed.isFinite() -> "$label 必须是有效数字；合法范围：$range"
            parsed !in minimum..maximum -> "$label 必须在 $range"
            else -> null
        }
    }
    val gramsError = numericError(shownGrams, "可食用克重", 1.0, 5_000.0, "1–5000 g")
    val kcalError = if (derivingKcal) null else numericError(shownKcal, "每 100 g 热量", 0.0, 1_000.0, "0–1000 kcal")
    val carbsError = numericError(shownCarbs, "每 100 g 碳水", 0.0, 100.0, "0–100 g")
    val proteinError = numericError(shownProtein, "每 100 g 蛋白质", 0.0, 100.0, "0–100 g")
    val fatError = numericError(shownFat, "每 100 g 脂肪", 0.0, 100.0, "0–100 g")
    val labelEnergyIsUsable = derivingKcal || macroKcal <= 0.5 || (parsedKcal ?: 0.0) > 0.0
    val valid = shownName.isNotBlank() && shownName.length <= 80 &&
        shownSourceName.isNotBlank() && shownSourceName.length <= 120 &&
        parsedGrams != null && parsedGrams.isFinite() && parsedGrams in 1.0..5_000.0 &&
        listOf(parsedCarbs, parsedProtein, parsedFat).all { parsed ->
            parsed != null && parsed.isFinite() && parsed in 0.0..100.0
        } && (parsedCarbs ?: 101.0) + (parsedProtein ?: 101.0) + (parsedFat ?: 101.0) <= 100.5 &&
        parsedKcal != null && parsedKcal.isFinite() && parsedKcal in 0.0..1_000.0 && labelEnergyIsUsable &&
        (!requireNonZeroNutrition || parsedKcal > 0.0 || macroKcal > 0.0) &&
        listOf(shownCarbs to 100.0, shownProtein to 100.0, shownFat to 100.0).all { (text, upper) ->
            val parsed = number(text)
            parsed != null && parsed.isFinite() && parsed in 0.0..upper
        }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        shownName,
                        { value ->
                            if (value.length <= 512) updateManual({ it.copy(name = value) }, { name = value })
                        },
                        label = { Text("食物名称") },
                        singleLine = true,
                        enabled = !isSaving,
                    )
                }
                item {
                    NumericField(
                        shownGrams,
                        { value -> if (value.length <= 64) updateManual({ it.copy(gramsText = value) }, { grams = value }) },
                        "可食用克重",
                        errorMessage = gramsError,
                        rangeHint = "合法范围：1–5000 g",
                        enabled = !isSaving,
                    )
                }
                item { Text("每 100 g 营养", fontWeight = FontWeight.SemiBold) }
                if (deriveKcalFromMacros) {
                    item {
                        LabeledToggleRow(
                            label = "使用包装或数据库热量",
                            supportingText = "关闭时按 4C + 4P + 9F 自动折算",
                            checked = shownUseLabelKcal,
                            onCheckedChange = { enabled ->
                                updateManual(
                                    { latest ->
                                        val nextSourceName = when {
                                            enabled && latest.sourceName == "用户手工输入" -> "包装标签（用户输入）"
                                            !enabled && latest.sourceName == "包装标签（用户输入）" -> "用户手工输入"
                                            else -> latest.sourceName
                                        }
                                        latest.copy(useLabelKcal = enabled, sourceName = nextSourceName)
                                    },
                                    {
                                        val nextSourceName = when {
                                            enabled && sourceName == "用户手工输入" -> "包装标签（用户输入）"
                                            !enabled && sourceName == "包装标签（用户输入）" -> "用户手工输入"
                                            else -> sourceName
                                        }
                                        useLabelKcal = enabled
                                        sourceName = nextSourceName
                                    },
                                )
                            },
                            enabled = !isSaving,
                            visual = ToggleVisual.SWITCH,
                            testTag = "manual-food-use-label-kcal",
                        )
                    }
                }
                if (derivingKcal) {
                    item {
                        Text(
                            "热量由三大营养素自动折算：${formatWhole(macroKcal)} kcal/100 g",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        NumericField(
                            shownKcal,
                            { value -> if (value.length <= 64) updateManual({ it.copy(kcalText = value) }, { kcal = value }) },
                            "热量 kcal",
                            errorMessage = kcalError,
                            rangeHint = "合法范围：0–1000 kcal/100 g",
                            enabled = !isSaving,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = shownSourceName,
                        onValueChange = { value ->
                            if (value.length <= 512) updateManual({ it.copy(sourceName = value) }, { sourceName = value })
                        },
                        label = { Text(if (shownUseLabelKcal) "营养来源（品牌包装或数据库）" else "营养来源") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumericField(
                            shownCarbs,
                            { value -> if (value.length <= 64) updateManual({ it.copy(carbsText = value) }, { carbs = value }) },
                            "碳水 g",
                            Modifier.fillMaxWidth(),
                            carbsError,
                            "0–100 g/100 g",
                            enabled = !isSaving,
                        )
                        NumericField(
                            shownProtein,
                            { value -> if (value.length <= 64) updateManual({ it.copy(proteinText = value) }, { protein = value }) },
                            "蛋白质 g",
                            Modifier.fillMaxWidth(),
                            proteinError,
                            "0–100 g/100 g",
                            enabled = !isSaving,
                        )
                    }
                }
                item {
                    NumericField(
                        shownFat,
                        { value -> if (value.length <= 64) updateManual({ it.copy(fatText = value) }, { fat = value }) },
                        "脂肪 g",
                        errorMessage = fatError,
                        rangeHint = "合法范围：0–100 g/100 g",
                        enabled = !isSaving,
                    )
                }
                item {
                    val shownKcal = parsedKcal ?: 0.0
                    val mismatch = abs(shownKcal - macroKcal)
                    Text(
                        if (derivingKcal) {
                            "碳水、蛋白质和脂肪合计不能超过 100.5 g/100 g"
                        } else {
                            "宏量折算约 ${formatWhole(macroKcal)} kcal" +
                                if (mismatch > maxOf(50.0, shownKcal * 0.25)) "，与填写热量差异较大，请核对" else ""
                        },
                        fontSize = 12.sp,
                        color = if (!derivingKcal && mismatch > maxOf(50.0, shownKcal * 0.25)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (requireNonZeroNutrition && (parsedKcal ?: 0.0) <= 0.0 && macroKcal <= 0.0) {
                    item {
                        Text(
                            "请至少填写一项大于 0 的营养值；本机候选不能以全零结果保存。",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
                item {
                    LabeledToggleRow(
                        label = "这是实际称重",
                        supportingText = "称重后克重区间收窄",
                        checked = shownWeighed,
                        onCheckedChange = { value -> updateManual({ it.copy(weighed = value) }, { weighed = value }) },
                        enabled = !isSaving,
                        visual = ToggleVisual.SWITCH,
                        testTag = "manual-food-weighed",
                    )
                }
                if (saveError != null) {
                    item {
                        Text(
                            saveError,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (saveStatus != null) {
                    item {
                        Text(
                            saveStatus,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !isSaving,
                onClick = {
                    val exactGrams = requireNotNull(parsedGrams)
                    val moved = initial.withGrams(exactGrams)
                    onSave(
                        moved.copy(
                            name = shownName.trim(),
                            grams = exactGrams,
                            gramsMin = when {
                                shownWeighed -> exactGrams
                                initial.portionBasis == PortionBasis.USER_WEIGHT -> maxOf(1.0, exactGrams * 0.8)
                                else -> moved.gramsMin
                            },
                            gramsMax = when {
                                shownWeighed -> exactGrams
                                initial.portionBasis == PortionBasis.USER_WEIGHT -> exactGrams * 1.2
                                else -> moved.gramsMax
                            },
                            per100g = Nutrition(
                                kcal = requireNotNull(parsedKcal),
                                carbsG = requireNotNull(number(shownCarbs)),
                                proteinG = requireNotNull(number(shownProtein)),
                                fatG = requireNotNull(number(shownFat)),
                            ),
                            sourceName = shownSourceName.trim(),
                            portionBasis = if (shownWeighed) PortionBasis.USER_WEIGHT else when (initial.portionBasis) {
                                PortionBasis.USER_WEIGHT -> PortionBasis.USER_ESTIMATE
                                else -> initial.portionBasis
                            },
                            // 称重只提高份量可靠性，不能把食物身份或每 100 g 营养值升级为高证据等级。
                            evidenceTier = EvidenceTier.C,
                            userModified = true,
                            calorieSource = if (derivingKcal) CalorieSource.DERIVED_FROM_MACROS else CalorieSource.LABEL_OR_DATABASE,
                        ),
                    )
                },
            ) { Text(if (isSaving) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

@Composable
private fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorMessage: String? = null,
    rangeHint: String? = null,
    enabled: Boolean = true,
) {
    val fieldModifier = if (errorMessage == null) modifier else modifier.semantics { error(errorMessage) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = fieldModifier,
        isError = errorMessage != null,
        enabled = enabled,
        supportingText = if (errorMessage != null || rangeHint != null) {
            { Text(requireNotNull(errorMessage ?: rangeHint)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun newManualFoodDraftItem() = FoodDraftItem(
    name = "",
    grams = 100.0,
    gramsMin = 80.0,
    gramsMax = 120.0,
    per100g = Nutrition(),
    sourceName = "用户手工输入",
    portionBasis = PortionBasis.USER_ESTIMATE,
    evidenceTier = EvidenceTier.C,
    userModified = true,
    calorieSource = CalorieSource.DERIVED_FROM_MACROS,
)

private fun formatModelScore(score: Double): String = "${formatWhole(score.coerceIn(0.0, 1.0) * 100.0)}%"

private fun FoodDraftItem.persistedFormSignature(): String = "$id:${hashCode()}"

private fun SavedFood.toDraftItem() = FoodDraftItem(
    name = name,
    grams = defaultGrams,
    gramsMin = (defaultGrams * 0.8).coerceAtLeast(1.0),
    gramsMax = defaultGrams * 1.2,
    per100g = per100g,
    sourceName = sourceName,
    portionBasis = PortionBasis.USER_ESTIMATE,
    evidenceTier = EvidenceTier.C,
    userModified = true,
    calorieSource = calorieSource,
)

private fun ManualFoodFormDraft.toInitialFoodDraftItem() = FoodDraftItem(
    id = itemId,
    name = name,
    grams = initialGrams,
    gramsMin = initialGramsMin,
    gramsMax = initialGramsMax,
    per100g = Nutrition(),
    sourceName = sourceName,
    portionBasis = initialPortionBasis,
    evidenceTier = EvidenceTier.C,
    userModified = true,
    calorieSource = if (useLabelKcal) CalorieSource.LABEL_OR_DATABASE else CalorieSource.DERIVED_FROM_MACROS,
)

private const val MILLIS_PER_DAY = 86_400_000L
