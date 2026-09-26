package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.personal.fitnessledger.data.MAX_ADDITIONAL_MEAL_PROMPT_LENGTH
import com.personal.fitnessledger.data.additionalMealPromptValidationError
import com.personal.fitnessledger.data.normalizeAdditionalMealPrompt

internal const val CANTEEN_TRAY_PROMPT_EXAMPLE =
    "仅当可确认是白色方形分格餐盘，且米饭完整未吃过时，米饭为已称量熟重200g；无法确认则不套用；其他菜利用相对体积结合密度估算，不按面积直接换算重量。"

@Composable
internal fun AnalysisAdditionalPromptCard(
    state: AppUiState,
    onSave: (String, (Boolean) -> Unit) -> Unit,
) {
    val busy = analysisSettingsBusy(state)
    val savedPrompt = state.analysisAdditionalPrompt
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("additional-prompt-card")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("附加提示词", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (savedPrompt.isBlank()) "未设置 · 使用内置识别规则" else "已保存 · ${savedPrompt.length} 字符",
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("additional-prompt-status"))
            if (savedPrompt.isNotBlank()) {
                Text(savedPrompt, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("additional-prompt-preview"))
            }
            Text("所有模型服务共用，保存后下次识别生效，不会修改现有草稿。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showEditor = true }, enabled = !busy,
                    modifier = Modifier.testTag("edit-additional-prompt")) { Text("编辑附加提示词") }
                if (savedPrompt.isNotBlank()) {
                    TextButton(onClick = { clearFailed = false; confirmClear = true }, enabled = !busy,
                        modifier = Modifier.testTag("clear-additional-prompt")) { Text("清空") }
                }
            }
        }
    }
    if (showEditor) AdditionalPromptEditor(savedPrompt, busy, onSave) { showEditor = false }
    if (confirmClear) ClearAdditionalPromptDialog(
        busy = busy, failed = clearFailed,
        onDismiss = { confirmClear = false },
        onConfirm = {
            clearFailed = false
            onSave("") { success -> if (success) confirmClear = false else clearFailed = true }
        },
    )
}

private enum class AdditionalPromptConfirmation { DISCARD, CLEAR, REPLACE_WITH_EXAMPLE }

