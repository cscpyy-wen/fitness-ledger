package com.personal.fitnessledger.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/** Public, password-encrypted, cross-device backup of user-owned ledger data.
 *
 * Android-Keystore-bound analysis tokens, their endpoint and transient camera
 * capture state are deliberately excluded. A successful restore therefore
 * requires the user to configure the optional remote analyser again.
 */
class LedgerBackupManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val databaseDelegate = lazy(LazyThreadSafetyMode.NONE) { FitnessDatabase(appContext) }
    private val database by databaseDelegate

    fun exportEncrypted(output: OutputStream, passphrase: CharArray): LedgerBackupSummary =
        COORDINATOR.withLock {
            settleInterruptedRestoreLocked(appContext)
            validatePassphrase(passphrase)
            val snapshot = captureConsistentSnapshot(database, appContext)
            validateIncomingBusinessPreferences(
                snapshot.preferences.fitness,
                snapshot.preferences.planner,
                incomingWorkoutBusinessContext(snapshot.database),
            )
            val payload = buildPayload(snapshot)
            val encrypted = encrypt(payload.bytes, passphrase, payload.schemaVersion)
            output.write(encrypted)
            output.flush()
            payload.summary.copy(encryptedBytes = encrypted.size.toLong())
        }

    fun importEncrypted(input: InputStream, passphrase: CharArray): LedgerRestoreResult =
        COORDINATOR.withLock {
            settleInterruptedRestoreLocked(appContext)
            validatePassphrase(passphrase)
                val incoming = parseAndValidate(decrypt(readLimited(input), passphrase))

            // Exercise every schema/value/foreign-key trigger before touching
            // photos or preferences. This transaction is intentionally rolled back.
            replaceDatabase(database.writableDatabase, incoming.database, commit = false)

            val rollback = captureConsistentSnapshot(database, appContext, includePhotos = false)
            var forwardCommitDecided = false
            try {
                prepareRestoreJournal(appContext, rollback, incoming)
                maybeFail(RestoreFailurePoint.AFTER_JOURNAL_PREPARED)
                maybeFail(RestoreFailurePoint.AFTER_JOURNAL_PREPARED_PROCESS_DEATH)
                swapInStagedPhotos(appContext)
                writeJournalPhase(appContext, RestorePhase.PHOTOS_SWAPPED)
                maybeFail(RestoreFailurePoint.AFTER_PHOTOS_SWAPPED)
                maybeFail(RestoreFailurePoint.AFTER_PHOTOS_SWAPPED_PROCESS_DEATH)

                replaceDatabase(database.writableDatabase, incoming.database, commit = true)
                FitnessDatabase.invalidateProcessCachesAfterExternalRestore()
                writeJournalPhase(appContext, RestorePhase.DATABASE_COMMITTED)
                maybeFail(RestoreFailurePoint.AFTER_DATABASE_COMMITTED)
                maybeFail(RestoreFailurePoint.AFTER_DATABASE_COMMITTED_PROCESS_DEATH)

                replacePreferences(appContext, incoming.fitnessPreferences, incoming.plannerPreferences)
                writeJournalPhase(appContext, RestorePhase.EXTERNAL_COMMITTED)
                maybeFail(RestoreFailurePoint.AFTER_PREFERENCES_COMMITTED)
                maybeFail(RestoreFailurePoint.AFTER_PREFERENCES_COMMITTED_PROCESS_DEATH)

                // CLEARING_CAMERA is the durable forward-completion decision.
                // Camera state itself lives in a different SharedPreferences
                // file, so a process death on either side of commit() can safely
                // retry the idempotent clear instead of guessing rollback state.
                check(clearCameraStateForRestore(appContext)) { "无法持久化相机恢复状态" }
                writeJournalPhase(appContext, RestorePhase.CLEARING_CAMERA)
                forwardCommitDecided = true
                maybeFail(RestoreFailurePoint.AFTER_CLEARING_CAMERA)
                writeJournalPhase(appContext, RestorePhase.COMMITTED)
                maybeFail(RestoreFailurePoint.AFTER_COMMITTED_PROCESS_DEATH)
                finishCommittedRestore(appContext)
            } catch (failure: Throwable) {
                if (failure is SimulatedRestoreProcessDeath) throw failure
                if (forwardCommitDecided) {
                    restoreProcessPoisoned = true
                    throw restoreRecoveryRequired(failure)
                }
                val phase = runCatching { readRestorePhaseOrNull(appContext) }
                    .getOrElse { phaseFailure ->
                        restoreProcessPoisoned = true
                        throw restoreRecoveryRequired(failure, phaseFailure)
                    }
                val rollbackFailure = when {
                    phase == RestorePhase.CLEARING_CAMERA || phase == RestorePhase.COMMITTED -> {
                        // The commit decision is already visible. Leave its journal
                        // for startup to finish deletion-only cleanup; rolling back
                        // now would cross a durable generation boundary.
                        null
                    }
                    phase == null && !File(transactionDirectory(appContext), JOURNAL_FILE).isFile -> {
                        // Failure before PREPARED became visible cannot have
                        // attempted a database commit. Reconcile reserved staging
                        // names instead of requiring snapshots that may not exist.
                        runCatching { recoverInterruptedRestoreLocked(appContext) }.exceptionOrNull()
                    }
                    phase == null -> LedgerBackupException("恢复事务日志损坏，账本已锁定")
                    else -> runCatching { rollbackPreparedRestore(appContext) }.exceptionOrNull()
                }
                if (rollbackFailure != null) {
                    restoreProcessPoisoned = true
                    throw restoreRecoveryRequired(failure, rollbackFailure)
                }
                if (transactionDirectory(appContext).exists()) {
                    restoreProcessPoisoned = true
                    throw restoreRecoveryRequired(failure)
                }
                throw failure
            }
            LedgerRestoreResult(
                summary = incoming.summary,
                restoredRowCounts = incoming.database.tables.associate { it.name to it.rows.size },
                restoredPhotoCount = incoming.photos.size,
                requiresAnalysisReconfiguration = true,
            )
        }

    override fun close() {
        if (databaseDelegate.isInitialized()) database.close()
    }

    internal fun failNextRestoreForTest(point: RestoreFailurePoint?) {
        nextFailurePoint = point
    }

    internal fun failNextRollbackDatabaseCommitForTest() {
        rejectNextRollbackDatabaseCommitForTest = true
    }

    internal fun directorySyncCountForTest(): Int = directorySyncCallsForTest

    internal fun rollbackDatabaseAttemptCountForTest(): Int = rollbackDatabaseAttemptsForTest

    private fun maybeFail(point: RestoreFailurePoint) {
        if (consumeFailurePoint(point)) {
            if (point.name.endsWith("_PROCESS_DEATH")) {
                throw SimulatedRestoreProcessDeath()
            }
            error("Injected restore failure at $point")
        }
    }

    companion object {
        /** Called before FitnessRepository opens its long-lived database handle. */
        internal fun recoverInterruptedRestore(context: Context) = COORDINATOR.withLock {
            settleInterruptedRestoreLocked(context.applicationContext)
        }

        internal fun restoreRecoveryRequired(context: Context): Boolean = COORDINATOR.withLock {
            // Repository construction temporarily asserts the fail-closed marker.
            // Xiaomi sync may construct a second repository during UI startup;
            // observe the settled result, never that transient in-progress marker.
            context.applicationContext.let { appContext ->
                restoreProcessPoisoned || transactionDirectory(appContext).exists() ||
                    photoStageDirectory(appContext).exists() || photoRollbackDirectory(appContext).exists()
            }
        }

        internal fun <T> withLedgerReadyAccess(context: Context, block: () -> T): T =
            COORDINATOR.withLock {
                val appContext = context.applicationContext
                if (restoreProcessPoisoned || transactionDirectory(appContext).exists() ||
                    photoStageDirectory(appContext).exists() || photoRollbackDirectory(appContext).exists()
                ) {
                    throw LedgerRestoreRecoveryRequiredException()
                }
                block()
            }
    }
}

data class LedgerBackupSummary(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val createdAt: String,
    val fileEntries: List<LedgerBackupFileEntry>,
    val overallContentSha256: String,
    val encryptedBytes: Long = 0,
)

data class LedgerBackupFileEntry(val path: String, val bytes: Long, val sha256: String)

data class LedgerRestoreResult(
    val summary: LedgerBackupSummary,
    val restoredRowCounts: Map<String, Int>,
    val restoredPhotoCount: Int,
    val requiresAnalysisReconfiguration: Boolean,
)

open class LedgerBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
class LedgerBackupAuthenticationException(cause: Throwable? = null) :
    LedgerBackupException("备份口令错误或文件已被篡改", cause)
class LedgerBackupCompatibilityException(message: String) : LedgerBackupException(message)
class LedgerBackupIntegrityException(message: String) : LedgerBackupException(message)
class LedgerRestoreRecoveryRequiredException(cause: Throwable? = null) :
    LedgerBackupException("恢复事务尚未安全收敛，账本已锁定；请完全关闭并重新打开 App 后重试", cause)

internal enum class RestoreFailurePoint {
    AFTER_JOURNAL_PREPARED,
    AFTER_JOURNAL_PREPARED_PROCESS_DEATH,
    AFTER_PHOTOS_SWAPPED,
    AFTER_PHOTOS_SWAPPED_PROCESS_DEATH,
    AFTER_DATABASE_COMMITTED,
    AFTER_DATABASE_COMMITTED_PROCESS_DEATH,
    AFTER_PREFERENCES_COMMITTED,
    AFTER_PREFERENCES_COMMITTED_PROCESS_DEATH,
    AFTER_COMMITTED_PROCESS_DEATH,
    AFTER_CLEARING_CAMERA,
    CAMERA_STATE_CLEAR_COMMIT_REJECTED,
    DIRECTORY_SYNC_FAILED,
    PREPARE_CLEANUP_PARENT_SYNC_FAILED,
    FINAL_TRANSACTION_PARENT_SYNC_FAILED,
}

internal class SimulatedRestoreProcessDeath : RuntimeException("simulated restore process death")

private enum class RestorePhase {
    PREPARED,
    PHOTOS_SWAPPED,
    DATABASE_COMMITTED,
    EXTERNAL_COMMITTED,
    CLEARING_CAMERA,
    COMMITTED,
}

private data class PreferencesSnapshot(
    val fitness: Map<String, Any?>,
    val planner: Map<String, Any?>,
)

