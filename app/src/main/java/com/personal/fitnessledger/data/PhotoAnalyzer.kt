package com.personal.fitnessledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

data class AnalysisServiceConfig(
    val endpointUrl: String = "",
    val accessToken: String = "",
    val transport: AnalysisTransport = AnalysisTransport.MEAL_PROXY,
    val modelName: String = "",
    val additionalPrompt: String = "",
) {
    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank() && accessToken.isNotBlank() &&
            analysisEndpointValidationError(endpointUrl) == null &&
            (transport != AnalysisTransport.VISION_API || visionModelValidationError(modelName) == null)

    // Legacy proxy secrets keep their existing binding. A protocol change must
    // never reinterpret an existing proxy token as a provider API key (or vice versa).
    val credentialBinding: String
        get() = if (transport == AnalysisTransport.MEAL_PROXY) endpointUrl else "vision-api:$endpointUrl"

    override fun toString(): String = "AnalysisServiceConfig(transport=$transport, configured=$isConfigured)"
}

enum class AnalysisTransport { MEAL_PROXY, VISION_API }

internal const val INTERRUPTED_PHOTO_ANALYSIS_MESSAGE =
    "上次识别中断，照片已保留且未入账；不会自动重发。手动重试可能再次计费，也可手工补录"

fun analysisEndpointValidationError(endpoint: String): String? {
    val normalized = endpoint.trim()
    if (normalized.isBlank()) return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return "识别服务地址格式无效"
    return when {
        !uri.scheme.equals("https", ignoreCase = true) -> "识别服务必须使用 HTTPS"
        uri.host.isNullOrBlank() -> "识别服务地址缺少有效域名"
        uri.userInfo != null -> "识别服务地址不能包含用户名或密码"
        uri.rawQuery != null -> "识别服务地址不能包含查询参数"
        uri.fragment != null -> "识别服务地址不能包含 # 片段"
        else -> null
    }
}

interface PhotoAnalyzer {
    fun analyze(context: Context, uri: Uri): MealDraft
}

class ConfigurablePhotoAnalyzer(
    private val config: AnalysisServiceConfig,
    private val onDeviceAnalyzer: OnDevicePhotoAnalyzer? = null,
    private val onPhotoPrepared: (Uri) -> Unit = {},
) : PhotoAnalyzer {
    override fun analyze(context: Context, uri: Uri): MealDraft {
        val pendingCameraCapture = PhotoStorage.pendingCameraCapture(context)
        if (pendingCameraCapture?.uri?.toString() == uri.toString()) {
            check(PhotoStorage.markPendingCameraAnalyzing(context, uri)) {
                "相机照片尚未完成交付"
            }
        }
        // A retry of an already persisted, metadata-stripped photo must reuse the
        // exact bytes and URI. Re-encoding would change the idempotency key and
        // could turn one user action into a second paid provider request.
        val persistedJpeg = PhotoStorage.readPersistedJpeg(context, uri)
        val jpeg = persistedJpeg ?: ImagePreprocessor.toStrippedJpeg(context, uri)
        val storedUri = if (persistedJpeg != null) uri else PhotoStorage.persist(context, jpeg)
        // Persist recovery BEFORE a request leaves the device. Persistence failures
        // escape before transport, and process death must never trigger a paid retry.
        onPhotoPrepared(storedUri)
        return runCatching {
            when {
                config.endpointUrl.isBlank() && onDeviceAnalyzer != null -> onDeviceAnalyzer.analyze(storedUri, jpeg)
                config.endpointUrl.isBlank() -> error("请先连接外置视觉大模型")
                config.isConfigured && config.transport == AnalysisTransport.VISION_API ->
                    VisionApiPhotoAnalyzer(config).analyze(storedUri, jpeg)
                config.isConfigured -> RemotePhotoAnalyzer(config).analyze(storedUri, jpeg)
                else -> error("识别服务配置不完整")
            }
        }.getOrElse { error ->
            MealDraft(
                photoUri = storedUri.toString(),
                state = DraftState.ANALYSIS_FAILED,
                items = emptyList(),
                evidenceTier = EvidenceTier.D,
                evidenceReason = analysisFailureReason(error),
                unresolvedFlags = emptySet(),
                providerLabel = "识别失败 · 未写入正式账本",
                analysisMode = AnalysisMode.MANUAL,
            )
        }
    }
}

