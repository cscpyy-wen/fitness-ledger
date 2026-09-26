package com.personal.fitnessledger.data

import org.junit.Assert.*
import org.junit.Test

class MealAnalysisPreferencesTest {
    @Test fun normalizesLineEndingsAndOuterWhitespaceWithoutFlatteningTheNote() {
        assertEquals("白色餐盘\n熟米饭\t200g\n已去骨", normalizeAdditionalMealPrompt(" \r\n白色餐盘\r\n熟米饭\t200g\r已去骨  "))
        assertEquals("", normalizeAdditionalMealPrompt(" \t\r\n "))
        assertNull(additionalMealPromptValidationError("餐盘\r\n米饭\t200g"))
    }

    @Test fun validatesUtf16LengthControlsAndPairedEmojiWithoutEchoingUserContent() {
        assertNull(additionalMealPromptValidationError("饭".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH)))
        assertNotNull(additionalMealPromptValidationError("饭".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH + 1)))
        assertNull(additionalMealPromptValidationError("🍚".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH / 2)))
        assertNotNull(additionalMealPromptValidationError("🍚".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH / 2) + "饭"))
        listOf('\u0000', '\u000b', '\u001f', '\u007f', '\u0085', '\u009f', '\ud800', '\udc00').forEach {
            val error = requireNotNull(additionalMealPromptValidationError("private-meal-note$it"))
            assertFalse(error.contains("private-meal-note"))
        }
        assertNull(additionalMealPromptValidationError("今天吃🍚，熟重200g"))
    }

    @Test fun contentDigestIsStableForNormalizedNotesAndAbsentForEmptyNotes() {
        assertEquals("", additionalMealPromptDigest(" \r\n\t"))
        assertEquals(additionalMealPromptDigest("米饭200g\n白色餐盘"), additionalMealPromptDigest(" 米饭200g\r\n白色餐盘 "))
        assertNotEquals(additionalMealPromptDigest("米饭200g"), additionalMealPromptDigest("米饭300g"))
        assertTrue(additionalMealPromptDigest("米饭200g").matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun configDiagnosticsNeverPrintMealNotesOrApiKeys() {
        val config = AnalysisServiceConfig("https://example.test/analyze", "private-api-key", additionalPrompt = "private-meal-note")
        assertFalse(config.toString().contains("private-api-key"))
        assertFalse(config.toString().contains("private-meal-note"))
    }
}