private data class CompleteSnapshot(
    val database: DatabaseSnapshot,
    val preferences: PreferencesSnapshot,
    val photos: Map<String, ByteArray>,
)

private data class DatabaseSnapshot(
    val schemaVersion: Int,
    val tables: List<TableSnapshot>,
    val sequences: Map<String, Long>,
)

private data class TableSnapshot(
    val name: String,
    val columns: List<String>,
    val rows: List<List<DatabaseValue>>,
)

private sealed interface DatabaseValue {
    data object Null : DatabaseValue
    data class Integer(val value: Long) : DatabaseValue
    data class Real(val value: Double) : DatabaseValue
    data class Text(val value: String) : DatabaseValue
    data class Blob(val value: ByteArray) : DatabaseValue
}

private data class ParsedBackup(
    val database: DatabaseSnapshot,
    val fitnessPreferences: Map<String, Any?>,
    val plannerPreferences: Map<String, Any?>,
    val photos: Map<String, ByteArray>,
    val summary: LedgerBackupSummary,
)

private data class BuiltPayload(
    val bytes: ByteArray,
    val schemaVersion: Int,
    val summary: LedgerBackupSummary,
)

private val COORDINATOR = ReentrantLock(true)
private var nextFailurePoint: RestoreFailurePoint? = null
private var directorySyncCallsForTest = 0
private var rejectNextRollbackDatabaseCommitForTest = false
private var rollbackDatabaseAttemptsForTest = 0
@Volatile private var restoreProcessPoisoned = false

private fun consumeFailurePoint(point: RestoreFailurePoint): Boolean {
    if (nextFailurePoint != point) return false
    nextFailurePoint = null
    return true
}

private fun consumeRollbackDatabaseCommitFailure(): Boolean {
    if (!rejectNextRollbackDatabaseCommitForTest) return false
    rejectNextRollbackDatabaseCommitForTest = false
    return true
}

private fun restoreRecoveryRequired(
    originalFailure: Throwable,
    recoveryFailure: Throwable? = null,
): LedgerRestoreRecoveryRequiredException = LedgerRestoreRecoveryRequiredException(
    recoveryFailure ?: originalFailure,
).also { blocked ->
    if (recoveryFailure != null && recoveryFailure !== originalFailure) {
        blocked.addSuppressed(originalFailure)
    }
}

private const val DATABASE_SCHEMA_VERSION = 11
private const val BACKUP_FORMAT_VERSION = 1
private const val PBKDF2_ITERATIONS = 310_000
private const val MAX_BACKUP_BYTES = 256 * 1024 * 1024
private const val MAX_ORDINARY_PREFERENCE_CHARACTERS = 2_000_000
private const val PLANNER_ENVELOPE_KEY = "workout_planner_envelope_v1"
// Existing installations may legitimately hold 100 templates with 500 bounded
// items each. Their one atomic preference can exceed the ordinary string limit.
// The encrypted container already bounds total input bytes; the planner parser
// additionally bounds template/item counts and every user-editable field.
private const val MAX_PLANNER_PREFERENCE_CHARACTERS = MAX_BACKUP_BYTES
private const val FITNESS_PREFERENCES = "fitness_settings"
private const val PLANNER_PREFERENCES = "workout_planner"
private const val CAMERA_PREFERENCES = "fitness_camera_state"
private const val PHOTO_DIRECTORY = "meal_photos"
private const val TRANSACTION_DIRECTORY = "ledger_restore_transaction_v1"
private const val JOURNAL_FILE = "journal.json"
private const val ROLLBACK_DATABASE_FILE = "rollback_database.json"
private const val ROLLBACK_PREFERENCES_FILE = "rollback_preferences.json"
private const val INCOMING_FILE = "incoming.json"
private const val PHOTO_STAGE_DIRECTORY = "meal_photos.restore_stage_v1"
private const val PHOTO_ROLLBACK_DIRECTORY = "meal_photos.restore_rollback_v1"
private val MAGIC = "FLBKUP01".toByteArray(Charsets.US_ASCII)
private val SECRET_SETTING_KEYS = setOf(
    "analysis_endpoint",
    "analysis_transport",
    "analysis_model",
    "analysis_access_token",
    "analysis_access_token_encrypted",
    KEY_ANALYSIS_PROFILES,
)
private val REQUIRED_EXCLUSIONS = setOf(
    "fitness_settings.analysis_endpoint",
    "fitness_settings.analysis_access_token",
    "fitness_settings.analysis_access_token_encrypted",
    "AndroidKeyStore key material",
    "fitness_camera_state and camera cache",
)
private const val XIAOMI_CREDENTIAL_EXCLUSION = "xiaomi_cloud_credentials and background connection"
private const val ANALYSIS_PROFILES_EXCLUSION = "fitness_settings.analysis_profiles_encrypted_v1"

private val BUSINESS_TABLES = listOf(
    "profile",
    "body_measurements",
    "body_measurement_form_commits",
    "meals",
    "meal_items",
    "saved_foods",
    "photo_drafts",
    "exercises",
    "workout_sessions",
    "workout_sets",
    "pr_events",
    "manual_food_form_conversions",
    "workout_rewrite_guards",
    "xiaomi_sync",
    "xiaomi_weights",
)
private val AUTOINCREMENT_TABLES = setOf(
    "body_measurements",
    "meals",
    "meal_items",
    "saved_foods",
    "exercises",
    "workout_sessions",
    "workout_sets",
    "pr_events",
    "xiaomi_weights",
)
// Keep restored identifiers comfortably below SQLite's terminal ROWID. This
// also makes an authenticated-but-malicious sequence unable to turn the next
// ordinary insert into SQLITE_FULL. 2^53-1 is far beyond any viable local
// ledger while remaining exactly representable by common backup tooling.
private const val MAX_SAFE_SQLITE_ROW_ID = 9_007_199_254_740_991L

private fun validatePassphrase(passphrase: CharArray) {
    require(passphrase.size >= 8) { "备份口令至少需要 8 个字符" }
}

private fun captureConsistentSnapshot(
    database: FitnessDatabase,
    context: Context,
    includePhotos: Boolean = true,
): CompleteSnapshot {
    repeat(3) {
        val before = captureBusinessPreferences(context)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val databaseSnapshot = captureDatabase(db)
            val photos = if (includePhotos) capturePhotos(context, databaseSnapshot) else emptyMap()
            val after = captureBusinessPreferences(context)
            if (before == after) {
                db.setTransactionSuccessful()
                return CompleteSnapshot(databaseSnapshot, after, photos)
            }
        } finally {
            db.endTransaction()
        }
    }
    throw LedgerBackupException("备份期间数据持续变化，请停止编辑后重试")
}

private fun captureBusinessPreferences(context: Context): PreferencesSnapshot = PreferencesSnapshot(
    fitness = context.getSharedPreferences(FITNESS_PREFERENCES, Context.MODE_PRIVATE).all
        .filterKeys { it !in SECRET_SETTING_KEYS }
        .mapValues { clonePreferenceValue(it.value) },
    planner = context.getSharedPreferences(PLANNER_PREFERENCES, Context.MODE_PRIVATE).all
        .mapValues { clonePreferenceValue(it.value) },
)

private fun clonePreferenceValue(value: Any?): Any? = when (value) {
    null, is String, is Boolean, is Int, is Long, is Float -> value
    is Set<*> -> value.map {
        require(it is String) { "SharedPreferences 包含未知集合类型" }
        it
    }.toSortedSet()
    else -> throw LedgerBackupException("SharedPreferences 包含不支持的类型 ${value::class.java.name}")
}

private fun captureDatabase(db: SQLiteDatabase): DatabaseSnapshot {
    if (db.version != DATABASE_SCHEMA_VERSION) {
        throw LedgerBackupCompatibilityException("仅支持数据库 v$DATABASE_SCHEMA_VERSION，当前为 v${db.version}")
    }
    val actual = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    if (actual.toSet() != BUSINESS_TABLES.toSet()) {
        throw LedgerBackupCompatibilityException("数据库业务表集合与备份格式不一致")
    }
    val tables = BUSINESS_TABLES.map { table ->
        val columns = db.rawQuery("PRAGMA table_info(${quoted(table)})", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val rows = db.rawQuery(
            "SELECT ${columns.joinToString(",") { quoted(it) }} FROM ${quoted(table)} ORDER BY rowid",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(columns.size) { cursor.databaseValue(it) })
            }
        }
        TableSnapshot(table, columns, rows)
    }
    val sequences = db.rawQuery("SELECT name,seq FROM sqlite_sequence ORDER BY name", null).use { cursor ->
        buildMap { while (cursor.moveToNext()) if (cursor.getString(0) in BUSINESS_TABLES) put(cursor.getString(0), cursor.getLong(1)) }
    }
    return DatabaseSnapshot(db.version, tables, sequences)
}

private fun Cursor.databaseValue(index: Int): DatabaseValue = when (getType(index)) {
    Cursor.FIELD_TYPE_NULL -> DatabaseValue.Null
    Cursor.FIELD_TYPE_INTEGER -> DatabaseValue.Integer(getLong(index))
    Cursor.FIELD_TYPE_FLOAT -> DatabaseValue.Real(getDouble(index))
    Cursor.FIELD_TYPE_STRING -> DatabaseValue.Text(getString(index))
    Cursor.FIELD_TYPE_BLOB -> DatabaseValue.Blob(getBlob(index))
    else -> throw LedgerBackupIntegrityException("SQLite 返回了未知字段类型")
}

private fun capturePhotos(context: Context, snapshot: DatabaseSnapshot): Map<String, ByteArray> {
    val drafts = snapshot.tables.single { it.name == "photo_drafts" }
    val uriIndex = drafts.columns.indexOf("photo_uri")
    val payloadIndex = drafts.columns.indexOf("payload_json")
    return buildMap {
        drafts.rows.forEach { row ->
            val uriText = (row[uriIndex] as? DatabaseValue.Text)?.value.orEmpty()
            if (uriText.isBlank()) return@forEach
            val payloadPhoto = runCatching {
                JSONObject((row[payloadIndex] as DatabaseValue.Text).value).getString("photoUri")
            }.getOrElse { throw LedgerBackupIntegrityException("照片草稿 JSON 已损坏") }
            if (payloadPhoto != uriText) throw LedgerBackupIntegrityException("照片草稿 URI 不一致")
            val uri = Uri.parse(uriText)
            val bytes = PhotoStorage.readPersistedJpeg(context, uri)
                ?: throw LedgerBackupIntegrityException("照片草稿引用的持久照片不存在")
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.matches(Regex("meal_[A-Za-z0-9-]+\\.jpg")) }
                ?: throw LedgerBackupIntegrityException("照片草稿路径不受支持")
            val previous = put(name, bytes)
            if (previous != null && !previous.contentEquals(bytes)) {
                throw LedgerBackupIntegrityException("同名照片内容冲突")
            }
        }
    }
}