object DemoPhotoAnalyzer {
    fun analyze(uri: Uri): MealDraft = MealDraft(
        photoUri = uri.toString(),
        items = listOf(
            FoodDraftItem(
                name = "米饭（熟）",
                grams = 180.0,
                gramsMin = 140.0,
                gramsMax = 230.0,
                per100g = Nutrition(kcal = 116.0, carbsG = 25.9, proteinG = 2.6, fatG = 0.3),
                sourceName = "本地审核食物库 v1",
                portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                evidenceTier = EvidenceTier.C,
                alternatives = listOf("杂粮饭", "糙米饭"),
            ),
            FoodDraftItem(
                name = "鸡胸肉（熟）",
                grams = 160.0,
                gramsMin = 120.0,
                gramsMax = 200.0,
                per100g = Nutrition(kcal = 165.0, carbsG = 0.0, proteinG = 31.0, fatG = 3.6),
                sourceName = "本地审核食物库 v1",
                portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                evidenceTier = EvidenceTier.C,
                alternatives = listOf("鸡腿肉", "鸡里脊"),
            ),
            FoodDraftItem(
                name = "西兰花（熟）",
                grams = 120.0,
                gramsMin = 90.0,
                gramsMax = 160.0,
                per100g = Nutrition(kcal = 35.0, carbsG = 7.2, proteinG = 2.4, fatG = 0.4),
                sourceName = "本地审核食物库 v1",
                portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                evidenceTier = EvidenceTier.C,
                alternatives = listOf("菜花", "芥蓝"),
            ),
            FoodDraftItem(
                name = "烹调油",
                grams = 8.0,
                gramsMin = 4.0,
                gramsMax = 15.0,
                per100g = Nutrition(kcal = 900.0, carbsG = 0.0, proteinG = 0.0, fatG = 100.0),
                sourceName = "通用食用油",
                portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                evidenceTier = EvidenceTier.C,
                riskFlags = setOf(RiskFlag.UNKNOWN_OIL),
                alternatives = listOf("无额外用油", "酱汁中的油"),
            ),
        ),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "单张照片无法确定尺度；食材和克重需要你逐项核对",
        unresolvedFlags = setOf(RiskFlag.UNKNOWN_OIL),
        providerLabel = "交互演示草稿 · 未接入识别服务",
        analysisMode = AnalysisMode.INTERACTIVE_DEMO,
    )
}

