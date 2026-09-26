package com.personal.fitnessledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Offline dish-name classification for the personal-device build.
 *
 * The AIY model never supplies nutrition or mass. Its only output is a ranked
 * list of alternative dish names. A conservative allow-list can attach one
 * reviewed USDA FNDDS reference to a hypothesis; every estimate remains a
 * draft and still requires explicit user review before commit.
 */
class OnDevicePhotoAnalyzer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val assets by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OnDeviceFoodAssets.load(appContext)
    }
    private val interpreter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createInterpreter(appContext)
    }

    @Synchronized
    fun analyze(uri: Uri, jpeg: ByteArray): MealDraft {
        val scores = runInference(jpeg)
        return OnDeviceFoodPostprocessor(assets).createDraft(uri, scores)
    }

    private fun runInference(jpeg: ByteArray): DoubleArray {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("本机模型无法解码照片")
        val side = min(decoded.width, decoded.height)
        check(side > 0) { "照片尺寸无效" }
        val cropped = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, INPUT_WIDTH, INPUT_HEIGHT, true)
        return try {
            val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            val input = ByteBuffer.allocateDirect(INPUT_BYTES).order(ByteOrder.nativeOrder())
            pixels.forEach { pixel ->
                input.put(((pixel ushr 16) and 0xff).toByte())
                input.put(((pixel ushr 8) and 0xff).toByte())
                input.put((pixel and 0xff).toByte())
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(OUTPUT_CLASSES).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            DoubleArray(OUTPUT_CLASSES) {
                (output.get().toInt() and 0xff) * OUTPUT_SCALE
            }
        } finally {
            if (scaled !== cropped && scaled !== decoded) scaled.recycle()
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
        }
    }

    private fun createInterpreter(context: Context): Interpreter {
        verifyAsset(context, MODEL_ASSET, MODEL_BYTES, MODEL_SHA256)
        val model = context.assets.openFd(MODEL_ASSET).use { descriptor ->
            check(descriptor.declaredLength == MODEL_BYTES) { "本机模型文件长度不匹配" }
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
        return Interpreter(model, Interpreter.Options().setNumThreads(4)).also { runtime ->
            runtime.allocateTensors()
            val input = runtime.getInputTensor(0)
            val output = runtime.getOutputTensor(0)
            check(input.dataType() == DataType.UINT8 && input.shape().contentEquals(INPUT_SHAPE)) {
                "本机模型输入契约不匹配"
            }
            check(output.dataType() == DataType.UINT8 && output.shape().contentEquals(OUTPUT_SHAPE)) {
                "本机模型输出契约不匹配"
            }
            val inputQuantization = input.quantizationParams()
            check(
                abs(inputQuantization.scale - INPUT_SCALE) < QUANTIZATION_TOLERANCE &&
                    inputQuantization.zeroPoint == INPUT_ZERO_POINT,
            ) { "本机模型输入量化契约不匹配" }
            val outputQuantization = output.quantizationParams()
            check(
                abs(outputQuantization.scale - OUTPUT_SCALE.toFloat()) < QUANTIZATION_TOLERANCE &&
                    outputQuantization.zeroPoint == OUTPUT_ZERO_POINT,
            ) { "本机模型输出量化契约不匹配" }
        }
    }

    private companion object {
        const val MODEL_ASSET = "models/aiy_food_v1.tflite"
        const val MODEL_BYTES = 21_151_551L
        const val MODEL_SHA256 = "03DD6D9129501F97BE00775D9E17B5D9AD13BE730149352ADB8F4CA953B7A650"
        const val INPUT_WIDTH = 192
        const val INPUT_HEIGHT = 192
        const val INPUT_BYTES = INPUT_WIDTH * INPUT_HEIGHT * 3
        const val INPUT_SCALE = 0.0078125f
        const val INPUT_ZERO_POINT = 128
        const val OUTPUT_CLASSES = 2024
        const val OUTPUT_SCALE = 1.0 / 256.0
        const val OUTPUT_ZERO_POINT = 0
        const val QUANTIZATION_TOLERANCE = 1e-8f
        val INPUT_SHAPE = intArrayOf(1, INPUT_HEIGHT, INPUT_WIDTH, 3)
        val OUTPUT_SHAPE = intArrayOf(1, OUTPUT_CLASSES)
    }
}

