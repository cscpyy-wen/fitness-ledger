package com.personal.fitnessledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.personal.fitnessledger.ui.XiaomiConnectionViewModel
import com.personal.fitnessledger.ui.XiaomiConnectionCard
import com.personal.fitnessledger.ui.AppViewModel
import com.personal.fitnessledger.ui.BodyScreen
import com.personal.fitnessledger.ui.FoodScreen
import com.personal.fitnessledger.ui.InitialSetupScreen
import com.personal.fitnessledger.ui.MealDraftScreen
import com.personal.fitnessledger.ui.MealCommitReceiptScreen
import com.personal.fitnessledger.ui.SettingsScreen
import com.personal.fitnessledger.ui.MacroPlanScreen
import com.personal.fitnessledger.ui.TodayScreen
import com.personal.fitnessledger.ui.TrainingScreen
import com.personal.fitnessledger.ui.todayBodyQuickActionModel
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import kotlinx.coroutines.delay
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitnessLedgerTheme {
                FitnessApp()
            }
        }
    }
}

private enum class AppTab(val label: String) {
    TODAY("今日"),
    FOOD("饮食"),
    TRAINING("训练"),
    BODY("身体"),
    PLAN("方案"),
    SETTINGS("设置"),
}

@Composable
private fun FitnessApp(viewModel: AppViewModel = viewModel()) {
    val xiaomiViewModel: XiaomiConnectionViewModel = viewModel()
    val xiaomiState by xiaomiViewModel.manager.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val state = viewModel.state
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    var settingsReturnTab by rememberSaveable { mutableStateOf(AppTab.PLAN) }
    var draftPhotoSettings by rememberSaveable { mutableStateOf(false) }
    val tabStateHolder = rememberSaveableStateHolder()
    var pendingBodyMeasurementRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshForCurrentDate()
                viewModel.reloadBodyMeasurements()
                xiaomiViewModel.foreground()
            }
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushWorkoutEditorDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(xiaomiState.revision) { viewModel.reloadBodyMeasurements() }

    DisposableEffect(selectedTab) {
        onDispose {
            if (selectedTab == AppTab.TRAINING) viewModel.flushWorkoutEditorDraft()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Receipt consumption is app-level, not Body-tab-level. Matching IDs keep a
    // restored old receipt from swallowing a later quick-action request.
    LaunchedEffect(state.resolvedBodyMeasurementShortcutRequestId) {
        state.resolvedBodyMeasurementShortcutRequestId?.let { resolvedId ->
            if (pendingBodyMeasurementRequestId == resolvedId) {
                pendingBodyMeasurementRequestId = null
            }
            viewModel.acknowledgeNewBodyMeasurementRequest(resolvedId)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.refreshForCurrentDate()
        }
    }

    if (!state.isInitialized) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.restoreRecoveryRequired) {
        RestoreRecoveryRequiredScreen(onRetry = viewModel::retryInterruptedRestoreRecovery)
        return
    }

    if (state.isExportingBackup || state.isImportingBackup) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(if (state.isImportingBackup) "正在验证并恢复本机账本…" else "正在生成加密备份…")
                Text("请保持 App 在前台", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    if (!state.profileConfigured) {
        Box(Modifier.fillMaxSize()) {
            InitialSetupScreen(
                profile = state.profile,
                isSaving = state.isSavingProfile,
                onSaveProfile = viewModel::updateProfile,
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
            )
        }
        return
    }

    if (state.mealDraft != null) {
        if (draftPhotoSettings) {
            BackHandler { draftPhotoSettings = false }
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Box(Modifier.padding(padding)) {
                    SettingsScreen(
                        state = state,
                        onSaveProfile = viewModel::updateProfile,
                        onSaveAnalysisConfig = viewModel::saveAnalysisConfig,
                        onSaveVisionConfig = viewModel::saveVisionConfig,
                        onSaveAnalysisProfile = viewModel::saveAnalysisProfile,
                        onSelectAnalysisProfile = viewModel::selectAnalysisProfile,
                        onDeleteAnalysisProfile = viewModel::deleteAnalysisProfile,
                        onClearAnalysisToken = viewModel::clearAnalysisToken,
                        onClearActiveAnalysisConnection = viewModel::clearActiveAnalysisConnection,
                        onSaveAnalysisAdditionalPrompt = viewModel::saveAnalysisAdditionalPrompt,
                        onExportBackup = viewModel::exportEncryptedBackup,
                        onImportBackup = viewModel::importEncryptedBackup,
                        onBack = { draftPhotoSettings = false },
                    )
                }
            }
            return
        }
        MealDraftScreen(
            state = state,
            onUpdateItem = viewModel::updateDraftItem,
            onRemoveItem = viewModel::removeDraftItem,
            onAddItem = viewModel::addDraftItem,
            onResolveHypothesis = viewModel::resolveFoodHypothesis,
            onReviewedChange = viewModel::setDraftReviewed,
            onConfirm = viewModel::confirmDraft,
            onDiscard = viewModel::discardDraft,
            onRetryAnalysis = viewModel::retryDraftAnalysis,
            onChangeTargetDate = viewModel::changeDraftTargetDate,
            onOpenPhotoSettings = { draftPhotoSettings = true },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        )
        return
    }

    state.mealCommitReceipt?.let { receipt ->
        LaunchedEffect(receipt.id) {
            // A confirmed meal always returns to Food. The opaque receipt owns
            // the whole touch surface until the user deliberately continues.
            selectedTab = AppTab.FOOD
        }
        MealCommitReceiptScreen(
            receipt = receipt,
            onContinue = { viewModel.acknowledgeMealCommitReceipt(receipt.id) },
        )
        return
    }

    BackHandler(enabled = selectedTab == AppTab.SETTINGS) { selectedTab = settingsReturnTab }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.filter { it != AppTab.SETTINGS }.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab || (selectedTab == AppTab.SETTINGS && tab == AppTab.PLAN),
                        onClick = {
                            selectedTab = tab
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.TODAY -> Icons.Default.Home
                                    AppTab.FOOD -> Icons.Default.Restaurant
                                    AppTab.TRAINING -> Icons.Default.FitnessCenter
                                    AppTab.BODY -> Icons.Default.MonitorWeight
                                    AppTab.PLAN -> Icons.Default.Tune
                                    AppTab.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val tabContent: @Composable () -> Unit = {
            when (selectedTab) {
                AppTab.TODAY -> TodayScreen(
                    state = state,
                    onOpenFood = { selectedTab = AppTab.FOOD },
                    onOpenTraining = { selectedTab = AppTab.TRAINING },
                    onOpenPlan = { selectedTab = AppTab.PLAN },
                    onOpenBody = {
                        pendingBodyMeasurementRequestId = if (todayBodyQuickActionModel(
                            hasBodyMeasurements = state.measurements.isNotEmpty(),
                        ).opensNewMeasurement) UUID.randomUUID().toString() else null
                        selectedTab = AppTab.BODY
                    },
                )
                AppTab.FOOD -> FoodScreen(
                    state = state,
                    onAnalyzePhoto = viewModel::analyzePhoto,
                    onOpenManualEntry = viewModel::openManualFoodForm,
                    onUpdateManualEntry = viewModel::updateManualFoodForm,
                    onKeepManualEntry = viewModel::keepManualFoodFormAndClose,
                    onDiscardManualEntry = viewModel::discardManualFoodForm,
                    onCreateManualEntry = viewModel::createManualDraft,
                    onSelectDate = viewModel::selectFoodDate,
                    onEditMeal = viewModel::editMeal,
                    onCopyMeal = viewModel::copyMeal,
                    onDeleteMeal = viewModel::deleteMeal,
                    onToggleFoodFavorite = viewModel::toggleFoodFavorite,
                    onRequestCameraCapture = viewModel::requestCameraCapture,
                    onCameraLaunchHandled = viewModel::onCameraLaunchHandled,
                    onCameraCaptureResult = viewModel::onCameraCaptureResult,
                    onOpenPhotoSettings = { settingsReturnTab = AppTab.FOOD; selectedTab = AppTab.SETTINGS },
                    onSelectAnalysisProfile = { id -> viewModel.selectAnalysisProfile(id) {} },
                )
                AppTab.TRAINING -> TrainingScreen(
                    state = state,
                    onStartWorkout = viewModel::startWorkout,
                    onAddSet = viewModel::addSet,
                    onAcknowledgeSingleSet = viewModel::acknowledgeSingleSetCommit,
                    onAcknowledgeFiveByFive = viewModel::acknowledgeFiveByFiveCommit,
                    onStartRestTimer = viewModel::startRestTimer,
                    onClearRestTimer = viewModel::clearRestTimer,
                    onAddFiveByFive = viewModel::addFiveByFive,
                    onUpdateSet = viewModel::updateSet,
                    onDeleteSet = viewModel::deleteSet,
                    onDeleteSetBatch = viewModel::deleteSetBatch,
                    onCancelWorkout = viewModel::cancelWorkout,
                    onCompleteWorkout = viewModel::completeWorkout,
                    onAddCustomExercise = viewModel::addCustomExercise,
                    onTogglePrimary = viewModel::togglePrimary,
                    onUpdateCustomExercise = viewModel::updateCustomExercise,
                    onSetCustomExerciseArchived = viewModel::setCustomExerciseArchived,
                    onOpenWorkoutHistory = viewModel::openWorkoutHistory,
                    onLoadMoreWorkoutHistory = viewModel::loadMoreWorkoutHistory,
                    onCloseWorkoutHistory = viewModel::closeWorkoutHistory,
                    onStartWorkoutCorrection = viewModel::startWorkoutCorrection,
                    onDeleteWorkoutHistory = viewModel::deleteWorkoutHistorySession,
                    onCopyPreviousWorkout = viewModel::copyPreviousWorkout,
                    onStartFromTemplate = viewModel::startWorkoutFromTemplate,
                    onSaveHistoryAsTemplate = viewModel::saveWorkoutHistoryAsTemplate,
                    onSaveActiveAsTemplate = viewModel::saveActiveWorkoutAsTemplate,
                    onUpdateTemplate = viewModel::updateWorkoutTemplate,
                    onDeleteTemplate = viewModel::deleteWorkoutTemplate,
                    onUpdateWorkoutEditor = viewModel::updateWorkoutEditorDraft,
                    onLoadPlanItem = viewModel::loadWorkoutPlanItem,
                    onUpdateActivePlanItem = viewModel::updateActiveWorkoutPlanItem,
                    onRemoveActivePlanItem = viewModel::removeActiveWorkoutPlanItem,
                    onUpdateBarbellBarWeight = viewModel::updateBarbellBarWeight,
                )
                AppTab.BODY -> BodyScreen(
                    onHideXiaomiMeasurement = viewModel::hideXiaomiMeasurement,
                    connectionCard = {
                        XiaomiConnectionCard(xiaomiState,xiaomiViewModel.connecting,xiaomiViewModel.awaitingLogin,xiaomiViewModel.loginError,
                            onConnect=xiaomiViewModel::connect,onLogin={xiaomiViewModel.openLogin(context)},onCancelLogin=xiaomiViewModel::cancelLogin,
                            onSync=xiaomiViewModel::sync,onBackground=xiaomiViewModel::setBackground,onDisconnect=xiaomiViewModel::disconnect)
                    },
                    state = state,
                    onOpenMeasurement = viewModel::openBodyMeasurementForm,
                    onUpdateMeasurement = viewModel::updateBodyMeasurementForm,
                    onSaveMeasurement = viewModel::saveBodyMeasurementForm,
                    onDiscardMeasurement = viewModel::discardBodyMeasurementForm,
                    onDeleteMeasurement = viewModel::deleteMeasurement,
                    newMeasurementRequestId = pendingBodyMeasurementRequestId,
                )
                AppTab.PLAN -> MacroPlanScreen(
                    state = state,
                    onSaveProfile = viewModel::updateProfile,
                    onOpenSettings = { settingsReturnTab = AppTab.PLAN; selectedTab = AppTab.SETTINGS },
                    onOpenBody = { selectedTab = AppTab.BODY },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    onSaveProfile = viewModel::updateProfile,
                    onSaveAnalysisConfig = viewModel::saveAnalysisConfig,
                    onSaveVisionConfig = viewModel::saveVisionConfig,
                    onSaveAnalysisProfile = viewModel::saveAnalysisProfile,
                    onSelectAnalysisProfile = viewModel::selectAnalysisProfile,
                    onDeleteAnalysisProfile = viewModel::deleteAnalysisProfile,
                    onClearAnalysisToken = viewModel::clearAnalysisToken,
                    onClearActiveAnalysisConnection = viewModel::clearActiveAnalysisConnection,
                    onSaveAnalysisAdditionalPrompt = viewModel::saveAnalysisAdditionalPrompt,
                    onExportBackup = viewModel::exportEncryptedBackup,
                    onImportBackup = viewModel::importEncryptedBackup,
                    onBack = { selectedTab = settingsReturnTab },
                )
            }
            }
            if (selectedTab == AppTab.PLAN) {
                tabStateHolder.SaveableStateProvider("PLAN-${state.planEditorEpoch}") { tabContent() }
            } else {
                tabContent()
            }
        }
    }
}

@Composable
internal fun RestoreRecoveryRequiredScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("restore-recovery-required"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("恢复尚未完成", style = MaterialTheme.typography.headlineSmall)
            Text("为避免旧快照覆盖新记录，账本目前保持只读。请先完成安全恢复。")
            Button(onClick = onRetry, modifier = Modifier.testTag("retry-restore-recovery")) {
                Text("重试安全恢复")
            }
            Text(
                "若重试仍失败，请完全关闭并重新打开 App；不要卸载 App 或清除数据。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
