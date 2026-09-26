package com.personal.fitnessledger.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerBackupManagerTest {
    private lateinit var context: Context
    private val passphrase = "可靠备份口令-2026".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearAll()
    }

    @After
    fun tearDown() = clearAll()

    @Test
    fun recoveryStatusWaitsForAnotherRepositoryStartupInsteadOfReportingTransientPoison() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val slowContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                if (blockOnce.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return context.noBackupFilesDir
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val recovering = workers.submit { LedgerBackupManager.recoverInterruptedRestore(slowContext) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val checking = workers.submit<Boolean> { LedgerBackupManager.restoreRecoveryRequired(context) }
            // The status must be observed only after the coordinator finishes,
            // not while its temporary fail-closed marker is asserted.
            assertThrows(TimeoutException::class.java) { checking.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            recovering.get(5, TimeUnit.SECONDS)
            assertFalse(checking.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun encryptedRoundTripRestoresEveryBusinessTablePreferencesAndPhotoButNotToken() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 7)
        val expectedCounts = tableCounts()
        val packageBytes = exportPackage()

        clearAll()
        val alreadyOpenDatabase = FitnessDatabase(context).also { it.writableDatabase }
        val result = LedgerBackupManager(context).use { manager ->
            manager.importEncrypted(ByteArrayInputStream(packageBytes), passphrase)
        }

        assertEquals(81.5, alreadyOpenDatabase.getProfile().referenceWeightKg, 0.0)
        alreadyOpenDatabase.close()
        assertEquals(expectedCounts, tableCounts())
        FitnessDatabase(context).use { database ->
            assertEquals(81.5, database.getProfile().referenceWeightKg, 0.0)
            assertEquals("备份动作", database.listExercises(true).single { it.isCustom }.name)
            assertEquals(1, database.listWorkoutHistory().size)
            val draft = requireNotNull(database.latestDraft())
            assertArrayEquals(jpeg(7), PhotoStorage.readPersistedJpeg(context, android.net.Uri.parse(draft.photoUri)))
        }
        assertTrue(context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
            .getBoolean("profile_configured", false))
        assertEquals(15.0, WorkoutPlannerStore(context).load(null, emptySet()).barbellBarWeightKg, 0.0)
        val settings = context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
        assertFalse(settings.contains("analysis_endpoint"))
        assertFalse(settings.contains("analysis_transport"))
        assertFalse(settings.contains("analysis_model"))
        assertFalse(settings.contains("analysis_access_token_encrypted"))
        assertTrue(result.requiresAnalysisReconfiguration)
        assertEquals(expectedCounts, result.restoredRowCounts)
        assertEquals(1, result.restoredPhotoCount)

        // Importing the exact same authenticated generation is idempotent.
        LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(packageBytes), passphrase) }
        assertEquals(expectedCounts, tableCounts())
    }

    @Test
    fun backupOmitsAllServiceProfilesAndRestoreDisablesEveryExistingConnection() {
        FitnessRepository(context).use { repository ->
            repository.saveAnalysisProfile(null, "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-vl-plus", "qwen-secret")
            repository.saveAnalysisProfile(null, "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", "deepseek-secret")
            repository.saveAnalysisProfile(null, "GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6v", "glm-secret")
        }
        val packageBytes = exportPackage()
        rewriteAuthenticatedPayload(packageBytes) { root ->
            val settings = JSONObject(Base64.decode(
                root.getJSONObject("files").getString("preferences/fitness_settings.json"), Base64.NO_WRAP,
            ).toString(Charsets.UTF_8)).getJSONObject("values")
            listOf(KEY_ANALYSIS_PROFILES, "analysis_endpoint", "analysis_transport", "analysis_model",
                "analysis_access_token", "analysis_access_token_encrypted").forEach { assertFalse(settings.has(it)) }
            listOf("qwen-secret", "deepseek-secret", "glm-secret", "dashscope.aliyuncs.com", "api.deepseek.com",
                "open.bigmodel.cn").forEach { assertFalse(settings.toString().contains(it)) }
        }
        FitnessRepository(context).use { repository ->
            repository.saveAnalysisProfile(null, "恢复前新服务", "https://other.example.test/v1/chat/completions", "other-model", "other-secret")
            repository.importEncryptedBackup(ByteArrayInputStream(packageBytes), passphrase)
            assertFalse(context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).contains(KEY_ANALYSIS_PROFILES))
            assertTrue(repository.analysisProfiles().isEmpty())
            assertEquals(null, repository.activeAnalysisProfileId())
            assertFalse(repository.hasAnalysisToken())
        }
        FitnessRepository(context).use { reopened ->
            assertTrue(reopened.analysisProfiles().isEmpty())
            assertFalse(reopened.analysisConfig().isConfigured)
        }
    }

    @Test
    fun profileCredentialsInjectedIntoAnAuthenticatedBackupAreRejectedBeforeRestore() {
        val sourcePackage = exportPackage()
        val forbidden = rewriteStringPreference(sourcePackage, "preferences/fitness_settings.json",
            KEY_ANALYSIS_PROFILES, "device-bound-profile-secret")
        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(forbidden), passphrase) }
        }
        // The new optional exclusion declaration must not make older backups
        // incompatible when they predate the profile store entirely.
        val legacy = rewriteAuthenticatedPayload(sourcePackage) { root ->
            val manifest = root.getJSONObject("manifest")
            val exclusions = manifest.getJSONArray("excluded")
            manifest.put("excluded", JSONArray().apply {
                repeat(exclusions.length()) { index ->
                    val value = exclusions.getString(index)
                    if (value != "fitness_settings.analysis_profiles_encrypted_v1") put(value)
                }
            })
        }
        LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(legacy), passphrase) }
    }

    @Test
    fun additionalMealPromptTravelsInThePasswordBackupAsBusinessData() {
        val prompt = "白色方形分格餐盘时，米饭熟重200g\n这是用户记录的份量线索"
        FitnessRepository(context).use { it.saveAnalysisAdditionalPrompt(prompt) }
        val packageBytes = exportPackage()
        rewriteAuthenticatedPayload(packageBytes) { root ->
            val settings = JSONObject(Base64.decode(
                root.getJSONObject("files").getString("preferences/fitness_settings.json"), Base64.NO_WRAP,
            ).toString(Charsets.UTF_8)).getJSONObject("values")
            assertEquals(prompt, settings.getJSONObject(KEY_ANALYSIS_ADDITIONAL_PROMPT).getString("value"))
        }
        FitnessRepository(context).use { repository ->
            repository.saveAnalysisAdditionalPrompt("当前设备旧提示词")
            repository.importEncryptedBackup(ByteArrayInputStream(packageBytes), passphrase)
            assertEquals(prompt, repository.analysisAdditionalPrompt())
            assertEquals(prompt, repository.analysisConfig().additionalPrompt)
            assertFalse(repository.analysisConfig().isConfigured)
        }
    }

    @Test
    fun restoringAnOlderBackupWithoutMealPromptClearsTheCurrentPrompt() {
        val packageBytes = exportPackage()
        FitnessRepository(context).use { repository ->
            repository.saveAnalysisAdditionalPrompt("旧备份里没有这一条")
            repository.importEncryptedBackup(ByteArrayInputStream(packageBytes), passphrase)
            assertEquals("", repository.analysisAdditionalPrompt())
            assertFalse(context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
                .contains(KEY_ANALYSIS_ADDITIONAL_PROMPT))
        }
    }

    @Test
    fun maliciousMealPromptTypesLengthsAndControlsAreRejectedBeforeAnyRestoreMutation() {
        val packageBytes = exportPackage()
        FitnessDatabase(context).use { it.updateProfile(it.getProfile().copy(referenceWeightKg = 93.0)) }
        FitnessRepository(context).use { it.saveAnalysisAdditionalPrompt("必须保留的当前提示词") }
        val invalidEntries = listOf(
            JSONObject().put("type", "int").put("value", 42),
            JSONObject().put("type", "null").put("value", JSONObject.NULL),
            JSONObject().put("type", "string").put("value", true),
            JSONObject().put("type", "string").put("value", "x".repeat(MAX_ADDITIONAL_MEAL_PROMPT_LENGTH + 1)),
            JSONObject().put("type", "string").put("value", "private-meal-note\u0000"),
        )
        invalidEntries.forEach { entry ->
            val invalid = rewriteAuthenticatedFile(packageBytes, "preferences/fitness_settings.json") { bytes ->
                JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                    getJSONObject("values").put(KEY_ANALYSIS_ADDITIONAL_PROMPT, entry)
                }.toString().toByteArray(Charsets.UTF_8)
            }
            val failure = assertThrows(LedgerBackupIntegrityException::class.java) {
                LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(invalid), passphrase) }
            }
            assertFalse(failure.message.orEmpty().contains("private-meal-note"))
            assertEquals(93.0, currentWeight(), 0.0)
            FitnessRepository(context).use { assertEquals("必须保留的当前提示词", it.analysisAdditionalPrompt()) }
            assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        }
    }

    @Test
    fun legitimateLegacyPlannerLargerThanTwoMillionCharactersRoundTripsWithoutDataLoss() {
        val expectedEnvelope = seedLargePlannerEnvelope()
        val packageBytes = exportPackage()

        clearAll()
        LedgerBackupManager(context).use { manager ->
            manager.importEncrypted(ByteArrayInputStream(packageBytes), passphrase)
        }

        assertEquals(expectedEnvelope, context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE)
            .getString("workout_planner_envelope_v1", null))
        val restored = WorkoutPlannerStore(context).load(null, emptySet()).templates
        assertEquals(9, restored.size)
        assertTrue(restored.all { it.items.size == 500 && it.items.all { item -> item.note == "x".repeat(500) } })
    }

    @Test
    fun failedRestoreCanRollBackExistingLargePlannerEnvelope() {
        val sourcePackage = exportPackage()
        val expectedEnvelope = seedLargePlannerEnvelope()

        assertThrows(IllegalStateException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_PREFERENCES_COMMITTED)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        assertFalse(LedgerBackupManager.restoreRecoveryRequired(context))
        assertEquals(expectedEnvelope, context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE)
            .getString("workout_planner_envelope_v1", null))
        assertEquals(9, WorkoutPlannerStore(context).load(null, emptySet()).templates.size)
    }

    @Test
    fun exportRejectsBusinessPreferencesThatItsOwnImporterWouldReject() {
        context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).edit()
            .putString("profile_configured", "wrong primitive type").commit()
        val output = ByteArrayOutputStream()

        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { it.exportEncrypted(output, passphrase) }
        }
        assertEquals(0, output.size())
    }

    @Test fun legacyV10BackupRestoresWithoutInventingCloudRecords() {
        seedRepresentativeLedger(referenceWeight=81.5,photoTail=55)
        val oldDatabase=rewriteAuthenticatedFile(exportPackage(),"database.json") { bytes ->
            val database=JSONObject(bytes.toString(Charsets.UTF_8)).put("schemaVersion",10)
            val tables=database.getJSONArray("tables")
            val kept=JSONArray()
            repeat(tables.length()) { i -> val table=tables.getJSONObject(i); if(!table.getString("name").startsWith("xiaomi_")) kept.put(table) }
            database.put("tables",kept)
            database.getJSONObject("sequences").remove("xiaomi_weights")
            database.toString().toByteArray(Charsets.UTF_8)
        }
        val oldBackup=rewriteAuthenticatedPayload(oldDatabase,schemaOverride=10) { root ->
            val manifest=root.getJSONObject("manifest").put("databaseSchemaVersion",10)
            val exclusions=manifest.getJSONArray("excluded")
            val legacyExclusions=JSONArray()
            repeat(exclusions.length()) { i ->
                if(!exclusions.getString(i).startsWith("xiaomi_cloud_credentials")) legacyExclusions.put(exclusions.getString(i))
            }
            manifest.put("excluded",legacyExclusions)
        }
        clearAll()
        LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(oldBackup),passphrase) }
        FitnessDatabase(context).use {
            assertEquals(11,it.readableDatabase.version)
            assertEquals(81.5,it.getProfile().referenceWeightKg,0.0)
            assertTrue(XiaomiWeightLedger.list(it.readableDatabase).isEmpty())
            assertEquals(null,XiaomiWeightLedger.status(it.readableDatabase).accountKey)
            assertEquals(1,it.listWorkoutHistory().size)
        }
    }

    @Test
    fun successfulRestoreInvalidatesPrewarmedEmptyHistoryOnSameRepositoryHandle() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 40)
        val sourcePackage = exportPackage()
        clearAll()
        val repository = FitnessRepository(context)
        assertTrue(repository.listWorkoutHistory(limit = 2, offset = 0).isEmpty())
        assertTrue(repository.listWorkoutHistory(limit = 2, offset = 8).isEmpty())

        LedgerBackupManager(context).use { manager ->
            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
        }

        val restored = repository.listWorkoutHistory(limit = 2, offset = 0).single()
        assertEquals("备份训练", restored.session.title)
    }

    @Test
    fun successfulRestoreInvalidatesPrewarmedMultiPageAnchorsOnSameRepositoryHandle() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 41)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 42)
        seedCompletedWorkouts(count = 6, titlePrefix = "目标历史")
        val repository = FitnessRepository(context)
        val prewarmed = listOf(0, 2, 4, 6).flatMap { offset ->
            repository.listWorkoutHistory(limit = 2, offset = offset)
        }
        assertEquals(7, prewarmed.map { it.session.id }.distinct().size)

        LedgerBackupManager(context).use { manager ->
            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
        }

        assertTrue(repository.listWorkoutHistory(limit = 2, offset = 2).isEmpty())
        assertEquals(
            listOf("备份训练"),
            repository.listWorkoutHistory(limit = 2, offset = 0).map { it.session.title },
        )
    }

    @Test
    fun wrongPasswordAndTamperingAreRejectedBeforeCurrentLedgerChanges() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 3)
        val packageBytes = exportPackage()
        FitnessDatabase(context).use { it.updateProfile(it.getProfile().copy(referenceWeightKg = 95.0)) }

        assertThrows(LedgerBackupAuthenticationException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.importEncrypted(ByteArrayInputStream(packageBytes), "错误口令-123456".toCharArray())
            }
        }
        assertEquals(95.0, currentWeight(), 0.0)

        val tampered = packageBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(LedgerBackupAuthenticationException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.importEncrypted(ByteArrayInputStream(tampered), passphrase)
            }
        }
        assertEquals(95.0, currentWeight(), 0.0)
    }

    @Test
    fun truncatedUnknownVersionAndMalformedAuthenticatedManifestAreRejectedBeforeMutation() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 4)
        val packageBytes = exportPackage()
        FitnessDatabase(context).use { it.updateProfile(it.getProfile().copy(referenceWeightKg = 94.0)) }

        assertThrows(LedgerBackupException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(packageBytes.copyOf(20)), passphrase) }
        }
        val unknownFormat = packageBytes.copyOf().also { it[11] = 2 }
        assertThrows(LedgerBackupCompatibilityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(unknownFormat), passphrase) }
        }
        val unknownSchema = packageBytes.copyOf().also { it[15] = 12 }
        assertThrows(LedgerBackupCompatibilityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(unknownSchema), passphrase) }
        }

        val illegalPath = rewriteAuthenticatedPayload(packageBytes) { root ->
            val manifest = root.getJSONObject("manifest")
            val entry = manifest.getJSONArray("files").getJSONObject(0)
            val oldPath = entry.getString("path")
            entry.put("path", "../escape")
            val files = root.getJSONObject("files")
            val value = files.getString(oldPath)
            files.remove(oldPath)
            files.put("../escape", value)
        }
        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(illegalPath), passphrase) }
        }
        val duplicate = rewriteAuthenticatedPayload(packageBytes) { root ->
            val entries = root.getJSONObject("manifest").getJSONArray("files")
            entries.put(JSONObject(entries.getJSONObject(0).toString()))
        }
        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(duplicate), passphrase) }
        }
        val oversized = rewriteAuthenticatedPayload(packageBytes) { root ->
            root.getJSONObject("manifest").getJSONArray("files").getJSONObject(0)
                .put("bytes", 300L * 1024L * 1024L)
        }
        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { it.importEncrypted(ByteArrayInputStream(oversized), passphrase) }
        }
        assertEquals(94.0, currentWeight(), 0.0)
    }

    @Test
    fun authenticatedPreferenceWithWrongBusinessTypeIsRejectedBeforeAnyReplacement() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 5)
        val packageBytes = exportPackage()
        FitnessDatabase(context).use { it.updateProfile(it.getProfile().copy(referenceWeightKg = 93.0)) }
        context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).edit()
            .putString("local-marker", "must-survive")
            .commit()
        val invalid = rewriteAuthenticatedFile(packageBytes, "preferences/fitness_settings.json") { bytes ->
            JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                getJSONObject("values").getJSONObject("profile_configured")
                    .put("type", "string")
                    .put("value", "true")
            }.toString().toByteArray(Charsets.UTF_8)
        }

        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.importEncrypted(ByteArrayInputStream(invalid), passphrase)
            }
        }

        assertEquals(93.0, currentWeight(), 0.0)
        assertEquals(
            "must-survive",
            context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
                .getString("local-marker", null),
        )
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun authenticatedTerminalSqliteSequenceIsRejectedBeforeAnyReplacement() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 60)
        val sourcePackage = exportPackage()
        val invalid = rewriteAuthenticatedFile(sourcePackage, "database.json") { bytes ->
            JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                getJSONObject("sequences").put("workout_sessions", Long.MAX_VALUE)
            }.toString().toByteArray(Charsets.UTF_8)
        }
        FitnessDatabase(context).use {
            it.updateProfile(it.getProfile().copy(referenceWeightKg = 93.0))
        }

        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.importEncrypted(ByteArrayInputStream(invalid), passphrase)
            }
        }

        assertEquals(93.0, currentWeight(), 0.0)
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun authenticatedTextInIntegerColumnIsRejectedBeforeAnyReplacement() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 63)
        val sourcePackage = exportPackage()
        val invalid = rewriteAuthenticatedFile(sourcePackage, "database.json") { bytes ->
            JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                val tables = getJSONArray("tables")
                val meals = (0 until tables.length())
                    .map { tables.getJSONObject(it) }
                    .single { it.getString("name") == "meals" }
                val columns = meals.getJSONArray("columns")
                val confirmedAtIndex = (0 until columns.length())
                    .single { columns.getString(it) == "confirmed_at" }
                meals.getJSONArray("rows").getJSONArray(0).put(
                    confirmedAtIndex,
                    JSONObject().apply {
                        put("type", "text")
                        put("value", "zzz")
                    },
                )
            }.toString().toByteArray(Charsets.UTF_8)
        }
        FitnessDatabase(context).use {
            it.updateProfile(it.getProfile().copy(referenceWeightKg = 93.0))
        }

        assertThrows(LedgerBackupIntegrityException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.importEncrypted(ByteArrayInputStream(invalid), passphrase)
            }
        }

        assertEquals(93.0, currentWeight(), 0.0)
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun authenticatedWorkoutJournalsAndLivePlannerRefsMustMatchIncomingDatabase() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 50)
        val (draftId, weightExerciseId, durationExerciseId) = FitnessDatabase(context).use { database ->
            val draft = database.startWorkout("备份中的训练")
            Triple(
                draft.id,
                database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }.id,
                database.listExercises().first { it.trackingType == TrackingType.DURATION }.id,
            )
        }
        val sourcePackage = exportPackage()
        val invalidPackages = listOf(
            rewriteStringPreference(
                sourcePackage,
                "preferences/fitness_settings.json",
                "pending_single_set_v1",
                pendingSingleSetJson(draftId, weightExerciseId, weightKg = 0.0, reps = 5),
            ),
            rewriteStringPreference(
                sourcePackage,
                "preferences/fitness_settings.json",
                "pending_5x5_v2",
                pendingFiveByFiveJson(draftId, exerciseId = 999_999L),
            ),
            rewriteStringPreference(
                sourcePackage,
                "preferences/fitness_settings.json",
                "pending_5x5_v2",
                pendingFiveByFiveJson(sessionId = 1L, exerciseId = weightExerciseId),
            ),
            rewriteLegacyFiveByFivePreferences(sourcePackage, durationExerciseId),
            rewritePlannerEnvelope(sourcePackage, plannerEnvelopeJson(
                editor = plannerEditorJson(sessionId = 1L, exerciseId = weightExerciseId),
            )),
            rewritePlannerEnvelope(sourcePackage, plannerEnvelopeJson(
                activePlan = activePlannerJson(draftId, exerciseId = 999_999L),
            )),
        )

        clearAll()
        seedRepresentativeLedger(referenceWeight = 93.0, photoTail = 51)
        invalidPackages.forEach { invalid ->
            assertThrows(LedgerBackupIntegrityException::class.java) {
                LedgerBackupManager(context).use { manager ->
                    manager.importEncrypted(ByteArrayInputStream(invalid), passphrase)
                }
            }
            assertEquals(93.0, currentWeight(), 0.0)
            assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        }
    }

    @Test
    fun authenticatedDurationPendingSetRestoresAndCommitsWithActualTrackingType() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 52)
        val (draftId, exerciseId) = FitnessDatabase(context).use { database ->
            database.startWorkout("时长动作恢复").id to
                database.listExercises().first { it.trackingType == TrackingType.DURATION }.id
        }
        assertTrue(
            context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).edit()
                .putString(
                    "pending_single_set_v1",
                    pendingSingleSetJson(
                        sessionId = draftId,
                        exerciseId = exerciseId,
                        weightKg = 0.0,
                        reps = 0,
                        durationSeconds = 90,
                    ),
                )
                .commit(),
        )
        val sourcePackage = exportPackage()

        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 53)
        LedgerBackupManager(context).use { manager ->
            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
        }

        val repository = FitnessRepository(context)
        val pending = requireNotNull(repository.pendingSingleSetCommit())
        assertEquals(TrackingType.DURATION, repository.listExercises().single { it.id == pending.exerciseId }.trackingType)
        val committed = repository.commitPendingSingleSet()
        assertEquals(0, committed.set.reps)
        assertEquals(90, committed.set.durationSeconds)
        assertEquals(0L, committed.set.loadGrams)
    }

    @Test
    fun injectedFailureAfterDatabaseCommitRollsBackDatabasePreferencesAndPhotos() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 1)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 9)
        val targetCounts = tableCounts()
        context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).edit()
            .putString("target_marker", "keep").commit()

        assertThrows(IllegalStateException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_DATABASE_COMMITTED)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        assertEquals(96.0, currentWeight(), 0.0)
        assertEquals(targetCounts, tableCounts())
        assertEquals("keep", context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE)
            .getString("target_marker", null))
        val photo = FitnessDatabase(context).use { requireNotNull(it.latestDraft()).photoUri }
        assertArrayEquals(jpeg(9), PhotoStorage.readPersistedJpeg(context, android.net.Uri.parse(photo)))
    }

    @Test
    fun failedImportAndRejectedRollbackCommitKeepWritesLockedUntilRecoveryFinishes() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 54)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 55)
        val repository = FitnessRepository(context)
        assertEquals(96.0, repository.getProfile().referenceWeightKg, 0.0)

        val failure = assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_DATABASE_COMMITTED)
                manager.failNextRollbackDatabaseCommitForTest()
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        assertTrue(failure.message.orEmpty().contains("账本已锁定"))
        assertTrue(repository.restoreRecoveryRequired())
        assertTrue(File(context.noBackupFilesDir, "ledger_restore_transaction_v1/journal.json").isFile)
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 99.0))
        }
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.saveBarbellBarWeight(10.0)
        }
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.markProfileConfigured()
        }

        // The next startup coordinator can now finish the preserved rollback.
        // Only after the durable journal disappears may the same repository
        // handle accept writes again.
        LedgerBackupManager.recoverInterruptedRestore(context)
        assertFalse(repository.restoreRecoveryRequired())
        assertEquals(96.0, repository.getProfile().referenceWeightKg, 0.0)
        repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 97.0))
        assertEquals(97.0, repository.getProfile().referenceWeightKg, 0.0)
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun finalTransactionUnlinkWithoutParentSyncStaysLockedUntilDurabilityRetry() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 56)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 57)
        val repository = FitnessRepository(context)

        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.FINAL_TRANSACTION_PARENT_SYNC_FAILED)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        // The imported generation is already committed and the journal name was
        // unlinked, but the unlink was not proven durable. Process poison keeps
        // every mutation closed until an unconditional parent-directory sync.
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        assertTrue(repository.restoreRecoveryRequired())
        assertEquals(81.5, repository.getProfile().referenceWeightKg, 0.0)
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 99.0))
        }

        LedgerBackupManager.recoverInterruptedRestore(context)
        assertFalse(repository.restoreRecoveryRequired())
        repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 82.0))
        assertEquals(82.0, repository.getProfile().referenceWeightKg, 0.0)
    }

    @Test
    fun failedPrepareCleanupWithoutParentSyncPoisonsEvenWhenJournalNameIsGone() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 61)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 62)
        val repository = FitnessRepository(context)

        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.PREPARE_CLEANUP_PARENT_SYNC_FAILED)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        assertFalse(File(context.filesDir, "meal_photos.restore_stage_v1").exists())
        assertEquals(96.0, repository.getProfile().referenceWeightKg, 0.0)
        assertTrue(repository.restoreRecoveryRequired())
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 99.0))
        }

        LedgerBackupManager.recoverInterruptedRestore(context)
        assertFalse(repository.restoreRecoveryRequired())
        repository.updateProfile(repository.getProfile().copy(referenceWeightKg = 97.0))
        assertEquals(97.0, repository.getProfile().referenceWeightKg, 0.0)
    }

    @Test
    fun clearingCameraJournalIsForwardOnlyAndNeverInvokesRollback() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 58)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 59)
        val repository = FitnessRepository(context)

        LedgerBackupManager(context).use { manager ->
            val rollbackAttemptsBefore = manager.rollbackDatabaseAttemptCountForTest()
            assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_CLEARING_CAMERA)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
            assertEquals(rollbackAttemptsBefore, manager.rollbackDatabaseAttemptCountForTest())
        }

        val journal = File(context.noBackupFilesDir, "ledger_restore_transaction_v1/journal.json")
        assertEquals("CLEARING_CAMERA", JSONObject(journal.readText()).getString("phase"))
        assertEquals(81.5, repository.getProfile().referenceWeightKg, 0.0)
        assertTrue(repository.restoreRecoveryRequired())
        assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
            repository.saveBarbellBarWeight(18.0)
        }

        LedgerBackupManager.recoverInterruptedRestore(context)
        assertFalse(repository.restoreRecoveryRequired())
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        assertEquals(81.5, repository.getProfile().referenceWeightKg, 0.0)
    }

    @Test
    fun rejectedCameraStateClearFailsRestoreAndRollsBackWithoutDeletingReferencedCache() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 6)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 96.0, photoTail = 9)
        val cameraCache = File(context.cacheDir, "meal_photos").apply { mkdirs() }
        val pendingFile = File(cameraCache, "meal_pending.jpg").apply { writeBytes(jpeg(44)) }
        val camera = context.getSharedPreferences("fitness_camera_state", Context.MODE_PRIVATE)
        assertTrue(camera.edit()
            .putString("pending_camera_uri", "content://camera/meal_pending.jpg")
            .putLong("pending_camera_created_at", 1234L)
            .commit())

        assertThrows(IllegalStateException::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.CAMERA_STATE_CLEAR_COMMIT_REJECTED)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }

        assertEquals(96.0, currentWeight(), 0.0)
        assertEquals("content://camera/meal_pending.jpg", camera.getString("pending_camera_uri", null))
        assertEquals(1234L, camera.getLong("pending_camera_created_at", -1L))
        assertTrue(pendingFile.isFile)
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun restoreFsyncsDirectoriesAndInjectedDirectorySyncFailureLeavesOldLedgerUntouched() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 11)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 98.0, photoTail = 12)

        LedgerBackupManager(context).use { manager ->
            val before = manager.directorySyncCountForTest()
            manager.failNextRestoreForTest(RestoreFailurePoint.DIRECTORY_SYNC_FAILED)
            assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
            assertTrue(manager.directorySyncCountForTest() > before)
        }
        assertEquals(98.0, currentWeight(), 0.0)
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())

        LedgerBackupManager(context).use { manager ->
            val before = manager.directorySyncCountForTest()
            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            assertTrue(manager.directorySyncCountForTest() > before)
        }
        assertEquals(81.5, currentWeight(), 0.0)
    }

    @Test
    fun repositoryStartupRecoversPersistedJournalAfterSimulatedProcessDeath() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 2)
        val sourcePackage = exportPackage()
        clearAll()
        seedRepresentativeLedger(referenceWeight = 97.0, photoTail = 8)
        val targetCounts = tableCounts()

        assertThrows(SimulatedRestoreProcessDeath::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_DATABASE_COMMITTED_PROCESS_DEATH)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }
        assertTrue(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())

        // Construction is the app-start recovery gate; it must restore the old
        // generation before exposing any repository reads.
        val repository = FitnessRepository(context)
        assertEquals(97.0, repository.getProfile().referenceWeightKg, 0.0)
        assertEquals(targetCounts, tableCounts())
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    @Test
    fun unknownRestoreJournalVersionNeverRunsV1ForwardCleanup() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 64)
        val sourcePackage = exportPackage()
        val scenarios = listOf(
            RestoreFailurePoint.AFTER_CLEARING_CAMERA to "CLEARING_CAMERA",
            RestoreFailurePoint.AFTER_COMMITTED_PROCESS_DEATH to "COMMITTED",
        )

        scenarios.forEachIndexed { index, (failurePoint, expectedPhase) ->
            clearAll()
            seedRepresentativeLedger(referenceWeight = 96.0 + index, photoTail = 65 + index)
            when (failurePoint) {
                RestoreFailurePoint.AFTER_CLEARING_CAMERA -> {
                    assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
                        LedgerBackupManager(context).use { manager ->
                            manager.failNextRestoreForTest(failurePoint)
                            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
                        }
                    }
                }
                else -> {
                    assertThrows(SimulatedRestoreProcessDeath::class.java) {
                        LedgerBackupManager(context).use { manager ->
                            manager.failNextRestoreForTest(failurePoint)
                            manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
                        }
                    }
                }
            }

            val transaction = File(context.noBackupFilesDir, "ledger_restore_transaction_v1")
            val journal = File(transaction, "journal.json")
            val originalJournal = JSONObject(journal.readText())
            assertEquals(expectedPhase, originalJournal.getString("phase"))
            journal.writeText(originalJournal.apply { put("version", 2) }.toString())
            val rollbackPhotos = File(context.filesDir, "meal_photos.restore_rollback_v1")
            assertTrue(rollbackPhotos.isDirectory)
            val cameraCache = File(context.cacheDir, "meal_photos").apply { mkdirs() }
            val sentinel = File(cameraCache, "unknown-journal-sentinel.jpg").apply { writeBytes(jpeg(70 + index)) }
            val camera = context.getSharedPreferences("fitness_camera_state", Context.MODE_PRIVATE)
            assertTrue(camera.edit().putString("pending_camera_uri", "content://camera/sentinel-$index").commit())

            assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
                LedgerBackupManager.recoverInterruptedRestore(context)
            }

            assertTrue(transaction.isDirectory)
            assertTrue(journal.isFile)
            assertTrue(rollbackPhotos.isDirectory)
            assertTrue(sentinel.isFile)
            assertEquals("content://camera/sentinel-$index", camera.getString("pending_camera_uri", null))
            val repository = FitnessRepository(context)
            assertTrue(repository.restoreRecoveryRequired())
            assertThrows(LedgerRestoreRecoveryRequiredException::class.java) {
                repository.markProfileConfigured()
            }

            // Restore the supported test fixture version so teardown can finish
            // the genuine v1 transaction and prove the lock is recoverable.
            journal.writeText(originalJournal.apply { put("version", 1) }.toString())
            LedgerBackupManager.recoverInterruptedRestore(context)
            assertFalse(repository.restoreRecoveryRequired())
            assertFalse(transaction.exists())
        }
    }

    @Test
    fun everyUncommittedJournalPhaseRollsBackAndCommittedPhaseFinishesForwardOnStartup() {
        seedRepresentativeLedger(referenceWeight = 81.5, photoTail = 2)
        val sourcePackage = exportPackage()
        val rollbackPhases = listOf(
            RestoreFailurePoint.AFTER_JOURNAL_PREPARED_PROCESS_DEATH,
            RestoreFailurePoint.AFTER_PHOTOS_SWAPPED_PROCESS_DEATH,
            RestoreFailurePoint.AFTER_DATABASE_COMMITTED_PROCESS_DEATH,
            RestoreFailurePoint.AFTER_PREFERENCES_COMMITTED_PROCESS_DEATH,
        )
        rollbackPhases.forEachIndexed { index, point ->
            clearAll()
            seedRepresentativeLedger(referenceWeight = 98.0, photoTail = 20 + index)
            assertThrows(SimulatedRestoreProcessDeath::class.java) {
                LedgerBackupManager(context).use { manager ->
                    manager.failNextRestoreForTest(point)
                    manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
                }
            }
            FitnessRepository(context)
            assertEquals(98.0, currentWeight(), 0.0)
            assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
        }

        clearAll()
        seedRepresentativeLedger(referenceWeight = 99.0, photoTail = 30)
        assertThrows(SimulatedRestoreProcessDeath::class.java) {
            LedgerBackupManager(context).use { manager ->
                manager.failNextRestoreForTest(RestoreFailurePoint.AFTER_COMMITTED_PROCESS_DEATH)
                manager.importEncrypted(ByteArrayInputStream(sourcePackage), passphrase)
            }
        }
        val journal = File(context.noBackupFilesDir, "ledger_restore_transaction_v1/journal.json")
        journal.writeText(JSONObject(journal.readText()).apply { remove("cameraStateCleared") }.toString())
        val legacyCameraCache = File(context.cacheDir, "meal_photos").apply { mkdirs() }
        File(legacyCameraCache, "meal_legacy-pending.jpg").writeBytes(jpeg(31))
        context.getSharedPreferences("fitness_camera_state", Context.MODE_PRIVATE).edit()
            .putString("pending_camera_uri", "content://camera/meal_legacy-pending.jpg")
            .commit()
        FitnessRepository(context)
        assertEquals(81.5, currentWeight(), 0.0)
        assertTrue(context.getSharedPreferences("fitness_camera_state", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse(legacyCameraCache.exists())
        assertFalse(File(context.noBackupFilesDir, "ledger_restore_transaction_v1").exists())
    }

    private fun seedRepresentativeLedger(referenceWeight: Double, photoTail: Int) {
        FitnessDatabase(context).use { database ->
            database.updateProfile(database.getProfile().copy(referenceWeightKg = referenceWeight, carbFactor = 2.8))
            database.saveBodyMeasurement(
                BodyMeasurement(date = LocalDate.of(2026, 8, 31), weightKg = referenceWeight, waistCm = 86.0),
                formCommitId = "body-form-$photoTail",
            )
            database.commitDraft(manualDraft(), LocalDate.of(2026, 8, 31))
            val photoUri = PhotoStorage.persist(context, jpeg(photoTail)).toString()
            database.saveDraft(manualDraft().copy(
                id = "photo-draft-$photoTail",
                commitId = "photo-commit-$photoTail",
                photoUri = photoUri,
                analysisMode = AnalysisMode.ON_DEVICE_AI,
            ))
            val exercise = database.addCustomExercise("备份动作", "背", TrackingType.WEIGHT_REPS, true)
            val session = database.startWorkout("备份训练")
            database.addWorkoutSet(WorkoutSet(
                sessionId = session.id,
                exerciseId = exercise.id,
                setOrder = 1,
                loadGrams = 80_000,
                reps = 5,
                commitId = "backup-set-$photoTail",
            ))
            database.completeWorkout(session.id)
        }
        context.getSharedPreferences("fitness_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("profile_configured", true)
            .putString("analysis_endpoint", "https://example.invalid/analyse")
            .putString("analysis_transport", "VISION_API")
            .putString("analysis_model", "synthetic-vision-model")
            .putString("analysis_access_token_encrypted", "device-bound-secret")
            .commit()
        context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE).edit()
            .putString(
                "workout_planner_envelope_v1",
                """{"version":1,"editorDraft":null,"activePlan":null,"templates":[],"barbellBarWeightKg":15.0,"clearedSessionId":null}""",
            ).commit()
    }

    private fun seedLargePlannerEnvelope(): String {
        val store = WorkoutPlannerStore(context)
        val exerciseId = FitnessDatabase(context).use { it.listExercises().first().id }
        val items = List(500) { index ->
            WorkoutPlanItem(
                id = "large-item-$index", plannedCommitId = "large-commit-$index",
                exerciseId = exerciseId, weightKg = 60.0, reps = 5, durationSeconds = 0,
                isWarmup = false, rpe = null, rir = null, note = "x".repeat(500),
                supersetId = null, autoRest = true, restSeconds = 120,
            )
        }
        repeat(9) { index ->
            store.saveTemplate(WorkoutTemplate("large-template-$index", "大模板 $index", items, 1_000L, 1_000L + index))
        }
        return requireNotNull(context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE)
            .getString("workout_planner_envelope_v1", null)).also { assertTrue(it.length > 2_000_000) }
    }

    private fun seedCompletedWorkouts(count: Int, titlePrefix: String) {
        FitnessDatabase(context).use { database ->
            val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
            repeat(count) { index ->
                val session = database.startWorkout("$titlePrefix-${index + 1}")
                database.addWorkoutSet(
                    WorkoutSet(
                        sessionId = session.id,
                        exerciseId = exercise.id,
                        setOrder = 1,
                        loadGrams = 60_000L + index * 1_000L,
                        reps = 5,
                        commitId = "history-cache-$titlePrefix-$index",
                    ),
                )
                database.completeWorkout(session.id)
            }
        }
    }

    private fun exportPackage(): ByteArray = ByteArrayOutputStream().also { output ->
        LedgerBackupManager(context).use { it.exportEncrypted(output, passphrase) }
    }.toByteArray()

    private fun rewriteAuthenticatedPayload(
        container: ByteArray,
        schemaOverride: Int? = null,
        mutation: (JSONObject) -> Unit,
    ): ByteArray {
        val headerSize = 48
        val header = container.copyOfRange(0, headerSize)
        val iterations = ByteBuffer.wrap(header, 16, 4).int
        val salt = header.copyOfRange(20, 36)
        val nonce = header.copyOfRange(36, 48)
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        try {
            val decryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
            val root = JSONObject(decryptor.doFinal(container.copyOfRange(headerSize, container.size)).toString(Charsets.UTF_8))
            mutation(root)
            if(schemaOverride!=null) ByteBuffer.wrap(header,12,4).putInt(schemaOverride)
            val encryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
            return header + encryptor.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }
    }

    private fun rewriteAuthenticatedFile(
        container: ByteArray,
        path: String,
        mutation: (ByteArray) -> ByteArray,
    ): ByteArray = rewriteAuthenticatedPayload(container) { root ->
        val files = root.getJSONObject("files")
        val replacement = mutation(Base64.decode(files.getString(path), Base64.NO_WRAP))
        files.put(path, Base64.encodeToString(replacement, Base64.NO_WRAP))
        val entries = root.getJSONObject("manifest").getJSONArray("files")
        val digestRows = mutableListOf<Triple<String, Long, String>>()
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("path") == path) {
                entry.put("bytes", replacement.size.toLong())
                entry.put("sha256", testSha256(replacement))
            }
            digestRows += Triple(entry.getString("path"), entry.getLong("bytes"), entry.getString("sha256"))
        }
        val overallBytes = digestRows.sortedBy { it.first }.joinToString("") { (entryPath, bytes, hash) ->
            "$entryPath\u0000$bytes\u0000$hash\n"
        }.toByteArray(Charsets.UTF_8)
        root.getJSONObject("manifest").put("overallContentSha256", testSha256(overallBytes))
    }

    private fun rewriteStringPreference(
        container: ByteArray,
        path: String,
        key: String,
        value: String,
    ): ByteArray = rewriteAuthenticatedFile(container, path) { bytes ->
        JSONObject(bytes.toString(Charsets.UTF_8)).apply {
            getJSONObject("values").put(
                key,
                JSONObject().apply { put("type", "string"); put("value", value) },
            )
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun rewriteLegacyFiveByFivePreferences(container: ByteArray, exerciseId: Long): ByteArray =
        rewriteAuthenticatedFile(container, "preferences/fitness_settings.json") { bytes ->
            JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                getJSONObject("values").apply {
                    put(
                        "pending_5x5_exercise_id",
                        JSONObject().apply { put("type", "long"); put("value", exerciseId.toString()) },
                    )
                    put(
                        "pending_5x5_load_grams",
                        JSONObject().apply { put("type", "long"); put("value", "0") },
                    )
                    put(
                        "pending_5x5_commit_id",
                        JSONObject().apply { put("type", "string"); put("value", "legacy-duration-5x5") },
                    )
                }
            }.toString().toByteArray(Charsets.UTF_8)
        }

    private fun rewritePlannerEnvelope(container: ByteArray, envelope: String): ByteArray =
        rewriteStringPreference(
            container,
            "preferences/workout_planner.json",
            "workout_planner_envelope_v1",
            envelope,
        )

    private fun pendingSingleSetJson(
        sessionId: Long,
        exerciseId: Long,
        weightKg: Double,
        reps: Int,
        durationSeconds: Int = 0,
    ): String = JSONObject().apply {
        put("version", 1)
        put("sessionId", sessionId)
        put("exerciseId", exerciseId)
        put("submittedAtMillis", 1_000L)
        put("weightKg", weightKg)
        put("reps", reps)
        put("durationSeconds", durationSeconds)
        put("completed", true)
        put("isWarmup", false)
        put("rpe", JSONObject.NULL)
        put("rir", JSONObject.NULL)
        put("note", "")
        put("supersetId", JSONObject.NULL)
        put("restSecondsAfter", JSONObject.NULL)
        put("commitId", "restored-single-set")
    }.toString()

    private fun pendingFiveByFiveJson(sessionId: Long, exerciseId: Long): String = JSONObject().apply {
        put("version", 2)
        put("sessionId", sessionId)
        put("exerciseId", exerciseId)
        put("loadGrams", 80_000L)
        put("batchId", "restored-five-by-five")
        put("submittedAtMillis", 1_000L)
    }.toString()

    private fun plannerEnvelopeJson(
        editor: JSONObject? = null,
        activePlan: JSONObject? = null,
    ): String = JSONObject().apply {
        put("version", 1)
        put("editorDraft", editor ?: JSONObject.NULL)
        put("activePlan", activePlan ?: JSONObject.NULL)
        put("templates", JSONArray())
        put("barbellBarWeightKg", 20.0)
        put("clearedSessionId", JSONObject.NULL)
    }.toString()

    private fun plannerEditorJson(sessionId: Long, exerciseId: Long): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("selectedExerciseId", exerciseId)
        put("weightText", "80")
        put("repsText", "5")
        put("durationText", "0")
        put("rpeText", "")
        put("rirText", "")
        put("noteText", "")
        put("isWarmup", false)
        put("isFailed", false)
        put("autoRest", true)
        put("restSeconds", 120)
        put("selectedSupersetId", JSONObject.NULL)
        put("commitId", "restored-editor")
        put("revision", 1L)
        put("updatedAtMillis", 1_000L)
    }

    private fun activePlannerJson(sessionId: Long, exerciseId: Long): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("sourceLabel", "恢复测试")
        put("items", JSONArray().put(JSONObject().apply {
            put("id", "restored-plan-item")
            put("plannedCommitId", "restored-plan-commit")
            put("exerciseId", exerciseId)
            put("weightKg", 80.0)
            put("reps", 5)
            put("durationSeconds", 0)
            put("isWarmup", false)
            put("rpe", JSONObject.NULL)
            put("rir", JSONObject.NULL)
            put("note", "")
            put("supersetId", JSONObject.NULL)
            put("autoRest", true)
            put("restSeconds", 120)
        }))
        put("revision", 1L)
        put("updatedAtMillis", 1_000L)
    }

    private fun testSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }

    private fun currentWeight(): Double = FitnessDatabase(context).use { it.getProfile().referenceWeightKg }

    private fun tableCounts(): Map<String, Int> = FitnessDatabase(context).use { database ->
        BUSINESS_TABLE_NAMES.associateWith { table ->
            database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst(); cursor.getInt(0)
            }
        }
    }

    private fun clearAll() {
        LedgerBackupManager.recoverInterruptedRestore(context)
        context.deleteDatabase("fitness_ledger.db")
        listOf("fitness_settings", "workout_planner", "fitness_camera_state").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, "meal_photos").deleteRecursively()
        File(context.filesDir, "meal_photos.restore_stage_v1").deleteRecursively()
        File(context.filesDir, "meal_photos.restore_rollback_v1").deleteRecursively()
        File(context.noBackupFilesDir, "ledger_restore_transaction_v1").deleteRecursively()
    }

    private fun jpeg(tail: Int) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), tail.toByte(), 0xd9.toByte())

    private fun manualDraft() = MealDraft(
        photoUri = "",
        state = DraftState.READY_TO_CONFIRM,
        items = listOf(FoodDraftItem(
            name = "备份燕麦",
            grams = 100.0,
            gramsMin = 100.0,
            gramsMax = 100.0,
            per100g = Nutrition(380.0, 66.0, 17.0, 7.0),
            sourceName = "包装标签",
            portionBasis = PortionBasis.USER_WEIGHT,
            evidenceTier = EvidenceTier.C,
            userModified = true,
            calorieSource = CalorieSource.LABEL_OR_DATABASE,
        )),
        evidenceTier = EvidenceTier.C,
        evidenceReason = "备份测试",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "手工",
        analysisMode = AnalysisMode.MANUAL,
    )

    private companion object {
        val BUSINESS_TABLE_NAMES = listOf(
            "profile", "body_measurements", "body_measurement_form_commits", "meals", "meal_items",
            "saved_foods", "photo_drafts", "exercises", "workout_sessions", "workout_sets", "pr_events",
            "manual_food_form_conversions", "workout_rewrite_guards",
            "xiaomi_sync", "xiaomi_weights",
        )
    }
}
