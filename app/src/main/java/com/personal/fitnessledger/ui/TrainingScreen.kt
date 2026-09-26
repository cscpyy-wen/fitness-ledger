package com.personal.fitnessledger.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.PersonalRecord
import com.personal.fitnessledger.data.PrSummary
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.WorkoutSetInput
import com.personal.fitnessledger.data.validationError
import com.personal.fitnessledger.data.WorkoutHistoryDetail
import com.personal.fitnessledger.data.WorkoutHistorySet
import com.personal.fitnessledger.data.WorkoutPlanItem
import com.personal.fitnessledger.data.WorkoutTemplate
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutStatus
import com.personal.fitnessledger.data.compactPlannerNumber
import com.personal.fitnessledger.data.REST_TIMER_WALL_CLOCK_TOLERANCE_MILLIS
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

private data class PendingFiveByFiveConfirmation(
    val exercise: Exercise,
    val loadKg: Double,
    /** Created when this one dialog opens; every queued callback reuses it. */
    val batchId: String,
)

@Composable
fun TrainingScreen(
    state: AppUiState,
    onStartWorkout: (String) -> Unit,
    onAddSet: (Exercise, WorkoutSetInput) -> Unit,
    onAcknowledgeSingleSet: (String) -> Unit,
    onAcknowledgeFiveByFive: (String) -> Unit,
    onStartRestTimer: (Int) -> Unit,
    onClearRestTimer: () -> Unit,
    onAddFiveByFive: (Exercise, Double, String) -> Unit,
    onUpdateSet: (WorkoutSet) -> Unit,
    onDeleteSet: (Long) -> Unit,
    onDeleteSetBatch: (String) -> Unit,
    onCancelWorkout: () -> Unit,
    onCompleteWorkout: () -> Unit,
    onAddCustomExercise: (String, String, TrackingType, Boolean, (Exercise?) -> Unit) -> Unit,
    onTogglePrimary: (Exercise) -> Unit,
    onUpdateCustomExercise: (Exercise, String, String, Boolean, (Boolean) -> Unit) -> Unit = { _, _, _, _, result -> result(false) },
    onSetCustomExerciseArchived: (Exercise, Boolean, (Boolean) -> Unit) -> Unit = { _, _, result -> result(false) },
    onOpenWorkoutHistory: (Long) -> Unit,
    onLoadMoreWorkoutHistory: () -> Unit,
    onCloseWorkoutHistory: () -> Unit,
    onStartWorkoutCorrection: (Long) -> Unit = {},
    onDeleteWorkoutHistory: (Long) -> Unit = {},
    onCopyPreviousWorkout: () -> Unit = {},
    onStartFromTemplate: (String) -> Unit = {},
    onSaveHistoryAsTemplate: (Long, String) -> Unit = { _, _ -> },
    onSaveActiveAsTemplate: (String) -> Unit = {},
    onUpdateTemplate: (WorkoutTemplate) -> Unit = {},
    onDeleteTemplate: (String) -> Unit = {},
    onUpdateWorkoutEditor: (Long, WorkoutEditorTransform) -> Unit = { _, _ -> },
    onLoadPlanItem: (String) -> Unit = {},
    onUpdateActivePlanItem: (WorkoutPlanItem) -> Unit = {},
    onRemoveActivePlanItem: (String) -> Unit = {},
    onUpdateBarbellBarWeight: (Double) -> Unit = {},
) {
    state.selectedWorkoutHistory?.let { detail ->
        WorkoutHistoryDetailScreen(
            detail = detail,
            onBack = onCloseWorkoutHistory,
            onSaveAsTemplate = onSaveHistoryAsTemplate,
            onStartCorrection = onStartWorkoutCorrection,
            onDeleteHistory = onDeleteWorkoutHistory,
            hasActiveWorkout = state.activeWorkout != null,
            isSaving = state.isSavingWorkout,
        )
        return
    }
    if (state.activeWorkout == null) {
        TrainingHome(
            state = state,
            onStartWorkout = onStartWorkout,
            onAddCustomExercise = onAddCustomExercise,
            onTogglePrimary = onTogglePrimary,
            onUpdateCustomExercise = onUpdateCustomExercise,
            onSetCustomExerciseArchived = onSetCustomExerciseArchived,
            onOpenWorkoutHistory = onOpenWorkoutHistory,
            onLoadMoreWorkoutHistory = onLoadMoreWorkoutHistory,
            onCopyPreviousWorkout = onCopyPreviousWorkout,
            onStartFromTemplate = onStartFromTemplate,
            onUpdateTemplate = onUpdateTemplate,
            onDeleteTemplate = onDeleteTemplate,
        )
    } else {
        val activeSessionId = requireNotNull(state.activeWorkout).id
        key(activeSessionId) {
            ActiveWorkoutScreen(
                state = state,
                onAddSet = onAddSet,
                onAcknowledgeSingleSet = onAcknowledgeSingleSet,
                onAcknowledgeFiveByFive = onAcknowledgeFiveByFive,
                onStartRestTimer = onStartRestTimer,
                onClearRestTimer = onClearRestTimer,
                onAddFiveByFive = onAddFiveByFive,
                onUpdateSet = onUpdateSet,
                onDeleteSet = onDeleteSet,
                onDeleteSetBatch = onDeleteSetBatch,
                onCancelWorkout = onCancelWorkout,
                onCompleteWorkout = onCompleteWorkout,
                onAddCustomExercise = onAddCustomExercise,
                onSaveActiveAsTemplate = onSaveActiveAsTemplate,
                onUpdateWorkoutEditor = onUpdateWorkoutEditor,
                onLoadPlanItem = onLoadPlanItem,
                onUpdateActivePlanItem = onUpdateActivePlanItem,
                onRemoveActivePlanItem = onRemoveActivePlanItem,
                onUpdateBarbellBarWeight = onUpdateBarbellBarWeight,
            )
        }
    }
}

