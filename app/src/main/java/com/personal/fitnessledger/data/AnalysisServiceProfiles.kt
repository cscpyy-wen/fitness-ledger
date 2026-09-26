package com.personal.fitnessledger.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** UI-safe view: credentials are never exposed through a profile or its toString. */
data class AnalysisServiceProfile(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val modelName: String,
    val tokenConfigured: Boolean,
    val transport: AnalysisTransport = AnalysisTransport.VISION_API,
)

internal const val KEY_ANALYSIS_PROFILES = "analysis_profiles_encrypted_v1"
internal const val MAX_ANALYSIS_SERVICE_PROFILES = 20

/**
 * One encrypted envelope owns every saved credential and the selected profile.
 * The envelope and the legacy transport fields are committed together. Existing
 * analyzers can therefore keep reading their single, endpoint-bound credential.
 */
internal class AnalysisServiceProfilesStore(private val preferences: SharedPreferences) {
    private val profileCipher = AnalysisSecretCipher("fitness_ledger_analysis_profiles_v1")
    private val tokenCipher = AnalysisSecretCipher("fitness_ledger_analysis_token_v1")

    fun profiles(): List<AnalysisServiceProfile> = synchronized(preferences) {
        readableStateLocked()?.profiles?.map { it.publicView() }.orEmpty()
    }

    fun activeId(): String? = synchronized(preferences) { readableStateLocked()?.activeId }

    fun unreadable(): Boolean = synchronized(preferences) {
        val encoded = preferences.getString(KEY_ANALYSIS_PROFILES, null) ?: return@synchronized false
        runCatching { decodeState(profileCipher.decrypt(encoded)) }.isFailure
    }

    fun config(): AnalysisServiceConfig = synchronized(preferences) {
        val state = readableStateLocked() ?: return@synchronized AnalysisServiceConfig()
        val endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty()
        if (endpoint.isNotBlank() && analysisEndpointValidationError(endpoint) != null) {
            // Upgraded/tampered query strings may contain credentials in plaintext.
            commitLocked(state.copy(activeId = null), AnalysisServiceConfig())
            return@synchronized AnalysisServiceConfig()
        }
        legacyConfigLocked().also { config ->
            if (preferences.contains(KEY_LEGACY_TOKEN)) {
                // Plain legacy tokens never belonged to VISION_API. Removing a
                // stray old token must not deselect an otherwise valid direct key.
                commitLocked(if (config.transport == AnalysisTransport.VISION_API) state
                    else state.copy(activeId = null), config)
            }
        }
    }

    fun save(id: String?, name: String, endpoint: String, modelName: String, apiKey: String): String =
        synchronized(preferences) {
            val normalizedName = name.trim()
            require(normalizedName.isNotBlank() && normalizedName.length <= 60 &&
                normalizedName.none { it.isISOControl() }) { "服务名称需为 1–60 个字符" }
            val normalizedEndpoint = endpoint.trim()
            val normalizedToken = apiKey.trim()
            validateConfigInput(normalizedEndpoint, normalizedToken, AnalysisTransport.VISION_API, modelName)
            require(normalizedEndpoint.isNotBlank()) { "请填写服务地址" }
            val state = stateLocked()
            val previous = id?.let { profileId ->
                requireNotNull(state.profiles.find { it.id == profileId }) { "服务配置已不存在" }
            }
            require(previous != null || state.profiles.size < MAX_ANALYSIS_SERVICE_PROFILES) {
                "最多保存 $MAX_ANALYSIS_SERVICE_PROFILES 个服务"
            }
            require(normalizedToken.isNotBlank() ||
                (previous?.config?.endpointUrl == normalizedEndpoint &&
                    previous.config.transport == AnalysisTransport.VISION_API &&
                    previous.config.accessToken.isNotBlank())) {
                "新增服务或更换地址、连接方式时必须重新输入 API Key"
            }
            val profile = StoredProfile(
                id = previous?.id ?: UUID.randomUUID().toString(),
                name = normalizedName,
                config = AnalysisServiceConfig(
                    normalizedEndpoint,
                    normalizedToken.ifBlank { requireNotNull(previous).config.accessToken },
                    AnalysisTransport.VISION_API,
                    modelName.trim(),
                ),
            )
            val profiles = if (previous == null) state.profiles + profile
                else state.profiles.map { if (it.id == profile.id) profile else it }
            commitLocked(ProfilesState(profiles, profile.id), profile.config)
            profile.id
        }

    fun select(id: String) = synchronized(preferences) {
        val state = stateLocked()
        val profile = requireNotNull(state.profiles.find { it.id == id }) { "服务配置已不存在" }
        require(profile.config.isConfigured) { "请先补充此服务的 API Key 和模型配置" }
        commitLocked(state.copy(activeId = id), profile.config)
    }

    fun delete(id: String) = synchronized(preferences) {
        val state = stateLocked()
        require(state.profiles.any { it.id == id }) { "服务配置已不存在" }
        val deletingActive = state.activeId == id
        commitLocked(
            ProfilesState(state.profiles.filterNot { it.id == id }, if (deletingActive) null else state.activeId),
            if (deletingActive) AnalysisServiceConfig() else legacyConfigLocked(),
        )
    }

