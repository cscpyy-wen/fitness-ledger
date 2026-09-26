package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisConfigSecurityTest {
    private lateinit var context: Context
    private lateinit var repository: FitnessRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(DATABASE_NAME)
        repository = FitnessRepository(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun encryptedTokenIsReleasedOnlyToItsExactBoundEndpoint() {
        repository.saveAnalysisConfig(ENDPOINT_ONE, "token-one")
        assertEquals("token-one", repository.analysisConfig().accessToken)

        repository.saveAnalysisConfig(ENDPOINT_TWO, "token-two")
        assertEquals("token-two", repository.analysisConfig().accessToken)

        // Simulate an interrupted endpoint preference write. Even though an encrypted
        // token exists, it must not be returned or sent to a different active host.
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANALYSIS_ENDPOINT, ENDPOINT_ONE)
            .commit()
        assertEquals("", repository.analysisConfig().accessToken)
        assertFalse(repository.hasAnalysisToken())

        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANALYSIS_ENDPOINT, ENDPOINT_TWO)
            .commit()
        assertEquals("token-two", repository.analysisConfig().accessToken)
        assertTrue(repository.hasAnalysisToken())
    }

    @Test
    fun directApiKeyIsEncryptedBoundToProtocolAndNeverReusedForAnotherHost() {
        val endpoint = "https://vision.example.test/v1/chat/completions"
        repository.saveAnalysisConfig(endpoint, "synthetic-api-key", AnalysisTransport.VISION_API, "vision-model")
        assertTrue(repository.analysisConfig().isConfigured)
        val prefs = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString().contains("synthetic-api-key"))
        repository.saveAnalysisConfig(endpoint, "", AnalysisTransport.VISION_API, "vision-model-2")
        assertEquals("vision-model-2", repository.analysisConfig().modelName)
        assertEquals("synthetic-api-key", repository.analysisConfig().accessToken)
        assertTrue(runCatching { repository.saveAnalysisConfig(ENDPOINT_TWO, "", AnalysisTransport.VISION_API, "vision-model") }.isFailure)
        assertTrue(runCatching { repository.saveAnalysisConfig(endpoint, "") }.isFailure)
        prefs.edit().putString("analysis_transport", AnalysisTransport.MEAL_PROXY.name).commit()
        assertFalse(repository.hasAnalysisToken())
    }

    @Test
    fun disablingEndpointAtomicallyRemovesEncryptedAndLegacyTokens() {
        repository.saveAnalysisConfig(ENDPOINT_ONE, "token-one")
        val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.contains(KEY_ENCRYPTED_TOKEN))
        preferences.edit().putString(KEY_LEGACY_TOKEN, "legacy-secret").commit()

        repository.saveAnalysisConfig("", "")

        assertEquals("", preferences.getString(KEY_ANALYSIS_ENDPOINT, null))
        assertFalse(preferences.contains(KEY_ENCRYPTED_TOKEN))
        assertFalse(preferences.contains(KEY_LEGACY_TOKEN))
        assertFalse(repository.hasAnalysisToken())
        assertEquals("", repository.analysisConfig().accessToken)
    }

    @Test
    fun upgradedUnsafeQueryEndpointIsAtomicallyScrubbedFromPlainPreferences() {
        repository.saveAnalysisConfig(ENDPOINT_ONE, "token-one")
        val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString(KEY_ANALYSIS_ENDPOINT, "$ENDPOINT_ONE?api_key=plain-secret")
            .putString(KEY_LEGACY_TOKEN, "legacy-secret")
            .commit()

        assertEquals(AnalysisServiceConfig(), repository.analysisConfig())
        assertEquals("", preferences.getString(KEY_ANALYSIS_ENDPOINT, null))
        assertFalse(preferences.contains(KEY_ENCRYPTED_TOKEN))
        assertFalse(preferences.contains(KEY_LEGACY_TOKEN))
    }

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
        const val KEY_ANALYSIS_ENDPOINT = "analysis_endpoint"
        const val KEY_ENCRYPTED_TOKEN = "analysis_access_token_encrypted"
        const val KEY_LEGACY_TOKEN = "analysis_access_token"
        const val ENDPOINT_ONE = "https://one.example.test/analyze"
        const val ENDPOINT_TWO = "https://two.example.test/analyze"
    }
}
