package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealAnalysisPreferencesRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)

    @Before fun reset() { preferences.edit().clear().commit() }
    @After fun cleanup() = reset()

    @Test fun globalPromptSurvivesProviderSwitchDeletionAndBothCredentialClearOperations() {
        val prompt = "白色方形分格餐盘时，米饭熟重200g\n其他食物按照片估计"
        FitnessRepository(context).use { repository ->
            assertEquals("", repository.analysisAdditionalPrompt())
            repository.saveAnalysisAdditionalPrompt(" $prompt ")
            val qwen = repository.saveAnalysisProfile(null, "Qwen", "https://example.test/qwen/chat/completions", "qwen-vl", "qwen-key")
            val glm = repository.saveAnalysisProfile(null, "GLM", "https://example.test/glm/chat/completions", "glm-v", "glm-key")
            repository.selectAnalysisProfile(qwen)
            assertEquals(prompt, repository.analysisConfig().additionalPrompt)
            repository.deleteAnalysisProfile(glm)
            repository.clearActiveAnalysisConnection()
            assertEquals(prompt, repository.analysisConfig().additionalPrompt)
            repository.clearAnalysisToken()
            assertEquals(prompt, repository.analysisAdditionalPrompt())
        }
        FitnessRepository(context).use { restarted ->
            assertEquals(prompt, restarted.analysisConfig().additionalPrompt)
            assertEquals(prompt, preferences.getString(KEY_ANALYSIS_ADDITIONAL_PROMPT, null))
            assertFalse(restarted.hasAnalysisToken())
        }
    }

    @Test fun invalidSaveKeepsThePreviousNoteAndBlankSaveRemovesThePreference() {
        FitnessRepository(context).use { repository ->
            repository.saveAnalysisAdditionalPrompt("原有提示词")
            listOf("饭".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH + 1), "private-note\u0000", "bad\ud800").forEach { invalid ->
                val failure = assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisAdditionalPrompt(invalid) }
                assertFalse(failure.message.orEmpty().contains("private-note"))
                assertEquals("原有提示词", repository.analysisAdditionalPrompt())
            }
            repository.saveAnalysisAdditionalPrompt(" \n\r\t ")
            assertEquals("", repository.analysisAdditionalPrompt())
            assertFalse(preferences.contains(KEY_ANALYSIS_ADDITIONAL_PROMPT))
        }
    }

    @Test fun aMalformedLocalPreferenceDoesNotPreventReadingTheLedgerConfiguration() {
        preferences.edit().putInt(KEY_ANALYSIS_ADDITIONAL_PROMPT, 42).commit()
        FitnessRepository(context).use { repository ->
            assertEquals("", repository.analysisAdditionalPrompt())
            assertEquals("", repository.analysisConfig().additionalPrompt)
            repository.saveAnalysisAdditionalPrompt("修复后的提示词")
            assertEquals("修复后的提示词", repository.analysisAdditionalPrompt())
        }
    }
}