private fun buildPayload(snapshot: CompleteSnapshot): BuiltPayload {
    val files = linkedMapOf<String, ByteArray>()
    files["database.json"] = encodeDatabase(snapshot.database).toByteArray(Charsets.UTF_8)
    files["preferences/fitness_settings.json"] = encodePreferences(snapshot.preferences.fitness).toByteArray(Charsets.UTF_8)
    files["preferences/workout_planner.json"] = encodePreferences(snapshot.preferences.planner).toByteArray(Charsets.UTF_8)
    snapshot.photos.toSortedMap().forEach { (name, bytes) -> files["photos/$name"] = bytes }
    val entries = files.map { (path, bytes) -> LedgerBackupFileEntry(path, bytes.size.toLong(), sha256(bytes)) }
    val overall = overallDigest(entries)
    val createdAt = Instant.now().toString()
    val manifest = JSONObject().apply {
        put("format", "fitness-ledger-encrypted-backup")
        put("formatVersion", BACKUP_FORMAT_VERSION)
        put("databaseSchemaVersion", snapshot.database.schemaVersion)
        put("createdAt", createdAt)
        put("packageName", "com.personal.fitnessledger")
        put("overallContentSha256", overall)
        put("requiresAnalysisReconfiguration", true)
        put("excluded", JSONArray((REQUIRED_EXCLUSIONS + XIAOMI_CREDENTIAL_EXCLUSION + ANALYSIS_PROFILES_EXCLUSION).sorted()))
        put("files", JSONArray().apply {
            entries.forEach { entry -> put(JSONObject().apply {
                put("path", entry.path); put("bytes", entry.bytes); put("sha256", entry.sha256)
            }) }
        })
    }
    val root = JSONObject().apply {
        put("manifest", manifest)
        put("files", JSONObject().apply {
            files.forEach { (path, bytes) -> put(path, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
        })
    }.toString().toByteArray(Charsets.UTF_8)
    if (root.size > MAX_BACKUP_BYTES) throw LedgerBackupException("备份内容超过 256 MiB 安全上限")
    return BuiltPayload(
        root,
        snapshot.database.schemaVersion,
        LedgerBackupSummary(BACKUP_FORMAT_VERSION, snapshot.database.schemaVersion, createdAt, entries, overall),
    )
}

private data class DecryptedPayload(val bytes: ByteArray, val headerSchemaVersion: Int)

private fun encrypt(plaintext: ByteArray, passphrase: CharArray, schemaVersion: Int): ByteArray {
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
    val header = ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use { out ->
        out.write(MAGIC); out.writeInt(BACKUP_FORMAT_VERSION); out.writeInt(schemaVersion)
        out.writeInt(PBKDF2_ITERATIONS); out.write(salt); out.write(nonce)
    } }.toByteArray()
    val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    } finally {
        key.fill(0)
    }
}

private fun decrypt(container: ByteArray, passphrase: CharArray): DecryptedPayload {
    try {
        val stream = DataInputStream(ByteArrayInputStream(container))
        val magic = ByteArray(MAGIC.size).also(stream::readFully)
        if (!magic.contentEquals(MAGIC)) throw LedgerBackupCompatibilityException("不是健身账本备份文件")
        val version = stream.readInt()
        if (version != BACKUP_FORMAT_VERSION) throw LedgerBackupCompatibilityException("不支持备份格式 v$version")
        val schema = stream.readInt()
        if (schema > DATABASE_SCHEMA_VERSION) throw LedgerBackupCompatibilityException("备份数据库 v$schema 高于本应用支持的 v$DATABASE_SCHEMA_VERSION")
        if (schema !in 10..DATABASE_SCHEMA_VERSION) throw LedgerBackupCompatibilityException("仅支持数据库 v10–v$DATABASE_SCHEMA_VERSION")
        val iterations = stream.readInt()
        if (iterations !in 100_000..2_000_000) throw LedgerBackupCompatibilityException("备份 KDF 参数不受支持")
        val salt = ByteArray(16).also(stream::readFully)
        val nonce = ByteArray(12).also(stream::readFully)
        val headerSize = MAGIC.size + 4 + 4 + 4 + 16 + 12
        val header = container.copyOfRange(0, headerSize)
        val ciphertext = container.copyOfRange(headerSize, container.size)
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return DecryptedPayload(cipher.doFinal(ciphertext), schema)
        } finally {
            key.fill(0)
        }
    } catch (known: LedgerBackupException) {
        throw known
    } catch (badTag: AEADBadTagException) {
        throw LedgerBackupAuthenticationException(badTag)
    } catch (failure: Throwable) {
        throw LedgerBackupAuthenticationException(failure)
    }
}

private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_BACKUP_BYTES + 1024) throw LedgerBackupIntegrityException("备份文件超过安全上限")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun parseAndValidate(decrypted: DecryptedPayload): ParsedBackup {
    val root = runCatching { JSONObject(decrypted.bytes.toString(Charsets.UTF_8)) }
        .getOrElse { throw LedgerBackupIntegrityException("备份载荷不是有效 JSON") }
    val manifest = root.getJSONObject("manifest")
    if (manifest.getString("format") != "fitness-ledger-encrypted-backup") {
        throw LedgerBackupCompatibilityException("不是受支持的健身账本备份 manifest")
    }
    if (manifest.getString("packageName") != "com.personal.fitnessledger") {
        throw LedgerBackupCompatibilityException("备份包名不匹配")
    }
    if (!manifest.getBoolean("requiresAnalysisReconfiguration")) {
        throw LedgerBackupIntegrityException("备份未声明代理凭据重新配置要求")
    }
    val format = manifest.getInt("formatVersion")
    val schema = manifest.getInt("databaseSchemaVersion")
    if (format != BACKUP_FORMAT_VERSION) throw LedgerBackupCompatibilityException("不支持备份格式 v$format")
    if (schema != decrypted.headerSchemaVersion || schema !in 10..DATABASE_SCHEMA_VERSION) {
        throw LedgerBackupCompatibilityException("备份数据库版本不受支持")
    }
    val exclusions = manifest.getJSONArray("excluded").let { array ->
        buildSet { repeat(array.length()) { add(array.getString(it)) } }
    }
    val expectedExclusions = if (schema >= 11) REQUIRED_EXCLUSIONS + XIAOMI_CREDENTIAL_EXCLUSION else REQUIRED_EXCLUSIONS
    // Backups from before saved service profiles remain importable; the payload
    // policy below rejects profile credentials regardless of the manifest age.
    if (exclusions != expectedExclusions && exclusions != expectedExclusions + ANALYSIS_PROFILES_EXCLUSION) {
        throw LedgerBackupIntegrityException("备份排除策略不匹配")
    }
    val encodedFiles = root.getJSONObject("files")
    val entriesArray = manifest.getJSONArray("files")
    val entries = buildList {
        repeat(entriesArray.length()) { index ->
            val item = entriesArray.getJSONObject(index)
            val bytes = item.getLong("bytes")
            val hash = item.getString("sha256")
            if (bytes !in 0..MAX_BACKUP_BYTES.toLong() || !hash.matches(Regex("[0-9A-F]{64}"))) {
                throw LedgerBackupIntegrityException("manifest 文件元数据非法")
            }
            add(LedgerBackupFileEntry(item.getString("path"), bytes, hash))
        }
    }
    if (entries.map { it.path }.toSet().size != entries.size) throw LedgerBackupIntegrityException("备份包含重复文件")
    val encodedNames = encodedFiles.keys().asSequence().toSet()
    if (encodedNames != entries.map { it.path }.toSet()) throw LedgerBackupIntegrityException("manifest 与文件集合不一致")
    val files = entries.associate { entry ->
        if (!validBackupPath(entry.path)) throw LedgerBackupIntegrityException("备份路径非法")
        val bytes = runCatching { Base64.decode(encodedFiles.getString(entry.path), Base64.NO_WRAP) }
            .getOrElse { throw LedgerBackupIntegrityException("备份文件 Base64 损坏") }
        if (bytes.size.toLong() != entry.bytes || sha256(bytes) != entry.sha256) {
            throw LedgerBackupIntegrityException("${entry.path} 完整性校验失败")
        }
        entry.path to bytes
    }
    val overall = manifest.getString("overallContentSha256")
    if (overallDigest(entries) != overall) throw LedgerBackupIntegrityException("备份整体完整性校验失败")
    val required = setOf("database.json", "preferences/fitness_settings.json", "preferences/workout_planner.json")
    if (!files.keys.containsAll(required)) throw LedgerBackupIntegrityException("备份缺少核心文件")
    val fitness = decodePreferences(files.getValue("preferences/fitness_settings.json").toString(Charsets.UTF_8))
    if (fitness.keys.any { it in SECRET_SETTING_KEYS }) throw LedgerBackupIntegrityException("备份不得包含代理凭据或地址")
    val database = decodeDatabase(files.getValue("database.json").toString(Charsets.UTF_8), expectedSchema = schema)
    val planner = decodePreferences(
        files.getValue("preferences/workout_planner.json").toString(Charsets.UTF_8),
        allowPlannerEnvelope = true,
    )
    validateIncomingBusinessPreferences(
        fitness = fitness,
        planner = planner,
        workout = incomingWorkoutBusinessContext(database),
    )
    val photos = files.filterKeys { it.startsWith("photos/") }.mapKeys { it.key.substringAfter("photos/") }
    validatePhotoSet(database, photos)
    val createdAt = manifest.getString("createdAt")
    runCatching { Instant.parse(createdAt) }.getOrElse { throw LedgerBackupIntegrityException("备份创建时间非法") }
    return ParsedBackup(
        database,
        fitness,
        planner,
        photos,
        LedgerBackupSummary(format, schema, createdAt, entries, overall),
    )
}

private fun validBackupPath(path: String): Boolean =
    path in setOf("database.json", "preferences/fitness_settings.json", "preferences/workout_planner.json") ||
        path.matches(Regex("photos/meal_[A-Za-z0-9-]+\\.jpg"))

