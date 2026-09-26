package com.personal.fitnessledger.data

import java.security.MessageDigest

const val MAX_ADDITIONAL_MEAL_PROMPT_LENGTH = 2000
internal const val KEY_ANALYSIS_ADDITIONAL_PROMPT = "analysis_additional_prompt_v1"

/** Keep ordinary line breaks/tabs, with consistent LF storage and no outer whitespace. */
fun normalizeAdditionalMealPrompt(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trim()

/** Errors deliberately describe only the rule, never the user's private meal notes. */
fun additionalMealPromptValidationError(text: String): String? {
    if (text.length > MAX_ADDITIONAL_MEAL_PROMPT_LENGTH) return "附加提示词最多 $MAX_ADDITIONAL_MEAL_PROMPT_LENGTH 个字符"
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isISOControl() && character != '\n' && character != '\r' && character != '\t') {
            return "附加提示词包含不支持的控制字符"
        }
        if (Character.isHighSurrogate(character)) {
            if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) {
                return "附加提示词包含无效字符"
            }
            index += 1
        } else if (Character.isLowSurrogate(character)) {
            return "附加提示词包含无效字符"
        }
        index += 1
    }
    return null
}

/** Only a digest, not the actual note, becomes part of an HTTP operation identifier. */
internal fun additionalMealPromptDigest(text: String): String {
    val normalized = normalizeAdditionalMealPrompt(text)
    if (normalized.isEmpty()) return ""
    return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