internal class RemoteAnalysisHttpClient(
    private val config: AnalysisServiceConfig,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection
            ?: error("识别代理地址不是 HTTP 连接")
    },
) {
    fun post(operationKey: String, requestBody: ByteArray): String {
        val connection = connectionFactory(URL(config.endpointUrl)).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            // The proxy has a shorter upstream deadline. Keeping the client wait
            // longer lets the proxy return/replay a definitive idempotent result
            // instead of abandoning a request that may still incur provider cost.
            readTimeout = if (config.transport == AnalysisTransport.VISION_API) 75_000 else 35_000
            // Redirects are never followed for image analysis. Following one can
            // disclose the photo, idempotency key, and bearer token to a different
            // origin chosen by a misconfigured or compromised proxy.
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Idempotency-Key", operationKey)
            setRequestProperty("Idempotency-Key", operationKey)
            if (config.accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.accessToken}")
        }
        try {
            connection.outputStream.use { it.write(requestBody) }
            val responseCode = connection.responseCode
            // Reject before opening even the redirect response body. This keeps
            // every 3xx on one explicit, non-forwarding failure path regardless
            // of Location or response size.
            if (responseCode in 300..399) {
                throw RemoteAnalysisException(responseCode, retryAfterSeconds = null)
            }
            val responseText = readLimitedText(
                if (responseCode in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_BYTES,
            )
            if (responseCode !in 200..299) {
                val retryAfterSeconds = connection.getHeaderField("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.coerceIn(1L, 86_400L)
                throw RemoteAnalysisException(responseCode, retryAfterSeconds,
                    classifyAnalysisProviderFailure(config, responseCode, responseText))
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedText(stream: InputStream?, maximumBytes: Int): String {
        if (stream == null) return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximumBytes) { "识别服务响应过大" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

internal class RemotePhotoAnalyzer(
    private val config: AnalysisServiceConfig,
    private val post: (String, ByteArray) -> String = RemoteAnalysisHttpClient(config)::post,
) {
    fun analyze(uri: Uri, jpeg: ByteArray): MealDraft {
        additionalMealPromptValidationError(config.additionalPrompt)?.let { throw IllegalArgumentException(it) }
        val additionalPrompt = normalizeAdditionalMealPrompt(config.additionalPrompt)
        val request = JSONObject().apply {
            put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            put("locale", "zh-CN")
            put("requestedFields", "food_candidates,portion_range,preparation,risk_flags")
            if (additionalPrompt.isNotEmpty()) put("additionalPrompt", additionalPrompt)
        }
        // The durable random photo URI represents one user analysis operation:
        // retries reuse it, while a later explicit analysis of identical image
        // bytes receives a new URI/key and is not pinned to an old result.
        val promptDigest = additionalMealPromptDigest(additionalPrompt)
        val responseText = post(
            uri.analysisOperationKey(if (promptDigest.isEmpty()) "" else "additional-meal-prompt:$promptDigest"),
            request.toString().toByteArray(Charsets.UTF_8),
        )
        return parseRemoteMealDraft(uri, JSONObject(responseText))
    }
}

internal fun parseRemoteMealDraft(uri: Uri, response: JSONObject, directModel: String? = null): MealDraft {
        val itemsJson = response.getJSONArray("items")
        if (itemsJson.length() == 0) throw NoVisibleFoodException()
        require(itemsJson.length() in 1..MAX_ITEMS) { "识别结果食物数量无效" }
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                val per100g = item.getJSONObject("per100g")
                val riskFlags = buildSet {
                    val flags = item.optJSONArray("riskFlags")
                    if (flags != null) {
                        for (flagIndex in 0 until flags.length()) {
                            runCatching { add(RiskFlag.valueOf(flags.getString(flagIndex))) }
                        }
                    }
                }
                val alternatives = buildList {
                    val values = item.optJSONArray("alternatives")
                    if (values != null) for (alternativeIndex in 0 until values.length()) add(values.getString(alternativeIndex))
                }
                val name = item.getString("name").trim().take(MAX_NAME_LENGTH)
                require(name.isNotBlank()) { "识别结果包含空食物名称" }
                val grams = item.getDouble("grams").requireRange("克重", 1.0, 5_000.0)
                val gramsMin = item.optDouble("gramsMin", grams * 0.75).requireRange("最小克重", 1.0, 5_000.0)
                val gramsMax = item.optDouble("gramsMax", grams * 1.25).requireRange("最大克重", 1.0, 5_000.0)
                require(gramsMin <= grams && grams <= gramsMax) { "识别结果克重区间无效" }
                add(
                    FoodDraftItem(
                        name = name,
                        grams = grams,
                        gramsMin = gramsMin,
                        gramsMax = gramsMax,
                        per100g = Nutrition(
                            kcal = per100g.getDouble("kcal").requireRange("热量", 0.0, 1_000.0),
                            carbsG = per100g.getDouble("carbsG").requireRange("碳水", 0.0, 100.0),
                            proteinG = per100g.getDouble("proteinG").requireRange("蛋白质", 0.0, 100.0),
                            fatG = per100g.getDouble("fatG").requireRange("脂肪", 0.0, 100.0),
                        ).also { nutrition ->
                            require(nutrition.carbsG + nutrition.proteinG + nutrition.fatG <= 100.5) { "营养素合计超出每 100 g" }
                            require(nutrition.kcal > 0 || nutrition.carbsG + nutrition.proteinG + nutrition.fatG <= 0.1) { "营养值与热量矛盾" }
                        },
                        sourceName = if (directModel != null) "视觉模型估算（按食用状态）" else
                            item.optString("sourceName", "识别服务估算").trim().take(MAX_SOURCE_LENGTH).ifBlank { "识别服务估算" },
                        portionBasis = PortionBasis.AI_SINGLE_PHOTO,
                        evidenceTier = if (item.optString("evidenceTier") == "D") EvidenceTier.D else EvidenceTier.C,
                        riskFlags = if (directModel != null) riskFlags + RiskFlag.UNKNOWN_PORTION else riskFlags,
                        alternatives = alternatives.take(MAX_ALTERNATIVES).map { it.take(MAX_NAME_LENGTH) },
                    ),
                )
            }
        }
        val unresolved = items.flatMap { it.riskFlags }.toSet()
        val declaredTier = runCatching { EvidenceTier.valueOf(response.optString("evidenceTier", "C")) }.getOrDefault(EvidenceTier.C)
        val tier = maxOf(EvidenceTier.C, declaredTier, strongestEvidence(items))
        return MealDraft(
            photoUri = uri.toString(),
            items = items,
            evidenceTier = tier,
            evidenceReason = response.optString("evidenceReason", "请核对食物身份、克重和烹调油").take(240),
            unresolvedFlags = unresolved,
            providerLabel = if (directModel != null) "整餐 AI 估算 · ${directModel.take(80)}" else
                response.optString("providerLabel", "远程识别草稿").take(MAX_PROVIDER_LENGTH),
            analysisMode = AnalysisMode.REMOTE_AI,
        )
    }

    private fun Double.requireRange(label: String, minimum: Double, maximum: Double): Double {
        require(isFinite() && this in minimum..maximum) { "$label 超出允许范围" }
        return this
    }

private const val MAX_ITEMS = 30
private const val MAX_ALTERNATIVES = 8
private const val MAX_NAME_LENGTH = 80
private const val MAX_SOURCE_LENGTH = 120
private const val MAX_PROVIDER_LENGTH = 120

internal class RemoteAnalysisException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    val providerFailure: AnalysisProviderFailure? = null,
) : IllegalStateException("meal analysis proxy returned HTTP $statusCode")

internal enum class AnalysisProviderFailure { GLM_THINKING_REQUIRED, GLM_INVALID_PARAMETERS }

/** Translate only verified provider codes into local messages. Never retain or
 * show arbitrary upstream text, which could echo credentials, images or notes. */
internal fun classifyAnalysisProviderFailure(
    config: AnalysisServiceConfig, statusCode: Int, responseText: String,
): AnalysisProviderFailure? {
    if (statusCode != 400 || config.transport != AnalysisTransport.VISION_API ||
        runCatching { java.net.URI(config.endpointUrl).host?.lowercase() }.getOrNull() != "open.bigmodel.cn") return null
    return runCatching {
        val error = JSONObject(responseText).optJSONObject("error") ?: return@runCatching null
        val code = error.opt("code")
        if (code != "1210" && code != 1210) return@runCatching null
        val message = error.opt("message") as? String ?: ""
        if (message.contains("不支持关闭思考")) AnalysisProviderFailure.GLM_THINKING_REQUIRED
        else AnalysisProviderFailure.GLM_INVALID_PARAMETERS
    }.getOrNull()
}

internal fun analysisFailureReason(error: Throwable): String = when (error) {
    is NoVisibleFoodException -> "没有识别到可记录的食物；请拍下整餐并保证光线清晰，未计入任何热量"
    is InvalidVisionResponseException -> "大模型没有返回完整、可用的营养明细；未入账，可重试或手工记录"
    is java.net.SocketTimeoutException -> "大模型响应超时；照片已保留。不会自动重发，手动重试可能再次计费"
    is RemoteAnalysisException -> when {
        error.statusCode in 300..399 ->
            "识别代理返回重定向（HTTP ${error.statusCode}）；为避免向其他地址转发照片或访问令牌，已拒绝请求，请填写最终 HTTPS 地址"
        error.statusCode == 401 || error.statusCode == 403 ->
            "识别服务拒绝访问；请检查 API Key 或代理令牌、所属地域和模型权限，照片已保留"
        error.statusCode == 400 && error.providerFailure == AnalysisProviderFailure.GLM_THINKING_REQUIRED ->
            "该 GLM 模型不能关闭思考（智谱 1210）；请更新 App 的模型参数适配。照片已保留，未入账"
        error.statusCode == 400 && error.providerFailure == AnalysisProviderFailure.GLM_INVALID_PARAMETERS ->
            "模型不接受当前请求参数（智谱 1210）；请检查模型 ID 或更新 App 的模型参数适配。照片已保留，未入账"
        error.statusCode == 400 ->
            "模型服务拒绝请求参数（HTTP 400）；请检查模型 ID、图片和思考参数，照片已保留"
        error.statusCode == 404 ->
            "服务地址或模型配置不匹配；请确认使用支持图片的 Chat Completions 模型，照片已保留"
        error.statusCode == 409 -> "识别请求标识与照片不一致；照片已保留，请重新拍照后再试"
        error.statusCode == 413 -> "照片超过识别代理允许的大小；请重新拍摄更简洁的画面"
        error.statusCode == 422 -> "识别代理无法读取这张照片；请重新拍摄或手工补录"
        error.statusCode == 429 -> error.retryAfterSeconds?.let { seconds ->
            "识别请求过于频繁；请在约 ${seconds} 秒后重试，照片已保留"
        } ?: "识别请求过于频繁；请稍后重试，照片已保留"
        error.statusCode == 503 || error.statusCode == 504 -> error.retryAfterSeconds?.let { seconds ->
            "识别服务暂不可用；请在约 ${seconds} 秒后重试，照片已保留"
        } ?: "识别服务暂不可用；照片已保留，请稍后重试或手工补录"
        else -> "识别服务返回错误（HTTP ${error.statusCode}）；照片已保留，请稍后重试"
    }
    else -> "识别服务暂时不可用；照片已保留，请重试或手工补录"
}

internal fun Uri.analysisOperationKey(namespace: String = ""): String = MessageDigest.getInstance("SHA-256")
    .digest((toString() + if (namespace.isBlank()) "" else "\n$namespace").toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private object ImagePreprocessor {
    fun toStrippedJpeg(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片" }

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 1_800) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法解码照片")
        val bitmap = decoded.applyExifOrientation(orientation)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            if (bitmap !== decoded) decoded.recycle()
            output.toByteArray()
        }
    }

    private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}

object PhotoStorage {
    private const val PHOTO_DIRECTORY = "meal_photos"
    private const val CAMERA_STATE_PREFERENCES = "fitness_camera_state"
    private const val KEY_PENDING_CAMERA_URI = "pending_camera_uri"
    private const val KEY_PENDING_CAMERA_CREATED_AT = "pending_camera_created_at"
    private const val KEY_PENDING_CAMERA_PHASE = "pending_camera_phase"
    private const val KEY_PENDING_CAMERA_TARGET_EPOCH_DAY = "pending_camera_target_epoch_day"
    private const val STALE_CAMERA_FILE_AGE_MILLIS = 24L * 60L * 60L * 1_000L

    enum class PendingCameraPhase {
        LAUNCHED,
        RESULT_RECEIVED,
        ANALYZING,
    }

    data class PendingCameraCapture(
        val uri: Uri,
        val createdAtMillis: Long,
        val phase: PendingCameraPhase,
        val targetDateEpochDay: Long?,
    )

    fun createPendingCameraUri(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        targetDateEpochDay: Long? = null,
    ): Uri {
        check(pendingCameraCapture(context) == null) { "已有相机任务尚未处理" }
        val directory = File(context.cacheDir, PHOTO_DIRECTORY).apply { mkdirs() }
        val file = File.createTempFile("meal_", ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val editor = cameraPreferences(context).edit()
            .putString(KEY_PENDING_CAMERA_URI, uri.toString())
            .putLong(KEY_PENDING_CAMERA_CREATED_AT, nowMillis)
            .putString(KEY_PENDING_CAMERA_PHASE, PendingCameraPhase.LAUNCHED.name)
        if (targetDateEpochDay == null) {
            editor.remove(KEY_PENDING_CAMERA_TARGET_EPOCH_DAY)
        } else {
            editor.putLong(KEY_PENDING_CAMERA_TARGET_EPOCH_DAY, targetDateEpochDay)
        }
        val stored = editor.commit()
        if (!stored) {
            file.delete()
            error("无法持久化相机拍照状态")
        }
        return uri
    }

    fun pendingCameraCapture(context: Context): PendingCameraCapture? {
        val preferences = cameraPreferences(context)
        val uriText = preferences.getString(KEY_PENDING_CAMERA_URI, "").orEmpty()
        val createdAt = preferences.getLong(KEY_PENDING_CAMERA_CREATED_AT, -1L)
        if (uriText.isBlank() || createdAt < 0L) return null
        val phase = runCatching {
            PendingCameraPhase.valueOf(
                preferences.getString(KEY_PENDING_CAMERA_PHASE, PendingCameraPhase.LAUNCHED.name).orEmpty(),
            )
        }.getOrDefault(PendingCameraPhase.LAUNCHED)
        val targetDateEpochDay = if (preferences.contains(KEY_PENDING_CAMERA_TARGET_EPOCH_DAY)) {
            preferences.getLong(KEY_PENDING_CAMERA_TARGET_EPOCH_DAY, 0L)
        } else {
            null
        }
        return runCatching { PendingCameraCapture(Uri.parse(uriText), createdAt, phase, targetDateEpochDay) }.getOrNull()
    }

    fun markPendingCameraResultReceived(context: Context, uri: Uri): Boolean =
        updateMatchingPendingPhase(
            context = context,
            uri = uri,
            phase = PendingCameraPhase.RESULT_RECEIVED,
            allowedCurrentPhases = setOf(PendingCameraPhase.LAUNCHED, PendingCameraPhase.RESULT_RECEIVED),
        )

    fun markPendingCameraAnalyzing(context: Context, uri: Uri): Boolean =
        updateMatchingPendingPhase(
            context = context,
            uri = uri,
            phase = PendingCameraPhase.ANALYZING,
            allowedCurrentPhases = setOf(PendingCameraPhase.RESULT_RECEIVED, PendingCameraPhase.ANALYZING),
        )

    fun completePendingCameraCapture(context: Context, uri: Uri): Boolean =
        clearMatchingPendingCapture(
            context = context,
            uri = uri,
            deleteFile = true,
            allowedCurrentPhases = setOf(PendingCameraPhase.RESULT_RECEIVED, PendingCameraPhase.ANALYZING),
        )

    /** A normal system-camera cancellation may only retire work that was never
     * delivered. RESULT_RECEIVED and ANALYZING files remain recoverable. */
    fun cancelPendingCameraCapture(context: Context, uri: Uri? = pendingCameraCapture(context)?.uri): Boolean {
        if (uri == null) return false
        return clearMatchingPendingCapture(
            context = context,
            uri = uri,
            deleteFile = true,
            allowedCurrentPhases = setOf(PendingCameraPhase.LAUNCHED),
        )
    }

    /** A durable draft already owns its stripped copy, so a delivered camera cache
     * can be acknowledged during repository recovery without touching LAUNCHED work. */
    fun completeDeliveredPendingCameraCapture(context: Context): Boolean {
        val pending = pendingCameraCapture(context) ?: return false
        if (pending.phase == PendingCameraPhase.LAUNCHED) return false
        return clearMatchingPendingCapture(
            context = context,
            uri = pending.uri,
            deleteFile = true,
            allowedCurrentPhases = setOf(PendingCameraPhase.RESULT_RECEIVED, PendingCameraPhase.ANALYZING),
        )
    }

    /** LAUNCHED is a lease, not an immortal lock. Only an undelivered capture may
     * expire; a returned or analyzing image is never removed by this path. */
    fun expireStaleLaunchedCameraCapture(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        leaseMillis: Long = STALE_CAMERA_FILE_AGE_MILLIS,
    ): Boolean {
        require(leaseMillis >= 0L)
        val pending = pendingCameraCapture(context) ?: return false
        if (pending.phase != PendingCameraPhase.LAUNCHED) return false
        if (nowMillis - pending.createdAtMillis < leaseMillis) return false
        return cancelPendingCameraCapture(context, pending.uri)
    }

    fun persist(context: Context, jpeg: ByteArray): Uri {
        val directory = File(context.filesDir, PHOTO_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "meal_${UUID.randomUUID()}.jpg")
        file.outputStream().use { it.write(jpeg) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Returns bytes only for this app's durable, already stripped photo store.
     * Camera-cache and external content URIs deliberately fall through to the full
     * decode/orientation/metadata-removal pipeline. */
    fun readPersistedJpeg(context: Context, uri: Uri): ByteArray? {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider") return null
        val fileName = uri.managedFileName() ?: return null
        val directory = File(context.filesDir, PHOTO_DIRECTORY).canonicalFile
        val file = File(directory, fileName).canonicalFile
        if (file.parentFile != directory || !file.isFile || file.length() !in 4L..MAX_PERSISTED_JPEG_BYTES) return null
        val bytes = file.readBytes()
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return null
        return bytes
    }

    fun delete(context: Context, uriText: String) {
        val fileName = Uri.parse(uriText).lastPathSegment?.substringAfterLast('/') ?: return
        if (!fileName.matches(Regex("meal_[A-Za-z0-9-]+\\.jpg"))) return
        File(File(context.filesDir, PHOTO_DIRECTORY), fileName).delete()
    }

    /** Delete only aged orphans. The currently persisted external-camera output is
     * protected across application process recreation regardless of its age. */
    fun cleanupStaleCameraOrphans(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        minimumAgeMillis: Long = STALE_CAMERA_FILE_AGE_MILLIS,
    ) {
        val pendingName = pendingCameraCapture(context)?.uri?.managedFileName()
        File(context.cacheDir, PHOTO_DIRECTORY).listFiles()?.forEach { file ->
            val managed = file.isFile && file.name.matches(Regex("meal_[A-Za-z0-9_-]+\\.jpg"))
            val oldEnough = nowMillis - file.lastModified() >= minimumAgeMillis
            if (managed && file.name != pendingName && oldEnough) file.delete()
        }
    }

    fun cleanupOrphans(context: Context, referencedUris: Set<String>) {
        val referencedNames = referencedUris.mapNotNull { Uri.parse(it).lastPathSegment?.substringAfterLast('/') }.toSet()
        File(context.filesDir, PHOTO_DIRECTORY).listFiles()?.forEach { file ->
            val managed = file.isFile && file.name.matches(Regex("meal_[A-Za-z0-9-]+\\.jpg"))
            if (managed && file.name !in referencedNames) file.delete()
        }
    }

    private fun updateMatchingPendingPhase(
        context: Context,
        uri: Uri,
        phase: PendingCameraPhase,
        allowedCurrentPhases: Set<PendingCameraPhase>,
    ): Boolean {
        val current = pendingCameraCapture(context) ?: return false
        if (current.uri.toString() != uri.toString()) return false
        if (current.phase !in allowedCurrentPhases) return false
        if (current.phase == phase) return true
        return cameraPreferences(context).edit().putString(KEY_PENDING_CAMERA_PHASE, phase.name).commit()
    }

    private fun clearMatchingPendingCapture(
        context: Context,
        uri: Uri,
        deleteFile: Boolean,
        allowedCurrentPhases: Set<PendingCameraPhase>,
    ): Boolean {
        val current = pendingCameraCapture(context) ?: return false
        if (current.uri.toString() != uri.toString()) return false
        if (current.phase !in allowedCurrentPhases) return false
        val cleared = cameraPreferences(context).edit()
            .remove(KEY_PENDING_CAMERA_URI)
            .remove(KEY_PENDING_CAMERA_CREATED_AT)
            .remove(KEY_PENDING_CAMERA_PHASE)
            .remove(KEY_PENDING_CAMERA_TARGET_EPOCH_DAY)
            .commit()
        // Cleanup failure must not turn a successfully persisted analysis draft into
        // an apparent analysis failure. Keeping both state and file is retry-safe.
        if (!cleared) return false
        if (deleteFile) deleteCameraCacheFile(context, uri)
        return true
    }

    private fun deleteCameraCacheFile(context: Context, uri: Uri) {
        val fileName = uri.managedFileName() ?: return
        File(File(context.cacheDir, PHOTO_DIRECTORY), fileName).delete()
    }

    private fun Uri.managedFileName(): String? = lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.matches(Regex("meal_[A-Za-z0-9_-]+\\.jpg")) }

    private fun cameraPreferences(context: Context) =
        context.applicationContext.getSharedPreferences(CAMERA_STATE_PREFERENCES, Context.MODE_PRIVATE)

    private const val MAX_PERSISTED_JPEG_BYTES = 12L * 1024L * 1024L
}