    fun saveLegacy(endpoint: String, accessToken: String, transport: AnalysisTransport, modelName: String) =
        synchronized(preferences) {
            val normalizedEndpoint = endpoint.trim()
            val normalizedToken = accessToken.trim()
            validateConfigInput(normalizedEndpoint, normalizedToken, transport, modelName)
            require(normalizedEndpoint.isNotBlank() || normalizedToken.isBlank()) {
                "填写令牌时必须同时填写 HTTPS 代理地址"
            }
            if (normalizedEndpoint.isBlank()) {
                clearActiveLocked()
                return@synchronized
            }
            val state = stateLocked()
            val previous = legacyConfigLocked()
            require(normalizedToken.isNotBlank() ||
                (normalizedEndpoint == previous.endpointUrl && transport == previous.transport &&
                    previous.accessToken.isNotBlank())) {
                "首次配置或更换服务地址、连接方式时必须重新输入 API Key 或令牌"
            }
            val config = AnalysisServiceConfig(
                normalizedEndpoint, normalizedToken.ifBlank { previous.accessToken }, transport,
                if (transport == AnalysisTransport.VISION_API) modelName.trim() else "",
            )
            val activeProxy = state.profiles.find {
                it.id == state.activeId && it.config.transport == AnalysisTransport.MEAL_PROXY
            }
            // Editing a migrated active proxy must update its saved credential;
            // otherwise selecting it later could revive the replaced token.
            // Other compatibility saves configure a standalone connection.
            val updatedState = if (activeProxy == null) state.copy(activeId = null) else state.copy(
                profiles = state.profiles.map {
                    if (it.id == activeProxy.id) StoredProfile(it.id, it.name, config) else it
                },
            )
            commitLocked(updatedState, config)
        }

    fun clearActive() = synchronized(preferences) { clearActiveLocked() }

    fun clear() = synchronized(preferences) { clearLocked() }

    private fun clearActiveLocked() {
        val state = stateLocked()
        // Remove only the selected saved credential. A standalone compatibility
        // connection has no selected profile, so all saved services survive.
        commitLocked(ProfilesState(state.profiles.filterNot { it.id == state.activeId }), AnalysisServiceConfig())
    }

    private fun clearLocked() = commitLocked(ProfilesState(), AnalysisServiceConfig())

    private fun readableStateLocked(): ProfilesState? = try {
        stateLocked()
    } catch (_: UnreadableProfilesException) {
        // A device key failure must not block manual ledger use or make the
        // transport fall back to a stale legacy credential. Keep ciphertext so
        // explicit clearing is a user choice, not silent data destruction.
        null
    }

    private fun stateLocked(): ProfilesState {
        val encoded = preferences.getString(KEY_ANALYSIS_PROFILES, null)
        if (encoded != null) {
            return try {
                decodeState(profileCipher.decrypt(encoded))
            } catch (_: Exception) {
                // Do not silently overwrite unreadable saved credentials or
                // reimport a stale legacy connection. Explicit clear can recover.
                throw UnreadableProfilesException()
            }
        }
        val legacy = legacyConfigLocked()
        val profiles = if (legacy.endpointUrl.isBlank()) emptyList() else listOf(
            StoredProfile(UUID.randomUUID().toString(), migratedName(legacy.endpointUrl), legacy),
        )
        val state = ProfilesState(profiles, profiles.singleOrNull()?.id?.takeIf { legacy.isConfigured })
        // Persist even an empty envelope as a migration tombstone. Deleting the
        // last profile or clearing credentials must never resurrect old settings.
        commitLocked(state, legacy)
        return state
    }

    private fun legacyConfigLocked(): AnalysisServiceConfig {
        val endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty()
        if (endpoint.isBlank() || analysisEndpointValidationError(endpoint) != null) return AnalysisServiceConfig()
        val transport = runCatching {
            AnalysisTransport.valueOf(preferences.getString(KEY_TRANSPORT, AnalysisTransport.MEAL_PROXY.name).orEmpty())
        }.getOrDefault(AnalysisTransport.MEAL_PROXY)
        val config = AnalysisServiceConfig(
            endpointUrl = endpoint, transport = transport,
            modelName = preferences.getString(KEY_MODEL, "").orEmpty(),
        )
        val legacyToken = preferences.getString(KEY_LEGACY_TOKEN, "").orEmpty()
        // The plaintext format predates direct APIs and is MEAL_PROXY-only.
        // Never reinterpret it using a later VISION_API transport preference.
        val token = if (legacyToken.isNotBlank() && transport == AnalysisTransport.MEAL_PROXY) legacyToken
            else readBoundToken(config.credentialBinding)
        return config.copy(accessToken = token)
    }