private fun validatePhotoSet(database: DatabaseSnapshot, photos: Map<String, ByteArray>) {
    val table = database.tables.single { it.name == "photo_drafts" }
    val uriIndex = table.columns.indexOf("photo_uri")
    val payloadIndex = table.columns.indexOf("payload_json")
    val expected = table.rows.mapNotNull { row ->
        val text = (row[uriIndex] as? DatabaseValue.Text)?.value.orEmpty()
        if (text.isBlank()) return@mapNotNull null
        val uri = Uri.parse(text)
        val name = uri.lastPathSegment?.substringAfterLast('/')
            ?.takeIf { it.matches(Regex("meal_[A-Za-z0-9-]+\\.jpg")) }
            ?: throw LedgerBackupIntegrityException("照片草稿路径不受支持")
        if (uri.scheme != "content" || uri.authority != "com.personal.fitnessledger.fileprovider" ||
            uri.pathSegments != listOf("private_meal_photos", name)
        ) {
            throw LedgerBackupIntegrityException("照片草稿 URI 不属于本应用持久照片目录")
        }
        val payloadText = (row[payloadIndex] as? DatabaseValue.Text)?.value
            ?: throw LedgerBackupIntegrityException("照片草稿 JSON 字段类型异常")
        val payloadPhoto = runCatching { JSONObject(payloadText).getString("photoUri") }
            .getOrElse { throw LedgerBackupIntegrityException("照片草稿 JSON 已损坏") }
        if (payloadPhoto != text) throw LedgerBackupIntegrityException("照片草稿 URI 不一致")
        name
    }.toSet()
    if (expected != photos.keys) throw LedgerBackupIntegrityException("照片文件与照片草稿引用不一致")
    photos.forEach { (name, bytes) ->
        if (!name.matches(Regex("meal_[A-Za-z0-9-]+\\.jpg")) || bytes.size !in 4..(12 * 1024 * 1024) ||
            bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()
        ) throw LedgerBackupIntegrityException("照片 $name 不是受支持的 JPEG")
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02X".format(it) }

private fun overallDigest(entries: List<LedgerBackupFileEntry>): String = sha256(
    entries.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.bytes}\u0000${it.sha256}\n" }
        .toByteArray(Charsets.UTF_8),
)

private fun quoted(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

private fun encodeDatabase(snapshot: DatabaseSnapshot): String = JSONObject().apply {
    put("schemaVersion", snapshot.schemaVersion)
    put("tables", JSONArray().apply {
        snapshot.tables.forEach { table -> put(JSONObject().apply {
            put("name", table.name)
            put("columns", JSONArray(table.columns))
            put("rows", JSONArray().apply {
                table.rows.forEach { row -> put(JSONArray().apply { row.forEach { put(encodeDatabaseValue(it)) } }) }
            })
        }) }
    })
    put("sequences", JSONObject().apply { snapshot.sequences.toSortedMap().forEach { (name, value) -> put(name, value) } })
}.toString()

private fun encodeDatabaseValue(value: DatabaseValue): JSONObject = JSONObject().apply {
    when (value) {
        DatabaseValue.Null -> put("type", "null")
        is DatabaseValue.Integer -> { put("type", "integer"); put("value", value.value.toString()) }
        is DatabaseValue.Real -> { put("type", "real"); put("value", value.value.toString()) }
        is DatabaseValue.Text -> { put("type", "text"); put("value", value.value) }
        is DatabaseValue.Blob -> { put("type", "blob"); put("value", Base64.encodeToString(value.value, Base64.NO_WRAP)) }
    }
}

private fun decodeDatabase(encoded: String, expectedSchema: Int? = null): DatabaseSnapshot {
    val root = runCatching { JSONObject(encoded) }.getOrElse { throw LedgerBackupIntegrityException("数据库快照 JSON 损坏") }
    val schema = root.getInt("schemaVersion")
    if (schema !in 10..DATABASE_SCHEMA_VERSION || (expectedSchema != null && schema != expectedSchema)) throw LedgerBackupCompatibilityException("数据库快照 v$schema 不受支持或与文件头不一致")
    val expectedTables = if(schema == 10) BUSINESS_TABLES.filterNot { it in XiaomiWeightLedger.columns } else BUSINESS_TABLES
    val tablesArray = root.getJSONArray("tables")
    if (tablesArray.length() != expectedTables.size) throw LedgerBackupIntegrityException("数据库快照表数量异常")
    var totalRows = 0
    val tables = List(tablesArray.length()) { tableIndex ->
        val value = tablesArray.getJSONObject(tableIndex)
        val name = value.getString("name")
        val columnsArray = value.getJSONArray("columns")
        val columns = List(columnsArray.length()) { columnsArray.getString(it) }
        if (columns.isEmpty() || columns.toSet().size != columns.size) throw LedgerBackupIntegrityException("$name 列定义异常")
        val rowsArray = value.getJSONArray("rows")
        totalRows += rowsArray.length()
        if (totalRows > 1_000_000) throw LedgerBackupIntegrityException("数据库快照行数超过安全上限")
        val rows = List(rowsArray.length()) { rowIndex ->
            val row = rowsArray.getJSONArray(rowIndex)
            if (row.length() != columns.size) throw LedgerBackupIntegrityException("$name 行列数量不一致")
            List(row.length()) { decodeDatabaseValue(row.getJSONObject(it)) }
        }
        TableSnapshot(name, columns, rows)
    }
    if (tables.map { it.name } != expectedTables) throw LedgerBackupIntegrityException("数据库快照表顺序或集合异常")
    val sequenceObject = root.getJSONObject("sequences")
    val sequences = sequenceObject.keys().asSequence().associateWith { name ->
        if (name !in AUTOINCREMENT_TABLES || name !in expectedTables) {
            throw LedgerBackupIntegrityException("数据库序列包含非自增表")
        }
        sequenceObject.getLong(name).also {
            if (it !in 0L..MAX_SAFE_SQLITE_ROW_ID) {
                throw LedgerBackupIntegrityException("数据库序列超出安全范围")
            }
        }
    }
    AUTOINCREMENT_TABLES.filter { it in expectedTables }.forEach { tableName ->
        val table = tables.single { it.name == tableName }
        val idIndex = table.columns.indexOf("id")
        if (idIndex < 0) throw LedgerBackupIntegrityException("$tableName 缺少自增主键")
        val maxId = table.rows.maxOfOrNull { row ->
            (row[idIndex] as? DatabaseValue.Integer)?.value
                ?: throw LedgerBackupIntegrityException("$tableName 自增主键类型异常")
        } ?: 0L
        if (maxId !in 0L..MAX_SAFE_SQLITE_ROW_ID) {
            throw LedgerBackupIntegrityException("$tableName 自增主键超出安全范围")
        }
        sequences[tableName]?.let { sequence ->
            if (sequence < maxId) {
                throw LedgerBackupIntegrityException("$tableName 数据库序列早于现有主键")
            }
        }
    }
    val upgradedTables = if(schema == 10) tables + XiaomiWeightLedger.columns.map { (name, columns) -> TableSnapshot(name,columns,emptyList()) } else tables
    return DatabaseSnapshot(DATABASE_SCHEMA_VERSION, upgradedTables, sequences)
}

private fun decodeDatabaseValue(value: JSONObject): DatabaseValue = when (value.getString("type")) {
    "null" -> DatabaseValue.Null
    "integer" -> DatabaseValue.Integer(value.getString("value").toLongOrNull()
        ?: throw LedgerBackupIntegrityException("数据库整数损坏"))
    "real" -> DatabaseValue.Real(value.getString("value").toDoubleOrNull()?.takeIf(Double::isFinite)
        ?: throw LedgerBackupIntegrityException("数据库浮点数损坏"))
    "text" -> DatabaseValue.Text(value.getString("value").also {
        if (it.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) throw LedgerBackupIntegrityException("数据库文本超过安全上限")
    })
    "blob" -> DatabaseValue.Blob(runCatching { Base64.decode(value.getString("value"), Base64.NO_WRAP) }
        .getOrElse { throw LedgerBackupIntegrityException("数据库二进制字段损坏") })
    else -> throw LedgerBackupIntegrityException("数据库字段类型未知")
}

private fun encodePreferences(values: Map<String, Any?>): String = JSONObject().apply {
    put("version", 1)
    put("values", JSONObject().apply {
        values.toSortedMap().forEach { (key, raw) -> put(key, JSONObject().apply {
            when (raw) {
                null -> put("type", "null")
                is String -> { put("type", "string"); put("value", raw) }
                is Boolean -> { put("type", "boolean"); put("value", raw) }
                is Int -> { put("type", "int"); put("value", raw) }
                is Long -> { put("type", "long"); put("value", raw.toString()) }
                is Float -> { put("type", "float"); put("value", raw.toString()) }
                is Set<*> -> { put("type", "strings"); put("value", JSONArray(raw.map { it as String }.sorted())) }
                else -> throw LedgerBackupException("无法编码偏好类型")
            }
        }) }
    })
}.toString()

private fun decodePreferences(encoded: String, allowPlannerEnvelope: Boolean = false): Map<String, Any?> {
    val root = runCatching { JSONObject(encoded) }.getOrElse { throw LedgerBackupIntegrityException("偏好快照 JSON 损坏") }
    if (root.getInt("version") != 1) throw LedgerBackupCompatibilityException("偏好快照版本不受支持")
    val values = root.getJSONObject("values")
    if (values.length() > 2_000) throw LedgerBackupIntegrityException("偏好条目过多")
    return values.keys().asSequence().associateWith { key ->
        if (key.length !in 1..200) throw LedgerBackupIntegrityException("偏好键长度异常")
        val entry = values.getJSONObject(key)
        when (entry.getString("type")) {
            "null" -> null
            "string" -> entry.getString("value").also {
                if (key == KEY_ANALYSIS_ADDITIONAL_PROMPT && entry.get("value") !is String) {
                    throw LedgerBackupIntegrityException("附加提示词类型无效")
                }
                val maximumLength = if (allowPlannerEnvelope && key == PLANNER_ENVELOPE_KEY) {
                    MAX_PLANNER_PREFERENCE_CHARACTERS
                } else MAX_ORDINARY_PREFERENCE_CHARACTERS
                if (it.length > maximumLength) throw LedgerBackupIntegrityException("偏好文本过长")
            }
            "boolean" -> entry.getBoolean("value")
            "int" -> entry.getInt("value")
            "long" -> entry.getString("value").toLongOrNull() ?: throw LedgerBackupIntegrityException("偏好 long 损坏")
            "float" -> entry.getString("value").toFloatOrNull()?.takeIf(Float::isFinite)
                ?: throw LedgerBackupIntegrityException("偏好 float 损坏")
            "strings" -> entry.getJSONArray("value").let { array ->
                buildSet { repeat(array.length()) { add(array.getString(it)) } }
            }
            else -> throw LedgerBackupIntegrityException("偏好类型未知")
        }
    }
}

/** Authenticated encryption proves origin/password possession, not that a
 * payload obeys this application's SharedPreferences contract. Validate every
 * restorable business key before even the database dry-run transaction starts;
 * otherwise a wrong primitive type can make a successful restore unreadable. */
private fun validateIncomingBusinessPreferences(
    fitness: Map<String, Any?>,
    planner: Map<String, Any?>,
    workout: IncomingWorkoutBusinessContext,
) {
    val allowedFitnessKeys = setOf(
        "profile_configured",
        "pending_5x5_exercise_id",
        "pending_5x5_load_grams",
        "pending_5x5_commit_id",
        "pending_5x5_v2",
        "pending_single_set_v1",
        "manual_food_form_v1",
        "manual_food_form_cleared_id_v1",
        "body_measurement_form_v1",
        "body_measurement_form_cleared_id_v1",
        "body_measurement_shortcut_resolved_id_v2",
        KEY_ANALYSIS_ADDITIONAL_PROMPT,
    )
    val unknownFitness = fitness.keys - allowedFitnessKeys
    if (unknownFitness.isNotEmpty()) {
        throw LedgerBackupIntegrityException("fitness_settings 包含未知业务键：${unknownFitness.sorted().joinToString()}")
    }
    validatePendingWorkoutPreferenceRelations(fitness, workout)
    fitness.forEach { (key, value) ->
        try {
            when (key) {
                "profile_configured" -> require(value is Boolean)
                KEY_ANALYSIS_ADDITIONAL_PROMPT -> {
                    require(value is String)
                    require(additionalMealPromptValidationError(value) == null)
                }
                "pending_5x5_exercise_id" -> require(value is Long && value > 0L)
                "pending_5x5_load_grams" -> require(value is Long && value in 0L..1_000_000L)
                "pending_5x5_commit_id" -> requireValidId(value, WORKOUT_BATCH_ID_MAX_LENGTH, forbidColon = true)
                "pending_5x5_v2" -> validatePendingFiveByFivePreference(requirePreferenceString(value), workout)
                "pending_single_set_v1" -> validatePendingSingleSetPreference(requirePreferenceString(value), workout)
                "manual_food_form_v1" -> validateManualFoodFormPreference(requirePreferenceString(value))
                "body_measurement_form_v1" -> validateBodyMeasurementFormPreference(requirePreferenceString(value))
                "manual_food_form_cleared_id_v1",
                "body_measurement_form_cleared_id_v1",
                "body_measurement_shortcut_resolved_id_v2",
                -> requireValidId(value, 128)
            }
        } catch (failure: Throwable) {
            if (failure is LedgerBackupException) throw failure
            throw LedgerBackupIntegrityException("fitness_settings.$key 的类型或值域无效")
        }
    }

    val allowedPlannerKeys = setOf(
        "workout_planner_envelope_v1",
        "workout_planner_corrupt_backup_v1",
    )
    val unknownPlanner = planner.keys - allowedPlannerKeys
    if (unknownPlanner.isNotEmpty()) {
        throw LedgerBackupIntegrityException("workout_planner 包含未知业务键：${unknownPlanner.sorted().joinToString()}")
    }
    planner.forEach { (key, value) ->
        try {
            val encoded = requirePreferenceString(
                value,
                if (key == PLANNER_ENVELOPE_KEY) MAX_PLANNER_PREFERENCE_CHARACTERS
                else MAX_ORDINARY_PREFERENCE_CHARACTERS,
            )
            when (key) {
                "workout_planner_envelope_v1" -> validatePlannerEnvelopePreference(encoded, workout)
                "workout_planner_corrupt_backup_v1" -> require(encoded.length <= 200_000)
            }
        } catch (failure: Throwable) {
            if (failure is LedgerBackupException) throw failure
            throw LedgerBackupIntegrityException("workout_planner.$key 的类型或值域无效")
        }
    }
}

private data class IncomingExerciseReference(
    val trackingType: TrackingType,
    val archived: Boolean,
)

private data class IncomingWorkoutBusinessContext(
    val sessionStatuses: Map<Long, String>,
    val exercises: Map<Long, IncomingExerciseReference>,
) {
    fun requireDraftSession(sessionId: Long) {
        require(sessionStatuses[sessionId] == WorkoutStatus.DRAFT.name)
    }

    fun requireAvailableExercise(exerciseId: Long): TrackingType {
        val exercise = requireNotNull(exercises[exerciseId])
        require(!exercise.archived)
        return exercise.trackingType
    }

    fun singleDraftSessionId(): Long = sessionStatuses.entries
        .filter { it.value == WorkoutStatus.DRAFT.name }
        .map { it.key }
        .single()
}

private fun incomingWorkoutBusinessContext(database: DatabaseSnapshot): IncomingWorkoutBusinessContext {
    fun TableSnapshot.column(columnName: String): Int = columns.indexOf(columnName).takeIf { it >= 0 }
        ?: throw LedgerBackupIntegrityException("数据库快照缺少 ${this.name}.$columnName")

    fun DatabaseValue.longValue(): Long = (this as? DatabaseValue.Integer)?.value
        ?: throw LedgerBackupIntegrityException("数据库训练引用字段类型异常")

    fun DatabaseValue.textValue(): String = (this as? DatabaseValue.Text)?.value
        ?: throw LedgerBackupIntegrityException("数据库训练引用字段类型异常")

    val sessions = database.tables.singleOrNull { it.name == "workout_sessions" }
        ?: throw LedgerBackupIntegrityException("数据库快照缺少训练场次")
    val sessionIdIndex = sessions.column("id")
    val sessionStatusIndex = sessions.column("status")
    val sessionStatuses = buildMap {
        sessions.rows.forEach { row ->
            val id = row[sessionIdIndex].longValue()
            val previous = put(id, row[sessionStatusIndex].textValue())
            if (previous != null) throw LedgerBackupIntegrityException("数据库训练场次标识重复")
        }
    }

    val exerciseTable = database.tables.singleOrNull { it.name == "exercises" }
        ?: throw LedgerBackupIntegrityException("数据库快照缺少动作")
    val exerciseIdIndex = exerciseTable.column("id")
    val trackingTypeIndex = exerciseTable.column("tracking_type")
    val archivedIndex = exerciseTable.column("archived")
    val exercises = buildMap {
        exerciseTable.rows.forEach { row ->
            val id = row[exerciseIdIndex].longValue()
            val trackingType = runCatching {
                TrackingType.valueOf(row[trackingTypeIndex].textValue())
            }.getOrElse { throw LedgerBackupIntegrityException("数据库动作追踪类型异常") }
            val archivedRaw = row[archivedIndex].longValue()
            if (archivedRaw !in 0L..1L) throw LedgerBackupIntegrityException("数据库动作归档状态异常")
            val previous = put(id, IncomingExerciseReference(trackingType, archivedRaw == 1L))
            if (previous != null) throw LedgerBackupIntegrityException("数据库动作标识重复")
        }
    }
    return IncomingWorkoutBusinessContext(sessionStatuses, exercises)
}

private fun validatePendingWorkoutPreferenceRelations(
    fitness: Map<String, Any?>,
    workout: IncomingWorkoutBusinessContext,
) {
    val legacyKeys = setOf(
        "pending_5x5_exercise_id",
        "pending_5x5_load_grams",
        "pending_5x5_commit_id",
    )
    val presentLegacyKeys = fitness.keys.intersect(legacyKeys)
    try {
        require(presentLegacyKeys.isEmpty() || presentLegacyKeys == legacyKeys)
        val hasFiveByFive = "pending_5x5_v2" in fitness || presentLegacyKeys.isNotEmpty()
        require(!(hasFiveByFive && "pending_single_set_v1" in fitness))
        require(!("pending_5x5_v2" in fitness && presentLegacyKeys.isNotEmpty()))
        if (presentLegacyKeys.isNotEmpty()) {
            val exerciseId = fitness.getValue("pending_5x5_exercise_id") as? Long
                ?: throw IllegalArgumentException("legacy exercise type")
            val loadGrams = fitness.getValue("pending_5x5_load_grams") as? Long
                ?: throw IllegalArgumentException("legacy load type")
            val batchId = fitness.getValue("pending_5x5_commit_id")
            requireValidId(batchId, WORKOUT_BATCH_ID_MAX_LENGTH, forbidColon = true)
            workout.requireDraftSession(workout.singleDraftSessionId())
            val trackingType = workout.requireAvailableExercise(exerciseId)
            require(trackingType == TrackingType.WEIGHT_REPS || trackingType == TrackingType.BODYWEIGHT_REPS)
            require(loadGrams in 0L..1_000_000L)
            if (trackingType == TrackingType.WEIGHT_REPS) require(loadGrams > 0L)
        }
    } catch (failure: Throwable) {
        if (failure is LedgerBackupException) throw failure
        throw LedgerBackupIntegrityException("fitness_settings 的待提交训练引用无效")
    }
}

private fun requirePreferenceString(
    value: Any?,
    maximumLength: Int = MAX_ORDINARY_PREFERENCE_CHARACTERS,
): String = (value as? String)
    ?.takeIf { it.length <= maximumLength }
    ?: throw IllegalArgumentException("not a bounded string")

private fun requireValidId(value: Any?, maxLength: Int, forbidColon: Boolean = false) {
    val text = value as? String ?: throw IllegalArgumentException("not a string")
    require(text.isNotBlank() && text.length <= maxLength)
    if (forbidColon) require(':' !in text)
}

private fun validateManualFoodFormPreference(encoded: String) {
    val value = JSONObject(encoded)
    require(value.getInt("version") == 1)
    val draft = ManualFoodFormDraft(
        id = value.getString("id"),
        itemId = value.getString("itemId"),
        targetDate = LocalDate.ofEpochDay(value.getLong("targetDateEpochDay")),
        initialGrams = value.getDouble("initialGrams"),
        initialGramsMin = value.getDouble("initialGramsMin"),
        initialGramsMax = value.getDouble("initialGramsMax"),
        initialPortionBasis = PortionBasis.valueOf(value.getString("initialPortionBasis")),
        name = value.getString("name"),
        gramsText = value.getString("gramsText"),
        kcalText = value.getString("kcalText"),
        carbsText = value.getString("carbsText"),
        proteinText = value.getString("proteinText"),
        fatText = value.getString("fatText"),
        sourceName = value.getString("sourceName"),
        weighed = value.getBoolean("weighed"),
        useLabelKcal = value.getBoolean("useLabelKcal"),
        isDirty = value.getBoolean("isDirty"),
        revision = value.getLong("revision"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
    require(draft.isDirty)
    require(draft.persistenceValidationError() == null)
}

private fun validateBodyMeasurementFormPreference(encoded: String) {
    val value = JSONObject(encoded)
    val version = value.getInt("version")
    require(version in 1..2)
    val shortcutRequestId = when (version) {
        1 -> if (value.optBoolean("openedFromFirstShortcut", false)) "legacy-${value.getString("id")}" else null
        else -> if (value.isNull("shortcutRequestId")) null else value.getString("shortcutRequestId")
    }
    val draft = BodyMeasurementFormDraft(
        id = value.getString("id"),
        measurementId = value.getLong("measurementId"),
        date = LocalDate.ofEpochDay(value.getLong("dateEpochDay")),
        weightText = value.getString("weightText"),
        waistText = value.getString("waistText"),
        shortcutRequestId = shortcutRequestId,
        revision = value.getLong("revision"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
    require(draft.persistenceValidationError() == null)
}

private fun validatePendingFiveByFivePreference(
    encoded: String,
    workout: IncomingWorkoutBusinessContext,
) {
    val value = JSONObject(encoded)
    require(value.getInt("version") == 2)
    val sessionId = value.getLong("sessionId")
    val exerciseId = value.getLong("exerciseId")
    val loadGrams = value.getLong("loadGrams")
    require(sessionId > 0L)
    require(exerciseId > 0L)
    require(loadGrams in 0L..1_000_000L)
    require(value.getLong("submittedAtMillis") > 0L)
    requireValidId(value.getString("batchId"), WORKOUT_BATCH_ID_MAX_LENGTH, forbidColon = true)
    workout.requireDraftSession(sessionId)
    val trackingType = workout.requireAvailableExercise(exerciseId)
    require(trackingType == TrackingType.WEIGHT_REPS || trackingType == TrackingType.BODYWEIGHT_REPS)
    if (trackingType == TrackingType.WEIGHT_REPS) require(loadGrams > 0L)
}

private fun validatePendingSingleSetPreference(
    encoded: String,
    workout: IncomingWorkoutBusinessContext,
) {
    val value = JSONObject(encoded)
    require(value.getInt("version") == 1)
    val sessionId = value.getLong("sessionId")
    val exerciseId = value.getLong("exerciseId")
    require(sessionId > 0L)
    require(exerciseId > 0L)
    require(value.getLong("submittedAtMillis") > 0L)
    val input = WorkoutSetInput(
        weightKg = value.getDouble("weightKg"),
        reps = value.getInt("reps"),
        durationSeconds = value.getInt("durationSeconds"),
        completed = value.getBoolean("completed"),
        isWarmup = value.getBoolean("isWarmup"),
        rpe = value.optionalPreferenceDouble("rpe"),
        rir = value.optionalPreferenceDouble("rir"),
        note = value.getString("note"),
        supersetId = value.optionalPreferenceString("supersetId"),
        restSecondsAfter = value.optionalPreferenceInt("restSecondsAfter"),
        commitId = value.getString("commitId"),
    )
    workout.requireDraftSession(sessionId)
    val trackingType = workout.requireAvailableExercise(exerciseId)
    require(input.validationError(trackingType) == null)
}

private fun validatePlannerEnvelopePreference(
    encoded: String,
    workout: IncomingWorkoutBusinessContext,
) {
    val value = JSONObject(encoded)
    require(value.getInt("version") == 1)
    require(value.getDouble("barbellBarWeightKg").let { it.isFinite() && it in 0.0..50.0 })
    if (!value.isNull("clearedSessionId")) require(value.getLong("clearedSessionId") > 0L)
    val editor = if (value.isNull("editorDraft")) null else validatePlannerEditor(value.getJSONObject("editorDraft"))
    val activePlan = if (value.isNull("activePlan")) null else validateActivePlannerPlan(value.getJSONObject("activePlan"))
    editor?.let { draft ->
        workout.requireDraftSession(draft.sessionId)
        workout.requireAvailableExercise(draft.selectedExerciseId)
    }
    activePlan?.let { plan ->
        workout.requireDraftSession(plan.sessionId)
        plan.items.forEach { item -> workout.requireAvailableExercise(item.exerciseId) }
    }
    require(editor == null || activePlan == null || editor.sessionId == activePlan.sessionId)
    val templates = value.getJSONArray("templates")
    require(templates.length() <= 100)
    repeat(templates.length()) { validatePlannerTemplate(templates.getJSONObject(it)) }
}

private fun validatePlannerEditor(value: JSONObject): WorkoutEditorDraft {
    val draft = WorkoutEditorDraft(
        sessionId = value.getLong("sessionId"),
        selectedExerciseId = value.getLong("selectedExerciseId"),
        weightText = value.getString("weightText"),
        repsText = value.getString("repsText"),
        durationText = value.getString("durationText"),
        rpeText = value.getString("rpeText"),
        rirText = value.getString("rirText"),
        noteText = value.getString("noteText"),
        isWarmup = value.getBoolean("isWarmup"),
        isFailed = value.getBoolean("isFailed"),
        autoRest = value.getBoolean("autoRest"),
        restSeconds = value.getInt("restSeconds"),
        selectedSupersetId = value.optionalPreferenceString("selectedSupersetId"),
        commitId = value.getString("commitId"),
        revision = value.getLong("revision"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
    require(draft.persistenceError() == null)
    return draft
}

private fun validatePlannerItem(value: JSONObject): WorkoutPlanItem = WorkoutPlanItem(
    id = value.getString("id"),
    plannedCommitId = value.getString("plannedCommitId"),
    exerciseId = value.getLong("exerciseId"),
    weightKg = value.getDouble("weightKg"),
    reps = value.getInt("reps"),
    durationSeconds = value.getInt("durationSeconds"),
    isWarmup = value.getBoolean("isWarmup"),
    rpe = value.optionalPreferenceDouble("rpe"),
    rir = value.optionalPreferenceDouble("rir"),
    note = value.getString("note"),
    supersetId = value.optionalPreferenceString("supersetId"),
    autoRest = value.getBoolean("autoRest"),
    restSeconds = value.getInt("restSeconds"),
).also { require(it.persistenceError() == null) }

private fun validateActivePlannerPlan(value: JSONObject): ActiveWorkoutPlan {
    val itemsArray = value.getJSONArray("items")
    require(itemsArray.length() in 1..500)
    val items = List(itemsArray.length()) { validatePlannerItem(itemsArray.getJSONObject(it)) }
    val plan = ActiveWorkoutPlan(
        sessionId = value.getLong("sessionId"),
        sourceLabel = value.getString("sourceLabel"),
        items = items,
        revision = value.getLong("revision"),
        updatedAtMillis = value.getLong("updatedAtMillis"),
    )
    require(plan.sessionId > 0L)
    require(plan.sourceLabel.trim().length in 1..120)
    require(plan.revision > 0L && plan.updatedAtMillis > 0L)
    require(items.map { it.id }.toSet().size == items.size)
    require(items.map { it.plannedCommitId }.toSet().size == items.size)
    return plan
}

private fun validatePlannerTemplate(value: JSONObject) {
    require(value.getString("id").let { it.isNotBlank() && it.length <= 128 })
    require(value.getString("name").trim().length in 1..60)
    val itemsArray = value.getJSONArray("items")
    require(itemsArray.length() in 1..500)
    val items = List(itemsArray.length()) { validatePlannerItem(itemsArray.getJSONObject(it)) }
    val created = value.getLong("createdAtMillis")
    require(created > 0L && value.getLong("updatedAtMillis") >= created)
    require(items.map { it.id }.toSet().size == items.size)
}

private fun JSONObject.optionalPreferenceString(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.optionalPreferenceDouble(key: String): Double? =
    if (isNull(key)) null else getDouble(key)

private fun JSONObject.optionalPreferenceInt(key: String): Int? =
    if (isNull(key)) null else getInt(key)

private fun replaceDatabase(
    db: SQLiteDatabase,
    snapshot: DatabaseSnapshot,
    commit: Boolean,
    beforeCommit: (() -> Unit)? = null,
) {
    validateDatabaseShape(db, snapshot)
    db.beginTransaction()
    try {
        // Durable guard rows authorize the exact lifecycle deletes that ordinary
        // callers are forbidden to perform.
        db.execSQL(
            "INSERT OR REPLACE INTO workout_rewrite_guards(session_id,reason,created_at) " +
                "SELECT id,'DELETE',0 FROM workout_sessions WHERE status <> 'DRAFT'",
        )
        db.delete("pr_events", null, null)
        db.delete("workout_sets", null, null)
        db.delete("workout_sessions", null, null)
        db.delete("workout_rewrite_guards", null, null)

        listOf(
            "meal_items", "meals", "saved_foods", "photo_drafts",
            "body_measurement_form_commits", "body_measurements",
            "manual_food_form_conversions", "exercises", "profile",
            "xiaomi_weights", "xiaomi_sync",
        ).forEach { db.delete(it, null, null) }

        val ordinary = listOf(
            "profile", "body_measurements", "body_measurement_form_commits",
            "meals", "meal_items", "saved_foods", "photo_drafts", "exercises",
            "manual_food_form_conversions",
            "xiaomi_sync", "xiaomi_weights",
        )
        ordinary.forEach { name -> insertRows(db, snapshot.tables.single { it.name == name }) }

        val sessions = snapshot.tables.single { it.name == "workout_sessions" }
        val correctionColumn = sessions.columns.indexOf("correction_of_session_id")
        val orderedSessions = sessions.copy(rows = sessions.rows.sortedBy { row ->
            if (row[correctionColumn] == DatabaseValue.Null) 0 else 1
        })
        insertRows(db, orderedSessions)
        val statusIndex = sessions.columns.indexOf("status")
        sessions.rows.forEach { row ->
            val status = (row[statusIndex] as DatabaseValue.Text).value
            if (status != "DRAFT") {
                val id = (row[sessions.columns.indexOf("id")] as DatabaseValue.Integer).value
                db.insertOrThrow("workout_rewrite_guards", null, ContentValues().apply {
                    put("session_id", id); put("reason", "CORRECT"); put("created_at", 0L)
                })
            }
        }
        insertRows(db, snapshot.tables.single { it.name == "workout_sets" })
        db.delete("workout_rewrite_guards", null, null)
        insertRows(db, snapshot.tables.single { it.name == "pr_events" })
        if (snapshot.tables.single { it.name == "workout_rewrite_guards" }.rows.isNotEmpty()) {
            throw LedgerBackupIntegrityException("备份不得包含瞬态训练改写锁")
        }

        db.delete("sqlite_sequence", "name IN (${BUSINESS_TABLES.joinToString(",") { "?" }})", BUSINESS_TABLES.toTypedArray())
        snapshot.sequences.forEach { (name, sequence) ->
            db.insertOrThrow("sqlite_sequence", null, ContentValues().apply { put("name", name); put("seq", sequence) })
        }
        if (db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) {
            throw LedgerBackupIntegrityException("备份违反数据库外键约束")
        }
        XiaomiWeightLedger.validateRestored(db)
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "missing"
        }
        if (integrity != "ok") throw LedgerBackupIntegrityException("数据库完整性检查失败：$integrity")
        snapshot.tables.forEach { table ->
            val actual = db.rawQuery("SELECT COUNT(*) FROM ${quoted(table.name)}", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            if (actual != table.rows.size) throw LedgerBackupIntegrityException("${table.name} 恢复行数不一致")
        }
        if (commit) {
            beforeCommit?.invoke()
            db.setTransactionSuccessful()
        }
    } finally {
        db.endTransaction()
    }
}

private fun validateDatabaseShape(db: SQLiteDatabase, snapshot: DatabaseSnapshot) {
    if (snapshot.schemaVersion != db.version || db.version != DATABASE_SCHEMA_VERSION) {
        throw LedgerBackupCompatibilityException("数据库版本不一致")
    }
    snapshot.tables.forEach { table ->
        val contracts = db.rawQuery("PRAGMA table_info(${quoted(table.name)})", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DatabaseColumnContract(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            declaredType = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                            primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) != 0,
                        ),
                    )
                }
            }
        }
        if (contracts.map { it.name } != table.columns) {
            throw LedgerBackupCompatibilityException("${table.name} 列结构不兼容")
        }
        table.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, value ->
                val contract = contracts[columnIndex]
                if (!contract.accepts(value)) {
                    throw LedgerBackupIntegrityException(
                        "${table.name}.${contract.name} 第 ${rowIndex + 1} 行存储类型或空值无效",
                    )
                }
            }
        }
    }
}

private data class DatabaseColumnContract(
    val name: String,
    val declaredType: String,
    val notNull: Boolean,
    val primaryKey: Boolean,
) {
    fun accepts(value: DatabaseValue): Boolean {
        if (value == DatabaseValue.Null) return !notNull && !primaryKey
        val normalized = declaredType.trim().uppercase()
        return when {
            "INT" in normalized -> value is DatabaseValue.Integer
            "CHAR" in normalized || "CLOB" in normalized || "TEXT" in normalized ->
                value is DatabaseValue.Text
            "REAL" in normalized || "FLOA" in normalized || "DOUB" in normalized ->
                value is DatabaseValue.Real
            normalized.isEmpty() || "BLOB" in normalized -> value is DatabaseValue.Blob
            // NUMERIC/DECIMAL affinity can legitimately expose either SQLite
            // INTEGER or REAL storage, but never TEXT/BLOB merely because
            // SQLite's non-STRICT coercion would accept it.
            else -> value is DatabaseValue.Integer || value is DatabaseValue.Real
        }
    }
}

private fun insertRows(db: SQLiteDatabase, table: TableSnapshot) {
    table.rows.forEach { row ->
        val values = ContentValues()
        table.columns.zip(row).forEach { (column, value) -> when (value) {
            DatabaseValue.Null -> values.putNull(column)
            is DatabaseValue.Integer -> values.put(column, value.value)
            is DatabaseValue.Real -> values.put(column, value.value)
            is DatabaseValue.Text -> values.put(column, value.value)
            is DatabaseValue.Blob -> values.put(column, value.value)
        } }
        db.insertOrThrow(table.name, null, values)
    }
}

private fun prepareRestoreJournal(context: Context, rollback: CompleteSnapshot, incoming: ParsedBackup) {
    val transaction = transactionDirectory(context)
    if (transaction.exists()) throw LedgerBackupException("存在尚未恢复的导入事务")
    var injectCleanupParentSyncFailure = false
    try {
        createDirectoryDurably(transaction, "无法建立恢复事务目录")
        writeSynced(File(transaction, ROLLBACK_DATABASE_FILE), encodeDatabase(rollback.database).toByteArray(Charsets.UTF_8))
        writeSynced(
            File(transaction, ROLLBACK_PREFERENCES_FILE),
            JSONObject().apply {
                // Rollback is internal and must retain the receiver's existing
                // Keystore ciphertext/endpoint if an import fails.
                put("fitness", JSONObject(encodePreferences(
                    context.getSharedPreferences(FITNESS_PREFERENCES, Context.MODE_PRIVATE).all
                        .mapValues { clonePreferenceValue(it.value) },
                )))
                put("planner", JSONObject(encodePreferences(
                    context.getSharedPreferences(PLANNER_PREFERENCES, Context.MODE_PRIVATE).all
                        .mapValues { clonePreferenceValue(it.value) },
                )))
                // Camera state remains excluded from the portable backup.  This
                // copy is only the receiver-local rollback generation so a
                // failed clear() cannot strand an in-memory or on-disk pointer.
                put("camera", JSONObject(encodePreferences(
                    context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE).all
                        .mapValues { clonePreferenceValue(it.value) },
                )))
            }.toString().toByteArray(Charsets.UTF_8),
        )
        writeSynced(File(transaction, INCOMING_FILE), JSONObject().apply {
            put("database", JSONObject(encodeDatabase(incoming.database)))
            put("fitness", JSONObject(encodePreferences(incoming.fitnessPreferences)))
            put("planner", JSONObject(encodePreferences(incoming.plannerPreferences)))
        }.toString().toByteArray(Charsets.UTF_8))

        val stage = photoStageDirectory(context)
        if (stage.exists()) check(deleteRecursivelyDurably(stage)) { "无法清理旧照片暂存目录" }
        createDirectoryDurably(stage, "无法建立照片暂存目录")
        incoming.photos.forEach { (name, bytes) -> writeSynced(File(stage, name), bytes) }
        if (nextFailurePoint == RestoreFailurePoint.PREPARE_CLEANUP_PARENT_SYNC_FAILED) {
            // Leave the point pending so deleteRecursivelyDurably consumes it
            // precisely after unlink and before the parent-directory fsync.
            injectCleanupParentSyncFailure = true
            error("Injected failure while preparing restore journal")
        }
        writeJournalPhase(context, RestorePhase.PREPARED)
    } catch (failure: Throwable) {
        var cleanupFailure: Throwable? = null
        runCatching {
            deleteRecursivelyDurably(
                transaction,
                failureAfterDelete = RestoreFailurePoint.PREPARE_CLEANUP_PARENT_SYNC_FAILED
                    .takeIf { injectCleanupParentSyncFailure },
            )
        }.onFailure { cleanup ->
            cleanupFailure = cleanup
            failure.addSuppressed(cleanup)
        }
        runCatching { deleteRecursivelyDurably(photoStageDirectory(context)) }.onFailure { cleanup ->
            if (cleanupFailure == null) cleanupFailure = cleanup
            failure.addSuppressed(cleanup)
        }
        if (cleanupFailure != null) {
            // An unlink that was not parent-fsynced may reappear after a crash.
            // Keep the process poisoned even if today's VFS reports no journal.
            restoreProcessPoisoned = true
            throw restoreRecoveryRequired(failure, cleanupFailure)
        }
        throw failure
    }
}

private fun swapInStagedPhotos(context: Context) {
    val active = activePhotoDirectory(context).also {
        if (!it.exists()) createDirectoryDurably(it, "无法建立照片目录")
    }
    val stage = photoStageDirectory(context)
    val rollback = photoRollbackDirectory(context)
    check(stage.isDirectory && !rollback.exists()) { "照片恢复目录状态异常" }
    check(renameDurably(active, rollback)) { "无法保存原照片目录" }
    if (!renameDurably(stage, active)) {
        check(renameDurably(rollback, active)) { "无法还原原照片目录" }
        error("无法切换恢复照片目录")
    }
}

private fun replacePreferences(
    context: Context,
    fitness: Map<String, Any?>,
    planner: Map<String, Any?>,
) {
    if (fitness.keys.any { it in SECRET_SETTING_KEYS }) throw LedgerBackupIntegrityException("恢复不得写入代理凭据")
    applyPreferences(context.getSharedPreferences(FITNESS_PREFERENCES, Context.MODE_PRIVATE), fitness)
    applyPreferences(context.getSharedPreferences(PLANNER_PREFERENCES, Context.MODE_PRIVATE), planner)
}

private fun applyPreferences(preferences: SharedPreferences, values: Map<String, Any?>) {
    val editor = preferences.edit().clear()
    values.forEach { (key, value) -> when (value) {
        null -> editor.remove(key)
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, (value as Set<String>).toSet())
        else -> throw LedgerBackupIntegrityException("恢复偏好类型未知")
    } }
    check(editor.commit()) { "无法持久化恢复偏好" }
}

private fun writeJournalPhase(context: Context, phase: RestorePhase) {
    writeSynced(
        File(transactionDirectory(context), JOURNAL_FILE),
        JSONObject().apply {
            put("version", 1)
            put("phase", phase.name)
            if (phase == RestorePhase.COMMITTED) put("cameraStateCleared", true)
        }.toString().toByteArray(Charsets.UTF_8),
    )
}

/** READY proof shared by startup, explicit retry, export and a new import. */
private fun settleInterruptedRestoreLocked(context: Context) {
    restoreProcessPoisoned = true
    try {
        recoverInterruptedRestoreLocked(context)
        // Sync every parent whose directory entries the restore protocol may have
        // renamed or removed. This is unconditional: a previous process can lose
        // its in-memory poison after unlink succeeded but parent fsync failed.
        syncDirectory(context.noBackupFilesDir)
        syncDirectory(context.filesDir)
        syncDirectory(context.cacheDir)
        check(!transactionDirectory(context).exists()) { "恢复事务目录仍未清理" }
        check(!photoStageDirectory(context).exists()) { "恢复照片暂存目录仍未清理" }
        check(!photoRollbackDirectory(context).exists()) { "恢复照片回滚目录仍未清理" }
        restoreProcessPoisoned = false
    } catch (failure: Throwable) {
        restoreProcessPoisoned = true
        if (failure is LedgerRestoreRecoveryRequiredException) throw failure
        throw LedgerRestoreRecoveryRequiredException(failure)
    }
}

private fun recoverInterruptedRestoreLocked(context: Context) {
    val transaction = transactionDirectory(context)
    if (!transaction.exists()) {
        // A process may die before PREPARED is durable. These names are reserved
        // exclusively for restore and can be safely reconciled here.
        check(deleteRecursivelyDurably(photoStageDirectory(context))) { "无法清理照片暂存目录" }
        if (photoRollbackDirectory(context).exists()) {
            if (activePhotoDirectory(context).exists()) {
                check(deleteRecursivelyDurably(photoRollbackDirectory(context))) { "无法清理照片回滚目录" }
            } else {
                check(renameDurably(photoRollbackDirectory(context), activePhotoDirectory(context))) {
                    "无法恢复照片目录"
                }
            }
        }
        return
    }
    val journal = File(transaction, JOURNAL_FILE)
    if (!journal.isFile) {
        // No durable PREPARED record means no database commit was attempted.
        check(deleteRecursivelyDurably(photoStageDirectory(context))) { "无法清理照片暂存目录" }
        if (photoRollbackDirectory(context).exists()) {
            if (activePhotoDirectory(context).exists()) {
                check(deleteRecursivelyDurably(photoRollbackDirectory(context))) { "无法清理照片回滚目录" }
            } else {
                check(renameDurably(photoRollbackDirectory(context), activePhotoDirectory(context))) {
                    "无法恢复照片目录"
                }
            }
        }
        check(deleteRecursivelyDurably(transaction)) { "无法清理无效恢复事务" }
        return
    }
    val phase = readRestorePhaseOrNull(context)
        ?: throw LedgerBackupException("恢复事务日志损坏，已停止打开账本")
    when (phase) {
        RestorePhase.CLEARING_CAMERA -> {
            check(clearCameraStateForRestore(context)) { "无法持久化相机恢复状态" }
            writeJournalPhase(context, RestorePhase.COMMITTED)
            finishCommittedRestore(context)
            FitnessDatabase.invalidateProcessCachesAfterExternalRestore()
        }
        RestorePhase.COMMITTED -> {
            // Alpha11 could leave a v1 COMMITTED journal before its unchecked
            // camera clear. Absence of the new receipt therefore requires an
            // idempotent checked clear before deleting the referenced cache.
            if (!restoreJournalHasCameraClearReceipt(context)) {
                check(clearCameraStateForRestore(context)) { "无法持久化相机恢复状态" }
                writeJournalPhase(context, RestorePhase.COMMITTED)
            }
            finishCommittedRestore(context)
            FitnessDatabase.invalidateProcessCachesAfterExternalRestore()
        }
        else -> rollbackPreparedRestore(context)
    }
}

private fun rollbackPreparedRestore(context: Context) {
    rollbackDatabaseAttemptsForTest += 1
    val transaction = transactionDirectory(context)
    if (!transaction.exists()) return
    val dbFile = File(transaction, ROLLBACK_DATABASE_FILE)
    val prefsFile = File(transaction, ROLLBACK_PREFERENCES_FILE)
    if (!dbFile.isFile || !prefsFile.isFile) throw LedgerBackupException("恢复回滚快照不完整")

    val database = FitnessDatabase(context)
    try {
        replaceDatabase(
            database.writableDatabase,
            decodeDatabase(dbFile.readText()),
            commit = true,
            beforeCommit = {
                if (consumeRollbackDatabaseCommitFailure()) {
                    error("Injected rollback database commit failure")
                }
            },
        )
        FitnessDatabase.invalidateProcessCachesAfterExternalRestore()
    } finally {
        database.close()
    }
    val prefs = JSONObject(prefsFile.readText())
    applyPreferences(
        context.getSharedPreferences(FITNESS_PREFERENCES, Context.MODE_PRIVATE),
        decodePreferences(prefs.getJSONObject("fitness").toString()),
    )
    applyPreferences(
        context.getSharedPreferences(PLANNER_PREFERENCES, Context.MODE_PRIVATE),
        decodePreferences(prefs.getJSONObject("planner").toString(), allowPlannerEnvelope = true),
    )
    if (prefs.has("camera")) {
        applyPreferences(
            context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE),
            decodePreferences(prefs.getJSONObject("camera").toString()),
        )
    }
    val rollbackPhotos = photoRollbackDirectory(context)
    if (rollbackPhotos.exists()) {
        check(deleteRecursivelyDurably(activePhotoDirectory(context))) { "无法清理已导入照片目录" }
        check(renameDurably(rollbackPhotos, activePhotoDirectory(context))) { "无法恢复原照片目录" }
    }
    check(deleteRecursivelyDurably(photoStageDirectory(context))) { "无法清理照片暂存目录" }
    check(deleteRecursivelyDurably(transaction)) { "无法清理恢复事务" }
}

