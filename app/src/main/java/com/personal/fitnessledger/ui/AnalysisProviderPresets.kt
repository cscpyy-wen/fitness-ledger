package com.personal.fitnessledger.ui

internal data class AnalysisProviderPreset(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val modelName: String,
    val guidance: String,
    val helpUrl: String? = null,
)

/** Editable starting points only; a preset makes no claim that an account or model is available. */
internal val analysisProviderPresets = listOf(
    AnalysisProviderPreset(
        id = "qwen", name = "Qwen",
        endpointUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        modelName = "qwen3-vl-plus",
        guidance = "百炼 API Host 可修改；地址、API Key 和模型须属于同一地区与业务空间。",
        helpUrl = "https://help.aliyun.com/zh/model-studio/get-api-key",
    ),
    AnalysisProviderPreset(
        id = "deepseek", name = "DeepSeek",
        endpointUrl = "https://api.deepseek.com",
        modelName = "deepseek-flash",
        guidance = "填写 DeepSeek API Key；模型 ID 可按账户实际可用的图片模型修改。",
        helpUrl = "https://api-docs.deepseek.com/",
    ),
    AnalysisProviderPreset(
        id = "glm", name = "GLM",
        endpointUrl = "https://open.bigmodel.cn/api/paas/v4",
        modelName = "glm-4.6v",
        guidance = "填写智谱开放平台 API Key；可将模型 ID 改为 glm-5.3-flash，App 自动使用 low 思考档。原有 glm-4.6v 仍可使用。",
        helpUrl = "https://docs.bigmodel.cn/",
    ),
    AnalysisProviderPreset(
        id = "custom", name = "自定义", endpointUrl = "", modelName = "",
        guidance = "填写支持图片输入与 Chat Completions 的兼容服务地址及模型 ID。",
    ),
)
