package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.AnalysisTransport
import com.personal.fitnessledger.data.AnalysisServiceProfile
import com.personal.fitnessledger.data.normalizeVisionEndpoint
import com.personal.fitnessledger.data.visionModelValidationError
import java.net.URI

@Composable
internal fun VisionSettingsCard(
    state: AppUiState,
    onSave: (String, String, String, (Boolean) -> Unit) -> Unit,
    onClear: ((Boolean) -> Unit) -> Unit,
    onSaveProfile: ((String?, String, String, String, String, (Boolean) -> Unit) -> Unit)? = null,
    onSelectProfile: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onDeleteProfile: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
) {
    if (onSaveProfile == null) {
        LegacyVisionSettingsCard(state, onSave, onClear)
    } else {
        AnalysisProfilesCard(state, onSaveProfile, onSelectProfile, onDeleteProfile, onClear)
    }
}

internal fun analysisSettingsBusy(state: AppUiState): Boolean =
    state.isSavingAnalysisConfig || state.isAnalyzingPhoto || state.isImportingBackup ||
        state.isExportingBackup || state.restoreRecoveryRequired

@Composable
private fun AnalysisProfilesCard(
    state: AppUiState,
    onSave: (String?, String, String, String, String, (Boolean) -> Unit) -> Unit,
    onSelect: (String, (Boolean) -> Unit) -> Unit,
    onDelete: (String, (Boolean) -> Unit) -> Unit,
    onClear: ((Boolean) -> Unit) -> Unit,
) {
    val busy = analysisSettingsBusy(state)
    val profileChangesBlocked = busy || state.analysisProfilesUnreadable
    // Only the editor target is restorable. API keys live solely inside the editor's memory.
    var editorTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val active = state.analysisProfiles.firstOrNull { it.id == state.activeAnalysisProfileId }
    LaunchedEffect(state.analysisProfilesUnreadable) {
        if (state.analysisProfilesUnreadable) {
            editorTarget = null
            deleteTarget = null
        }
    }

    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("vision-settings-card")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("整餐 AI 识别", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("一张照片识别多道菜，先预填份量、碳水、蛋白质和脂肪，你确认后才记入账本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                state.analysisProfilesUnreadable -> "已保存服务暂时无法读取，照片上传已停用；清除连接后重新配置。"
                active != null && active.tokenConfigured -> "当前启用：${active.name} · ${active.modelName.ifBlank { "自建代理" }}"
                active != null -> "当前服务：${active.name} · 请补充 API Key"
                state.analysisTokenConfigured -> "当前使用自建代理"
                else -> "上传已停用 · 添加或启用一个服务后使用"
            }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("vision-active-profile"))
            if (state.analysisProfiles.isNotEmpty()) {
                Text("已保存的服务", fontWeight = FontWeight.SemiBold)
                state.analysisProfiles.forEach { profile ->
                    Column(Modifier.fillMaxWidth().testTag("vision-profile-${profile.id}"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name + if (profile.id == state.activeAnalysisProfileId) " · 当前启用" else "",
                            fontWeight = FontWeight.SemiBold)
                        Text(profile.modelName.ifBlank { "自建代理" } + " · " +
                            (runCatching { URI(profile.endpointUrl).host }.getOrNull() ?: profile.endpointUrl),
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (profile.tokenConfigured) "密钥已保存在本机 · 尚不代表服务可用" else "未保存密钥",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onSelect(profile.id) {} },
                                enabled = !profileChangesBlocked && profile.tokenConfigured && profile.id != state.activeAnalysisProfileId,
                                modifier = Modifier.testTag("vision-select-${profile.id}")) { Text("启用") }
                            if (profile.transport == AnalysisTransport.VISION_API) {
                                TextButton(onClick = { editorTarget = "edit:${profile.id}" }, enabled = !profileChangesBlocked,
                                    modifier = Modifier.testTag("vision-edit-${profile.id}")) { Text("编辑") }
                            }
                            TextButton(onClick = { deleteTarget = profile.id }, enabled = !profileChangesBlocked,
                                modifier = Modifier.testTag("vision-delete-${profile.id}")) { Text("删除") }
                        }
                        if (profile.transport != AnalysisTransport.VISION_API) {
                            Text("请在下方高级代理设置中编辑此服务。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("添加服务", fontWeight = FontWeight.SemiBold)
            ProviderPresetButtons("vision-add", profileChangesBlocked) { editorTarget = "new:${it.id}" }
            Text("预置只填入地址和模型，可随时修改。保存不会发起付费调用；每个服务独立保存密钥。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("每次拍照只请求当前服务一次，不自动重试；超时后手动重试可能再次计费。单图份量、用油仍是估算。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.analysisProfilesUnreadable || state.analysisTokenConfigured || state.analysisProfiles.any { it.tokenConfigured }) {
                TextButton(onClick = { confirmClear = true }, enabled = !busy,
                    modifier = Modifier.testTag("vision-clear-all")) {
                    Text(if (state.analysisProfilesUnreadable) "清除全部服务并重新配置" else "停用上传并删除全部服务连接和密钥")
                }
            }
        }
    }

    editorTarget?.let { target ->
        val profile = if (target.startsWith("edit:")) state.analysisProfiles.firstOrNull { it.id == target.removePrefix("edit:") } else null
        val preset = analysisProviderPresets.firstOrNull { it.id == target.removePrefix("new:") }
        if (!state.analysisProfilesUnreadable && (profile != null || preset != null)) {
            AnalysisProfileEditor(target, profile, preset, busy, onSave) { editorTarget = null }
        }
    }
    deleteTarget?.let { id ->
        val profile = state.analysisProfiles.firstOrNull { it.id == id }
        if (profile != null) AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除 ${profile.name}？") },
            text = { Text(if (id == state.activeAnalysisProfileId)
                "将删除此服务及其密钥，并停用上传。不会自动启用其他服务。"
                else "将删除此服务及其密钥，其他服务不受影响。") },
            confirmButton = { TextButton(onClick = { onDelete(id) { if (it) deleteTarget = null } }, enabled = !busy,
                modifier = Modifier.testTag("vision-confirm-delete")) { Text("删除服务") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("取消") } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { if (!busy) confirmClear = false },
        title = { Text("删除全部服务连接和密钥？") },
        text = { Text(if (state.analysisProfilesUnreadable)
            "将删除本机暂时无法读取的全部服务连接和密钥，之后可以重新添加服务。"
            else "将停用照片上传，并删除本机保存的全部服务连接和密钥，包括服务名称、地址和模型。再次使用需要重新添加服务。") },
        confirmButton = { TextButton(onClick = { onClear { if (it) confirmClear = false } }, enabled = !busy,
            modifier = Modifier.testTag("vision-confirm-clear-all")) { Text("停用并清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun ProviderPresetButtons(tagPrefix: String, busy: Boolean, onPreset: (AnalysisProviderPreset) -> Unit) {
    Column {
        analysisProviderPresets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    TextButton(onClick = { onPreset(preset) }, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("$tagPrefix-${preset.id}")) { Text(preset.name) }
                }
            }
        }
    }
}

@Composable
private fun AnalysisProfileEditor(
    target: String,
    profile: AnalysisServiceProfile?,
    initialPreset: AnalysisProviderPreset?,
    busy: Boolean,
    onSave: (String?, String, String, String, String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialName = profile?.name ?: initialPreset?.name?.takeUnless { it == "自定义" }.orEmpty()
    val initialEndpoint = profile?.endpointUrl?.removeSuffix("/chat/completions") ?: initialPreset?.endpointUrl.orEmpty()
    val initialModel = profile?.modelName ?: initialPreset?.modelName.orEmpty()
    val initialConsent = profile?.tokenConfigured == true && profile.transport == AnalysisTransport.VISION_API
    var name by rememberSaveable(target) { mutableStateOf(initialName) }
    var endpoint by rememberSaveable(target) { mutableStateOf(initialEndpoint) }
    var model by rememberSaveable(target) { mutableStateOf(initialModel) }
    var presetId by rememberSaveable(target) { mutableStateOf(initialPreset?.id) }
    // Neither the saveable state registry nor AppUiState ever receives this value.
    var apiKey by remember(target, endpoint) { mutableStateOf("") }
    var consent by rememberSaveable(target) { mutableStateOf(initialConsent) }
    var confirmDiscard by rememberSaveable(target) { mutableStateOf(false) }
    var pendingPresetId by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var saveFailed by remember(target) { mutableStateOf(false) }
    val normalized = runCatching { normalizeVisionEndpoint(endpoint) }
    val unchanged = profile?.transport == AnalysisTransport.VISION_API && normalized.getOrNull() == profile.endpointUrl
    val retainKey = unchanged && profile?.tokenConfigured == true
    val dirty = name != initialName || endpoint != initialEndpoint || model != initialModel ||
        apiKey.isNotBlank() || consent != initialConsent
    val modelError = visionModelValidationError(model)
    val preset = analysisProviderPresets.firstOrNull { it.id == presetId }
    val uriHandler = LocalUriHandler.current
    val applyPreset: (AnalysisProviderPreset) -> Unit = {
        name = it.name.takeUnless { value -> value == "自定义" }.orEmpty()
        endpoint = it.endpointUrl
        model = it.modelName
        presetId = it.id
        apiKey = ""
        consent = false
        saveFailed = false
    }
    val requestDismiss: () -> Unit = {
        if (!busy) {
            if (dirty) confirmDiscard = true else onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (profile == null) "添加识别服务" else "编辑 ${profile.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("vision-profile-editor"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选用预置", fontWeight = FontWeight.SemiBold)
                ProviderPresetButtons("vision-preset", busy) { next ->
                    if (dirty) pendingPresetId = next.id else applyPreset(next)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("服务名称") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("vision-profile-name"))
                OutlinedTextField(
                    value = endpoint, onValueChange = {
                        if (it != endpoint) {
                            endpoint = it
                            apiKey = ""
                            consent = false
                            saveFailed = false
                        }
                    },
                    label = { Text("API 地址 / Base URL") },
                    placeholder = { Text("支持图片输入的兼容服务 Base URL") },
                    supportingText = { Text(if (endpoint.isNotBlank() && normalized.isFailure)
                        normalized.exceptionOrNull()?.message.orEmpty()
                        else preset?.guidance ?: "包含 /v1 等路径的 Base URL；更改地址需重新输入密钥并确认上传。") },
                    isError = endpoint.isNotBlank() && normalized.isFailure,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("vision-endpoint"),
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("视觉模型 ID") },
                    supportingText = { Text(modelError ?: "请使用账户可调用且支持图片的模型。") },
                    isError = modelError != null, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("vision-model"))
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text(if (retainKey) "API Key（留空保留此服务密钥）" else "API Key") },
                    supportingText = { Text(if (retainKey)
                        "仅保留这个服务原地址的密钥，不会使用其他服务的密钥。"
                        else "密钥由 Android Keystore 加密，不随备份导出；地址变化后须重新输入。") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("vision-key"))
                val destination = normalized.getOrNull()?.let { URI(it).host } ?: "你填写的服务"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy,
                        modifier = Modifier.testTag("vision-upload-consent"))
                    Text("允许将去除定位等 EXIF 的餐食照片及已保存的附加说明发送至 $destination；服务可能计费，留存按其政策执行。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("保存并启用不会发送照片或测试请求。", style = MaterialTheme.typography.bodySmall)
                preset?.helpUrl?.let { url ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, enabled = !busy) {
                        Text("${preset.name} 官方配置说明")
                    }
                }
                if (saveFailed) Text("保存未完成，请检查配置或页面提示后重试。", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("vision-save-error"))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saveFailed = false
                onSave(profile?.id, name, endpoint, model, apiKey) { success ->
                    if (success) { apiKey = ""; onDismiss() } else saveFailed = true
                }
            }, enabled = !busy && name.isNotBlank() && normalized.isSuccess && modelError == null &&
                (apiKey.isNotBlank() || retainKey) && consent,
                modifier = Modifier.testTag("save-vision-config")) { Text(if (busy) "请稍候…" else "保存并启用") }
        },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !busy,
            modifier = Modifier.testTag("vision-cancel-editor")) { Text("取消") } },
    )
    if (confirmDiscard || pendingPresetId != null) AlertDialog(
        onDismissRequest = { if (!busy) { confirmDiscard = false; pendingPresetId = null } },
        title = { Text("放弃未保存的修改？") },
        text = { Text(if (pendingPresetId != null) "切换预置将清除当前输入的配置和密钥。" else "本次修改和输入的密钥尚未保存。") },
        confirmButton = {
            TextButton(onClick = {
                val next = analysisProviderPresets.firstOrNull { it.id == pendingPresetId }
                pendingPresetId = null
                confirmDiscard = false
                apiKey = ""
                if (next == null) onDismiss() else applyPreset(next)
            }, enabled = !busy, modifier = Modifier.testTag("vision-confirm-discard")) { Text("放弃修改") }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false; pendingPresetId = null }, enabled = !busy,
            modifier = Modifier.testTag("vision-keep-editing")) { Text("继续编辑") } },
    )
}