internal data class OnDeviceFoodReference(
    val canonicalKey: String,
    val displayName: String,
    val fdcId: Long,
    val per100g: Nutrition,
    val defaultGrams: Double,
    val gramsMin: Double,
    val gramsMax: Double,
    val minimumScore: Double,
    val minimumMargin: Double,
    val riskFlags: Set<RiskFlag>,
)

internal data class OnDeviceFoodAssets(
    val labels: List<String>,
    val referencesByLabelId: Map<Int, OnDeviceFoodReference>,
) {
    companion object {
        private const val LABELS_ASSET = "models/aiy_food_v1_labels.csv"
        private const val LABELS_BYTES = 34_210L
        private const val LABELS_SHA256 = "8335571E32D893C986ADDAE14D96B6AB0157EA1F8BAD983A22C224E9EEAC6F90"
        private const val MAP_ASSET = "models/aiy_food_v1_nutrition_map.json"
        private const val MAP_BYTES = 4_123L
        private const val MAP_SHA256 = "3262EEB5A07E9F92DC474EB6231F97B5F6DBEBA93F7DECD46AF326DEEA7B151B"

        fun load(context: Context): OnDeviceFoodAssets {
            verifyAsset(context, LABELS_ASSET, LABELS_BYTES, LABELS_SHA256)
            verifyAsset(context, MAP_ASSET, MAP_BYTES, MAP_SHA256)
            val labels = context.assets.open(LABELS_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.drop(1).map { line ->
                    val separator = line.indexOf(',')
                    check(separator > 0) { "本机模型标签格式无效" }
                    val id = line.substring(0, separator).toInt()
                    val raw = line.substring(separator + 1)
                        .removeSurrounding("\"")
                        .replace("\"\"", "\"")
                    id to raw
                }.toList()
            }
            check(labels.size == 2024 && labels.indices.all { labels[it].first == it }) {
                "本机模型标签数量或编号无效"
            }
            val labelNames = labels.map(Pair<Int, String>::second)
            check(labelNames.first() == "__background__") { "本机模型背景标签无效" }

            val root = JSONObject(context.assets.open(MAP_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(root.getInt("schemaVersion") == 1) { "本机营养映射版本不受支持" }
            val references = buildMap {
                val entries = root.getJSONArray("entries")
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val nutrition = entry.getJSONObject("per100g")
                    val reference = OnDeviceFoodReference(
                        canonicalKey = entry.getString("canonicalKey"),
                        displayName = entry.getString("displayName"),
                        fdcId = entry.getLong("fdcId"),
                        per100g = Nutrition(
                            kcal = nutrition.getDouble("kcal"),
                            carbsG = nutrition.getDouble("carbsG"),
                            proteinG = nutrition.getDouble("proteinG"),
                            fatG = nutrition.getDouble("fatG"),
                        ),
                        defaultGrams = entry.getDouble("defaultGrams"),
                        gramsMin = entry.getDouble("gramsMin"),
                        gramsMax = entry.getDouble("gramsMax"),
                        minimumScore = entry.getDouble("minimumScore"),
                        minimumMargin = entry.getDouble("minimumMargin"),
                        riskFlags = buildSet {
                            val flags = entry.getJSONArray("riskFlags")
                            for (flagIndex in 0 until flags.length()) add(RiskFlag.valueOf(flags.getString(flagIndex)))
                        },
                    )
                    validate(reference)
                    val labelIds = entry.getJSONArray("labelIds")
                    for (labelIndex in 0 until labelIds.length()) {
                        val labelId = labelIds.getInt(labelIndex)
                        check(labelId in 1 until labelNames.size) { "本机营养映射标签编号越界" }
                        check(put(labelId, reference) == null) { "本机营养映射标签重复" }
                    }
                }
            }
            return OnDeviceFoodAssets(labelNames, references)
        }

        private fun validate(reference: OnDeviceFoodReference) {
            check(reference.canonicalKey.isNotBlank() && reference.displayName.isNotBlank())
            check(reference.fdcId > 0L)
            check(reference.defaultGrams in reference.gramsMin..reference.gramsMax)
            check(reference.gramsMin > 0.0 && reference.gramsMax <= 5_000.0)
            check(reference.minimumScore in 0.0..1.0 && reference.minimumMargin in 0.0..1.0)
            check(reference.per100g.kcal in 0.0..1_000.0)
            check(reference.per100g.carbsG in 0.0..100.0)
            check(reference.per100g.proteinG in 0.0..100.0)
            check(reference.per100g.fatG in 0.0..100.0)
            check(reference.per100g.carbsG + reference.per100g.proteinG + reference.per100g.fatG <= 100.5)
        }
    }
}

internal class OnDeviceFoodPostprocessor(
    private val assets: OnDeviceFoodAssets,
) {
    fun createDraft(uri: Uri, scores: DoubleArray): MealDraft = createDraft(uri.toString(), scores)

    /** String entry point keeps the score-only policy independently testable on the local JVM. */
    internal fun createDraft(photoUri: String, scores: DoubleArray): MealDraft {
        require(scores.size == assets.labels.size) { "本机模型输出类别数无效" }
        require(scores.all { it.isFinite() && it in 0.0..1.0 }) { "本机模型输出包含无效分数" }
        val rawTopId = scores.indices.maxByOrNull { scores[it] } ?: error("本机模型没有输出")
        val groups = linkedMapOf<String, RankedHypothesis>()
        scores.indices
            .asSequence()
            .filter { it > 0 && scores[it] > 0.0 }
            .sortedByDescending { scores[it] }
            .forEach { labelId ->
                val rawLabel = assets.labels[labelId]
                if (rawLabel.startsWith("/g/")) return@forEach
                val reference = assets.referencesByLabelId[labelId]
                val canonicalKey = reference?.canonicalKey ?: rawLabel.canonicalLabelKey()
                val candidate = RankedHypothesis(
                    labelId = labelId,
                    rawLabel = rawLabel,
                    displayName = reference?.displayName ?: localizedFoodCandidateLabel(labelId, rawLabel),
                    score = scores[labelId],
                    reference = reference,
                )
                val previous = groups[canonicalKey]
                if (previous == null || candidate.score > previous.score) groups[canonicalKey] = candidate
            }
        val ranked = groups.values.sortedByDescending(RankedHypothesis::score).take(MAX_HYPOTHESES)
        val top = ranked.firstOrNull()
        val rawTopIsUsable = rawTopId > 0 &&
            !assets.labels[rawTopId].startsWith("/g/") &&
            top?.labelId == rawTopId
        val topSupportsNutrition = rawTopIsUsable && top.reference?.let { reference ->
            top.score >= reference.minimumScore
        } == true
        val hypotheses = ranked.map { candidate ->
            // A candidate may remain visible for human interpretation at any
            // score, but low-confidence output must not carry pseudo-nutrition.
            // An opaque or uncatalogued raw top label invalidates every prefill,
            // including otherwise mapped lower-ranked alternatives.
            val prefillReference = candidate.reference?.takeIf { reference ->
                topSupportsNutrition && candidate.score >= reference.minimumScore
            }
            FoodHypothesis(
                labelId = candidate.labelId,
                rawLabel = candidate.rawLabel,
                displayName = candidate.displayName,
                modelScore = candidate.score,
                canonicalKey = candidate.reference?.canonicalKey ?: candidate.rawLabel.canonicalLabelKey(),
                suggestedItem = prefillReference?.toDraftItem(
                    alternatives = ranked.filterNot { it === candidate }.map(RankedHypothesis::displayName),
                ),
            )
        }
        val margin = if (ranked.size >= 2) requireNotNull(top).score - ranked[1].score else top?.score ?: 0.0
        val autoReference = top?.reference?.takeIf { reference ->
            rawTopIsUsable && top.score >= reference.minimumScore && margin >= reference.minimumMargin
        }
        val autoItem = autoReference?.toDraftItem(
            alternatives = ranked.drop(1).map(RankedHypothesis::displayName),
        )
        val scoreLabel = top?.score?.let { "%.0f%%".format(Locale.ROOT, it * 100.0) } ?: "无"
        return MealDraft(
            photoUri = photoUri,
            items = listOfNotNull(autoItem),
            evidenceTier = if (autoItem == null) EvidenceTier.D else EvidenceTier.C,
            evidenceReason = if (autoItem == null) {
                "无法从这张单图可靠确认食物或营养；候选仅供参考，请手工确认名称并补全营养"
            } else {
                "本机模型只判断食物像什么；营养来自匹配的 USDA 通用条目，克重、油、酱汁和配方仍需你核对"
            },
            unresolvedFlags = autoItem?.riskFlags.orEmpty(),
            providerLabel = if (autoItem == null) {
                "本机 Google AIY Food V1 · 最高模型分数 $scoreLabel · 无法可靠预填营养"
            } else {
                "本机 Google AIY Food V1 · 最高模型分数 $scoreLabel · USDA FNDDS 通用值"
            },
            analysisMode = AnalysisMode.ON_DEVICE_AI,
            hypotheses = hypotheses,
        )
    }

    private data class RankedHypothesis(
        val labelId: Int,
        val rawLabel: String,
        val displayName: String,
        val score: Double,
        val reference: OnDeviceFoodReference?,
    )

    private fun OnDeviceFoodReference.toDraftItem(alternatives: List<String>): FoodDraftItem = FoodDraftItem(
        name = displayName,
        grams = defaultGrams,
        gramsMin = gramsMin,
        gramsMax = gramsMax,
        per100g = per100g,
        sourceName = "USDA FNDDS 2021–2023 · FDC $fdcId",
        portionBasis = PortionBasis.AI_SINGLE_PHOTO,
        evidenceTier = EvidenceTier.C,
        riskFlags = riskFlags,
        alternatives = alternatives.take(4),
        calorieSource = CalorieSource.LABEL_OR_DATABASE,
    )

    private companion object {
        const val MAX_HYPOTHESES = 5
    }
}

private fun String.canonicalLabelKey(): String = lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')
    .ifBlank { "opaque" }

internal fun localizedFoodCandidateLabel(labelId: Int, rawLabel: String): String =
    LOCALIZED_LABELS[rawLabel] ?: "未收录候选 #$labelId（需手工命名）"

private val LOCALIZED_LABELS = mapOf(
    "Jianbing" to "煎饼",
    "Peking duck" to "北京烤鸭",
    "Dumpling" to "饺子",
    "Hot dry noodles" to "热干面",
    "Congee" to "粥",
    "Mapo doufu" to "麻婆豆腐",
    "Chinese noodles" to "中式面条",
    "Fried rice" to "炒饭",
    "Beef noodle soup" to "牛肉面",
    "White rice" to "白米饭",
    "Shanghai fried noodles" to "上海炒面",
    "Hainanese chicken rice" to "海南鸡饭",
    "Crossing-the-bridge noodles" to "过桥米线",
    "Hot pot" to "火锅",
    "Baozi" to "包子",
    "Dandan noodles" to "担担面",
    "Biangbiang noodles" to "油泼宽面",
    "Xiaolongbao" to "小笼包",
    "Ramen" to "拉面",
    "Yangzhou fried rice" to "扬州炒饭",
    "Claypot chicken rice" to "煲仔鸡饭",
    "Wonton noodles" to "云吞面",
    "Tea egg" to "茶叶蛋",
    "Soy egg" to "卤蛋",
    "White boiled shrimp" to "白灼虾",
    "Kung Pao chicken" to "宫保鸡丁",
    "Sweet and sour pork" to "糖醋猪肉",
    "Egg drop soup" to "蛋花汤",
    // Explicitly localize labels observed during alpha11 emulator audits. They
    // remain dish-name hypotheses; this table never attaches nutrition.
    "Popover" to "美式空心松饼",
    "Jelly bean" to "果冻豆糖",
    "Waffle" to "华夫饼",
    "Bento" to "便当",
)

private fun verifyAsset(context: Context, path: String, expectedBytes: Long, expectedSha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    context.assets.open(path).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= expectedBytes) { "本机模型资产长度超出清单" }
            digest.update(buffer, 0, count)
        }
    }
    check(total == expectedBytes) { "本机模型资产长度与清单不符" }
    val actual = digest.digest().joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
    check(actual == expectedSha256) { "本机模型资产校验失败" }
}
