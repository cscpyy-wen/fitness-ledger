package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisServiceProfilesTest {
    private lateinit var context: Context
    private lateinit var repository: FitnessRepository
    private val preferences get() = context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences.edit().clear().commit()
        repository = FitnessRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        preferences.edit().clear().commit()
    }

    @Test
    fun encryptedLegacyDirectConfigurationMigratesOnceWithoutLosingItsKey() {
        repository.saveAnalysisConfig(QWEN, "legacy-qwen-key", AnalysisTransport.VISION_API, "qwen-vl-plus")
        // The compatibility writer uses the exact pre-profile v2 key envelope.
        preferences.edit().remove(KEY_ANALYSIS_PROFILES).commit()

        val migrated = repository.analysisProfiles().single()
        assertEquals("Qwen", migrated.name)
        assertEquals(QWEN, migrated.endpointUrl)
        assertEquals("qwen-vl-plus", migrated.modelName)
        assertTrue(migrated.tokenConfigured)
        assertEquals(AnalysisTransport.VISION_API, migrated.transport)
        assertEquals(migrated.id, repository.activeAnalysisProfileId())
        assertEquals("legacy-qwen-key", repository.analysisConfig().accessToken)

        reopen()
        assertEquals(listOf(migrated), repository.analysisProfiles())
        assertEquals(migrated.id, repository.activeAnalysisProfileId())
        assertEquals("legacy-qwen-key", repository.analysisConfig().accessToken)
    }

    @Test
    fun plaintextLegacyProxyMigratesAndKeepsItsProtocolWhenSelectedAgain() {
        preferences.edit()
            .putString("analysis_endpoint", "https://proxy.example.test/analyze")
            .putString("analysis_access_token", "legacy-proxy-key")
            .commit()

        val migrated = repository.analysisProfiles().single()
        assertEquals("原有服务", migrated.name)
        assertEquals(AnalysisTransport.MEAL_PROXY, migrated.transport)
        assertFalse(preferences.contains("analysis_access_token"))
        assertFalse(preferences.all.toString().contains("legacy-proxy-key"))
        val directId = save("Qwen", QWEN, "direct-key")
        assertEquals(directId, repository.activeAnalysisProfileId())
        repository.selectAnalysisProfile(migrated.id)
        assertEquals(AnalysisTransport.MEAL_PROXY, repository.analysisConfig().transport)
        assertEquals("legacy-proxy-key", repository.analysisConfig().accessToken)
        assertEquals(2, repository.analysisProfiles().size)
    }

    @Test
    fun threeServicesKeepTheirOwnCredentialsAcrossSwitchesAndRepositoryReopening() {
        val ids = listOf(save("Qwen", QWEN, "qwen-secret"),
            save("DeepSeek", DEEPSEEK, "deepseek-secret"), save("GLM", GLM, "glm-secret"))
        val endpoints = listOf(QWEN, DEEPSEEK, GLM)
        val keys = listOf("qwen-secret", "deepseek-secret", "glm-secret")
        keys.forEach { key ->
            assertFalse(preferences.all.toString().contains(key))
            assertFalse(repository.analysisProfiles().toString().contains(key))
            assertFalse(repository.analysisConfig().toString().contains(key))
        }
        reopen()
        assertEquals(ids.last(), repository.activeAnalysisProfileId())
        assertEquals(ids, repository.analysisProfiles().map { it.id })
        ids.indices.forEach { index ->
            repository.selectAnalysisProfile(ids[index])
            reopen()
            assertEquals(ids[index], repository.activeAnalysisProfileId())
            assertEquals(endpoints[index], repository.analysisConfig().endpointUrl)
            assertEquals(keys[index], repository.analysisConfig().accessToken)
        }
    }

    @Test
    fun blankKeyReusesOnlyTheEditedProfilesExactEndpointAndProtocol() {
        val id = save("Qwen", QWEN, "qwen-secret")
        repository.saveAnalysisProfile(id, "Qwen 新名称", QWEN, "qwen-vl-max", "")
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
        assertEquals("qwen-vl-max", repository.analysisConfig().modelName)
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveAnalysisProfile(id, "已变更", DEEPSEEK, "deepseek-chat", "")
        }
        assertEquals(QWEN, repository.analysisConfig().endpointUrl)
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
        repository.saveAnalysisProfile(id, "DeepSeek", DEEPSEEK, "deepseek-chat", "replacement-key")
        assertEquals("replacement-key", repository.analysisConfig().accessToken)
    }

    @Test
    fun aMigratedProxyTokenCannotBecomeADirectApiKeyAtTheSameAddress() {
        repository.saveAnalysisConfig(QWEN, "proxy-secret")
        preferences.edit().remove(KEY_ANALYSIS_PROFILES).commit()
        val id = repository.analysisProfiles().single().id
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveAnalysisProfile(id, "Qwen", QWEN, "qwen-vl-plus", "")
        }
        assertEquals(AnalysisTransport.MEAL_PROXY, repository.analysisConfig().transport)
        assertEquals("proxy-secret", repository.analysisConfig().accessToken)
    }

    @Test
    fun profilesAtTheSameAddressNeverBorrowOneAnothersKey() {
        val first = save("个人 Qwen", QWEN, "personal-key")
        assertThrows(IllegalArgumentException::class.java) { save("另一个 Qwen", QWEN, "") }
        val second = save("其他 Qwen", QWEN, "other-key")
        repository.saveAnalysisProfile(first, "个人 Qwen 更新", QWEN, "qwen-vl-max", "")
        assertEquals("personal-key", repository.analysisConfig().accessToken)
        repository.selectAnalysisProfile(second)
        assertEquals("other-key", repository.analysisConfig().accessToken)
        repository.selectAnalysisProfile(first)
        assertEquals("personal-key", repository.analysisConfig().accessToken)
    }

    @Test
    fun deletingTheActiveServiceDisablesAnalysisWithoutSelectingAnotherService() {
        val first = save("Qwen", QWEN, "qwen-secret")
        val second = save("GLM", GLM, "glm-secret")
        repository.deleteAnalysisProfile(second)
        reopen()
        assertNull(repository.activeAnalysisProfileId())
        assertFalse(repository.analysisConfig().isConfigured)
        assertFalse(preferences.contains("analysis_access_token_encrypted"))
        assertEquals(listOf(first), repository.analysisProfiles().map { it.id })
        repository.selectAnalysisProfile(first)
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
        repository.deleteAnalysisProfile(first)
        reopen()
        assertTrue(repository.analysisProfiles().isEmpty())
        assertFalse(repository.hasAnalysisToken())
    }

    @Test
    fun deletingAnInactiveServiceLeavesTheSelectedServiceAndItsKeyUntouched() {
        val first = save("Qwen", QWEN, "qwen-secret")
        val second = save("GLM", GLM, "glm-secret")
        repository.deleteAnalysisProfile(first)
        assertEquals(second, repository.activeAnalysisProfileId())
        assertEquals("glm-secret", repository.analysisConfig().accessToken)
        assertEquals(listOf(second), repository.analysisProfiles().map { it.id })
    }

    @Test
    fun clearingAllCredentialsForgetsAllProfilesAndNeverResurrectsThem() {
        val first = save("Qwen", QWEN, "qwen-secret")
        save("GLM", GLM, "glm-secret")
        repository.clearAnalysisToken()
        reopen()
        assertTrue(repository.analysisProfiles().isEmpty())
        assertNull(repository.activeAnalysisProfileId())
        assertFalse(repository.hasAnalysisToken())
        assertThrows(IllegalArgumentException::class.java) { repository.selectAnalysisProfile(first) }
    }

    @Test
    fun bothActiveClearPathsRemoveOnlyTheSelectedProxyAndKeepOtherCredentials() {
        listOf<() -> Unit>({ repository.clearActiveAnalysisConnection() }, { repository.saveAnalysisConfig("", "") })
            .forEach { clear ->
                repository.clearAnalysisToken()
                repository.saveAnalysisConfig("https://proxy.example.test/analyze", "proxy-secret")
                preferences.edit().remove(KEY_ANALYSIS_PROFILES).commit()
                val proxy = repository.analysisProfiles().single()
                val qwen = save("Qwen", QWEN, "qwen-secret")
                val glm = save("GLM", GLM, "glm-secret")
                repository.selectAnalysisProfile(proxy.id)

                clear()
                reopen()
                assertNull(repository.activeAnalysisProfileId())
                assertFalse(repository.hasAnalysisToken())
                assertFalse(preferences.contains("analysis_access_token_encrypted"))
                assertEquals(listOf(qwen, glm), repository.analysisProfiles().map { it.id })
                assertThrows(IllegalArgumentException::class.java) { repository.selectAnalysisProfile(proxy.id) }
                repository.selectAnalysisProfile(qwen)
                assertEquals("qwen-secret", repository.analysisConfig().accessToken)
                repository.selectAnalysisProfile(glm)
                assertEquals("glm-secret", repository.analysisConfig().accessToken)
            }
    }

    @Test
    fun clearingAStandaloneLegacyConnectionKeepsEverySavedProfile() {
        val qwen = save("Qwen", QWEN, "qwen-secret")
        val glm = save("GLM", GLM, "glm-secret")
        listOf<() -> Unit>({ repository.clearActiveAnalysisConnection() }, { repository.saveAnalysisConfig("", "") })
            .forEach { clear ->
                repository.saveAnalysisConfig("https://proxy.example.test/analyze", "proxy-secret")
                assertNull(repository.activeAnalysisProfileId())
                clear()
                reopen()
                assertNull(repository.activeAnalysisProfileId())
                assertFalse(repository.hasAnalysisToken())
                assertEquals(listOf(qwen, glm), repository.analysisProfiles().map { it.id })
                repository.selectAnalysisProfile(qwen)
                assertEquals("qwen-secret", repository.analysisConfig().accessToken)
                repository.selectAnalysisProfile(glm)
                assertEquals("glm-secret", repository.analysisConfig().accessToken)
            }
    }

    @Test
    fun clearingTheActiveDirectServiceNeverSelectsOrDeletesAnotherSavedService() {
        val qwen = save("Qwen", QWEN, "qwen-secret")
        val glm = save("GLM", GLM, "glm-secret")
        repository.clearActiveAnalysisConnection()
        reopen()
        assertFalse(repository.hasAnalysisToken())
        assertNull(repository.activeAnalysisProfileId())
        assertEquals(listOf(qwen), repository.analysisProfiles().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { repository.selectAnalysisProfile(glm) }
        repository.selectAnalysisProfile(qwen)
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
    }

    @Test
    fun editingAnActiveMigratedProxyUpdatesItsSavedKeyAndAddressAcrossSwitches() {
        val originalEndpoint = "https://proxy.example.test/analyze"
        val replacementEndpoint = "https://replacement.example.test/analyze"
        repository.saveAnalysisConfig(originalEndpoint, "original-proxy-key")
        preferences.edit().remove(KEY_ANALYSIS_PROFILES).commit()
        val proxy = repository.analysisProfiles().single()
        val qwen = save("Qwen", QWEN, "qwen-secret")
        repository.selectAnalysisProfile(proxy.id)

        repository.saveAnalysisConfig(originalEndpoint, "replacement-proxy-key")
        assertEquals(proxy.id, repository.activeAnalysisProfileId())
        assertEquals(proxy.name, repository.analysisProfiles().first { it.id == proxy.id }.name)
        repository.selectAnalysisProfile(qwen)
        repository.selectAnalysisProfile(proxy.id)
        assertEquals("replacement-proxy-key", repository.analysisConfig().accessToken)
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisConfig(replacementEndpoint, "") }
        repository.saveAnalysisConfig(replacementEndpoint, "changed-host-key")
        repository.saveAnalysisConfig(replacementEndpoint, "")
        reopen()
        assertEquals(proxy.id, repository.activeAnalysisProfileId())
        repository.selectAnalysisProfile(qwen)
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
        repository.selectAnalysisProfile(proxy.id)
        assertEquals(replacementEndpoint, repository.analysisConfig().endpointUrl)
        assertEquals("changed-host-key", repository.analysisConfig().accessToken)
        assertEquals(proxy.name, repository.analysisProfiles().first { it.id == proxy.id }.name)
        assertEquals(2, repository.analysisProfiles().size)
    }

    @Test
    fun plaintextLegacyProxyTokenIsDiscardedWhenTransportClaimsDirectApi() {
        preferences.edit()
            .putString("analysis_endpoint", QWEN)
            .putString("analysis_transport", AnalysisTransport.VISION_API.name)
            .putString("analysis_model", "qwen-vl-plus")
            .putString("analysis_access_token", "plaintext-proxy-token")
            .commit()
        val migrated = repository.analysisProfiles().single()
        assertFalse(migrated.tokenConfigured)
        assertNull(repository.activeAnalysisProfileId())
        assertFalse(repository.hasAnalysisToken())
        assertFalse(preferences.contains("analysis_access_token"))
        assertFalse(preferences.contains("analysis_access_token_encrypted"))
        assertThrows(IllegalArgumentException::class.java) { repository.selectAnalysisProfile(migrated.id) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveAnalysisProfile(migrated.id, "Qwen", QWEN, "qwen-vl-plus", "")
        }
        reopen()
        assertFalse(repository.hasAnalysisToken())
        assertFalse(repository.analysisProfiles().single().tokenConfigured)
    }

    @Test
    fun strayPlaintextTokenCannotOverrideAProtocolBoundDirectKey() {
        val qwen = save("Qwen", QWEN, "direct-api-key")
        preferences.edit().putString("analysis_access_token", "plaintext-proxy-token").commit()
        assertEquals("direct-api-key", repository.analysisConfig().accessToken)
        assertEquals(qwen, repository.activeAnalysisProfileId())
        assertFalse(preferences.contains("analysis_access_token"))
        reopen()
        repository.selectAnalysisProfile(qwen)
        assertEquals("direct-api-key", repository.analysisConfig().accessToken)
    }

    @Test
    fun compatibilitySaveDoesNotMislabelItsConnectionAsAnExistingProfile() {
        val id = save("Qwen", QWEN, "qwen-secret")
        repository.saveAnalysisConfig("https://proxy.example.test/analyze", "proxy-secret")
        assertNull(repository.activeAnalysisProfileId())
        assertEquals(listOf(id), repository.analysisProfiles().map { it.id })
        assertEquals("proxy-secret", repository.analysisConfig().accessToken)
        repository.selectAnalysisProfile(id)
        assertEquals("qwen-secret", repository.analysisConfig().accessToken)
    }

    @Test
    fun profileLimitDoesNotPreventEditingOrDeleteAnyExistingCredential() {
        val ids = List(MAX_ANALYSIS_SERVICE_PROFILES) { save("Qwen $it", QWEN, "key-$it") }
        assertThrows(IllegalArgumentException::class.java) { save("超出限制", QWEN, "extra-key") }
        repository.saveAnalysisProfile(ids.first(), "更新第一个", QWEN, "qwen-vl-max", "")
        assertEquals(MAX_ANALYSIS_SERVICE_PROFILES, repository.analysisProfiles().size)
        assertEquals("key-0", repository.analysisConfig().accessToken)
    }

    @Test
    fun unreadableProfilesDisableAnalysisWithoutLosingCiphertextOrBlockingExplicitRecovery() {
        val id = save("Qwen", QWEN, "qwen-secret")
        val damaged = "damaged-encrypted-profile-data"
        preferences.edit().putString(KEY_ANALYSIS_PROFILES, damaged).commit()
        reopen()
        assertTrue(repository.analysisProfilesUnreadable())
        assertTrue(repository.analysisProfiles().isEmpty())
        assertNull(repository.activeAnalysisProfileId())
        assertFalse(repository.analysisConfig().isConfigured)
        assertEquals("", repository.analysisConfig().accessToken)
        assertEquals(damaged, preferences.getString(KEY_ANALYSIS_PROFILES, null))
        assertThrows(IllegalStateException::class.java) { repository.selectAnalysisProfile(id) }
        assertThrows(IllegalStateException::class.java) { save("GLM", GLM, "glm-secret") }
        assertThrows(IllegalStateException::class.java) { repository.deleteAnalysisProfile(id) }
        assertThrows(IllegalStateException::class.java) { repository.clearActiveAnalysisConnection() }
        assertThrows(IllegalStateException::class.java) { repository.saveAnalysisConfig("", "") }
        assertEquals(damaged, preferences.getString(KEY_ANALYSIS_PROFILES, null))
        repository.clearAnalysisToken()
        assertFalse(repository.analysisProfilesUnreadable())
        assertFalse(repository.hasAnalysisToken())
        val newId = save("GLM", GLM, "new-key")
        assertEquals(newId, repository.activeAnalysisProfileId())
        assertEquals("new-key", repository.analysisConfig().accessToken)
    }

    private fun save(name: String, endpoint: String, key: String): String =
        repository.saveAnalysisProfile(null, name, endpoint, "test-model", key)

    private fun reopen() {
        repository.close()
        repository = FitnessRepository(context)
    }

    private companion object {
        const val QWEN = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEEPSEEK = "https://api.deepseek.com/chat/completions"
        const val GLM = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    }
}