private fun finishCommittedRestore(context: Context) {
    // Camera state was synchronously cleared before COMMITTED became durable.
    // Only delete its referenced cache after that checked commit.
    check(deleteRecursivelyDurably(File(context.cacheDir, PHOTO_DIRECTORY))) { "无法清理相机缓存" }
    check(deleteRecursivelyDurably(photoRollbackDirectory(context))) { "无法清理照片回滚目录" }
    check(deleteRecursivelyDurably(photoStageDirectory(context))) { "无法清理照片暂存目录" }
    check(
        deleteRecursivelyDurably(
            transactionDirectory(context),
            failureAfterDelete = RestoreFailurePoint.FINAL_TRANSACTION_PARENT_SYNC_FAILED,
        ),
    ) { "无法清理恢复事务" }
}

private fun writeSynced(file: File, bytes: ByteArray) {
    file.parentFile?.let { parent ->
        if (!parent.exists()) createDirectoryDurably(parent, "无法建立 ${file.name} 的父目录")
    }
    val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        // Linux rename(2) atomically replaces an existing file.  Syncing the
        // directory afterwards makes both the new name and the removal of the
        // previous name durable across sudden power loss.
        Os.rename(temporary.absolutePath, file.absolutePath)
        syncDirectory(checkNotNull(file.parentFile))
    } catch (failure: Throwable) {
        temporary.delete()
        throw failure
    }
}

