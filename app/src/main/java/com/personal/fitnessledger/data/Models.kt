package com.personal.fitnessledger.data

import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

data class Nutrition(
    val kcal: Double = 0.0,
    val carbsG: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
) {
    operator fun plus(other: Nutrition) = Nutrition(
        kcal = kcal + other.kcal,
        carbsG = carbsG + other.carbsG,
        proteinG = proteinG + other.proteinG,
        fatG = fatG + other.fatG,
    )

    operator fun minus(other: Nutrition) = Nutrition(
        kcal = kcal - other.kcal,
        carbsG = carbsG - other.carbsG,
        proteinG = proteinG - other.proteinG,
        fatG = fatG - other.fatG,
    )

    operator fun times(multiplier: Double) = Nutrition(
        kcal = kcal * multiplier,
        carbsG = carbsG * multiplier,
        proteinG = proteinG * multiplier,
        fatG = fatG * multiplier,
    )
}

data class NutritionRange(
    val minimum: Nutrition,
    val maximum: Nutrition,
)

data class UserProfile(
    val heightCm: Double = 175.0,
    val referenceWeightKg: Double = 80.0,
    val waistCm: Double = 88.0,
    val carbFactor: Double = 3.0,
    val proteinFactor: Double = 1.6,
    val fatFactor: Double = 0.7,
) {
    val dailyTarget: Nutrition
        get() {
            val carbs = referenceWeightKg * carbFactor
            val protein = referenceWeightKg * proteinFactor
            val fat = referenceWeightKg * fatFactor
            return Nutrition(
                kcal = carbs * 4.0 + protein * 4.0 + fat * 9.0,
                carbsG = carbs,
                proteinG = protein,
                fatG = fat,
            )
        }
}

fun UserProfile.validationError(): String? = when {
    !heightCm.isFinite() || heightCm !in 100.0..250.0 -> "身高必须在 100–250 cm"
    !referenceWeightKg.isFinite() || referenceWeightKg !in 30.0..300.0 -> "参考体重必须在 30–300 kg"
    !waistCm.isFinite() || waistCm !in 30.0..250.0 -> "腰围必须在 30–250 cm"
    !carbFactor.isFinite() || carbFactor !in 0.0..10.0 -> "碳水系数必须在 0–10 g/kg"
    !proteinFactor.isFinite() || proteinFactor !in 0.5..4.0 -> "蛋白质系数必须在 0.5–4 g/kg"
    !fatFactor.isFinite() || fatFactor !in 0.1..5.0 -> "脂肪系数必须在 0.1–5 g/kg"
    else -> null
}

data class BodyMeasurement(
    val id: Long = 0,
    val date: LocalDate,
    val weightKg: Double,
    val waistCm: Double?,
    val cloudMeasuredAtSeconds: Long? = null,
    val cloudOffsetSeconds: Int = 0,
    val bodyFatPercent: Double? = null,
    val waterPercent: Double? = null,
)

val BodyMeasurement.isXiaomi: Boolean get() = cloudMeasuredAtSeconds != null

/**
 * Durable, deliberately unparsed input for the body-measurement editor.
 *
 * Weight and waist stay as strings so an Activity/process recreation can
 * restore intermediate input such as `79.` instead of silently normalizing or
 * rejecting it. [measurementId] keeps an edit tied to the original database
 * row even when the user changes its date.
 */