@Composable
private fun LegacyVisionSettingsCard(
    state: AppUiState,
    onSave: (String, String, String, (Boolean) -> Unit) -> Unit,
    onClear: ((Boolean) -> Unit) -> Unit,
) {
    val direct = state.analysisTransport == AnalysisTransport.VISION_API
    var endpoint by rememberSaveable(state.analysisEndpoint, direct) {
        mutableStateOf(if (direct) state.analysisEndpoint.removeSuffix("/chat/completions") else "")
    }
    var model by rememberSaveable(state.analysisModel) { mutableStateOf(state.analysisModel.ifBlank { "qwen3-vl-plus" }) }
    // Secrets deliberately never enter rememberSaveable, AppUiState, or backup.
    var apiKey by remember(endpoint, direct) { mutableStateOf("") }
    val normalized = runCatching { normalizeVisionEndpoint(endpoint) }
    val unchanged = direct && normalized.getOrNull() == state.analysisEndpoint
    var consent by rememberSaveable(endpoint) { mutableStateOf(unchanged && state.analysisTokenConfigured) }
    val modelError = visionModelValidationError(model)
    val busy = analysisSettingsBusy(state)
    val keyReady = apiKey.isNotBlank() || unchanged && state.analysisTokenConfigured
    val uriHandler = LocalUriHandler.current

    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("vision-settings-card")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("整餐 AI 识别", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("一张照片识别多道菜，先预填份量、碳水、蛋白质和脂肪，你确认后才记入账本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                direct && state.analysisTokenConfigured -> "已保存连接 · ${state.analysisModel}（尚不代表服务可用）"
                state.analysisTokenConfigured -> "当前使用自建代理；可改为下方直连方式"
                else -> "尚未连接 · 配置一次，以后直接拍整餐"
            }, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("API 地址 / Base URL") },
                placeholder = { Text("粘贴百炼 API Host 或兼容服务 Base URL") },
                supportingText = { Text(if (endpoint.isNotBlank() && normalized.isFailure)
                    normalized.exceptionOrNull()?.message.orEmpty()
                    else "百炼可直接粘贴业务空间 API Host；其他服务填写包含 /v1 等路径的 Base URL。") },
                isError = endpoint.isNotBlank() && normalized.isFailure,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("vision-endpoint"),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text("视觉模型 ID") },
                supportingText = { Text(modelError ?: "默认 qwen3-vl-plus；也可填写支持图片的兼容模型 ID。") },
                isError = modelError != null, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("vision-model"),
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text(if (unchanged && state.analysisTokenConfigured) "API Key（留空保留）" else "API Key") },
                supportingText = { Text(if (!unchanged && state.analysisTokenConfigured)
                    "地址或连接方式更改后必须重新输入，旧密钥不会发送到新地址。"
                    else "只在手机上输入。密钥由 Android Keystore 加密，不随备份导出。") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("vision-key"),
            )
            val destination = normalized.getOrNull()?.let { URI(it).host } ?: "你填写的服务"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy,
                    modifier = Modifier.testTag("vision-upload-consent"))
                Text("允许将去除定位等 EXIF 的餐食照片及已保存的附加说明发送至 $destination；服务可能计费，留存按其政策执行。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onSave(endpoint, model, apiKey) { success -> if (success) apiKey = "" } },
                enabled = !busy && normalized.isSuccess && modelError == null && keyReady && consent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-vision-config")) {
                Text(if (state.isSavingAnalysisConfig) "正在保存…" else "保存并启用整餐识别")
            }
            Text("保存不会发起付费调用。每次拍照只请求一次，不自动重试；超时后手动重试可能再次计费。单图份量、用油仍是估算。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { runCatching { uriHandler.openUri("https://help.aliyun.com/zh/model-studio/get-api-key") } }) {
                Text("如何获取百炼 API Key")
            }
            if (state.analysisTokenConfigured) TextButton(onClick = { onClear { if (it) apiKey = "" } }, enabled = !busy) {
                Text("停用上传并清除识别密钥")
            }
        }
    }
}