private fun clearCameraStateForRestore(context: Context): Boolean {
    // Idempotent, credentials excluded from every backup. A restore requires fresh consent/login.
    XiaomiSessionStore(context).clear()
    XiaomiSyncScheduler.cancel(context)
    val preferences = context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
    if (consumeFailurePoint(RestoreFailurePoint.CAMERA_STATE_CLEAR_COMMIT_REJECTED)) {
        // Model the worst legal outcome: Editor state became observable but the
        // caller received a persistence failure. The receiver-local rollback
        // snapshot must restore it before any referenced cache is deleted.
        preferences.edit().clear().commit()
        return false
    }
    return preferences.edit().clear().commit()
}

private fun readRestorePhaseOrNull(context: Context): RestorePhase? {
    val journal = File(transactionDirectory(context), JOURNAL_FILE)
    if (!journal.isFile) return null
    return parseRestoreJournal(journal).phase
}

private fun restoreJournalHasCameraClearReceipt(context: Context): Boolean {
    val journal = File(transactionDirectory(context), JOURNAL_FILE)
    if (!journal.isFile) return false
    val parsed = parseRestoreJournal(journal)
    return parsed.phase == RestorePhase.COMMITTED && parsed.cameraStateCleared
}

private data class ParsedRestoreJournal(
    val phase: RestorePhase,
    val cameraStateCleared: Boolean,
)