data class BodyMeasurementFormDraft(
    val id: String = UUID.randomUUID().toString(),
    val measurementId: Long = 0L,
    val date: LocalDate,
    val weightText: String,
    val waistText: String,
    /** Stable identity of the Today quick-action request bound to this form. */
    val shortcutRequestId: String? = null,
    val revision: Long = 0L,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

fun BodyMeasurementFormDraft.persistenceValidationError(): String? = when {
    id.isBlank() || id.length > 128 || measurementId < 0L -> "身体表单草稿标识无效"
    shortcutRequestId != null && (shortcutRequestId.isBlank() || shortcutRequestId.length > 128) ->
        "身体记录入口标识无效"
    weightText.length > 64 || waistText.length > 64 -> "身体表单数值文字过长"
    revision < 0L || updatedAtMillis <= 0L -> "身体表单草稿版本无效"
    else -> null
}

/**
 * Clock-independent validation shared by database writes and defensive reads.
 *
 * A persisted local calendar date remains structurally valid if the device later
 * crosses the international date line. Consequently this function must never
 * compare [date] with the device's current date.
 */
fun BodyMeasurement.structuralValidationError(): String? = when {
    id < 0L -> "身体记录标识无效"
    date.toString().length != 10 -> "身体记录日期超出持久化范围"
    !weightKg.isFinite() || weightKg !in 30.0..300.0 -> "体重必须在 30–300 kg"
    waistCm != null && (!waistCm.isFinite() || waistCm !in 30.0..250.0) -> "腰围必须在 30–250 cm"
    else -> null
}

/** Business validation for a new or edited body-record submission. */
fun BodyMeasurement.validationError(currentDate: LocalDate = LocalDate.now()): String? = when {
    date > currentDate -> "身体数据不能记录到未来日期"
    else -> structuralValidationError()
}

enum class EvidenceTier(val label: String) {
    A("高可靠"),
    B("较可靠"),
    C("粗略估算"),
    D("信息不足"),
}

enum class PortionBasis {
    AI_SINGLE_PHOTO,
    AI_REFERENCE_OBJECT,
    USER_ESTIMATE,
    USER_WEIGHT,
    PACKAGE_WEIGHT,
    STANDARD_PORTION,
    SAVED_RECIPE,
}

enum class CalorieSource {
    DERIVED_FROM_MACROS,
    LABEL_OR_DATABASE,
}

enum class DraftState {
    PHOTO_SELECTED,
    ANALYZING,
    DRAFT_READY,
    EDITING,
    READY_TO_CONFIRM,
    COMMITTING,
    COMMITTED,
    ANALYSIS_FAILED,
    DISCARDED,
}

enum class AnalysisMode {
    REMOTE_AI,
    ON_DEVICE_AI,
    INTERACTIVE_DEMO,
    MANUAL,
}

enum class RiskFlag(val label: String) {
    UNKNOWN_PORTION("可食用克重尚未确认"),
    UNKNOWN_OIL("烹调油尚未确认"),
    UNKNOWN_SAUCE("酱汁用量尚未确认"),
    MIXED_DISH("混合菜成分不完全可见"),
    HIDDEN_INGREDIENTS("可能存在隐藏配料"),
    LOW_IDENTITY_CONFIDENCE("食物身份置信度较低"),
}

data class FoodDraftItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val grams: Double,
    val gramsMin: Double,
    val gramsMax: Double,
    val per100g: Nutrition,
    val sourceName: String,
    val portionBasis: PortionBasis,
    val evidenceTier: EvidenceTier,
    val riskFlags: Set<RiskFlag> = emptySet(),
    val alternatives: List<String> = emptyList(),
    val userModified: Boolean = false,
    val calorieSource: CalorieSource = CalorieSource.LABEL_OR_DATABASE,
) {
    val nutrition: Nutrition get() = per100g * (grams / 100.0)
    val nutritionRange: NutritionRange
        get() = NutritionRange(
            minimum = per100g * (gramsMin / 100.0),
            maximum = per100g * (gramsMax / 100.0),
        )

    fun withGrams(newGrams: Double): FoodDraftItem {
        val safe = max(1.0, newGrams)
        val widthBelow = max(0.0, grams - gramsMin)
        val widthAbove = max(0.0, gramsMax - grams)
        return copy(
            grams = safe,
            gramsMin = max(1.0, safe - widthBelow),
            gramsMax = safe + widthAbove,
            userModified = true,
        )
    }

    /** Scale both sides of a photo estimate with the eaten portion. This is not
     * a new weighing and must never promote the evidence tier. */
    fun scaledPortion(fraction: Double): FoodDraftItem {
        require(fraction.isFinite() && fraction > 0.0 && fraction <= 1.0)
        val newGrams = grams * fraction
        require(newGrams >= 1.0) { "调整后的份量不能小于 1 g" }
        return copy(
            grams = newGrams,
            gramsMin = (gramsMin * fraction).coerceAtLeast(1.0),
            gramsMax = gramsMax * fraction,
            userModified = true,
        )
    }
}

