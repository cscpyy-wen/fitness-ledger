package com.personal.fitnessledger.data

import org.junit.Assert.*
import org.junit.Test

class VisionConfigurationTest {
    @Test fun acceptsBaseAndFinalUrlsWithoutDuplicatingPath() {
        assertEquals("https://api.example/v1/chat/completions", normalizeVisionEndpoint(" https://api.example/v1/ "))
        assertEquals("https://api.example/v1/chat/completions", normalizeVisionEndpoint("https://api.example/v1/chat/completions/"))
        assertEquals("https://llm-demo.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
            normalizeVisionEndpoint("https://llm-demo.cn-beijing.maas.aliyuncs.com"))
    }

    @Test fun rejectsUnsafeOrWrongEndpointFormats() {
        listOf("", "http://api.example/v1", "https://u:secret@api.example/v1", "https://api.example/v1?key=x",
            "https://api.example/v1#x", "https://api.example", "https://api.example/v1/responses").forEach { value ->
            assertTrue(value, runCatching { normalizeVisionEndpoint(value) }.isFailure)
        }
    }

    @Test fun knownProviderRootsNormalizeWithoutChangingCustomDestinations() {
        assertEquals("https://api.deepseek.com/chat/completions", normalizeVisionEndpoint("https://api.deepseek.com"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", normalizeVisionEndpoint("https://open.bigmodel.cn/"))
        assertEquals("https://custom.example.test/llm/chat/completions", normalizeVisionEndpoint("https://custom.example.test/llm"))
        assertTrue(runCatching { normalizeVisionEndpoint("https://api.deepseek.com.evil.example") }.isFailure)
    }

    @Test fun configurationRequiresVisionModelAndRedactsSecretWhenPrinted() {
        val config = AnalysisServiceConfig("https://api.example/v1/chat/completions", "secret-api-key", AnalysisTransport.VISION_API)
        assertFalse(config.isConfigured)
        assertTrue(config.copy(modelName = "qwen3-vl-plus").isConfigured)
        assertFalse(config.toString().contains("secret-api-key"))
        assertNotEquals(config.credentialBinding, config.copy(transport = AnalysisTransport.MEAL_PROXY).credentialBinding)
        assertNotNull(visionModelValidationError("model\ninjected-header"))
    }

    @Test fun eatenFractionScalesNutritionAndUncertaintyWithoutPromotingEvidence() {
        val original = FoodDraftItem(name = "合餐鸡肉", grams = 400.0, gramsMin = 300.0, gramsMax = 500.0,
            per100g = Nutrition(200.0, 8.0, 24.0, 8.0), sourceName = "视觉模型估算",
            portionBasis = PortionBasis.AI_SINGLE_PHOTO, evidenceTier = EvidenceTier.C)
        val quarter = original.scaledPortion(0.25)
        assertEquals(100.0, quarter.grams, 0.0)
        assertEquals(75.0, quarter.gramsMin, 0.0)
        assertEquals(125.0, quarter.gramsMax, 0.0)
        assertEquals(original.nutrition.kcal / 4, quarter.nutrition.kcal, 0.0)
        assertEquals(EvidenceTier.C, quarter.evidenceTier)
        assertTrue(quarter.userModified)
        listOf(0.0, -1.0, Double.NaN, 1.1).forEach { assertTrue(runCatching { original.scaledPortion(it) }.isFailure) }
    }
}