private fun parseRestoreJournal(journal: File): ParsedRestoreJournal {
    try {
        val value = JSONObject(journal.readText(Charsets.UTF_8))
        if (value.getInt("version") != 1) {
            throw LedgerBackupCompatibilityException("恢复事务日志版本不受支持，已保留恢复证据")
        }
        return ParsedRestoreJournal(
            phase = RestorePhase.valueOf(value.getString("phase")),
            cameraStateCleared = value.optBoolean("cameraStateCleared", false),
        )
    } catch (failure: Throwable) {
        if (failure is LedgerBackupException) throw failure
        throw LedgerBackupException("恢复事务日志损坏，已保留恢复证据", failure)
    }
}

private fun createDirectoryDurably(directory: File, failureMessage: String) {
    if (directory.exists()) {
        check(directory.isDirectory) { failureMessage }
        return
    }
    check(directory.mkdirs()) { failureMessage }
    directory.parentFile?.let(::syncDirectory)
}

private fun renameDurably(source: File, destination: File): Boolean {
    if (!source.renameTo(destination)) return false
    source.parentFile?.let(::syncDirectory)
    val sourceParent = source.parentFile?.canonicalPath
    val destinationParent = destination.parentFile
    if (destinationParent != null && destinationParent.canonicalPath != sourceParent) syncDirectory(destinationParent)
    return true
}