@Composable
private fun AdditionalPromptEditor(
    savedPrompt: String,
    busy: Boolean,
    onSave: (String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep this editing session independent of later repository/bootstrap refreshes.
    val openingPrompt = rememberSaveable { savedPrompt }
    var draft by rememberSaveable { mutableStateOf(savedPrompt) }
    var confirmation by rememberSaveable { mutableStateOf<AdditionalPromptConfirmation?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val normalized = normalizeAdditionalMealPrompt(draft)
    val validationError = additionalMealPromptValidationError(draft)
    val dirty = draft != openingPrompt
    val requestDismiss: () -> Unit = {
        if (!busy) {
            if (dirty) confirmation = AdditionalPromptConfirmation.DISCARD else onDismiss()
        }
    }
    val save: (String) -> Unit = { value ->
        saveFailed = false
        onSave(value) { success ->
            if (success) { confirmation = null; onDismiss() } else saveFailed = true
        }
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        modifier = Modifier.imePadding().safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("编辑附加提示词") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("additional-prompt-editor"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("补充你已确认的餐盘、份量或饮食背景。保存一次后，切换 Qwen、DeepSeek、GLM 等服务时仍会使用。",
                    style = MaterialTheme.typography.bodySmall)
                if (savedPrompt != openingPrompt && !busy) {
                    Text("已保存内容已更新，当前编辑内容仍保留；点击保存将采用本次编辑。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("additional-prompt-saved-change"))
                }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it; saveFailed = false },
                    label = { Text("附加说明") }, minLines = 5, maxLines = 8, enabled = !busy,
                    isError = validationError != null,
                    supportingText = {
                        Column {
                            Text("${draft.length} / $MAX_ADDITIONAL_MEAL_PROMPT_LENGTH 字符",
                                modifier = Modifier.testTag("additional-prompt-count"))
                            validationError?.let { Text(it, modifier = Modifier.testTag("additional-prompt-validation")) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("additional-prompt-input"),
                )
                TextButton(onClick = {
                    if (draft.isNotBlank() && draft != CANTEEN_TRAY_PROMPT_EXAMPLE) {
                        confirmation = AdditionalPromptConfirmation.REPLACE_WITH_EXAMPLE
                    } else {
                        draft = CANTEEN_TRAY_PROMPT_EXAMPLE
                        saveFailed = false
                    }
                }, enabled = !busy, modifier = Modifier.testTag("fill-canteen-prompt-example")) {
                    Text("填入食堂餐盘示例")
                }
                Text("示例只填入编辑框，请核对是否符合你的实际餐盘和称量结果后再保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("附加说明会随照片发送至当前服务，请勿填写密钥或其他隐私信息。它不覆盖内置 JSON 输出格式和安全规则。",
                    style = MaterialTheme.typography.bodySmall)
                Text("保存不请求模型；下次识别生效，不会修改现有草稿。",
                    style = MaterialTheme.typography.bodySmall)
                if (saveFailed) Text("保存未完成，请稍后重试。", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("additional-prompt-save-error"))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (normalized.isBlank() && savedPrompt.isNotBlank()) {
                    confirmation = AdditionalPromptConfirmation.CLEAR
                } else save(normalized)
            }, enabled = !busy && validationError == null && normalized != savedPrompt,
                modifier = Modifier.testTag("save-additional-prompt")) { Text(if (busy) "请稍候…" else "保存") }
        },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !busy,
            modifier = Modifier.testTag("cancel-additional-prompt")) { Text("取消") } },
    )
    when (confirmation) {
        AdditionalPromptConfirmation.CLEAR -> ClearAdditionalPromptDialog(busy, saveFailed,
            onDismiss = { confirmation = null }, onConfirm = { save("") })
        AdditionalPromptConfirmation.DISCARD, AdditionalPromptConfirmation.REPLACE_WITH_EXAMPLE -> {
            val replacing = confirmation == AdditionalPromptConfirmation.REPLACE_WITH_EXAMPLE
            AlertDialog(
                onDismissRequest = { if (!busy) confirmation = null },
                modifier = Modifier.imePadding().safeDrawingPadding(),
                properties = DialogProperties(decorFitsSystemWindows = false),
                title = { Text(if (replacing) "用示例替换编辑内容？" else "放弃未保存的修改？") },
                text = { Text(if (replacing) "当前编辑框内容将被示例替换，已保存的提示词不会改变，直到你点击保存。"
                    else "本次附加提示词修改尚未保存，原有设置会保留。") },
                confirmButton = { TextButton(onClick = {
                    confirmation = null
                    if (replacing) { draft = CANTEEN_TRAY_PROMPT_EXAMPLE; saveFailed = false } else onDismiss()
                }, enabled = !busy, modifier = Modifier.testTag("confirm-additional-prompt-discard")) {
                    Text(if (replacing) "填入示例" else "放弃修改")
                } },
                dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy,
                    modifier = Modifier.testTag("keep-editing-additional-prompt")) { Text("继续编辑") } },
            )
        }
        null -> Unit
    }
}

@Composable
private fun ClearAdditionalPromptDialog(busy: Boolean, failed: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.imePadding().safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("清空已保存的附加提示词？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("下次识别将仅使用内置规则。已保存的模型服务、密钥和现有草稿不会改变。")
            if (failed) Text("清空未完成，请稍后重试。", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy,
            modifier = Modifier.testTag("confirm-clear-additional-prompt")) { Text("清空提示词") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy,
            modifier = Modifier.testTag("cancel-clear-additional-prompt")) { Text("取消") } },
    )
}
