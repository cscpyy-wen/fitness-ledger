package com.personal.fitnessledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.fitnessledger.data.UserProfile
import com.personal.fitnessledger.domain.MacroPlanPresets
import com.personal.fitnessledger.domain.factorInput

@Composable
fun MacroPlanScreen(
    state: AppUiState,
    onSaveProfile: (UserProfile) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBody: () -> Unit,
) {
    MacroPlanEditor(
        profile = state.profile,
        isSaving = state.isSavingProfile,
        isInitialSetup = false,
        onSaveProfile = onSaveProfile,
        onOpenSettings = onOpenSettings,
        onOpenBody = onOpenBody,
    )
}

/** One editor for first-run confirmation and later, explicitly saved changes. */
@Composable
internal fun MacroPlanEditor(
    profile: UserProfile,
    isSaving: Boolean,
    isInitialSetup: Boolean,
    onSaveProfile: (UserProfile) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onOpenBody: (() -> Unit)? = null,
) {
    // rememberSaveable's inputs invalidate a live composition, but are not checked
    // against the inputs that produced restored SaveableStateHolder values. Keep
    // the draft's complete baseline beside its fields so an offscreen backup
    // replacement cannot silently restore an older profile's edits.
    val profileFingerprint = listOf(
        profile.heightCm,
        profile.referenceWeightKg,
        profile.waistCm,
        profile.carbFactor,
        profile.proteinFactor,
        profile.fatFactor,
    ).joinToString(separator = ":", prefix = "$isInitialSetup:") { it.toBits().toString() }
    var draftProfileFingerprint by rememberSaveable { mutableStateOf(profileFingerprint) }
    var weight by rememberSaveable(profile.referenceWeightKg, isInitialSetup) {
        mutableStateOf(if (isInitialSetup) "" else factorInput(profile.referenceWeightKg))
    }
    var carbFactor by rememberSaveable(profile.carbFactor) { mutableStateOf(factorInput(profile.carbFactor)) }
    var proteinFactor by rememberSaveable(profile.proteinFactor) { mutableStateOf(factorInput(profile.proteinFactor)) }
    var fatFactor by rememberSaveable(profile.fatFactor) { mutableStateOf(factorInput(profile.fatFactor)) }
    var height by rememberSaveable(profile.heightCm) { mutableStateOf(factorInput(profile.heightCm)) }
    var waist by rememberSaveable(profile.waistCm) { mutableStateOf(factorInput(profile.waistCm)) }
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var showBodyDetails by rememberSaveable { mutableStateOf(false) }
    var researchLinkError by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current

    if (draftProfileFingerprint != profileFingerprint) {
        weight = if (isInitialSetup) "" else factorInput(profile.referenceWeightKg)
        carbFactor = factorInput(profile.carbFactor)
        proteinFactor = factorInput(profile.proteinFactor)
        fatFactor = factorInput(profile.fatFactor)
        height = factorInput(profile.heightCm)
        waist = factorInput(profile.waistCm)
        draftProfileFingerprint = profileFingerprint
    }

    val weightError = boundedDecimalError(weight, "参考体重", 30.0, 300.0, "30–300 kg")
    val carbError = boundedDecimalError(carbFactor, "碳水系数", 0.0, 10.0, "0–10 g/kg")
    val proteinError = boundedDecimalError(proteinFactor, "蛋白质系数", 0.5, 4.0, "0.5–4 g/kg")
    val fatError = boundedDecimalError(fatFactor, "脂肪系数", 0.1, 5.0, "0.1–5 g/kg")
    val heightError = boundedDecimalError(height, "身高", 100.0, 250.0, "100–250 cm")
    val waistError = boundedDecimalError(waist, "腰围", 30.0, 250.0, "30–250 cm")
    fun decimal(text: String) = text.trim().replace(',', '.').toDouble()
    val valid = listOf(weightError, carbError, proteinError, fatError, heightError, waistError).all { it == null }
    val preview = if (valid) profile.copy(
        referenceWeightKg = decimal(weight),
        carbFactor = decimal(carbFactor),
        proteinFactor = decimal(proteinFactor),
        fatFactor = decimal(fatFactor),
        heightCm = decimal(height),
        waistCm = decimal(waist),
    ) else null
    val hasChanges = isInitialSetup || preview != profile

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp).testTag("macro-plan-scroll"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isInitialSetup) "从你的方案开始" else "我的方案",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isInitialSetup) "先设定每日目标，之后随时可调整" else "当前使用 · ${MacroPlanPresets.titleFor(profile)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("macro-plan-settings")) {
                        Icon(Icons.Default.Settings, contentDescription = "打开设置")
                    }
                }
            }

            PlanCard {
                Text("参考体重", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PlanDecimalField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = "参考体重 kg",
                    tag = "macro-plan-weight",
                    errorMessage = if (isInitialSetup && weight.isBlank()) null else weightError,
                    enabled = !isSaving,
                    hint = if (isInitialSetup && weight.isBlank()) "填写你的参考体重 · 30–300 kg" else null,
                )
                Text(
                    "用它计算每日目标。日常称重不会自动改变这个基准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenBody != null) {
                    TextButton(onClick = onOpenBody, modifier = Modifier.testTag("macro-plan-body")) {
                        Text("查看身体趋势")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从示例起步，或直接自定义", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("可编辑示例 · 非谭成义官方标准", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MacroPlanPresets.entries.forEach { preset ->
                    Card(
                        onClick = {
                            carbFactor = factorInput(preset.carbFactor)
                            proteinFactor = factorInput(preset.proteinFactor)
                            fatFactor = factorInput(preset.fatFactor)
                        },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().testTag("macro-preset-${preset.id}"),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(preset.title, fontWeight = FontWeight.SemiBold)
                            Text("${preset.description} g/kg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("点选填入，保存后生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            PlanCard {
                Text("每日宏量系数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("每公斤参考体重的营养素克数 · g/kg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BoxWithConstraints {
                    val stackFields = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.25f
                    val fields: @Composable (Modifier) -> Unit = { fieldModifier ->
                        PlanDecimalField(carbFactor, { carbFactor = it }, "碳水", "macro-plan-carb", carbError, !isSaving, fieldModifier)
                        PlanDecimalField(proteinFactor, { proteinFactor = it }, "蛋白质", "macro-plan-protein", proteinError, !isSaving, fieldModifier)
                        PlanDecimalField(fatFactor, { fatFactor = it }, "脂肪", "macro-plan-fat", fatError, !isSaving, fieldModifier)
                    }
                    if (stackFields) {
                        Column(Modifier.testTag("macro-plan-factors-stacked"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            fields(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.testTag("macro-plan-factors-row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fields(Modifier.weight(1f))
                        }
                    }
                }
                Text("“3 倍”指每公斤体重 3 g 碳水，不是米饭重量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().testTag("macro-plan-preview"),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("每日目标预览", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (preview != null) {
                        Text(
                            "${formatWhole(preview.dailyTarget.kcal)} kcal",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("macro-plan-preview-kcal"),
                        )
                        PreviewNutrient("碳水", preview.dailyTarget.carbsG, "macro-plan-preview-carbs")
                        PreviewNutrient("蛋白质", preview.dailyTarget.proteinG, "macro-plan-preview-protein")
                        PreviewNutrient("脂肪", preview.dailyTarget.fatG, "macro-plan-preview-fat")
                    } else {
                        Text("填写有效数值后显示目标", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("热量按碳水 / 蛋白质 / 脂肪的 4 / 4 / 9 kcal/g 估算，不保证热量缺口。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            PlanCard {
                DisclosureButton("这些系数怎么用", showExplanation, "macro-plan-explanation") { showExplanation = !showExplanation }
                if (showExplanation) {
                    Text(
                        "每日克数 = 参考体重 × 对应系数。比如参考体重 70 kg、碳水系数 3，得到 210 g 碳水；食物重量还需根据其营养成分换算。\n\n" +
                            "目前未核实谭成义存在唯一固定配方。这里两组数值仅是可编辑示例，全部系数都由你确认；它们不代表个人推荐量，也不保证减脂。\n\n" +
                            "按健康运动成年人场景设计；特殊人群应按专业建议设定。输入范围只是软件允许范围，不代表安全推荐量。\n\n" +
                            "所有食物来源的蛋白质和脂肪都要计入。是否存在热量缺口取决于实际消耗，应结合多日体重与腰围趋势复盘，不因单日停滞自动削减；修改方案后需再次保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("研究依据", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "ISSN · 蛋白质与运动（2017）" to "https://link.springer.com/article/10.1186/s12970-017-0177-8",
                        "ISSN · 饮食与身体成分（2017）" to "https://link.springer.com/article/10.1186/s12970-017-0174-y",
                    ).forEach { (sourceName, sourceUrl) ->
                        TextButton(
                            onClick = {
                                runCatching { uriHandler.openUri(sourceUrl) }
                                    .onSuccess { researchLinkError = null }
                                    .onFailure { researchLinkError = "暂时无法打开浏览器，请稍后重试。" }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(sourceName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    researchLinkError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                DisclosureButton("更多身体资料", showBodyDetails, "macro-plan-body-details") { showBodyDetails = !showBodyDetails }
                if (isInitialSetup && !showBodyDetails) {
                    Text("身高 ${factorInput(profile.heightCm)} cm、腰围 ${factorInput(profile.waistCm)} cm 为待修改起始值，不参与宏量计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showBodyDetails) {
                    Text("用于个人资料和新身体记录预填，不参与每日宏量目标计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PlanDecimalField(height, { height = it }, "身高 cm", "macro-plan-height", heightError, !isSaving)
                    PlanDecimalField(waist, { waist = it }, "参考腰围 cm", "macro-plan-waist", waistError, !isSaving)
                } else if (heightError != null || waistError != null) {
                    Text("请展开并修正身体资料中的数值", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (preview != null) {
                    val target = preview.dailyTarget
                    Text(
                        "${formatWhole(target.kcal)} kcal · 碳水 ${formatOne(target.carbsG)} g · 蛋白质 ${formatOne(target.proteinG)} g · 脂肪 ${formatOne(target.fatG)} g",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("macro-plan-fixed-preview"),
                    )
                }
                Text(
                    when {
                        isSaving -> "正在保存，请稍候"
                        isInitialSetup && weight.isBlank() -> "填写参考体重后，确认才会生效"
                        !valid -> "请修正表单中的数值；当前修改尚未生效"
                        hasChanges -> "仅为预览，确认保存后才会生效"
                        else -> "当前方案已保存；修改后请再次确认"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("macro-plan-save-status"),
                )
                Button(
                    onClick = {
                        preview?.let {
                            focusManager.clearFocus()
                            onSaveProfile(it)
                        }
                    },
                    enabled = valid && !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                        .testTag(if (isInitialSetup) "initial-setup-confirm" else "macro-plan-save"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(if (isSaving) "正在保存…" else if (isInitialSetup) "确认并开始使用" else "保存我的方案", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlanCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun PlanDecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    errorMessage: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        isError = errorMessage != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(12.dp),
        supportingText = if (errorMessage != null || hint != null) ({ Text(requireNotNull(errorMessage ?: hint)) }) else null,
        modifier = modifier.testTag(tag).then(if (errorMessage == null) Modifier else Modifier.semantics { error(errorMessage) }),
    )
}

@Composable
private fun PreviewNutrient(label: String, grams: Double, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("${formatOne(grams)} g", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun DisclosureButton(label: String, expanded: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "收起" else "展开")
    }
}