private fun deleteRecursivelyDurably(
    target: File,
    failureAfterDelete: RestoreFailurePoint? = null,
): Boolean {
    if (!target.exists()) return true
    val parent = target.parentFile
    if (!target.deleteRecursively()) return false
    if (failureAfterDelete != null && consumeFailurePoint(failureAfterDelete)) {
        error("Injected restore final transaction directory fsync failure")
    }
    parent?.let(::syncDirectory)
    return true
}

private fun syncDirectory(directory: File) {
    directorySyncCallsForTest += 1
    if (consumeFailurePoint(RestoreFailurePoint.DIRECTORY_SYNC_FAILED)) {
        error("Injected restore directory fsync failure")
    }
    val descriptor = Os.open(
        directory.absolutePath,
        // Android's public OsConstants surface does not expose O_DIRECTORY on
        // every API level; opening a known internal directory read-only still
        // yields a directory fd that fsync(2) can durably flush.
        OsConstants.O_RDONLY,
        0,
    )
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private fun transactionDirectory(context: Context) = File(context.noBackupFilesDir, TRANSACTION_DIRECTORY)
private fun activePhotoDirectory(context: Context) = File(context.filesDir, PHOTO_DIRECTORY)
private fun photoStageDirectory(context: Context) = File(context.filesDir, PHOTO_STAGE_DIRECTORY)
private fun photoRollbackDirectory(context: Context) = File(context.filesDir, PHOTO_ROLLBACK_DIRECTORY)
