package com.personal.fitnessledger.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Raw, not-yet-submitted set editor state. Text is intentionally retained as
 * entered so a process restart never silently replaces an incomplete value.
 */
data class WorkoutEditorDraft(
    val sessionId: Long,
    val selectedExerciseId: Long,
    val weightText: String,
    val repsText: String,
    val durationText: String,
    val rpeText: String,
    val rirText: String,
    val noteText: String,
    val isWarmup: Boolean,
    val isFailed: Boolean,
    val autoRest: Boolean,
    val restSeconds: Int,
    val selectedSupersetId: String?,
    val commitId: String,
    val revision: Long,
    val updatedAtMillis: Long,
)

data class WorkoutPlanItem(
    val id: String,
    val plannedCommitId: String,
    val exerciseId: Long,
    val weightKg: Double,
    val reps: Int,
    val durationSeconds: Int,
    val isWarmup: Boolean,
    val rpe: Double?,
    val rir: Double?,
    val note: String,
    val supersetId: String?,
    val autoRest: Boolean,
    val restSeconds: Int,
)

data class WorkoutTemplate(
    val id: String,
    val name: String,
    val items: List<WorkoutPlanItem>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class ActiveWorkoutPlan(
    val sessionId: Long,
    val sourceLabel: String,
    val items: List<WorkoutPlanItem>,
    val revision: Long,
    val updatedAtMillis: Long,
)

data class WorkoutPlannerSnapshot(
    val editorDraft: WorkoutEditorDraft?,
    val activePlan: ActiveWorkoutPlan?,
    val templates: List<WorkoutTemplate>,
    val barbellBarWeightKg: Double,
)

fun newWorkoutEditorDraft(
    sessionId: Long,
    exercise: Exercise,
    restSeconds: Int = 120,
    nowMillis: Long = System.currentTimeMillis(),
): WorkoutEditorDraft = WorkoutEditorDraft(
    sessionId = sessionId,
    selectedExerciseId = exercise.id,
    weightText = when (exercise.trackingType) {
        TrackingType.BODYWEIGHT_REPS -> "0"
        TrackingType.ASSISTED_REPS -> "30"
        else -> "80"
    },
    repsText = "8",
    durationText = "60",
    rpeText = "",
    rirText = "",
    noteText = "",
    isWarmup = false,
    isFailed = false,
    autoRest = true,
    restSeconds = restSeconds.coerceIn(15, 3_600),
    selectedSupersetId = null,
    commitId = UUID.randomUUID().toString(),
    revision = 1L,
    updatedAtMillis = nowMillis,
)

fun WorkoutEditorDraft.afterCommittedSet(nowMillis: Long = System.currentTimeMillis()): WorkoutEditorDraft = copy(
    rpeText = "",
    rirText = "",
    noteText = "",
    isWarmup = false,
    isFailed = false,
    selectedSupersetId = null,
    commitId = UUID.randomUUID().toString(),
    revision = revision + 1L,
    updatedAtMillis = nowMillis.coerceAtLeast(updatedAtMillis + 1L),
)

fun WorkoutPlanItem.freshCopy(): WorkoutPlanItem = copy(
    id = UUID.randomUUID().toString(),
    plannedCommitId = UUID.randomUUID().toString(),
)

fun WorkoutPlanItem.toEditorDraft(
    current: WorkoutEditorDraft,
    nowMillis: Long = System.currentTimeMillis(),
): WorkoutEditorDraft = current.copy(
    selectedExerciseId = exerciseId,
    weightText = compactPlannerNumber(weightKg),
    repsText = reps.toString(),
    durationText = durationSeconds.toString(),
    rpeText = rpe?.let(::compactPlannerNumber).orEmpty(),
    rirText = rir?.let(::compactPlannerNumber).orEmpty(),
    noteText = note,
    isWarmup = isWarmup,
    isFailed = false,
    autoRest = autoRest,
    restSeconds = restSeconds,
    selectedSupersetId = supersetId,
    commitId = plannedCommitId,
    revision = current.revision + 1L,
    updatedAtMillis = nowMillis.coerceAtLeast(current.updatedAtMillis + 1L),
)

fun WorkoutHistoryDetail.toPlanItems(): List<WorkoutPlanItem> {
    val copiedRestSeconds = summary.session.restDurationSeconds.coerceIn(15, 3_600)
    return sets
        .asSequence()
        // A failed attempt is an observation, not a future planned completed set.
        .filter { it.set.completed }
        .map { record ->
            WorkoutPlanItem(
                id = UUID.randomUUID().toString(),
                plannedCommitId = UUID.randomUUID().toString(),
                exerciseId = record.set.exerciseId,
                weightKg = record.set.weightKg,
                reps = record.set.reps,
                durationSeconds = record.set.durationSeconds,
                isWarmup = record.set.isWarmup,
                rpe = record.set.rpe,
                rir = record.set.rir,
                note = record.set.note,
                supersetId = record.set.supersetId,
                autoRest = true,
                restSeconds = copiedRestSeconds,
            )
        }
        .toList()
}

fun WorkoutSet.toPlanItem(restSeconds: Int = 120): WorkoutPlanItem = WorkoutPlanItem(
    id = UUID.randomUUID().toString(),
    plannedCommitId = UUID.randomUUID().toString(),
    exerciseId = exerciseId,
    weightKg = weightKg,
    reps = reps,
    durationSeconds = durationSeconds,
    isWarmup = isWarmup,
    rpe = rpe,
    rir = rir,
    note = note,
    supersetId = supersetId,
    autoRest = true,
    restSeconds = restSeconds.coerceIn(15, 3_600),
)

internal fun compactPlannerNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

internal fun WorkoutEditorDraft.persistenceError(): String? = when {
    sessionId <= 0L || selectedExerciseId <= 0L -> "训练草稿不属于有效动作或场次"
    listOf(weightText, repsText, durationText, rpeText, rirText).any { it.length > 64 } -> "训练草稿数字字段过长"
    noteText.length > 500 -> "训练草稿备注不能超过 500 字"
    restSeconds !in 15..3_600 -> "休息时间必须在 15–3600 秒"
    selectedSupersetId != null && !selectedSupersetId.matches(Regex("[A-Z0-9_-]{1,20}")) -> "超级组标识无效"
    commitId.isBlank() || commitId.length > 128 -> "训练草稿提交标识无效"
    revision <= 0L || updatedAtMillis <= 0L -> "训练草稿版本无效"
    else -> null
}

internal fun WorkoutPlanItem.persistenceError(): String? = when {
    id.isBlank() || id.length > 128 || plannedCommitId.isBlank() || plannedCommitId.length > 128 -> "计划项标识无效"
    exerciseId <= 0L -> "计划项动作无效"
    !weightKg.isFinite() || weightKg !in 0.0..1_000.0 -> "计划项重量必须在 0–1000 kg"
    reps !in 0..1_000 -> "计划项次数必须在 0–1000"
    durationSeconds !in 0..86_400 -> "计划项时长必须在 0–86400 秒"
    rpe != null && (!rpe.isFinite() || rpe !in 0.0..10.0) -> "计划项 RPE 必须在 0–10"
    rir != null && (!rir.isFinite() || rir !in 0.0..10.0) -> "计划项 RIR 必须在 0–10"
    note.length > 500 -> "计划项备注不能超过 500 字"
    supersetId != null && !supersetId.matches(Regex("[A-Z0-9_-]{1,20}")) -> "计划项超级组标识无效"
    restSeconds !in 15..3_600 -> "计划项休息时间必须在 15–3600 秒"
    else -> null
}

/** One preference value is the transaction boundary for templates, active plan,
 * raw editor state, and bar configuration. */
internal class WorkoutPlannerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(activeSessionId: Long?, committedSetIds: Set<String>): WorkoutPlannerSnapshot = synchronized(preferences) {
        val original = readEnvelopeLocked()
        var draft = original.editorDraft?.takeIf { it.sessionId == activeSessionId }
        var plan = original.activePlan?.takeIf { it.sessionId == activeSessionId }
        if (plan != null) {
            plan = plan.copy(items = plan.items.filterNot { it.plannedCommitId in committedSetIds })
                .takeIf { it.items.isNotEmpty() }
        }
        if (draft != null && draft.commitId in committedSetIds) {
            draft = draft.afterCommittedSet()
        }
        val reconciled = original.copy(editorDraft = draft, activePlan = plan)
        if (reconciled != original) writeEnvelopeLocked(reconciled)
        reconciled.snapshot()
    }

    fun beginSession(draft: WorkoutEditorDraft, plan: ActiveWorkoutPlan?): WorkoutPlannerSnapshot = synchronized(preferences) {
        draft.persistenceError()?.let { throw IllegalArgumentException(it) }
        require(plan == null || plan.sessionId == draft.sessionId)
        plan?.let(::validateActivePlan)
        val current = readEnvelopeLocked()
        val updated = current.copy(
            editorDraft = draft,
            activePlan = plan?.takeIf { it.items.isNotEmpty() },
            clearedSessionId = current.clearedSessionId.takeUnless { it == draft.sessionId },
        )
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun saveEditorDraft(draft: WorkoutEditorDraft): Boolean = synchronized(preferences) {
        draft.persistenceError()?.let { throw IllegalArgumentException(it) }
        val current = readEnvelopeLocked()
        if (current.clearedSessionId == draft.sessionId) return@synchronized false
        val stored = current.editorDraft
        if (stored != null && stored.sessionId == draft.sessionId && stored.revision > draft.revision) return@synchronized false
        writeEnvelopeLocked(current.copy(editorDraft = draft))
        true
    }

    fun replaceActivePlanning(plan: ActiveWorkoutPlan?, draft: WorkoutEditorDraft): WorkoutPlannerSnapshot = synchronized(preferences) {
        draft.persistenceError()?.let { throw IllegalArgumentException(it) }
        require(plan == null || plan.sessionId == draft.sessionId)
        plan?.let(::validateActivePlan)
        val current = readEnvelopeLocked()
        require(current.clearedSessionId != draft.sessionId) { "训练已结束，不能再修改计划" }
        val updated = current.copy(editorDraft = draft, activePlan = plan?.takeIf { it.items.isNotEmpty() })
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun completeSubmission(
        sessionId: Long,
        committedId: String,
        nextDraft: WorkoutEditorDraft,
    ): WorkoutPlannerSnapshot = synchronized(preferences) {
        require(nextDraft.sessionId == sessionId)
        nextDraft.persistenceError()?.let { throw IllegalArgumentException(it) }
        val current = readEnvelopeLocked()
        require(current.clearedSessionId != sessionId) { "训练已结束，不能再完成旧提交" }
        val remaining = current.activePlan
            ?.takeIf { it.sessionId == sessionId }
            ?.let { plan ->
                plan.copy(
                    items = plan.items.filterNot { it.plannedCommitId == committedId },
                    revision = plan.revision + 1L,
                    updatedAtMillis = System.currentTimeMillis().coerceAtLeast(plan.updatedAtMillis + 1L),
                )
            }
            ?.takeIf { it.items.isNotEmpty() }
        val updated = current.copy(editorDraft = nextDraft, activePlan = remaining)
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun clearSession(sessionId: Long): WorkoutPlannerSnapshot = synchronized(preferences) {
        val current = readEnvelopeLocked()
        val updated = current.copy(
            editorDraft = current.editorDraft?.takeUnless { it.sessionId == sessionId },
            activePlan = current.activePlan?.takeUnless { it.sessionId == sessionId },
            clearedSessionId = sessionId,
        )
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun saveTemplate(template: WorkoutTemplate): WorkoutPlannerSnapshot = synchronized(preferences) {
        validateTemplate(template)
        val current = readEnvelopeLocked()
        require(current.templates.any { it.id == template.id } || current.templates.size < MAX_TEMPLATES) {
            "训练模板最多保存 $MAX_TEMPLATES 份；请先删除不再需要的模板"
        }
        val templates = (current.templates.filterNot { it.id == template.id } + template)
            .sortedByDescending { it.updatedAtMillis }
        val updated = current.copy(templates = templates)
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun deleteTemplate(templateId: String): WorkoutPlannerSnapshot = synchronized(preferences) {
        require(templateId.isNotBlank() && templateId.length <= 128)
        val current = readEnvelopeLocked()
        val updated = current.copy(templates = current.templates.filterNot { it.id == templateId })
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    fun saveBarbellBarWeight(weightKg: Double): WorkoutPlannerSnapshot = synchronized(preferences) {
        require(weightKg.isFinite() && weightKg in 0.0..50.0) { "杆重必须在 0–50 kg" }
        val current = readEnvelopeLocked()
        val updated = current.copy(barbellBarWeightKg = weightKg)
        writeEnvelopeLocked(updated)
        updated.snapshot()
    }

    private fun validateTemplate(template: WorkoutTemplate) {
        require(template.id.isNotBlank() && template.id.length <= 128) { "模板标识无效" }
        require(template.name.trim().length in 1..60) { "模板名称必须为 1–60 个字符" }
        require(template.items.isNotEmpty() && template.items.size <= MAX_PLAN_ITEMS) { "模板必须包含 1–$MAX_PLAN_ITEMS 个计划组" }
        require(template.createdAtMillis > 0L && template.updatedAtMillis >= template.createdAtMillis) { "模板时间无效" }
        template.items.forEach { it.persistenceError()?.let { error -> throw IllegalArgumentException(error) } }
        require(template.items.map { it.id }.toSet().size == template.items.size) { "模板计划项标识重复" }
    }

    private fun validateActivePlan(plan: ActiveWorkoutPlan) {
        require(plan.sessionId > 0L) { "活动计划场次无效" }
        require(plan.sourceLabel.trim().length in 1..120) { "活动计划来源无效" }
        require(plan.items.isNotEmpty() && plan.items.size <= MAX_PLAN_ITEMS) {
            "活动计划必须包含 1–$MAX_PLAN_ITEMS 个计划组"
        }
        require(plan.revision > 0L && plan.updatedAtMillis > 0L) { "活动计划版本无效" }
        plan.items.forEach { it.persistenceError()?.let { error -> throw IllegalArgumentException(error) } }
        require(plan.items.map { it.id }.toSet().size == plan.items.size) { "活动计划项标识重复" }
        require(plan.items.map { it.plannedCommitId }.toSet().size == plan.items.size) { "活动计划提交标识重复" }
    }

    private fun readEnvelopeLocked(): PlannerEnvelope {
        val encoded = preferences.getString(KEY_ENVELOPE, "").orEmpty()
        if (encoded.isBlank()) return PlannerEnvelope()
        return runCatching { decodeEnvelope(encoded) }.getOrElse {
            // Keep the damaged payload for support instead of repeatedly crashing.
            preferences.edit().putString(KEY_CORRUPT_BACKUP, encoded.take(200_000)).remove(KEY_ENVELOPE).commit()
            PlannerEnvelope()
        }
    }

    private fun writeEnvelopeLocked(envelope: PlannerEnvelope) {
        check(preferences.edit().putString(KEY_ENVELOPE, encodeEnvelope(envelope)).commit()) {
            "无法持久化训练草稿与模板"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "workout_planner"
        const val KEY_ENVELOPE = "workout_planner_envelope_v1"
        const val KEY_CORRUPT_BACKUP = "workout_planner_corrupt_backup_v1"
        const val MAX_TEMPLATES = 100
        const val MAX_PLAN_ITEMS = 500
    }
}

private data class PlannerEnvelope(
    val editorDraft: WorkoutEditorDraft? = null,
    val activePlan: ActiveWorkoutPlan? = null,
    val templates: List<WorkoutTemplate> = emptyList(),
    val barbellBarWeightKg: Double = 20.0,
    val clearedSessionId: Long? = null,
) {
    fun snapshot() = WorkoutPlannerSnapshot(editorDraft, activePlan, templates, barbellBarWeightKg)
}

private fun encodeEnvelope(envelope: PlannerEnvelope): String = JSONObject().apply {
    put("version", 1)
    put("editorDraft", envelope.editorDraft?.let(::encodeEditorDraft) ?: JSONObject.NULL)
    put("activePlan", envelope.activePlan?.let(::encodeActivePlan) ?: JSONObject.NULL)
    put("templates", JSONArray().apply { envelope.templates.forEach { put(encodeTemplate(it)) } })
    put("barbellBarWeightKg", envelope.barbellBarWeightKg)
    put("clearedSessionId", envelope.clearedSessionId ?: JSONObject.NULL)
}.toString()

private fun decodeEnvelope(encoded: String): PlannerEnvelope {
    val value = JSONObject(encoded)
    require(value.getInt("version") == 1)
    val templatesArray = value.getJSONArray("templates")
    require(templatesArray.length() <= 100)
    val templates = List(templatesArray.length()) { decodeTemplate(templatesArray.getJSONObject(it)) }
    val result = PlannerEnvelope(
        editorDraft = if (value.isNull("editorDraft")) null else decodeEditorDraft(value.getJSONObject("editorDraft")),
        activePlan = if (value.isNull("activePlan")) null else decodeActivePlan(value.getJSONObject("activePlan")),
        templates = templates,
        barbellBarWeightKg = value.getDouble("barbellBarWeightKg"),
        clearedSessionId = if (value.isNull("clearedSessionId")) null else value.getLong("clearedSessionId"),
    )
    require(result.barbellBarWeightKg.isFinite() && result.barbellBarWeightKg in 0.0..50.0)
    result.editorDraft?.persistenceError()?.let { throw IllegalArgumentException(it) }
    result.activePlan?.let { plan ->
        require(plan.sessionId > 0L && plan.sourceLabel.trim().length in 1..120 && plan.items.size in 1..500)
        require(plan.revision > 0L && plan.updatedAtMillis > 0L)
        plan.items.forEach { it.persistenceError()?.let { error -> throw IllegalArgumentException(error) } }
        require(plan.items.map { it.id }.toSet().size == plan.items.size)
        require(plan.items.map { it.plannedCommitId }.toSet().size == plan.items.size)
    }
    templates.forEach { template ->
        require(template.id.isNotBlank() && template.name.trim().length in 1..60 && template.items.isNotEmpty())
        require(template.createdAtMillis > 0L && template.updatedAtMillis >= template.createdAtMillis)
        template.items.forEach { it.persistenceError()?.let { error -> throw IllegalArgumentException(error) } }
        require(template.items.map { it.id }.toSet().size == template.items.size)
    }
    return result
}

private fun encodeEditorDraft(draft: WorkoutEditorDraft) = JSONObject().apply {
    put("sessionId", draft.sessionId)
    put("selectedExerciseId", draft.selectedExerciseId)
    put("weightText", draft.weightText)
    put("repsText", draft.repsText)
    put("durationText", draft.durationText)
    put("rpeText", draft.rpeText)
    put("rirText", draft.rirText)
    put("noteText", draft.noteText)
    put("isWarmup", draft.isWarmup)
    put("isFailed", draft.isFailed)
    put("autoRest", draft.autoRest)
    put("restSeconds", draft.restSeconds)
    put("selectedSupersetId", draft.selectedSupersetId ?: JSONObject.NULL)
    put("commitId", draft.commitId)
    put("revision", draft.revision)
    put("updatedAtMillis", draft.updatedAtMillis)
}

private fun decodeEditorDraft(value: JSONObject) = WorkoutEditorDraft(
    sessionId = value.getLong("sessionId"),
    selectedExerciseId = value.getLong("selectedExerciseId"),
    weightText = value.getString("weightText"),
    repsText = value.getString("repsText"),
    durationText = value.getString("durationText"),
    rpeText = value.getString("rpeText"),
    rirText = value.getString("rirText"),
    noteText = value.getString("noteText"),
    isWarmup = value.getBoolean("isWarmup"),
    isFailed = value.getBoolean("isFailed"),
    autoRest = value.getBoolean("autoRest"),
    restSeconds = value.getInt("restSeconds"),
    selectedSupersetId = value.optionalString("selectedSupersetId"),
    commitId = value.getString("commitId"),
    revision = value.getLong("revision"),
    updatedAtMillis = value.getLong("updatedAtMillis"),
)

private fun encodePlanItem(item: WorkoutPlanItem) = JSONObject().apply {
    put("id", item.id)
    put("plannedCommitId", item.plannedCommitId)
    put("exerciseId", item.exerciseId)
    put("weightKg", item.weightKg)
    put("reps", item.reps)
    put("durationSeconds", item.durationSeconds)
    put("isWarmup", item.isWarmup)
    put("rpe", item.rpe ?: JSONObject.NULL)
    put("rir", item.rir ?: JSONObject.NULL)
    put("note", item.note)
    put("supersetId", item.supersetId ?: JSONObject.NULL)
    put("autoRest", item.autoRest)
    put("restSeconds", item.restSeconds)
}

private fun decodePlanItem(value: JSONObject) = WorkoutPlanItem(
    id = value.getString("id"),
    plannedCommitId = value.getString("plannedCommitId"),
    exerciseId = value.getLong("exerciseId"),
    weightKg = value.getDouble("weightKg"),
    reps = value.getInt("reps"),
    durationSeconds = value.getInt("durationSeconds"),
    isWarmup = value.getBoolean("isWarmup"),
    rpe = value.optionalDouble("rpe"),
    rir = value.optionalDouble("rir"),
    note = value.getString("note"),
    supersetId = value.optionalString("supersetId"),
    autoRest = value.getBoolean("autoRest"),
    restSeconds = value.getInt("restSeconds"),
)

private fun encodeActivePlan(plan: ActiveWorkoutPlan) = JSONObject().apply {
    put("sessionId", plan.sessionId)
    put("sourceLabel", plan.sourceLabel)
    put("items", JSONArray().apply { plan.items.forEach { put(encodePlanItem(it)) } })
    put("revision", plan.revision)
    put("updatedAtMillis", plan.updatedAtMillis)
}

private fun decodeActivePlan(value: JSONObject): ActiveWorkoutPlan {
    val items = value.getJSONArray("items")
    require(items.length() <= 500)
    return ActiveWorkoutPlan(
        sessionId = value.getLong("sessionId"),
        sourceLabel = value.getString("sourceLabel"),
        items = List(items.length()) { decodePlanItem(items.getJSONObject(it)) },
        revision = value.getLong("revision"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
}

private fun encodeTemplate(template: WorkoutTemplate) = JSONObject().apply {
    put("id", template.id)
    put("name", template.name)
    put("items", JSONArray().apply { template.items.forEach { put(encodePlanItem(it)) } })
    put("createdAtMillis", template.createdAtMillis)
    put("updatedAtMillis", template.updatedAtMillis)
}

private fun decodeTemplate(value: JSONObject): WorkoutTemplate {
    val items = value.getJSONArray("items")
    require(items.length() in 1..500)
    return WorkoutTemplate(
        id = value.getString("id"),
        name = value.getString("name"),
        items = List(items.length()) { decodePlanItem(items.getJSONObject(it)) },
        createdAtMillis = value.getLong("createdAtMillis"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
}

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.optionalDouble(key: String): Double? =
    if (isNull(key)) null else getDouble(key)
