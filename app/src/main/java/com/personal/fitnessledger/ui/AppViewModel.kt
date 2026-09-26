package com.personal.fitnessledger.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.fitnessledger.data.BodyMeasurement
import com.personal.fitnessledger.data.BodyMeasurementFormDraft
import com.personal.fitnessledger.data.AnalysisMode
import com.personal.fitnessledger.data.AnalysisTransport
import com.personal.fitnessledger.data.AnalysisServiceConfig
import com.personal.fitnessledger.data.AnalysisServiceProfile
import com.personal.fitnessledger.data.normalizeVisionEndpoint
import com.personal.fitnessledger.data.ActiveWorkoutPlan
import com.personal.fitnessledger.data.DraftState
import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.FitnessRepository
import com.personal.fitnessledger.data.FoodDraftItem
import com.personal.fitnessledger.data.FoodHypothesis
import com.personal.fitnessledger.data.LedgerBackupAuthenticationException
import com.personal.fitnessledger.data.LedgerBackupCompatibilityException
import com.personal.fitnessledger.data.LedgerBackupIntegrityException
import com.personal.fitnessledger.data.LedgerRestoreRecoveryRequiredException
import com.personal.fitnessledger.data.MealDraft
import com.personal.fitnessledger.data.MealRecord
import com.personal.fitnessledger.data.ManualFoodFormDraft
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PersonalRecord
import com.personal.fitnessledger.data.PhotoStorage
import com.personal.fitnessledger.data.PrSummary
import com.personal.fitnessledger.data.RiskFlag
import com.personal.fitnessledger.data.SavedFood
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.UserProfile
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutSet
import com.personal.fitnessledger.data.WorkoutSetInput
import com.personal.fitnessledger.data.WORKOUT_BATCH_ID_MAX_LENGTH
import com.personal.fitnessledger.data.WorkoutHistoryDetail
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutEditorDraft
import com.personal.fitnessledger.data.WorkoutPlanItem
import com.personal.fitnessledger.data.WorkoutPlannerSnapshot
import com.personal.fitnessledger.data.WorkoutTemplate
import com.personal.fitnessledger.data.afterCommittedSet
import com.personal.fitnessledger.data.freshCopy
import com.personal.fitnessledger.data.newWorkoutEditorDraft
import com.personal.fitnessledger.data.analysisEndpointValidationError
import com.personal.fitnessledger.data.commitValidationError
import com.personal.fitnessledger.data.strongestEvidence
import com.personal.fitnessledger.data.persistenceValidationError
import com.personal.fitnessledger.data.toFoodDraftItem
import com.personal.fitnessledger.data.toPlanItem
import com.personal.fitnessledger.data.toPlanItems
import com.personal.fitnessledger.data.toEditorDraft
import com.personal.fitnessledger.data.validationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToLong

private const val HISTORY_PAGE_SIZE = 50
private const val WORKOUT_EDITOR_DEBOUNCE_MILLIS = 300L
private const val RESTORE_RECOVERY_REQUIRED_MESSAGE =
    "恢复事务尚未安全收敛，账本已锁定且不会接受新记录；请完全关闭并重新打开 App 以继续自动恢复"

typealias ManualFoodFormTransform = (ManualFoodFormDraft) -> ManualFoodFormDraft
typealias WorkoutEditorTransform = (WorkoutEditorDraft) -> WorkoutEditorDraft

/**
 * Applies an editor event to the latest form, rather than accepting a full form
 * snapshot captured by an earlier composition. Identity, original portion data,
 * and journal metadata remain owned by the ViewModel.
 */
internal fun mergeManualFoodFormUpdate(
    current: ManualFoodFormDraft,
    expectedFormId: String,
    transform: ManualFoodFormTransform,
    updatedAtMillis: Long = System.currentTimeMillis(),
): ManualFoodFormDraft? {
    if (current.id != expectedFormId) return null
    val candidate = transform(current)
    return candidate.copy(
        id = current.id,
        itemId = current.itemId,
        targetDate = current.targetDate,
        initialGrams = current.initialGrams,
        initialGramsMin = current.initialGramsMin,
        initialGramsMax = current.initialGramsMax,
        initialPortionBasis = current.initialPortionBasis,
        isDirty = true,
        revision = current.revision + 1L,
        updatedAtMillis = updatedAtMillis.coerceAtLeast(current.updatedAtMillis + 1L),
    )
}

typealias BodyMeasurementFormTransform =
    (BodyMeasurementFormDraft) -> BodyMeasurementFormDraft

internal fun mergeBodyMeasurementFormUpdate(
    current: BodyMeasurementFormDraft,
    expectedFormId: String,
    transform: BodyMeasurementFormTransform,
    updatedAtMillis: Long = System.currentTimeMillis(),
): BodyMeasurementFormDraft? {
    if (current.id != expectedFormId) return null
    val candidate = transform(current)
    return candidate.copy(
        id = current.id,
        measurementId = current.measurementId,
        shortcutRequestId = current.shortcutRequestId,
        revision = current.revision + 1L,
        updatedAtMillis = updatedAtMillis.coerceAtLeast(current.updatedAtMillis + 1L),
    )
}

internal fun completedWorkoutsForLoadedDate(
    loadedDate: LocalDate,
    recordedDate: LocalDate,
    current: List<WorkoutHistorySummary>,
    completedForRecordedDate: List<WorkoutHistorySummary>,
): List<WorkoutHistorySummary> =
    if (loadedDate == recordedDate) completedForRecordedDate else current

internal fun mergeWorkoutEditorUpdate(
    current: WorkoutEditorDraft,
    expectedSessionId: Long,
    transform: WorkoutEditorTransform,
    updatedAtMillis: Long = System.currentTimeMillis(),
): WorkoutEditorDraft? {
    if (current.sessionId != expectedSessionId) return null
    val candidate = transform(current)
    return candidate.copy(
        sessionId = current.sessionId,
        revision = current.revision + 1L,
        updatedAtMillis = updatedAtMillis.coerceAtLeast(current.updatedAtMillis + 1L),
    )
}

@Immutable
data class CameraLaunchRequest(
    val id: Long,
    val uri: Uri,
)

@Immutable
data class MealCommitReceipt(
    val id: String,
    val message: String,
)

@Immutable
data class AppUiState(
    val isInitialized: Boolean = false,
    val loadedDate: LocalDate = LocalDate.now(),
    val profile: UserProfile = UserProfile(),
    val profileConfigured: Boolean = false,
    /** Invalidates off-screen unsaved plan inputs after a whole-ledger replacement. */
    val planEditorEpoch: String = "initial",
    val todayNutrition: Nutrition = Nutrition(),
    val todayMeals: List<MealRecord> = emptyList(),
    val selectedFoodDate: LocalDate = LocalDate.now(),
    val selectedDateNutrition: Nutrition = Nutrition(),
    val selectedDateMeals: List<MealRecord> = emptyList(),
    val savedFoods: List<SavedFood> = emptyList(),
    val isLoadingFoodDate: Boolean = false,
    val measurements: List<BodyMeasurement> = emptyList(),
    val isSavingProfile: Boolean = false,
    val isSavingMeasurement: Boolean = false,
    val bodyMeasurementFormDraft: BodyMeasurementFormDraft? = null,
    val isBodyMeasurementFormVisible: Boolean = false,
    val isAutoSavingBodyMeasurementFormDraft: Boolean = false,
    /** False while the currently displayed raw revision is not confirmed durable. */
    val isBodyMeasurementFormDraftDurable: Boolean = false,
    val bodyMeasurementFormSaveError: String? = null,
    /** Stable receipt ID set only after its matching shortcut form is resolved. */
    val resolvedBodyMeasurementShortcutRequestId: String? = null,
    /** Active-only choices for new workout records. */
    val exercises: List<Exercise> = emptyList(),
    /** Active and archived definitions used by management, history, PR and template labels. */
    val exerciseCatalog: List<Exercise> = emptyList(),
    val activeWorkout: WorkoutSession? = null,
    val activeSets: List<WorkoutSet> = emptyList(),
    val workoutEditorDraft: WorkoutEditorDraft? = null,
    val activeWorkoutPlan: ActiveWorkoutPlan? = null,
    val workoutTemplates: List<WorkoutTemplate> = emptyList(),
    val barbellBarWeightKg: Double = 20.0,
    val isAutoSavingWorkoutEditorDraft: Boolean = false,
    val workoutEditorDraftSaveError: String? = null,
    val mealDraft: MealDraft? = null,
    val manualFoodFormDraft: ManualFoodFormDraft? = null,
    val isManualFoodFormVisible: Boolean = false,
    /** Background keystroke journal writes never disable the editor. */
    val isAutoSavingManualFoodFormDraft: Boolean = false,
    val manualFoodFormSaveError: String? = null,
    /** Reserved for close, discard and promotion operations that must lock UI. */
    val isSavingManualFoodFormDraft: Boolean = false,
    val isAnalyzingPhoto: Boolean = false,
    val pendingCameraUri: Uri? = null,
    val pendingCameraPhase: PhotoStorage.PendingCameraPhase? = null,
    val pendingCameraTargetDate: LocalDate? = null,
    val cameraLaunchRequest: CameraLaunchRequest? = null,
    val isPreparingCameraCapture: Boolean = false,
    val isHandlingCameraResult: Boolean = false,
    val isSavingMealDraft: Boolean = false,
    val isCommittingMeal: Boolean = false,
    /** Full-screen, touch-consuming receipt shown after a durable meal commit.
     * It prevents queued taps from falling through to bottom navigation. */
    val mealCommitReceipt: MealCommitReceipt? = null,
    val isSavingWorkout: Boolean = false,
    /** Increments only after a single-set write is durably committed/idempotently recovered. */
    val workoutSetSaveRevision: Long = 0L,
    /** A committed single-set journal remains until the UI has rejected queued
     * taps and the user can clearly distinguish the next set as a new action. */
    val unacknowledgedWorkoutSetCommitId: String? = null,
    /** A committed 5x5 journal is explicitly acknowledged before another workout
     * write is accepted. This keeps stale confirmation callbacks idempotent. */
    val unacknowledgedFiveByFiveCommitId: String? = null,
    val analysisEndpoint: String = "",
    val analysisTransport: AnalysisTransport = AnalysisTransport.MEAL_PROXY,
    val analysisModel: String = "",
    val analysisAdditionalPrompt: String = "",
    val analysisTokenConfigured: Boolean = false,
    val analysisProfiles: List<AnalysisServiceProfile> = emptyList(),
    val activeAnalysisProfileId: String? = null,
    val analysisProfilesUnreadable: Boolean = false,
    val isSavingAnalysisConfig: Boolean = false,
    val isExportingBackup: Boolean = false,
    val isImportingBackup: Boolean = false,
    /** A previous restore could not finish or roll back. No ledger mutation is
     * accepted until the durable coordinator succeeds. */
    val restoreRecoveryRequired: Boolean = false,
    val lastPrEvents: List<PersonalRecord> = emptyList(),
    val todayCompletedWorkouts: List<WorkoutHistorySummary> = emptyList(),
    val workoutHistory: List<WorkoutHistorySummary> = emptyList(),
    val hasMoreWorkoutHistory: Boolean = false,
    val isLoadingMoreWorkoutHistory: Boolean = false,
    /** Compose reads this cache only; it never calls SQLite during recomposition. */
    val prSummaries: Map<Long, PrSummary> = emptyMap(),
    val selectedWorkoutHistory: WorkoutHistoryDetail? = null,
    /** Original immutable receipt while [activeWorkout] is an isolated correction draft. */
    val workoutCorrectionOriginal: WorkoutHistoryDetail? = null,
    val isLoadingWorkoutHistory: Boolean = false,
    val message: String? = null,
)

internal fun backupWriteInProgress(state: AppUiState): Boolean =
    state.restoreRecoveryRequired || state.isSavingProfile || state.isSavingMeasurement ||
        state.isAutoSavingBodyMeasurementFormDraft ||
        state.isSavingManualFoodFormDraft || state.isAutoSavingManualFoodFormDraft ||
        state.isAnalyzingPhoto || state.isPreparingCameraCapture || state.isHandlingCameraResult ||
        state.isSavingMealDraft || state.isCommittingMeal ||
        state.isSavingWorkout || state.isAutoSavingWorkoutEditorDraft ||
        state.isSavingAnalysisConfig || state.isExportingBackup || state.isImportingBackup

internal fun backupRestoreBlockReason(state: AppUiState): String? = when {
    state.restoreRecoveryRequired -> "请先完成未收敛的账本恢复"
    state.isExportingBackup || state.isImportingBackup -> "已有备份任务正在进行"
    backupWriteInProgress(state) -> "请等待当前保存或识别任务完成"
    state.mealDraft != null || state.manualFoodFormDraft != null -> "请先确认或放弃当前饮食草稿"
    state.bodyMeasurementFormDraft != null || state.isBodyMeasurementFormVisible -> "请先保存或放弃身体记录草稿"
    state.activeWorkout != null -> "请先完成或取消当前训练"
    state.pendingCameraUri != null || state.cameraLaunchRequest != null -> "请先完成或取消当前拍照记录"
    state.unacknowledgedWorkoutSetCommitId != null || state.unacknowledgedFiveByFiveCommitId != null ->
        "请先确认刚刚保存的训练组"
    else -> null
}

internal fun refreshFailureState(
    current: AppUiState,
    recoveryGateWasActive: Boolean,
    error: Throwable,
): AppUiState = if (error is LedgerRestoreRecoveryRequiredException ||
    recoveryGateWasActive || current.restoreRecoveryRequired
) {
    AppUiState(
        isInitialized = true,
        planEditorEpoch = UUID.randomUUID().toString(),
        restoreRecoveryRequired = true,
        message = null,
    )
} else {
    current.copy(isInitialized = true, message = "无法读取本地账本；请重新打开 App 后重试")
}

private fun formatBackupSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}

private fun backupImportFailureMessage(error: Throwable): String = when (error) {
    is LedgerRestoreRecoveryRequiredException -> RESTORE_RECOVERY_REQUIRED_MESSAGE
    is LedgerBackupAuthenticationException -> "恢复失败：口令错误，或备份文件已被篡改；现有账本未改变"
    is LedgerBackupCompatibilityException -> "恢复失败：${error.message}；现有账本未改变"
    is LedgerBackupIntegrityException -> "恢复失败：备份内容不完整或已损坏；现有账本未改变"
    is SecurityException -> "恢复失败：系统未授权读取该文件；现有账本未改变"
    is IllegalArgumentException -> "恢复失败：${error.message ?: "备份或口令无效"}；现有账本未改变"
    else -> "恢复失败，现有账本已回滚且未改变；请检查文件后重试"
}

internal fun AppUiState.withVisibleWorkoutCorrection(
    correction: WorkoutSession,
    sets: List<WorkoutSet>,
    planner: WorkoutPlannerSnapshot?,
    original: WorkoutHistoryDetail,
): AppUiState = copy(
    activeWorkout = correction,
    activeSets = sets,
    workoutEditorDraft = planner?.editorDraft,
    activeWorkoutPlan = null,
    workoutTemplates = planner?.templates ?: workoutTemplates,
    barbellBarWeightKg = planner?.barbellBarWeightKg ?: barbellBarWeightKg,
    workoutCorrectionOriginal = original,
    selectedWorkoutHistory = null,
    isSavingWorkout = false,
    isLoadingWorkoutHistory = false,
    isLoadingMoreWorkoutHistory = false,
    isAutoSavingWorkoutEditorDraft = false,
    workoutEditorDraftSaveError = null,
    message = if (planner == null) {
        "更正草稿已安全创建；编辑器初始化失败，正在从本机草稿重新对账"
    } else {
        "已创建独立更正草稿；原完成记录和 PR 在保存前保持不变"
    },
)

internal fun AppUiState.afterDurableWorkoutCorrection(sessionId: Long, revision: Int): AppUiState = copy(
    activeWorkout = null,
    activeSets = emptyList(),
    workoutEditorDraft = null,
    activeWorkoutPlan = null,
    workoutCorrectionOriginal = null,
    selectedWorkoutHistory = null,
    todayCompletedWorkouts = todayCompletedWorkouts.filterNot { it.session.id == sessionId },
    workoutHistory = workoutHistory.filterNot { it.session.id == sessionId },
    hasMoreWorkoutHistory = false,
    lastPrEvents = emptyList(),
    prSummaries = emptyMap(),
    isLoadingWorkoutHistory = false,
    isLoadingMoreWorkoutHistory = false,
    isAutoSavingWorkoutEditorDraft = false,
    workoutEditorDraftSaveError = null,
    message = "更正已保存（第 $revision 次）；正在刷新历史和 PR",
)

internal fun AppUiState.afterDurableWorkoutHistoryDelete(sessionId: Long): AppUiState = copy(
    selectedWorkoutHistory = null,
    workoutCorrectionOriginal = null,
    todayCompletedWorkouts = todayCompletedWorkouts.filterNot { it.session.id == sessionId },
    workoutHistory = workoutHistory.filterNot { it.session.id == sessionId },
    hasMoreWorkoutHistory = false,
    lastPrEvents = emptyList(),
    prSummaries = emptyMap(),
    isLoadingWorkoutHistory = false,
    isLoadingMoreWorkoutHistory = false,
    message = "整场训练已删除；正在刷新首页、历史和 PR",
)

internal data class WorkoutEditorFailureRecovery(
    val state: AppUiState,
    val draftToPersist: WorkoutEditorDraft?,
)