    private fun readBoundToken(binding: String): String {
        val encrypted = preferences.getString(KEY_ENCRYPTED_TOKEN, "").orEmpty()
        if (encrypted.isBlank()) return ""
        return runCatching {
            val parts = tokenCipher.decrypt(encrypted).split('.', limit = 3)
            require(parts.size == 3 && parts[0] == "v2")
            val endpoint = decodeBase64(parts[1])
            if (endpoint == binding) decodeBase64(parts[2]) else ""
        }.getOrDefault("")
    }

    private fun commitLocked(state: ProfilesState, config: AnalysisServiceConfig) {
        // Prepare all cryptographic output before changing any preference.
        val values = linkedMapOf(
            KEY_ANALYSIS_PROFILES to profileCipher.encrypt(encodeState(state)),
            KEY_ENDPOINT to config.endpointUrl,
            KEY_TRANSPORT to config.transport.name,
            KEY_MODEL to config.modelName,
            KEY_LEGACY_TOKEN to null,
            KEY_ENCRYPTED_TOKEN to config.accessToken.takeIf { it.isNotBlank() }?.let {
                tokenCipher.encrypt("v2.${encodeBase64(config.credentialBinding)}.${encodeBase64(it)}")
            },
        )
        val previous = values.keys.associateWith { preferences.getString(it, null) }
        fun writeValues(valuesToWrite: Map<String, String?>): SharedPreferences.Editor =
            preferences.edit().apply {
                valuesToWrite.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }
        if (!writeValues(values).commit()) {
            // A failed disk commit still updates SharedPreferences' in-memory map.
            // Restore it before another reader could observe a false activation.
            writeValues(previous).apply()
            error("无法安全保存识别服务配置")
        }
    }

    private fun validateConfigInput(endpoint: String, token: String, transport: AnalysisTransport, model: String) {
        analysisEndpointValidationError(endpoint)?.let { throw IllegalArgumentException(it) }
        require(token.all { it.code in 33..126 } && token.length <= 4096) { "API Key 或令牌包含无效字符" }
        if (endpoint.isNotBlank() && transport == AnalysisTransport.VISION_API) {
            visionModelValidationError(model)?.let { throw IllegalArgumentException(it) }
            require(normalizeVisionEndpoint(endpoint) == endpoint) { "请使用完整的 Chat Completions 地址" }
        }
    }

    private fun encodeState(state: ProfilesState): String = JSONObject().apply {
        put("version", 1)
        put("activeId", state.activeId ?: JSONObject.NULL)
        put("profiles", JSONArray().apply {
            state.profiles.forEach { profile -> put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("endpoint", profile.config.endpointUrl)
                put("model", profile.config.modelName)
                put("transport", profile.config.transport.name)
                put("token", profile.config.accessToken)
            }) }
        })
    }.toString()

    private fun decodeState(encoded: String): ProfilesState {
        val root = JSONObject(encoded)
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("profiles")
        require(array.length() <= MAX_ANALYSIS_SERVICE_PROFILES)
        val profiles = List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            StoredProfile(
                entry.getString("id"), entry.getString("name"),
                AnalysisServiceConfig(
                    entry.getString("endpoint"), entry.getString("token"),
                    AnalysisTransport.valueOf(entry.getString("transport")), entry.getString("model"),
                ),
            ).also {
                require(it.id.isNotBlank() && it.id.length <= 128)
                require(it.name.isNotBlank() && it.name.length <= 60)
                require(it.config.endpointUrl.isNotBlank() && analysisEndpointValidationError(it.config.endpointUrl) == null)
            }
        }
        require(profiles.map { it.id }.toSet().size == profiles.size)
        val activeId = if (root.isNull("activeId")) null else root.getString("activeId")
        require(activeId == null || profiles.any { it.id == activeId })
        return ProfilesState(profiles, activeId)
    }

    private fun migratedName(endpoint: String): String = when (runCatching { URI(endpoint).host }.getOrNull()) {
        "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com" -> "Qwen"
        "api.deepseek.com" -> "DeepSeek"
        "open.bigmodel.cn" -> "GLM"
        else -> "原有服务"
    }

    private class StoredProfile(val id: String, val name: String, val config: AnalysisServiceConfig) {
        fun publicView() = AnalysisServiceProfile(id, name, config.endpointUrl, config.modelName,
            config.accessToken.isNotBlank(), config.transport)
    }

    private data class ProfilesState(val profiles: List<StoredProfile> = emptyList(), val activeId: String? = null)

    private class UnreadableProfilesException : IllegalStateException("无法解密已保存的服务配置，请清除识别服务后重新添加")

    private companion object {
        const val KEY_ENDPOINT = "analysis_endpoint"
        const val KEY_TRANSPORT = "analysis_transport"
        const val KEY_MODEL = "analysis_model"
        const val KEY_LEGACY_TOKEN = "analysis_access_token"
        const val KEY_ENCRYPTED_TOKEN = "analysis_access_token_encrypted"
    }
}

private class AnalysisSecretCipher(private val alias: String) {
    fun encrypt(payload: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val parts = encoded.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}

private fun encodeBase64(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
private fun decodeBase64(value: String): String = Base64.decode(value, Base64.NO_WRAP).toString(Charsets.UTF_8)