@Composable
private fun TrainingHome(
    state: AppUiState,
    onStartWorkout: (String) -> Unit,
    onAddCustomExercise: (String, String, TrackingType, Boolean, (Exercise?) -> Unit) -> Unit,
    onTogglePrimary: (Exercise) -> Unit,
    onUpdateCustomExercise: (Exercise, String, String, Boolean, (Boolean) -> Unit) -> Unit,
    onSetCustomExerciseArchived: (Exercise, Boolean, (Boolean) -> Unit) -> Unit,
    onOpenWorkoutHistory: (Long) -> Unit,
    onLoadMoreWorkoutHistory: () -> Unit,
    onCopyPreviousWorkout: () -> Unit,
    onStartFromTemplate: (String) -> Unit,
    onUpdateTemplate: (WorkoutTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCustomName by rememberSaveable { mutableStateOf<String?>(null) }
    var customSaveError by rememberSaveable { mutableStateOf<String?>(null) }
    var showCustomManager by rememberSaveable { mutableStateOf(false) }
    var editingCustomExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var archivingCustomExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    val exerciseCatalog = state.exerciseCatalog.ifEmpty { state.exercises }
    var editingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val primaryExercises = primaryExercisesForDisplay(state.exercises)
    val filtered = state.exercises.filter { exerciseMatches(it, search) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("力量训练", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("常见动作快速选择，也可以建立自己的动作和 PR", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(
                onClick = { onStartWorkout("力量训练") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.FitnessCenter, contentDescription = null)
                Text("开始训练", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onCopyPreviousWorkout,
                enabled = !state.isSavingWorkout && state.workoutHistory.any { it.session.status == WorkoutStatus.COMPLETED },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("复制上一场为新计划", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Text("训练模板", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("模板和复制只预填计划；逐组确认前不会进入历史或 PR。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        if (state.workoutTemplates.isEmpty()) {
            item { Text("可在训练历史详情或进行中的训练里命名保存模板。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.workoutTemplates, key = { "template-${it.id}" }) { template ->
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(template.name, fontWeight = FontWeight.Bold)
                        Text(
                            templateSummaryLabel(template, exerciseCatalog),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = { onStartFromTemplate(template.id) },
                            enabled = !state.isSavingWorkout,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("从此模板开始") }
                        TemplateActionButtons(
                            templateId = template.id,
                            enabled = !state.isSavingWorkout,
                            onEdit = { editingTemplateId = template.id },
                            onDelete = { deletingTemplateId = template.id },
                        )
                    }
                }
            }
        }
        if (state.lastPrEvents.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("上一场训练纪录", fontWeight = FontWeight.Bold)
                        state.lastPrEvents.forEach { event ->
                            Text(
                                prEventDisplayLabel(event, exerciseCatalog),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item { Text("训练历史", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.isLoadingWorkoutHistory) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在读取训练详情…", modifier = Modifier.padding(start = 10.dp))
                }
            }
        } else if (state.workoutHistory.isEmpty()) {
            item { Text("完成第一场训练后可在这里回看每一组。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.workoutHistory, key = { "history-${it.session.id}" }) { history ->
                Card(
                    onClick = { onOpenWorkoutHistory(history.session.id) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(workoutDateLabel(history.recordedLocalDate), fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    history.session.status == WorkoutStatus.CANCELLED -> "已取消"
                                    history.session.correctionRevision > 0 -> "已更正 ${history.session.correctionRevision} 次"
                                    else -> "已完成"
                                },
                                color = if (history.session.status == WorkoutStatus.COMPLETED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            history.exerciseNames.joinToString("、").ifBlank { "没有动作记录" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Text(
                            "${history.completedSetCount} 个正式完成组" +
                                (if (history.failedSetCount > 0) " · ${history.failedSetCount} 个失败组" else "") +
                                " · 负重容量 ${formatWhole(history.totalVolumeKg)} kg · ${workoutDurationLabel(history.session)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.hasMoreWorkoutHistory) {
                item(key = "load-more-workout-history") {
                    OutlinedButton(
                        onClick = onLoadMoreWorkoutHistory,
                        enabled = !state.isLoadingMoreWorkoutHistory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoadingMoreWorkoutHistory) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在加载…", modifier = Modifier.padding(start = 8.dp))
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
        item { Text("主要动作 PR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (primaryExercises.isEmpty()) {
            item { Text("点击动作右侧星标，把任意内置或自定义动作设为主要动作。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(primaryExercises, key = { "pr-${it.id}" }) { exercise ->
                PrimaryPrCard(exercise, state.prSummaries[exercise.id])
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("动作库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCustomManager = true }, enabled = !state.isSavingWorkout) {
                    Text("管理")
                }
                TextButton(onClick = { showCustomDialog = true }, enabled = !state.isSavingWorkout) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("新建")
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().testTag("workout-exercise-search"),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("搜索名称、拼音或英文，如 bench") },
                singleLine = true,
            )
        }
        items(filtered, key = { it.id }) { exercise ->
            ExerciseLibraryRow(exercise = exercise, onTogglePrimary = { onTogglePrimary(exercise) })
        }
        item { Spacer(Modifier.height(18.dp)) }
    }

    LaunchedEffect(state.exercises, pendingCustomName) {
        val pendingName = pendingCustomName
        if (pendingName != null && state.exercises.any { normalizeExerciseName(it.name) == normalizeExerciseName(pendingName) }) {
            showCustomDialog = false
            pendingCustomName = null
            customSaveError = null
        }
    }
    if (showCustomDialog) {
        CustomExerciseDialog(
            existingExerciseNames = exerciseCatalog.map { it.name },
            isSaving = state.isSavingWorkout,
            saveError = customSaveError ?: state.message?.takeIf { it.startsWith("无法创建动作") },
            onDismiss = { if (!state.isSavingWorkout) showCustomDialog = false },
            onSave = { name, category, type, primary ->
                pendingCustomName = name
                customSaveError = null
                onAddCustomExercise(name, category, type, primary) { created ->
                    if (created != null) {
                        showCustomDialog = false
                        pendingCustomName = null
                    } else {
                        customSaveError = "未能创建动作；输入已保留，请检查后重试"
                    }
                }
            },
        )
    }
    if (showCustomManager) {
        CustomExerciseManagerDialog(
            exercises = exerciseCatalog.filter { it.isCustom },
            isSaving = state.isSavingWorkout,
            onEdit = {
                showCustomManager = false
                editingCustomExerciseId = it.id
            },
            onArchive = {
                showCustomManager = false
                archivingCustomExerciseId = it.id
            },
            onRestore = { exercise ->
                onSetCustomExerciseArchived(exercise, false) { success ->
                    if (!success) customSaveError = "未能恢复动作，请稍后重试"
                }
            },
            onDismiss = { showCustomManager = false },
        )
    }
    exerciseCatalog.firstOrNull { it.id == editingCustomExerciseId && it.isCustom }?.let { exercise ->
        EditCustomExerciseDialog(
            exercise = exercise,
            existingExerciseNames = exerciseCatalog.filterNot { it.id == exercise.id }.map { it.name },
            isSaving = state.isSavingWorkout,
            saveError = state.message?.takeIf { it.startsWith("无法更新自定义动作") },
            onDismiss = { if (!state.isSavingWorkout) editingCustomExerciseId = null },
            onSave = { name, category, primary ->
                onUpdateCustomExercise(exercise, name, category, primary) { success ->
                    if (success) {
                        editingCustomExerciseId = null
                        showCustomManager = true
                    }
                }
            },
        )
    }
    exerciseCatalog.firstOrNull { it.id == archivingCustomExerciseId && it.isCustom }?.let { exercise ->
        AlertDialog(
            onDismissRequest = { if (!state.isSavingWorkout) archivingCustomExerciseId = null },
            title = { Text("归档“${exercise.name}”？") },
            text = {
                Text("归档后不再出现在新训练选择器中；历史、PR 和模板里的原动作 ID 都会保留。恢复后模板可继续使用，不会物理删除任何训练数据。")
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingWorkout,
                    onClick = {
                        onSetCustomExerciseArchived(exercise, true) { success ->
                            if (success) {
                                archivingCustomExerciseId = null
                                showCustomManager = true
                            }
                        }
                    },
                ) { Text("确认归档") }
            },
            dismissButton = {
                TextButton(onClick = { archivingCustomExerciseId = null }, enabled = !state.isSavingWorkout) { Text("保留启用") }
            },
        )
    }
    state.workoutTemplates.firstOrNull { it.id == editingTemplateId }?.let { template ->
        WorkoutTemplateEditorDialog(
            template = template,
            exercises = state.exercises,
            exerciseCatalog = exerciseCatalog,
            barWeightKg = state.barbellBarWeightKg,
            isSaving = state.isSavingWorkout,
            onDismiss = { editingTemplateId = null },
            onSave = {
                onUpdateTemplate(it)
                editingTemplateId = null
            },
        )
    }
    state.workoutTemplates.firstOrNull { it.id == deletingTemplateId }?.let { template ->
        AlertDialog(
            onDismissRequest = { deletingTemplateId = null },
            title = { Text("删除模板“${template.name}”？") },
            text = { Text("只删除模板，不会删除任何训练历史或 PR。") },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingWorkout,
                    onClick = {
                        onDeleteTemplate(template.id)
                        deletingTemplateId = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTemplateId = null }) { Text("保留") } },
        )
    }
}

@Composable
private fun WorkoutHistoryDetailScreen(
    detail: WorkoutHistoryDetail,
    onBack: () -> Unit,
    onSaveAsTemplate: (Long, String) -> Unit,
    onStartCorrection: (Long) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    hasActiveWorkout: Boolean,
    isSaving: Boolean,
) {
    BackHandler(onBack = onBack)
    var showSaveTemplate by rememberSaveable(detail.summary.session.id) { mutableStateOf(false) }
    var showCorrectionConfirmation by rememberSaveable(detail.summary.session.id) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(detail.summary.session.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().testTag("workout-history-detail-screen")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("workout-history-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回训练历史")
            }
            Text(
                "训练详情",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).testTag("workout-history-title"),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp).testTag("workout-history-detail-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    workoutDateLabel(detail.summary.recordedLocalDate),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (detail.summary.session.status == WorkoutStatus.COMPLETED) "已完成训练" else "已取消训练 · 不参与 PR",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${detail.summary.completedSetCount} 个正式完成组" +
                                (if (detail.summary.failedSetCount > 0) " · ${detail.summary.failedSetCount} 个失败组" else "") +
                                " · 负重容量 ${formatWhole(detail.summary.totalVolumeKg)} kg",
                        )
                        Text(
                            "用时 ${workoutDurationLabel(detail.summary.session)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detail.summary.session.correctionRevision > 0) {
                            Text(
                                "已更正 ${detail.summary.session.correctionRevision} 次 · 最近更正 " +
                                    workoutCorrectionTimeLabel(detail.summary.session.correctedAtMillis),
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (detail.summary.session.status == WorkoutStatus.COMPLETED && detail.sets.any { it.set.completed }) {
                    OutlinedButton(
                        onClick = { showSaveTemplate = true },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text("命名保存为训练模板", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (detail.summary.session.status == WorkoutStatus.COMPLETED) {
                    OutlinedButton(
                        onClick = { showCorrectionConfirmation = true },
                        enabled = !isSaving && !hasActiveWorkout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("workout-history-correct")
                            .semantics { contentDescription = "更正本场训练" },
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Text("更正本场", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (hasActiveWorkout) {
                        Text(
                            "请先完成或取消进行中的训练，再更正历史。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = !isSaving && !hasActiveWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workout-history-delete")
                        .semantics { contentDescription = "删除本场训练历史" },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("删除本场", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            detail.sets.groupBy { it.exercise.id }.forEach { (_, records) ->
                val exercise = records.first().exercise
                item(key = "history-heading-${exercise.id}") {
                    Text(exercise.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(records.sortedBy { it.set.setOrder }, key = { "history-set-${it.set.id}" }) { record ->
                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column {
                                Text("第 ${record.set.setOrder} 组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (record.set.isWarmup) Text("热身组 · 不计 PR", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                if (!record.set.completed) Text("失败组 · 不计 PR", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                record.set.supersetId?.let { Text("超级组 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                                if (record.set.note.isNotBlank()) Text(record.set.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column {
                                Text(setValueLabel(exercise.trackingType, record.set, isBarbellExercise(exercise)), fontWeight = FontWeight.Bold)
                                record.set.rpe?.let { Text("RPE ${formatOne(it)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                record.set.rir?.let { Text("RIR ${formatOne(it)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            if (detail.sets.isEmpty()) {
                item { Text("这场训练没有组记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (showSaveTemplate) {
        NameWorkoutTemplateDialog(
            title = "保存这场训练为模板",
            initialName = "${workoutDateLabel(detail.summary.recordedLocalDate)} 训练",
            isSaving = isSaving,
            onDismiss = { showSaveTemplate = false },
            onConfirm = { name ->
                onSaveAsTemplate(detail.summary.session.id, name)
                showSaveTemplate = false
            },
        )
    }
    if (showCorrectionConfirmation) {
        AlertDialog(
            onDismissRequest = { showCorrectionConfirmation = false },
            title = { Text("创建本场更正草稿？") },
            text = {
                Text(
                    "将复制 ${detail.sets.size} 组到独立更正草稿；本场现有 ${detail.personalRecords.size} 条 PR。" +
                        "保存更正前原完成记录和 PR 保持不变；保存时会全量重算全部训练 PR。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSaving && !hasActiveWorkout,
                    modifier = Modifier.semantics { contentDescription = "确认更正本场训练" },
                    onClick = {
                        showCorrectionConfirmation = false
                        onStartCorrection(detail.summary.session.id)
                    },
                ) { Text("确认开始更正") }
            },
            dismissButton = {
                TextButton(onClick = { showCorrectionConfirmation = false }) { Text("保持原记录") }
            },
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("永久删除整场训练？") },
            text = {
                Text(
                    "将删除整场、${detail.sets.size} 个组和 ${detail.personalRecords.size} 条关联 PR，" +
                        "并全量重算其余训练 PR。此操作完成后不能撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSaving && !hasActiveWorkout,
                    modifier = Modifier.semantics { contentDescription = "确认永久删除本场训练" },
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteHistory(detail.summary.session.id)
                    },
                ) { Text("确认永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消删除") }
            },
        )
    }
}

@Composable
private fun PrimaryPrCard(exercise: Exercise, summary: PrSummary?) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text(exercise.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
            }
            when {
                summary == null -> {
                    Text("PR 摘要尚未加载；可在训练历史中查看已记录组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !summary.hasData -> {
                    Text("完成该动作的第一组正式训练后建立 PR 基准", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        summary.values.forEach { PrValue(it.label, it.value) }
                    }
                    if (isBarbellExercise(exercise)) {
                        Text(
                            "这里的重量与 PR 均为含杆总重量，不存储单侧片重。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    if (exercise.trackingType == TrackingType.WEIGHT_REPS) {
                        Text(
                            "5RM 仅统计已完成、非热身且恰好完成 5 次的正式组，不代表必须进行极限测试。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    summary.latestSetLabel?.let { Text("最近：$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun PrValue(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ExerciseLibraryRow(exercise: Exercise, onTogglePrimary: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(exercise.name, fontWeight = FontWeight.SemiBold)
                    if (exercise.isCustom) {
                        Text("  自定义", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                    }
                }
                Text("${exercise.category} · ${trackingLabel(exercise.trackingType)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            IconButton(onClick = onTogglePrimary) {
                Icon(
                    if (exercise.isPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (exercise.isPrimary) "取消主要动作" else "设为主要动作",
                    tint = if (exercise.isPrimary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TemplateActionButtons(
    templateId: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackActions = maxWidth < 276.dp || LocalDensity.current.fontScale >= 1.3f
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("template-actions-stacked-$templateId"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("template-edit-$templateId"),
                ) {
                    Text(
                        "编辑模板",
                        modifier = Modifier.fillMaxWidth().testTag("template-edit-label-$templateId"),
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("template-delete-$templateId"),
                ) {
                    Text(
                        "删除模板",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().testTag("template-delete-label-$templateId"),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("template-actions-row-$templateId"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("template-edit-$templateId"),
                ) {
                    Text(
                        "编辑模板",
                        modifier = Modifier.fillMaxWidth().testTag("template-edit-label-$templateId"),
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("template-delete-$templateId"),
                ) {
                    Text(
                        "删除模板",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().testTag("template-delete-label-$templateId"),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun correctionSetEditingExercises(
    activeExercises: List<Exercise>,
    original: WorkoutHistoryDetail?,
): List<Exercise> = (
    activeExercises + original?.sets.orEmpty().map(WorkoutHistorySet::exercise)
).distinctBy(Exercise::id)

@Composable
private fun ActiveWorkoutScreen(
    state: AppUiState,
    onAddSet: (Exercise, WorkoutSetInput) -> Unit,
    onAcknowledgeSingleSet: (String) -> Unit,
    onAcknowledgeFiveByFive: (String) -> Unit,
    onStartRestTimer: (Int) -> Unit,
    onClearRestTimer: () -> Unit,
    onAddFiveByFive: (Exercise, Double, String) -> Unit,
    onUpdateSet: (WorkoutSet) -> Unit,
    onDeleteSet: (Long) -> Unit,
    onDeleteSetBatch: (String) -> Unit,
    onCancelWorkout: () -> Unit,
    onCompleteWorkout: () -> Unit,
    onAddCustomExercise: (String, String, TrackingType, Boolean, (Exercise?) -> Unit) -> Unit,
    onSaveActiveAsTemplate: (String) -> Unit,
    onUpdateWorkoutEditor: (Long, WorkoutEditorTransform) -> Unit,
    onLoadPlanItem: (String) -> Unit,
    onUpdateActivePlanItem: (WorkoutPlanItem) -> Unit,
    onRemoveActivePlanItem: (String) -> Unit,
    onUpdateBarbellBarWeight: (Double) -> Unit,
) {
    val session = requireNotNull(state.activeWorkout)
    val exerciseCatalog = state.exerciseCatalog.ifEmpty { state.exercises }
    val isCorrection = session.correctionOfSessionId != null
    // Active exercise lists intentionally hide archived rows, while a historical
    // receipt may still reference one. Keep those immutable receipt definitions
    // available for rendering and editing the copied correction sets so an old
    // duration/bodyweight exercise is never silently treated as weight+reps.
    val setEditingExercises = correctionSetEditingExercises(
        activeExercises = state.exercises,
        original = state.workoutCorrectionOriginal,
    )
    val persistedEditor = state.workoutEditorDraft?.takeIf { it.sessionId == session.id }
    val fallbackExerciseId = state.exercises.firstOrNull { it.isPrimary }?.id ?: state.exercises.firstOrNull()?.id
    var selectedExerciseId by rememberSaveable(session.id) {
        mutableStateOf(persistedEditor?.selectedExerciseId ?: fallbackExerciseId)
    }
    var search by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.weightText ?: "80") }
    var repsText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.repsText ?: "8") }
    var durationText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.durationText ?: "60") }
    var rpeText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.rpeText.orEmpty()) }
    var rirText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.rirText.orEmpty()) }
    var noteText by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.noteText.orEmpty()) }
    var isWarmup by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.isWarmup ?: false) }
    var isFailed by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.isFailed ?: false) }
    var autoRest by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.autoRest ?: true) }
    var restSeconds by rememberSaveable(session.id) {
        mutableIntStateOf(persistedEditor?.restSeconds ?: session.restDurationSeconds)
    }
    var selectedSupersetId by rememberSaveable(session.id) { mutableStateOf(persistedEditor?.selectedSupersetId) }
    var barWeightText by rememberSaveable { mutableStateOf(compactPlannerNumber(state.barbellBarWeightKg)) }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCustomName by rememberSaveable { mutableStateOf<String?>(null) }
    var customSaveError by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSet by remember { mutableStateOf<WorkoutSet?>(null) }
    var deletingSet by remember { mutableStateOf<WorkoutSet?>(null) }
    var deletingBatchId by remember { mutableStateOf<String?>(null) }
    var pendingFiveByFive by remember { mutableStateOf<PendingFiveByFiveConfirmation?>(null) }
    var showCompleteConfirmation by remember { mutableStateOf(false) }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var showSaveTemplate by rememberSaveable { mutableStateOf(false) }
    var showExercisePicker by rememberSaveable(session.id) { mutableStateOf(false) }
    var showAdvancedSetOptions by rememberSaveable(session.id) { mutableStateOf(false) }
    var editingPlanItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingPlanItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var waitingForSingleSetSave by rememberSaveable { mutableStateOf(false) }
    var quickUndoLocked by rememberSaveable(session.id) { mutableStateOf(false) }
    var fiveByFiveReceiptReady by rememberSaveable { mutableStateOf(false) }
    var fiveByFivePostReceiptGuard by rememberSaveable { mutableStateOf(false) }
    var singleSetSaveRevisionAtSubmit by rememberSaveable {
        mutableStateOf(state.workoutSetSaveRevision)
    }
    var submittedCommitId by rememberSaveable { mutableStateOf<String?>(null) }
    val fallbackCommitId = rememberSaveable(session.id) { UUID.randomUUID().toString() }
    fun selectExercise(exercise: Exercise) {
        if (selectedExerciseId == exercise.id) return
        selectedExerciseId = exercise.id
        weightText = when (exercise.trackingType) {
            TrackingType.BODYWEIGHT_REPS -> "0"
            TrackingType.ASSISTED_REPS -> "30"
            else -> "80"
        }
        repsText = "8"
        durationText = "60"
        rpeText = ""
        rirText = ""
        noteText = ""
        isWarmup = false
        isFailed = false
        val nextCommitId = UUID.randomUUID().toString()
        onUpdateWorkoutEditor(session.id) { current ->
            current.copy(
                selectedExerciseId = exercise.id,
                weightText = weightText,
                repsText = repsText,
                durationText = durationText,
                rpeText = "",
                rirText = "",
                noteText = "",
                isWarmup = false,
                isFailed = false,
                commitId = nextCommitId,
            )
        }
    }
    LaunchedEffect(persistedEditor?.revision) {
        persistedEditor?.let { restored ->
            selectedExerciseId = restored.selectedExerciseId
            weightText = restored.weightText
            repsText = restored.repsText
            durationText = restored.durationText
            rpeText = restored.rpeText
            rirText = restored.rirText
            noteText = restored.noteText
            isWarmup = restored.isWarmup
            isFailed = restored.isFailed
            autoRest = restored.autoRest
            restSeconds = restored.restSeconds
            selectedSupersetId = restored.selectedSupersetId
        }
    }
    LaunchedEffect(state.barbellBarWeightKg) {
        barWeightText = compactPlannerNumber(state.barbellBarWeightKg)
    }
    LaunchedEffect(state.exercises, selectedExerciseId) {
        if (state.exercises.none { it.id == selectedExerciseId }) {
            state.exercises.firstOrNull { it.isPrimary }
                ?.let(::selectExercise)
                ?: state.exercises.firstOrNull()?.let(::selectExercise)
        }
    }
    val clockAnchor = remember(
        session.id,
        session.startedAtMillis,
        session.restTimerEndAtMillis,
        session.restDurationSeconds,
    ) {
        workoutClockAnchor(
            session = session,
            wallNowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
    }
    var elapsedRealtimeNow by remember(clockAnchor) {
        mutableLongStateOf(clockAnchor.elapsedRealtimeAtAnchorMillis)
    }
    LaunchedEffect(clockAnchor) {
        while (true) {
            delay(1_000)
            elapsedRealtimeNow = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(
        state.workoutSetSaveRevision,
        state.isSavingWorkout,
        state.unacknowledgedWorkoutSetCommitId,
        waitingForSingleSetSave,
    ) {
        when (
            singleSetSaveUiDecision(
                waitingForSingleSetSave,
                state.isSavingWorkout,
                singleSetSaveRevisionAtSubmit,
                state.workoutSetSaveRevision,
            )
        ) {
            SingleSetSaveUiDecision.CLEAR_AFTER_SUCCESS -> {
                val committedId = submittedCommitId
                if (committedId != null && state.unacknowledgedWorkoutSetCommitId == committedId) {
                    delay(SINGLE_SET_SUCCESS_ACK_DELAY_MILLIS)
                    onAcknowledgeSingleSet(committedId)
                    submittedCommitId = null
                    waitingForSingleSetSave = false
                }
            }
            SingleSetSaveUiDecision.RELEASE_WITHOUT_CLEAR -> {
                // Also acts as a short local debounce before ViewModel busy state is
                // observed. A failed/refused write releases the lock without clearing.
                delay(SINGLE_SET_SUBMIT_COOLDOWN_MILLIS)
                if (state.workoutSetSaveRevision == singleSetSaveRevisionAtSubmit) {
                    submittedCommitId = null
                    waitingForSingleSetSave = false
                }
            }
            SingleSetSaveUiDecision.WAIT -> Unit
        }
    }
    LaunchedEffect(quickUndoLocked) {
        if (quickUndoLocked) {
            delay(QUICK_UNDO_GUARD_MILLIS)
            quickUndoLocked = false
        }
    }
    val seconds = monotonicWorkoutElapsedSeconds(clockAnchor, elapsedRealtimeNow)
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)
    val selectedExercise = state.exercises.firstOrNull { it.id == selectedExerciseId }
    val filtered = state.exercises.filter { exerciseMatches(it, search) }
    val lastRecordedSet = state.activeSets.maxByOrNull { it.id }
    // Repeat belongs to the visible exercise card. Undo still targets the last
    // write in the workout, including an unsuccessful set or a confirmed batch.
    val lastRepeatableSet = state.activeSets
        .filter { it.exerciseId == selectedExerciseId && it.completed }
        .maxByOrNull { it.id }
    val existingSupersets = selectableSupersetIds(state.activeSets, selectedSupersetId = null)
    val selectableSupersets = selectableSupersetIds(state.activeSets, selectedSupersetId)
    val weight = weightText.replace(',', '.').toDoubleOrNull()
    val reps = repsText.toIntOrNull()
    val duration = durationText.toIntOrNull()
    val rpe = optionalDecimal(rpeText)
    val rir = optionalDecimal(rirText)
    val intensityValid = (rpeText.isBlank() || (rpe != null && rpe in 0.0..10.0)) &&
        (rirText.isBlank() || (rir != null && rir in 0.0..10.0))
    val parsedBarWeight = barWeightText.replace(',', '.').toDoubleOrNull()
    val restEnd = session.restTimerEndAtMillis
    val restRemaining = restEnd?.let {
        monotonicRestRemainingSeconds(clockAnchor, elapsedRealtimeNow)
    }
    val pendingSetInput = selectedExercise?.let { exercise ->
        WorkoutSetInput(
            weightKg = if (exercise.trackingType == TrackingType.DURATION) 0.0 else weight ?: Double.NaN,
            reps = if (exercise.trackingType == TrackingType.DURATION) 0 else reps ?: -1,
            durationSeconds = if (exercise.trackingType == TrackingType.DURATION) duration ?: -1 else 0,
            completed = !isFailed,
            isWarmup = isWarmup,
            rpe = rpe,
            rir = rir,
            note = noteText.trim(),
            supersetId = selectedSupersetId,
            restSecondsAfter = if (autoRest) restSeconds else null,
            commitId = persistedEditor?.commitId ?: fallbackCommitId,
        )
    }
    val pendingSetError = when {
        !intensityValid -> "RPE 和 RIR 必须留空或填写 0–10"
        selectedExercise != null -> barbellTotalValidationError(selectedExercise, weight, parsedBarWeight)
            ?: pendingSetInput?.validationError(selectedExercise.trackingType)
        else -> "请先选择训练动作"
    }
    val pendingSetValid = selectedExercise != null && pendingSetError == null
    val loadedPlanItem = state.activeWorkoutPlan?.items
        ?.firstOrNull { it.plannedCommitId == persistedEditor?.commitId }
    val unacknowledgedCommitId = state.unacknowledgedWorkoutSetCommitId
    val unacknowledgedFiveByFiveCommitId = state.unacknowledgedFiveByFiveCommitId
    val workoutWriteLocked = state.isSavingWorkout || unacknowledgedCommitId != null ||
        unacknowledgedFiveByFiveCommitId != null || fiveByFivePostReceiptGuard
    LaunchedEffect(unacknowledgedFiveByFiveCommitId, fiveByFivePostReceiptGuard) {
        fiveByFiveReceiptReady = false
        val expectedBatchId = unacknowledgedFiveByFiveCommitId
        if (expectedBatchId != null) {
            // Keep queued taps from the preceding confirmation dialog from landing
            // on this receipt and acknowledging it immediately.
            delay(FIVE_BY_FIVE_RECEIPT_GUARD_MILLIS)
            if (state.unacknowledgedFiveByFiveCommitId == expectedBatchId) {
                fiveByFiveReceiptReady = true
            }
        } else if (fiveByFivePostReceiptGuard) {
            // After acknowledgement, keep the underlying form disabled briefly so
            // a sustained click stream cannot pass through to a new dialog.
            delay(FIVE_BY_FIVE_POST_RECEIPT_GUARD_MILLIS)
            fiveByFivePostReceiptGuard = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .imePadding()
            .testTag("active-workout-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isCorrection) "更正已完成训练" else "正在训练",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        workoutSetSummaryLabel(state.activeSets),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    if (isCorrection) "更正中" else timer,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isCorrection) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("workout-correction-banner")
                        .semantics {
                            contentDescription = "正在更正已完成训练；原记录在保存更正前保持不变"
                        },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("正在更正", fontWeight = FontWeight.Bold)
                        Text(
                            "原记录保持锁定；保存本次纠错后会原子替换并全量重算 PR。",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        state.workoutCorrectionOriginal?.let { original ->
                            Text(
                                "原场日期 ${workoutDateLabel(original.summary.recordedLocalDate)} · " +
                                    "${original.personalRecords.size} 条现有 PR",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { showCancelConfirmation = true },
                    enabled = !workoutWriteLocked,
                    modifier = Modifier.semantics {
                        contentDescription = if (isCorrection) "放弃本场更正" else "取消本次训练"
                    },
                ) {
                    Text(if (isCorrection) "放弃更正" else "取消训练")
                }
                TextButton(
                    onClick = { showSaveTemplate = true },
                    enabled = !workoutWriteLocked && (state.activeSets.any { it.completed } || state.activeWorkoutPlan?.items?.isNotEmpty() == true),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("保存为模板", modifier = Modifier.padding(start = 6.dp))
                }
            }
            when {
                state.isAutoSavingWorkoutEditorDraft -> Text(
                    "正在保存可恢复草稿…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                state.workoutEditorDraftSaveError != null -> Text(
                    state.workoutEditorDraftSaveError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
        if (unacknowledgedCommitId != null && unacknowledgedFiveByFiveCommitId == null && !waitingForSingleSetSave) {
            item(key = "recovered-single-set-receipt") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("上一组已经安全保存", fontWeight = FontWeight.Bold)
                        Text(
                            "App 在保存确认完成前被中断。记录只写入一次；请确认看到后再开始下一组。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onAcknowledgeSingleSet(unacknowledgedCommitId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("我看到了，继续记录下一组") }
                    }
                }
            }
        }
        state.activeWorkoutPlan?.let { plan ->
            item(key = "active-plan-heading") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("本次计划 · ${plan.sourceLabel}", fontWeight = FontWeight.Bold)
                        Text(
                            "剩余 ${plan.items.size} 个计划组；只有点击“记录已完成一组”后才写入历史和 PR。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            items(plan.items, key = { "active-plan-item-${it.id}" }) { item ->
                val exercise = state.exercises.firstOrNull { it.id == item.exerciseId }
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(exercise?.name ?: "动作已不可用", fontWeight = FontWeight.SemiBold)
                        Text(planItemValueLabel(item, exercise), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onLoadPlanItem(item.id) },
                                enabled = !workoutWriteLocked && exercise != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("载入") }
                            OutlinedButton(
                                onClick = { editingPlanItemId = item.id },
                                enabled = !workoutWriteLocked,
                                modifier = Modifier.weight(1f),
                            ) { Text("调整") }
                            TextButton(
                                onClick = { deletingPlanItemId = item.id },
                                enabled = !workoutWriteLocked,
                                modifier = Modifier.weight(1f),
                            ) { Text("移除") }
                        }
                    }
                }
            }
        }
        if (restRemaining != null) {
            item(key = "active-rest-timer") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (restRemaining > 0) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("workout-rest-timer"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(if (restRemaining > 0) "休息 ${countdownLabel(restRemaining)}" else "休息结束", fontWeight = FontWeight.Bold)
                            Text(if (restRemaining > 0) "计时会持续运行" else "可以开始下一组", fontSize = 12.sp)
                        }
                        TextButton(onClick = onClearRestTimer, enabled = !workoutWriteLocked) { Text("跳过") }
                    }
                }
            }
        }
        if (showExercisePicker || selectedExercise == null) item(key = "exercise-picker") {
            Text("更换动作", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("搜索动作") },
                trailingIcon = {
                    IconButton(onClick = { showCustomDialog = true }, enabled = !workoutWriteLocked) {
                        Icon(Icons.Default.Add, contentDescription = "自定义动作")
                    }
                },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filtered.forEach { exercise ->
                    FilterChip(
                        selected = exercise.id == selectedExerciseId,
                        onClick = { selectExercise(exercise) },
                        modifier = Modifier.testTag("workout-exercise-${exercise.id}"),
                        label = { Text(exercise.name, maxLines = 1) },
                    )
                }
            }
            TextButton(
                onClick = { showExercisePicker = false },
                enabled = selectedExercise != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("收起动作选择") }
        }
        if (selectedExercise != null) {
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selectedExercise.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                if (selectedExercise.isPrimary) {
                                    Text("主要动作 · 参与 PR", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            TextButton(
                                onClick = { showExercisePicker = !showExercisePicker },
                                enabled = !workoutWriteLocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("workout-change-exercise"),
                            ) { Text(if (showExercisePicker) "收起" else "更换") }
                        }
                        if (showAdvancedSetOptions && isBarbellExercise(selectedExercise)) {
                            val loading = if (weight != null && parsedBarWeight != null) {
                                barbellLoadingBreakdown(weight, parsedBarWeight)
                            } else {
                                null
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("杠铃统一口径：总重量包含杆和两侧杠铃片", fontWeight = FontWeight.SemiBold)
                                    OutlinedTextField(
                                        value = barWeightText,
                                        onValueChange = { value ->
                                            if (value.length <= 16) {
                                                barWeightText = value
                                                value.replace(',', '.').toDoubleOrNull()
                                                    ?.takeIf { it.isFinite() && it in 0.0..50.0 }
                                                    ?.let(onUpdateBarbellBarWeight)
                                            }
                                        },
                                        label = { Text("杆重 kg") },
                                        supportingText = { Text("常见奥杆 20 kg；可按实际杆修改") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("workout-bar-weight-input"),
                                    )
                                    Text(
                                        loading?.displayLabel ?: "填写有效总重量与杆重后显示每侧装片",
                                        color = if (loading?.isValid == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        if (selectedExercise.trackingType == TrackingType.DURATION) {
                            OutlinedTextField(
                                value = durationText,
                                onValueChange = { value ->
                                    if (value.length <= 64) {
                                        durationText = value
                                        onUpdateWorkoutEditor(session.id) { it.copy(durationText = value) }
                                    }
                                },
                                label = { Text(if (isFailed) "尝试时长（秒，可为 0）" else "完成时长（秒）") },
                                modifier = Modifier.fillMaxWidth().testTag("workout-duration-input"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = weightText,
                                    onValueChange = { value ->
                                        if (value.length <= 64) {
                                            weightText = value
                                            onUpdateWorkoutEditor(session.id) { it.copy(weightText = value) }
                                        }
                                    },
                                    label = {
                                        Text(
                                            when (selectedExercise.trackingType) {
                                                TrackingType.WEIGHT_REPS -> if (isBarbellExercise(selectedExercise)) "总重量（含杆）kg" else "重量 kg"
                                                TrackingType.BODYWEIGHT_REPS -> "额外负重 kg"
                                                TrackingType.ASSISTED_REPS -> "辅助重量 kg"
                                                TrackingType.DURATION -> "重量 kg"
                                            },
                                        )
                                    },
                                    modifier = Modifier.weight(1f).testTag("workout-weight-input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = repsText,
                                    onValueChange = { value ->
                                        if (value.length <= 64) {
                                            repsText = value
                                            onUpdateWorkoutEditor(session.id) { it.copy(repsText = value) }
                                        }
                                    },
                                    label = { Text(if (isFailed) "完成次数（可为 0）" else "完成次数") },
                                    modifier = Modifier.weight(1f).testTag("workout-reps-input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }

                        pendingSetError?.let { errorText ->
                            Text("无法记录：$errorText", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (canSubmitSingleSet(workoutWriteLocked, waitingForSingleSetSave, pendingSetValid)) {
                                    waitingForSingleSetSave = true
                                    singleSetSaveRevisionAtSubmit = state.workoutSetSaveRevision
                                    val input = requireNotNull(pendingSetInput)
                                    submittedCommitId = input.commitId
                                    onAddSet(selectedExercise, input)
                                }
                            },
                            enabled = canSubmitSingleSet(workoutWriteLocked, waitingForSingleSetSave, pendingSetValid),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("workout-add-set"),
                        ) {
                            Text(if (isFailed) "记录失败组" else "记录一组", fontWeight = FontWeight.Bold)
                        }

                        if (lastRecordedSet != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().testTag("workout-quick-set-actions"),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (lastRepeatableSet != null) {
                                    OutlinedButton(
                                        onClick = {
                                            if (!workoutWriteLocked && !waitingForSingleSetSave) {
                                                val repeatedInput = WorkoutSetInput(
                                                    weightKg = lastRepeatableSet.weightKg,
                                                    reps = lastRepeatableSet.reps,
                                                    durationSeconds = lastRepeatableSet.durationSeconds,
                                                    completed = lastRepeatableSet.completed,
                                                    isWarmup = lastRepeatableSet.isWarmup,
                                                    rpe = lastRepeatableSet.rpe,
                                                    rir = lastRepeatableSet.rir,
                                                    note = lastRepeatableSet.note,
                                                    supersetId = lastRepeatableSet.supersetId,
                                                    restSecondsAfter = if (autoRest) restSeconds else null,
                                                    commitId = UUID.randomUUID().toString(),
                                                )
                                                waitingForSingleSetSave = true
                                                singleSetSaveRevisionAtSubmit = state.workoutSetSaveRevision
                                                submittedCommitId = repeatedInput.commitId
                                                onAddSet(selectedExercise, repeatedInput)
                                            }
                                        },
                                        enabled = !workoutWriteLocked && !waitingForSingleSetSave,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workout-repeat-last-set"),
                                    ) {
                                        Text(
                                            "重复 ${selectedExercise.name} · " +
                                                setValueLabel(selectedExercise.trackingType, lastRepeatableSet, isBarbellExercise(selectedExercise)) +
                                                (if (lastRepeatableSet.isWarmup) " · 热身" else ""),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.testTag("workout-repeat-last-set-label"),
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        if (!quickUndoLocked) {
                                            // The lock is set before invoking storage so a second
                                            // queued click cannot target the newly exposed last set.
                                            quickUndoLocked = true
                                            lastRecordedSet.batchId?.let(onDeleteSetBatch) ?: onDeleteSet(lastRecordedSet.id)
                                        }
                                    },
                                    enabled = !workoutWriteLocked && !waitingForSingleSetSave && !quickUndoLocked,
                                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("workout-undo-last-set"),
                                ) {
                                    Text(
                                        when {
                                            quickUndoLocked -> "已发送一次撤销"
                                            lastRecordedSet.batchId == null -> "撤销上一组"
                                            else -> "撤销上一批"
                                        },
                                    )
                                }
                            }
                            if (quickUndoLocked) {
                                Text(
                                    "已只发送一次撤销；等待完成后如需继续，请再点一次。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .testTag("workout-undo-guard-receipt")
                                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                                )
                            }
                        }

                        TextButton(
                            onClick = { showAdvancedSetOptions = !showAdvancedSetOptions },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("workout-more-options"),
                        ) {
                            Text(if (showAdvancedSetOptions) "收起更多选项" else "更多选项")
                            Icon(
                                if (showAdvancedSetOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                        }

                        if (showAdvancedSetOptions) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = rpeText,
                                onValueChange = { value ->
                                    if (value.length <= 64) {
                                        rpeText = value
                                        onUpdateWorkoutEditor(session.id) { it.copy(rpeText = value) }
                                    }
                                },
                                label = { Text("RPE 0–10（可选）") },
                                modifier = Modifier.weight(1f).testTag("workout-rpe-input"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                isError = rpeText.isNotBlank() && (rpe == null || rpe !in 0.0..10.0),
                            )
                            OutlinedTextField(
                                value = rirText,
                                onValueChange = { value ->
                                    if (value.length <= 64) {
                                        rirText = value
                                        onUpdateWorkoutEditor(session.id) { it.copy(rirText = value) }
                                    }
                                },
                                label = { Text("RIR 0–10（可选）") },
                                modifier = Modifier.weight(1f).testTag("workout-rir-input"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                isError = rirText.isNotBlank() && (rir == null || rir !in 0.0..10.0),
                            )
                        }
                        Text("RPE 是主观用力程度；RIR 是预计还能完成的次数。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { value ->
                                if (value.length <= 500) {
                                    noteText = value
                                    onUpdateWorkoutEditor(session.id) { it.copy(noteText = value) }
                                }
                            },
                            label = { Text("单组备注（可选）") },
                            supportingText = { Text("${noteText.length}/500") },
                            modifier = Modifier.fillMaxWidth().testTag("workout-note-input"),
                            minLines = 2,
                            maxLines = 3,
                        )

                        LabeledToggleRow(
                            label = "热身组（不计 PR）",
                            checked = isWarmup,
                            onCheckedChange = {
                                isWarmup = it
                                if (it) isFailed = false
                                val warmupValue = it
                                onUpdateWorkoutEditor(session.id) { current ->
                                    current.copy(isWarmup = warmupValue, isFailed = if (warmupValue) false else current.isFailed)
                                }
                            },
                            visual = ToggleVisual.CHECKBOX,
                            testTag = "workout-warmup-toggle",
                        )
                        LabeledToggleRow(
                            label = "失败组（不计 PR）",
                            checked = isFailed,
                            onCheckedChange = {
                                isFailed = it
                                if (it) isWarmup = false
                                val failedValue = it
                                onUpdateWorkoutEditor(session.id) { current ->
                                    current.copy(isFailed = failedValue, isWarmup = if (failedValue) false else current.isWarmup)
                                }
                            },
                            visual = ToggleVisual.CHECKBOX,
                            testTag = "workout-failed-toggle",
                        )

                        Text("超级组（给连续动作选择同一个标识）", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedSupersetId == null,
                                onClick = {
                                    selectedSupersetId = null
                                    onUpdateWorkoutEditor(session.id) { it.copy(selectedSupersetId = null) }
                                },
                                label = { Text("不加入") },
                            )
                            selectableSupersets.forEach { groupId ->
                                FilterChip(
                                    selected = selectedSupersetId == groupId,
                                    onClick = {
                                        selectedSupersetId = groupId
                                        onUpdateWorkoutEditor(session.id) { it.copy(selectedSupersetId = groupId) }
                                    },
                                    label = { Text("超级组 $groupId") },
                                )
                            }
                            AssistChip(
                                onClick = {
                                    val newId = nextSupersetId(state.activeSets)
                                    selectedSupersetId = newId
                                    onUpdateWorkoutEditor(session.id) { it.copy(selectedSupersetId = newId) }
                                },
                                label = { Text("新建超级组") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            )
                        }

                        LabeledToggleRow(
                            label = "记录后自动开始 ${restSeconds} 秒休息",
                            checked = autoRest,
                            onCheckedChange = { value ->
                                autoRest = value
                                onUpdateWorkoutEditor(session.id) { it.copy(autoRest = value) }
                            },
                            visual = ToggleVisual.CHECKBOX,
                            testTag = "workout-auto-rest-toggle",
                        )

                        Text("休息时长", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(60, 90, 120, 180).forEach { secondsOption ->
                                FilterChip(
                                    selected = restSeconds == secondsOption,
                                    onClick = {
                                        restSeconds = secondsOption
                                        onUpdateWorkoutEditor(session.id) { it.copy(restSeconds = secondsOption) }
                                    },
                                    label = { Text("${secondsOption} 秒") },
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { onStartRestTimer(restSeconds) },
                            enabled = !workoutWriteLocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(if (restRemaining != null && restRemaining > 0) "按当前预设重新计时" else "现在开始休息") }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (loadedPlanItem == null && !isFailed && !isWarmup &&
                                (selectedExercise.trackingType == TrackingType.WEIGHT_REPS || selectedExercise.trackingType == TrackingType.BODYWEIGHT_REPS)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        pendingFiveByFive = PendingFiveByFiveConfirmation(
                                            exercise = selectedExercise,
                                            loadKg = weight ?: 0.0,
                                            batchId = UUID.randomUUID().toString(),
                                        )
                                    },
                                    enabled = !workoutWriteLocked && weight != null && weight.isFinite() && weight in 0.0..1_000.0 &&
                                        (selectedExercise.trackingType != TrackingType.WEIGHT_REPS || weight > 0.0) &&
                                        barbellTotalValidationError(selectedExercise, weight, parsedBarWeight) == null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("workout-five-by-five-submit"),
                                ) {
                                    Text(
                                        "记录 ${if (isBarbellExercise(selectedExercise)) "总重量 " else ""}" +
                                            "${formatOne(weight ?: 0.0)} kg 5×5",
                                    )
                                }
                            }
                        }
                        }
                        if (loadedPlanItem != null && !isFailed && !isWarmup &&
                            (selectedExercise.trackingType == TrackingType.WEIGHT_REPS || selectedExercise.trackingType == TrackingType.BODYWEIGHT_REPS)
                        ) {
                            Text(
                                "已载入一个计划组；请逐组记录，完成后它会从未完成计划中移除。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        val supersetGroups = state.activeSets.filter { it.supersetId != null }.groupBy { requireNotNull(it.supersetId) }
        if (supersetGroups.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("本次超级组", fontWeight = FontWeight.Bold)
                        supersetGroups.forEach { (groupId, sets) ->
                            val names = sets.mapNotNull { set -> setEditingExercises.firstOrNull { it.id == set.exerciseId }?.name }.distinct()
                            Text("$groupId · ${names.joinToString(" + ")} · ${sets.size} 组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        state.activeSets.groupBy { it.exerciseId }.forEach { (exerciseId, sets) ->
            val exercise = setEditingExercises.firstOrNull { it.id == exerciseId }
            item(key = "heading-$exerciseId") {
                Text(exercise?.name ?: "动作", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            items(sets, key = { it.id }) { set ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("第 ${set.setOrder} 组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (set.isWarmup) Text("热身", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                if (!set.completed) Text("失败", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                set.supersetId?.let { Text("超级组 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                            }
                            if (set.note.isNotBlank()) Text(set.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Column {
                            Text(
                                setValueLabel(
                                    exercise?.trackingType ?: TrackingType.WEIGHT_REPS,
                                    set,
                                    exercise?.let(::isBarbellExercise) == true,
                                ),
                                fontWeight = FontWeight.Bold,
                            )
                            val effort = listOfNotNull(
                                set.rpe?.let { "RPE ${formatOne(it)}" },
                                set.rir?.let { "RIR ${formatOne(it)}" },
                            ).joinToString(" · ")
                            if (effort.isNotBlank()) Text(effort, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = { editingSet = set }, enabled = !workoutWriteLocked) {
                                Icon(Icons.Default.Edit, contentDescription = "修改该组")
                            }
                            IconButton(onClick = { deletingSet = set }, enabled = !workoutWriteLocked) {
                                Icon(Icons.Default.Delete, contentDescription = "删除该组")
                            }
                        }
                    }
                }
            }
            sets.mapNotNull { it.batchId }.distinct().forEach { batchId ->
                item(key = "undo-batch-$batchId") {
                    OutlinedButton(
                        onClick = { deletingBatchId = batchId },
                        enabled = !workoutWriteLocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("撤销这批 5×5（${sets.count { it.batchId == batchId }} 组）") }
                }
            }
        }
        item {
            Button(
                onClick = { showCompleteConfirmation = true },
                enabled = !workoutWriteLocked && state.activeSets.any { it.completed && (!isCorrection || !it.isWarmup) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(if (isCorrection) "workout-correction-save" else "workout-complete")
                    .semantics {
                        contentDescription = if (isCorrection) "保存更正并全量重算个人纪录" else "完成训练并计算个人纪录"
                    },
            ) {
                Text(if (isCorrection) "保存更正并重算 PR" else "完成训练并计算 PR", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    editingSet?.let { set ->
        val exercise = setEditingExercises.firstOrNull { it.id == set.exerciseId }
        EditSetDialog(
            set = set,
            exercises = setEditingExercises,
            initialExercise = exercise,
            barWeightKg = state.barbellBarWeightKg,
            existingSupersets = existingSupersets,
            onDismiss = { editingSet = null },
            onSave = {
                onUpdateSet(it)
                editingSet = null
            },
        )
    }
    deletingSet?.let { set ->
        AlertDialog(
            onDismissRequest = { deletingSet = null },
            title = { Text("删除第 ${set.setOrder} 组？") },
            text = { Text("删除后这组不会计入训练或 PR。") },
            confirmButton = {
                TextButton(enabled = !workoutWriteLocked, onClick = {
                    onDeleteSet(set.id)
                    deletingSet = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingSet = null }) { Text("保留") } },
        )
    }
    deletingBatchId?.let { batchId ->
        AlertDialog(
            onDismissRequest = { deletingBatchId = null },
            title = { Text("撤销整批 5×5？") },
            text = { Text("这批中的所有组都会删除，适合处理重复误点。") },
            confirmButton = {
                TextButton(enabled = !workoutWriteLocked, onClick = {
                    onDeleteSetBatch(batchId)
                    deletingBatchId = null
                }) { Text("撤销整批") }
            },
            dismissButton = { TextButton(onClick = { deletingBatchId = null }) { Text("保留") } },
        )
    }
    pendingFiveByFive?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { pendingFiveByFive = null },
            title = { Text("确认已完成 5×5？") },
            text = {
                Text(
                    "将为${confirmation.exercise.name}记录 5 组 " +
                        "${if (isBarbellExercise(confirmation.exercise)) "含杆总重量 " else ""}" +
                        "${formatOne(confirmation.loadKg)} kg × 5。请只在五组都已完成时确认；" +
                        "确认后仍可修改、逐组删除或撤销整批。",
                )
            },
            confirmButton = {
                TextButton(enabled = !workoutWriteLocked, onClick = {
                    pendingFiveByFive = null
                    onAddFiveByFive(confirmation.exercise, confirmation.loadKg, confirmation.batchId)
                }) { Text("确认五组已完成") }
            },
            dismissButton = { TextButton(onClick = { pendingFiveByFive = null }) { Text("还没完成") } },
        )
    }
    unacknowledgedFiveByFiveCommitId?.let { batchId ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("5×5 已安全保存") },
            text = {
                Text("五组使用同一个批次标识，只写入一次。请确认看到本回执后再记录下一项；若误加，可随后撤销整批。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        fiveByFivePostReceiptGuard = true
                        onAcknowledgeFiveByFive(batchId)
                    },
                    enabled = fiveByFiveReceiptReady,
                ) {
                    Text(if (fiveByFiveReceiptReady) "我看到了，继续记录" else "正在确认安全写入…")
                }
            },
        )
    }
    if (showCompleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showCompleteConfirmation = false },
            title = { Text(if (isCorrection) "保存本场更正？" else "完成本次训练？") },
            text = {
                Text(
                    if (isCorrection) {
                        "将以当前 ${state.activeSets.size} 个组原子替换原场记录，保留原完成日期和历史顺序，" +
                            "并全量重算所有训练 PR。任何失败都会整体回滚，更正草稿仍可继续。" +
                            " 当前表单若尚未点“记录”，确认后会清除且不会计入更正。"
                    } else workoutCompletionMessage(state.activeSets) +
                        " 当前表单若尚未点“记录”，确认后会清除且不会计入训练。",
                )
            },
            confirmButton = {
                TextButton(enabled = !workoutWriteLocked, onClick = {
                    onCompleteWorkout()
                    showCompleteConfirmation = false
                }) { Text(if (isCorrection) "确认保存更正" else "确认完成") }
            },
            dismissButton = {
                TextButton(onClick = { showCompleteConfirmation = false }) {
                    Text(if (isCorrection) "继续更正" else "继续记录")
                }
            },
        )
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = {
                Text(
                    if (isCorrection) "放弃本场更正？"
                    else if (state.activeSets.isEmpty()) "取消空训练？"
                    else "取消本次训练？",
                )
            },
            text = {
                Text(
                    if (isCorrection) {
                        "只删除独立更正草稿；原完成记录、组和 PR 均保持不变。" +
                            " 当前表单若尚未点“记录”，也会清除且不会计入任何训练。"
                    } else if (state.activeSets.isEmpty()) {
                        "空训练没有已记录组；确认后会直接丢弃，不会进入训练历史。" +
                            " 当前表单即使已自动保存，只要尚未点“记录”就不会入账。"
                    } else {
                        "已记录的组将保留为已取消历史，不会计入 PR。" +
                            " 当前表单若尚未点“记录”，确认后会清除且不会入账。"
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !workoutWriteLocked, onClick = {
                    onCancelWorkout()
                    showCancelConfirmation = false
                }) { Text(if (isCorrection) "确认放弃更正" else "确认取消") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) {
                    Text(if (isCorrection) "继续更正" else "继续训练")
                }
            },
        )
    }
    if (showSaveTemplate) {
        NameWorkoutTemplateDialog(
            title = "保存本场安排为模板",
            initialName = session.title.takeIf { it.isNotBlank() } ?: "力量训练模板",
            isSaving = state.isSavingWorkout,
            onDismiss = { showSaveTemplate = false },
            onConfirm = { name ->
                onSaveActiveAsTemplate(name)
                showSaveTemplate = false
            },
        )
    }
    state.activeWorkoutPlan?.items?.firstOrNull { it.id == editingPlanItemId }?.let { item ->
        WorkoutPlanItemEditorDialog(
            title = "调整本次计划项",
            initial = item,
            exercises = state.exercises,
            barWeightKg = state.barbellBarWeightKg,
            isSaving = state.isSavingWorkout,
            onDismiss = { editingPlanItemId = null },
            onSave = {
                onUpdateActivePlanItem(it)
                editingPlanItemId = null
            },
        )
    }
    state.activeWorkoutPlan?.items?.firstOrNull { it.id == deletingPlanItemId }?.let { item ->
        val exerciseName = state.exercises.firstOrNull { it.id == item.exerciseId }?.name ?: "该动作"
        AlertDialog(
            onDismissRequest = { deletingPlanItemId = null },
            title = { Text("移除计划项？") },
            text = { Text("将从本次未完成计划移除 $exerciseName；不会删除任何已记录组。") },
            confirmButton = {
                TextButton(
                    enabled = !state.isSavingWorkout,
                    onClick = {
                        onRemoveActivePlanItem(item.id)
                        deletingPlanItemId = null
                    },
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { deletingPlanItemId = null }) { Text("保留") } },
        )
    }
    LaunchedEffect(state.exercises, pendingCustomName) {
        val pendingName = pendingCustomName
        if (pendingName != null && state.exercises.any { normalizeExerciseName(it.name) == normalizeExerciseName(pendingName) }) {
            showCustomDialog = false
            pendingCustomName = null
            customSaveError = null
        }
    }
    if (showCustomDialog) {
        CustomExerciseDialog(
            existingExerciseNames = exerciseCatalog.map { it.name },
            isSaving = state.isSavingWorkout,
            saveError = customSaveError ?: state.message?.takeIf { it.startsWith("无法创建动作") },
            onDismiss = { if (!state.isSavingWorkout) showCustomDialog = false },
            onSave = { name, category, type, primary ->
                pendingCustomName = name
                customSaveError = null
                onAddCustomExercise(name, category, type, primary) { created ->
                    if (created != null) {
                        selectExercise(created)
                        search = ""
                        showCustomDialog = false
                        pendingCustomName = null
                    } else {
                        customSaveError = "未能创建动作；输入已保留，请检查后重试"
                    }
                }
            },
        )
    }
}

@Composable
private fun EditSetDialog(
    set: WorkoutSet,
    exercises: List<Exercise>,
    initialExercise: Exercise?,
    barWeightKg: Double,
    existingSupersets: List<String>,
    onDismiss: () -> Unit,
    onSave: (WorkoutSet) -> Unit,
) {
    var selectedExerciseId by remember(set.id) { mutableStateOf(set.exerciseId) }
    val selectedExercise = exercises.firstOrNull { it.id == selectedExerciseId } ?: initialExercise
    val trackingType = selectedExercise?.trackingType ?: TrackingType.WEIGHT_REPS
    val isBarbell = selectedExercise?.let(::isBarbellExercise) == true
    var weight by remember(set.id) { mutableStateOf(formatOne(set.weightKg)) }
    var reps by remember(set.id) { mutableStateOf(set.reps.toString()) }
    var duration by remember(set.id) { mutableStateOf(set.durationSeconds.toString()) }
    var warmup by remember(set.id) { mutableStateOf(set.isWarmup) }
    var failed by remember(set.id) { mutableStateOf(!set.completed) }
    var rpe by remember(set.id) { mutableStateOf(set.rpe?.let(::formatOne).orEmpty()) }
    var rir by remember(set.id) { mutableStateOf(set.rir?.let(::formatOne).orEmpty()) }
    var note by remember(set.id) { mutableStateOf(set.note) }
    var supersetId by remember(set.id) { mutableStateOf(set.supersetId) }
    val parsedWeight = weight.replace(',', '.').toDoubleOrNull()
    val parsedReps = reps.toIntOrNull()
    val parsedDuration = duration.toIntOrNull()
    val parsedRpe = optionalDecimal(rpe)
    val parsedRir = optionalDecimal(rir)
    val intensityValid = (rpe.isBlank() || (parsedRpe != null && parsedRpe in 0.0..10.0)) &&
        (rir.isBlank() || (parsedRir != null && parsedRir in 0.0..10.0))
    val candidate = WorkoutSetInput(
        weightKg = if (trackingType == TrackingType.DURATION) 0.0 else parsedWeight ?: Double.NaN,
        reps = if (trackingType == TrackingType.DURATION) 0 else parsedReps ?: -1,
        durationSeconds = if (trackingType == TrackingType.DURATION) parsedDuration ?: -1 else 0,
        completed = !failed,
        isWarmup = warmup,
        rpe = parsedRpe,
        rir = parsedRir,
        note = note.trim(),
        supersetId = supersetId,
    )
    val candidateError = candidate.validationError(trackingType)
    val barbellError = selectedExercise?.let { barbellTotalValidationError(it, parsedWeight, barWeightKg) }
    val rpeError = if (rpe.isNotBlank() && (parsedRpe == null || parsedRpe !in 0.0..10.0)) "RPE 必须在 0–10" else null
    val rirError = if (rir.isNotBlank() && (parsedRir == null || parsedRir !in 0.0..10.0)) "RIR 必须在 0–10" else null
    val valid = intensityValid && candidateError == null && barbellError == null
    val selectableSupersets = (existingSupersets + listOfNotNull(set.supersetId)).distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改第 ${set.setOrder} 组") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("动作", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    exercises.forEach { exercise ->
                        FilterChip(
                            selected = selectedExerciseId == exercise.id,
                            onClick = { selectedExerciseId = exercise.id },
                            label = { Text(exercise.name) },
                            modifier = Modifier
                                .testTag("edit-set-exercise-${exercise.id}")
                                .semantics { contentDescription = "把该组动作改为${exercise.name}" },
                        )
                    }
                }
                if (trackingType == TrackingType.DURATION) {
                    OutlinedTextField(
                        duration,
                        { duration = it },
                        label = { Text(if (failed) "尝试时长（秒，可为 0）" else "完成时长（秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    OutlinedTextField(
                        weight,
                        { weight = it },
                        label = {
                            Text(
                                if (trackingType == TrackingType.ASSISTED_REPS) "辅助重量 kg"
                                else if (trackingType == TrackingType.BODYWEIGHT_REPS) "额外负重 kg"
                                else if (isBarbell) "总重量（含杆）kg"
                                else "重量 kg",
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        reps,
                        { reps = it },
                        label = { Text(if (failed) "完成次数（可为 0）" else "完成次数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rpe,
                        onValueChange = { rpe = it },
                        label = { Text("RPE（可选）") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = rpeError != null,
                        supportingText = { Text(rpeError ?: "合法范围：0–10") },
                    )
                    OutlinedTextField(
                        value = rir,
                        onValueChange = { rir = it },
                        label = { Text("RIR（可选）") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = rirError != null,
                        supportingText = { Text(rirError ?: "合法范围：0–10") },
                    )
                }
                candidateError?.let { errorText ->
                    Text("无法保存：$errorText", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                LabeledToggleRow(
                    label = "热身组",
                    checked = warmup,
                    onCheckedChange = {
                        warmup = it
                        if (it) failed = false
                    },
                    visual = ToggleVisual.CHECKBOX,
                    testTag = "edit-set-warmup-toggle",
                )
                LabeledToggleRow(
                    label = "失败组",
                    checked = failed,
                    onCheckedChange = {
                        failed = it
                        if (it) warmup = false
                    },
                    visual = ToggleVisual.CHECKBOX,
                    testTag = "edit-set-failed-toggle",
                )
                barbellError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text("热身组和失败组均不计 PR。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 500) note = it },
                    label = { Text("单组备注（可选）") },
                    supportingText = { Text("${note.length}/500") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("超级组", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = supersetId == null, onClick = { supersetId = null }, label = { Text("不加入") })
                    selectableSupersets.forEach { groupId ->
                        FilterChip(
                            selected = supersetId == groupId,
                            onClick = { supersetId = groupId },
                            label = { Text("超级组 $groupId") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        set.copy(
                            exerciseId = selectedExerciseId,
                            loadGrams = if (trackingType == TrackingType.DURATION) 0L else ((parsedWeight ?: 0.0) * 1000.0).roundToLong(),
                            reps = if (trackingType == TrackingType.DURATION) 0 else parsedReps ?: 0,
                            durationSeconds = if (trackingType == TrackingType.DURATION) parsedDuration ?: 0 else 0,
                            completed = !failed,
                            isWarmup = warmup,
                            rpe = parsedRpe,
                            rir = parsedRir,
                            note = note.trim(),
                            supersetId = supersetId,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NameWorkoutTemplateDialog(
    title: String,
    initialName: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName.take(60)) }
    val cleanName = name.trim()
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("模板名称") },
                    supportingText = { Text("${name.length}/60；模板只保存未来计划，不会补写训练历史") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = cleanName.isNotEmpty() && !isSaving,
                onClick = { onConfirm(cleanName) },
            ) { Text(if (isSaving) "正在保存…" else "保存模板") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

@Composable
private fun WorkoutTemplateEditorDialog(
    template: WorkoutTemplate,
    exercises: List<Exercise>,
    exerciseCatalog: List<Exercise>,
    barWeightKg: Double,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkoutTemplate) -> Unit,
) {
    var name by rememberSaveable(template.id, template.updatedAtMillis) { mutableStateOf(template.name) }
    var items by remember(template.id, template.updatedAtMillis) { mutableStateOf(template.items) }
    var editingItemId by rememberSaveable(template.id, template.updatedAtMillis) { mutableStateOf<String?>(null) }
    val editing = items.firstOrNull { it.id == editingItemId }
    if (editing != null) {
        WorkoutPlanItemEditorDialog(
            title = "编辑模板计划项",
            initial = editing,
            exercises = exercises,
            barWeightKg = barWeightKg,
            isSaving = isSaving,
            onDismiss = { editingItemId = null },
            onSave = { updated ->
                items = items.map { if (it.id == updated.id) updated else it }
                editingItemId = null
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("编辑训练模板") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 60) name = it },
                        label = { Text("模板名称") },
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("计划组（可逐项修改、移除或添加）", fontWeight = FontWeight.SemiBold)
                }
                items(items, key = { "template-editor-${it.id}" }) { item ->
                    val exercise = exerciseCatalog.firstOrNull { it.id == item.exerciseId }
                    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when {
                                    exercise == null -> "动作已不可用"
                                    exercise.isArchived -> "${exercise.name}（已归档）"
                                    else -> exercise.name
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(planItemValueLabel(item, exercise), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { editingItemId = item.id },
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f),
                                ) { Text("修改") }
                                TextButton(
                                    onClick = { items = items.filterNot { it.id == item.id } },
                                    enabled = !isSaving && items.size > 1,
                                    modifier = Modifier.weight(1f),
                                ) { Text("移除") }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            val exercise = exercises.firstOrNull { it.isPrimary } ?: exercises.firstOrNull()
                            if (exercise != null) {
                                val id = UUID.randomUUID().toString()
                                items = items + WorkoutPlanItem(
                                    id = id,
                                    plannedCommitId = UUID.randomUUID().toString(),
                                    exerciseId = exercise.id,
                                    weightKg = if (exercise.trackingType == TrackingType.ASSISTED_REPS) 30.0 else if (exercise.trackingType == TrackingType.BODYWEIGHT_REPS) 0.0 else 80.0,
                                    reps = if (exercise.trackingType == TrackingType.DURATION) 0 else 8,
                                    durationSeconds = if (exercise.trackingType == TrackingType.DURATION) 60 else 0,
                                    isWarmup = false,
                                    rpe = null,
                                    rir = null,
                                    note = "",
                                    supersetId = null,
                                    autoRest = true,
                                    restSeconds = 120,
                                )
                                editingItemId = id
                            }
                        },
                        enabled = !isSaving && exercises.isNotEmpty() && items.size < 500,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加计划组", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty() && items.isNotEmpty() && !isSaving,
                onClick = { onSave(template.copy(name = name.trim(), items = items)) },
            ) { Text(if (isSaving) "正在保存…" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

@Composable
private fun WorkoutPlanItemEditorDialog(
    title: String,
    initial: WorkoutPlanItem,
    exercises: List<Exercise>,
    barWeightKg: Double,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkoutPlanItem) -> Unit,
) {
    var exerciseId by rememberSaveable(initial.id) { mutableLongStateOf(initial.exerciseId) }
    var weight by rememberSaveable(initial.id) { mutableStateOf(compactPlannerNumber(initial.weightKg)) }
    var reps by rememberSaveable(initial.id) { mutableStateOf(initial.reps.toString()) }
    var duration by rememberSaveable(initial.id) { mutableStateOf(initial.durationSeconds.toString()) }
    var rpe by rememberSaveable(initial.id) { mutableStateOf(initial.rpe?.let(::compactPlannerNumber).orEmpty()) }
    var rir by rememberSaveable(initial.id) { mutableStateOf(initial.rir?.let(::compactPlannerNumber).orEmpty()) }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.note) }
    var superset by rememberSaveable(initial.id) { mutableStateOf(initial.supersetId.orEmpty()) }
    var warmup by rememberSaveable(initial.id) { mutableStateOf(initial.isWarmup) }
    var autoRest by rememberSaveable(initial.id) { mutableStateOf(initial.autoRest) }
    var restSeconds by rememberSaveable(initial.id) { mutableIntStateOf(initial.restSeconds) }
    val exercise = exercises.firstOrNull { it.id == exerciseId }
    val parsedWeight = weight.replace(',', '.').toDoubleOrNull()
    val parsedReps = reps.toIntOrNull()
    val parsedDuration = duration.toIntOrNull()
    val parsedRpe = optionalDecimal(rpe)
    val parsedRir = optionalDecimal(rir)
    val candidate = exercise?.let {
        WorkoutSetInput(
            weightKg = if (it.trackingType == TrackingType.DURATION) 0.0 else parsedWeight ?: Double.NaN,
            reps = if (it.trackingType == TrackingType.DURATION) 0 else parsedReps ?: -1,
            durationSeconds = if (it.trackingType == TrackingType.DURATION) parsedDuration ?: -1 else 0,
            completed = true,
            isWarmup = warmup,
            rpe = parsedRpe,
            rir = parsedRir,
            note = note.trim(),
            supersetId = superset.trim().uppercase().ifBlank { null },
            restSecondsAfter = if (autoRest) restSeconds else null,
            commitId = initial.plannedCommitId,
        )
    }
    val error = when {
        exercise == null -> "请选择仍然可用的动作"
        rpe.isNotBlank() && parsedRpe == null -> "RPE 必须留空或填写 0–10"
        rir.isNotBlank() && parsedRir == null -> "RIR 必须留空或填写 0–10"
        else -> barbellTotalValidationError(exercise, parsedWeight, barWeightKg)
            ?: candidate?.validationError(exercise.trackingType)
    }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("动作", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        exercises.forEach { option ->
                            FilterChip(
                                selected = option.id == exerciseId,
                                onClick = { exerciseId = option.id },
                                enabled = !isSaving,
                                label = { Text(option.name, maxLines = 1) },
                            )
                        }
                    }
                }
                if (exercise?.trackingType == TrackingType.DURATION) {
                    item {
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { if (it.length <= 64) duration = it },
                            label = { Text("计划时长（秒）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { if (it.length <= 64) weight = it },
                            label = {
                                Text(
                                    when {
                                        exercise?.trackingType == TrackingType.ASSISTED_REPS -> "辅助重量 kg"
                                        exercise?.trackingType == TrackingType.BODYWEIGHT_REPS -> "额外负重 kg"
                                        exercise?.let(::isBarbellExercise) == true -> "总重量（含杆）kg"
                                        else -> "重量 kg"
                                    },
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = reps,
                            onValueChange = { if (it.length <= 64) reps = it },
                            label = { Text("计划次数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = rpe,
                        onValueChange = { if (it.length <= 64) rpe = it },
                        label = { Text("目标 RPE（可选）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = rir,
                        onValueChange = { if (it.length <= 64) rir = it },
                        label = { Text("目标 RIR（可选）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 500) note = it },
                        label = { Text("计划备注（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                    )
                }
                item {
                    OutlinedTextField(
                        value = superset,
                        onValueChange = { if (it.length <= 20) superset = it.uppercase() },
                        label = { Text("超级组标识（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    LabeledToggleRow(
                        label = "计划为热身组（不计 PR）",
                        checked = warmup,
                        onCheckedChange = { warmup = it },
                        enabled = !isSaving,
                        visual = ToggleVisual.CHECKBOX,
                    )
                }
                item {
                    LabeledToggleRow(
                        label = "完成后自动开始休息",
                        checked = autoRest,
                        onCheckedChange = { autoRest = it },
                        enabled = !isSaving,
                        visual = ToggleVisual.CHECKBOX,
                    )
                }
                if (autoRest) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(60, 90, 120, 180).forEach { seconds ->
                                FilterChip(
                                    selected = restSeconds == seconds,
                                    onClick = { restSeconds = seconds },
                                    label = { Text("${seconds}秒") },
                                )
                            }
                        }
                    }
                }
                if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && !isSaving,
                onClick = {
                    val input = requireNotNull(candidate)
                    onSave(
                        initial.copy(
                            exerciseId = requireNotNull(exercise).id,
                            weightKg = input.weightKg,
                            reps = input.reps,
                            durationSeconds = input.durationSeconds,
                            isWarmup = input.isWarmup,
                            rpe = input.rpe,
                            rir = input.rir,
                            note = input.note,
                            supersetId = input.supersetId,
                            autoRest = autoRest,
                            restSeconds = restSeconds,
                        ),
                    )
                },
            ) { Text("保存计划项") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

@Composable
private fun CustomExerciseManagerDialog(
    exercises: List<Exercise>,
    isSaving: Boolean,
    onEdit: (Exercise) -> Unit,
    onArchive: (Exercise) -> Unit,
    onRestore: (Exercise) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = exercises.sortedWith(compareBy<Exercise> { it.isArchived }.thenBy { it.name })
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("管理自定义动作") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("custom-exercise-manager"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "可修改名称和肌群；记录方式保持不变，避免旧训练被重新解释。归档只会从新记录选择器隐藏，不删除历史、PR 或模板引用。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (ordered.isEmpty()) {
                    item { Text("还没有自定义动作。") }
                } else {
                    items(ordered, key = { "manage-custom-${it.id}" }) { exercise ->
                        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(exercise.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${exercise.category} · ${trackingLabel(exercise.trackingType)}" +
                                                if (exercise.isArchived) " · 已归档" else if (exercise.isPrimary) " · 主要动作" else "",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { onEdit(exercise) },
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f).testTag("custom-exercise-edit-${exercise.id}"),
                                    ) { Text("编辑") }
                                    TextButton(
                                        onClick = { if (exercise.isArchived) onRestore(exercise) else onArchive(exercise) },
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f).testTag("custom-exercise-archive-${exercise.id}"),
                                    ) {
                                        Text(if (exercise.isArchived) "恢复" else "归档")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("完成") } },
    )
}

@Composable
private fun EditCustomExerciseDialog(
    exercise: Exercise,
    existingExerciseNames: List<String>,
    isSaving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    var name by rememberSaveable(exercise.id, exercise.definitionVersion) { mutableStateOf(exercise.name) }
    var category by rememberSaveable(exercise.id, exercise.definitionVersion) { mutableStateOf(exercise.category) }
    var primary by rememberSaveable(exercise.id, exercise.definitionVersion) { mutableStateOf(exercise.isPrimary) }
    val nameError = customExerciseNameError(name, existingExerciseNames)
    val categoryError = exerciseCategoryError(category)
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("编辑自定义动作") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("动作名称（必填）") },
                    supportingText = { Text(nameError ?: "1–$EXERCISE_NAME_MAX_CODE_POINTS 个字符") },
                    isError = nameError != null,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("custom-exercise-edit-name"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("主要肌群（必填）") },
                    supportingText = { Text(categoryError ?: "1–$EXERCISE_CATEGORY_MAX_CODE_POINTS 个字符") },
                    isError = categoryError != null,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("custom-exercise-edit-category"),
                    singleLine = true,
                )
                Text("记录方式：${trackingLabel(exercise.trackingType)}（为保护旧训练含义，创建后不改类型）")
                if (exercise.isArchived) {
                    Text("该动作当前已归档；保存编辑不会自动恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LabeledToggleRow(
                        label = "设为主要动作",
                        supportingText = "首页重点展示该动作 PR",
                        checked = primary,
                        onCheckedChange = { primary = it },
                        enabled = !isSaving,
                        visual = ToggleVisual.CHECKBOX,
                        testTag = "custom-exercise-edit-primary",
                    )
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameError == null && categoryError == null && !isSaving,
                onClick = {
                    onSave(
                        collapseExerciseNameWhitespace(name),
                        category.trim(),
                        if (exercise.isArchived) false else primary,
                    )
                },
                modifier = Modifier.testTag("custom-exercise-edit-save"),
            ) { Text(if (isSaving) "正在保存…" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

@Composable
private fun CustomExerciseDialog(
    existingExerciseNames: List<String>,
    isSaving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, TrackingType, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    var categoryTouched by rememberSaveable { mutableStateOf(false) }
    var typeName by rememberSaveable { mutableStateOf(TrackingType.WEIGHT_REPS.name) }
    var primary by rememberSaveable { mutableStateOf(false) }
    val type = TrackingType.valueOf(typeName)
    val nameError = customExerciseNameError(name, existingExerciseNames)
    val categoryError = exerciseCategoryError(category)
    val visibleNameError = nameError.takeIf { nameTouched }
    val visibleCategoryError = categoryError.takeIf { categoryTouched }
    val displayName = collapseExerciseNameWhitespace(name)
    val trimmedCategory = category.trim()
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("新建自定义动作") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameTouched = true
                    },
                    label = { Text("动作名称（必填）") },
                    supportingText = {
                        Text(
                            visibleNameError ?: "1–$EXERCISE_NAME_MAX_CODE_POINTS 个字符；名称不能与已有动作重复",
                        )
                    },
                    isError = visibleNameError != null,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("custom-exercise-name").let { fieldModifier ->
                        if (visibleNameError != null) fieldModifier.semantics { error(visibleNameError) } else fieldModifier
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = {
                        category = it
                        categoryTouched = true
                    },
                    label = { Text("主要肌群（必填）") },
                    supportingText = {
                        Text(visibleCategoryError ?: "1–$EXERCISE_CATEGORY_MAX_CODE_POINTS 个字符")
                    },
                    isError = visibleCategoryError != null,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("custom-exercise-category").let { fieldModifier ->
                        if (visibleCategoryError != null) fieldModifier.semantics { error(visibleCategoryError) } else fieldModifier
                    },
                    singleLine = true,
                )
                Text("记录方式", fontWeight = FontWeight.SemiBold)
                TrackingType.entries.forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = { typeName = option.name },
                        enabled = !isSaving,
                        label = { Text(trackingLabel(option)) },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {
                            contentDescription = "设为主要动作；首页重点展示该动作 PR"
                            role = Role.Checkbox
                            toggleableState = if (primary) ToggleableState.On else ToggleableState.Off
                            if (!isSaving) {
                                onClick(label = "切换主要动作状态") {
                                    primary = !primary
                                    true
                                }
                            } else {
                                disabled()
                            }
                        }
                        .toggleable(
                            value = primary,
                            enabled = !isSaving,
                            role = Role.Checkbox,
                            onValueChange = { primary = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = primary, onCheckedChange = null, enabled = !isSaving)
                    Column {
                        Text("设为主要动作")
                        Text("首页重点展示该动作 PR", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (saveError != null) {
                    Text(saveError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameError == null && categoryError == null && !isSaving,
                onClick = { onSave(displayName, trimmedCategory, type, primary) },
                modifier = Modifier.testTag("custom-exercise-create"),
            ) { Text(if (isSaving) "正在创建…" else "创建并保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") } },
    )
}

internal fun templateSummaryLabel(template: WorkoutTemplate, exercises: List<Exercise>): String {
    val names = template.items.mapNotNull { item -> exercises.firstOrNull { it.id == item.exerciseId }?.name }.distinct()
    val archived = template.items.count { item -> exercises.firstOrNull { it.id == item.exerciseId }?.isArchived == true }
    val unavailable = template.items.count { item -> exercises.none { it.id == item.exerciseId } }
    return "${template.items.size} 个计划组 · ${names.take(3).joinToString("、").ifBlank { "动作不可用" }}" +
        (if (names.size > 3) "等" else "") +
        (if (archived > 0) " · $archived 项已归档，需恢复或替换" else "") +
        (if (unavailable > 0) " · $unavailable 项缺失，需修复" else "")
}

private fun planItemValueLabel(item: WorkoutPlanItem, exercise: Exercise?): String {
    val value = when (exercise?.trackingType) {
        TrackingType.DURATION -> "${item.durationSeconds} 秒"
        TrackingType.BODYWEIGHT_REPS -> "额外 ${formatOne(item.weightKg)} kg × ${item.reps}"
        TrackingType.ASSISTED_REPS -> "辅助 ${formatOne(item.weightKg)} kg × ${item.reps}"
        else -> (if (exercise?.let(::isBarbellExercise) == true) "总重量 " else "") +
            "${formatOne(item.weightKg)} kg × ${item.reps}"
    }
    val tags = listOfNotNull(
        if (item.isWarmup) "热身" else null,
        item.supersetId?.let { "超级组 $it" },
        if (item.autoRest) "休息 ${item.restSeconds} 秒" else "不自动休息",
    )
    return value + if (tags.isEmpty()) "" else " · ${tags.joinToString(" · ")}"
}

internal fun isBarbellExercise(exercise: Exercise): Boolean {
    if (exercise.trackingType != TrackingType.WEIGHT_REPS) return false
    val searchable = (listOf(exercise.name) + exercise.aliases).joinToString(" ").lowercase(Locale.ROOT)
    return searchable.contains("杠铃") || searchable.contains("硬拉") ||
        Regex("(^|\\s)(barbell|deadlift|ohp)(\\s|$)").containsMatchIn(searchable) ||
        exercise.name == "站姿推举"
}

internal fun barbellTotalValidationError(
    exercise: Exercise,
    totalWeightKg: Double?,
    barWeightKg: Double?,
): String? {
    if (!isBarbellExercise(exercise) || totalWeightKg == null) return null
    if (barWeightKg == null || !barWeightKg.isFinite() || barWeightKg !in 0.0..50.0) {
        return "请先填写 0–50 kg 的有效杆重"
    }
    return barbellLoadingBreakdown(totalWeightKg, barWeightKg)
        .takeUnless { it.isValid }
        ?.displayLabel
}

internal data class BarbellLoadingBreakdown(
    val isValid: Boolean,
    val perSideKg: Double?,
    val platesPerSideKg: List<Double>,
    val displayLabel: String,
)

/** Converts one unified total load into each side. History and PR continue to
 * receive only the total load; this is display/configuration math. */
internal fun barbellLoadingBreakdown(totalWeightKg: Double, barWeightKg: Double): BarbellLoadingBreakdown {
    if (!totalWeightKg.isFinite() || !barWeightKg.isFinite() || totalWeightKg < 0.0 || barWeightKg !in 0.0..50.0) {
        return BarbellLoadingBreakdown(false, null, emptyList(), "总重量或杆重无效")
    }
    if (totalWeightKg + 0.000_001 < barWeightKg) {
        return BarbellLoadingBreakdown(false, null, emptyList(), "总重量不能小于杆重 ${compactPlannerNumber(barWeightKg)} kg")
    }
    val perSide = (totalWeightKg - barWeightKg) / 2.0
    if (perSide <= 0.000_001) {
        return BarbellLoadingBreakdown(true, 0.0, emptyList(), "每侧不需加片；总重量就是杆重")
    }
    val available = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5, 0.25)
    var remaining = perSide
    val plates = mutableListOf<Double>()
    available.forEach { plate ->
        while (remaining + 0.000_001 >= plate) {
            plates += plate
            remaining -= plate
        }
    }
    val exact = kotlin.math.abs(remaining) <= 0.001
    val plateLabel = if (exact && plates.isNotEmpty()) {
        plates.groupingBy { it }.eachCount().entries.joinToString(" + ") { (plate, count) ->
            if (count == 1) compactPlannerNumber(plate) else "${compactPlannerNumber(plate)}×$count"
        } + " kg"
    } else {
        "请按健身房现有片组合"
    }
    return BarbellLoadingBreakdown(
        isValid = true,
        perSideKg = perSide,
        platesPerSideKg = if (exact) plates else emptyList(),
        displayLabel = "每侧需加载 ${compactPlannerNumber(perSide)} kg · 每侧片：$plateLabel",
    )
}

internal const val EXERCISE_NAME_MAX_CODE_POINTS = 60
internal const val EXERCISE_CATEGORY_MAX_CODE_POINTS = 30
private const val SINGLE_SET_SUBMIT_COOLDOWN_MILLIS = 400L
private const val SINGLE_SET_SUCCESS_ACK_DELAY_MILLIS = 1_500L
private const val FIVE_BY_FIVE_RECEIPT_GUARD_MILLIS = 2_000L
private const val FIVE_BY_FIVE_POST_RECEIPT_GUARD_MILLIS = 1_000L
private const val QUICK_UNDO_GUARD_MILLIS = 1_200L

internal enum class SingleSetSaveUiDecision {
    WAIT,
    CLEAR_AFTER_SUCCESS,
    RELEASE_WITHOUT_CLEAR,
}

internal fun canSubmitSingleSet(
    isSaving: Boolean,
    isLocallyLocked: Boolean,
    isInputValid: Boolean,
): Boolean = !isSaving && !isLocallyLocked && isInputValid

internal fun <Revision> singleSetSaveUiDecision(
    isAwaitingSave: Boolean,
    isSaving: Boolean,
    revisionAtSubmit: Revision,
    currentRevision: Revision,
): SingleSetSaveUiDecision = when {
    !isAwaitingSave -> SingleSetSaveUiDecision.WAIT
    currentRevision != revisionAtSubmit -> SingleSetSaveUiDecision.CLEAR_AFTER_SUCCESS
    !isSaving -> SingleSetSaveUiDecision.RELEASE_WITHOUT_CLEAR
    else -> SingleSetSaveUiDecision.WAIT
}

/** One process-local anchor converts the persisted wall-clock facts into
 * monotonic durations. Wall time is sampled only once; subsequent system clock
 * changes cannot freeze either timer. */
internal data class WorkoutClockAnchor(
    val elapsedRealtimeAtAnchorMillis: Long,
    val workoutElapsedAtAnchorMillis: Long,
    val restRemainingAtAnchorMillis: Long?,
)

internal fun workoutClockAnchor(
    session: WorkoutSession,
    wallNowMillis: Long,
    elapsedRealtimeMillis: Long,
): WorkoutClockAnchor {
    val workoutElapsed = if (wallNowMillis <= session.startedAtMillis) {
        0L
    } else {
        (wallNowMillis - session.startedAtMillis).takeIf { it >= 0L } ?: Long.MAX_VALUE
    }
    val restRemaining = session.restTimerEndAtMillis?.let { endAt ->
        safeRestRemainingMillis(
            restEndAtMillis = endAt,
            nowMillis = wallNowMillis,
            configuredDurationSeconds = session.restDurationSeconds,
        )
    }
    return WorkoutClockAnchor(
        elapsedRealtimeAtAnchorMillis = elapsedRealtimeMillis,
        workoutElapsedAtAnchorMillis = workoutElapsed,
        restRemainingAtAnchorMillis = restRemaining,
    )
}

internal fun monotonicWorkoutElapsedSeconds(
    anchor: WorkoutClockAnchor,
    elapsedRealtimeMillis: Long,
): Long {
    val monotonicDelta = (elapsedRealtimeMillis - anchor.elapsedRealtimeAtAnchorMillis)
        .takeIf { it >= 0L }
        ?: 0L
    val totalMillis = if (Long.MAX_VALUE - anchor.workoutElapsedAtAnchorMillis < monotonicDelta) {
        Long.MAX_VALUE
    } else {
        anchor.workoutElapsedAtAnchorMillis + monotonicDelta
    }
    return totalMillis / 1_000L
}

internal fun monotonicRestRemainingSeconds(
    anchor: WorkoutClockAnchor,
    elapsedRealtimeMillis: Long,
): Int {
    val initial = anchor.restRemainingAtAnchorMillis ?: return 0
    val monotonicDelta = (elapsedRealtimeMillis - anchor.elapsedRealtimeAtAnchorMillis)
        .takeIf { it >= 0L }
        ?: 0L
    val remaining = (initial - monotonicDelta).coerceAtLeast(0L)
    return ((remaining + 999L) / 1_000L).toInt()
}

/** Persisted timers use wall clock only to survive process death. A deadline
 * farther away than the configured duration plus a small scheduling tolerance
 * proves that wall time moved backwards, so it expires instead of clamping to a
 * frozen full-duration countdown. */
private fun safeRestRemainingMillis(
    restEndAtMillis: Long,
    nowMillis: Long,
    configuredDurationSeconds: Int,
): Long {
    if (restEndAtMillis <= nowMillis) return 0L
    val maximumSeconds = configuredDurationSeconds.coerceIn(15, 3_600)
    val maximumMillis = maximumSeconds * 1_000L
    val remainingMillis = restEndAtMillis - nowMillis
    if (remainingMillis < 0L ||
        remainingMillis > maximumMillis + REST_TIMER_WALL_CLOCK_TOLERANCE_MILLIS
    ) {
        return 0L
    }
    return remainingMillis.coerceAtMost(maximumMillis)
}

internal fun safeRestRemainingSeconds(
    restEndAtMillis: Long,
    nowMillis: Long,
    configuredDurationSeconds: Int,
): Int {
    val maximumSeconds = configuredDurationSeconds.coerceIn(15, 3_600)
    val remainingMillis = safeRestRemainingMillis(
        restEndAtMillis,
        nowMillis,
        configuredDurationSeconds,
    )
    return ((remainingMillis + 999L) / 1_000L).toInt().coerceIn(0, maximumSeconds)
}

internal fun primaryExercisesForDisplay(exercises: List<Exercise>): List<Exercise> =
    exercises.filter { it.isPrimary }

internal fun prEventDisplayLabel(
    event: PersonalRecord,
    exercises: Iterable<Exercise>,
): String {
    val exercise = exercises.firstOrNull { it.id == event.exerciseId }
    val exerciseName = exercise
        ?.name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "未知动作（ID ${event.exerciseId}）"
    val weightScope = if (exercise?.let(::isBarbellExercise) == true) " · 含杆总重量口径" else ""
    return "$exerciseName · ${event.eventKind.label} · ${event.type.label} · ${prEventValue(event)}$weightScope"
}

internal fun selectableSupersetIds(
    sets: List<WorkoutSet>,
    selectedSupersetId: String?,
): List<String> = (sets.mapNotNull { it.supersetId } + listOfNotNull(selectedSupersetId))
    .filter { it.isNotBlank() }
    .distinct()
    .sorted()

internal data class WorkoutSetBreakdown(
    val total: Int,
    val completedWorking: Int,
    val warmup: Int,
    val failed: Int,
)

internal fun workoutSetBreakdown(sets: List<WorkoutSet>): WorkoutSetBreakdown = WorkoutSetBreakdown(
    total = sets.size,
    completedWorking = sets.count { it.completed && !it.isWarmup },
    warmup = sets.count { it.completed && it.isWarmup },
    failed = sets.count { !it.completed },
)

internal fun workoutSetSummaryLabel(sets: List<WorkoutSet>): String {
    val breakdown = workoutSetBreakdown(sets)
    return "共记录 ${breakdown.total} 组：正式完成 ${breakdown.completedWorking} / " +
        "热身 ${breakdown.warmup} / 失败 ${breakdown.failed}"
}

internal fun workoutCompletionMessage(sets: List<WorkoutSet>): String {
    return "${workoutSetSummaryLabel(sets)}。正式完成组进入 PR 候选；热身和失败组不计 PR。" +
        "完成后原记录锁定；如需修改，可从训练历史发起纠错并重算 PR。"
}

internal fun customExerciseNameError(name: String, existingNames: Iterable<String>): String? {
    val trimmed = name.trim()
    val codePoints = trimmed.codePointCount(0, trimmed.length)
    return when {
        codePoints == 0 -> "请输入动作名称（1–$EXERCISE_NAME_MAX_CODE_POINTS 个字符）"
        codePoints > EXERCISE_NAME_MAX_CODE_POINTS ->
            "动作名称不能超过 $EXERCISE_NAME_MAX_CODE_POINTS 个字符（当前 $codePoints 个）"
        existingNames.any { normalizeExerciseName(it) == normalizeExerciseName(name) } ->
            "已有同名动作；请直接选择它或换一个名称"
        else -> null
    }
}

internal fun exerciseCategoryError(category: String): String? {
    val trimmed = category.trim()
    val codePoints = trimmed.codePointCount(0, trimmed.length)
    return when {
        codePoints == 0 -> "请输入主要肌群（1–$EXERCISE_CATEGORY_MAX_CODE_POINTS 个字符）"
        codePoints > EXERCISE_CATEGORY_MAX_CODE_POINTS ->
            "主要肌群不能超过 $EXERCISE_CATEGORY_MAX_CODE_POINTS 个字符（当前 $codePoints 个）"
        else -> null
    }
}

internal fun normalizeExerciseName(value: String): String {
    return collapseExerciseNameWhitespace(value).lowercase(Locale.ROOT)
}

internal fun collapseExerciseNameWhitespace(value: String): String {
    val normalized = StringBuilder()
    var index = 0
    var pendingSpace = false
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = normalized.isNotEmpty()
        } else {
            if (pendingSpace) normalized.append(' ')
            normalized.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    return normalized.toString()
}

private fun exerciseMatches(exercise: Exercise, query: String): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isBlank()) return true
    return exercise.name.lowercase().contains(normalized) || exercise.aliases.any { it.lowercase().contains(normalized) }
}

private fun optionalDecimal(value: String): Double? = value.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

private fun countdownLabel(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

internal fun nextSupersetId(sets: List<WorkoutSet>): String {
    val used = sets.mapNotNull { it.supersetId?.uppercase() }.toSet()
    ('A'..'Z').firstOrNull { it.toString() !in used }?.let { return it.toString() }
    var index = 1
    while ("G$index" in used) index += 1
    return "G$index"
}

private fun trackingLabel(type: TrackingType): String = when (type) {
    TrackingType.WEIGHT_REPS -> "重量 + 次数"
    TrackingType.BODYWEIGHT_REPS -> "自重次数"
    TrackingType.ASSISTED_REPS -> "辅助重量 + 次数"
    TrackingType.DURATION -> "计时"
}

private fun setValueLabel(type: TrackingType, set: WorkoutSet, isBarbell: Boolean = false): String = when (type) {
    TrackingType.WEIGHT_REPS -> (if (isBarbell) "总重量 " else "") + "${formatOne(set.weightKg)} kg × ${set.reps}"
    TrackingType.BODYWEIGHT_REPS -> "额外 ${formatOne(set.weightKg)} kg × ${set.reps}"
    TrackingType.ASSISTED_REPS -> "辅助 ${formatOne(set.weightKg)} kg × ${set.reps}"
    TrackingType.DURATION -> durationLabel(set.durationSeconds)
}

private fun prEventValue(event: PersonalRecord): String = when (event.type) {
    com.personal.fitnessledger.data.PrType.MAX_DURATION -> durationLabel(event.durationSeconds)
    com.personal.fitnessledger.data.PrType.MAX_REPS,
    com.personal.fitnessledger.data.PrType.REP_AT_WEIGHT,
    com.personal.fitnessledger.data.PrType.REPS_AT_ASSISTANCE -> "${event.reps} 次"
    com.personal.fitnessledger.data.PrType.MIN_ASSISTANCE_AT_REPS -> "辅助 ${formatOne(event.weightKg)} kg × ${event.reps}"
    com.personal.fitnessledger.data.PrType.ESTIMATED_1RM -> "${formatOne(event.value)} kg"
    com.personal.fitnessledger.data.PrType.SET_VOLUME -> "${formatOne(event.value)} kg·次"
    else -> "${formatOne(event.weightKg)} kg × ${event.reps}"
}

private fun durationLabel(seconds: Int): String = if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"

internal fun workoutDateLabel(recordedLocalDate: LocalDate?): String =
    recordedLocalDate?.toString() ?: "日期未记录"

internal fun workoutCorrectionTimeLabel(correctedAtMillis: Long?): String =
    correctedAtMillis?.takeIf { it >= 0L }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } ?: "时间未记录"

private fun workoutDurationLabel(session: WorkoutSession): String {
    val seconds = (((session.endedAtMillis ?: session.startedAtMillis) - session.startedAtMillis) / 1000L)
        .coerceAtLeast(0L)
        .toInt()
    return durationLabel(seconds)
}