/** Shared by set submission, plan mutation and terminal-action failures. The
 * pending debounce was cancelled before those operations, so a still-active
 * editor must be queued again instead of leaving a permanent "saving" label. */
internal fun AppUiState.prepareWorkoutEditorFailureRecovery(): WorkoutEditorFailureRecovery {
    val draft = workoutEditorDraft?.takeIf { it.sessionId == activeWorkout?.id }
    return WorkoutEditorFailureRecovery(
        state = copy(
            isSavingWorkout = false,
            isAutoSavingWorkoutEditorDraft = draft != null,
            workoutEditorDraftSaveError = null,
        ),
        draftToPersist = draft,
    )
}

private data class FoodLedgerSnapshot(
    val today: LocalDate,
    val todayNutrition: Nutrition,
    val todayMeals: List<MealRecord>,
    val todayCompletedWorkouts: List<WorkoutHistorySummary>,
    val selectedDate: LocalDate,
    val selectedNutrition: Nutrition,
    val selectedMeals: List<MealRecord>,
    val savedFoods: List<SavedFood>,
)

private data class MealDraftCreation(
    val draft: MealDraft,
    val targetNutrition: Nutrition,
    val targetMeals: List<MealRecord>,
)

private data class StartedWorkoutPlanning(
    val session: WorkoutSession,
    val planner: WorkoutPlannerSnapshot,
)

private data class WorkoutLedgerRefresh(
    val todayCompletedWorkouts: List<WorkoutHistorySummary>,
    val workoutHistory: List<WorkoutHistorySummary>,
    val hasMoreWorkoutHistory: Boolean,
    val lastPrEvents: List<PersonalRecord>,
    val prSummaries: Map<Long, PrSummary>,
    val selectedDetail: WorkoutHistoryDetail? = null,
)

private sealed interface BodyMeasurementSaveAttempt {
    data class Success(
        val measurements: List<BodyMeasurement>,
        val cleanupSucceeded: Boolean,
    ) : BodyMeasurementSaveAttempt

    data class Failure(
        val error: Throwable,
        val rawDraftDurable: Boolean,
    ) : BodyMeasurementSaveAttempt
}

