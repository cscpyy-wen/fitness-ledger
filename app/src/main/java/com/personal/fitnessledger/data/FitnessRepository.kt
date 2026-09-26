package com.personal.fitnessledger.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToLong

private fun SharedPreferences.Editor.restoreString(
    key: String,
    previousValue: String?,
): SharedPreferences.Editor = if (previousValue == null) remove(key) else putString(key, previousValue)

internal const val REST_TIMER_WALL_CLOCK_TOLERANCE_MILLIS = 5_000L

internal fun persistedRestTimerRequiresReset(
    session: WorkoutSession,
    nowMillis: Long,
): Boolean {
    val endAt = session.restTimerEndAtMillis ?: return false
    if (endAt <= nowMillis) return false
    val remaining = endAt - nowMillis
    val safeRemaining = if (remaining < 0L) Long.MAX_VALUE else remaining
    val configuredMaximum = session.restDurationSeconds.coerceIn(15, 3_600) * 1_000L
    return safeRemaining > configuredMaximum + REST_TIMER_WALL_CLOCK_TOLERANCE_MILLIS
}

class FitnessRepository(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    init {
        // A password restore spans SQLite, SharedPreferences and the durable
        // photo directory. Finish/rollback its persisted journal before any
        // long-lived database handle can observe an intermediate generation.
        // A failed startup recovery keeps its durable transaction directory.
        // Construct the repository so the ViewModel can show a fail-closed
        // recovery screen, but do not permit any mutation until a later startup
        // successfully reconciles that directory.
        runCatching { LedgerBackupManager.recoverInterruptedRestore(appContext) }
    }
    private val databaseDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FitnessDatabase(appContext) }
    private val database by databaseDelegate
    override fun close() { if(databaseDelegate.isInitialized()) database.close() }
    private val preferences = appContext.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
    private val analysisServices = AnalysisServiceProfilesStore(preferences)
    private val workoutPlannerStore = WorkoutPlannerStore(appContext)

    internal fun restoreRecoveryRequired(): Boolean =
        LedgerBackupManager.restoreRecoveryRequired(appContext)

    internal fun recoverInterruptedRestore() {
        LedgerBackupManager.recoverInterruptedRestore(appContext)
    }

    private fun <T> ledgerWrite(block: () -> T): T =
        LedgerBackupManager.withLedgerReadyAccess(appContext, block)

    fun getProfile(): UserProfile = database.getProfile()
    fun updateProfile(profile: UserProfile) = ledgerWrite { database.updateProfile(profile) }

    fun addBodyMeasurement(measurement: BodyMeasurement): Long = ledgerWrite {
        measurement.validationError()?.let { throw IllegalArgumentException(it) }
        database.saveBodyMeasurement(measurement)
    }
    fun saveBodyMeasurement(measurement: BodyMeasurement, formCommitId: String? = null): Long = ledgerWrite {
        if (formCommitId == null) {
            measurement.validationError()?.let { throw IllegalArgumentException(it) }
        }
        database.saveBodyMeasurement(measurement, formCommitId)
    }
    fun deleteBodyMeasurement(measurementId: Long): Boolean = ledgerWrite {
        database.deleteBodyMeasurement(measurementId)
    }
    fun listBodyMeasurements(): List<BodyMeasurement> = ledgerWrite {
        (database.listBodyMeasurements() + XiaomiWeightLedger.list(database.readableDatabase))
            .sortedWith(compareBy<BodyMeasurement> { it.date }.thenBy { it.cloudMeasuredAtSeconds ?: Long.MAX_VALUE }.thenBy { it.id })
    }

    fun xiaomiLedgerStatus(): XiaomiLedgerStatus = ledgerWrite { XiaomiWeightLedger.status(database.readableDatabase) }
    fun importXiaomiWeights(account: String, result: com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult, now: Long): XiaomiImportResult =
        ledgerWrite { XiaomiWeightLedger.import(database.writableDatabase, account, result, now) }
    fun hideXiaomiWeight(id: Long): Boolean = ledgerWrite { XiaomiWeightLedger.hide(database.writableDatabase,id) }

    fun bodyMeasurementFormDraft(): BodyMeasurementFormDraft? = ledgerWrite {
        val draft = synchronized(preferences) {
            val current = bodyMeasurementFormDraftLocked()
            // Keep the raw-form observation and SQLite reconciliation under the
            // same process lock. Otherwise a concurrent draft save could land
            // between a null read and confirm-all, losing its replay receipt.
            runCatching {
                database.reconcileBodyMeasurementCommitReceipts(protectedRawFormId = current?.id)
            }
            current
        }
        if (draft == null) return@ledgerWrite null
        if (database.bodyMeasurementCommitId(draft.id) == null) {
            return@ledgerWrite draft
        }
        // SQLite is authoritative. A process death after the atomic ledger write
        // must not reopen an editable measurementId=0 form and allow a duplicate.
        runCatching {
            clearBodyMeasurementFormDraft(draft.id, draft.shortcutRequestId)
        }
        // On failure the raw form is either restored under the preference lock,
        // or another form has replaced it. Never reconcile again from this stale
        // snapshot outside that lock.
        null
    }

    /** Persists the latest raw editor revision synchronously on the caller's IO context. */
    fun saveBodyMeasurementFormDraft(draft: BodyMeasurementFormDraft): Boolean = ledgerWrite {
        draft.persistenceValidationError()?.let { throw IllegalArgumentException(it) }
        synchronized(preferences) {
            if (preferences.getString(KEY_CLEARED_BODY_MEASUREMENT_FORM_ID, null) == draft.id) {
                return@synchronized false
            }
            val current = bodyMeasurementFormDraftLocked()
            require(current == null || current.id == draft.id) {
                "已有另一份未完成的身体记录"
            }
            if (current != null && current.revision > draft.revision) return@synchronized false
            check(
                preferences.edit()
                    .putString(KEY_BODY_MEASUREMENT_FORM, encodeBodyMeasurementFormDraft(draft))
                    .commit(),
            ) { "无法持久化身体表单草稿" }
            true
        }
    }

    /** Clears only the named editor and tombstones it against a late async write. */
    fun clearBodyMeasurementFormDraft(
        formId: String,
        resolvedShortcutRequestId: String? = null,
    ): Boolean = ledgerWrite {
        require(formId.isNotBlank() && formId.length <= 128)
        require(resolvedShortcutRequestId == null ||
            (resolvedShortcutRequestId.isNotBlank() && resolvedShortcutRequestId.length <= 128))
        val cleared = synchronized(preferences) {
            val current = bodyMeasurementFormDraftLocked()
            if (current != null && current.id != formId) return@synchronized false
            val previousRawForm = preferences.getString(KEY_BODY_MEASUREMENT_FORM, null)
            val previousClearedFormId =
                preferences.getString(KEY_CLEARED_BODY_MEASUREMENT_FORM_ID, null)
            val previousResolvedShortcutId =
                preferences.getString(KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID, null)
            val editor = preferences.edit()
                .remove(KEY_BODY_MEASUREMENT_FORM)
                .putString(KEY_CLEARED_BODY_MEASUREMENT_FORM_ID, formId)
            if (resolvedShortcutRequestId != null) {
                editor.putString(KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID, resolvedShortcutRequestId)
            }
            if (!editor.commit()) {
                // SharedPreferences may expose a failed commit's changes in its
                // process-local map even though the old raw form is still on disk.
                // Restore the exact prior state before releasing the lock so a
                // second read cannot acknowledge and age out the only DB receipt.
                preferences.edit()
                    .restoreString(KEY_BODY_MEASUREMENT_FORM, previousRawForm)
                    .restoreString(KEY_CLEARED_BODY_MEASUREMENT_FORM_ID, previousClearedFormId)
                    .restoreString(
                        KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID,
                        previousResolvedShortcutId,
                    )
                    .apply()
                error("无法清除身体表单草稿")
            }
            // Confirm only this exact receipt, before another raw form can be
            // persisted under the shared preference lock.
            runCatching { database.confirmBodyMeasurementRawFormCleanup(formId) }
            true
        }
        cleared
    }

    fun resolvedBodyMeasurementShortcutRequestId(): String? = ledgerWrite {
        synchronized(preferences) {
            preferences.getString(KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID, null)?.let {
                return@ledgerWrite it
            }
        }
        // If preference cleanup itself failed after SQLite commit, still expose
        // the matching request ID so restored UI cannot open a duplicate form.
        val raw = synchronized(preferences) { bodyMeasurementFormDraftLocked() }
            ?: return@ledgerWrite null
        raw.shortcutRequestId?.takeIf { database.bodyMeasurementCommitId(raw.id) != null }
    }

    fun acknowledgeBodyMeasurementShortcutRequest(expectedRequestId: String): Boolean = ledgerWrite {
        require(expectedRequestId.isNotBlank() && expectedRequestId.length <= 128)
        synchronized(preferences) {
            if (preferences.getString(KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID, null) != expectedRequestId) {
                return@synchronized false
            }
            check(preferences.edit().remove(KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID).commit()) {
                "无法确认身体记录入口已处理"
            }
            true
        }
    }

    fun nutritionForDate(date: LocalDate): Nutrition = database.nutritionForDate(date)
    fun mealsForDate(date: LocalDate): List<MealRecord> = database.mealsForDate(date)
    fun deleteMeal(mealId: Long) = ledgerWrite { database.deleteMeal(mealId) }
    fun draftFromMeal(mealId: Long, targetDate: LocalDate, replaceOriginal: Boolean): MealDraft =
        ledgerWrite { database.draftFromMeal(mealId, targetDate, replaceOriginal) }
    fun listSavedFoods(): List<SavedFood> = database.listSavedFoods()
    fun setFoodFavorite(foodId: Long, favorite: Boolean): Boolean = ledgerWrite {
        database.setFoodFavorite(foodId, favorite)
    }
    fun latestDraft(): MealDraft? = ledgerWrite {
        val existing = database.latestDraft()
        // Upgrade recovery for a camera operation from a build without a
        // pre-request draft. Decode locally only: the provider may have charged.
        val pending = PhotoStorage.pendingCameraCapture(appContext)
        val recovered = if (existing == null && pending?.phase == PhotoStorage.PendingCameraPhase.ANALYZING) {
            runCatching {
                ConfigurablePhotoAnalyzer(AnalysisServiceConfig()).analyze(appContext, pending.uri).copy(
                    evidenceReason = INTERRUPTED_PHOTO_ANALYSIS_MESSAGE,
                    targetDate = pending.targetDateEpochDay?.let(LocalDate::ofEpochDay),
                ).also { database.saveDraft(it) }
            }.getOrNull()
        } else null
        (existing ?: recovered).also {
            PhotoStorage.cleanupOrphans(appContext, database.draftPhotoUris())
        }
    }
    fun saveDraft(draft: MealDraft) = ledgerWrite {
        val previous = database.latestDraft()?.takeIf { it.id != draft.id }
        database.saveDraft(draft)
        previous
            ?.takeIf { it.photoUri != draft.photoUri }
            ?.let { PhotoStorage.delete(appContext, it.photoUri) }
    }

    fun saveManualDraftFromForm(draft: MealDraft, formId: String): Boolean = ledgerWrite {
        val previous = database.latestDraft()?.takeIf { it.id != draft.id }
        val stored = database.saveDraft(draft, manualFormId = formId)
        if (stored) {
            previous
                ?.takeIf { it.photoUri != draft.photoUri }
                ?.let { PhotoStorage.delete(appContext, it.photoUri) }
        }
        stored
    }
    fun discardDraft(draft: MealDraft) = ledgerWrite {
        database.discardDraft(draft.id)
        if (draft.photoUri !in database.draftPhotoUris()) {
            PhotoStorage.delete(appContext, draft.photoUri)
        }
    }

    fun manualFoodFormDraft(): ManualFoodFormDraft? = ledgerWrite {
        val draft = synchronized(preferences) {
            val current = manualFoodFormDraftLocked()
            runCatching {
                database.reconcileManualFoodFormConversionReceipts(protectedRawFormId = current?.id)
            }
            current
        }
        if (draft == null) return@ledgerWrite null
        if (database.manualFoodFormConversionDraftId(draft.id) == null) {
            return@ledgerWrite draft
        }
        // SQLite is authoritative. Keep the conversion receipt after the meal
        // draft is committed/discarded so a failed preference cleanup cannot
        // resurrect and reconvert the old raw editor.
        runCatching { clearManualFoodFormDraftIfCurrent(draft.id) }
        null
    }

    /**
     * Stores the latest raw form revision synchronously. The ViewModel invokes
     * this on Dispatchers.IO and coalesces stale revisions. Repository-level
     * guards provide a second line of defence against a late coroutine
     * resurrecting a form after the user explicitly discarded it.
     *
     * @return true when this revision is now durable, false when it was already
     * superseded or belongs to an explicitly cleared form id.
     */
    fun saveManualFoodFormDraft(draft: ManualFoodFormDraft): Boolean = ledgerWrite {
        draft.persistenceValidationError()?.let { throw IllegalArgumentException(it) }
        require(draft.isDirty) { "空白手工饮食表单无需持久化" }
        if (database.manualFoodFormConversionDraftId(draft.id) != null) return@ledgerWrite false
        synchronized(preferences) {
            if (preferences.getString(KEY_CLEARED_MANUAL_FOOD_FORM_ID, null) == draft.id) {
                return@synchronized false
            }
            val current = manualFoodFormDraftLocked()
            require(current == null || current.id == draft.id) {
                "已有另一份未完成的手工饮食记录"
            }
            if (current != null && current.revision > draft.revision) return@synchronized false
            check(
                preferences.edit()
                    .putString(KEY_MANUAL_FOOD_FORM, encodeManualFoodFormDraft(draft))
                    .commit(),
            ) { "无法持久化手工饮食草稿" }
            true
        }
    }

    /** Clears only the named form and leaves a one-id tombstone so a late save
     * from the old UI cannot recreate it after an explicit discard/commit. */
    fun clearManualFoodFormDraft(formId: String) = ledgerWrite {
        clearManualFoodFormDraftIfCurrent(formId)
    }

    private fun clearManualFoodFormDraftIfCurrent(formId: String): Boolean {
        require(formId.isNotBlank() && formId.length <= 128)
        val cleared = synchronized(preferences) {
            val current = manualFoodFormDraftLocked()
            if (current != null && current.id != formId) return@synchronized false
            val previousRawForm = preferences.getString(KEY_MANUAL_FOOD_FORM, null)
            val previousClearedFormId = preferences.getString(KEY_CLEARED_MANUAL_FOOD_FORM_ID, null)
            val editor = preferences.edit()
                .remove(KEY_MANUAL_FOOD_FORM)
                .putString(KEY_CLEARED_MANUAL_FOOD_FORM_ID, formId)
            if (!editor.commit()) {
                preferences.edit()
                    .restoreString(KEY_MANUAL_FOOD_FORM, previousRawForm)
                    .restoreString(KEY_CLEARED_MANUAL_FOOD_FORM_ID, previousClearedFormId)
                    .apply()
                error("无法清除手工饮食草稿")
            }
            runCatching { database.confirmManualFoodRawFormCleanup(formId) }
            true
        }
        return cleared
    }

    fun commitDraft(draft: MealDraft, mealDate: LocalDate): Long = ledgerWrite {
        draft.commitValidationError()?.let { throw IllegalArgumentException(it) }
        database.commitDraft(draft, mealDate).also { PhotoStorage.delete(appContext, draft.photoUri) }
    }

    fun analysisConfig(): AnalysisServiceConfig = ledgerWrite {
        analysisServices.config().copy(additionalPrompt = analysisAdditionalPrompt())
    }

    fun analysisAdditionalPrompt(): String = ledgerWrite {
        val stored = preferences.all[KEY_ANALYSIS_ADDITIONAL_PROMPT] as? String ?: return@ledgerWrite ""
        if (additionalMealPromptValidationError(stored) == null) normalizeAdditionalMealPrompt(stored) else ""
    }

    fun saveAnalysisAdditionalPrompt(text: String) = ledgerWrite {
        additionalMealPromptValidationError(text)?.let { throw IllegalArgumentException(it) }
        val normalized = normalizeAdditionalMealPrompt(text)
        synchronized(preferences) {
            val previous = preferences.all[KEY_ANALYSIS_ADDITIONAL_PROMPT] as? String
            val editor = preferences.edit()
            if (normalized.isEmpty()) editor.remove(KEY_ANALYSIS_ADDITIONAL_PROMPT)
            else editor.putString(KEY_ANALYSIS_ADDITIONAL_PROMPT, normalized)
            if (!editor.commit()) {
                preferences.edit().restoreString(KEY_ANALYSIS_ADDITIONAL_PROMPT, previous).apply()
                error("无法保存附加提示词，请重试")
            }
        }
    }

    fun analysisProfiles(): List<AnalysisServiceProfile> = ledgerWrite { analysisServices.profiles() }

    fun activeAnalysisProfileId(): String? = ledgerWrite { analysisServices.activeId() }

    fun analysisProfilesUnreadable(): Boolean = ledgerWrite { analysisServices.unreadable() }

    fun saveAnalysisProfile(id: String?, name: String, endpoint: String, modelName: String, apiKey: String): String =
        ledgerWrite { analysisServices.save(id, name, endpoint, modelName, apiKey) }

    fun selectAnalysisProfile(id: String) = ledgerWrite { analysisServices.select(id) }

    fun deleteAnalysisProfile(id: String) = ledgerWrite { analysisServices.delete(id) }

    fun hasAnalysisToken(): Boolean = analysisConfig().accessToken.isNotBlank()

    fun saveAnalysisConfig(
        endpoint: String,
        accessToken: String,
        transport: AnalysisTransport = AnalysisTransport.MEAL_PROXY,
        modelName: String = "",
    ) = ledgerWrite { analysisServices.saveLegacy(endpoint, accessToken, transport, modelName) }

    fun clearAnalysisToken() = ledgerWrite { analysisServices.clear() }

    /** Deletes only the active saved service (if any) and disables its connection. */
    fun clearActiveAnalysisConnection() = ledgerWrite { analysisServices.clearActive() }

    /** Cross-device backup deliberately omits the Keystore-bound analyser token. */
    fun exportEncryptedBackup(output: java.io.OutputStream, passphrase: CharArray): LedgerBackupSummary =
        LedgerBackupManager(appContext).use { it.exportEncrypted(output, passphrase) }

    /** Restores a fully authenticated package and leaves remote analysis disabled
     * until the user enters a new endpoint/token on this device. */
    fun importEncryptedBackup(input: java.io.InputStream, passphrase: CharArray): LedgerRestoreResult =
        LedgerBackupManager(appContext).use { it.importEncrypted(input, passphrase) }

    fun analyzePhoto(uri: Uri, targetDate: LocalDate? = null): MealDraft = ledgerWrite {
        ConfigurablePhotoAnalyzer(
            config = analysisConfig(),
            onPhotoPrepared = { preparedUri ->
                // Retries keep the edited draft until success. First camera AND
                // gallery requests get a durable recovery draft before upload.
                if (database.latestDraft() == null) database.saveDraft(
                    MealDraft(
                        photoUri = preparedUri.toString(),
                        targetDate = targetDate,
                        state = DraftState.ANALYSIS_FAILED,
                        items = emptyList(),
                        evidenceTier = EvidenceTier.D,
                        evidenceReason = INTERRUPTED_PHOTO_ANALYSIS_MESSAGE,
                        unresolvedFlags = emptySet(),
                        providerLabel = "识别中断 · 未写入正式账本",
                        analysisMode = AnalysisMode.MANUAL,
                    ),
                )
            },
        ).analyze(appContext, uri)
    }
    fun completePendingCameraCapture(uri: Uri) = ledgerWrite {
        PhotoStorage.completePendingCameraCapture(appContext, uri)
    }

    /** Called by AppViewModel on Dispatchers.IO after the durable draft is read.
     * Constructor-time work is deliberately avoided so Activity creation never scans
     * files or camera SharedPreferences on the main thread. */
    fun reconcilePendingCameraCapture(
        hasDurableDraft: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): PhotoStorage.PendingCameraCapture? = ledgerWrite {
        PhotoStorage.expireStaleLaunchedCameraCapture(appContext, nowMillis)
        PhotoStorage.cleanupStaleCameraOrphans(appContext, nowMillis)
        val pending = PhotoStorage.pendingCameraCapture(appContext) ?: return@ledgerWrite null
        if (hasDurableDraft && pending.phase != PhotoStorage.PendingCameraPhase.LAUNCHED) {
            // A crash can occur after the stripped photo and draft transaction are
            // durable but before the temporary camera output is acknowledged.
            PhotoStorage.completeDeliveredPendingCameraCapture(appContext)
            return@ledgerWrite null
        }
        pending
    }

    /** Before opening the system camera, retire only an expired LAUNCHED lease and
     * reuse a fresh one. Delivered/analyzing work is returned for recovery instead
     * of being overwritten or deleted. */
    fun prepareCameraCapture(
        nowMillis: Long = System.currentTimeMillis(),
        targetDateEpochDay: Long? = null,
    ): PhotoStorage.PendingCameraCapture = ledgerWrite {
        PhotoStorage.expireStaleLaunchedCameraCapture(appContext, nowMillis)
        PhotoStorage.cleanupStaleCameraOrphans(appContext, nowMillis)
        PhotoStorage.pendingCameraCapture(appContext)
            ?: PhotoStorage.createPendingCameraUri(appContext, nowMillis, targetDateEpochDay).let { uri ->
                requireNotNull(PhotoStorage.pendingCameraCapture(appContext)).also {
                    check(it.uri.toString() == uri.toString())
                }
            }
    }

    /** Resolve ActivityResult without trusting process-local Compose state. A false
     * result clears only LAUNCHED; delivered/analyzing work remains recoverable. */
    fun recordCameraCaptureResult(
        success: Boolean,
        expectedUri: Uri?,
    ): PhotoStorage.PendingCameraCapture? = ledgerWrite {
        val pending = PhotoStorage.pendingCameraCapture(appContext) ?: return@ledgerWrite null
        check(expectedUri == null || pending.uri.toString() == expectedUri.toString()) {
            "系统相机返回与当前拍照任务不匹配"
        }
        if (!success) {
            PhotoStorage.cancelPendingCameraCapture(appContext, pending.uri)
            return@ledgerWrite PhotoStorage.pendingCameraCapture(appContext)
        }
        if (pending.phase == PhotoStorage.PendingCameraPhase.LAUNCHED) {
            check(PhotoStorage.markPendingCameraResultReceived(appContext, pending.uri)) {
                "无法持久化相机返回状态"
            }
        }
        PhotoStorage.pendingCameraCapture(appContext)
    }

    fun cancelCameraLaunch(uri: Uri): PhotoStorage.PendingCameraCapture? = ledgerWrite {
        PhotoStorage.cancelPendingCameraCapture(appContext, uri)
        PhotoStorage.pendingCameraCapture(appContext)
    }

    fun listExercises(includeArchived: Boolean = false): List<Exercise> = database.listExercises(includeArchived)
    fun addCustomExercise(name: String, category: String, trackingType: TrackingType, isPrimary: Boolean): Exercise =
        ledgerWrite { database.addCustomExercise(name, category, trackingType, isPrimary) }

    fun updateCustomExercise(exerciseId: Long, name: String, category: String, isPrimary: Boolean): Exercise =
        ledgerWrite { database.updateCustomExercise(exerciseId, name, category, isPrimary) }

    fun setCustomExerciseArchived(exerciseId: Long, archived: Boolean): Exercise =
        ledgerWrite { database.setCustomExerciseArchived(exerciseId, archived) }

    fun setExercisePrimary(exerciseId: Long, primary: Boolean) = ledgerWrite {
        database.setExercisePrimary(exerciseId, primary)
    }
    fun startWorkout(title: String): WorkoutSession = ledgerWrite { database.startWorkout(title) }
    fun activeWorkout(nowMillis: Long = System.currentTimeMillis()): WorkoutSession? = ledgerWrite {
        // A persisted wall-clock deadline cannot be converted to a reliable
        // duration after a reboot if the wall clock moved backwards. Clear it
        // durably instead of presenting a frozen full-length countdown.
        database.activeWorkoutWithRestTimerReconciled(nowMillis)
    }
    fun startRestTimer(sessionId: Long, durationSeconds: Int): WorkoutSession = ledgerWrite {
        database.startRestTimer(sessionId, durationSeconds)
    }
    fun clearRestTimer(sessionId: Long): WorkoutSession = ledgerWrite { database.clearRestTimer(sessionId) }
    fun setsForSession(sessionId: Long): List<WorkoutSet> = database.setsForSession(sessionId)
    fun listWorkoutHistory(limit: Int = 50, offset: Int = 0): List<WorkoutHistorySummary> =
        database.listWorkoutHistory(limit, offset)
    fun completedWorkoutsForDate(date: LocalDate): List<WorkoutHistorySummary> =
        database.completedWorkoutsForDate(date)
    fun workoutHistoryDetail(sessionId: Long): WorkoutHistoryDetail = database.workoutHistoryDetail(sessionId)
    fun startWorkoutCorrection(sessionId: Long): WorkoutSession = ledgerWrite {
        database.startWorkoutCorrection(sessionId)
    }
    fun abandonWorkoutCorrection(correctionDraftId: Long): Boolean =
        ledgerWrite { database.abandonWorkoutCorrection(correctionDraftId) }
    fun commitWorkoutCorrection(correctionDraftId: Long): WorkoutCorrectionResult =
        ledgerWrite { database.commitWorkoutCorrection(correctionDraftId) }
    fun deleteWorkoutHistorySession(sessionId: Long): WorkoutHistoryDeleteResult =
        ledgerWrite { database.deleteWorkoutHistorySession(sessionId) }
    fun workoutPlannerSnapshot(activeSessionId: Long?, committedSetIds: Set<String>): WorkoutPlannerSnapshot =
        ledgerWrite { workoutPlannerStore.load(activeSessionId, committedSetIds) }
    fun beginWorkoutPlanning(draft: WorkoutEditorDraft, plan: ActiveWorkoutPlan?): WorkoutPlannerSnapshot =
        ledgerWrite { workoutPlannerStore.beginSession(draft, plan) }
    fun saveWorkoutEditorDraft(draft: WorkoutEditorDraft): Boolean = ledgerWrite {
        workoutPlannerStore.saveEditorDraft(draft)
    }
    fun replaceActiveWorkoutPlanning(plan: ActiveWorkoutPlan?, draft: WorkoutEditorDraft): WorkoutPlannerSnapshot =
        ledgerWrite { workoutPlannerStore.replaceActivePlanning(plan, draft) }
    fun completeWorkoutDraftSubmission(
        sessionId: Long,
        committedId: String,
        nextDraft: WorkoutEditorDraft,
    ): WorkoutPlannerSnapshot = ledgerWrite {
        workoutPlannerStore.completeSubmission(sessionId, committedId, nextDraft)
    }
    fun clearWorkoutPlanning(sessionId: Long): WorkoutPlannerSnapshot = ledgerWrite {
        workoutPlannerStore.clearSession(sessionId)
    }
    fun saveWorkoutTemplate(template: WorkoutTemplate): WorkoutPlannerSnapshot = ledgerWrite {
        workoutPlannerStore.saveTemplate(template)
    }
    fun deleteWorkoutTemplate(templateId: String): WorkoutPlannerSnapshot = ledgerWrite {
        workoutPlannerStore.deleteTemplate(templateId)
    }
    fun saveBarbellBarWeight(weightKg: Double): WorkoutPlannerSnapshot =
        ledgerWrite { workoutPlannerStore.saveBarbellBarWeight(weightKg) }
    fun addWorkoutSet(set: WorkoutSet): WorkoutSet = ledgerWrite { database.addWorkoutSet(set) }
    fun addWorkoutSets(sets: List<WorkoutSet>): List<WorkoutSet> = ledgerWrite { database.addWorkoutSets(sets) }
    fun updateWorkoutSet(set: WorkoutSet) = ledgerWrite { database.updateWorkoutSet(set) }
    fun deleteWorkoutSet(setId: Long) = ledgerWrite { database.deleteWorkoutSet(setId) }
    fun deleteWorkoutSetBatch(batchId: String) = ledgerWrite { database.deleteWorkoutSetBatch(batchId) }
    fun cancelWorkout(sessionId: Long) = ledgerWrite { database.cancelWorkout(sessionId) }
    fun completeWorkout(sessionId: Long): WorkoutCompletionResult = ledgerWrite {
        database.completeWorkout(sessionId)
    }
    fun prSummary(exerciseId: Long): PrSummary = database.prSummary(exerciseId)
    fun latestPrEvents(limit: Int = 20): List<PersonalRecord> = database.latestPrEvents(limit)

    /** Persist the exact user intent before SQLite work begins. A different new
     * intent is rejected until the earlier committed result is acknowledged. */
    fun prepareSingleSetCommit(
        sessionId: Long,
        exerciseId: Long,
        input: WorkoutSetInput,
        nowMillis: Long = System.currentTimeMillis(),
    ): PendingSingleSetCommit = ledgerWrite {
        require(sessionId > 0L && exerciseId > 0L && input.commitId.isNotBlank())
        // This call also removes a deterministic invalid/uncommitted legacy 5x5
        // journal, so it cannot block all later workout writes forever.
        pendingFiveByFiveCommit()
        synchronized(preferences) {
            require(pendingFiveByFiveCommitLocked() == null) {
                "上一批 5×5 仍在确认保存结果；请先确认后再记录下一组"
            }
            pendingSingleSetCommitLocked()?.let { existing ->
                require(existing.sessionId == sessionId && existing.exerciseId == exerciseId && existing.input == input) {
                    "上一组仍在确认保存结果；请先确认后再记录下一组"
                }
                return@synchronized existing
            }
            val pending = PendingSingleSetCommit(sessionId, exerciseId, input, nowMillis)
            check(
                preferences.edit()
                    .putString(KEY_PENDING_SINGLE_SET, encodePendingSingleSetCommit(pending))
                    .commit(),
            ) { "无法持久化单组提交状态" }
            pending
        }
    }

    fun pendingSingleSetCommit(): PendingSingleSetCommit? = ledgerWrite {
        synchronized(preferences) { pendingSingleSetCommitLocked() }
    }

    fun isSingleSetCommitVisible(pending: PendingSingleSetCommit): Boolean =
        database.setsForSession(pending.sessionId).any { it.commitId == pending.input.commitId }

    /** Idempotently commits the current journal. If a process died after the row
     * write, the same business key returns that row instead of inserting another. */
    fun commitPendingSingleSet(nowMillis: Long = System.currentTimeMillis()): SingleSetCommitResult = ledgerWrite {
        val pending = pendingSingleSetCommit() ?: error("没有待保存的单组训练")
        val session = database.activeWorkout()?.takeIf { it.id == pending.sessionId }
            ?: error("待保存的单组训练已不属于进行中的训练")
        val exercise = database.listExercises().firstOrNull { it.id == pending.exerciseId }
            ?: error("待保存单组对应的动作不存在")
        pending.input.validationError(exercise.trackingType)?.let { throw IllegalArgumentException(it) }
        val existing = database.setsForSession(pending.sessionId)
            .firstOrNull { it.commitId == pending.input.commitId }
        val set = existing ?: database.addWorkoutSet(
            WorkoutSet(
                sessionId = pending.sessionId,
                exerciseId = pending.exerciseId,
                setOrder = 1,
                loadGrams = (pending.input.weightKg * 1000.0).roundToLong(),
                reps = pending.input.reps,
                durationSeconds = pending.input.durationSeconds,
                completed = pending.input.completed,
                isWarmup = pending.input.isWarmup,
                rpe = pending.input.rpe,
                rir = pending.input.rir,
                note = pending.input.note.trim(),
                supersetId = pending.input.supersetId?.trim()?.uppercase()?.ifBlank { null },
                commitId = pending.input.commitId,
            ),
        )
        val timerSession = pending.input.restSecondsAfter?.let { durationSeconds ->
            val requestedEnd = pending.submittedAtMillis + durationSeconds * 1_000L
            val remainingSeconds = ((requestedEnd - nowMillis + 999L) / 1_000L).toInt()
            val timerAlreadyApplied = session.restTimerEndAtMillis?.let { end ->
                kotlin.math.abs(end - requestedEnd) <= 5_000L
            } == true
            when {
                existing == null -> database.startRestTimer(session.id, durationSeconds)
                timerAlreadyApplied -> session
                remainingSeconds >= 15 -> database.startRestTimer(session.id, remainingSeconds.coerceAtMost(3_600))
                else -> session
            }
        } ?: session
        SingleSetCommitResult(pending, timerSession, set)
    }

    fun acknowledgeSingleSetCommit(commitId: String) = ledgerWrite {
        require(commitId.isNotBlank())
        synchronized(preferences) {
            val pending = pendingSingleSetCommitLocked() ?: return@synchronized
            if (pending.input.commitId != commitId) return@synchronized
            check(preferences.edit().remove(KEY_PENDING_SINGLE_SET).commit()) {
                "无法确认单组提交结果"
            }
        }
    }

    /** A journal may be abandoned only when its row is provably absent. */
    fun discardUncommittedSingleSet(pending: PendingSingleSetCommit) = ledgerWrite {
        check(!isSingleSetCommitVisible(pending)) { "已写入的单组训练不能作为未提交任务丢弃" }
        acknowledgeSingleSetCommit(pending.input.commitId)
    }

    /** Persist one visible 5x5 confirmation before SQLite work begins. The UI
     * supplies a stable batch id created when the confirmation dialog opens, so
     * even stale queued callbacks replay the same five-row operation. */
    fun prepareFiveByFiveCommit(
        sessionId: Long,
        exerciseId: Long,
        loadGrams: Long,
        batchId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): PendingFiveByFiveCommit = ledgerWrite {
        require(sessionId > 0L && exerciseId > 0L && loadGrams in 0L..1_000_000L)
        require(batchId.isNotBlank() && batchId.length <= WORKOUT_BATCH_ID_MAX_LENGTH && !batchId.contains(':'))
        validateFiveByFiveIntent(sessionId, exerciseId, loadGrams)
        synchronized(preferences) {
            require(pendingSingleSetCommitLocked() == null) {
                "上一组仍在确认保存结果；请先确认后再记录 5×5"
            }
            pendingFiveByFiveCommitLocked()?.let { existing ->
                require(
                    existing.sessionId == sessionId &&
                        existing.exerciseId == exerciseId &&
                        existing.loadGrams == loadGrams &&
                        existing.batchId == batchId,
                ) { "上一批 5×5 仍在确认保存结果；请先确认后再记录下一批" }
                return@synchronized existing
            }
            val pending = PendingFiveByFiveCommit(
                sessionId = sessionId,
                exerciseId = exerciseId,
                loadGrams = loadGrams,
                batchId = batchId,
                submittedAtMillis = nowMillis,
            )
            check(
                preferences.edit()
                    .putString(KEY_PENDING_FIVE_BY_FIVE, encodePendingFiveByFiveCommit(pending))
                    .remove(KEY_PENDING_FIVE_BY_FIVE_EXERCISE)
                    .remove(KEY_PENDING_FIVE_BY_FIVE_LOAD)
                    .remove(KEY_PENDING_FIVE_BY_FIVE_COMMIT)
                    .commit(),
            ) { "无法持久化 5×5 提交状态" }
            pending
        }
    }

    fun pendingFiveByFiveCommit(): PendingFiveByFiveCommit? = ledgerWrite {
        val pending = synchronized(preferences) { pendingFiveByFiveCommitLocked() }
            ?: return@ledgerWrite null
        if (!isFiveByFiveCommitVisible(pending) &&
            runCatching {
                validateFiveByFiveIntent(pending.sessionId, pending.exerciseId, pending.loadGrams)
            }.isFailure
        ) {
            discardUncommittedFiveByFive(pending)
            return@ledgerWrite null
        }
        pending
    }

    fun isFiveByFiveCommitVisible(pending: PendingFiveByFiveCommit): Boolean {
        val expectedIds = (0 until 5).map { "${pending.batchId}:$it" }.toSet()
        val rows = database.setsForBatch(pending.batchId)
        return rows.size == 5 && rows.map { it.commitId }.toSet() == expectedIds &&
            rows.all {
                it.sessionId == pending.sessionId && it.exerciseId == pending.exerciseId &&
                    it.loadGrams == pending.loadGrams && it.reps == 5 && it.durationSeconds == 0 &&
                    it.completed && !it.isWarmup && it.batchId == pending.batchId
            }
    }

    fun commitPendingFiveByFive(): FiveByFiveCommitResult = ledgerWrite {
        val pending = pendingFiveByFiveCommit() ?: error("没有待保存的 5×5")
        val (session, _) = validateFiveByFiveIntent(pending.sessionId, pending.exerciseId, pending.loadGrams)
        val sets = database.addWorkoutSets(
            List(5) { index ->
                WorkoutSet(
                    sessionId = pending.sessionId,
                    exerciseId = pending.exerciseId,
                    setOrder = index + 1,
                    loadGrams = pending.loadGrams,
                    reps = 5,
                    completed = true,
                    batchId = pending.batchId,
                    commitId = "${pending.batchId}:$index",
                )
            },
        )
        check(isFiveByFiveCommitVisible(pending)) { "5×5 未完整写入，请重新打开 App 恢复" }
        FiveByFiveCommitResult(pending, session, sets)
    }

    private fun validateFiveByFiveIntent(
        sessionId: Long,
        exerciseId: Long,
        loadGrams: Long,
    ): Pair<WorkoutSession, Exercise> {
        val session = database.activeWorkout()?.takeIf { it.id == sessionId }
            ?: throw IllegalArgumentException("待保存的 5×5 已不属于进行中的训练")
        val exercise = database.listExercises().firstOrNull { it.id == exerciseId }
            ?: throw IllegalArgumentException("待保存 5×5 对应的动作不存在")
        require(
            exercise.trackingType == TrackingType.WEIGHT_REPS ||
                exercise.trackingType == TrackingType.BODYWEIGHT_REPS,
        ) { "该动作不支持 5×5" }
        if (exercise.trackingType == TrackingType.WEIGHT_REPS) {
            require(loadGrams > 0L) { "负重动作必须填写大于 0 kg 的重量" }
        }
        return session to exercise
    }

    fun acknowledgeFiveByFiveCommit(batchId: String) = ledgerWrite {
        require(batchId.isNotBlank())
        synchronized(preferences) {
            val pending = pendingFiveByFiveCommitLocked() ?: return@synchronized
            if (pending.batchId != batchId) return@synchronized
            check(isFiveByFiveCommitVisible(pending)) { "尚未完整写入的 5×5 不能确认" }
            check(preferences.edit().remove(KEY_PENDING_FIVE_BY_FIVE).commit()) {
                "无法确认 5×5 提交结果"
            }
        }
    }

    fun discardUncommittedFiveByFive(pending: PendingFiveByFiveCommit) = ledgerWrite {
        check(!isFiveByFiveCommitVisible(pending)) { "已写入的 5×5 不能作为未提交任务丢弃" }
        synchronized(preferences) {
            if (pendingFiveByFiveCommitLocked()?.batchId != pending.batchId) return@synchronized
            check(preferences.edit().remove(KEY_PENDING_FIVE_BY_FIVE).commit()) {
                "无法清理未提交的 5×5"
            }
        }
    }

    companion object {

        private const val KEY_PENDING_FIVE_BY_FIVE_EXERCISE = "pending_5x5_exercise_id"
        private const val KEY_PENDING_FIVE_BY_FIVE_LOAD = "pending_5x5_load_grams"
        private const val KEY_PENDING_FIVE_BY_FIVE_COMMIT = "pending_5x5_commit_id"
        private const val KEY_PENDING_FIVE_BY_FIVE = "pending_5x5_v2"
        private const val KEY_PENDING_SINGLE_SET = "pending_single_set_v1"
        private const val KEY_MANUAL_FOOD_FORM = "manual_food_form_v1"
        private const val KEY_CLEARED_MANUAL_FOOD_FORM_ID = "manual_food_form_cleared_id_v1"
        private const val KEY_BODY_MEASUREMENT_FORM = "body_measurement_form_v1"
        private const val KEY_CLEARED_BODY_MEASUREMENT_FORM_ID = "body_measurement_form_cleared_id_v1"
        private const val KEY_RESOLVED_BODY_MEASUREMENT_SHORTCUT_ID = "body_measurement_shortcut_resolved_id_v2"

        private const val KEY_PROFILE_CONFIGURED = "profile_configured"
    }

    private fun manualFoodFormDraftLocked(): ManualFoodFormDraft? {
        val encoded = preferences.getString(KEY_MANUAL_FOOD_FORM, "").orEmpty()
        if (encoded.isBlank()) return null
        return runCatching { decodeManualFoodFormDraft(encoded) }.getOrElse {
            check(preferences.edit().remove(KEY_MANUAL_FOOD_FORM).commit()) {
                "无法清理损坏的手工饮食草稿"
            }
            null
        }
    }

    private fun bodyMeasurementFormDraftLocked(): BodyMeasurementFormDraft? {
        val encoded = preferences.getString(KEY_BODY_MEASUREMENT_FORM, "").orEmpty()
        if (encoded.isBlank()) return null
        return runCatching { decodeBodyMeasurementFormDraft(encoded) }.getOrElse {
            check(preferences.edit().remove(KEY_BODY_MEASUREMENT_FORM).commit()) {
                "无法清理损坏的身体表单草稿"
            }
            null
        }
    }

    private fun pendingSingleSetCommitLocked(): PendingSingleSetCommit? {
        val encoded = preferences.getString(KEY_PENDING_SINGLE_SET, "").orEmpty()
        if (encoded.isBlank()) return null
        return runCatching { decodePendingSingleSetCommit(encoded) }.getOrElse {
            check(preferences.edit().remove(KEY_PENDING_SINGLE_SET).commit()) {
                "无法清理损坏的单组提交状态"
            }
            null
        }
    }

    /** Reads v2 and upgrades the alpha01 three-key journal in place. The legacy
     * format had no session id, so it is inferred only from the already committed
     * batch or the single active workout. */
    private fun pendingFiveByFiveCommitLocked(): PendingFiveByFiveCommit? {
        val encoded = preferences.getString(KEY_PENDING_FIVE_BY_FIVE, "").orEmpty()
        if (encoded.isNotBlank()) {
            return runCatching { decodePendingFiveByFiveCommit(encoded) }.getOrElse {
                check(preferences.edit().remove(KEY_PENDING_FIVE_BY_FIVE).commit()) {
                    "无法清理损坏的 5×5 提交状态"
                }
                null
            }
        }

        val legacyBatchId = preferences.getString(KEY_PENDING_FIVE_BY_FIVE_COMMIT, "").orEmpty()
        if (legacyBatchId.isBlank()) return null
        val legacyExerciseId = preferences.getLong(KEY_PENDING_FIVE_BY_FIVE_EXERCISE, -1L)
        val legacyLoadGrams = preferences.getLong(KEY_PENDING_FIVE_BY_FIVE_LOAD, -1L)
        val sessionId = database.setsForBatch(legacyBatchId).firstOrNull()?.sessionId
            ?: database.activeWorkout()?.id
        if (sessionId == null || legacyExerciseId <= 0L || legacyLoadGrams !in 0L..1_000_000L ||
            legacyBatchId.length !in 1..WORKOUT_BATCH_ID_MAX_LENGTH || legacyBatchId.contains(':')
        ) {
            check(
                preferences.edit()
                    .remove(KEY_PENDING_FIVE_BY_FIVE_EXERCISE)
                    .remove(KEY_PENDING_FIVE_BY_FIVE_LOAD)
                    .remove(KEY_PENDING_FIVE_BY_FIVE_COMMIT)
                    .commit(),
            ) { "无法清理无法恢复的旧版 5×5 提交状态" }
            return null
        }
        val migrated = PendingFiveByFiveCommit(
            sessionId = sessionId,
            exerciseId = legacyExerciseId,
            loadGrams = legacyLoadGrams,
            batchId = legacyBatchId,
            submittedAtMillis = System.currentTimeMillis(),
        )
        check(
            preferences.edit()
                .putString(KEY_PENDING_FIVE_BY_FIVE, encodePendingFiveByFiveCommit(migrated))
                .remove(KEY_PENDING_FIVE_BY_FIVE_EXERCISE)
                .remove(KEY_PENDING_FIVE_BY_FIVE_LOAD)
                .remove(KEY_PENDING_FIVE_BY_FIVE_COMMIT)
                .commit(),
        ) { "无法迁移旧版 5×5 提交状态" }
        return migrated
    }

    private fun encodePendingFiveByFiveCommit(pending: PendingFiveByFiveCommit): String = JSONObject().apply {
        put("version", 2)
        put("sessionId", pending.sessionId)
        put("exerciseId", pending.exerciseId)
        put("loadGrams", pending.loadGrams)
        put("batchId", pending.batchId)
        put("submittedAtMillis", pending.submittedAtMillis)
    }.toString()

    private fun encodeManualFoodFormDraft(draft: ManualFoodFormDraft): String = JSONObject().apply {
        put("version", 1)
        put("id", draft.id)
        put("itemId", draft.itemId)
        put("targetDateEpochDay", draft.targetDate.toEpochDay())
        put("initialGrams", draft.initialGrams)
        put("initialGramsMin", draft.initialGramsMin)
        put("initialGramsMax", draft.initialGramsMax)
        put("initialPortionBasis", draft.initialPortionBasis.name)
        put("name", draft.name)
        put("gramsText", draft.gramsText)
        put("kcalText", draft.kcalText)
        put("carbsText", draft.carbsText)
        put("proteinText", draft.proteinText)
        put("fatText", draft.fatText)
        put("sourceName", draft.sourceName)
        put("weighed", draft.weighed)
        put("useLabelKcal", draft.useLabelKcal)
        put("isDirty", draft.isDirty)
        put("revision", draft.revision)
        put("updatedAtMillis", draft.updatedAtMillis)
    }.toString()

    private fun encodeBodyMeasurementFormDraft(draft: BodyMeasurementFormDraft): String = JSONObject().apply {
        put("version", 2)
        put("id", draft.id)
        put("measurementId", draft.measurementId)
        put("dateEpochDay", draft.date.toEpochDay())
        put("weightText", draft.weightText)
        put("waistText", draft.waistText)
        put("shortcutRequestId", draft.shortcutRequestId ?: JSONObject.NULL)
        put("revision", draft.revision)
        put("updatedAtMillis", draft.updatedAtMillis)
    }.toString()

    private fun decodeBodyMeasurementFormDraft(encoded: String): BodyMeasurementFormDraft {
        val value = JSONObject(encoded)
        val version = value.getInt("version")
        require(version in 1..2)
        val shortcutRequestId = when (version) {
            1 -> if (value.optBoolean("openedFromFirstShortcut", false)) "legacy-${value.getString("id")}" else null
            else -> value.optString("shortcutRequestId").takeIf { it.isNotBlank() && it != "null" }
        }
        return BodyMeasurementFormDraft(
            id = value.getString("id"),
            measurementId = value.getLong("measurementId"),
            date = LocalDate.ofEpochDay(value.getLong("dateEpochDay")),
            weightText = value.getString("weightText"),
            waistText = value.getString("waistText"),
            shortcutRequestId = shortcutRequestId,
            revision = value.getLong("revision"),
            updatedAtMillis = value.getLong("updatedAtMillis"),
        ).also { draft ->
            draft.persistenceValidationError()?.let { throw IllegalArgumentException(it) }
        }
    }

    private fun decodeManualFoodFormDraft(encoded: String): ManualFoodFormDraft {
        val value = JSONObject(encoded)
        require(value.getInt("version") == 1)
        return ManualFoodFormDraft(
            id = value.getString("id"),
            itemId = value.getString("itemId"),
            targetDate = LocalDate.ofEpochDay(value.getLong("targetDateEpochDay")),
            initialGrams = value.getDouble("initialGrams"),
            initialGramsMin = value.getDouble("initialGramsMin"),
            initialGramsMax = value.getDouble("initialGramsMax"),
            initialPortionBasis = PortionBasis.valueOf(value.getString("initialPortionBasis")),
            name = value.getString("name"),
            gramsText = value.getString("gramsText"),
            kcalText = value.getString("kcalText"),
            carbsText = value.getString("carbsText"),
            proteinText = value.getString("proteinText"),
            fatText = value.getString("fatText"),
            sourceName = value.getString("sourceName"),
            weighed = value.getBoolean("weighed"),
            useLabelKcal = value.getBoolean("useLabelKcal"),
            isDirty = value.getBoolean("isDirty"),
            revision = value.getLong("revision"),
            updatedAtMillis = value.getLong("updatedAtMillis"),
        ).also { draft ->
            require(draft.isDirty)
            draft.persistenceValidationError()?.let { throw IllegalArgumentException(it) }
        }
    }

    private fun decodePendingFiveByFiveCommit(encoded: String): PendingFiveByFiveCommit {
        val value = JSONObject(encoded)
        require(value.getInt("version") == 2)
        return PendingFiveByFiveCommit(
            sessionId = value.getLong("sessionId"),
            exerciseId = value.getLong("exerciseId"),
            loadGrams = value.getLong("loadGrams"),
            batchId = value.getString("batchId"),
            submittedAtMillis = value.getLong("submittedAtMillis"),
        ).also { pending ->
            require(pending.sessionId > 0L && pending.exerciseId > 0L)
            require(pending.loadGrams in 0L..1_000_000L && pending.submittedAtMillis > 0L)
            require(
                pending.batchId.isNotBlank() &&
                    pending.batchId.length <= WORKOUT_BATCH_ID_MAX_LENGTH &&
                    !pending.batchId.contains(':'),
            )
        }
    }

    private fun encodePendingSingleSetCommit(pending: PendingSingleSetCommit): String = JSONObject().apply {
        put("version", 1)
        put("sessionId", pending.sessionId)
        put("exerciseId", pending.exerciseId)
        put("submittedAtMillis", pending.submittedAtMillis)
        put("weightKg", pending.input.weightKg)
        put("reps", pending.input.reps)
        put("durationSeconds", pending.input.durationSeconds)
        put("completed", pending.input.completed)
        put("isWarmup", pending.input.isWarmup)
        put("rpe", pending.input.rpe ?: JSONObject.NULL)
        put("rir", pending.input.rir ?: JSONObject.NULL)
        put("note", pending.input.note)
        put("supersetId", pending.input.supersetId ?: JSONObject.NULL)
        put("restSecondsAfter", pending.input.restSecondsAfter ?: JSONObject.NULL)
        put("commitId", pending.input.commitId)
    }.toString()

    private fun decodePendingSingleSetCommit(encoded: String): PendingSingleSetCommit {
        val value = JSONObject(encoded)
        require(value.getInt("version") == 1)
        fun optionalDouble(key: String): Double? = if (value.isNull(key)) null else value.getDouble(key)
        fun optionalInt(key: String): Int? = if (value.isNull(key)) null else value.getInt(key)
        fun optionalString(key: String): String? = if (value.isNull(key)) null else value.getString(key)
        return PendingSingleSetCommit(
            sessionId = value.getLong("sessionId"),
            exerciseId = value.getLong("exerciseId"),
            submittedAtMillis = value.getLong("submittedAtMillis"),
            input = WorkoutSetInput(
                weightKg = value.getDouble("weightKg"),
                reps = value.getInt("reps"),
                durationSeconds = value.getInt("durationSeconds"),
                completed = value.getBoolean("completed"),
                isWarmup = value.getBoolean("isWarmup"),
                rpe = optionalDouble("rpe"),
                rir = optionalDouble("rir"),
                note = value.getString("note"),
                supersetId = optionalString("supersetId"),
                restSecondsAfter = optionalInt("restSecondsAfter"),
                commitId = value.getString("commitId"),
            ),
        ).also { pending ->
            require(pending.sessionId > 0L && pending.exerciseId > 0L && pending.submittedAtMillis > 0L)
            require(pending.input.commitId.isNotBlank() && pending.input.commitId.length <= 128)
        }
    }

    fun isProfileConfigured(): Boolean = preferences.getBoolean(KEY_PROFILE_CONFIGURED, false)

    fun markProfileConfigured() = ledgerWrite {
        check(preferences.edit().putBoolean(KEY_PROFILE_CONFIGURED, true).commit()) {
            "无法持久化资料配置状态"
        }
    }

}