/**
 * One possible name for the single dish shown in a photo.
 *
 * Top-k hypotheses are alternatives for the same image, never separate foods.
 * [suggestedItem] is present only for the small, reviewed FNDDS allow-list; an
 * unmapped hypothesis must be completed by the user before it can become a
 * ledger item.
 */
data class FoodHypothesis(
    val labelId: Int,
    val rawLabel: String,
    val displayName: String,
    val modelScore: Double,
    val canonicalKey: String,
    val suggestedItem: FoodDraftItem? = null,
)

/**
 * Durable, deliberately unparsed input for the manual-food editor. Keeping the
 * raw strings is important: a process can be killed while the user is halfway
 * through a decimal or while a field is temporarily invalid. Converting to
 * [FoodDraftItem] happens only after the form validates and the user taps Save.
 */
data class ManualFoodFormDraft(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String = UUID.randomUUID().toString(),
    val targetDate: LocalDate,
    val initialGrams: Double,
    val initialGramsMin: Double,
    val initialGramsMax: Double,
    val initialPortionBasis: PortionBasis,
    val name: String,
    val gramsText: String,
    val kcalText: String,
    val carbsText: String,
    val proteinText: String,
    val fatText: String,
    val sourceName: String,
    val weighed: Boolean,
    val useLabelKcal: Boolean,
    val isDirty: Boolean = false,
    val revision: Long = 0L,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

fun ManualFoodFormDraft.persistenceValidationError(): String? = when {
    id.isBlank() || id.length > 128 || itemId.isBlank() || itemId.length > 128 ->
        "手工饮食草稿标识无效"
    !initialGrams.isFinite() || !initialGramsMin.isFinite() || !initialGramsMax.isFinite() ||
        initialGrams <= 0.0 || initialGramsMin <= 0.0 || initialGramsMax < initialGramsMin ->
        "手工饮食草稿的初始份量无效"
    name.length > 512 || sourceName.length > 512 -> "手工饮食草稿文字过长"
    listOf(gramsText, kcalText, carbsText, proteinText, fatText).any { it.length > 64 } ->
        "手工饮食草稿数值文字过长"
    revision < 0L || updatedAtMillis <= 0L -> "手工饮食草稿版本无效"
    else -> null
}

/** Parses the latest durable manual editor state only at the terminal Save action. */
fun ManualFoodFormDraft.toFoodDraftItem(): FoodDraftItem {
    persistenceValidationError()?.let { throw IllegalArgumentException(it) }
    fun number(raw: String, label: String): Double = raw.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?: throw IllegalArgumentException("$label 必须是有效数字")

    val exactName = name.trim()
    val exactSource = sourceName.trim()
    require(exactName.isNotEmpty() && exactName.length <= 80) { "食物名称必须为 1–80 个字符" }
    require(exactSource.isNotEmpty() && exactSource.length <= 120) { "营养来源必须为 1–120 个字符" }
    val exactGrams = number(gramsText, "可食用克重")
    val carbs = number(carbsText, "每 100 g 碳水")
    val protein = number(proteinText, "每 100 g 蛋白质")
    val fat = number(fatText, "每 100 g 脂肪")
    require(exactGrams in 1.0..5_000.0) { "可食用克重必须在 1–5000 g" }
    require(carbs in 0.0..100.0 && protein in 0.0..100.0 && fat in 0.0..100.0) {
        "每 100 g 三大营养素必须分别在 0–100 g"
    }
    require(carbs + protein + fat <= 100.5) { "每 100 g 三大营养素合计不能超过 100.5 g" }
    val macroKcal = carbs * 4.0 + protein * 4.0 + fat * 9.0
    val kcal = if (useLabelKcal) number(kcalText, "每 100 g 热量") else macroKcal
    require(kcal in 0.0..1_000.0) { "每 100 g 热量必须在 0–1000 kcal" }
    require(!useLabelKcal || macroKcal <= 0.5 || kcal > 0.0) {
        "三大营养素不为零时，包装或数据库热量不能为 0"
    }

    val initial = FoodDraftItem(
        id = itemId,
        name = exactName,
        grams = initialGrams,
        gramsMin = initialGramsMin,
        gramsMax = initialGramsMax,
        per100g = Nutrition(kcal = kcal, carbsG = carbs, proteinG = protein, fatG = fat),
        sourceName = exactSource,
        portionBasis = initialPortionBasis,
        evidenceTier = EvidenceTier.C,
        userModified = true,
        calorieSource = if (useLabelKcal) CalorieSource.LABEL_OR_DATABASE else CalorieSource.DERIVED_FROM_MACROS,
    )
    val moved = initial.withGrams(exactGrams)
    return moved.copy(
        grams = exactGrams,
        gramsMin = when {
            weighed -> exactGrams
            initialPortionBasis == PortionBasis.USER_WEIGHT -> max(1.0, exactGrams * 0.8)
            else -> moved.gramsMin
        },
        gramsMax = when {
            weighed -> exactGrams
            initialPortionBasis == PortionBasis.USER_WEIGHT -> exactGrams * 1.2
            else -> moved.gramsMax
        },
        portionBasis = if (weighed) PortionBasis.USER_WEIGHT else when (initialPortionBasis) {
            PortionBasis.USER_WEIGHT -> PortionBasis.USER_ESTIMATE
            else -> initialPortionBasis
        },
    )
}

data class MealDraft(
    val id: String = UUID.randomUUID().toString(),
    val commitId: String = UUID.randomUUID().toString(),
    val photoUri: String,
    val state: DraftState = DraftState.DRAFT_READY,
    val items: List<FoodDraftItem>,
    val evidenceTier: EvidenceTier,
    val evidenceReason: String,
    val unresolvedFlags: Set<RiskFlag>,
    val userReviewed: Boolean = false,
    val providerLabel: String,
    val analysisMode: AnalysisMode = AnalysisMode.REMOTE_AI,
    val targetDate: LocalDate? = null,
    val replacesMealId: Long? = null,
    val hypotheses: List<FoodHypothesis> = emptyList(),
) {
    val total: Nutrition get() = items.fold(Nutrition()) { total, item -> total + item.nutrition }
    val totalRange: NutritionRange
        get() = items.fold(NutritionRange(Nutrition(), Nutrition())) { total, item ->
            val itemRange = item.nutritionRange
            NutritionRange(
                minimum = total.minimum + itemRange.minimum,
                maximum = total.maximum + itemRange.maximum,
            )
        }
}

fun MealDraft.commitValidationError(): String? {
    if (analysisMode == AnalysisMode.INTERACTIVE_DEMO) return "交互演示数据不能计入正式账本，请先配置识别服务"
    if (analysisMode == AnalysisMode.ON_DEVICE_AI && items.isEmpty() && hypotheses.isNotEmpty()) {
        return "请先从本机模型候选中选择食物；候选本身不是营养记录"
    }
    if (!userReviewed) return "请先核对所有食材、克重、烹调油和酱汁"
    if (items.isEmpty()) return "至少需要一项食物"
    val weakestItemEvidence = strongestEvidence(items)
    if (evidenceTier == EvidenceTier.D || weakestItemEvidence == EvidenceTier.D) {
        return "当前照片信息不足，请补充或修改食物与份量后再入账"
    }
    if (evidenceTier.ordinal < weakestItemEvidence.ordinal) return "整餐可靠性与食物明细不一致，请重新分析或编辑"
    items.forEach { item ->
        if (item.name.isBlank()) return "食物名称不能为空"
        if (item.name.length > 80) return "食物名称不能超过 80 个字符"
        if (item.sourceName.isBlank()) return "营养来源不能为空"
        if (item.sourceName.length > 120) return "营养来源不能超过 120 个字符"
        if (!item.grams.isFinite() || item.grams !in 1.0..5_000.0) return "食物克重必须在 1–5000 g"
        if (!item.gramsMin.isFinite() || !item.gramsMax.isFinite() ||
            item.gramsMin <= 0.0 || item.gramsMin > item.grams || item.grams > item.gramsMax
        ) return "食物克重区间无效"
        val nutrients = listOf(
            item.per100g.kcal to 1_000.0,
            item.per100g.carbsG to 100.0,
            item.per100g.proteinG to 100.0,
            item.per100g.fatG to 100.0,
        )
        if (nutrients.any { (value, upper) -> !value.isFinite() || value !in 0.0..upper }) {
            return "每 100 g 营养值超出合理范围"
        }
        if (item.per100g.carbsG + item.per100g.proteinG + item.per100g.fatG > 100.5) {
            return "每 100 g 的碳水、蛋白质和脂肪总和不能超过 100.5 g"
        }
        if (analysisMode == AnalysisMode.ON_DEVICE_AI && item.per100g.kcal <= 0.0 &&
            item.per100g.carbsG <= 0.0 && item.per100g.proteinG <= 0.0 && item.per100g.fatG <= 0.0
        ) return "本机候选尚未填写营养值，请补全后再入账"
        if (item.calorieSource == CalorieSource.DERIVED_FROM_MACROS) {
            val macroKcal = item.per100g.carbsG * 4.0 + item.per100g.proteinG * 4.0 + item.per100g.fatG * 9.0
            if (abs(item.per100g.kcal - macroKcal) > 0.5) return "手工食物热量必须由三大营养素自动折算"
        }
        if (item.calorieSource == CalorieSource.LABEL_OR_DATABASE) {
            val macroKcal = item.per100g.carbsG * 4.0 + item.per100g.proteinG * 4.0 + item.per100g.fatG * 9.0
            if (macroKcal > 0.5 && item.per100g.kcal <= 0.0) return "包装或数据库热量不能为 0，请核对"
        }
    }
    return null
}

/** The persisted facts for one confirmed food, without reconstructed estimate ranges. */
data class MealFoodRecord(
    val id: Long,
    val name: String,
    val grams: Double,
    val per100g: Nutrition,
    val sourceName: String,
    val portionBasis: PortionBasis,
    val evidenceTier: EvidenceTier,
    val calorieSource: CalorieSource,
) {
    val nutrition: Nutrition get() = per100g * (grams / 100.0)
}

data class MealRecord(
    val id: Long = 0,
    val commitId: String,
    val date: LocalDate,
    val title: String,
    val nutrition: Nutrition,
    val evidenceTier: EvidenceTier,
    /** Original ledger confirmation time, not the time at which the meal was eaten. */
    val confirmedAtMillis: Long,
    val items: List<MealFoodRecord> = emptyList(),
    /** At least one stored food row could not be read; [nutrition] remains the stored meal total. */
    val itemDetailsIncomplete: Boolean = false,
)

data class SavedFood(
    val id: Long,
    val name: String,
    val per100g: Nutrition,
    val sourceName: String,
    val calorieSource: CalorieSource,
    val defaultGrams: Double,
    val isFavorite: Boolean,
    val useCount: Int,
    val lastUsedAtMillis: Long,
)

enum class TrackingType {
    WEIGHT_REPS,
    BODYWEIGHT_REPS,
    ASSISTED_REPS,
    DURATION,
}

data class Exercise(
    val id: Long = 0,
    val name: String,
    val category: String,
    val aliases: List<String> = emptyList(),
    val isCustom: Boolean,
    val isPrimary: Boolean,
    val trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    val definitionVersion: Int = 1,
    /** Archived actions stay addressable by immutable history/PR/template IDs, but are hidden from new logging. */
    val isArchived: Boolean = false,
)

data class WorkoutSet(
    val id: Long = 0,
    val sessionId: Long = 0,
    val exerciseId: Long,
    val setOrder: Int,
    val loadGrams: Long,
    val reps: Int,
    val durationSeconds: Int = 0,
    val completed: Boolean = true,
    val isWarmup: Boolean = false,
    val rpe: Double? = null,
    val rir: Double? = null,
    val note: String = "",
    val supersetId: String? = null,
    /** Non-null only when several completed sets were added as one confirmed batch (for example 5×5). */
    val batchId: String? = null,
    /**
     * Stable business idempotency key for one user-confirmed set write. Callers must
     * retain this value while retrying an uncertain write; the database returns the
     * already committed row when the same key and payload are submitted again.
     */
    val commitId: String = UUID.randomUUID().toString(),
) {
    val weightKg: Double get() = loadGrams / 1000.0
}

data class WorkoutSetInput(
    val weightKg: Double,
    val reps: Int,
    val durationSeconds: Int = 0,
    val completed: Boolean = true,
    val isWarmup: Boolean = false,
    val rpe: Double? = null,
    val rir: Double? = null,
    val note: String = "",
    val supersetId: String? = null,
    val restSecondsAfter: Int? = null,
    /** Stable for the lifetime of the visible form and regenerated only after success. */
    val commitId: String = UUID.randomUUID().toString(),
)

/** Durable intent written before a single-set database transaction starts. The
 * journal remains until UI has displayed the committed result long enough to
 * reject queued taps, or a restored process receives an explicit acknowledgement. */
data class PendingSingleSetCommit(
    val sessionId: Long,
    val exerciseId: Long,
    val input: WorkoutSetInput,
    val submittedAtMillis: Long,
)

data class SingleSetCommitResult(
    val pending: PendingSingleSetCommit,
    val session: WorkoutSession,
    val set: WorkoutSet,
)

/** Durable user intent for one confirmed 5x5 action. All five rows share the
 * stable [batchId], and the journal is kept until the user acknowledges the
 * committed receipt. */
data class PendingFiveByFiveCommit(
    val sessionId: Long,
    val exerciseId: Long,
    val loadGrams: Long,
    val batchId: String,
    val submittedAtMillis: Long,
)

data class FiveByFiveCommitResult(
    val pending: PendingFiveByFiveCommit,
    val session: WorkoutSession,
    val sets: List<WorkoutSet>,
)

const val WORKOUT_BATCH_ID_MAX_LENGTH = 80

fun WorkoutSetInput.validationError(trackingType: TrackingType): String? = when {
    !weightKg.isFinite() || weightKg !in 0.0..1_000.0 -> "重量必须在 0–1000 kg"
    trackingType == TrackingType.WEIGHT_REPS && weightKg <= 0.0 -> "负重动作必须填写大于 0 kg 的重量"
    reps !in 0..1_000 -> "次数必须在 0–1000"
    durationSeconds !in 0..86_400 -> "时长必须在 0–86400 秒"
    completed && reps == 0 && durationSeconds == 0 -> "完成组必须填写次数或时长"
    rpe != null && (!rpe.isFinite() || rpe !in 0.0..10.0) -> "RPE 必须在 0–10"
    rir != null && (!rir.isFinite() || rir !in 0.0..10.0) -> "RIR 必须在 0–10"
    note.length > 500 -> "单组备注不能超过 500 字"
    supersetId != null && !supersetId.trim().uppercase().matches(Regex("[A-Z0-9_-]{1,20}")) -> "超级组标识只能包含字母、数字、下划线或短横线"
    restSecondsAfter != null && restSecondsAfter !in 15..3_600 -> "休息时间必须在 15–3600 秒"
    commitId.isBlank() || commitId.length > 128 -> "训练组提交标识无效，请重新打开训练页后再试"
    else -> null
}

data class WorkoutSession(
    val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val title: String,
    val status: WorkoutStatus = WorkoutStatus.DRAFT,
    val restTimerEndAtMillis: Long? = null,
    val restDurationSeconds: Int = 120,
    /** Calendar semantics captured when the workout left DRAFT. */
    val recordedLocalDate: LocalDate? = null,
    val recordedZoneId: String? = null,
    /** Non-null only for an isolated DRAFT that is correcting this completed session. */
    val correctionOfSessionId: Long? = null,
    /** Monotonic audit marker retained on the original completed session. */
    val correctionRevision: Int = 0,
    val correctedAtMillis: Long? = null,
)

enum class WorkoutStatus {
    DRAFT,
    COMPLETED,
    CANCELLED,
}

data class WorkoutHistorySummary(
    val session: WorkoutSession,
    val exerciseNames: List<String>,
    val completedSetCount: Int,
    val totalVolumeKg: Double,
    val failedSetCount: Int = 0,
    /** Stable historical date; unlike formatting epoch millis, this cannot shift after a timezone change. */
    val recordedLocalDate: LocalDate? = session.recordedLocalDate,
    val recordedZoneId: String? = session.recordedZoneId,
)

data class WorkoutHistorySet(
    val set: WorkoutSet,
    val exercise: Exercise,
)

data class WorkoutHistoryDetail(
    val summary: WorkoutHistorySummary,
    val sets: List<WorkoutHistorySet>,
    val personalRecords: List<PersonalRecord> = emptyList(),
)

data class WorkoutCorrectionResult(
    val sessionId: Long,
    val correctionRevision: Int,
    val rebuiltPrEventCount: Int,
)

data class WorkoutHistoryDeleteResult(
    val sessionId: Long,
    val recordedLocalDate: LocalDate?,
    val rebuiltPrEventCount: Int,
)

enum class PrType(val label: String) {
    MAX_WEIGHT("最大重量 PR"),
    WEIGHT_AT_REPS("同次数重量 PR"),
    REP_AT_WEIGHT("同重量次数 PR"),
    ESTIMATED_1RM("估算 1RM PR"),
    SET_VOLUME("单组容量 PR"),
    MAX_REPS("最高次数 PR"),
    MIN_ASSISTANCE_AT_REPS("相同次数下最少辅助重量 PR"),
    REPS_AT_ASSISTANCE("相同辅助重量下最多次数 PR"),
    MAX_DURATION("最长时长 PR"),
}

enum class PrEventKind(val label: String) {
    BASELINE("建立基准"),
    BROKEN("刷新纪录"),
}

data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: Long,
    val setId: Long,
    val type: PrType,
    val bucketKey: String = "",
    val value: Double,
    val weightKg: Double,
    val reps: Int,
    val durationSeconds: Int = 0,
    val achievedAtMillis: Long,
    val eventKind: PrEventKind,
)

data class PrSummary(
    val exerciseId: Long,
    val values: List<PrDisplayValue>,
    val latestSetLabel: String?,
){
    val hasData: Boolean get() = values.isNotEmpty()
}

data class PrDisplayValue(
    val label: String,
    val value: String,
)

data class PrMetric(
    val type: PrType,
    val bucketKey: String,
    val value: Double,
    val higherIsBetter: Boolean,
)

data class PrDecision(
    val set: WorkoutSet,
    val metric: PrMetric,
    val eventKind: PrEventKind,
)

data class PrEvaluation(
    val estimated1RmKg: Double,
    val records: List<PrType>,
    val baselines: List<PrType>,
)

fun strongestEvidence(items: List<FoodDraftItem>): EvidenceTier =
    items.maxOfOrNull { it.evidenceTier } ?: EvidenceTier.D

fun clampedProgress(current: Double, target: Double): Float =
    if (target <= 0.0) 0f else min(1.0, max(0.0, current / target)).toFloat()