private data class AppBootstrapSnapshot(
    val today: LocalDate,
    val profile: UserProfile,
    val profileConfigured: Boolean,
    val todayNutrition: Nutrition,
    val todayMeals: List<MealRecord>,
    val selectedDate: LocalDate,
    val selectedNutrition: Nutrition,
    val selectedMeals: List<MealRecord>,
    val savedFoods: List<SavedFood>,
    val measurements: List<BodyMeasurement>,
    val bodyMeasurementFormDraft: BodyMeasurementFormDraft?,
    val resolvedBodyMeasurementShortcutRequestId: String?,
    val exercises: List<Exercise>,
    val exerciseCatalog: List<Exercise>,
    val activeWorkout: WorkoutSession?,
    val activeSets: List<WorkoutSet>,
    val workoutCorrectionOriginal: WorkoutHistoryDetail?,
    val workoutPlanner: WorkoutPlannerSnapshot,
    val workoutDraftWasRecovered: Boolean,
    val draft: MealDraft?,
    val manualFoodFormDraft: ManualFoodFormDraft?,
    val pendingCameraCapture: PhotoStorage.PendingCameraCapture?,
    val analysisEndpoint: String,
    val analysisTransport: AnalysisTransport,
    val analysisModel: String,
    val analysisAdditionalPrompt: String,
    val analysisTokenConfigured: Boolean,
    val analysisProfiles: List<AnalysisServiceProfile>,
    val activeAnalysisProfileId: String?,
    val analysisProfilesUnreadable: Boolean,
    val lastPrEvents: List<PersonalRecord>,
    val todayCompletedWorkouts: List<WorkoutHistorySummary>,
    val workoutHistory: List<WorkoutHistorySummary>,
    val hasMoreWorkoutHistory: Boolean,
    val prSummaries: Map<Long, PrSummary>,
    val unacknowledgedWorkoutSetCommitId: String?,
    val unacknowledgedFiveByFiveCommitId: String?,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    // The first access happens inside refreshAll's Dispatchers.IO block. Keeping
    // repository construction lazy prevents database/preferences setup on the
    // Activity/ViewModel construction path.
    private val repository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FitnessRepository(application) }
    private var photoOperationId = 0L
    private var cameraLaunchRequestId = 0L
    private var fullRefreshId = 0L
    private var currentDateRefreshId = 0L
    @Volatile private var workoutLedgerGeneration = 0L
    private val manualFoodPersistenceMutex = Mutex()
    private val bodyMeasurementPersistenceMutex = Mutex()
    private val workoutEditorPersistenceMutex = Mutex()
    private val barbellBarWeightPersistenceMutex = Mutex()
    private var workoutEditorPersistenceJob: Job? = null
    @Volatile private var manualFoodFormGeneration = 0L
    @Volatile private var latestManualFoodRevision = 0L
    @Volatile private var bodyMeasurementFormGeneration = 0L
    @Volatile private var latestBodyMeasurementRevision = 0L
    @Volatile private var workoutEditorGeneration = 0L
    @Volatile private var barbellBarWeightGeneration = 0L
    private var lastDurableBarbellBarWeightKg = 20.0
    private var lastDurableBarbellGeneration = 0L
    @Volatile private var latestWorkoutEditorRevision = 0L
    private val shownWorkoutRecoveryNotices = linkedSetOf<Long>()
    private val shownWorkoutCorrectionRecoveryNotices = linkedSetOf<Long>()
    /** Process-local tombstones for confirmation dialogs already acknowledged.
     * They stop a very late callback from reopening the durable journal after the
     * receipt itself has been tapped. Process recreation cannot retain stale UI
     * callbacks, so persistence is unnecessary here. */
    private val acknowledgedFiveByFiveBatchIds = linkedSetOf<String>()

    var state by mutableStateOf(AppUiState())
        private set

    init {
        refreshAll()
    }

    fun refreshAll() {
        val refreshId = ++fullRefreshId
        val ledgerGeneration = ++workoutLedgerGeneration
        // Once recovery has entered fail-closed mode, keep that gate asserted
        // until one complete post-recovery snapshot has loaded. Merely removing
        // the journal is not enough: exposing an empty/default state after a
        // transient SQLite read failure could let new writes target an unknown
        // generation.
        val recoveryGateWasActive = state.restoreRecoveryRequired
        val requestedSelectedDate = state.selectedFoodDate
        val requestedBarbellGeneration = barbellBarWeightGeneration
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Repository construction performs the persisted restore
                    // coordinator. Both it and this file-state check must remain
                    // off the main thread.
                    if (repository.restoreRecoveryRequired()) {
                        throw LedgerRestoreRecoveryRequiredException()
                    }
                    val pendingFiveByFive = repository.pendingFiveByFiveCommit()
                    var recoveredFiveByFiveCommitId: String? = null
                    if (pendingFiveByFive != null) {
                        val activeBeforeRecovery = repository.activeWorkout()
                        when {
                            repository.isFiveByFiveCommitVisible(pendingFiveByFive) -> {
                                recoveredFiveByFiveCommitId = pendingFiveByFive.batchId
                            }
                            activeBeforeRecovery?.id == pendingFiveByFive.sessionId -> {
                                repository.commitPendingFiveByFive()
                                recoveredFiveByFiveCommitId = pendingFiveByFive.batchId
                            }
                            else -> repository.discardUncommittedFiveByFive(pendingFiveByFive)
                        }
                    }
                    val pendingSingleSet = repository.pendingSingleSetCommit()
                    var recoveredSingleSetCommitId: String? = null
                    if (pendingSingleSet != null) {
                        val activeBeforeRecovery = repository.activeWorkout()
                        when {
                            repository.isSingleSetCommitVisible(pendingSingleSet) -> {
                                recoveredSingleSetCommitId = pendingSingleSet.input.commitId
                            }
                            activeBeforeRecovery?.id == pendingSingleSet.sessionId -> {
                                repository.commitPendingSingleSet()
                                recoveredSingleSetCommitId = pendingSingleSet.input.commitId
                            }
                            else -> repository.discardUncommittedSingleSet(pendingSingleSet)
                        }
                    }
                    val today = LocalDate.now()
                    val activeWorkout = repository.activeWorkout()
                    val activeSets = activeWorkout?.let { repository.setsForSession(it.id) }.orEmpty()
                    val workoutCorrectionOriginal = activeWorkout?.correctionOfSessionId?.let {
                        repository.workoutHistoryDetail(it)
                    }
                    var workoutPlanner = repository.workoutPlannerSnapshot(
                        activeSessionId = activeWorkout?.id,
                        committedSetIds = activeSets.map { it.commitId }.toSet(),
                    )
                    val workoutDraftWasRecovered = workoutPlanner.editorDraft != null
                    if (recoveredFiveByFiveCommitId != null && activeWorkout?.id != pendingFiveByFive?.sessionId) {
                        repository.acknowledgeFiveByFiveCommit(recoveredFiveByFiveCommitId)
                        recoveredFiveByFiveCommitId = null
                    }
                    if (recoveredSingleSetCommitId != null && activeWorkout?.id != pendingSingleSet?.sessionId) {
                        repository.acknowledgeSingleSetCommit(recoveredSingleSetCommitId)
                        recoveredSingleSetCommitId = null
                    }
                    val analysisProfiles = repository.analysisProfiles()
                    val analysisConfig = repository.analysisConfig()
                    val draft = repository.latestDraft()
                    val manualFoodFormDraft = if (draft == null) {
                        repository.manualFoodFormDraft()
                    } else {
                        // A crash after the parsed MealDraft became durable but before
                        // raw-form cleanup may leave both representations. The parsed
                        // draft is authoritative and prevents a duplicate manual meal.
                        repository.manualFoodFormDraft()?.let { raw ->
                            runCatching { repository.clearManualFoodFormDraft(raw.id) }
                        }
                        null
                    }
                    val pendingCameraCapture = repository.reconcilePendingCameraCapture(draft != null)
                    val pendingTargetDate = pendingCameraCapture?.targetDateEpochDay?.let(LocalDate::ofEpochDay)
                    val selectedDate = (
                        draft?.targetDate ?: manualFoodFormDraft?.targetDate ?: pendingTargetDate ?: requestedSelectedDate
                    ).coerceAtMost(today)
                    val exerciseCatalog = repository.listExercises(includeArchived = true)
                    val exercises = exerciseCatalog.filterNot { it.isArchived }
                    if (activeWorkout != null && workoutPlanner.editorDraft == null) {
                        exercises.firstOrNull { it.isPrimary }
                            ?.let { preferred ->
                                workoutPlanner = repository.beginWorkoutPlanning(
                                    newWorkoutEditorDraft(
                                        sessionId = activeWorkout.id,
                                        exercise = preferred,
                                        restSeconds = activeWorkout.restDurationSeconds,
                                    ),
                                    workoutPlanner.activePlan,
                                )
                            }
                            ?: exercises.firstOrNull()?.let { fallback ->
                                workoutPlanner = repository.beginWorkoutPlanning(
                                    newWorkoutEditorDraft(
                                        sessionId = activeWorkout.id,
                                        exercise = fallback,
                                        restSeconds = activeWorkout.restDurationSeconds,
                                    ),
                                    workoutPlanner.activePlan,
                                )
                            }
                    }
                    val historyRows = repository.listWorkoutHistory(HISTORY_PAGE_SIZE + 1)
                    val primaryIds = exercises.asSequence().filter { it.isPrimary }.map { it.id }.toList()
                    AppBootstrapSnapshot(
                        today = today,
                        profile = repository.getProfile(),
                        profileConfigured = repository.isProfileConfigured(),
                        todayNutrition = repository.nutritionForDate(today),
                        todayMeals = repository.mealsForDate(today),
                        selectedDate = selectedDate,
                        selectedNutrition = repository.nutritionForDate(selectedDate),
                        selectedMeals = repository.mealsForDate(selectedDate),
                        savedFoods = repository.listSavedFoods(),
                        measurements = repository.listBodyMeasurements(),
                        bodyMeasurementFormDraft = repository.bodyMeasurementFormDraft(),
                        resolvedBodyMeasurementShortcutRequestId = repository.resolvedBodyMeasurementShortcutRequestId(),
                        exercises = exercises,
                        exerciseCatalog = exerciseCatalog,
                        activeWorkout = activeWorkout,
                        activeSets = activeSets,
                        workoutCorrectionOriginal = workoutCorrectionOriginal,
                        workoutPlanner = workoutPlanner,
                        workoutDraftWasRecovered = workoutDraftWasRecovered,
                        draft = draft,
                        manualFoodFormDraft = manualFoodFormDraft,
                        pendingCameraCapture = pendingCameraCapture,
                        analysisEndpoint = analysisConfig.endpointUrl,
                        analysisTransport = analysisConfig.transport,
                        analysisModel = analysisConfig.modelName,
                        analysisAdditionalPrompt = analysisConfig.additionalPrompt,
                        analysisTokenConfigured = analysisConfig.accessToken.isNotBlank(),
                        analysisProfiles = analysisProfiles,
                        activeAnalysisProfileId = repository.activeAnalysisProfileId(),
                        analysisProfilesUnreadable = repository.analysisProfilesUnreadable(),
                        lastPrEvents = repository.latestPrEvents(),
                        todayCompletedWorkouts = repository.completedWorkoutsForDate(today),
                        workoutHistory = historyRows.take(HISTORY_PAGE_SIZE),
                        hasMoreWorkoutHistory = historyRows.size > HISTORY_PAGE_SIZE,
                        prSummaries = primaryIds.associateWith(repository::prSummary),
                        unacknowledgedWorkoutSetCommitId = recoveredSingleSetCommitId,
                        unacknowledgedFiveByFiveCommitId = recoveredFiveByFiveCommitId,
                    )
                }
            }
            if (refreshId != fullRefreshId || ledgerGeneration != workoutLedgerGeneration) return@launch
            result.onSuccess { snapshot ->
                val pendingCameraCapture = snapshot.pendingCameraCapture
                val recoveredWorkoutDraft = snapshot.workoutPlanner.editorDraft
                    ?.takeIf { it.sessionId == snapshot.activeWorkout?.id }
                latestWorkoutEditorRevision = recoveredWorkoutDraft?.revision ?: 0L
                val shouldApplyBarbellSnapshot = requestedBarbellGeneration == barbellBarWeightGeneration
                if (shouldApplyBarbellSnapshot) {
                    lastDurableBarbellBarWeightKg = snapshot.workoutPlanner.barbellBarWeightKg
                    lastDurableBarbellGeneration = maxOf(lastDurableBarbellGeneration, requestedBarbellGeneration)
                }
                val shouldShowWorkoutRecovery = snapshot.workoutDraftWasRecovered && recoveredWorkoutDraft != null &&
                    shownWorkoutRecoveryNotices.add(recoveredWorkoutDraft.sessionId)
                val shouldShowWorkoutCorrectionRecovery = snapshot.activeWorkout?.correctionOfSessionId != null &&
                    shownWorkoutCorrectionRecoveryNotices.add(snapshot.activeWorkout.id)
                state = state.copy(
                    isInitialized = true,
                    restoreRecoveryRequired = false,
                    isImportingBackup = false,
                    loadedDate = snapshot.today,
                    profile = snapshot.profile,
                    profileConfigured = snapshot.profileConfigured,
                    todayNutrition = snapshot.todayNutrition,
                    todayMeals = snapshot.todayMeals,
                    selectedFoodDate = snapshot.selectedDate,
                    selectedDateNutrition = snapshot.selectedNutrition,
                    selectedDateMeals = snapshot.selectedMeals,
                    savedFoods = snapshot.savedFoods,
                    measurements = snapshot.measurements,
                    bodyMeasurementFormDraft = snapshot.bodyMeasurementFormDraft,
                    isBodyMeasurementFormVisible = snapshot.bodyMeasurementFormDraft != null,
                    isAutoSavingBodyMeasurementFormDraft = false,
                    isBodyMeasurementFormDraftDurable = snapshot.bodyMeasurementFormDraft != null,
                    bodyMeasurementFormSaveError = null,
                    resolvedBodyMeasurementShortcutRequestId = snapshot.resolvedBodyMeasurementShortcutRequestId,
                    exercises = snapshot.exercises,
                    exerciseCatalog = snapshot.exerciseCatalog,
                    activeWorkout = snapshot.activeWorkout,
                    activeSets = snapshot.activeSets,
                    workoutCorrectionOriginal = snapshot.workoutCorrectionOriginal,
                    workoutEditorDraft = recoveredWorkoutDraft,
                    activeWorkoutPlan = snapshot.workoutPlanner.activePlan,
                    workoutTemplates = snapshot.workoutPlanner.templates,
                    barbellBarWeightKg = if (shouldApplyBarbellSnapshot) {
                        snapshot.workoutPlanner.barbellBarWeightKg
                    } else {
                        state.barbellBarWeightKg
                    },
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = null,
                    mealDraft = snapshot.draft,
                    manualFoodFormDraft = snapshot.manualFoodFormDraft,
                    isManualFoodFormVisible = state.isManualFoodFormVisible && snapshot.manualFoodFormDraft != null,
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = null,
                    isSavingManualFoodFormDraft = false,
                    pendingCameraUri = pendingCameraCapture?.uri,
                    pendingCameraPhase = pendingCameraCapture?.phase,
                    pendingCameraTargetDate = pendingCameraCapture?.targetDateEpochDay?.let(LocalDate::ofEpochDay),
                    analysisEndpoint = snapshot.analysisEndpoint,
                    analysisTransport = snapshot.analysisTransport,
                    analysisModel = snapshot.analysisModel,
                    analysisAdditionalPrompt = snapshot.analysisAdditionalPrompt,
                    analysisTokenConfigured = snapshot.analysisTokenConfigured,
                    analysisProfiles = snapshot.analysisProfiles,
                    activeAnalysisProfileId = snapshot.activeAnalysisProfileId,
                    analysisProfilesUnreadable = snapshot.analysisProfilesUnreadable,
                    lastPrEvents = snapshot.lastPrEvents,
                    todayCompletedWorkouts = snapshot.todayCompletedWorkouts,
                    workoutHistory = snapshot.workoutHistory,
                    hasMoreWorkoutHistory = snapshot.hasMoreWorkoutHistory,
                    isLoadingWorkoutHistory = false,
                    isLoadingMoreWorkoutHistory = false,
                    prSummaries = snapshot.prSummaries,
                    unacknowledgedWorkoutSetCommitId = snapshot.unacknowledgedWorkoutSetCommitId,
                    unacknowledgedFiveByFiveCommitId = snapshot.unacknowledgedFiveByFiveCommitId,
                    message = when {
                        snapshot.unacknowledgedFiveByFiveCommitId != null ->
                            "上一批 5×5 已安全保存；请确认后再继续记录"
                        snapshot.unacknowledgedWorkoutSetCommitId != null ->
                            "上一组已安全保存；请确认后再记录下一组"
                        shouldShowWorkoutCorrectionRecovery ->
                            "更正草稿已恢复；原完成记录和 PR 在保存更正前保持不变"
                        shouldShowWorkoutRecovery ->
                            "未完成组已恢复：动作和全部输入已从本机草稿还原"
                        else -> state.message
                    },
                )
                if (snapshot.draft == null && pendingCameraCapture != null &&
                    pendingCameraCapture.phase == PhotoStorage.PendingCameraPhase.RESULT_RECEIVED
                ) {
                    analyzePhoto(pendingCameraCapture.uri)
                }
            }.onFailure { error ->
                state = refreshFailureState(state, recoveryGateWasActive, error)
            }
        }
    }

    fun retryInterruptedRestoreRecovery() {
        if (!state.restoreRecoveryRequired) return
        fullRefreshId += 1L
        workoutLedgerGeneration += 1L
        state = AppUiState(
            isInitialized = false,
            planEditorEpoch = UUID.randomUUID().toString(),
            restoreRecoveryRequired = true,
        )
        viewModelScope.launch {
            val recovered = runCatching {
                withContext(Dispatchers.IO) { repository.recoverInterruptedRestore() }
            }
            if (recovered.isSuccess) {
                refreshAll()
            } else {
                state = AppUiState(
                    isInitialized = true,
                    planEditorEpoch = UUID.randomUUID().toString(),
                    restoreRecoveryRequired = true,
                    message = null,
                )
            }
        }
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun acknowledgeMealCommitReceipt(receiptId: String) {
        if (state.mealCommitReceipt?.id != receiptId) return
        state = state.copy(mealCommitReceipt = null)
    }

    fun updateProfile(profile: UserProfile) {
        if (state.isSavingProfile) return
        profile.validationError()?.let {
            state = state.copy(message = it)
            return
        }
        state = state.copy(isSavingProfile = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.updateProfile(profile)
                    repository.markProfileConfigured()
                }
            }.onSuccess {
                state = state.copy(
                    profile = profile,
                    profileConfigured = true,
                    isSavingProfile = false,
                    message = "目标已保存",
                )
            }.onFailure {
                state = state.copy(
                    isSavingProfile = false,
                    message = "无法保存目标；原设置未改变，请稍后重试",
                )
            }
        }
    }

    fun openBodyMeasurementForm(
        initial: BodyMeasurement,
        shortcutRequestId: String? = null,
    ) {
        if (state.isSavingMeasurement) return
        state.bodyMeasurementFormDraft?.let { existing ->
            val resumed = if (shortcutRequestId != null && existing.shortcutRequestId != shortcutRequestId) {
                existing.copy(
                    shortcutRequestId = shortcutRequestId,
                    revision = existing.revision + 1L,
                    updatedAtMillis = System.currentTimeMillis().coerceAtLeast(existing.updatedAtMillis + 1L),
                )
            } else {
                existing
            }
            latestBodyMeasurementRevision = resumed.revision
            state = state.copy(
                bodyMeasurementFormDraft = resumed,
                isBodyMeasurementFormVisible = true,
                isAutoSavingBodyMeasurementFormDraft = resumed != existing,
                isBodyMeasurementFormDraftDurable = resumed == existing && state.isBodyMeasurementFormDraftDurable,
                bodyMeasurementFormSaveError = null,
                message = if (resumed == existing) "已恢复未完成的身体记录" else null,
            )
            if (resumed != existing) persistBodyMeasurementForm(resumed, bodyMeasurementFormGeneration)
            return
        }
        val draft = BodyMeasurementFormDraft(
            measurementId = initial.id,
            date = initial.date,
            weightText = formatOne(initial.weightKg),
            waistText = initial.waistCm?.let(::formatOne).orEmpty(),
            shortcutRequestId = shortcutRequestId,
        )
        bodyMeasurementFormGeneration += 1L
        latestBodyMeasurementRevision = draft.revision
        state = state.copy(
            bodyMeasurementFormDraft = draft,
            isBodyMeasurementFormVisible = true,
            isAutoSavingBodyMeasurementFormDraft = true,
            isBodyMeasurementFormDraftDurable = false,
            bodyMeasurementFormSaveError = null,
            message = null,
        )
        persistBodyMeasurementForm(draft, bodyMeasurementFormGeneration)
    }

    fun updateBodyMeasurementForm(expectedFormId: String, transform: BodyMeasurementFormTransform) {
        val current = state.bodyMeasurementFormDraft ?: return
        if (!state.isBodyMeasurementFormVisible || state.isSavingMeasurement) return
        val updated = mergeBodyMeasurementFormUpdate(current, expectedFormId, transform) ?: return
        updated.persistenceValidationError()?.let { error ->
            state = state.copy(bodyMeasurementFormSaveError = error)
            return
        }
        latestBodyMeasurementRevision = updated.revision
        state = state.copy(
            bodyMeasurementFormDraft = updated,
            isAutoSavingBodyMeasurementFormDraft = true,
            isBodyMeasurementFormDraftDurable = false,
            bodyMeasurementFormSaveError = null,
            message = null,
        )
        persistBodyMeasurementForm(updated, bodyMeasurementFormGeneration)
    }

    private fun persistBodyMeasurementForm(draft: BodyMeasurementFormDraft, generation: Long) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    bodyMeasurementPersistenceMutex.withLock {
                        if (generation != bodyMeasurementFormGeneration ||
                            draft.revision != latestBodyMeasurementRevision
                        ) {
                            return@withLock null
                        }
                        repository.saveBodyMeasurementFormDraft(draft)
                    }
                }
            }
            val current = state.bodyMeasurementFormDraft
            if (generation != bodyMeasurementFormGeneration || current?.id != draft.id ||
                current.revision != draft.revision
            ) {
                return@launch
            }
            result.onSuccess { stored ->
                if (stored != null) {
                    state = state.copy(
                        isAutoSavingBodyMeasurementFormDraft = false,
                        isBodyMeasurementFormDraftDurable = stored,
                        bodyMeasurementFormSaveError = if (stored) null else "这份身体记录已被丢弃，无法再次保存",
                    )
                }
            }.onFailure {
                state = state.copy(
                    isAutoSavingBodyMeasurementFormDraft = false,
                    isBodyMeasurementFormDraftDurable = false,
                    bodyMeasurementFormSaveError = "无法自动保存到本机；当前页仍保留输入，请修改任一字段重试",
                )
            }
        }
    }

    fun saveBodyMeasurementForm(expectedFormId: String) {
        val draft = state.bodyMeasurementFormDraft ?: return
        if (draft.id != expectedFormId || !state.isBodyMeasurementFormVisible || state.isSavingMeasurement) return
        val weight = draft.weightText.trim().replace(',', '.').toDoubleOrNull()
        val waist = draft.waistText.trim().replace(',', '.').let { raw ->
            if (raw.isBlank()) null else raw.toDoubleOrNull()
        }
        val measurement = BodyMeasurement(
            id = draft.measurementId,
            date = draft.date,
            weightKg = weight ?: Double.NaN,
            waistCm = if (draft.waistText.isBlank()) null else waist ?: Double.NaN,
        )
        measurement.validationError()?.let { error ->
            state = state.copy(bodyMeasurementFormSaveError = error, message = error)
            return
        }
        val generation = bodyMeasurementFormGeneration
        val previousMeasurements = state.measurements
        val draftWasDurable = state.isBodyMeasurementFormDraftDurable
        latestBodyMeasurementRevision = draft.revision
        state = state.copy(
            isSavingMeasurement = true,
            isAutoSavingBodyMeasurementFormDraft = false,
            bodyMeasurementFormSaveError = null,
            message = null,
        )
        viewModelScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                bodyMeasurementPersistenceMutex.withLock {
                    if (generation != bodyMeasurementFormGeneration ||
                        draft.revision != latestBodyMeasurementRevision
                    ) {
                        return@withLock BodyMeasurementSaveAttempt.Failure(
                            IllegalStateException("身体表单已变更"),
                            rawDraftDurable = draftWasDurable,
                        )
                    }
                    try {
                        check(repository.saveBodyMeasurementFormDraft(draft)) {
                            "身体表单已被丢弃"
                        }
                    } catch (error: Throwable) {
                        return@withLock BodyMeasurementSaveAttempt.Failure(error, rawDraftDurable = false)
                    }
                    val persistedId = try {
                        repository.saveBodyMeasurement(measurement, formCommitId = draft.id)
                    } catch (error: Throwable) {
                        return@withLock BodyMeasurementSaveAttempt.Failure(error, rawDraftDurable = true)
                    }
                    val persisted = measurement.copy(id = persistedId)
                    val measurements = runCatching { repository.listBodyMeasurements() }.getOrElse {
                        (previousMeasurements.filterNot { row ->
                            row.id == persistedId || row.date == persisted.date
                        } + persisted).sortedWith(compareBy(BodyMeasurement::date, BodyMeasurement::id))
                    }
                    val cleanupSucceeded = runCatching {
                        repository.clearBodyMeasurementFormDraft(draft.id, draft.shortcutRequestId)
                    }.getOrDefault(false)
                    BodyMeasurementSaveAttempt.Success(measurements, cleanupSucceeded)
                }
            }
            if (generation != bodyMeasurementFormGeneration ||
                state.bodyMeasurementFormDraft?.id != draft.id
            ) {
                return@launch
            }
            when (attempt) {
                is BodyMeasurementSaveAttempt.Success -> {
                    bodyMeasurementFormGeneration += 1L
                    latestBodyMeasurementRevision = 0L
                    state = state.copy(
                        measurements = attempt.measurements,
                        isSavingMeasurement = false,
                        bodyMeasurementFormDraft = null,
                        isBodyMeasurementFormVisible = false,
                        isAutoSavingBodyMeasurementFormDraft = false,
                        isBodyMeasurementFormDraftDurable = false,
                        bodyMeasurementFormSaveError = null,
                        resolvedBodyMeasurementShortcutRequestId = draft.shortcutRequestId,
                        message = if (attempt.cleanupSucceeded) {
                            "${measurement.date} 身体数据已保存；参考体重不会自动改变"
                        } else {
                            "${measurement.date} 身体数据已保存；未完成表单将在下次启动时自动清理"
                        },
                    )
                }
                is BodyMeasurementSaveAttempt.Failure -> {
                    val message = if (attempt.error is IllegalArgumentException) {
                        attempt.error.message ?: "身体数据无效"
                    } else {
                        "无法保存身体数据；表单和输入已保留，请稍后重试"
                    }
                    state = state.copy(
                        isSavingMeasurement = false,
                        isBodyMeasurementFormDraftDurable = attempt.rawDraftDurable,
                        bodyMeasurementFormSaveError = message,
                        message = message,
                    )
                }
            }
        }
    }

    fun discardBodyMeasurementForm(expectedFormId: String) {
        val draft = state.bodyMeasurementFormDraft ?: return
        if (draft.id != expectedFormId || state.isSavingMeasurement) return
        bodyMeasurementFormGeneration += 1L
        latestBodyMeasurementRevision = 0L
        val generation = bodyMeasurementFormGeneration
        state = state.copy(
            isSavingMeasurement = true,
            isAutoSavingBodyMeasurementFormDraft = false,
            bodyMeasurementFormSaveError = null,
            message = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    bodyMeasurementPersistenceMutex.withLock {
                        check(generation == bodyMeasurementFormGeneration)
                        check(repository.clearBodyMeasurementFormDraft(draft.id, draft.shortcutRequestId)) {
                            "身体表单已变化"
                        }
                    }
                }
            }
            if (generation != bodyMeasurementFormGeneration) return@launch
            result.onSuccess {
                state = state.copy(
                    isSavingMeasurement = false,
                    bodyMeasurementFormDraft = null,
                    isBodyMeasurementFormVisible = false,
                    isAutoSavingBodyMeasurementFormDraft = false,
                    isBodyMeasurementFormDraftDurable = false,
                    bodyMeasurementFormSaveError = null,
                    resolvedBodyMeasurementShortcutRequestId = draft.shortcutRequestId,
                    message = "未完成的身体记录已丢弃；账本未改变",
                )
            }.onFailure {
                state = state.copy(
                    isSavingMeasurement = false,
                    bodyMeasurementFormSaveError = "无法丢弃身体记录；表单仍保留，请重试",
                    message = "无法丢弃身体记录；表单仍保留，请重试",
                )
            }
        }
    }

    fun acknowledgeNewBodyMeasurementRequest(expectedRequestId: String) {
        if (state.resolvedBodyMeasurementShortcutRequestId != expectedRequestId) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.acknowledgeBodyMeasurementShortcutRequest(expectedRequestId)
                }
            }.onSuccess { acknowledged ->
                if (acknowledged && state.resolvedBodyMeasurementShortcutRequestId == expectedRequestId) {
                    state = state.copy(resolvedBodyMeasurementShortcutRequestId = null)
                } else if (!acknowledged && state.resolvedBodyMeasurementShortcutRequestId == expectedRequestId) {
                    state = state.copy(message = "身体记录已处理；本机回执暂未确认，将在下次启动时重试")
                }
            }.onFailure {
                state = state.copy(message = "入口状态已处理，但本机确认失败；不影响已保存的身体数据")
            }
        }
    }

    fun deleteMeasurement(measurementId: Long) {
        if (state.isSavingMeasurement) return
        state = state.copy(isSavingMeasurement = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val deleted = repository.deleteBodyMeasurement(measurementId)
                    deleted to repository.listBodyMeasurements()
                }
            }.onSuccess { (deleted, measurements) ->
                state = state.copy(
                    measurements = measurements,
                    isSavingMeasurement = false,
                    message = if (deleted) "身体记录已删除" else "该身体记录已不存在",
                )
            }.onFailure {
                state = state.copy(isSavingMeasurement = false, message = "无法删除身体记录；数据未改变")
            }
        }
    }

    fun reloadBodyMeasurements() {
        val generation=workoutLedgerGeneration
        viewModelScope.launch {
            val records=withContext(Dispatchers.IO) { runCatching { repository.listBodyMeasurements() }.getOrNull() }
            if(records!=null && generation==workoutLedgerGeneration && !state.isImportingBackup && !state.restoreRecoveryRequired) state=state.copy(measurements=records)
        }
    }

    fun hideXiaomiMeasurement(id: Long) {
        if(state.restoreRecoveryRequired || state.isImportingBackup) return
        viewModelScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching { repository.hideXiaomiWeight(id) }.getOrDefault(false) }
            if(ok) reloadBodyMeasurements() else state=state.copy(message="这条记录未能隐藏，请重试。")
        }
    }

    fun refreshForCurrentDate(force: Boolean = false) {
        val today = LocalDate.now()
        if (!force && state.loadedDate == today) return
        val refreshId = ++currentDateRefreshId
        val selectedDate = state.mealDraft?.targetDate ?: if (state.selectedFoodDate == state.loadedDate) {
            today
        } else {
            state.selectedFoodDate.coerceAtMost(today)
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val todayLedger = repository.nutritionForDate(today) to repository.mealsForDate(today)
                    val selectedLedger = repository.nutritionForDate(selectedDate) to repository.mealsForDate(selectedDate)
                    Triple(todayLedger, selectedLedger, repository.completedWorkoutsForDate(today))
                }
            }.onSuccess { (todayLedger, selectedLedger, completedWorkouts) ->
                if (refreshId != currentDateRefreshId) return@onSuccess
                state = state.copy(
                    loadedDate = today,
                    todayNutrition = todayLedger.first,
                    todayMeals = todayLedger.second,
                    selectedFoodDate = selectedDate,
                    selectedDateNutrition = selectedLedger.first,
                    selectedDateMeals = selectedLedger.second,
                    todayCompletedWorkouts = completedWorkouts,
                )
            }.onFailure {
                if (refreshId != currentDateRefreshId) return@onFailure
                state = state.copy(message = "日期已变化，但本地账本刷新失败；请稍后重试")
            }
        }
    }

    fun selectFoodDate(date: LocalDate) {
        if (state.mealDraft != null || state.manualFoodFormDraft != null || state.isLoadingFoodDate || mealDraftMutationLocked()) return
        val safeDate = date.coerceAtMost(LocalDate.now())
        state = state.copy(isLoadingFoodDate = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.nutritionForDate(safeDate) to repository.mealsForDate(safeDate)
                }
            }.onSuccess { (nutrition, meals) ->
                state = state.copy(
                    selectedFoodDate = safeDate,
                    selectedDateNutrition = nutrition,
                    selectedDateMeals = meals,
                    isLoadingFoodDate = false,
                )
            }.onFailure {
                state = state.copy(isLoadingFoodDate = false, message = "无法读取该日期的饮食记录")
            }
        }
    }

    fun requestCameraCapture() {
        if (!state.isInitialized || state.mealDraft != null || state.manualFoodFormDraft != null || state.isSavingMealDraft ||
            state.isCommittingMeal || state.isAnalyzingPhoto || state.isPreparingCameraCapture ||
            state.isHandlingCameraResult || state.cameraLaunchRequest != null || state.isSavingAnalysisConfig
        ) return

        val recoverableUri = state.pendingCameraUri
            ?.takeIf { state.pendingCameraPhase.isResumableCameraPhase() }
        if (recoverableUri != null) {
            analyzePhoto(recoverableUri)
            return
        }

        val targetDate = state.selectedFoodDate
        state = state.copy(isPreparingCameraCapture = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.prepareCameraCapture(targetDateEpochDay = targetDate.toEpochDay())
                }
            }.onSuccess { pending ->
                state = state.copy(
                    isPreparingCameraCapture = false,
                    pendingCameraUri = pending.uri,
                    pendingCameraPhase = pending.phase,
                    pendingCameraTargetDate = pending.targetDateEpochDay?.let(LocalDate::ofEpochDay) ?: targetDate,
                )
                if (pending.phase.isResumableCameraPhase()) {
                    analyzePhoto(pending.uri)
                } else {
                    val request = CameraLaunchRequest(++cameraLaunchRequestId, pending.uri)
                    state = state.copy(cameraLaunchRequest = request)
                }
            }.onFailure {
                state = state.copy(
                    isPreparingCameraCapture = false,
                    message = "无法准备系统相机；未创建新的照片草稿",
                )
            }
        }
    }

    /** Compose reports only whether ActivityResult launch itself succeeded. Any
     * temporary-file cleanup remains serialized here on Dispatchers.IO. */
    fun onCameraLaunchHandled(requestId: Long, launched: Boolean) {
        val request = state.cameraLaunchRequest?.takeIf { it.id == requestId } ?: return
        state = state.copy(cameraLaunchRequest = null)
        if (launched) return

        state = state.copy(isPreparingCameraCapture = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.cancelCameraLaunch(request.uri) }
            }.onSuccess { pending ->
                state = state.copy(
                    isPreparingCameraCapture = false,
                    pendingCameraUri = pending?.uri,
                    pendingCameraPhase = pending?.phase,
                    pendingCameraTargetDate = pending?.targetDateEpochDay?.let(LocalDate::ofEpochDay),
                    message = "无法打开系统相机；未交付的临时照片已清理",
                )
            }.onFailure {
                state = state.copy(
                    isPreparingCameraCapture = false,
                    message = "无法打开系统相机；临时照片将在下次启动时安全清理",
                )
            }
        }
    }

    /** ActivityResult callbacks contain no file or SharedPreferences work. The
     * durable journal is read and transitioned on Dispatchers.IO, which also makes
     * process-restored callbacks independent of process-local Compose state. */
    fun onCameraCaptureResult(success: Boolean) {
        if (state.isHandlingCameraResult) return
        val expectedUri = state.pendingCameraUri
        state = state.copy(
            cameraLaunchRequest = null,
            isHandlingCameraResult = true,
            message = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.recordCameraCaptureResult(success, expectedUri)
                }
            }.onSuccess { pending ->
                state = state.copy(
                    isHandlingCameraResult = false,
                    pendingCameraUri = pending?.uri,
                    pendingCameraPhase = pending?.phase,
                    pendingCameraTargetDate = pending?.targetDateEpochDay?.let(LocalDate::ofEpochDay),
                    message = if (!success && pending == null) "已取消拍照，未生成草稿" else null,
                )
                // A restored/duplicate result must not automatically repeat a
                // request already marked ANALYZING by the previous process.
                if (pending?.phase == PhotoStorage.PendingCameraPhase.RESULT_RECEIVED) analyzePhoto(pending.uri)
            }.onFailure {
                state = state.copy(
                    isHandlingCameraResult = false,
                    message = "无法确认系统相机返回状态；照片未入账，重新打开 App 可自动恢复",
                )
            }
        }
    }

    fun analyzePhoto(uri: Uri) {
        if (state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal ||
            state.mealDraft != null || state.manualFoodFormDraft != null || state.isSavingAnalysisConfig
        ) return
        val operationId = ++photoOperationId
        val isPendingCameraPhoto = state.pendingCameraUri?.toString() == uri.toString()
        val targetDate = if (isPendingCameraPhoto) {
            state.pendingCameraTargetDate ?: state.selectedFoodDate
        } else {
            state.selectedFoodDate
        }
        state = state.copy(
            isAnalyzingPhoto = true,
            message = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.analyzePhoto(uri, targetDate) }
            }
            val draft = result.getOrNull()?.copy(targetDate = targetDate)
            if (draft != null) {
                if (operationId != photoOperationId || !state.isAnalyzingPhoto || state.mealDraft != null) {
                    withContext(Dispatchers.IO) { repository.discardDraft(draft) }
                    return@launch
                }
                val stored = runCatching { withContext(Dispatchers.IO) { repository.saveDraft(draft) } }
                if (stored.isFailure) {
                    withContext(Dispatchers.IO) { repository.discardDraft(draft) }
                    state = state.copy(isAnalyzingPhoto = false, message = "无法保存识别草稿；所选日期摄入未改变")
                    return@launch
                }
                withContext(Dispatchers.IO) { repository.completePendingCameraCapture(uri) }
                state = state.copy(
                    isAnalyzingPhoto = false,
                    mealDraft = draft,
                    pendingCameraUri = if (isPendingCameraPhoto) null else state.pendingCameraUri,
                    pendingCameraPhase = if (isPendingCameraPhoto) null else state.pendingCameraPhase,
                    pendingCameraTargetDate = if (isPendingCameraPhoto) null else state.pendingCameraTargetDate,
                    message = when {
                        draft.state == DraftState.ANALYSIS_FAILED -> "识别服务暂不可用；照片已保留，可重试或手工补录"
                        draft.analysisMode == AnalysisMode.INTERACTIVE_DEMO -> "当前展示交互演示草稿，请勿当作照片识别结果"
                        draft.analysisMode == AnalysisMode.ON_DEVICE_AI && draft.items.isEmpty() ->
                            "本机模型已给出菜名候选；请选择一项并补全营养后再入账"
                        draft.analysisMode == AnalysisMode.ON_DEVICE_AI ->
                            "本机模型已预填 USDA 通用值；请核对名称、克重、油、酱汁和配方"
                        else -> "识别草稿已生成，请核对"
                    },
                )
            } else if (operationId == photoOperationId && state.isAnalyzingPhoto) {
                state = state.copy(
                    isAnalyzingPhoto = false,
                    message = if (isPendingCameraPhoto) {
                        "识别失败；照片未入账，重新打开 App 可自动恢复，或点击拍照按钮重试上次照片"
                    } else {
                        "识别失败；照片未计入所选日期，请重试或改用手工记录"
                    },
                )
            }
        }
    }

    fun openManualFoodForm(initial: FoodDraftItem) {
        if (state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal || state.mealDraft != null) {
            state = state.copy(message = "请先处理当前饮食草稿")
            return
        }
        state.manualFoodFormDraft?.let {
            state = state.copy(
                isManualFoodFormVisible = true,
                message = "已恢复未完成的手工记录；保存前不会计入账本",
            )
            return
        }
        val form = ManualFoodFormDraft(
            targetDate = state.selectedFoodDate,
            itemId = initial.id,
            initialGrams = initial.grams,
            initialGramsMin = initial.gramsMin,
            initialGramsMax = initial.gramsMax,
            initialPortionBasis = initial.portionBasis,
            name = initial.name,
            gramsText = formatOne(initial.grams),
            kcalText = formatOne(initial.per100g.kcal),
            carbsText = formatOne(initial.per100g.carbsG),
            proteinText = formatOne(initial.per100g.proteinG),
            fatText = formatOne(initial.per100g.fatG),
            sourceName = initial.sourceName,
            weighed = initial.portionBasis == com.personal.fitnessledger.data.PortionBasis.USER_WEIGHT,
            useLabelKcal = initial.calorieSource == com.personal.fitnessledger.data.CalorieSource.LABEL_OR_DATABASE,
        )
        manualFoodFormGeneration += 1L
        latestManualFoodRevision = form.revision
        state = state.copy(
            manualFoodFormDraft = form,
            isManualFoodFormVisible = true,
            isAutoSavingManualFoodFormDraft = false,
            manualFoodFormSaveError = null,
            isSavingManualFoodFormDraft = false,
            message = null,
        )
    }

    fun updateManualFoodForm(expectedFormId: String, transform: ManualFoodFormTransform) {
        val current = state.manualFoodFormDraft ?: return
        if (state.isSavingMealDraft || state.isCommittingMeal) return
        val updated = mergeManualFoodFormUpdate(current, expectedFormId, transform) ?: return
        updated.persistenceValidationError()?.let { error ->
            state = state.copy(message = error)
            return
        }
        val generation = manualFoodFormGeneration
        latestManualFoodRevision = updated.revision
        state = state.copy(
            manualFoodFormDraft = updated,
            isAutoSavingManualFoodFormDraft = true,
            manualFoodFormSaveError = null,
            message = null,
        )
        persistManualFoodForm(updated, generation)
    }

    private fun persistManualFoodForm(draft: ManualFoodFormDraft, generation: Long) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manualFoodPersistenceMutex.withLock {
                        if (generation != manualFoodFormGeneration || draft.revision != latestManualFoodRevision) {
                            return@withLock null
                        }
                        repository.saveManualFoodFormDraft(draft)
                    }
                }
            }
            val current = state.manualFoodFormDraft
            if (generation != manualFoodFormGeneration || current?.id != draft.id || current.revision != draft.revision) {
                return@launch
            }
            result.onSuccess { stored ->
                if (stored != null) {
                    state = state.copy(
                        isAutoSavingManualFoodFormDraft = false,
                        manualFoodFormSaveError = if (stored) null else "这份手工记录已被丢弃，无法再次保存",
                    )
                }
            }.onFailure {
                state = state.copy(
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = "无法自动保存到本机；当前页面仍保留输入，请修改任一字段重试",
                )
            }
        }
    }

    fun keepManualFoodFormAndClose(expectedFormId: String) {
        val draft = state.manualFoodFormDraft ?: return
        if (draft.id != expectedFormId || state.isSavingManualFoodFormDraft || state.isSavingMealDraft) return
        val draftToKeep = if (draft.isDirty) draft else draft.copy(
            isDirty = true,
            revision = draft.revision + 1L,
            updatedAtMillis = System.currentTimeMillis().coerceAtLeast(draft.updatedAtMillis + 1L),
        )
        val generation = manualFoodFormGeneration
        latestManualFoodRevision = draftToKeep.revision
        state = state.copy(
            manualFoodFormDraft = draftToKeep,
            isAutoSavingManualFoodFormDraft = false,
            manualFoodFormSaveError = null,
            isSavingManualFoodFormDraft = true,
            message = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manualFoodPersistenceMutex.withLock {
                        if (generation != manualFoodFormGeneration) return@withLock false
                        repository.saveManualFoodFormDraft(draftToKeep)
                    }
                }
            }
            if (generation != manualFoodFormGeneration || state.manualFoodFormDraft?.id != draftToKeep.id) return@launch
            result.onSuccess { stored ->
                state = state.copy(
                    isManualFoodFormVisible = !stored,
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = if (stored) null else "无法保留这份手工记录",
                    isSavingManualFoodFormDraft = false,
                    message = if (stored) "未完成的手工记录已保留，可稍后继续" else "无法保留这份手工记录",
                )
            }.onFailure {
                state = state.copy(
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = "无法保留手工记录；表单仍保持打开",
                    isSavingManualFoodFormDraft = false,
                    message = "无法保留手工记录；表单仍保持打开",
                )
            }
        }
    }

    fun discardManualFoodForm(expectedFormId: String) {
        val draft = state.manualFoodFormDraft ?: return
        if (draft.id != expectedFormId || state.isSavingMealDraft) return
        manualFoodFormGeneration += 1L
        latestManualFoodRevision = 0L
        val generation = manualFoodFormGeneration
        state = state.copy(
            isAutoSavingManualFoodFormDraft = false,
            manualFoodFormSaveError = null,
            isSavingManualFoodFormDraft = true,
            message = null,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manualFoodPersistenceMutex.withLock {
                        if (generation != manualFoodFormGeneration) return@withLock
                        repository.clearManualFoodFormDraft(draft.id)
                    }
                }
            }
            if (generation != manualFoodFormGeneration) return@launch
            result.onSuccess {
                state = state.copy(
                    manualFoodFormDraft = null,
                    isManualFoodFormVisible = false,
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = null,
                    isSavingManualFoodFormDraft = false,
                    message = "未完成的手工记录已丢弃；账本未改变",
                )
            }.onFailure {
                state = state.copy(
                    isAutoSavingManualFoodFormDraft = false,
                    manualFoodFormSaveError = "无法丢弃手工记录；内容仍保留，请重试",
                    isSavingManualFoodFormDraft = false,
                    message = "无法丢弃手工记录；内容仍保留，请重试",
                )
            }
        }
    }

    fun createManualDraft(expectedFormId: String) {
        if (state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal || state.mealDraft != null) {
            state = state.copy(
                message = if (state.isAnalyzingPhoto) "照片仍在分析，请等待完成后再手工记录" else "请先处理当前饮食草稿",
            )
            return
        }
        val rawForm = state.manualFoodFormDraft
        if (rawForm == null || rawForm.id != expectedFormId) {
            state = state.copy(message = "手工表单已变化，请重新核对后保存")
            return
        }
        val item = runCatching { rawForm.toFoodDraftItem() }.getOrElse { error ->
            state = state.copy(
                manualFoodFormSaveError = error.message ?: "手工表单内容无效，请核对",
                message = error.message ?: "手工表单内容无效，请核对",
            )
            return
        }
        val draft = MealDraft(
            photoUri = "",
            state = DraftState.EDITING,
            items = listOf(item.copy(evidenceTier = com.personal.fitnessledger.data.EvidenceTier.C, userModified = true)),
            evidenceTier = com.personal.fitnessledger.data.EvidenceTier.C,
            evidenceReason = "营养和份量由你手工输入；请按包装标签、可信食物数据库或称重结果核对",
            unresolvedFlags = emptySet(),
            providerLabel = "手工记录 · 尚未计入账本",
            analysisMode = AnalysisMode.MANUAL,
            targetDate = rawForm.targetDate,
        )
        manualFoodFormGeneration += 1L
        latestManualFoodRevision = 0L
        val generation = manualFoodFormGeneration
        state = state.copy(
            isAutoSavingManualFoodFormDraft = false,
            manualFoodFormSaveError = null,
            isSavingMealDraft = true,
            message = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    manualFoodPersistenceMutex.withLock {
                        check(generation == manualFoodFormGeneration)
                        check(repository.saveManualFoodFormDraft(rawForm)) {
                            "手工表单已被丢弃"
                        }
                        check(repository.saveManualDraftFromForm(draft, rawForm.id)) {
                            "这份手工表单已经转换为饮食草稿"
                        }
                        runCatching { repository.clearManualFoodFormDraft(rawForm.id) }
                    }
                }
            }
                .onSuccess {
                    state = state.copy(
                        mealDraft = draft,
                        manualFoodFormDraft = null,
                        isManualFoodFormVisible = false,
                        isAutoSavingManualFoodFormDraft = false,
                        manualFoodFormSaveError = null,
                        isSavingManualFoodFormDraft = false,
                        isSavingMealDraft = false,
                        message = "手工草稿已创建，请确认后入账",
                    )
                }
                .onFailure {
                    state = state.copy(
                        isSavingMealDraft = false,
                        isSavingManualFoodFormDraft = false,
                        manualFoodFormSaveError = "无法创建饮食草稿；最新原始输入仍保留在本机",
                        message = "无法保存手工草稿；所选日期摄入未改变",
                    )
                }
        }
    }

    fun editMeal(mealId: Long) = createDraftFromMeal(mealId, replaceOriginal = true)

    fun copyMeal(mealId: Long) = createDraftFromMeal(mealId, replaceOriginal = false)

    private fun createDraftFromMeal(mealId: Long, replaceOriginal: Boolean) {
        if (state.mealDraft != null || state.manualFoodFormDraft != null || state.isLoadingFoodDate || mealDraftMutationLocked()) return
        // Reusing an older meal normally means “eat this again today”. The draft
        // screen still lets the user move the target date before confirmation.
        val targetDate = if (replaceOriginal) state.selectedFoodDate else LocalDate.now()
        state = state.copy(isSavingMealDraft = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val draft = repository.draftFromMeal(mealId, targetDate, replaceOriginal)
                    repository.saveDraft(draft)
                    MealDraftCreation(
                        draft = draft,
                        targetNutrition = repository.nutritionForDate(requireNotNull(draft.targetDate)),
                        targetMeals = repository.mealsForDate(requireNotNull(draft.targetDate)),
                    )
                }
            }.onSuccess { creation ->
                val draft = creation.draft
                state = state.copy(
                    mealDraft = draft,
                    selectedFoodDate = draft.targetDate ?: targetDate,
                    selectedDateNutrition = creation.targetNutrition,
                    selectedDateMeals = creation.targetMeals,
                    isSavingMealDraft = false,
                    message = if (replaceOriginal) {
                        "已建立更正草稿；确认前原餐食保持不变"
                    } else {
                        "已复制到 ${draft.targetDate} 的待确认草稿；确认前不会计入"
                    },
                )
            }.onFailure { error ->
                state = state.copy(
                    isSavingMealDraft = false,
                    message = if (error is IllegalArgumentException) error.message ?: "无法读取餐食明细" else "无法建立餐食草稿",
                )
            }
        }
    }

    fun changeDraftTargetDate(date: LocalDate) {
        val previous = state.mealDraft ?: return
        if (previous.replacesMealId != null || state.isLoadingFoodDate || mealDraftMutationLocked()) return
        val safeDate = date.coerceAtMost(LocalDate.now())
        if (previous.targetDate == safeDate && state.selectedFoodDate == safeDate) return
        val updated = previous.copy(targetDate = safeDate)
        state = state.copy(isLoadingFoodDate = true, isSavingMealDraft = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Read first so a failed lookup cannot leave persisted and visible
                    // draft dates disagreeing. Only then persist the new target date.
                    val nutrition = repository.nutritionForDate(safeDate)
                    val meals = repository.mealsForDate(safeDate)
                    repository.saveDraft(updated)
                    nutrition to meals
                }
            }.onSuccess { (nutrition, meals) ->
                state = state.copy(
                    mealDraft = updated,
                    selectedFoodDate = safeDate,
                    selectedDateNutrition = nutrition,
                    selectedDateMeals = meals,
                    isLoadingFoodDate = false,
                    isSavingMealDraft = false,
                    message = "草稿将计入 $safeDate；确认前账本未改变",
                )
            }.onFailure {
                state = state.copy(
                    isLoadingFoodDate = false,
                    isSavingMealDraft = false,
                    message = "无法更改草稿日期；原日期保持不变",
                )
            }
        }
    }

    fun toggleFoodFavorite(food: SavedFood) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    check(repository.setFoodFavorite(food.id, !food.isFavorite))
                    repository.listSavedFoods()
                }
            }.onSuccess { foods ->
                state = state.copy(savedFoods = foods)
            }.onFailure {
                state = state.copy(message = "无法更新常用食物收藏")
            }
        }
    }

    fun retryDraftAnalysis() {
        val previous = state.mealDraft ?: return
        if (previous.photoUri.isBlank() || state.isAnalyzingPhoto || state.isSavingMealDraft ||
            state.isCommittingMeal || state.isSavingAnalysisConfig) return
        val operationId = ++photoOperationId
        state = state.copy(isAnalyzingPhoto = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.analyzePhoto(Uri.parse(previous.photoUri)) }
            }
            val replacement = result.getOrNull()?.copy(
                targetDate = previous.targetDate,
                replacesMealId = previous.replacesMealId,
            )
            if (replacement != null) {
                val stillCurrent = operationId == photoOperationId && state.isAnalyzingPhoto &&
                    state.mealDraft?.id == previous.id
                if (!stillCurrent) {
                    withContext(Dispatchers.IO) { repository.discardDraft(replacement) }
                    return@launch
                }
                if (replacement.state == DraftState.ANALYSIS_FAILED && previous.items.isNotEmpty()) {
                    // An unavailable provider has no replacement nutrition data.
                    // Preserve the user's edits, identity and confirmation state.
                    withContext(Dispatchers.IO) { repository.discardDraft(replacement) }
                    state = state.copy(
                        isAnalyzingPhoto = false,
                        message = "重新识别失败，原草稿和修改已保留；${replacement.evidenceReason}",
                    )
                    return@launch
                }
                val stored = runCatching { withContext(Dispatchers.IO) { repository.saveDraft(replacement) } }
                if (stored.isFailure) {
                    withContext(Dispatchers.IO) { repository.discardDraft(replacement) }
                    state = state.copy(isAnalyzingPhoto = false, message = "无法替换草稿；已保留原草稿")
                    return@launch
                }
                state = state.copy(
                    isAnalyzingPhoto = false,
                    mealDraft = replacement,
                    message = if (replacement.state == DraftState.ANALYSIS_FAILED) {
                        "识别仍不可用；照片已保留，可继续手工补录"
                    } else {
                        "已重新生成识别草稿，请核对"
                    },
                )
            } else if (operationId == photoOperationId && state.mealDraft?.id == previous.id) {
                state = state.copy(isAnalyzingPhoto = false, message = "无法重试；已保留当前草稿，请稍后再试或手工补录")
            }
        }
    }

    fun updateDraftItem(updatedItem: FoodDraftItem, onResult: (Boolean) -> Unit) {
        if (mealDraftMutationLocked()) {
            onResult(false)
            return
        }
        val draft = state.mealDraft
        if (draft == null) {
            onResult(false)
            return
        }
        val updatedDraft = recalculateDraft(
            draft.copy(items = draft.items.map { if (it.id == updatedItem.id) updatedItem.copy(userModified = true) else it }),
        )
        persistDraftUpdate(draft, updatedDraft, onResult)
    }

    fun removeDraftItem(itemId: String) {
        if (mealDraftMutationLocked()) return
        val draft = state.mealDraft ?: return
        val updatedDraft = recalculateDraft(draft.copy(items = draft.items.filterNot { it.id == itemId }))
        persistDraftUpdate(draft, updatedDraft)
    }

    fun addDraftItem(item: FoodDraftItem, onResult: (Boolean) -> Unit) {
        if (mealDraftMutationLocked()) {
            onResult(false)
            return
        }
        val draft = state.mealDraft
        if (draft == null) {
            onResult(false)
            return
        }
        val updatedDraft = recalculateDraft(draft.copy(items = draft.items + item.copy(userModified = true)))
        persistDraftUpdate(draft, updatedDraft, onResult)
    }

    fun resolveFoodHypothesis(
        hypothesis: FoodHypothesis,
        item: FoodDraftItem,
        onResult: (Boolean) -> Unit,
    ) {
        if (mealDraftMutationLocked()) {
            onResult(false)
            return
        }
        val draft = state.mealDraft
        val current = draft?.hypotheses?.firstOrNull {
            it.labelId == hypothesis.labelId && it.canonicalKey == hypothesis.canonicalKey
        }
        if (draft == null || draft.analysisMode != AnalysisMode.ON_DEVICE_AI || current == null) {
            onResult(false)
            return
        }
        if (item.per100g.kcal <= 0.0 && item.per100g.carbsG <= 0.0 &&
            item.per100g.proteinG <= 0.0 && item.per100g.fatG <= 0.0
        ) {
            state = state.copy(message = "请为所选候选填写有效营养值，不能以全零结果入账")
            onResult(false)
            return
        }
        val updatedDraft = recalculateDraft(
            draft.copy(
                items = listOf(item.copy(userModified = true)),
                hypotheses = emptyList(),
                providerLabel = buildString {
                    append("本机 Google AIY Food V1 · 已选择 ")
                    append(current.displayName)
                    append(" · 模型分数 ")
                    append((current.modelScore.coerceIn(0.0, 1.0) * 100.0).roundToLong())
                    append("%（不是准确率） · ")
                    append(
                        if (item.sourceName.startsWith("USDA FNDDS")) {
                            "USDA FNDDS 通用值"
                        } else {
                            "用户补录营养"
                        },
                    )
                },
            ),
        )
        persistDraftUpdate(draft, updatedDraft, onResult)
    }

    fun setDraftReviewed(reviewed: Boolean) {
        if (mealDraftMutationLocked()) return
        val draft = state.mealDraft ?: return
        val updatedDraft = draft.copy(
            state = if (reviewed) DraftState.READY_TO_CONFIRM else DraftState.EDITING,
            userReviewed = reviewed,
        )
        persistDraftUpdate(draft, updatedDraft)
    }

    fun confirmDraft() {
        val draft = state.mealDraft ?: return
        if (state.isCommittingMeal || state.isAnalyzingPhoto || state.isSavingMealDraft) return
        val validationError = draft.commitValidationError()
        if (validationError != null) {
            state = state.copy(message = validationError)
            return
        }
        val commitDate = draft.targetDate ?: state.selectedFoodDate
        state = state.copy(isCommittingMeal = true, message = null)
        viewModelScope.launch {
            val commitResult = runCatching {
                withContext(Dispatchers.IO) { repository.commitDraft(draft, commitDate) }
            }
            commitResult.onFailure { error ->
                state = state.copy(
                    isCommittingMeal = false,
                    message = if (error is IllegalArgumentException) {
                        "无法计入：${error.message ?: "请检查草稿内容"}"
                    } else {
                        "无法计入；数据未改变，请稍后重试"
                    },
                )
            }
            commitResult.onSuccess {
                runCatching { withContext(Dispatchers.IO) { loadFoodLedgerSnapshot(commitDate) } }
                    .onSuccess { snapshot ->
                        val receiptMessage = if (draft.replacesMealId != null) {
                            "已原子更正 ${commitDate} 的餐食"
                        } else {
                            "已确认并计入 ${commitDate}"
                        }
                        state = state.copy(
                            loadedDate = snapshot.today,
                            mealDraft = null,
                            isCommittingMeal = false,
                            todayNutrition = snapshot.todayNutrition,
                            todayMeals = snapshot.todayMeals,
                            todayCompletedWorkouts = snapshot.todayCompletedWorkouts,
                            selectedFoodDate = snapshot.selectedDate,
                            selectedDateNutrition = snapshot.selectedNutrition,
                            selectedDateMeals = snapshot.selectedMeals,
                            savedFoods = snapshot.savedFoods,
                            mealCommitReceipt = MealCommitReceipt(
                                id = draft.commitId,
                                message = receiptMessage,
                            ),
                            message = null,
                        )
                    }
                    .onFailure {
                        state = state.copy(
                            mealDraft = null,
                            isCommittingMeal = false,
                            mealCommitReceipt = MealCommitReceipt(
                                id = draft.commitId,
                                message = "餐食已安全写入；列表暂未刷新，重新打开 App 即可看到",
                            ),
                            message = null,
                        )
                    }
            }
        }
    }

    fun deleteMeal(mealId: Long) {
        if (state.isCommittingMeal) return
        val selectedDate = state.selectedFoodDate
        state = state.copy(isCommittingMeal = true, message = null)
        viewModelScope.launch {
            val deleteResult = runCatching { withContext(Dispatchers.IO) { repository.deleteMeal(mealId) } }
            deleteResult.onFailure {
                state = state.copy(isCommittingMeal = false, message = "无法删除餐食；数据未改变")
            }
            deleteResult.onSuccess {
                runCatching { withContext(Dispatchers.IO) { loadFoodLedgerSnapshot(selectedDate) } }
                    .onSuccess { snapshot ->
                        state = state.copy(
                            loadedDate = snapshot.today,
                            todayNutrition = snapshot.todayNutrition,
                            todayMeals = snapshot.todayMeals,
                            todayCompletedWorkouts = snapshot.todayCompletedWorkouts,
                            selectedFoodDate = snapshot.selectedDate,
                            selectedDateNutrition = snapshot.selectedNutrition,
                            selectedDateMeals = snapshot.selectedMeals,
                            isCommittingMeal = false,
                            message = "餐食已删除，${snapshot.selectedDate} 摄入已重新计算",
                            savedFoods = snapshot.savedFoods,
                        )
                    }
                    .onFailure {
                        state = state.copy(
                            isCommittingMeal = false,
                            message = "餐食已删除，但列表刷新失败；重新打开 App 即可看到结果",
                        )
                    }
            }
        }
    }

    fun discardDraft() {
        if (state.isCommittingMeal || state.isAnalyzingPhoto || state.isSavingMealDraft) return
        val draft = state.mealDraft ?: return
        state = state.copy(isSavingMealDraft = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.discardDraft(draft) } }
                .onSuccess {
                    state = state.copy(
                        mealDraft = null,
                        isSavingMealDraft = false,
                        message = "草稿已丢弃，所选日期摄入未改变",
                    )
                }
                .onFailure {
                    state = state.copy(
                        isSavingMealDraft = false,
                        message = "无法丢弃草稿；已保留当前内容",
                    )
                }
        }
    }

    fun saveAnalysisConfig(endpoint: String, accessToken: String, onResult: (Boolean) -> Unit) {
        savePhotoService(endpoint, accessToken, AnalysisTransport.MEAL_PROXY, "", onResult)
    }

    fun saveVisionConfig(endpoint: String, model: String, apiKey: String, onResult: (Boolean) -> Unit) {
        val normalized = runCatching { normalizeVisionEndpoint(endpoint) }.getOrElse {
            state = state.copy(message = it.message ?: "服务地址无效")
            onResult(false)
            return
        }
        savePhotoService(normalized, apiKey, AnalysisTransport.VISION_API, model, onResult)
    }

    fun saveAnalysisProfile(id: String?, name: String, endpoint: String, model: String, apiKey: String, onResult: (Boolean) -> Unit) {
        val normalized = runCatching { normalizeVisionEndpoint(endpoint) }.getOrElse {
            state = state.copy(message = it.message ?: "服务地址无效")
            onResult(false)
            return
        }
        updatePhotoServices("服务已保存并启用；没有调用模型", onResult) {
            repository.saveAnalysisProfile(id, name, normalized, model, apiKey)
        }
    }

    fun selectAnalysisProfile(id: String, onResult: (Boolean) -> Unit) {
        updatePhotoServices("已切换识别服务；下一次识别使用新选择，不会自动重新识别已有草稿", onResult) {
            repository.selectAnalysisProfile(id)
        }
    }

    fun deleteAnalysisProfile(id: String, onResult: (Boolean) -> Unit) {
        updatePhotoServices("服务及其密钥已删除；删除当前服务后不会自动启用其他服务", onResult) {
            repository.deleteAnalysisProfile(id)
        }
    }

    fun saveAnalysisAdditionalPrompt(text: String, onResult: (Boolean) -> Unit) {
        updatePhotoServices("附加提示词已保存；下次识别或手动重新识别时使用，不会自动发送照片", onResult) {
            repository.saveAnalysisAdditionalPrompt(text)
        }
    }

    private data class PhotoServiceState(
        val config: AnalysisServiceConfig,
        val profiles: List<AnalysisServiceProfile>,
        val activeId: String?,
        val unreadable: Boolean,
    )

    private fun readPhotoServiceState(): PhotoServiceState {
        val profiles = repository.analysisProfiles()
        return PhotoServiceState(repository.analysisConfig(), profiles, repository.activeAnalysisProfileId(), repository.analysisProfilesUnreadable())
    }

    private fun AppUiState.withPhotoServices(value: PhotoServiceState): AppUiState = copy(
        analysisEndpoint = value.config.endpointUrl,
        analysisTransport = value.config.transport,
        analysisModel = value.config.modelName,
        analysisAdditionalPrompt = value.config.additionalPrompt,
        analysisTokenConfigured = value.config.accessToken.isNotBlank(),
        analysisProfiles = value.profiles,
        activeAnalysisProfileId = value.activeId,
        analysisProfilesUnreadable = value.unreadable,
    )

    private fun updatePhotoServices(successMessage: String, onResult: (Boolean) -> Unit, operation: () -> Unit) {
        if (state.isSavingAnalysisConfig || state.isAnalyzingPhoto || state.isImportingBackup ||
            state.isExportingBackup || state.restoreRecoveryRequired || !state.isInitialized) {
            onResult(false)
            return
        }
        // An older bootstrap snapshot must not undo an explicit service selection.
        fullRefreshId += 1L
        state = state.copy(isSavingAnalysisConfig = true, message = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operation(); readPhotoServiceState() } }
            result.onSuccess { stored ->
                // Also discard refreshes started while this IO save was pending.
                fullRefreshId += 1L
                state = state.withPhotoServices(stored).copy(isSavingAnalysisConfig = false, message = successMessage)
                onResult(true)
            }.onFailure { error ->
                val recovered = runCatching { withContext(Dispatchers.IO) { readPhotoServiceState() } }.getOrNull()
                fullRefreshId += 1L
                state = (recovered?.let { state.withPhotoServices(it) } ?: state).copy(
                    isSavingAnalysisConfig = false,
                    message = if (error is IllegalArgumentException) error.message ?: "识别服务设置无效" else "无法更新识别服务设置；请重试",
                )
                onResult(false)
            }
        }
    }

    private fun savePhotoService(endpoint: String, accessToken: String, transport: AnalysisTransport, model: String, onResult: (Boolean) -> Unit) {
        if (state.isSavingAnalysisConfig || state.isAnalyzingPhoto || state.isImportingBackup) {
            onResult(false)
            return
        }
        val normalizedEndpoint = endpoint.trim()
        analysisEndpointValidationError(normalizedEndpoint)?.let {
            state = state.copy(message = it)
            onResult(false)
            return
        }
        val endpointChanged = normalizedEndpoint != state.analysisEndpoint || transport != state.analysisTransport
        if (normalizedEndpoint.isNotBlank() && accessToken.isBlank() &&
            (endpointChanged || !state.analysisTokenConfigured)
        ) {
            state = state.copy(message = "首次配置或更换服务时必须重新输入 API Key 或令牌")
            onResult(false)
            return
        }
        updatePhotoServices(if (normalizedEndpoint.isBlank()) "已删除当前连接并停用照片上传；其他已保存服务保留"
            else "识别设置已保存，尚未调用模型；回到饮食拍整餐照片即可", onResult) {
            repository.saveAnalysisConfig(normalizedEndpoint, accessToken, transport, model)
        }
    }

    fun clearAnalysisToken(onResult: (Boolean) -> Unit) {
        updatePhotoServices("已删除全部识别服务连接和密钥，照片上传已停用", onResult) {
            repository.clearAnalysisToken()
        }
    }

    fun clearActiveAnalysisConnection(onResult: (Boolean) -> Unit) {
        updatePhotoServices("已删除当前连接并停用照片上传；其他已保存服务保留", onResult) {
            repository.clearActiveAnalysisConnection()
        }
    }

    fun exportEncryptedBackup(destination: Uri, passphrase: CharArray) {
        if (backupWriteInProgress(state)) {
            passphrase.fill('\u0000')
            state = state.copy(message = "请等待当前保存、识别或备份任务完成后再导出")
            return
        }
        state = state.copy(isExportingBackup = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("ledger-backup-", ".encrypted", getApplication<Application>().cacheDir)
                    try {
                        val summary = FileOutputStream(temporary).use { output ->
                            repository.exportEncryptedBackup(output, passphrase)
                        }
                        val resolver = getApplication<Application>().contentResolver
                        val output = requireNotNull(resolver.openOutputStream(destination, "rwt")) {
                            "所选位置无法写入"
                        }
                        output.use { destinationStream ->
                            temporary.inputStream().use { source -> source.copyTo(destinationStream) }
                            destinationStream.flush()
                        }
                        summary
                    } finally {
                        runCatching { temporary.delete() }
                    }
                }
            }
            passphrase.fill('\u0000')
            result.onSuccess { summary ->
                state = state.copy(
                    isExportingBackup = false,
                    message = "加密备份已导出（${formatBackupSize(summary.encryptedBytes)}）；口令不会保存在本机",
                )
            }.onFailure {
                state = state.copy(
                    isExportingBackup = false,
                    message = "导出失败，账本未改变；若目标位置出现不完整文件，请删除后重试",
                )
            }
        }
    }

    fun importEncryptedBackup(source: Uri, passphrase: CharArray) {
        backupRestoreBlockReason(state)?.let { reason ->
            passphrase.fill('\u0000')
            state = state.copy(message = "$reason，再恢复备份")
            return
        }

        // No editor/write operation is allowed to outlive a successful whole-ledger
        // replacement. Generation invalidation also makes stale refresh callbacks inert.
        photoOperationId += 1L
        fullRefreshId += 1L
        currentDateRefreshId += 1L
        workoutLedgerGeneration += 1L
        manualFoodFormGeneration += 1L
        bodyMeasurementFormGeneration += 1L
        workoutEditorGeneration += 1L
        barbellBarWeightGeneration += 1L
        workoutEditorPersistenceJob?.cancel()
        state = state.copy(isImportingBackup = true, message = null)

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openInputStream(source)) { "所选备份文件无法读取" }.use { input ->
                        repository.importEncryptedBackup(input, passphrase)
                    }
                }
            }
            passphrase.fill('\u0000')
            result.onSuccess { restored ->
                state = AppUiState(
                    planEditorEpoch = UUID.randomUUID().toString(),
                    isInitialized = false,
                    // Keep all writes gated until refreshAll has loaded one
                    // complete snapshot from the newly restored generation.
                    restoreRecoveryRequired = true,
                    selectedFoodDate = LocalDate.now(),
                    message = "恢复完成：${restored.restoredRowCounts.values.sum()} 条账本记录、" +
                        "${restored.restoredPhotoCount} 张照片；识别代理需重新配置",
                )
                refreshAll()
            }.onFailure { error ->
                if (error is LedgerRestoreRecoveryRequiredException) {
                    state = AppUiState(
                        isInitialized = true,
                        planEditorEpoch = UUID.randomUUID().toString(),
                        restoreRecoveryRequired = true,
                        message = null,
                    )
                } else {
                    state = state.copy(
                        isImportingBackup = false,
                        message = backupImportFailureMessage(error),
                    )
                }
            }
        }
    }

    fun addCustomExercise(
        name: String,
        category: String,
        trackingType: TrackingType,
        isPrimary: Boolean,
        onResult: (Exercise?) -> Unit,
    ) {
        if (name.isBlank()) {
            onResult(null)
            return
        }
        if (workoutWriteBlocked()) {
            onResult(null)
            return
        }
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val createResult = runCatching {
                withContext(Dispatchers.IO) {
                    repository.addCustomExercise(name, category, trackingType, isPrimary)
                }
            }
            if (createResult.isFailure) {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法创建动作", requireNotNull(createResult.exceptionOrNull()))
                onResult(null)
                return@launch
            }

            val created = createResult.getOrThrow()
            val refreshResult = runCatching {
                withContext(Dispatchers.IO) {
                    val catalog = repository.listExercises(includeArchived = true)
                    val exercises = catalog.filterNot { it.isArchived }
                    Triple(exercises, catalog, loadPrimaryPrSummaries(exercises))
                }
            }
            refreshResult.onSuccess { (exercises, catalog, summaries) ->
                state = state.copy(
                    exercises = exercises,
                    exerciseCatalog = catalog,
                    prSummaries = summaries,
                    isSavingWorkout = false,
                    message = "自定义动作已创建",
                )
                onResult(created)
            }.onFailure {
                // The insert is already durable. Keep the newly created action visible and report
                // only the refresh degradation; treating this as a failed create invites a duplicate retry.
                val mergedExercises = (state.exercises.filterNot { it.id == created.id } + created)
                    .sortedWith(compareByDescending<Exercise> { it.isPrimary }.thenBy { it.name })
                state = state.copy(
                    exercises = mergedExercises,
                    exerciseCatalog = (state.exerciseCatalog.filterNot { it.id == created.id } + created)
                        .sortedWith(compareByDescending<Exercise> { it.isPrimary }.thenBy { it.name }),
                    isSavingWorkout = false,
                    message = "自定义动作已创建；列表刷新不完整，重新打开 App 后会自动恢复",
                )
                onResult(created)
            }
        }
    }

    fun updateCustomExercise(
        exercise: Exercise,
        name: String,
        category: String,
        isPrimary: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        if (!exercise.isCustom || state.activeWorkout != null || workoutWriteBlocked()) {
            state = state.copy(message = "只能在未训练时编辑自定义动作")
            onResult(false)
            return
        }
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.updateCustomExercise(exercise.id, name, category, isPrimary)
                    loadExerciseCatalogOnIo()
                }
            }.onSuccess { refreshed ->
                state = state.copy(
                    exercises = refreshed.active,
                    exerciseCatalog = refreshed.catalog,
                    prSummaries = refreshed.prSummaries,
                    isSavingWorkout = false,
                    message = "自定义动作已更新；历史和 PR 仍保留原动作 ID",
                )
                onResult(true)
                // Rename/category changes must also refresh already materialized
                // history labels and any open detail that references this stable ID.
                refreshAll()
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法更新自定义动作", it)
                onResult(false)
            }
        }
    }

    fun setCustomExerciseArchived(
        exercise: Exercise,
        archived: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        if (!exercise.isCustom || state.activeWorkout != null || workoutWriteBlocked()) {
            state = state.copy(message = "只能在未训练时管理自定义动作")
            onResult(false)
            return
        }
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.setCustomExerciseArchived(exercise.id, archived)
                    loadExerciseCatalogOnIo()
                }
            }.onSuccess { refreshed ->
                state = state.copy(
                    exercises = refreshed.active,
                    exerciseCatalog = refreshed.catalog,
                    prSummaries = refreshed.prSummaries,
                    isSavingWorkout = false,
                    message = if (archived) {
                        "动作已归档；历史、PR 和模板引用均未删除"
                    } else {
                        "动作已恢复，可重新用于训练记录"
                    },
                )
                onResult(true)
                // Template/history labels consume the all-definition catalog;
                // refresh them immediately instead of waiting for a process restart.
                refreshAll()
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure(if (archived) "无法归档自定义动作" else "无法恢复自定义动作", it)
                onResult(false)
            }
        }
    }

    private data class ExerciseCatalogRefresh(
        val active: List<Exercise>,
        val catalog: List<Exercise>,
        val prSummaries: Map<Long, PrSummary>,
    )

    private fun loadExerciseCatalogOnIo(): ExerciseCatalogRefresh {
        val catalog = repository.listExercises(includeArchived = true)
        val active = catalog.filterNot { it.isArchived }
        return ExerciseCatalogRefresh(active, catalog, loadPrimaryPrSummaries(active))
    }

    fun togglePrimary(exercise: Exercise) {
        if (workoutWriteBlocked()) return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.setExercisePrimary(exercise.id, !exercise.isPrimary)
                    loadExerciseCatalogOnIo()
                }
            }.onSuccess { refreshed ->
                state = state.copy(
                    exercises = refreshed.active,
                    exerciseCatalog = refreshed.catalog,
                    prSummaries = refreshed.prSummaries,
                    isSavingWorkout = false,
                )
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法更新主要动作", it)
            }
        }
    }

    fun updateWorkoutEditorDraft(
        expectedSessionId: Long,
        transform: WorkoutEditorTransform,
    ) {
        val current = state.workoutEditorDraft ?: return
        val updated = mergeWorkoutEditorUpdate(current, expectedSessionId, transform) ?: return
        state = state.copy(workoutEditorDraft = updated)
        scheduleWorkoutEditorDraftPersistence(updated)
    }

    private fun scheduleWorkoutEditorDraftPersistence(
        draft: WorkoutEditorDraft,
        delayMillis: Long = WORKOUT_EDITOR_DEBOUNCE_MILLIS,
    ) {
        latestWorkoutEditorRevision = draft.revision
        val generation = ++workoutEditorGeneration
        state = state.copy(
            isAutoSavingWorkoutEditorDraft = true,
            workoutEditorDraftSaveError = null,
        )
        workoutEditorPersistenceJob?.cancel()
        workoutEditorPersistenceJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            val result = runCatching {
                workoutEditorPersistenceMutex.withLock {
                    withContext(Dispatchers.IO) { repository.saveWorkoutEditorDraft(draft) }
                }
            }
            if (generation != workoutEditorGeneration ||
                state.workoutEditorDraft?.sessionId != draft.sessionId ||
                state.workoutEditorDraft?.revision != draft.revision
            ) return@launch
            result.onSuccess { saved ->
                state = state.copy(
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = if (saved) null else "这场训练已结束，旧草稿未重新写入",
                )
            }.onFailure {
                state = state.copy(
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = "未完成组暂未保存到本机；请停留片刻后重试输入",
                )
            }
        }
    }

    /** Best-effort lifecycle flush. Force-stop can still interrupt the final
     * in-flight preference commit, but normal ON_STOP/tab changes do not retain
     * the 300 ms typing debounce window. */
    fun flushWorkoutEditorDraft() {
        val sessionId = state.activeWorkout?.id ?: return
        val draft = state.workoutEditorDraft?.takeIf { it.sessionId == sessionId } ?: return
        scheduleWorkoutEditorDraftPersistence(draft, delayMillis = 0L)
    }

    private fun recoverWorkoutEditorAfterFailedMutation() {
        val recovery = state.prepareWorkoutEditorFailureRecovery()
        state = recovery.state
        recovery.draftToPersist?.let { scheduleWorkoutEditorDraftPersistence(it, delayMillis = 0L) }
    }

    fun startWorkout(title: String = "力量训练") {
        startWorkoutWithPlan(title = title, sourceLabel = null, sourceItems = emptyList())
    }

    fun copyPreviousWorkout() {
        if (workoutWriteBlocked()) return
        if (state.activeWorkout != null) return
        val previous = state.workoutHistory.firstOrNull { it.session.status == com.personal.fitnessledger.data.WorkoutStatus.COMPLETED }
        if (previous == null) {
            state = state.copy(message = "还没有可复制的已完成训练")
            return
        }
        workoutLedgerGeneration += 1L
        state = state.copy(
            selectedWorkoutHistory = null,
            isLoadingWorkoutHistory = false,
            isSavingWorkout = true,
            message = null,
        )
        val exercises = state.exercises
        viewModelScope.launch {
            runCatching {
                val detail = withContext(Dispatchers.IO) { repository.workoutHistoryDetail(previous.session.id) }
                val items = detail.toPlanItems()
                require(items.isNotEmpty()) { "上一场没有可复制的完成组" }
                withContext(Dispatchers.IO) {
                    startWorkoutWithPlanOnIo("复制上一场", "复制 ${previous.recordedLocalDate ?: "上一场"}", items, exercises)
                }
            }.onSuccess { started -> applyStartedWorkout(started, "已复制上一场为新训练计划；计划组尚未计入历史或 PR") }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法复制上一场", it)
                    // The SQLite session may already be durable when the
                    // atomic planner-envelope write fails.
                    refreshAll()
                }
        }
    }

    fun startWorkoutFromTemplate(templateId: String) {
        if (workoutWriteBlocked() || state.activeWorkout != null) return
        val template = state.workoutTemplates.firstOrNull { it.id == templateId }
        if (template == null) {
            state = state.copy(message = "模板已不存在，请刷新后重试")
            return
        }
        val validExerciseIds = state.exercises.map { it.id }.toSet()
        if (template.items.any { it.exerciseId !in validExerciseIds }) {
            state = state.copy(message = "模板含已归档或不可用动作；请先恢复动作或编辑模板后再开始")
            return
        }
        val usableItems = template.items
        workoutLedgerGeneration += 1L
        state = state.copy(
            selectedWorkoutHistory = null,
            isLoadingWorkoutHistory = false,
            isSavingWorkout = true,
            message = null,
        )
        val exercises = state.exercises
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    startWorkoutWithPlanOnIo(template.name, "模板：${template.name}", usableItems, exercises)
                }
            }.onSuccess { started ->
                applyStartedWorkout(
                    started,
                    "已从模板开始；逐组确认完成后才会写入训练记录",
                )
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法从模板开始", it)
                refreshAll()
            }
        }
    }

    private fun startWorkoutWithPlan(
        title: String,
        sourceLabel: String?,
        sourceItems: List<WorkoutPlanItem>,
    ) {
        if (workoutWriteBlocked() || state.activeWorkout != null) return
        workoutLedgerGeneration += 1L
        state = state.copy(
            selectedWorkoutHistory = null,
            isLoadingWorkoutHistory = false,
            isSavingWorkout = true,
            message = null,
        )
        val exercises = state.exercises
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { startWorkoutWithPlanOnIo(title, sourceLabel, sourceItems, exercises) }
            }.onSuccess { started -> applyStartedWorkout(started, null) }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法开始训练", it)
                    // SQLite may already contain the active session if preference
                    // storage failed. A refresh reconciles that recoverable state.
                    refreshAll()
                }
        }
    }

    private fun startWorkoutWithPlanOnIo(
        title: String,
        sourceLabel: String?,
        sourceItems: List<WorkoutPlanItem>,
        exercises: List<Exercise>,
    ): StartedWorkoutPlanning {
        val planItems = sourceItems.map { it.freshCopy() }
        require(planItems.size <= 500) { "训练计划最多包含 500 个计划组" }
        val firstExercise = planItems.firstOrNull()
            ?.let { item -> exercises.firstOrNull { it.id == item.exerciseId } }
            ?: exercises.firstOrNull { it.isPrimary }
            ?: exercises.firstOrNull()
            ?: error("没有可用训练动作")
        val session = repository.startWorkout(title.trim().ifBlank { "力量训练" })
        var draft = newWorkoutEditorDraft(session.id, firstExercise, session.restDurationSeconds)
        val plan = sourceLabel?.let {
            ActiveWorkoutPlan(
                sessionId = session.id,
                sourceLabel = it,
                items = planItems,
                revision = 1L,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }?.takeIf { it.items.isNotEmpty() }
        plan?.items?.firstOrNull()?.let { draft = it.toEditorDraft(draft) }
        val planner = repository.beginWorkoutPlanning(draft, plan)
        return StartedWorkoutPlanning(session, planner)
    }

    private fun applyStartedWorkout(started: StartedWorkoutPlanning, successMessage: String?) {
        workoutLedgerGeneration += 1L
        latestWorkoutEditorRevision = started.planner.editorDraft?.revision ?: 0L
        lastDurableBarbellBarWeightKg = started.planner.barbellBarWeightKg
        state = state.copy(
            activeWorkout = started.session,
            activeSets = emptyList(),
            workoutEditorDraft = started.planner.editorDraft,
            activeWorkoutPlan = started.planner.activePlan,
            workoutTemplates = started.planner.templates,
            barbellBarWeightKg = started.planner.barbellBarWeightKg,
            isAutoSavingWorkoutEditorDraft = false,
            workoutEditorDraftSaveError = null,
            lastPrEvents = emptyList(),
            isSavingWorkout = false,
            message = successMessage,
        )
    }

    fun loadWorkoutPlanItem(itemId: String) {
        mutateActiveWorkoutPlan("无法载入计划项") { plan, current ->
            val item = plan.items.firstOrNull { it.id == itemId }
                ?: throw IllegalArgumentException("计划项已不存在")
            plan to item.toEditorDraft(current)
        }
    }

    fun updateActiveWorkoutPlanItem(item: WorkoutPlanItem) {
        val exercise = state.exercises.firstOrNull { it.id == item.exerciseId }
        if (exercise != null) {
            barbellTotalValidationError(exercise, item.weightKg, state.barbellBarWeightKg)?.let {
                state = state.copy(message = it)
                return
            }
        }
        mutateActiveWorkoutPlan("无法调整计划项") { plan, current ->
            require(plan.items.any { it.id == item.id }) { "计划项已不存在" }
            val now = System.currentTimeMillis()
            val updatedPlan = plan.copy(
                items = plan.items.map { if (it.id == item.id) item else it },
                revision = plan.revision + 1L,
                updatedAtMillis = now.coerceAtLeast(plan.updatedAtMillis + 1L),
            )
            val updatedDraft = if (current.commitId == item.plannedCommitId) item.toEditorDraft(current, now) else current
            updatedPlan to updatedDraft
        }
    }

    fun removeActiveWorkoutPlanItem(itemId: String) {
        mutateActiveWorkoutPlan("无法移除计划项") { plan, current ->
            val removed = plan.items.firstOrNull { it.id == itemId }
                ?: throw IllegalArgumentException("计划项已不存在")
            val now = System.currentTimeMillis()
            val remaining = plan.items.filterNot { it.id == itemId }
            val updatedPlan = plan.copy(
                items = remaining,
                revision = plan.revision + 1L,
                updatedAtMillis = now.coerceAtLeast(plan.updatedAtMillis + 1L),
            )
            val updatedDraft = if (current.commitId == removed.plannedCommitId) current.afterCommittedSet(now) else current
            updatedPlan to updatedDraft
        }
    }

    private fun mutateActiveWorkoutPlan(
        failureAction: String,
        transform: (ActiveWorkoutPlan, WorkoutEditorDraft) -> Pair<ActiveWorkoutPlan, WorkoutEditorDraft>,
    ) {
        if (workoutWriteBlocked()) return
        val plan = state.activeWorkoutPlan ?: return
        val draft = state.workoutEditorDraft ?: return
        val session = state.activeWorkout ?: return
        if (plan.sessionId != session.id || draft.sessionId != session.id) return
        val (updatedPlan, updatedDraft) = runCatching { transform(plan, draft) }
            .getOrElse {
                reportWorkoutFailure(failureAction, it)
                return
            }
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.replaceActiveWorkoutPlanning(updatedPlan.takeIf { it.items.isNotEmpty() }, updatedDraft)
                }
            }.onSuccess { planner ->
                latestWorkoutEditorRevision = planner.editorDraft?.revision ?: latestWorkoutEditorRevision
                state = state.copy(
                    workoutEditorDraft = planner.editorDraft,
                    activeWorkoutPlan = planner.activePlan,
                    workoutTemplates = planner.templates,
                    barbellBarWeightKg = planner.barbellBarWeightKg,
                    isSavingWorkout = false,
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = null,
                )
            }.onFailure {
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure(failureAction, it)
            }
        }
    }

    fun saveWorkoutHistoryAsTemplate(sessionId: Long, name: String) {
        if (state.isSavingWorkout) return
        val cleanName = name.trim()
        if (cleanName.length !in 1..60) {
            state = state.copy(message = "模板名称必须为 1–60 个字符")
            return
        }
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val detail = repository.workoutHistoryDetail(sessionId)
                    val items = detail.toPlanItems()
                    require(items.isNotEmpty()) { "这场训练没有可保存的完成组" }
                    val now = System.currentTimeMillis()
                    repository.saveWorkoutTemplate(
                        WorkoutTemplate(UUID.randomUUID().toString(), cleanName, items, now, now),
                    )
                }
            }.onSuccess { planner ->
                state = state.copy(
                    workoutTemplates = planner.templates,
                    isSavingWorkout = false,
                    message = "模板“$cleanName”已保存；没有新增训练历史或 PR",
                )
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法保存模板", it)
            }
        }
    }

    fun saveActiveWorkoutAsTemplate(name: String) {
        val session = state.activeWorkout ?: return
        val cleanName = name.trim()
        if (cleanName.length !in 1..60) {
            state = state.copy(message = "模板名称必须为 1–60 个字符")
            return
        }
        val committedIds = state.activeSets.map { it.commitId }.toSet()
        val items = state.activeSets.filter { it.completed }.map { it.toPlanItem(session.restDurationSeconds) } +
            state.activeWorkoutPlan?.items.orEmpty()
                .filterNot { it.plannedCommitId in committedIds }
                .map { it.freshCopy() }
        if (items.isEmpty()) {
            state = state.copy(message = "当前还没有可保存的完成组或计划组")
            return
        }
        val now = System.currentTimeMillis()
        saveWorkoutTemplateInternal(
            WorkoutTemplate(UUID.randomUUID().toString(), cleanName, items, now, now),
            "模板“$cleanName”已保存；活动训练内容未被重复记账",
        )
    }

    fun updateWorkoutTemplate(template: WorkoutTemplate) {
        val current = state.workoutTemplates.firstOrNull { it.id == template.id }
        if (current == null) {
            state = state.copy(message = "模板已不存在")
            return
        }
        val now = System.currentTimeMillis()
        saveWorkoutTemplateInternal(
            template.copy(
                name = template.name.trim(),
                createdAtMillis = current.createdAtMillis,
                updatedAtMillis = now.coerceAtLeast(current.updatedAtMillis + 1L),
            ),
            "模板“${template.name.trim()}”已更新",
        )
    }

    private fun saveWorkoutTemplateInternal(template: WorkoutTemplate, successMessage: String) {
        if (state.isSavingWorkout) return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.saveWorkoutTemplate(template) } }
                .onSuccess { planner ->
                    state = state.copy(
                        workoutTemplates = planner.templates,
                        isSavingWorkout = false,
                        message = successMessage,
                    )
                }.onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法保存模板", it)
                }
        }
    }

    fun deleteWorkoutTemplate(templateId: String) {
        if (state.isSavingWorkout) return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.deleteWorkoutTemplate(templateId) } }
                .onSuccess { planner ->
                    state = state.copy(workoutTemplates = planner.templates, isSavingWorkout = false, message = "训练模板已删除")
                }.onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法删除模板", it)
                }
        }
    }

    fun updateBarbellBarWeight(weightKg: Double) {
        if (!weightKg.isFinite() || weightKg !in 0.0..50.0) {
            state = state.copy(message = "杆重必须在 0–50 kg")
            return
        }
        val generation = ++barbellBarWeightGeneration
        // Reflect valid text immediately, but coalesce ordinary typing before
        // touching disk. The serialized writer tracks every completed durable
        // value so a failed newest write can roll the UI back to disk truth.
        state = state.copy(barbellBarWeightKg = weightKg)
        viewModelScope.launch {
            delay(WORKOUT_EDITOR_DEBOUNCE_MILLIS)
            val result = runCatching {
                barbellBarWeightPersistenceMutex.withLock {
                    if (generation != barbellBarWeightGeneration) return@withLock null
                    withContext(Dispatchers.IO) { repository.saveBarbellBarWeight(weightKg) }
                }
            }
            result.getOrNull()?.let { planner ->
                if (generation >= lastDurableBarbellGeneration) {
                    lastDurableBarbellBarWeightKg = planner.barbellBarWeightKg
                    lastDurableBarbellGeneration = generation
                }
            }
            if (generation != barbellBarWeightGeneration) return@launch
            result.onSuccess { planner ->
                if (planner != null) state = state.copy(barbellBarWeightKg = planner.barbellBarWeightKg)
            }.onFailure {
                state = state.copy(
                    barbellBarWeightKg = lastDurableBarbellBarWeightKg,
                    message = "无法保存杆重设置；已恢复上次保存的 ${lastDurableBarbellBarWeightKg} kg",
                )
            }
        }
    }

    fun addSet(exercise: Exercise, input: WorkoutSetInput) {
        if (state.isSavingWorkout || state.unacknowledgedWorkoutSetCommitId != null ||
            state.unacknowledgedFiveByFiveCommitId != null
        ) return
        input.validationError(exercise.trackingType)?.let {
            state = state.copy(message = it)
            return
        }
        barbellTotalValidationError(exercise, input.weightKg, state.barbellBarWeightKg)?.let {
            state = state.copy(message = it)
            return
        }
        val existingSession = state.activeWorkout ?: return
        val editorAtSubmit = state.workoutEditorDraft
            ?.takeIf { it.sessionId == existingSession.id && it.commitId == input.commitId }
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val writeResult = runCatching {
                withContext(Dispatchers.IO) {
                    editorAtSubmit?.let { repository.saveWorkoutEditorDraft(it) }
                    repository.prepareSingleSetCommit(existingSession.id, exercise.id, input)
                    repository.commitPendingSingleSet()
                }
            }
            writeResult.exceptionOrNull()?.let { error ->
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure("无法记录该组", error)
                return@launch
            }
            val committed = requireNotNull(writeResult.getOrNull())
            val session = committed.session
            val inserted = committed.set

            val refreshedSets = runCatching {
                withContext(Dispatchers.IO) { repository.setsForSession(session.id) }
            }
            val sets = refreshedSets.getOrElse {
                (state.activeSets.filterNot { it.id == inserted.id } + inserted)
                    .sortedWith(compareBy<WorkoutSet> { it.exerciseId }.thenBy { it.setOrder })
            }
            val nextDraft = editorAtSubmit?.afterCommittedSet()
            val plannerResult = nextDraft?.let { next ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.completeWorkoutDraftSubmission(session.id, input.commitId, next)
                    }
                }
            }
            val planner = plannerResult?.getOrNull()
            val locallyReconciledPlan = state.activeWorkoutPlan?.let { plan ->
                plan.copy(items = plan.items.filterNot { it.plannedCommitId == input.commitId })
                    .takeIf { it.items.isNotEmpty() }
            }
            latestWorkoutEditorRevision = planner?.editorDraft?.revision
                ?: nextDraft?.revision
                ?: latestWorkoutEditorRevision
            val savedMessage = if (input.completed) "已记录完成组" else "已记录失败组；不会计入 PR"
            val message = when {
                refreshedSets.isFailure ->
                    "$savedMessage；列表刷新失败，重新打开 App 可恢复"
                plannerResult?.isFailure == true ->
                    "$savedMessage；未完成组草稿刷新失败，重新打开 App 会按已写入组安全对账"
                else -> "$savedMessage；正在确认本次提交，请勿连续点击"
            }
            state = state.copy(
                activeWorkout = session,
                activeSets = sets,
                workoutEditorDraft = if (planner != null) planner.editorDraft else nextDraft ?: state.workoutEditorDraft,
                activeWorkoutPlan = if (planner != null) planner.activePlan else locallyReconciledPlan,
                isSavingWorkout = false,
                isAutoSavingWorkoutEditorDraft = false,
                workoutEditorDraftSaveError = if (plannerResult?.isFailure == true) "训练组已保存；表单将在重启时自动对账" else null,
                workoutSetSaveRevision = state.workoutSetSaveRevision + 1L,
                unacknowledgedWorkoutSetCommitId = committed.pending.input.commitId,
                message = message,
            )
        }
    }

    fun acknowledgeSingleSetCommit(commitId: String) {
        if (state.unacknowledgedWorkoutSetCommitId != commitId) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.acknowledgeSingleSetCommit(commitId) } }
                .onSuccess {
                    if (state.unacknowledgedWorkoutSetCommitId == commitId) {
                        state = state.copy(
                            unacknowledgedWorkoutSetCommitId = null,
                            message = "上一组已确认保存，可以记录下一组",
                        )
                    }
                }
                .onFailure {
                    state = state.copy(message = "该组已经写入，但提交确认失败；重新打开 App 可安全恢复")
                }
        }
    }

    fun startRestTimer(durationSeconds: Int) {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.startRestTimer(session.id, durationSeconds) } }
                .onSuccess { updated -> state = state.copy(activeWorkout = updated, isSavingWorkout = false) }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法开始休息计时", it)
                }
        }
    }

    fun clearRestTimer() {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.clearRestTimer(session.id) } }
                .onSuccess { updated -> state = state.copy(activeWorkout = updated, isSavingWorkout = false) }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法结束休息计时", it)
                }
        }
    }

    fun addFiveByFive(exercise: Exercise, weightKg: Double, batchId: String) {
        if (batchId in acknowledgedFiveByFiveBatchIds) return
        if (workoutWriteBlocked()) return
        val editorCommitId = state.workoutEditorDraft?.commitId
        if (state.activeWorkoutPlan?.items?.any { it.plannedCommitId == editorCommitId } == true) {
            state = state.copy(message = "已载入的计划组请逐组记录，避免 5×5 批量操作留下重复计划项")
            return
        }
        if (!weightKg.isFinite() || weightKg !in 0.0..1_000.0 ||
            (exercise.trackingType == TrackingType.WEIGHT_REPS && weightKg <= 0.0)
        ) {
            state = state.copy(message = "无法记录 5×5：请检查重量")
            return
        }
        barbellTotalValidationError(exercise, weightKg, state.barbellBarWeightKg)?.let {
            state = state.copy(message = "无法记录 5×5：$it")
            return
        }
        if (batchId.isBlank() || batchId.length > WORKOUT_BATCH_ID_MAX_LENGTH || batchId.contains(':')) {
            state = state.copy(message = "无法记录 5×5：提交标识无效，请重新打开训练页后再试")
            return
        }
        val existingSession = state.activeWorkout ?: return
        val loadGrams = (weightKg * 1000.0).roundToLong()
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val writeResult = runCatching {
                withContext(Dispatchers.IO) {
                    repository.prepareFiveByFiveCommit(
                        sessionId = existingSession.id,
                        exerciseId = exercise.id,
                        loadGrams = loadGrams,
                        batchId = batchId,
                    )
                    repository.commitPendingFiveByFive()
                }
            }
            writeResult.exceptionOrNull()?.let { error ->
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法记录 5×5", error)
                return@launch
            }
            val committed = requireNotNull(writeResult.getOrNull())
            val session = committed.session
            val inserted = committed.sets
            val batchCommitId = committed.pending.batchId
            val refreshedSets = runCatching {
                withContext(Dispatchers.IO) { repository.setsForSession(session.id) }
            }
            val sets = refreshedSets.getOrElse {
                (state.activeSets + inserted).distinctBy { it.id }
                    .sortedWith(compareBy<WorkoutSet> { it.exerciseId }.thenBy { it.setOrder })
            }
            state = state.copy(
                activeWorkout = session,
                activeSets = sets,
                isSavingWorkout = false,
                unacknowledgedFiveByFiveCommitId = batchCommitId,
                message = if (refreshedSets.isSuccess) {
                    "5×5 已安全记录为一批；确认回执后可继续或撤销整批"
                } else {
                    "5×5 已记录；列表刷新失败，重新打开 App 可恢复，请先确认回执"
                },
            )
        }
    }

    fun acknowledgeFiveByFiveCommit(batchId: String) {
        if (state.unacknowledgedFiveByFiveCommitId != batchId) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.acknowledgeFiveByFiveCommit(batchId) } }
                .onSuccess {
                    if (state.unacknowledgedFiveByFiveCommitId == batchId) {
                        acknowledgedFiveByFiveBatchIds += batchId
                        if (acknowledgedFiveByFiveBatchIds.size > 128) {
                            acknowledgedFiveByFiveBatchIds.remove(acknowledgedFiveByFiveBatchIds.first())
                        }
                        state = state.copy(
                            unacknowledgedFiveByFiveCommitId = null,
                            message = "5×5 已确认保存，可以继续记录；误加时可撤销整批",
                        )
                    }
                }
                .onFailure {
                    state = state.copy(message = "5×5 已写入，但提交确认失败；重新打开 App 可安全恢复")
                }
        }
    }

    fun updateSet(set: WorkoutSet) {
        if (workoutWriteBlocked()) return
        state.exercises.firstOrNull { it.id == set.exerciseId }?.let { exercise ->
            barbellTotalValidationError(exercise, set.weightKg, state.barbellBarWeightKg)?.let {
                state = state.copy(message = it)
                return
            }
        }
        val session = state.activeWorkout ?: return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.updateWorkoutSet(set)
                    repository.setsForSession(session.id)
                }
            }.onSuccess { sets -> state = state.copy(activeSets = sets, isSavingWorkout = false) }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法修改该组", it)
                }
        }
    }

    fun deleteSet(setId: Long) {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.deleteWorkoutSet(setId) to repository.setsForSession(session.id)
                }
            }.onSuccess { (deleted, sets) ->
                state = state.copy(
                    activeSets = sets,
                    isSavingWorkout = false,
                    message = if (deleted) "该组已删除" else "该组已不在进行中的训练里",
                )
            }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法删除该组", it)
                }
        }
    }

    fun deleteSetBatch(batchId: String) {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.deleteWorkoutSetBatch(batchId) to repository.setsForSession(session.id)
                }
            }.onSuccess { (deleted, sets) ->
                state = state.copy(
                    activeSets = sets,
                    isSavingWorkout = false,
                    message = if (deleted > 0) "该批 5×5 已撤销" else "该批记录已不存在",
                )
            }
                .onFailure {
                    state = state.copy(isSavingWorkout = false)
                    reportWorkoutFailure("无法撤销 5×5", it)
                }
        }
    }

    fun cancelWorkout() {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        if (session.correctionOfSessionId != null) {
            abandonActiveWorkoutCorrection(session)
            return
        }
        val wasEmpty = state.activeSets.isEmpty()
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val cancelResult = runCatching {
                withContext(Dispatchers.IO) {
                    repository.cancelWorkout(session.id)
                    runCatching { repository.clearWorkoutPlanning(session.id) }
                }
            }
            cancelResult.onFailure {
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure("无法取消训练", it)
            }
            cancelResult.onSuccess {
                val ledgerGeneration = ++workoutLedgerGeneration
                state = state.copy(
                    activeWorkout = null,
                    activeSets = emptyList(),
                    workoutEditorDraft = null,
                    activeWorkoutPlan = null,
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = null,
                    isSavingWorkout = false,
                    message = if (wasEmpty) "空训练已丢弃，未生成训练历史" else "本次训练已取消，不会计算 PR",
                )
                runCatching { withContext(Dispatchers.IO) { loadWorkoutHistoryPage() } }
                    .onSuccess { (history, hasMore) ->
                        if (ledgerGeneration != workoutLedgerGeneration) return@onSuccess
                        state = state.copy(workoutHistory = history, hasMoreWorkoutHistory = hasMore)
                    }
                    .onFailure {
                        if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                        state = state.copy(message = "训练已取消，但历史刷新失败；重新打开 App 即可看到结果")
                    }
            }
        }
    }

    fun completeWorkout() {
        if (workoutWriteBlocked()) return
        val session = state.activeWorkout ?: return
        if (session.correctionOfSessionId != null) {
            commitActiveWorkoutCorrection(session)
            return
        }
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val completeResult = runCatching {
                withContext(Dispatchers.IO) {
                    val completion = repository.completeWorkout(session.id)
                    runCatching { repository.clearWorkoutPlanning(session.id) }
                    completion to repository.completedWorkoutsForDate(completion.recordedLocalDate)
                }
            }
            completeResult.onFailure {
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure("无法完成训练", it)
            }
            completeResult.onSuccess { (completion, completedForRecordedDate) ->
                // Invalidate an older resume/midnight query so it cannot replace
                // this just-committed completion with a stale empty Today card.
                currentDateRefreshId += 1L
                val ledgerGeneration = ++workoutLedgerGeneration
                val loadedDateBeforeCompletion = state.loadedDate
                val currentCalendarDate = LocalDate.now()
                val needsCalendarRefresh = loadedDateBeforeCompletion != currentCalendarDate ||
                    completion.recordedLocalDate != loadedDateBeforeCompletion
                state = state.copy(
                    activeWorkout = null,
                    activeSets = emptyList(),
                    workoutEditorDraft = null,
                    activeWorkoutPlan = null,
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = null,
                    // Never pair a D+1 workout list with a D title/nutrition
                    // snapshot. A forced calendar refresh below atomically
                    // replaces all date-bound Today fields when dates differ.
                    todayCompletedWorkouts = completedWorkoutsForLoadedDate(
                        loadedDate = loadedDateBeforeCompletion,
                        recordedDate = completion.recordedLocalDate,
                        current = state.todayCompletedWorkouts,
                        completedForRecordedDate = completedForRecordedDate,
                    ),
                    isSavingWorkout = false,
                    message = if (completion.personalRecords.isNotEmpty()) "训练已完成，PR 已更新" else "训练已完成",
                )
                if (needsCalendarRefresh) refreshForCurrentDate(force = true)
                val exercisesForPr = state.exercises
                runCatching {
                    withContext(Dispatchers.IO) {
                        Triple(
                            repository.latestPrEvents(),
                            loadWorkoutHistoryPage(),
                            loadPrimaryPrSummaries(exercisesForPr),
                        )
                    }
                }.onSuccess { (latestPrEvents, historyPage, summaries) ->
                    if (ledgerGeneration != workoutLedgerGeneration) return@onSuccess
                    state = state.copy(
                        lastPrEvents = latestPrEvents,
                        workoutHistory = historyPage.first,
                        hasMoreWorkoutHistory = historyPage.second,
                        prSummaries = summaries,
                    )
                }.onFailure {
                    if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                    state = state.copy(message = "训练已完成且 PR 已计算，但历史刷新失败；重新打开 App 即可看到结果")
                }
            }
        }
    }

    private fun abandonActiveWorkoutCorrection(session: WorkoutSession) {
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.abandonWorkoutCorrection(session.id)
                    runCatching { repository.clearWorkoutPlanning(session.id) }
                }
            }.onSuccess {
                workoutLedgerGeneration += 1L
                state = state.copy(
                    activeWorkout = null,
                    activeSets = emptyList(),
                    workoutEditorDraft = null,
                    activeWorkoutPlan = null,
                    workoutCorrectionOriginal = null,
                    isAutoSavingWorkoutEditorDraft = false,
                    workoutEditorDraftSaveError = null,
                    isSavingWorkout = false,
                    message = "更正草稿已放弃；原完成记录和 PR 未改变",
                )
            }.onFailure {
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure("无法放弃更正", it)
            }
        }
    }

    private fun commitActiveWorkoutCorrection(session: WorkoutSession) {
        workoutEditorPersistenceJob?.cancel()
        workoutEditorGeneration += 1L
        val loadedDate = state.loadedDate
        val exercises = state.exercises
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val commitResult = runCatching {
                withContext(Dispatchers.IO) {
                    val committed = repository.commitWorkoutCorrection(session.id)
                    runCatching { repository.clearWorkoutPlanning(session.id) }
                    committed
                }
            }
            commitResult.onFailure {
                recoverWorkoutEditorAfterFailedMutation()
                reportWorkoutFailure("无法保存更正", it)
                return@launch
            }
            val committed = requireNotNull(commitResult.getOrNull())
            // The destructive write is now durable. Clear the correction UI before
            // any fallible readback so a refresh failure cannot invite a duplicate retry.
            val ledgerGeneration = ++workoutLedgerGeneration
            currentDateRefreshId += 1L
            state = state.afterDurableWorkoutCorrection(committed.sessionId, committed.correctionRevision)
            runCatching {
                withContext(Dispatchers.IO) {
                    loadWorkoutLedgerRefresh(
                        loadedDate = loadedDate,
                        exercises = exercises,
                        selectedSessionId = committed.sessionId,
                    )
                }
            }.onSuccess { refresh ->
                if (ledgerGeneration != workoutLedgerGeneration) return@onSuccess
                state = state.copy(
                    selectedWorkoutHistory = refresh.selectedDetail,
                    todayCompletedWorkouts = refresh.todayCompletedWorkouts,
                    workoutHistory = refresh.workoutHistory,
                    hasMoreWorkoutHistory = refresh.hasMoreWorkoutHistory,
                    lastPrEvents = refresh.lastPrEvents,
                    prSummaries = refresh.prSummaries,
                    isSavingWorkout = false,
                    message = "更正已保存（第 ${committed.correctionRevision} 次）；已确定性重建 ${committed.rebuiltPrEventCount} 条 PR",
                )
            }.onFailure {
                if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                state = state.copy(
                    isSavingWorkout = false,
                    message = "更正已保存且 PR 已重建，但界面刷新不完整；重新打开 App 可恢复，勿重复提交",
                )
            }
        }
    }

    fun prSummary(exerciseId: Long): PrSummary =
        state.prSummaries[exerciseId] ?: PrSummary(exerciseId, emptyList(), null)

    /** Exposed for the history UI to request the next bounded database page. */
    fun loadMoreWorkoutHistory() {
        if (state.isLoadingMoreWorkoutHistory || !state.hasMoreWorkoutHistory) return
        val offset = state.workoutHistory.size
        val ledgerGeneration = workoutLedgerGeneration
        state = state.copy(isLoadingMoreWorkoutHistory = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { loadWorkoutHistoryPage(offset) } }
                .onSuccess { (page, hasMore) ->
                    if (ledgerGeneration != workoutLedgerGeneration) return@onSuccess
                    state = state.copy(
                        workoutHistory = (state.workoutHistory + page).distinctBy { it.session.id },
                        hasMoreWorkoutHistory = hasMore,
                        isLoadingMoreWorkoutHistory = false,
                    )
                }
                .onFailure {
                    if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                    state = state.copy(
                        isLoadingMoreWorkoutHistory = false,
                        message = "无法继续加载训练历史，请稍后重试",
                    )
                }
        }
    }

    fun startWorkoutCorrection(sessionId: Long) {
        if (workoutWriteBlocked()) return
        if (state.activeWorkout != null) {
            state = state.copy(message = "请先完成或取消当前进行中的训练")
            return
        }
        val original = state.selectedWorkoutHistory
            ?.takeIf { it.summary.session.id == sessionId }
            ?: return
        workoutLedgerGeneration += 1L
        val exercises = state.exercises
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val correction = repository.startWorkoutCorrection(sessionId)
                    val sets = repository.setsForSession(correction.id)
                    val preferredExerciseId = sets.firstOrNull()?.exerciseId
                    val exercise = exercises.firstOrNull { it.id == preferredExerciseId }
                        ?: exercises.firstOrNull { it.isPrimary }
                        ?: exercises.firstOrNull()
                        ?: error("动作库为空，无法创建更正编辑器")
                    // SQLite creation is already durable here. Planner failure must
                    // not turn the active correction into a UI-invisible ghost.
                    val planner = runCatching {
                        repository.beginWorkoutPlanning(
                            newWorkoutEditorDraft(
                                sessionId = correction.id,
                                exercise = exercise,
                                restSeconds = correction.restDurationSeconds,
                            ),
                            null,
                        )
                    }.getOrNull()
                    Triple(correction, sets, planner)
                }
            }
            result.onSuccess { (correction, sets, planner) ->
                workoutLedgerGeneration += 1L
                val editor = planner?.editorDraft
                latestWorkoutEditorRevision = editor?.revision ?: 0L
                state = state.withVisibleWorkoutCorrection(correction, sets, planner, original)
                if (planner == null) refreshAll()
            }.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法开始更正", it)
            }
        }
    }

    fun deleteWorkoutHistorySession(sessionId: Long) {
        if (workoutWriteBlocked()) return
        if (state.activeWorkout != null) {
            state = state.copy(message = "请先完成或取消当前进行中的训练")
            return
        }
        if (state.selectedWorkoutHistory?.summary?.session?.id != sessionId) return
        val loadedDate = state.loadedDate
        val exercises = state.exercises
        state = state.copy(isSavingWorkout = true, message = null)
        viewModelScope.launch {
            val deleteResult = runCatching {
                withContext(Dispatchers.IO) {
                    repository.deleteWorkoutHistorySession(sessionId)
                }
            }
            deleteResult.onFailure {
                state = state.copy(isSavingWorkout = false)
                reportWorkoutFailure("无法删除整场训练", it)
                return@launch
            }
            val deleted = requireNotNull(deleteResult.getOrNull())
            // Deletion is durable; remove the stale detail before fallible refresh.
            val ledgerGeneration = ++workoutLedgerGeneration
            currentDateRefreshId += 1L
            state = state.afterDurableWorkoutHistoryDelete(deleted.sessionId)
            runCatching {
                withContext(Dispatchers.IO) { loadWorkoutLedgerRefresh(loadedDate, exercises) }
            }.onSuccess { refresh ->
                if (ledgerGeneration != workoutLedgerGeneration) return@onSuccess
                state = state.copy(
                    todayCompletedWorkouts = refresh.todayCompletedWorkouts,
                    workoutHistory = refresh.workoutHistory,
                    hasMoreWorkoutHistory = refresh.hasMoreWorkoutHistory,
                    lastPrEvents = refresh.lastPrEvents,
                    prSummaries = refresh.prSummaries,
                    isSavingWorkout = false,
                    message = "整场训练已删除；已确定性重建 ${deleted.rebuiltPrEventCount} 条 PR",
                )
            }.onFailure {
                if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                state = state.copy(
                    isSavingWorkout = false,
                    message = "整场训练已删除且 PR 已重建，但界面刷新不完整；重新打开 App 可恢复，勿重复删除",
                )
            }
        }
    }

    fun openWorkoutHistory(sessionId: Long) {
        if (state.isLoadingWorkoutHistory || state.activeWorkout != null) return
        val ledgerGeneration = workoutLedgerGeneration
        state = state.copy(isLoadingWorkoutHistory = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.workoutHistoryDetail(sessionId) } }
                .onSuccess { detail ->
                    if (ledgerGeneration != workoutLedgerGeneration || state.activeWorkout != null) return@onSuccess
                    state = state.copy(selectedWorkoutHistory = detail, isLoadingWorkoutHistory = false)
                }
                .onFailure {
                    if (ledgerGeneration != workoutLedgerGeneration) return@onFailure
                    state = state.copy(isLoadingWorkoutHistory = false, message = "无法读取训练历史详情")
                }
        }
    }

    fun closeWorkoutHistory() {
        workoutLedgerGeneration += 1L
        state = state.copy(selectedWorkoutHistory = null, isLoadingWorkoutHistory = false)
    }

    private fun reportWorkoutFailure(action: String, error: Throwable) {
        val detail = if (error is IllegalArgumentException) error.message ?: "请检查输入后重试" else "数据未改变，请稍后重试"
        state = state.copy(message = "$action：$detail")
    }

    private fun workoutWriteBlocked(): Boolean {
        if (state.isImportingBackup || state.restoreRecoveryRequired) {
            state = state.copy(message = RESTORE_RECOVERY_REQUIRED_MESSAGE)
            return true
        }
        if (state.isSavingWorkout) return true
        if (state.unacknowledgedFiveByFiveCommitId != null) {
            state = state.copy(message = "上一批 5×5 已经写入；请先确认保存结果，再开始下一项操作")
            return true
        }
        if (state.unacknowledgedWorkoutSetCommitId != null) {
            state = state.copy(message = "上一组已经写入；请先确认保存结果，再开始下一项操作")
            return true
        }
        return false
    }

    private fun mealDraftMutationLocked(): Boolean =
        state.isImportingBackup || state.restoreRecoveryRequired ||
            state.isAnalyzingPhoto || state.isSavingMealDraft || state.isCommittingMeal

    private fun loadFoodLedgerSnapshot(selectedDate: LocalDate): FoodLedgerSnapshot {
        val today = LocalDate.now()
        return FoodLedgerSnapshot(
            today = today,
            todayNutrition = repository.nutritionForDate(today),
            todayMeals = repository.mealsForDate(today),
            todayCompletedWorkouts = repository.completedWorkoutsForDate(today),
            selectedDate = selectedDate,
            selectedNutrition = repository.nutritionForDate(selectedDate),
            selectedMeals = repository.mealsForDate(selectedDate),
            savedFoods = repository.listSavedFoods(),
        )
    }

    /** Must be called from Dispatchers.IO. One extra row determines whether another page exists. */
    private fun loadWorkoutHistoryPage(offset: Int = 0): Pair<List<WorkoutHistorySummary>, Boolean> {
        val rows = repository.listWorkoutHistory(HISTORY_PAGE_SIZE + 1, offset)
        return rows.take(HISTORY_PAGE_SIZE) to (rows.size > HISTORY_PAGE_SIZE)
    }

    /** Must be called from Dispatchers.IO; Compose receives only the immutable result map. */
    private fun loadPrimaryPrSummaries(exercises: List<Exercise>): Map<Long, PrSummary> =
        exercises.asSequence()
            .filter { it.isPrimary }
            .map { it.id }
            .associateWith(repository::prSummary)

    /** Must be called from Dispatchers.IO after an atomic correction/delete. */
    private fun loadWorkoutLedgerRefresh(
        loadedDate: LocalDate,
        exercises: List<Exercise>,
        selectedSessionId: Long? = null,
    ): WorkoutLedgerRefresh {
        val history = loadWorkoutHistoryPage()
        return WorkoutLedgerRefresh(
            todayCompletedWorkouts = repository.completedWorkoutsForDate(loadedDate),
            workoutHistory = history.first,
            hasMoreWorkoutHistory = history.second,
            lastPrEvents = repository.latestPrEvents(),
            prSummaries = loadPrimaryPrSummaries(exercises),
            selectedDetail = selectedSessionId?.let(repository::workoutHistoryDetail),
        )
    }

    private fun persistDraftUpdate(
        previous: MealDraft,
        updated: MealDraft,
        onResult: (Boolean) -> Unit = {},
    ) {
        state = state.copy(isSavingMealDraft = true, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.saveDraft(updated) } }
                .onSuccess {
                    state = state.copy(mealDraft = updated, isSavingMealDraft = false)
                    onResult(true)
                }
                .onFailure {
                    state = state.copy(
                        mealDraft = previous,
                        isSavingMealDraft = false,
                        message = "无法保存草稿修改；已保留上一次内容",
                    )
                    onResult(false)
                }
        }
    }

    private fun recalculateDraft(draft: MealDraft): MealDraft {
        val risks = draft.items.flatMap { it.riskFlags }.toSet()
        val tier = strongestEvidence(draft.items)
        return draft.copy(
            state = DraftState.EDITING,
            evidenceTier = tier,
            evidenceReason = if (draft.items.any { it.userModified }) {
                "包含用户修改项；请再次核对食物身份、克重、烹调油和营养来源"
            } else draft.evidenceReason,
            unresolvedFlags = risks,
            userReviewed = false,
        )
    }
}

private fun PhotoStorage.PendingCameraPhase?.isResumableCameraPhase(): Boolean =
    this == PhotoStorage.PendingCameraPhase.RESULT_RECEIVED || this == PhotoStorage.PendingCameraPhase.ANALYZING
