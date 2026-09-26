package com.personal.fitnessledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.personal.fitnessledger.domain.PrCalculator
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val MAX_EXERCISE_NAME_CODE_POINTS = 60
private const val MAX_EXERCISE_CATEGORY_CODE_POINTS = 30
private const val CORRECTION_BASE_FINGERPRINT_PREFIX = "CFP1:"
private const val MEAL_FOOD_SELECT_COLUMNS = """
    i.id AS food_id, i.item_name AS food_name, i.grams AS food_grams,
    i.kcal_per_100g AS food_kcal, i.carbs_per_100g AS food_carbs,
    i.protein_per_100g AS food_protein, i.fat_per_100g AS food_fat,
    i.source_name AS food_source_name, i.portion_basis AS food_portion_basis,
    i.evidence_tier AS food_evidence_tier, i.calorie_source AS food_calorie_source
"""

/**
 * Form receipts contain only opaque form/target identifiers and a timestamp;
 * body values, food names, and nutrition values must never be copied into them.
 * Thirty days covers process death and delayed callbacks without retaining this
 * low-sensitivity metadata indefinitely after the raw form has been cleared.
 */
internal const val FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1_000L
internal const val FORM_RECEIPT_MAX_ROWS_PER_TABLE = 2_048

/** Stable keyset for descending workout-history traversal. */
data class WorkoutHistoryCursor(
    val sortAtMillis: Long,
    val sessionId: Long,
)

data class WorkoutHistoryPage(
    val items: List<WorkoutHistorySummary>,
    val nextCursor: WorkoutHistoryCursor?,
)

/** The completion receipt returned from the same transaction that persisted
 * the workout's calendar facts. Callers must use [recordedLocalDate] for the
 * post-completion Today query instead of sampling the wall clock again. */
data class WorkoutCompletionResult(
    val personalRecords: List<PersonalRecord>,
    val recordedLocalDate: LocalDate,
    val recordedZoneId: String,
)

internal enum class WorkoutRewriteFailurePoint {
    AFTER_CORRECTION_DRAFT_REMOVED,
    AFTER_ORIGINAL_SETS_REPLACED,
    DURING_PR_REBUILD,
    AFTER_HISTORY_DELETE,
}

internal data class WorkoutHistoryQueryStats(
    val sessionQueryCount: Int = 0,
    val sessionRowsReturned: Int = 0,
    val maxSessionRowsReturnedByOneQuery: Int = 0,
    val maxSessionRowsRequestedByOneQuery: Int = 0,
) {
    operator fun plus(other: WorkoutHistoryQueryStats) = WorkoutHistoryQueryStats(
        sessionQueryCount = sessionQueryCount + other.sessionQueryCount,
        sessionRowsReturned = sessionRowsReturned + other.sessionRowsReturned,
        maxSessionRowsReturnedByOneQuery = maxOf(
            maxSessionRowsReturnedByOneQuery,
            other.maxSessionRowsReturnedByOneQuery,
        ),
        maxSessionRowsRequestedByOneQuery = maxOf(
            maxSessionRowsRequestedByOneQuery,
            other.maxSessionRowsRequestedByOneQuery,
        ),
    )
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
    value?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }

private fun String.codePointLength(): Int = codePointCount(0, length)

private fun trimToCodePoints(value: String, maximum: Int): String {
    if (maximum <= 0) return ""
    if (value.codePointLength() <= maximum) return value
    val end = value.offsetByCodePoints(0, maximum)
    return value.substring(0, end).trimEnd()
}

private fun normalizeExerciseName(value: String): String {
    val normalized = StringBuilder(value.length)
    var pendingSpace = false
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            if (normalized.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) normalized.append(' ')
            normalized.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    return normalized.toString().lowercase(Locale.ROOT)
}

class FitnessDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private data class HistoryOffsetAnchor(
        val cursor: WorkoutHistoryCursor?,
        val exhausted: Boolean,
    )

    private data class WorkoutHistorySessionScan(
        val sessions: List<WorkoutSession>,
        val resumeCursor: WorkoutHistoryCursor?,
        val exhausted: Boolean,
        val stats: WorkoutHistoryQueryStats,
    )

    private val historyPaginationLock = Any()
    private val historyOffsetAnchors = LinkedHashMap<Int, HistoryOffsetAnchor>().apply {
        put(0, HistoryOffsetAnchor(cursor = null, exhausted = false))
    }
    private var historyPaginationGeneration = 0L
    private var observedExternalRestoreGeneration = externalRestoreGeneration.get()

    @Volatile
    private var lastWorkoutHistoryQueryStats = WorkoutHistoryQueryStats()

    @Volatile
    private var nextWorkoutRewriteFailureForTest: WorkoutRewriteFailurePoint? = null

    @Volatile
    private var workoutRewriteGuardCleanupDeleteAttemptsForTest = 0

    internal fun failNextWorkoutRewriteForTest(point: WorkoutRewriteFailurePoint?) {
        nextWorkoutRewriteFailureForTest = point
    }

    internal fun workoutRewriteGuardCleanupDeleteAttemptsForTest(): Int =
        workoutRewriteGuardCleanupDeleteAttemptsForTest

    private fun maybeFailWorkoutRewriteForTest(point: WorkoutRewriteFailurePoint) {
        if (nextWorkoutRewriteFailureForTest == point) {
            nextWorkoutRewriteFailureForTest = null
            throw IllegalStateException("Injected workout rewrite failure at $point")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profile (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                height_cm REAL NOT NULL,
                reference_weight_kg REAL NOT NULL,
                waist_cm REAL NOT NULL,
                carb_factor REAL NOT NULL,
                protein_factor REAL NOT NULL,
                fat_factor REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE body_measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_date TEXT NOT NULL,
                weight_kg REAL NOT NULL,
                waist_cm REAL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                commit_id TEXT NOT NULL UNIQUE,
                meal_date TEXT NOT NULL,
                title TEXT NOT NULL,
                kcal REAL NOT NULL,
                carbs_g REAL NOT NULL,
                protein_g REAL NOT NULL,
                fat_g REAL NOT NULL,
                evidence_tier TEXT NOT NULL,
                confirmed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE meal_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                meal_id INTEGER NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
                item_name TEXT NOT NULL,
                grams REAL NOT NULL,
                kcal_per_100g REAL NOT NULL,
                carbs_per_100g REAL NOT NULL,
                protein_per_100g REAL NOT NULL,
                fat_per_100g REAL NOT NULL,
                source_name TEXT NOT NULL,
                portion_basis TEXT NOT NULL,
                evidence_tier TEXT NOT NULL,
                calorie_source TEXT NOT NULL DEFAULT 'LABEL_OR_DATABASE'
            )
            """.trimIndent(),
        )
        createFoodLibraryV4(db)
        db.execSQL(
            """
            CREATE TABLE photo_drafts (
                draft_id TEXT PRIMARY KEY,
                commit_id TEXT NOT NULL UNIQUE,
                photo_uri TEXT NOT NULL,
                state TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                builtin_key TEXT UNIQUE,
                name TEXT NOT NULL,
                normalized_name TEXT NOT NULL,
                aliases TEXT NOT NULL,
                category TEXT NOT NULL,
                is_custom INTEGER NOT NULL,
                is_primary INTEGER NOT NULL,
                tracking_type TEXT NOT NULL,
                definition_version INTEGER NOT NULL DEFAULT 1,
                archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                status TEXT NOT NULL,
                rest_timer_end_at INTEGER,
                rest_duration_seconds INTEGER NOT NULL DEFAULT 120,
                recorded_local_date TEXT,
                recorded_zone_id TEXT,
                correction_of_session_id INTEGER,
                correction_revision INTEGER NOT NULL DEFAULT 0,
                corrected_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE workout_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
                exercise_id INTEGER NOT NULL REFERENCES exercises(id),
                set_order INTEGER NOT NULL,
                load_grams INTEGER NOT NULL,
                reps INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL,
                is_warmup INTEGER NOT NULL,
                rpe REAL,
                rir REAL,
                note TEXT NOT NULL DEFAULT '',
                superset_id TEXT,
                batch_id TEXT,
                commit_id TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pr_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
                exercise_id INTEGER NOT NULL REFERENCES exercises(id),
                set_id INTEGER NOT NULL REFERENCES workout_sets(id) ON DELETE CASCADE,
                pr_type TEXT NOT NULL,
                bucket_key TEXT NOT NULL DEFAULT '',
                event_kind TEXT NOT NULL,
                value REAL NOT NULL,
                weight_kg REAL NOT NULL,
                reps INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                achieved_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        createNutritionReadIndexesV9(db)
        createDataLayerV10(db)
        XiaomiWeightLedger.create(db)
        db.execSQL("CREATE UNIQUE INDEX idx_body_measurement_date ON body_measurements(recorded_date)")
        db.execSQL("CREATE INDEX idx_sets_exercise ON workout_sets(exercise_id)")
        db.execSQL("CREATE INDEX idx_sets_superset ON workout_sets(session_id, superset_id) WHERE superset_id IS NOT NULL")
        db.execSQL("CREATE INDEX idx_sessions_status ON workout_sessions(status)")
        createWorkoutHistoryIndexV7(db)
        createCompletedWorkoutDateIndexV8(db)
        db.execSQL("CREATE UNIQUE INDEX idx_pr_event_key ON pr_events(session_id, exercise_id, pr_type, bucket_key)")
        db.execSQL("CREATE UNIQUE INDEX idx_workout_set_commit_id ON workout_sets(commit_id)")
        db.execSQL("CREATE UNIQUE INDEX idx_exercise_normalized_name ON exercises(normalized_name COLLATE NOCASE)")
        createWorkoutIntegrityV3(db)
        createCoreIntegrityV6(db)
        createWorkoutRewriteIntegrityV10(db)

        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN duration_seconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pr_events ADD COLUMN bucket_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pr_events ADD COLUMN duration_seconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_event_key ON pr_events(session_id, exercise_id, pr_type, bucket_key)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN batch_id TEXT")
            db.execSQL(
                "DELETE FROM body_measurements WHERE id NOT IN " +
                    "(SELECT MAX(id) FROM body_measurements GROUP BY recorded_date)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_body_measurement_date ON body_measurements(recorded_date)")
            migrateWorkoutIntegrityV3(db)
        }
        if (oldVersion < 4) {
            db.execSQL(
                "ALTER TABLE meal_items ADD COLUMN calorie_source TEXT NOT NULL DEFAULT 'LABEL_OR_DATABASE'",
            )
            createFoodLibraryV4(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN rest_timer_end_at INTEGER")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN rest_duration_seconds INTEGER NOT NULL DEFAULT 120")
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN rir REAL")
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN superset_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sets_superset ON workout_sets(session_id, superset_id) WHERE superset_id IS NOT NULL")
            db.execSQL("DROP TRIGGER IF EXISTS validate_workout_set_insert")
            db.execSQL("DROP TRIGGER IF EXISTS validate_workout_set_update")
            createWorkoutIntegrityV3(db)
        }
        if (oldVersion < 6) {
            migrateDataIntegrityV6(db)
        }
        if (oldVersion < 7) {
            createWorkoutHistoryIndexV7(db)
        }
        if (oldVersion < 8) {
            createCompletedWorkoutDateIndexV8(db)
        }
        if (oldVersion < 9) {
            createNutritionReadIndexesV9(db)
            createBodyMeasurementCommitReceiptsV9(db)
            createManualFoodFormConversionReceiptsV9(db)
        }
        if (oldVersion < 10) {
            migrateDataLayerV10(db)
        }
        if (oldVersion < 11) XiaomiWeightLedger.create(db)
    }

    /** Latest-schema creation bucket; append other independent v10 features here. */
    private fun createDataLayerV10(db: SQLiteDatabase) {
        createBodyMeasurementCommitReceiptsV10(db)
        createManualFoodFormConversionReceiptsV10(db)
        createWorkoutCorrectionSchemaV10(db)
    }

    /** Upgrade bucket deliberately mirrors [createDataLayerV10] for later v10 additions. */
    private fun migrateDataLayerV10(db: SQLiteDatabase) {
        migrateFormReceiptCleanupStateV10(db)
        createWorkoutCorrectionSchemaV10(db, migratingLegacyRows = true)
        createWorkoutRewriteIntegrityV10(db)
    }

    private fun createWorkoutCorrectionSchemaV10(
        db: SQLiteDatabase,
        migratingLegacyRows: Boolean = false,
    ) {
        if (!tableExists(db, "workout_sessions")) return
        if (!columnExists(db, "workout_sessions", "correction_of_session_id")) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN correction_of_session_id INTEGER")
        }
        if (!columnExists(db, "workout_sessions", "correction_revision")) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN correction_revision INTEGER NOT NULL DEFAULT 0")
        }
        if (!columnExists(db, "workout_sessions", "corrected_at")) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN corrected_at INTEGER")
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_rewrite_guards (
                session_id INTEGER PRIMARY KEY,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_workout_correction_target_v10 " +
                "ON workout_sessions(correction_of_session_id) WHERE correction_of_session_id IS NOT NULL",
        )
        // v6 intentionally retained malformed legacy rows and its UPDATE trigger
        // validates the entire NEW row. Updating only ended_at would therefore
        // abort an otherwise non-destructive v9 -> v10 migration when some other
        // old field is malformed. Temporarily remove only that value trigger,
        // perform the narrow backfill, then restore the latest integrity layer.
        if (migratingLegacyRows) {
            db.execSQL("DROP TRIGGER IF EXISTS validate_workout_sessions_values_v6_update")
        }
        try {
            db.execSQL(
                "UPDATE workout_sessions SET ended_at = started_at " +
                    "WHERE status IN ('COMPLETED','CANCELLED') AND ended_at IS NULL",
            )
        } finally {
            if (migratingLegacyRows) createCoreIntegrityV6(db)
        }
    }

    private fun createNutritionReadIndexesV9(db: SQLiteDatabase) {
        if (tableExists(db, "meals")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_meals_date_confirmed_v9 " +
                    "ON meals(meal_date, confirmed_at DESC, id DESC)",
            )
            // The composite index is a left-prefix replacement for this legacy
            // index and also satisfies the date-screen ordering without a sort.
            db.execSQL("DROP INDEX IF EXISTS idx_meals_date")
        }
        if (tableExists(db, "meal_items")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_meal_items_meal_id_id_v9 " +
                    "ON meal_items(meal_id, id)",
            )
        }
    }

    private fun createBodyMeasurementCommitReceiptsV9(db: SQLiteDatabase) {
        if (!tableExists(db, "body_measurements")) return
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS body_measurement_form_commits (
                form_id TEXT PRIMARY KEY,
                measurement_id INTEGER NOT NULL,
                committed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createManualFoodFormConversionReceiptsV9(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manual_food_form_conversions (
                form_id TEXT PRIMARY KEY,
                draft_id TEXT NOT NULL,
                converted_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createBodyMeasurementCommitReceiptsV10(db: SQLiteDatabase) {
        if (!tableExists(db, "body_measurements")) return
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS body_measurement_form_commits (
                form_id TEXT PRIMARY KEY,
                measurement_id INTEGER NOT NULL,
                committed_at INTEGER NOT NULL,
                cleanup_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_confirmed IN (0, 1))
            )
            """.trimIndent(),
        )
    }

    private fun createManualFoodFormConversionReceiptsV10(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manual_food_form_conversions (
                form_id TEXT PRIMARY KEY,
                draft_id TEXT NOT NULL,
                converted_at INTEGER NOT NULL,
                cleanup_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_confirmed IN (0, 1))
            )
            """.trimIndent(),
        )
    }

    /**
     * v9 receipts predate a durable acknowledgement that SharedPreferences raw
     * form cleanup reached disk. Preserve every legacy row as unconfirmed: it
     * remains replay-safe until Repository first reconciles a locked raw-form
     * snapshot, or a later successful cleanup confirms that exact form id.
     */
    private fun migrateFormReceiptCleanupStateV10(db: SQLiteDatabase) {
        if (tableExists(db, "body_measurement_form_commits") &&
            !columnExists(db, "body_measurement_form_commits", "cleanup_confirmed")
        ) {
            db.execSQL(
                "ALTER TABLE body_measurement_form_commits ADD COLUMN " +
                    "cleanup_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_confirmed IN (0, 1))",
            )
        }
        if (tableExists(db, "manual_food_form_conversions") &&
            !columnExists(db, "manual_food_form_conversions", "cleanup_confirmed")
        ) {
            db.execSQL(
                "ALTER TABLE manual_food_form_conversions ADD COLUMN " +
                    "cleanup_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_confirmed IN (0, 1))",
            )
        }
    }

    private fun createWorkoutHistoryIndexV7(db: SQLiteDatabase) {
        if (!tableExists(db, "workout_sessions")) return
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_workout_history_sort_v7 " +
                "ON workout_sessions(COALESCE(ended_at, started_at) DESC, id DESC) " +
                "WHERE status IN ('COMPLETED', 'CANCELLED')",
        )
    }

    private fun createCompletedWorkoutDateIndexV8(db: SQLiteDatabase) {
        if (!tableExists(db, "workout_sessions")) return
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_completed_workout_date_v8 " +
                "ON workout_sessions(recorded_local_date, ended_at DESC, id DESC) " +
                "WHERE status = 'COMPLETED'",
        )
    }

    /**
     * Adds durable write identities, stable history dates, normalized exercise
     * uniqueness, and future-write guards without rebuilding user-owned ledgers.
     * Existing malformed rows remain readable through the defensive decoders below.
     */
    private fun migrateDataIntegrityV6(db: SQLiteDatabase) {
        if (tableExists(db, "workout_sets") && !columnExists(db, "workout_sets", "commit_id")) {
            // v3-v5 deliberately reject every UPDATE against sets owned by a
            // completed/cancelled session. Schema backfill is the one legitimate
            // exception: remove the lifecycle guard inside SQLiteOpenHelper's
            // upgrade transaction, populate durable identities, then recreate the
            // guard below via createWorkoutIntegrityV3().
            db.execSQL("DROP TRIGGER IF EXISTS workout_set_update_requires_draft")
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN commit_id TEXT")
            db.execSQL("UPDATE workout_sets SET commit_id = 'legacy-set-' || id WHERE commit_id IS NULL OR trim(commit_id) = ''")
        }
        if (tableExists(db, "workout_sets")) {
            db.execSQL("DROP TRIGGER IF EXISTS validate_workout_set_insert")
            db.execSQL("DROP TRIGGER IF EXISTS validate_workout_set_update")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_workout_set_commit_id ON workout_sets(commit_id)")
            createWorkoutIntegrityV3(db)
        }

        if (tableExists(db, "workout_sessions")) {
            if (!columnExists(db, "workout_sessions", "recorded_local_date")) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN recorded_local_date TEXT")
            }
            if (!columnExists(db, "workout_sessions", "recorded_zone_id")) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN recorded_zone_id TEXT")
            }
            backfillWorkoutHistoryDatesV6(db)
        }

        if (tableExists(db, "exercises")) {
            normalizeExistingExercisesV6(db)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_exercise_normalized_name ON exercises(normalized_name COLLATE NOCASE)")
        }
        createCoreIntegrityV6(db)
    }

    private fun backfillWorkoutHistoryDatesV6(db: SQLiteDatabase) {
        val zone = ZoneId.systemDefault()
        db.rawQuery(
            "SELECT id, COALESCE(ended_at, started_at) FROM workout_sessions " +
                "WHERE status IN ('COMPLETED', 'CANCELLED') AND (recorded_local_date IS NULL OR recorded_zone_id IS NULL)",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val millis = cursor.getLong(1).coerceAtLeast(0L)
                db.update(
                    "workout_sessions",
                    ContentValues().apply {
                        put("recorded_local_date", Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString())
                        put("recorded_zone_id", zone.id)
                    },
                    "id = ?",
                    arrayOf(cursor.getLong(0).toString()),
                )
            }
        }
    }

    private fun normalizeExistingExercisesV6(db: SQLiteDatabase) {
        val used = mutableSetOf<String>()
        db.query(
            "exercises",
            arrayOf("id", "name", "category", "tracking_type", "is_custom", "is_primary", "definition_version", "archived"),
            null,
            null,
            null,
            null,
            "is_custom ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val originalName = cursor.getString(1).orEmpty()
                var displayName = trimToCodePoints(originalName.trim().ifBlank { "动作 $id" }, MAX_EXERCISE_NAME_CODE_POINTS)
                var normalized = normalizeExerciseName(displayName)
                if (normalized.isBlank()) {
                    displayName = "动作 $id"
                    normalized = normalizeExerciseName(displayName)
                }
                if (!used.add(normalized)) {
                    val suffix = " ($id)"
                    displayName = trimToCodePoints(displayName, MAX_EXERCISE_NAME_CODE_POINTS - suffix.codePointLength()) + suffix
                    normalized = normalizeExerciseName(displayName)
                    var discriminator = 2
                    while (!used.add(normalized)) {
                        val numberedSuffix = " ($id-$discriminator)"
                        displayName = trimToCodePoints(originalName.ifBlank { "动作" }, MAX_EXERCISE_NAME_CODE_POINTS - numberedSuffix.codePointLength()) + numberedSuffix
                        normalized = normalizeExerciseName(displayName)
                        discriminator += 1
                    }
                }
                val rawCategory = cursor.getString(2).orEmpty().trim().ifBlank { "其他" }
                val category = trimToCodePoints(rawCategory, MAX_EXERCISE_CATEGORY_CODE_POINTS)
                val trackingType = enumValueOrNull<TrackingType>(cursor.getString(3))?.name ?: TrackingType.WEIGHT_REPS.name
                db.update(
                    "exercises",
                    ContentValues().apply {
                        put("name", displayName)
                        put("normalized_name", normalized)
                        put("category", category)
                        put("tracking_type", trackingType)
                        put("is_custom", if (cursor.getInt(4) == 1) 1 else 0)
                        put("is_primary", if (cursor.getInt(5) == 1) 1 else 0)
                        put("definition_version", cursor.getInt(6).coerceAtLeast(1))
                        put("archived", if (cursor.getInt(7) == 1) 1 else 0)
                    },
                    "id = ?",
                    arrayOf(id.toString()),
                )
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table),
    ).use { it.moveToFirst() }

    /**
     * Removes receipt metadata without ever reading or copying business payloads.
     * [protectedFormId] is prioritized by the capacity bound. While the raw form
     * may still be recoverable it is also excluded from TTL; after a successful
     * durable cleanup, only capacity priority remains and an already-old receipt
     * may expire immediately.
     */
    private fun pruneFormReceiptTable(
        db: SQLiteDatabase,
        tableName: String,
        timestampColumn: String,
        nowMillis: Long,
        pruneExpired: Boolean,
        protectedFormId: String?,
        protectCurrentFromExpiry: Boolean = true,
    ) {
        require(
            (tableName == "body_measurement_form_commits" && timestampColumn == "committed_at") ||
                (tableName == "manual_food_form_conversions" && timestampColumn == "converted_at"),
        ) { "未知的表单回执表" }
        if (!tableExists(db, tableName)) return

        if (pruneExpired) {
            val cutoff = if (nowMillis > FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS) {
                nowMillis - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS
            } else {
                0L
            }
            val ttlProtectedFormId = protectedFormId.takeIf { protectCurrentFromExpiry }
            val where = if (ttlProtectedFormId == null) {
                "$timestampColumn < ? AND cleanup_confirmed = 1"
            } else {
                "$timestampColumn < ? AND cleanup_confirmed = 1 AND form_id <> ?"
            }
            val args = if (ttlProtectedFormId == null) {
                arrayOf(cutoff.toString())
            } else {
                arrayOf(cutoff.toString(), ttlProtectedFormId)
            }
            db.delete(tableName, where, args)
        }

        val protectedOrder = if (protectedFormId == null) {
            ""
        } else {
            "CASE WHEN form_id = ? THEN 0 ELSE 1 END, "
        }
        val bindArgs: Array<out Any?> = if (protectedFormId == null) {
            arrayOf<Any?>(FORM_RECEIPT_MAX_ROWS_PER_TABLE)
        } else {
            arrayOf<Any?>(protectedFormId, FORM_RECEIPT_MAX_ROWS_PER_TABLE)
        }
        db.execSQL(
            """
            DELETE FROM $tableName
            WHERE rowid NOT IN (
                SELECT rowid
                FROM $tableName
                ORDER BY $protectedOrder cleanup_confirmed ASC,
                    CASE WHEN cleanup_confirmed = 0 THEN rowid ELSE NULL END DESC,
                    $timestampColumn DESC, rowid DESC
                LIMIT ?
            )
            """.trimIndent(),
            bindArgs,
        )
    }

    private fun confirmFormReceiptCleanupExcept(
        db: SQLiteDatabase,
        tableName: String,
        protectedFormId: String?,
    ) {
        require(
            tableName == "body_measurement_form_commits" ||
                tableName == "manual_food_form_conversions",
        ) { "未知的表单回执表" }
        if (!tableExists(db, tableName)) return
        val values = ContentValues().apply { put("cleanup_confirmed", 1) }
        if (protectedFormId == null) {
            db.update(tableName, values, "cleanup_confirmed = 0", null)
        } else {
            db.update(
                tableName,
                values,
                "cleanup_confirmed = 0 AND form_id <> ?",
                arrayOf(protectedFormId),
            )
        }
    }

    private fun confirmFormReceiptCleanup(
        db: SQLiteDatabase,
        tableName: String,
        formId: String,
    ) {
        require(
            tableName == "body_measurement_form_commits" ||
                tableName == "manual_food_form_conversions",
        ) { "未知的表单回执表" }
        if (!tableExists(db, tableName)) return
        db.update(
            tableName,
            ContentValues().apply { put("cleanup_confirmed", 1) },
            "form_id = ? AND cleanup_confirmed = 0",
            arrayOf(formId),
        )
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) found = true
            }
            found
        }

    private fun createFoodLibraryV4(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saved_foods (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                normalized_name TEXT NOT NULL,
                normalized_source TEXT NOT NULL,
                name TEXT NOT NULL,
                kcal_per_100g REAL NOT NULL,
                carbs_per_100g REAL NOT NULL,
                protein_per_100g REAL NOT NULL,
                fat_per_100g REAL NOT NULL,
                source_name TEXT NOT NULL,
                calorie_source TEXT NOT NULL,
                default_grams REAL NOT NULL,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                use_count INTEGER NOT NULL DEFAULT 1,
                last_used_at INTEGER NOT NULL,
                UNIQUE(normalized_name, normalized_source)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_saved_foods_recent ON saved_foods(is_favorite DESC, last_used_at DESC)",
        )
    }

    /**
     * v2 allowed several active sessions and trusted callers to mutate completed workouts.
     * Keep the newest draft, cancel stale drafts, then install database-level invariants.
     */
    private fun migrateWorkoutIntegrityV3(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE workout_sessions
            SET status = 'CANCELLED', ended_at = COALESCE(ended_at, started_at)
            WHERE status = 'DRAFT'
              AND id <> (
                  SELECT id FROM workout_sessions
                  WHERE status = 'DRAFT'
                  ORDER BY started_at DESC, id DESC
                  LIMIT 1
              )
            """.trimIndent(),
        )
        // Rebuild deterministic, gap-free order values before adding the unique index.
        db.execSQL(
            """
            UPDATE workout_sets AS current
            SET set_order = (
                SELECT COUNT(*) FROM workout_sets AS prior
                WHERE prior.session_id = current.session_id
                  AND prior.exercise_id = current.exercise_id
                  AND prior.id <= current.id
            )
            """.trimIndent(),
        )
        createWorkoutIntegrityV3(db)
    }

    private fun createWorkoutIntegrityV3(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_single_draft_workout ON workout_sessions(status) WHERE status = 'DRAFT'")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_workout_set_order ON workout_sets(session_id, exercise_id, set_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_workout_sets_batch ON workout_sets(batch_id) WHERE batch_id IS NOT NULL")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_workout_session_insert
            BEFORE INSERT ON workout_sessions
            WHEN NEW.status NOT IN ('DRAFT', 'COMPLETED', 'CANCELLED')
            BEGIN SELECT RAISE(ABORT, 'invalid workout status'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_workout_session_update
            BEFORE UPDATE OF status ON workout_sessions
            WHEN NEW.status NOT IN ('DRAFT', 'COMPLETED', 'CANCELLED')
              OR (OLD.status <> 'DRAFT' AND NEW.status <> OLD.status)
            BEGIN SELECT RAISE(ABORT, 'invalid workout status transition'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS workout_set_insert_requires_draft
            BEFORE INSERT ON workout_sets
            WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = NEW.session_id), '') <> 'DRAFT'
            BEGIN SELECT RAISE(ABORT, 'sets can only be added to a draft workout'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS workout_set_update_requires_draft
            BEFORE UPDATE ON workout_sets
            WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = OLD.session_id), '') <> 'DRAFT'
              OR NEW.session_id <> OLD.session_id
            BEGIN SELECT RAISE(ABORT, 'sets can only be changed in their draft workout'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS workout_set_delete_requires_draft
            BEFORE DELETE ON workout_sets
            WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = OLD.session_id), '') <> 'DRAFT'
            BEGIN SELECT RAISE(ABORT, 'sets can only be deleted from a draft workout'); END
            """.trimIndent(),
        )
        listOf("INSERT" to "NEW", "UPDATE" to "NEW").forEach { (operation, row) ->
            val commitClause = if (columnExists(db, "workout_sets", "commit_id")) {
                "OR $row.commit_id IS NULL OR length(trim($row.commit_id)) < 1 OR length($row.commit_id) > 128"
            } else {
                ""
            }
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_workout_set_${operation.lowercase()}
                BEFORE $operation ON workout_sets
                WHEN $row.set_order < 1
                  OR $row.load_grams < 0 OR $row.load_grams > 1000000
                  OR $row.reps < 0 OR $row.reps > 1000
                  OR $row.duration_seconds < 0 OR $row.duration_seconds > 86400
                  OR ($row.completed = 1 AND $row.reps = 0 AND $row.duration_seconds = 0)
                  OR $row.completed NOT IN (0, 1)
                  OR $row.is_warmup NOT IN (0, 1)
                  OR ($row.rpe IS NOT NULL AND ($row.rpe < 0 OR $row.rpe > 10))
                  OR ($row.rir IS NOT NULL AND ($row.rir < 0 OR $row.rir > 10))
                  OR length($row.note) > 500
                  OR ($row.superset_id IS NOT NULL AND (length($row.superset_id) < 1 OR length($row.superset_id) > 20))
                  OR ($row.batch_id IS NOT NULL AND (length($row.batch_id) < 1 OR length($row.batch_id) > $WORKOUT_BATCH_ID_MAX_LENGTH))
                  $commitClause
                BEGIN SELECT RAISE(ABORT, 'invalid workout set values'); END
                """.trimIndent(),
            )
        }
    }

    /**
     * Final v10 lifecycle layer. Earlier migration buckets may install the v3
     * triggers before the guard table exists, so v10 replaces those triggers only
     * after its schema has been added. A guard is always scoped to one session and
     * is created and removed inside the same private rewrite transaction.
     */
    private fun createWorkoutRewriteIntegrityV10(db: SQLiteDatabase) {
        if (!tableExists(db, "workout_sessions") ||
            !tableExists(db, "workout_sets") ||
            !tableExists(db, "pr_events") ||
            !tableExists(db, "workout_rewrite_guards")
        ) return

        listOf(
            "validate_workout_session_update",
            "workout_set_insert_requires_draft",
            "workout_set_update_requires_draft",
            "workout_set_delete_requires_draft",
            "workout_session_locked_update_v10",
            "workout_session_locked_delete_v10",
            "workout_correction_values_insert_v10",
            "workout_correction_values_update_v10",
            "workout_guard_values_insert_v10",
            "workout_guard_values_update_v10",
            "pr_event_parent_consistency_v10",
            "pr_event_update_locked_v10",
            "pr_event_delete_guarded_v10",
        ).forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS $name") }

        db.execSQL(
            """
            CREATE TRIGGER validate_workout_session_update
            BEFORE UPDATE OF status ON workout_sessions
            WHEN NEW.status NOT IN ('DRAFT', 'COMPLETED', 'CANCELLED')
              OR (OLD.status <> 'DRAFT' AND NEW.status <> OLD.status AND NOT EXISTS (
                    SELECT 1 FROM workout_rewrite_guards g
                    WHERE g.session_id = OLD.id AND g.reason = 'CORRECT'
                 ))
            BEGIN SELECT RAISE(ABORT, 'invalid workout status transition'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER workout_session_locked_update_v10
            BEFORE UPDATE ON workout_sessions
            WHEN OLD.status <> 'DRAFT'
              AND NOT EXISTS (
                    SELECT 1 FROM workout_rewrite_guards g
                    WHERE g.session_id = OLD.id AND g.reason = 'CORRECT'
              )
            BEGIN SELECT RAISE(ABORT, 'completed workout sessions are immutable'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER workout_session_locked_delete_v10
            BEFORE DELETE ON workout_sessions
            WHEN OLD.status <> 'DRAFT'
              AND NOT EXISTS (
                    SELECT 1 FROM workout_rewrite_guards g
                    WHERE g.session_id = OLD.id AND g.reason = 'DELETE'
              )
            BEGIN SELECT RAISE(ABORT, 'completed workout sessions cannot be deleted directly'); END
            """.trimIndent(),
        )

        val correctionCondition = "NEW.correction_revision < 0 " +
            "OR (NEW.corrected_at IS NOT NULL AND NEW.corrected_at < 0) " +
            "OR (NEW.correction_revision = 0 AND NEW.corrected_at IS NOT NULL) " +
            "OR (NEW.correction_revision > 0 AND NEW.corrected_at IS NULL) " +
            "OR (NEW.correction_of_session_id IS NOT NULL AND (" +
            "NEW.status <> 'DRAFT' OR NEW.correction_of_session_id = NEW.id OR NOT EXISTS (" +
            "SELECT 1 FROM workout_sessions original WHERE original.id = NEW.correction_of_session_id " +
            "AND original.status = 'COMPLETED')))"
        listOf("INSERT", "UPDATE").forEach { operation ->
            db.execSQL(
                "CREATE TRIGGER workout_correction_values_${operation.lowercase()}_v10 " +
                    "BEFORE $operation ON workout_sessions WHEN $correctionCondition " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid workout correction values'); END",
            )
        }
        listOf("INSERT", "UPDATE").forEach { operation ->
            db.execSQL(
                "CREATE TRIGGER workout_guard_values_${operation.lowercase()}_v10 " +
                    "BEFORE $operation ON workout_rewrite_guards " +
                    "WHEN NEW.session_id <= 0 OR NEW.reason NOT IN ('CORRECT','DELETE','REBUILD_PR') OR NEW.created_at < 0 " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid workout rewrite guard'); END",
            )
        }

        db.execSQL(
            """
            CREATE TRIGGER workout_set_insert_requires_draft
            BEFORE INSERT ON workout_sets
            WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = NEW.session_id), '') <> 'DRAFT'
              AND NOT EXISTS (
                    SELECT 1 FROM workout_rewrite_guards g
                    WHERE g.session_id = NEW.session_id AND g.reason = 'CORRECT'
              )
            BEGIN SELECT RAISE(ABORT, 'sets can only be added to a draft workout'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER workout_set_update_requires_draft
            BEFORE UPDATE ON workout_sets
            WHEN NEW.session_id <> OLD.session_id
              OR (COALESCE((SELECT status FROM workout_sessions WHERE id = OLD.session_id), '') <> 'DRAFT'
                  AND NOT EXISTS (
                        SELECT 1 FROM workout_rewrite_guards g
                        WHERE g.session_id = OLD.session_id AND g.reason = 'CORRECT'
                  ))
            BEGIN SELECT RAISE(ABORT, 'sets can only be changed in their draft workout'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER workout_set_delete_requires_draft
            BEFORE DELETE ON workout_sets
            WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = OLD.session_id), '') <> 'DRAFT'
              AND NOT EXISTS (
                    SELECT 1 FROM workout_rewrite_guards g
                    WHERE g.session_id = OLD.session_id AND g.reason IN ('CORRECT','DELETE')
              )
            BEGIN SELECT RAISE(ABORT, 'sets can only be deleted from a draft workout'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER pr_event_parent_consistency_v10
            BEFORE INSERT ON pr_events
            WHEN NOT EXISTS (
                SELECT 1
                FROM workout_sessions s
                JOIN workout_sets ws ON ws.id = NEW.set_id
                WHERE s.id = NEW.session_id
                  AND s.status = 'COMPLETED'
                  AND ws.session_id = NEW.session_id
                  AND ws.exercise_id = NEW.exercise_id
            )
            BEGIN SELECT RAISE(ABORT, 'PR parent session, set and exercise must match'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER pr_event_update_locked_v10
            BEFORE UPDATE ON pr_events
            BEGIN SELECT RAISE(ABORT, 'PR events are immutable'); END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER pr_event_delete_guarded_v10
            BEFORE DELETE ON pr_events
            WHEN NOT EXISTS (
                SELECT 1 FROM workout_rewrite_guards g
                WHERE g.session_id = OLD.session_id AND g.reason IN ('CORRECT','DELETE','REBUILD_PR')
            )
            BEGIN SELECT RAISE(ABORT, 'PR events can only be deleted by a guarded rewrite'); END
            """.trimIndent(),
        )
    }

    /** SQLite triggers provide equivalent guards for upgraded databases whose table
     * declarations cannot gain CHECK clauses without a destructive rebuild. */
    private fun createCoreIntegrityV6(db: SQLiteDatabase) {
        createValueTriggers(
            db,
            table = "profile",
            condition = "NEW.id <> 1 OR NEW.height_cm < 100 OR NEW.height_cm > 250 " +
                "OR NEW.reference_weight_kg < 30 OR NEW.reference_weight_kg > 300 " +
                "OR NEW.waist_cm < 30 OR NEW.waist_cm > 250 " +
                "OR NEW.carb_factor < 0 OR NEW.carb_factor > 10 " +
                "OR NEW.protein_factor < 0.5 OR NEW.protein_factor > 4 " +
                "OR NEW.fat_factor < 0.1 OR NEW.fat_factor > 5",
        )
        createValueTriggers(
            db,
            table = "body_measurements",
            condition = "length(NEW.recorded_date) <> 10 OR NEW.recorded_date NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' " +
                "OR NEW.weight_kg < 30 OR NEW.weight_kg > 300 " +
                "OR (NEW.waist_cm IS NOT NULL AND (NEW.waist_cm < 30 OR NEW.waist_cm > 250))",
        )
        createValueTriggers(
            db,
            table = "meals",
            condition = "length(trim(NEW.commit_id)) < 1 OR length(NEW.commit_id) > 128 " +
                "OR length(NEW.meal_date) <> 10 OR length(trim(NEW.title)) < 1 OR length(NEW.title) > 160 " +
                "OR NEW.kcal < 0 OR NEW.kcal > 50000 OR NEW.carbs_g < 0 OR NEW.carbs_g > 5000 " +
                "OR NEW.protein_g < 0 OR NEW.protein_g > 5000 OR NEW.fat_g < 0 OR NEW.fat_g > 5000 " +
                "OR NEW.evidence_tier NOT IN ('A','B','C','D') OR NEW.confirmed_at < 0",
        )
        createValueTriggers(
            db,
            table = "meal_items",
            condition = "length(trim(NEW.item_name)) < 1 OR length(NEW.item_name) > 80 " +
                "OR NEW.grams < 1 OR NEW.grams > 5000 OR NEW.kcal_per_100g < 0 OR NEW.kcal_per_100g > 1000 " +
                "OR NEW.carbs_per_100g < 0 OR NEW.carbs_per_100g > 100 " +
                "OR NEW.protein_per_100g < 0 OR NEW.protein_per_100g > 100 " +
                "OR NEW.fat_per_100g < 0 OR NEW.fat_per_100g > 100 " +
                "OR NEW.carbs_per_100g + NEW.protein_per_100g + NEW.fat_per_100g > 100.5 " +
                "OR length(trim(NEW.source_name)) < 1 OR length(NEW.source_name) > 120 " +
                "OR NEW.portion_basis NOT IN ('AI_SINGLE_PHOTO','AI_REFERENCE_OBJECT','USER_ESTIMATE','USER_WEIGHT','PACKAGE_WEIGHT','STANDARD_PORTION','SAVED_RECIPE') " +
                "OR NEW.evidence_tier NOT IN ('A','B','C','D') " +
                "OR NEW.calorie_source NOT IN ('DERIVED_FROM_MACROS','LABEL_OR_DATABASE')",
        )
        createValueTriggers(
            db,
            table = "photo_drafts",
            condition = "length(trim(NEW.draft_id)) < 1 OR length(NEW.draft_id) > 128 " +
                "OR length(trim(NEW.commit_id)) < 1 OR length(NEW.commit_id) > 128 " +
                "OR length(NEW.photo_uri) > 2048 OR NEW.state NOT IN " +
                "('PHOTO_SELECTED','ANALYZING','DRAFT_READY','EDITING','READY_TO_CONFIRM','COMMITTING','COMMITTED','ANALYSIS_FAILED','DISCARDED') " +
                "OR length(NEW.payload_json) > 1048576 OR NEW.updated_at < 0",
        )
        createValueTriggers(
            db,
            table = "exercises",
            condition = "length(trim(NEW.name)) < 1 OR length(NEW.name) > 60 " +
                "OR length(trim(NEW.normalized_name)) < 1 OR length(NEW.normalized_name) > 60 " +
                "OR length(trim(NEW.category)) < 1 OR length(NEW.category) > 30 " +
                "OR NEW.is_custom NOT IN (0,1) OR NEW.is_primary NOT IN (0,1) OR NEW.archived NOT IN (0,1) " +
                "OR NEW.tracking_type NOT IN ('WEIGHT_REPS','BODYWEIGHT_REPS','ASSISTED_REPS','DURATION') " +
                "OR NEW.definition_version < 1",
        )
        createValueTriggers(
            db,
            table = "workout_sessions",
            condition = "length(trim(NEW.title)) < 1 OR length(NEW.title) > 160 OR NEW.started_at < 0 " +
                "OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at) " +
                "OR NEW.status NOT IN ('DRAFT','COMPLETED','CANCELLED') " +
                "OR NEW.rest_duration_seconds < 15 OR NEW.rest_duration_seconds > 3600 " +
                "OR (NEW.rest_timer_end_at IS NOT NULL AND NEW.rest_timer_end_at < 0) " +
                "OR ((NEW.status IN ('COMPLETED','CANCELLED')) AND " +
                "(NEW.recorded_local_date IS NULL OR length(NEW.recorded_local_date) <> 10 " +
                "OR NEW.recorded_zone_id IS NULL OR length(trim(NEW.recorded_zone_id)) < 1 OR length(NEW.recorded_zone_id) > 80))",
        )
        createValueTriggers(
            db,
            table = "pr_events",
            condition = "NEW.pr_type NOT IN ('MAX_WEIGHT','WEIGHT_AT_REPS','REP_AT_WEIGHT','ESTIMATED_1RM','SET_VOLUME','MAX_REPS','MIN_ASSISTANCE_AT_REPS','REPS_AT_ASSISTANCE','MAX_DURATION') " +
                "OR length(NEW.bucket_key) > 80 OR NEW.event_kind NOT IN ('BASELINE','BROKEN') " +
                "OR NEW.value < 0 OR NEW.weight_kg < 0 OR NEW.weight_kg > 1000 " +
                "OR NEW.reps < 0 OR NEW.reps > 1000 OR NEW.duration_seconds < 0 OR NEW.duration_seconds > 86400 " +
                "OR NEW.achieved_at < 0",
        )
        createValueTriggers(
            db,
            table = "saved_foods",
            condition = "length(trim(NEW.name)) < 1 OR length(NEW.name) > 80 " +
                "OR length(trim(NEW.normalized_name)) < 1 OR length(trim(NEW.source_name)) < 1 OR length(NEW.source_name) > 120 " +
                "OR NEW.kcal_per_100g < 0 OR NEW.kcal_per_100g > 1000 " +
                "OR NEW.carbs_per_100g < 0 OR NEW.carbs_per_100g > 100 " +
                "OR NEW.protein_per_100g < 0 OR NEW.protein_per_100g > 100 " +
                "OR NEW.fat_per_100g < 0 OR NEW.fat_per_100g > 100 " +
                "OR NEW.calorie_source NOT IN ('DERIVED_FROM_MACROS','LABEL_OR_DATABASE') " +
                "OR NEW.default_grams < 1 OR NEW.default_grams > 5000 OR NEW.is_favorite NOT IN (0,1) " +
                "OR NEW.use_count < 0 OR NEW.last_used_at < 0",
        )
    }

    private fun createValueTriggers(db: SQLiteDatabase, table: String, condition: String) {
        if (!tableExists(db, table)) return
        listOf("INSERT", "UPDATE").forEach { operation ->
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_${table}_values_v6_${operation.lowercase()}
                BEFORE $operation ON $table
                WHEN $condition
                BEGIN SELECT RAISE(ABORT, 'invalid $table values'); END
                """.trimIndent(),
            )
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (tableExists(db, "workout_rewrite_guards") &&
            db.rawQuery("SELECT 1 FROM workout_rewrite_guards LIMIT 1", null).use(Cursor::moveToFirst)
        ) {
            // A correctly scoped guard lives only inside one transaction. Any row
            // visible at open time is stale or externally injected and is unsafe.
            workoutRewriteGuardCleanupDeleteAttemptsForTest += 1
            db.delete("workout_rewrite_guards", null, null)
        }
    }

    private fun seedDefaults(db: SQLiteDatabase) {
        db.insertOrThrow(
            "profile",
            null,
            ContentValues().apply {
                put("id", 1)
                put("height_cm", 175.0)
                put("reference_weight_kg", 80.0)
                put("waist_cm", 88.0)
                put("carb_factor", 3.0)
                put("protein_factor", 1.6)
                put("fat_factor", 0.7)
            },
        )

        defaultExercises.forEach { exercise ->
            db.insertOrThrow(
                "exercises",
                null,
                ContentValues().apply {
                    put("builtin_key", exercise.key)
                    put("name", exercise.name)
                    put("normalized_name", normalizeExerciseName(exercise.name))
                    put("aliases", exercise.aliases.joinToString("|"))
                    put("category", exercise.category)
                    put("is_custom", 0)
                    put("is_primary", if (exercise.primary) 1 else 0)
                    put("tracking_type", exercise.trackingType.name)
                    put("definition_version", 1)
                },
            )
        }
    }

    fun getProfile(): UserProfile = readableDatabase.query(
        "profile",
        null,
        "id = 1",
        null,
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use UserProfile()
        val profile = UserProfile(
            heightCm = cursor.double("height_cm"),
            referenceWeightKg = cursor.double("reference_weight_kg"),
            waistCm = cursor.double("waist_cm"),
            carbFactor = cursor.double("carb_factor"),
            proteinFactor = cursor.double("protein_factor"),
            fatFactor = cursor.double("fat_factor"),
        )
        if (profile.validationError() == null) profile else UserProfile()
    }

    fun updateProfile(profile: UserProfile) {
        profile.validationError()?.let { throw IllegalArgumentException(it) }
        val values = ContentValues().apply {
            put("id", 1)
            put("height_cm", profile.heightCm)
            put("reference_weight_kg", profile.referenceWeightKg)
            put("waist_cm", profile.waistCm)
            put("carb_factor", profile.carbFactor)
            put("protein_factor", profile.proteinFactor)
            put("fat_factor", profile.fatFactor)
        }
        val db = writableDatabase
        val updated = db.update(
            "profile",
            values,
            "id = 1",
            null,
        )
        if (updated == 0) db.insertOrThrow("profile", null, values)
    }

    fun saveBodyMeasurement(measurement: BodyMeasurement, formCommitId: String? = null): Long {
        require(!measurement.isXiaomi) { "小米原始记录不能作为手工记录覆盖保存" }
        require(formCommitId == null || (formCommitId.isNotBlank() && formCommitId.length <= 128)) {
            "身体表单提交标识无效"
        }
        if (formCommitId == null) {
            measurement.validationError()?.let { throw IllegalArgumentException(it) }
        }
        val db = writableDatabase
        val committedAtMillis = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            formCommitId?.let { currentFormId ->
                pruneFormReceiptTable(
                    db = db,
                    tableName = "body_measurement_form_commits",
                    timestampColumn = "committed_at",
                    nowMillis = committedAtMillis,
                    pruneExpired = true,
                    protectedFormId = currentFormId,
                )
            }
            val alreadyCommitted = formCommitId?.let { bodyMeasurementCommitId(db, it) }
            val id = if (alreadyCommitted != null) {
                alreadyCommitted
            } else {
                // Validation belongs to the first write only. A durable form
                // receipt must win over a changed or newly-future retry payload.
                measurement.validationError()?.let { throw IllegalArgumentException(it) }
                val existingForDate = bodyMeasurementIdForDate(db, measurement.date)
                val values = ContentValues().apply {
                    put("recorded_date", measurement.date.toString())
                    put("weight_kg", measurement.weightKg)
                    if (measurement.waistCm == null) putNull("waist_cm") else put("waist_cm", measurement.waistCm)
                }
                val persistedId = if (measurement.id > 0L) {
                    val persisted = db.query(
                        "body_measurements",
                        arrayOf("id"),
                        "id = ?",
                        arrayOf(measurement.id.toString()),
                        null,
                        null,
                        null,
                        "1",
                    ).use { it.moveToFirst() }
                    require(persisted) { "身体记录不存在或已被删除" }
                    require(existingForDate == null || existingForDate == measurement.id) { "该日期已有身体记录，请直接编辑那一条" }
                    require(db.update("body_measurements", values, "id = ?", arrayOf(measurement.id.toString())) == 1)
                    measurement.id
                } else if (existingForDate != null) {
                    require(db.update("body_measurements", values, "id = ?", arrayOf(existingForDate.toString())) == 1)
                    existingForDate
                } else {
                    db.insertOrThrow("body_measurements", null, values)
                }
                formCommitId?.let { formId ->
                    db.insertOrThrow(
                        "body_measurement_form_commits",
                        null,
                        ContentValues().apply {
                            put("form_id", formId)
                            put("measurement_id", persistedId)
                            put("committed_at", committedAtMillis)
                            put("cleanup_confirmed", 0)
                        },
                    )
                    pruneFormReceiptTable(
                        db = db,
                        tableName = "body_measurement_form_commits",
                        timestampColumn = "committed_at",
                        nowMillis = committedAtMillis,
                        pruneExpired = true,
                        protectedFormId = formId,
                    )
                }
                persistedId
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    fun addBodyMeasurement(measurement: BodyMeasurement): Long = saveBodyMeasurement(measurement)

    fun bodyMeasurementCommitId(formId: String): Long? {
        require(formId.isNotBlank() && formId.length <= 128)
        return bodyMeasurementCommitId(readableDatabase, formId)
    }

    private fun bodyMeasurementCommitId(db: SQLiteDatabase, formId: String): Long? = db.rawQuery(
        "SELECT measurement_id FROM body_measurement_form_commits WHERE form_id = ?",
        arrayOf(formId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    internal fun pruneBodyMeasurementCommitReceipts(protectedFormId: String? = null) {
        require(protectedFormId == null || (protectedFormId.isNotBlank() && protectedFormId.length <= 128)) {
            "身体表单提交标识无效"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            pruneFormReceiptTable(
                db = db,
                tableName = "body_measurement_form_commits",
                timestampColumn = "committed_at",
                nowMillis = System.currentTimeMillis(),
                pruneExpired = true,
                protectedFormId = protectedFormId,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun reconcileBodyMeasurementCommitReceipts(protectedRawFormId: String?) {
        require(protectedRawFormId == null ||
            (protectedRawFormId.isNotBlank() && protectedRawFormId.length <= 128)) {
            "身体表单提交标识无效"
        }
        val db = writableDatabase
        val nowMillis = System.currentTimeMillis()
        db.beginTransaction()
        try {
            confirmFormReceiptCleanupExcept(
                db = db,
                tableName = "body_measurement_form_commits",
                protectedFormId = protectedRawFormId,
            )
            pruneFormReceiptTable(
                db = db,
                tableName = "body_measurement_form_commits",
                timestampColumn = "committed_at",
                nowMillis = nowMillis,
                pruneExpired = true,
                protectedFormId = protectedRawFormId,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun confirmBodyMeasurementRawFormCleanup(formId: String) {
        require(formId.isNotBlank() && formId.length <= 128) { "身体表单提交标识无效" }
        val db = writableDatabase
        val nowMillis = System.currentTimeMillis()
        db.beginTransaction()
        try {
            confirmFormReceiptCleanup(
                db = db,
                tableName = "body_measurement_form_commits",
                formId = formId,
            )
            // A recent just-cleared receipt must survive capacity pressure from
            // conservative v9 rows; an already-expired one may now be removed.
            pruneFormReceiptTable(
                db = db,
                tableName = "body_measurement_form_commits",
                timestampColumn = "committed_at",
                nowMillis = nowMillis,
                pruneExpired = true,
                protectedFormId = formId,
                protectCurrentFromExpiry = false,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteBodyMeasurement(measurementId: Long): Boolean {
        require(measurementId > 0L) { "身体记录不存在" }
        return writableDatabase.delete("body_measurements", "id = ?", arrayOf(measurementId.toString())) == 1
    }

    private fun bodyMeasurementIdForDate(db: SQLiteDatabase, date: LocalDate): Long? = db.query(
        "body_measurements",
        arrayOf("id"),
        "recorded_date = ?",
        arrayOf(date.toString()),
        null,
        null,
        "id DESC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun listBodyMeasurements(): List<BodyMeasurement> = readableDatabase.query(
        "body_measurements",
        null,
        null,
        null,
        null,
        null,
        "recorded_date ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching {
                    BodyMeasurement(
                        id = cursor.long("id"),
                        date = LocalDate.parse(cursor.string("recorded_date")),
                        weightKg = cursor.double("weight_kg"),
                        waistCm = if (cursor.isNull(cursor.getColumnIndexOrThrow("waist_cm"))) null else cursor.double("waist_cm"),
                    )
                }.getOrNull()?.takeIf {
                    it.id > 0L && it.structuralValidationError() == null
                }?.let(::add)
            }
        }
    }

    /**
     * Persists a draft and, for a manual editor conversion, its durable receipt
     * in one SQLite transaction. A retained SharedPreferences form can then be
     * suppressed even after the resulting draft is later committed or discarded.
     *
     * @return false only when [manualFormId] was already converted earlier.
     */
    fun saveDraft(draft: MealDraft, manualFormId: String? = null): Boolean {
        require(manualFormId == null || (manualFormId.isNotBlank() && manualFormId.length <= 128)) {
            "手工表单提交标识无效"
        }
        val db = writableDatabase
        val convertedAtMillis = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            manualFormId?.let { currentFormId ->
                pruneFormReceiptTable(
                    db = db,
                    tableName = "manual_food_form_conversions",
                    timestampColumn = "converted_at",
                    nowMillis = convertedAtMillis,
                    pruneExpired = true,
                    protectedFormId = currentFormId,
                )
            }
            if (manualFormId != null && manualFoodFormConversionDraftId(db, manualFormId) != null) {
                db.setTransactionSuccessful()
                return false
            }
            val rowId = db.insertWithOnConflict(
                "photo_drafts",
                null,
                ContentValues().apply {
                    put("draft_id", draft.id)
                    put("commit_id", draft.commitId)
                    put("photo_uri", draft.photoUri)
                    put("state", draft.state.name)
                    put("payload_json", DraftCodec.encode(draft))
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            check(rowId != -1L) { "无法保存饮食草稿" }
            db.delete("photo_drafts", "draft_id <> ?", arrayOf(draft.id))
            manualFormId?.let { formId ->
                db.insertOrThrow(
                    "manual_food_form_conversions",
                    null,
                    ContentValues().apply {
                        put("form_id", formId)
                        put("draft_id", draft.id)
                        put("converted_at", convertedAtMillis)
                        put("cleanup_confirmed", 0)
                    },
                )
                pruneFormReceiptTable(
                    db = db,
                    tableName = "manual_food_form_conversions",
                    timestampColumn = "converted_at",
                    nowMillis = convertedAtMillis,
                    pruneExpired = true,
                    protectedFormId = formId,
                )
            }
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun manualFoodFormConversionDraftId(formId: String): String? {
        require(formId.isNotBlank() && formId.length <= 128)
        return manualFoodFormConversionDraftId(readableDatabase, formId)
    }

    private fun manualFoodFormConversionDraftId(db: SQLiteDatabase, formId: String): String? = db.rawQuery(
        "SELECT draft_id FROM manual_food_form_conversions WHERE form_id = ?",
        arrayOf(formId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    internal fun pruneManualFoodFormConversionReceipts(protectedFormId: String? = null) {
        require(protectedFormId == null || (protectedFormId.isNotBlank() && protectedFormId.length <= 128)) {
            "手工表单提交标识无效"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            pruneFormReceiptTable(
                db = db,
                tableName = "manual_food_form_conversions",
                timestampColumn = "converted_at",
                nowMillis = System.currentTimeMillis(),
                pruneExpired = true,
                protectedFormId = protectedFormId,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun reconcileManualFoodFormConversionReceipts(protectedRawFormId: String?) {
        require(protectedRawFormId == null ||
            (protectedRawFormId.isNotBlank() && protectedRawFormId.length <= 128)) {
            "手工表单提交标识无效"
        }
        val db = writableDatabase
        val nowMillis = System.currentTimeMillis()
        db.beginTransaction()
        try {
            confirmFormReceiptCleanupExcept(
                db = db,
                tableName = "manual_food_form_conversions",
                protectedFormId = protectedRawFormId,
            )
            pruneFormReceiptTable(
                db = db,
                tableName = "manual_food_form_conversions",
                timestampColumn = "converted_at",
                nowMillis = nowMillis,
                pruneExpired = true,
                protectedFormId = protectedRawFormId,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun confirmManualFoodRawFormCleanup(formId: String) {
        require(formId.isNotBlank() && formId.length <= 128) { "手工表单提交标识无效" }
        val db = writableDatabase
        val nowMillis = System.currentTimeMillis()
        db.beginTransaction()
        try {
            confirmFormReceiptCleanup(
                db = db,
                tableName = "manual_food_form_conversions",
                formId = formId,
            )
            pruneFormReceiptTable(
                db = db,
                tableName = "manual_food_form_conversions",
                timestampColumn = "converted_at",
                nowMillis = nowMillis,
                pruneExpired = true,
                protectedFormId = formId,
                protectCurrentFromExpiry = false,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun latestDraft(): MealDraft? {
        val stored = readableDatabase.query(
            "photo_drafts",
            arrayOf("draft_id", "payload_json"),
            "state NOT IN (?, ?)",
            arrayOf(DraftState.COMMITTED.name, DraftState.DISCARDED.name),
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null }
            ?: return null
        return runCatching { DraftCodec.decode(stored.second) }.getOrElse {
            discardDraft(stored.first)
            null
        }
    }

    fun discardDraft(draftId: String) {
        writableDatabase.delete("photo_drafts", "draft_id = ?", arrayOf(draftId))
    }

    fun draftPhotoUris(): Set<String> = readableDatabase.query(
        "photo_drafts",
        arrayOf("photo_uri"),
        null,
        null,
        null,
        null,
        null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun commitDraft(draft: MealDraft, mealDate: LocalDate): Long {
        draft.commitValidationError()?.let { throw IllegalArgumentException(it) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingId = db.rawQuery(
                "SELECT id FROM meals WHERE commit_id = ?",
                arrayOf(draft.commitId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (existingId != null) {
                db.delete("photo_drafts", "draft_id = ?", arrayOf(draft.id))
                db.setTransactionSuccessful()
                return existingId
            }

            // A durable editor can outlive a wall-clock or time-zone change.  The
            // UI's earlier date check is therefore only advisory: sample the
            // current local day again inside the write transaction so a stale
            // draft can never create a future ledger entry.
            val currentLocalDate = LocalDate.now(ZoneId.systemDefault())
            require(!mealDate.isAfter(currentLocalDate)) { "餐食不能记录到未来日期" }

            val originalConfirmedAt = draft.replacesMealId?.let { mealId ->
                val confirmedAt = db.rawQuery(
                    "SELECT confirmed_at FROM meals WHERE id = ?",
                    arrayOf(mealId.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                requireNotNull(confirmedAt) { "要更正的餐食已不存在" }
            }

            val replacedFoodIdentities = draft.replacesMealId
                ?.let { replacedId -> foodIdentitiesForMeal(db, replacedId) }
                .orEmpty()

            val total = draft.total
            val committedAt = System.currentTimeMillis()
            val confirmedAt = originalConfirmedAt ?: committedAt
            val mealId = db.insertOrThrow(
                "meals",
                null,
                ContentValues().apply {
                    put("commit_id", draft.commitId)
                    put("meal_date", mealDate.toString())
                    put(
                        "title",
                        if (draft.analysisMode == AnalysisMode.MANUAL) {
                            "手工记录 · ${draft.items.firstOrNull()?.name.orEmpty()}".trimEnd(' ', '·')
                        } else {
                            "拍照记录"
                        },
                    )
                    put("kcal", total.kcal)
                    put("carbs_g", total.carbsG)
                    put("protein_g", total.proteinG)
                    put("fat_g", total.fatG)
                    put("evidence_tier", draft.evidenceTier.name)
                    put("confirmed_at", confirmedAt)
                },
            )
            draft.items.forEach { item ->
                db.insertOrThrow(
                    "meal_items",
                    null,
                    ContentValues().apply {
                        put("meal_id", mealId)
                        put("item_name", item.name)
                        put("grams", item.grams)
                        put("kcal_per_100g", item.per100g.kcal)
                        put("carbs_per_100g", item.per100g.carbsG)
                        put("protein_per_100g", item.per100g.proteinG)
                        put("fat_per_100g", item.per100g.fatG)
                        put("source_name", item.sourceName)
                        put("portion_basis", item.portionBasis.name)
                        put("evidence_tier", item.evidenceTier.name)
                        put("calorie_source", item.calorieSource.name)
                    },
                )
                rememberFood(db, item, committedAt)
            }
            val newFoodIdentities = draft.items.mapNotNull(::foodIdentity)
            draft.replacesMealId?.let { replacedId ->
                check(db.delete("meals", "id = ?", arrayOf(replacedId.toString())) == 1) {
                    "无法原子替换原餐食"
                }
            }
            reconcileSavedFoodUsage(db, (replacedFoodIdentities + newFoodIdentities).toSet())
            db.delete("photo_drafts", "draft_id = ?", arrayOf(draft.id))
            db.setTransactionSuccessful()
            return mealId
        } finally {
            db.endTransaction()
        }
    }

    fun nutritionForDate(date: LocalDate): Nutrition = readableDatabase.rawQuery(
        """
        SELECT COALESCE(SUM(kcal), 0), COALESCE(SUM(carbs_g), 0),
               COALESCE(SUM(protein_g), 0), COALESCE(SUM(fat_g), 0)
        FROM meals
        WHERE meal_date = ?
          AND kcal BETWEEN 0 AND 50000
          AND carbs_g BETWEEN 0 AND 5000
          AND protein_g BETWEEN 0 AND 5000
          AND fat_g BETWEEN 0 AND 5000
        """.trimIndent(),
        arrayOf(date.toString()),
    ).use { cursor ->
        cursor.moveToFirst()
        Nutrition(cursor.getDouble(0), cursor.getDouble(1), cursor.getDouble(2), cursor.getDouble(3))
    }

    /** One read-only statement keeps each meal and its foods in the same SQLite snapshot. */
    fun mealsForDate(date: LocalDate): List<MealRecord> = readableDatabase.rawQuery(
        """
        SELECT m.*, $MEAL_FOOD_SELECT_COLUMNS
        FROM meals AS m INDEXED BY idx_meals_date_confirmed_v9
        LEFT JOIN meal_items AS i INDEXED BY idx_meal_items_meal_id_id_v9 ON i.meal_id = m.id
        WHERE m.meal_date = ?
        ORDER BY m.confirmed_at DESC, m.id DESC, i.id ASC
        """.trimIndent(),
        arrayOf(date.toString()),
    ).use { cursor ->
        val meals = linkedMapOf<Long, MealRecord>()
        val items = mutableMapOf<Long, MutableList<MealFoodRecord>>()
        val incompleteMealIds = mutableSetOf<Long>()
        while (cursor.moveToNext()) {
            val mealId = cursor.long("id")
            if (mealId !in meals) {
                meals[mealId] = cursor.toMealRecordOrNull() ?: continue
            }
            // A LEFT JOIN with no child rows represents missing historical details,
            // not a zero-nutrition food. Invalid child rows are explicitly signalled.
            if (cursor.nullableLong("food_id") != null) {
                val item = cursor.toMealFoodRecordOrNull()
                if (item == null) incompleteMealIds.add(mealId)
                else items.getOrPut(mealId) { mutableListOf() }.add(item)
            }
        }
        meals.values.map { meal ->
            meal.copy(
                items = items[meal.id]?.toList().orEmpty(),
                itemDetailsIncomplete = meal.id in incompleteMealIds,
            )
        }
    }

    fun deleteMeal(mealId: Long) {
        require(mealId > 0) { "餐食记录不存在" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val identities = foodIdentitiesForMeal(db, mealId)
            if (db.delete("meals", "id = ?", arrayOf(mealId.toString())) == 1) {
                reconcileSavedFoodUsage(db, identities.toSet())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun draftFromMeal(mealId: Long, targetDate: LocalDate, replaceOriginal: Boolean): MealDraft {
        require(mealId > 0L) { "餐食记录不存在" }
        val meal = readableDatabase.query(
            "meals",
            null,
            "id = ?",
            arrayOf(mealId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.toMealRecordOrNull()
        } ?: throw IllegalArgumentException("餐食记录不存在")

        val storedItems = readableDatabase.rawQuery(
            "SELECT $MEAL_FOOD_SELECT_COLUMNS FROM meal_items AS i INDEXED BY idx_meal_items_meal_id_id_v9 " +
                "WHERE i.meal_id = ? ORDER BY i.id ASC",
            arrayOf(mealId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // Editing a subset would replace the original total with an
                    // incomplete meal. Reads may warn, but draft creation fails closed.
                    add(requireNotNull(cursor.toMealFoodRecordOrNull()) { "餐食明细不完整，暂不能更正或复制" })
                }
            }
        }
        require(storedItems.isNotEmpty()) { "餐食没有可编辑的食物明细" }
        val items = storedItems.map { item ->
            val exact = item.portionBasis == PortionBasis.USER_WEIGHT || item.portionBasis == PortionBasis.PACKAGE_WEIGHT
            FoodDraftItem(
                name = item.name,
                grams = item.grams,
                gramsMin = if (exact) item.grams else (item.grams * 0.8).coerceAtLeast(1.0),
                gramsMax = if (exact) item.grams else item.grams * 1.2,
                per100g = item.per100g,
                sourceName = item.sourceName,
                portionBasis = item.portionBasis,
                evidenceTier = item.evidenceTier,
                userModified = true,
                calorieSource = item.calorieSource,
            )
        }
        return MealDraft(
            photoUri = "",
            state = DraftState.EDITING,
            items = items,
            evidenceTier = meal.evidenceTier,
            evidenceReason = if (replaceOriginal) "正在更正已入账餐食；确认后会原子替换原记录" else "复制自已入账餐食；修改并确认前不会重复计入",
            unresolvedFlags = emptySet(),
            providerLabel = if (replaceOriginal) "餐食更正草稿 · 尚未替换原记录" else "餐食复制草稿 · 尚未计入账本",
            analysisMode = AnalysisMode.MANUAL,
            targetDate = if (replaceOriginal) meal.date else targetDate,
            replacesMealId = if (replaceOriginal) meal.id else null,
        )
    }

    fun listSavedFoods(): List<SavedFood> = readableDatabase.query(
        "saved_foods",
        null,
        null,
        null,
        null,
        null,
        "is_favorite DESC, last_used_at DESC, id DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching {
                    val favorite = cursor.int("is_favorite")
                    if (favorite !in 0..1) return@runCatching null
                    SavedFood(
                        id = cursor.long("id"),
                        name = cursor.string("name"),
                        per100g = Nutrition(
                            cursor.double("kcal_per_100g"),
                            cursor.double("carbs_per_100g"),
                            cursor.double("protein_per_100g"),
                            cursor.double("fat_per_100g"),
                        ),
                        sourceName = cursor.string("source_name"),
                        calorieSource = enumValueOrNull<CalorieSource>(cursor.string("calorie_source"))
                            ?: return@runCatching null,
                        defaultGrams = cursor.double("default_grams"),
                        isFavorite = favorite == 1,
                        useCount = cursor.int("use_count"),
                        lastUsedAtMillis = cursor.long("last_used_at"),
                    )
                }.getOrNull()?.takeIf { food ->
                    food.name.isNotBlank() && food.per100g.let {
                        listOf(it.kcal, it.carbsG, it.proteinG, it.fatG).all(Double::isFinite)
                    } && food.defaultGrams.isFinite() && food.defaultGrams in 1.0..5_000.0 &&
                        food.useCount >= 0 && food.lastUsedAtMillis >= 0L
                }?.let(::add)
            }
        }
    }

    fun setFoodFavorite(foodId: Long, favorite: Boolean): Boolean = writableDatabase.update(
        "saved_foods",
        ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) },
        "id = ?",
        arrayOf(foodId.toString()),
    ) == 1

    private fun rememberFood(db: SQLiteDatabase, item: FoodDraftItem, usedAt: Long) {
        val normalized = item.name.trim().lowercase()
        val normalizedSource = item.sourceName.trim().lowercase()
        if (normalized.isBlank()) return
        val existing = db.query(
            "saved_foods",
            arrayOf("id", "use_count"),
            "normalized_name = ? AND normalized_source = ?",
            arrayOf(normalized, normalizedSource),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getInt(1) else null }
        val values = ContentValues().apply {
            put("normalized_name", normalized)
            put("normalized_source", normalizedSource)
            put("name", item.name.trim())
            put("kcal_per_100g", item.per100g.kcal)
            put("carbs_per_100g", item.per100g.carbsG)
            put("protein_per_100g", item.per100g.proteinG)
            put("fat_per_100g", item.per100g.fatG)
            put("source_name", item.sourceName)
            put("calorie_source", item.calorieSource.name)
            put("default_grams", item.grams)
            put("last_used_at", usedAt)
            put("use_count", (existing?.second ?: 0) + 1)
        }
        if (existing == null) {
            values.put("is_favorite", 0)
            db.insertOrThrow("saved_foods", null, values)
        } else {
            check(db.update("saved_foods", values, "id = ?", arrayOf(existing.first.toString())) == 1)
        }
    }

    private fun foodIdentitiesForMeal(db: SQLiteDatabase, mealId: Long): List<Pair<String, String>> =
        db.query(
            "meal_items",
            arrayOf("item_name", "source_name"),
            "meal_id = ?",
            arrayOf(mealId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val normalizedName = cursor.getString(0).trim().lowercase()
                    val normalizedSource = cursor.getString(1).trim().lowercase()
                    if (normalizedName.isNotBlank()) add(normalizedName to normalizedSource)
                }
            }
        }

    private fun foodIdentity(item: FoodDraftItem): Pair<String, String>? {
        val normalizedName = item.name.trim().lowercase()
        if (normalizedName.isBlank()) return null
        return normalizedName to item.sourceName.trim().lowercase()
    }

    /** Recompute affected identities from active meal items, rather than merely
     * applying a delta. This also repairs an alpha11 count that an earlier meal
     * correction may already have inflated. Rows remain at zero so favourites
     * survive edits and deletions. */
    private fun reconcileSavedFoodUsage(
        db: SQLiteDatabase,
        identities: Set<Pair<String, String>>,
    ) {
        if (identities.isEmpty()) return
        val counts = identities.associateWith { 0 }.toMutableMap()
        db.query(
            "meal_items",
            arrayOf("item_name", "source_name"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val identity = cursor.getString(0).trim().lowercase() to
                    cursor.getString(1).trim().lowercase()
                if (identity in counts) counts[identity] = counts.getValue(identity) + 1
            }
        }
        counts.forEach { (identity, count) ->
            db.update(
                "saved_foods",
                ContentValues().apply { put("use_count", count) },
                "normalized_name = ? AND normalized_source = ?",
                arrayOf(identity.first, identity.second),
            )
        }
    }

    fun listExercises(includeArchived: Boolean = false): List<Exercise> =
        listExercises(readableDatabase, includeArchived)

    private fun listExercises(db: SQLiteDatabase, includeArchived: Boolean): List<Exercise> {
        val selection = if (includeArchived) null else "archived = 0"
        return db.query(
            "exercises",
            null,
            selection,
            null,
            null,
            null,
            "is_primary DESC, is_custom ASC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.toExerciseOrNull()?.let(::add)
            }
        }
    }

    fun addCustomExercise(
        name: String,
        category: String,
        trackingType: TrackingType,
        isPrimary: Boolean,
    ): Exercise {
        val displayName = name.trim()
        require(displayName.codePointLength() in 1..MAX_EXERCISE_NAME_CODE_POINTS) { "动作名称必须为 1–60 个字符" }
        val normalizedName = normalizeExerciseName(displayName)
        val displayCategory = category.trim()
        require(displayCategory.codePointLength() in 1..MAX_EXERCISE_CATEGORY_CODE_POINTS) { "动作类别必须为 1–30 个字符" }
        val db = writableDatabase
        val conflictName = exerciseNameForNormalized(db, normalizedName)
        require(conflictName == null) { "已存在同名动作“$conflictName”；大小写及连续空格视为同名" }
        val id = try {
            db.insertOrThrow(
                "exercises",
                null,
                ContentValues().apply {
                    putNull("builtin_key")
                    put("name", displayName)
                    put("normalized_name", normalizedName)
                    put("aliases", "")
                    put("category", displayCategory)
                    put("is_custom", 1)
                    put("is_primary", if (isPrimary) 1 else 0)
                    put("tracking_type", trackingType.name)
                    put("definition_version", 1)
                    put("archived", 0)
                },
            )
        } catch (_: SQLiteConstraintException) {
            throw IllegalArgumentException("已存在同名动作；大小写及连续空格视为同名")
        }
        // Every returned field is part of the row that insertOrThrow just committed. Avoid a
        // post-insert list query here: a refresh/read failure must never turn a durable insert
        // into an apparent create failure that invites a duplicate retry.
        return Exercise(
            id = id,
            name = displayName,
            category = displayCategory,
            aliases = emptyList(),
            isCustom = true,
            isPrimary = isPrimary,
            trackingType = trackingType,
            definitionVersion = 1,
            isArchived = false,
        )
    }

    private fun exerciseNameForNormalized(
        db: SQLiteDatabase,
        normalizedName: String,
        excludingId: Long? = null,
    ): String? = db.query(
        "exercises",
        arrayOf("name"),
        "normalized_name = ? COLLATE NOCASE" + if (excludingId == null) "" else " AND id <> ?",
        if (excludingId == null) arrayOf(normalizedName) else arrayOf(normalizedName, excludingId.toString()),
        null,
        null,
        "id ASC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun updateCustomExercise(
        exerciseId: Long,
        name: String,
        category: String,
        isPrimary: Boolean,
    ): Exercise {
        require(exerciseId > 0L) { "自定义动作不存在" }
        val displayName = name.trim()
        require(displayName.codePointLength() in 1..MAX_EXERCISE_NAME_CODE_POINTS) { "动作名称必须为 1–60 个字符" }
        val normalizedName = normalizeExerciseName(displayName)
        val displayCategory = category.trim()
        require(displayCategory.codePointLength() in 1..MAX_EXERCISE_CATEGORY_CODE_POINTS) { "动作类别必须为 1–30 个字符" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = customExercise(db, exerciseId)
                ?: throw IllegalArgumentException("只能编辑自定义动作")
            check(existing.definitionVersion < Int.MAX_VALUE) { "动作定义版本已达到上限" }
            val conflictName = exerciseNameForNormalized(db, normalizedName, excludingId = exerciseId)
            require(conflictName == null) { "已存在同名动作“$conflictName”；大小写及连续空格视为同名" }
            val nextVersion = existing.definitionVersion + 1
            val effectivePrimary = isPrimary && !existing.isArchived
            val updated = try {
                db.update(
                    "exercises",
                    ContentValues().apply {
                        put("name", displayName)
                        put("normalized_name", normalizedName)
                        put("category", displayCategory)
                        put("is_primary", if (effectivePrimary) 1 else 0)
                        put("definition_version", nextVersion)
                    },
                    "id = ? AND is_custom = 1 AND definition_version = ?",
                    arrayOf(exerciseId.toString(), existing.definitionVersion.toString()),
                )
            } catch (_: SQLiteConstraintException) {
                throw IllegalArgumentException("已存在同名动作；大小写及连续空格视为同名")
            }
            check(updated == 1) { "自定义动作状态已变化，请刷新后重试" }
            val result = requireNotNull(customExercise(db, exerciseId)) { "自定义动作更新后无法读取" }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun setCustomExerciseArchived(exerciseId: Long, archived: Boolean): Exercise {
        require(exerciseId > 0L) { "自定义动作不存在" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = customExercise(db, exerciseId)
                ?: throw IllegalArgumentException("只能归档或恢复自定义动作")
            val changed = existing.isArchived != archived || existing.isPrimary
            if (changed) {
                check(existing.definitionVersion < Int.MAX_VALUE) { "动作定义版本已达到上限" }
                check(
                    db.update(
                        "exercises",
                        ContentValues().apply {
                            put("archived", if (archived) 1 else 0)
                            put("is_primary", 0)
                            put("definition_version", existing.definitionVersion + 1)
                        },
                        "id = ? AND is_custom = 1 AND definition_version = ?",
                        arrayOf(exerciseId.toString(), existing.definitionVersion.toString()),
                    ) == 1,
                ) { "自定义动作状态已变化，请刷新后重试" }
            }
            val result = requireNotNull(customExercise(db, exerciseId)) { "自定义动作更新后无法读取" }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun customExercise(db: SQLiteDatabase, exerciseId: Long): Exercise? = db.query(
        "exercises",
        null,
        "id = ? AND is_custom = 1",
        arrayOf(exerciseId.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toExerciseOrNull() else null }

    fun setExercisePrimary(exerciseId: Long, primary: Boolean) {
        require(exerciseId > 0L) { "动作不存在" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val archived = db.rawQuery(
                "SELECT archived FROM exercises WHERE id = ? LIMIT 1",
                arrayOf(exerciseId.toString()),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "动作不存在" }
                cursor.getInt(0)
            }
            require(archived == 0) { "已归档动作不能设为常用动作" }
            check(
                db.update(
                    "exercises",
                    ContentValues().apply { put("is_primary", if (primary) 1 else 0) },
                    "id = ? AND archived = 0",
                    arrayOf(exerciseId.toString()),
                ) == 1,
            ) { "动作状态已变化，请刷新后重试" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun startWorkout(title: String): WorkoutSession {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = activeWorkout(db)
            if (existing != null) {
                db.setTransactionSuccessful()
                existing
            } else {
                val now = System.currentTimeMillis()
                val safeTitle = title.trim().ifBlank { "力量训练" }.take(80)
                val id = db.insertOrThrow(
                    "workout_sessions",
                    null,
                    ContentValues().apply {
                        put("title", safeTitle)
                        put("started_at", now)
                        putNull("ended_at")
                        put("status", "DRAFT")
                    },
                )
                db.setTransactionSuccessful()
                WorkoutSession(id = id, title = safeTitle, startedAtMillis = now)
            }
        } finally {
            db.endTransaction()
        }
    }

    fun activeWorkout(): WorkoutSession? = activeWorkout(readableDatabase)

    /** Reconciles the only persisted timer state that cannot be trusted after a
     * process restart: a wall-clock deadline farther away than its configured
     * duration. The caller supplies wall time so rollback behavior is testable. */
    fun activeWorkoutWithRestTimerReconciled(
        nowMillis: Long = System.currentTimeMillis(),
    ): WorkoutSession? {
        val session = activeWorkout() ?: return null
        return if (persistedRestTimerRequiresReset(session, nowMillis)) {
            clearRestTimer(session.id)
        } else {
            session
        }
    }

    private fun activeWorkout(db: SQLiteDatabase): WorkoutSession? = db.query(
        "workout_sessions",
        null,
        "status = 'DRAFT'",
        null,
        null,
        null,
        "started_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toWorkoutSessionOrNull()
    }

    fun startRestTimer(sessionId: Long, durationSeconds: Int): WorkoutSession {
        require(durationSeconds in 15..3_600) { "休息时间必须在 15–3600 秒" }
        val db = writableDatabase
        val updated = db.update(
            "workout_sessions",
            ContentValues().apply {
                put("rest_timer_end_at", System.currentTimeMillis() + durationSeconds * 1_000L)
                put("rest_duration_seconds", durationSeconds)
            },
            "id = ? AND status = 'DRAFT'",
            arrayOf(sessionId.toString()),
        )
        require(updated == 1) { "只能为进行中的训练设置休息计时" }
        return requireNotNull(activeWorkout(db)?.takeIf { it.id == sessionId })
    }

    fun clearRestTimer(sessionId: Long): WorkoutSession {
        val db = writableDatabase
        val updated = db.update(
            "workout_sessions",
            ContentValues().apply { putNull("rest_timer_end_at") },
            "id = ? AND status = 'DRAFT'",
            arrayOf(sessionId.toString()),
        )
        require(updated == 1) { "只能清除进行中训练的休息计时" }
        return requireNotNull(activeWorkout(db)?.takeIf { it.id == sessionId })
    }

    fun addWorkoutSet(set: WorkoutSet): WorkoutSet {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val canonical = canonicalWorkoutSet(set)
            validateWorkoutSet(canonical)
            val existing = workoutSetByCommitId(db, canonical.commitId)
            val inserted = if (existing != null) {
                requireSameCommittedSet(existing, canonical)
                existing
            } else {
                val ordered = canonical.copy(setOrder = nextSetOrder(db, canonical.sessionId, canonical.exerciseId))
                insertWorkoutSet(db, ordered)
            }
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    fun addWorkoutSets(sets: List<WorkoutSet>): List<WorkoutSet> {
        if (sets.isEmpty()) return emptyList()
        val canonicalSets = sets.map(::canonicalWorkoutSet)
        canonicalSets.forEach(::validateWorkoutSet)
        require(canonicalSets.map { it.commitId }.distinct().size == canonicalSets.size) {
            "同一批训练组的提交标识不能重复"
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val nextOrders = mutableMapOf<Pair<Long, Long>, Int>()
            val inserted = canonicalSets.map { set ->
                val existing = workoutSetByCommitId(db, set.commitId)
                if (existing != null) {
                    requireSameCommittedSet(existing, set)
                    existing
                } else {
                    val key = set.sessionId to set.exerciseId
                    val order = nextOrders.getOrPut(key) { nextSetOrder(db, set.sessionId, set.exerciseId) }
                    nextOrders[key] = order + 1
                    insertWorkoutSet(db, set.copy(setOrder = order))
                }
            }
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    private fun insertWorkoutSet(db: SQLiteDatabase, set: WorkoutSet): WorkoutSet {
        validateWorkoutSet(set)
        val id = db.insertWithOnConflict(
            "workout_sets",
            null,
            ContentValues().apply {
                put("session_id", set.sessionId)
                put("exercise_id", set.exerciseId)
                put("set_order", set.setOrder)
                put("load_grams", set.loadGrams)
                put("reps", set.reps)
                put("duration_seconds", set.durationSeconds)
                put("completed", if (set.completed) 1 else 0)
                put("is_warmup", if (set.isWarmup) 1 else 0)
                if (set.rpe == null) putNull("rpe") else put("rpe", set.rpe)
                if (set.rir == null) putNull("rir") else put("rir", set.rir)
                put("note", set.note.trim())
                if (set.supersetId == null) putNull("superset_id") else put("superset_id", set.supersetId)
                if (set.batchId == null) putNull("batch_id") else put("batch_id", set.batchId)
                put("commit_id", set.commitId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (id != -1L) return set.copy(id = id)
        val existing = workoutSetByCommitId(db, set.commitId)
            ?: throw SQLiteConstraintException("无法插入训练组；组顺序或提交标识冲突")
        requireSameCommittedSet(existing, set)
        return existing
    }

    private fun workoutSetByCommitId(db: SQLiteDatabase, commitId: String): WorkoutSet? = db.rawQuery(
        "SELECT * FROM workout_sets WHERE commit_id = ? LIMIT 1",
        arrayOf(commitId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toWorkoutSetOrNull() else null }

    private fun canonicalWorkoutSet(set: WorkoutSet): WorkoutSet = set.copy(
        note = set.note.trim(),
        supersetId = set.supersetId?.trim()?.uppercase(Locale.ROOT)?.ifBlank { null },
        batchId = set.batchId?.trim()?.ifBlank { null },
        commitId = set.commitId.trim(),
    )

    private fun requireSameCommittedSet(existing: WorkoutSet, requested: WorkoutSet) {
        require(
            existing.sessionId == requested.sessionId &&
                existing.exerciseId == requested.exerciseId &&
                existing.loadGrams == requested.loadGrams &&
                existing.reps == requested.reps &&
                existing.durationSeconds == requested.durationSeconds &&
                existing.completed == requested.completed &&
                existing.isWarmup == requested.isWarmup &&
                existing.rpe == requested.rpe &&
                existing.rir == requested.rir &&
                existing.note == requested.note &&
                existing.supersetId == requested.supersetId &&
                existing.batchId == requested.batchId
        ) { "训练组提交标识已用于另一条记录，请重新填写后提交" }
    }

    private fun nextSetOrder(db: SQLiteDatabase, sessionId: Long, exerciseId: Long): Int = db.rawQuery(
        "SELECT COALESCE(MAX(set_order), 0) + 1 FROM workout_sets WHERE session_id = ? AND exercise_id = ?",
        arrayOf(sessionId.toString(), exerciseId.toString()),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun validateWorkoutSet(set: WorkoutSet) {
        require(set.sessionId > 0L && set.exerciseId > 0L) { "Workout and exercise are required" }
        require(set.setOrder >= 1) { "Set order must be positive" }
        require(set.loadGrams in 0L..1_000_000L) { "Weight must be between 0 and 1000 kg" }
        require(set.reps in 0..1_000) { "Reps must be between 0 and 1000" }
        require(set.durationSeconds in 0..86_400) { "Duration must be between 0 and 86400 seconds" }
        require(!set.completed || set.reps > 0 || set.durationSeconds > 0) { "A completed set needs reps or duration" }
        require(set.rpe == null || (set.rpe.isFinite() && set.rpe in 0.0..10.0)) { "RPE must be between 0 and 10" }
        require(set.rir == null || (set.rir.isFinite() && set.rir in 0.0..10.0)) { "RIR must be between 0 and 10" }
        require(set.note.length <= 500) { "Set note is too long" }
        require(set.supersetId == null || set.supersetId.matches(Regex("[A-Z0-9_-]{1,20}"))) { "Invalid superset id" }
        require(set.batchId == null || set.batchId.length in 1..WORKOUT_BATCH_ID_MAX_LENGTH) { "Invalid set batch id" }
        require(set.commitId.isNotBlank() && set.commitId.length <= 128) { "Invalid set commit id" }
    }

    fun updateWorkoutSet(set: WorkoutSet) {
        require(set.id > 0) { "Only persisted sets can be updated" }
        val canonical = canonicalWorkoutSet(set)
        validateWorkoutSet(canonical)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT ws.* FROM workout_sets ws JOIN workout_sessions s ON s.id = ws.session_id " +
                    "WHERE ws.id = ? AND s.status = 'DRAFT' LIMIT 1",
                arrayOf(set.id.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toWorkoutSetOrNull() else null }
                ?: throw IllegalArgumentException("Only a set in the active draft workout can be updated")
            require(existing.sessionId == canonical.sessionId) { "训练组不能移动到另一场训练" }
            val movedExercise = existing.exerciseId != canonical.exerciseId
            val targetOrder = if (movedExercise) {
                nextSetOrder(db, existing.sessionId, canonical.exerciseId)
            } else {
                existing.setOrder
            }
            val updated = db.update(
                "workout_sets",
                ContentValues().apply {
                    put("exercise_id", canonical.exerciseId)
                    put("set_order", targetOrder)
                    put("load_grams", canonical.loadGrams)
                    put("reps", canonical.reps)
                    put("duration_seconds", canonical.durationSeconds)
                    put("completed", if (canonical.completed) 1 else 0)
                    put("is_warmup", if (canonical.isWarmup) 1 else 0)
                    if (canonical.rpe == null) putNull("rpe") else put("rpe", canonical.rpe)
                    if (canonical.rir == null) putNull("rir") else put("rir", canonical.rir)
                    put("note", canonical.note)
                    if (canonical.supersetId == null) putNull("superset_id") else put("superset_id", canonical.supersetId)
                },
                "id = ? AND session_id IN (SELECT id FROM workout_sessions WHERE status = 'DRAFT')",
                arrayOf(canonical.id.toString()),
            )
            require(updated == 1) { "Only a set in the active draft workout can be updated" }
            if (movedExercise) compactSetOrders(db, existing.sessionId, existing.exerciseId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteWorkoutSet(setId: Long): Boolean {
        require(setId > 0L) { "Only persisted sets can be deleted" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val owner = draftSetOwners(db, "ws.id = ?", arrayOf(setId.toString())).singleOrNull()
            val deleted = if (owner == null) {
                0
            } else {
                db.delete(
                    "workout_sets",
                    "id = ? AND session_id IN (SELECT id FROM workout_sessions WHERE status = 'DRAFT')",
                    arrayOf(setId.toString()),
                ).also {
                    if (it == 1) compactSetOrders(db, owner.first, owner.second)
                }
            }
            db.setTransactionSuccessful()
            deleted == 1
        } finally {
            db.endTransaction()
        }
    }

    fun deleteWorkoutSetBatch(batchId: String): Int {
        require(batchId.isNotBlank()) { "Batch id is required" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val owners = draftSetOwners(db, "ws.batch_id = ?", arrayOf(batchId))
            val deleted = db.delete(
                "workout_sets",
                "batch_id = ? AND session_id IN (SELECT id FROM workout_sessions WHERE status = 'DRAFT')",
                arrayOf(batchId),
            )
            owners.forEach { (sessionId, exerciseId) -> compactSetOrders(db, sessionId, exerciseId) }
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    private fun draftSetOwners(
        db: SQLiteDatabase,
        setSelection: String,
        args: Array<String>,
    ): List<Pair<Long, Long>> = db.rawQuery(
        """
        SELECT DISTINCT ws.session_id, ws.exercise_id
        FROM workout_sets ws
        JOIN workout_sessions s ON s.id = ws.session_id
        WHERE s.status = 'DRAFT' AND $setSelection
        """.trimIndent(),
        args,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1))
        }
    }

    private fun compactSetOrders(db: SQLiteDatabase, sessionId: Long, exerciseId: Long) {
        val ids = db.rawQuery(
            "SELECT id FROM workout_sets WHERE session_id = ? AND exercise_id = ? ORDER BY set_order, id",
            arrayOf(sessionId.toString(), exerciseId.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        ids.forEachIndexed { index, id ->
            db.update(
                "workout_sets",
                ContentValues().apply { put("set_order", index + 1) },
                "id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    fun cancelWorkout(sessionId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        val cancelled = try {
            val status = workoutStatus(db, sessionId)
            val changed = when (status) {
                "DRAFT" -> {
                    check(correctionTarget(db, sessionId) == null) {
                        "A correction draft must be abandoned with abandonWorkoutCorrection"
                    }
                    val hasAnySet = db.rawQuery(
                        "SELECT 1 FROM workout_sets WHERE session_id = ? LIMIT 1",
                        arrayOf(sessionId.toString()),
                    ).use(Cursor::moveToFirst)
                    if (!hasAnySet) {
                        // Keep the empty-draft check in the DELETE predicate so a
                        // future multi-connection caller cannot create a phantom
                        // CANCELLED history row between the check and the write.
                        db.delete(
                            "workout_sessions",
                            "id = ? AND status = 'DRAFT' AND NOT EXISTS (" +
                                "SELECT 1 FROM workout_sets WHERE session_id = workout_sessions.id LIMIT 1)",
                            arrayOf(sessionId.toString()),
                        ) == 1
                    } else {
                        val rawNow = System.currentTimeMillis()
                        val safeEnd = rawNow.coerceAtLeast(workoutStartedAt(db, sessionId))
                        val zone = ZoneId.systemDefault()
                        db.update(
                            "workout_sessions",
                            ContentValues().apply {
                                put("status", "CANCELLED")
                                put("ended_at", safeEnd)
                                put(
                                    "recorded_local_date",
                                    Instant.ofEpochMilli(rawNow).atZone(zone).toLocalDate().toString(),
                                )
                                put("recorded_zone_id", zone.id)
                                putNull("rest_timer_end_at")
                            },
                            "id = ? AND status = 'DRAFT'",
                            arrayOf(sessionId.toString()),
                        ) == 1
                    }
                }
                "CANCELLED" -> false
                null -> false
                else -> throw IllegalStateException("A completed workout cannot be cancelled")
            }
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
        if (cancelled) invalidateWorkoutHistoryPagination()
        return cancelled
    }

    fun setsForSession(sessionId: Long): List<WorkoutSet> = querySets(
        "ws.session_id = ?",
        arrayOf(sessionId.toString()),
        "ws.exercise_id ASC, ws.set_order ASC",
    )

    private fun setsForSession(db: SQLiteDatabase, sessionId: Long): List<WorkoutSet> = querySets(
        "ws.session_id = ?",
        arrayOf(sessionId.toString()),
        "ws.exercise_id ASC, ws.set_order ASC",
        db,
    )

    fun setsForBatch(batchId: String): List<WorkoutSet> {
        require(batchId.isNotBlank()) { "Batch id is required" }
        return querySets(
            "ws.batch_id = ?",
            arrayOf(batchId),
            "ws.exercise_id ASC, ws.set_order ASC",
        )
    }

    fun listWorkoutHistory(limit: Int = DEFAULT_HISTORY_PAGE_SIZE, offset: Int = 0): List<WorkoutHistorySummary> {
        require(limit in 1..MAX_HISTORY_PAGE_SIZE) { "训练历史每页必须为 1–${MAX_HISTORY_PAGE_SIZE} 条" }
        require(offset >= 0) { "训练历史偏移量不能为负数" }
        val (generation, anchorOffset, initialAnchor) = synchronized(historyPaginationLock) {
            observeExternalRestoreLocked()
            val nearestOffset = historyOffsetAnchors.keys.filter { it <= offset }.maxOrNull() ?: 0
            Triple(
                historyPaginationGeneration,
                nearestOffset,
                checkNotNull(historyOffsetAnchors[nearestOffset]),
            )
        }
        if (initialAnchor.exhausted) {
            lastWorkoutHistoryQueryStats = WorkoutHistoryQueryStats()
            return emptyList()
        }

        val db = readableDatabase
        var currentOffset = anchorOffset
        var cursor = initialAnchor.cursor
        var stats = WorkoutHistoryQueryStats()
        while (currentOffset < offset) {
            val skipCount = minOf(MAX_HISTORY_PAGE_SIZE, offset - currentOffset)
            val scan = scanWorkoutHistorySessions(db, skipCount, cursor)
            stats += scan.stats
            cacheWorkoutHistoryAnchors(generation, currentOffset, cursor, scan)
            currentOffset += scan.sessions.size
            cursor = scan.resumeCursor
            if (scan.sessions.size < skipCount || scan.exhausted) {
                lastWorkoutHistoryQueryStats = stats
                return emptyList()
            }
        }

        val page = scanWorkoutHistorySessions(db, limit, cursor)
        stats += page.stats
        cacheWorkoutHistoryAnchors(generation, currentOffset, cursor, page)
        lastWorkoutHistoryQueryStats = stats
        return summarizeWorkoutHistorySessions(page.sessions)
    }

    /**
     * Keyset API used when a caller can retain the returned cursor. Every SQLite
     * session query has an explicit hard LIMIT; malformed rows are over-read in
     * bounded chunks and discarded by the same defensive decoder as detail reads.
     */
    fun listWorkoutHistoryPage(
        limit: Int = DEFAULT_HISTORY_PAGE_SIZE,
        after: WorkoutHistoryCursor? = null,
    ): WorkoutHistoryPage {
        require(limit in 1..MAX_HISTORY_PAGE_SIZE) { "训练历史每页必须为 1–${MAX_HISTORY_PAGE_SIZE} 条" }
        require(after == null || (after.sortAtMillis >= 0L && after.sessionId > 0L)) { "训练历史游标无效" }
        val scan = scanWorkoutHistorySessions(readableDatabase, limit, after)
        lastWorkoutHistoryQueryStats = scan.stats
        return WorkoutHistoryPage(
            items = summarizeWorkoutHistorySessions(scan.sessions),
            nextCursor = if (scan.exhausted) null else scan.resumeCursor,
        )
    }

    /** Exact non-paginated source for the Today card. It uses the stable local
     * date captured when a workout completed, never a reinterpretation of epoch
     * millis in the device's current timezone. */
    fun completedWorkoutsForDate(date: LocalDate): List<WorkoutHistorySummary> {
        val sessions = buildList {
            readableDatabase.rawQuery(
                """
                SELECT *
                FROM workout_sessions INDEXED BY idx_completed_workout_date_v8
                WHERE status = 'COMPLETED' AND recorded_local_date = ?
                ORDER BY ended_at DESC, id DESC
                """.trimIndent(),
                arrayOf(date.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) cursor.toWorkoutSessionOrNull()?.let(::add)
            }
        }
        return summarizeWorkoutHistorySessions(sessions)
    }

    internal fun workoutHistoryQueryStatsForTest(): WorkoutHistoryQueryStats = lastWorkoutHistoryQueryStats

    internal fun workoutHistoryQueryPlanForTest(after: WorkoutHistoryCursor): List<String> {
        require(after.sortAtMillis >= 0L && after.sessionId > 0L) { "训练历史游标无效" }
        return readableDatabase.rawQuery(
            "EXPLAIN QUERY PLAN ${workoutHistorySessionSql(hasCursor = true)}",
            workoutHistorySessionArgs(after = after, queryLimit = 1),
        ).use { cursor ->
            buildList {
                val detailIndex = cursor.getColumnIndexOrThrow("detail")
                while (cursor.moveToNext()) add(cursor.getString(detailIndex))
            }
        }
    }

    private fun workoutHistorySessionSql(hasCursor: Boolean): String {
        val keysetClause = if (!hasCursor) {
            ""
        } else {
            // The redundant <= bound lets SQLite seek into the expression index
            // before applying the tie-break OR. rawQuery binds selectionArgs as
            // TEXT, while COALESCE has no affinity. Arithmetic + 0 preserves the
            // already Long-validated numeric semantics and, unlike CAST(? AS
            // INTEGER), remains usable as an expression-index range constraint
            // on the supported Android SQLite versions.
            "AND COALESCE(ended_at, started_at) <= (? + 0) " +
                "AND (COALESCE(ended_at, started_at) < (? + 0) OR " +
                "(COALESCE(ended_at, started_at) = (? + 0) " +
                "AND id < (? + 0)))"
        }
        return """
            SELECT *, COALESCE(ended_at, started_at) AS history_sort_at
            FROM workout_sessions INDEXED BY idx_workout_history_sort_v7
            WHERE status IN ('COMPLETED', 'CANCELLED')
              $keysetClause
            ORDER BY COALESCE(ended_at, started_at) DESC, id DESC
            LIMIT ?
        """.trimIndent()
    }

    private fun workoutHistorySessionArgs(
        after: WorkoutHistoryCursor?,
        queryLimit: Int,
    ): Array<String> = if (after == null) {
        arrayOf(queryLimit.toString())
    } else {
        arrayOf(
            after.sortAtMillis.toString(),
            after.sortAtMillis.toString(),
            after.sortAtMillis.toString(),
            after.sessionId.toString(),
            queryLimit.toString(),
        )
    }

    private fun scanWorkoutHistorySessions(
        db: SQLiteDatabase,
        limit: Int,
        after: WorkoutHistoryCursor?,
    ): WorkoutHistorySessionScan {
        val targetValidRows = limit + 1
        val validSessions = ArrayList<WorkoutSession>(targetValidRows)
        var rawCursor = after
        var exhausted = false
        var stats = WorkoutHistoryQueryStats()

        while (validSessions.size < targetValidRows && !exhausted) {
            val queryLimit = minOf(
                HISTORY_SESSION_QUERY_ROW_LIMIT,
                maxOf(HISTORY_SESSION_QUERY_MIN_OVERFETCH, (targetValidRows - validSessions.size) * 2),
            )
            db.rawQuery(
                workoutHistorySessionSql(hasCursor = rawCursor != null),
                workoutHistorySessionArgs(after = rawCursor, queryLimit = queryLimit),
            ).use { result ->
                val rowsReturned = result.count
                stats += WorkoutHistoryQueryStats(
                    sessionQueryCount = 1,
                    sessionRowsReturned = rowsReturned,
                    maxSessionRowsReturnedByOneQuery = rowsReturned,
                    maxSessionRowsRequestedByOneQuery = queryLimit,
                )
                while (validSessions.size < targetValidRows && result.moveToNext()) {
                    rawCursor = WorkoutHistoryCursor(
                        sortAtMillis = result.getLong(result.getColumnIndexOrThrow("history_sort_at")),
                        sessionId = result.getLong(result.getColumnIndexOrThrow("id")),
                    )
                    result.toWorkoutSessionOrNull()?.let(validSessions::add)
                }
                // A short SQL result proves end-of-ledger only when this call
                // actually consumed that entire result. We may stop early after
                // finding limit + 1 valid rows, leaving more rows in this cursor.
                val fullyConsumedResult = result.position >= rowsReturned - 1
                exhausted = fullyConsumedResult && rowsReturned < queryLimit
            }
        }

        val sessions = validSessions.take(limit)
        val resumeCursor = sessions.lastOrNull()?.historyCursor() ?: after
        return WorkoutHistorySessionScan(
            sessions = sessions,
            resumeCursor = resumeCursor,
            exhausted = exhausted && validSessions.size <= limit,
            stats = stats,
        )
    }

    private fun cacheWorkoutHistoryAnchors(
        generation: Long,
        startOffset: Int,
        startingCursor: WorkoutHistoryCursor?,
        scan: WorkoutHistorySessionScan,
    ) {
        synchronized(historyPaginationLock) {
            if (generation != historyPaginationGeneration) return
            if (scan.sessions.isEmpty() && scan.exhausted) {
                historyOffsetAnchors[startOffset] = HistoryOffsetAnchor(startingCursor, exhausted = true)
            }
            scan.sessions.forEachIndexed { index, session ->
                historyOffsetAnchors[startOffset + index + 1] = HistoryOffsetAnchor(
                    cursor = session.historyCursor(),
                    exhausted = scan.exhausted && index == scan.sessions.lastIndex,
                )
            }
            while (historyOffsetAnchors.size > MAX_HISTORY_OFFSET_ANCHORS) {
                val removable = historyOffsetAnchors.keys.firstOrNull { it != 0 } ?: break
                historyOffsetAnchors.remove(removable)
            }
        }
    }

    private fun invalidateWorkoutHistoryPagination() {
        synchronized(historyPaginationLock) {
            resetWorkoutHistoryPaginationLocked()
        }
    }

    /**
     * Backup restore replaces table contents through a separate helper. The
     * process generation lets every already-open database/repository instance
     * discard offset anchors lazily before its next history read, without
     * retaining strong references to long-lived helpers.
     */
    private fun observeExternalRestoreLocked() {
        val current = externalRestoreGeneration.get()
        if (observedExternalRestoreGeneration == current) return
        observedExternalRestoreGeneration = current
        resetWorkoutHistoryPaginationLocked()
    }

    private fun resetWorkoutHistoryPaginationLocked() {
        historyPaginationGeneration += 1L
        historyOffsetAnchors.clear()
        historyOffsetAnchors[0] = HistoryOffsetAnchor(cursor = null, exhausted = false)
        lastWorkoutHistoryQueryStats = WorkoutHistoryQueryStats()
    }

    private fun WorkoutSession.historyCursor() = WorkoutHistoryCursor(
        sortAtMillis = endedAtMillis ?: startedAtMillis,
        sessionId = id,
    )

    private fun summarizeWorkoutHistorySessions(sessions: List<WorkoutSession>): List<WorkoutHistorySummary> {
        if (sessions.isEmpty()) return emptyList()
        val exercises = listExercises(includeArchived = true).associateBy { it.id }
        // Fetch only sets belonging to this page, avoiding both N+1 queries and an
        // unbounded scan of the user's lifetime ledger.
        val sessionIds = sessions.map { it.id }
        val placeholders = sessionIds.joinToString(",") { "?" }
        val setsBySession = querySets(
            "s.status IN ('COMPLETED', 'CANCELLED') AND ws.session_id IN ($placeholders)",
            sessionIds.map(Long::toString).toTypedArray(),
            "ws.session_id DESC, ws.exercise_id ASC, ws.set_order ASC",
        ).groupBy { it.sessionId }
        return sessions.map { session ->
            workoutHistorySummary(session, setsBySession[session.id].orEmpty(), exercises)
        }
    }

    fun workoutHistoryDetail(sessionId: Long): WorkoutHistoryDetail {
        val session = readableDatabase.query(
            "workout_sessions",
            null,
            "id = ? AND status IN ('COMPLETED', 'CANCELLED')",
            arrayOf(sessionId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toWorkoutSessionOrNull() else null }
            ?: throw IllegalArgumentException("训练历史不存在")
        val exercises = listExercises(includeArchived = true).associateBy { it.id }
        val sets = setsForSession(session.id)
        val records = sets.mapNotNull { set -> exercises[set.exerciseId]?.let { WorkoutHistorySet(set, it) } }
        return WorkoutHistoryDetail(
            summary = workoutHistorySummary(session, sets, exercises),
            sets = records,
            personalRecords = prEventsForSession(readableDatabase, session.id),
        )
    }

    private data class WorkoutCorrectionSourceSnapshot(
        val session: WorkoutSession,
        val sets: List<WorkoutSet>,
        val fingerprint: String,
    )

    private data class WorkoutCorrectionDraftSnapshot(
        val session: WorkoutSession,
        val sets: List<WorkoutSet>,
        val baseRevision: Int,
        val baseFingerprint: String,
        val fingerprint: String,
    )

    private data class WorkoutCorrectionBase(
        val revision: Int,
        val fingerprint: String,
    )

    fun startWorkoutCorrection(sessionId: Long): WorkoutSession {
        require(sessionId > 0L) { "训练历史不存在" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            requireNoRewriteGuards(db)
            val rawDraftCount = scalarLong(db, "SELECT COUNT(*) FROM workout_sessions WHERE status = 'DRAFT'")
            check(rawDraftCount == 0L && activeWorkout(db) == null) {
                "请先完成、取消或修复当前进行中的训练"
            }
            val source = preflightWorkoutCorrectionSource(db, sessionId)
            val original = source.session
            val now = System.currentTimeMillis()
            val draftId = db.insertOrThrow(
                "workout_sessions",
                null,
                ContentValues().apply {
                    put("title", original.title)
                    put("started_at", now)
                    putNull("ended_at")
                    put("status", "DRAFT")
                    putNull("rest_timer_end_at")
                    put("rest_duration_seconds", original.restDurationSeconds)
                    put("correction_of_session_id", original.id)
                    put("correction_revision", 0)
                    putNull("corrected_at")
                    // recorded_zone_id is not a calendar fact for DRAFT rows and
                    // is hidden by the decoder. It safely carries the immutable
                    // source revision + fingerprint without changing schema v10.
                    put(
                        "recorded_zone_id",
                        encodeWorkoutCorrectionBase(original.correctionRevision, source.fingerprint),
                    )
                },
            )
            source.sets.forEach { sourceSet ->
                insertWorkoutSet(
                    db,
                    sourceSet.copy(
                        id = 0L,
                        sessionId = draftId,
                        commitId = UUID.randomUUID().toString(),
                    ),
                )
            }
            val copiedSets = exactWorkoutSetsForRewrite(db, draftId, "更正草稿")
            check(copiedSets.size == source.sets.size && copiedSets.zip(source.sets).all { (copy, originalSet) ->
                sameCopiedWorkoutSetPayload(copy, originalSet)
            }) { "更正草稿无法无损复制原训练组；未创建更正草稿" }
            check(preflightWorkoutCorrectionSource(db, sessionId).fingerprint == source.fingerprint) {
                "原训练在创建更正草稿时发生变化；未创建更正草稿"
            }
            val draft = workoutSession(db, draftId, "DRAFT")
                ?: error("更正草稿创建后无法读取")
            db.setTransactionSuccessful()
            draft
        } finally {
            db.endTransaction()
        }
    }

    private fun preflightWorkoutCorrectionSource(
        db: SQLiteDatabase,
        sessionId: Long,
    ): WorkoutCorrectionSourceSnapshot {
        val session = exactWorkoutSessionForRewrite(
            db = db,
            sessionId = sessionId,
            expectedStatus = WorkoutStatus.COMPLETED,
            label = "原完成训练",
        )
        check(session.correctionRevision < Int.MAX_VALUE) {
            "原完成训练更正版本已达到上限；未更改训练历史或 PR"
        }
        validateCompletedWorkoutCalendarFacts(db, sessionId)
        val sets = exactWorkoutSetsForRewrite(db, sessionId, "原完成训练")
        exactRelatedPrEventsForRewrite(db, sessionId, "原完成训练", requireEmpty = false)
        return WorkoutCorrectionSourceSnapshot(
            session = session,
            sets = sets,
            fingerprint = workoutCorrectionFingerprint(db, sessionId),
        )
    }

    private fun preflightWorkoutCorrectionDraft(
        db: SQLiteDatabase,
        draftId: Long,
        expectedOriginalId: Long,
    ): WorkoutCorrectionDraftSnapshot {
        val session = exactWorkoutSessionForRewrite(
            db = db,
            sessionId = draftId,
            expectedStatus = WorkoutStatus.DRAFT,
            label = "更正草稿",
        )
        check(session.correctionOfSessionId == expectedOriginalId) {
            "更正草稿与原训练关联已变化；未保存本次更正"
        }
        val correctionBase = db.rawQuery(
            "SELECT recorded_zone_id FROM workout_sessions WHERE id = ? LIMIT 1",
            arrayOf(draftId.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "更正草稿已不存在；未保存本次更正" }
            cursor.getString(0)?.let(::decodeWorkoutCorrectionBase)
        }
        check(correctionBase != null) {
            "更正草稿缺少安全基线；请放弃后重新开始更正"
        }
        val sets = exactWorkoutSetsForRewrite(db, draftId, "更正草稿")
        exactRelatedPrEventsForRewrite(db, draftId, "更正草稿", requireEmpty = true)
        return WorkoutCorrectionDraftSnapshot(
            session = session,
            sets = sets,
            baseRevision = correctionBase.revision,
            baseFingerprint = correctionBase.fingerprint,
            fingerprint = workoutCorrectionFingerprint(db, draftId),
        )
    }

    private fun exactWorkoutSessionForRewrite(
        db: SQLiteDatabase,
        sessionId: Long,
        expectedStatus: WorkoutStatus,
        label: String,
    ): WorkoutSession = db.rawQuery(
        "SELECT * FROM workout_sessions WHERE id = ? ORDER BY id",
        arrayOf(sessionId.toString()),
    ).use { cursor ->
        if (cursor.count == 0) {
            throw IllegalArgumentException("$label 不存在")
        }
        val decoded = buildList {
            while (cursor.moveToNext()) cursor.toWorkoutSessionOrNull()?.let(::add)
        }
        check(cursor.count == 1 && decoded.size == cursor.count) {
            "$label 含无法安全解析的旧数据；未更改训练历史或 PR"
        }
        decoded.single().also { session ->
            if (session.status != expectedStatus) {
                throw IllegalArgumentException(
                    if (expectedStatus == WorkoutStatus.COMPLETED) "只能更正已完成训练" else "$label 已结束",
                )
            }
        }
    }

    private fun validateCompletedWorkoutCalendarFacts(db: SQLiteDatabase, sessionId: Long) {
        db.rawQuery(
            "SELECT ended_at, recorded_local_date, recorded_zone_id FROM workout_sessions WHERE id = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "原完成训练已不存在；未更改训练历史或 PR" }
            val endedAt = if (cursor.isNull(0)) null else cursor.getLong(0)
            val localDate = if (cursor.isNull(1)) null else cursor.getString(1)
            val zoneId = if (cursor.isNull(2)) null else cursor.getString(2)
            check(
                endedAt != null &&
                    localDate?.let { runCatching { LocalDate.parse(it) }.isSuccess } == true &&
                    zoneId?.let { it.isNotBlank() && it.length <= 80 && runCatching { ZoneId.of(it) }.isSuccess } == true,
            ) { "原完成训练日历事实无法安全复制；未更改训练历史或 PR" }
        }
    }

    private fun exactWorkoutSetsForRewrite(
        db: SQLiteDatabase,
        sessionId: Long,
        label: String,
    ): List<WorkoutSet> {
        val rawCount = scalarLong(
            db,
            "SELECT COUNT(*) FROM workout_sets WHERE session_id = $sessionId",
        )
        val decoded = setsForSession(db, sessionId)
        check(decoded.size.toLong() == rawCount) {
            "$label 训练组含无法安全解析的旧数据；未更改训练历史或 PR"
        }
        check(
            scalarLong(
                db,
                "SELECT COUNT(*) FROM workout_sets ws LEFT JOIN exercises e ON e.id = ws.exercise_id " +
                    "WHERE ws.session_id = $sessionId AND e.id IS NULL",
            ) == 0L,
        ) { "$label 训练组引用了缺失动作；未更改训练历史或 PR" }

        val decodedExerciseIds = listExercises(db, includeArchived = true).mapTo(hashSetOf()) { it.id }
        check(decoded.all { it.exerciseId in decodedExerciseIds }) {
            "$label 训练组引用了无法安全解析的动作；未更改训练历史或 PR"
        }
        check(
            decoded.groupBy { it.exerciseId }.values.all { exerciseSets ->
                exerciseSets.sortedWith(compareBy<WorkoutSet> { it.setOrder }.thenBy { it.id })
                    .map { it.setOrder } == (1..exerciseSets.size).toList()
            },
        ) { "$label 训练组顺序无法无损复制；未更改训练历史或 PR" }
        decoded.forEach { set ->
            val canonical = canonicalWorkoutSet(set)
            check(
                canonical.note == set.note &&
                    canonical.supersetId == set.supersetId &&
                    canonical.batchId == set.batchId,
            ) { "$label 训练组含无法无损复制的旧格式；未更改训练历史或 PR" }
        }
        return decoded
    }

    private fun exactRelatedPrEventsForRewrite(
        db: SQLiteDatabase,
        sessionId: Long,
        label: String,
        requireEmpty: Boolean,
    ): List<PersonalRecord> {
        val args = arrayOf(sessionId.toString(), sessionId.toString())
        val decoded = db.rawQuery(
            "SELECT pe.* FROM pr_events pe " +
                "WHERE pe.session_id = ? OR pe.set_id IN (SELECT id FROM workout_sets WHERE session_id = ?) " +
                "ORDER BY pe.id",
            args,
        ).use { cursor ->
            val rawCount = cursor.count
            val records = readPrEvents(cursor)
            check(records.size == rawCount) {
                "$label 关联 PR 含无法安全解析的旧数据；未更改训练历史或 PR"
            }
            records
        }
        if (requireEmpty) {
            check(decoded.isEmpty()) { "$label 不应关联 PR；未保存本次更正" }
            return decoded
        }
        check(
            scalarLong(
                db,
                "SELECT COUNT(*) FROM pr_events pe " +
                    "LEFT JOIN workout_sessions s ON s.id = pe.session_id " +
                    "LEFT JOIN workout_sets ws ON ws.id = pe.set_id " +
                    "LEFT JOIN exercises e ON e.id = pe.exercise_id " +
                    "WHERE (pe.session_id = $sessionId OR ws.session_id = $sessionId) AND (" +
                    "pe.session_id <> $sessionId OR s.id IS NULL OR s.status <> 'COMPLETED' " +
                    "OR ws.id IS NULL OR ws.session_id <> pe.session_id " +
                    "OR e.id IS NULL OR ws.exercise_id <> pe.exercise_id)",
            ) == 0L,
        ) { "$label 关联 PR 的会话、训练组或动作引用不完整；未更改训练历史或 PR" }
        val decodedExerciseIds = listExercises(db, includeArchived = true).mapTo(hashSetOf()) { it.id }
        check(decoded.all { it.exerciseId in decodedExerciseIds }) {
            "$label 关联 PR 引用了无法安全解析的动作；未更改训练历史或 PR"
        }
        return decoded
    }

    private fun sameCopiedWorkoutSetPayload(copy: WorkoutSet, source: WorkoutSet): Boolean =
        copy.exerciseId == source.exerciseId &&
            copy.setOrder == source.setOrder &&
            copy.loadGrams == source.loadGrams &&
            copy.reps == source.reps &&
            copy.durationSeconds == source.durationSeconds &&
            copy.completed == source.completed &&
            copy.isWarmup == source.isWarmup &&
            copy.rpe == source.rpe &&
            copy.rir == source.rir &&
            copy.note == source.note &&
            copy.supersetId == source.supersetId &&
            copy.batchId == source.batchId

    private fun encodeWorkoutCorrectionBase(revision: Int, fingerprint: String): String =
        "$CORRECTION_BASE_FINGERPRINT_PREFIX$revision:$fingerprint"

    private fun decodeWorkoutCorrectionBase(encoded: String): WorkoutCorrectionBase? {
        if (!encoded.startsWith(CORRECTION_BASE_FINGERPRINT_PREFIX)) return null
        val payload = encoded.removePrefix(CORRECTION_BASE_FINGERPRINT_PREFIX)
        val separator = payload.indexOf(':')
        if (separator <= 0) return null
        val revision = payload.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val fingerprint = payload.substring(separator + 1)
            .takeIf { it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' } }
            ?: return null
        return WorkoutCorrectionBase(revision, fingerprint)
    }

    private fun workoutCorrectionFingerprint(db: SQLiteDatabase, sessionId: Long): String {
        val raw = StringBuilder()
        appendRawQueryFingerprint(
            db,
            raw,
            "session",
            "SELECT * FROM workout_sessions WHERE id = ? ORDER BY id",
            arrayOf(sessionId.toString()),
        )
        appendRawQueryFingerprint(
            db,
            raw,
            "sets",
            "SELECT * FROM workout_sets WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString()),
        )
        appendRawQueryFingerprint(
            db,
            raw,
            "prs",
            "SELECT * FROM pr_events WHERE session_id = ? " +
                "OR set_id IN (SELECT id FROM workout_sets WHERE session_id = ?) ORDER BY id",
            arrayOf(sessionId.toString(), sessionId.toString()),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun appendRawQueryFingerprint(
        db: SQLiteDatabase,
        destination: StringBuilder,
        label: String,
        sql: String,
        args: Array<String>,
    ) {
        appendFingerprintToken(destination, "Q", label)
        db.rawQuery(sql, args).use { cursor ->
            cursor.columnNames.forEach { appendFingerprintToken(destination, "C", it) }
            while (cursor.moveToNext()) {
                appendFingerprintToken(destination, "R", cursor.position.toString())
                repeat(cursor.columnCount) { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> appendFingerprintToken(destination, "N", "")
                        Cursor.FIELD_TYPE_INTEGER -> appendFingerprintToken(destination, "I", cursor.getLong(column).toString())
                        Cursor.FIELD_TYPE_FLOAT -> appendFingerprintToken(
                            destination,
                            "F",
                            java.lang.Double.doubleToRawLongBits(cursor.getDouble(column)).toString(),
                        )
                        Cursor.FIELD_TYPE_STRING -> appendFingerprintToken(destination, "S", cursor.getString(column))
                        Cursor.FIELD_TYPE_BLOB -> appendFingerprintToken(
                            destination,
                            "B",
                            cursor.getBlob(column).joinToString(",") { (it.toInt() and 0xff).toString() },
                        )
                    }
                }
            }
        }
    }

    private fun appendFingerprintToken(destination: StringBuilder, type: String, value: String) {
        destination.append(type)
            .append(value.toByteArray(Charsets.UTF_8).size)
            .append(':')
            .append(value)
            .append('|')
    }

    fun abandonWorkoutCorrection(correctionDraftId: Long): Boolean {
        require(correctionDraftId > 0L) { "更正草稿不存在" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            check(workoutStatus(db, correctionDraftId) == "DRAFT" && correctionTarget(db, correctionDraftId) != null) {
                "只能放弃进行中的更正草稿"
            }
            // Delete children while the parent is still visibly DRAFT. This is
            // deterministic across SQLite foreign-key cascade implementations.
            db.delete("workout_sets", "session_id = ?", arrayOf(correctionDraftId.toString()))
            val deleted = db.delete(
                "workout_sessions",
                "id = ? AND status = 'DRAFT' AND correction_of_session_id IS NOT NULL",
                arrayOf(correctionDraftId.toString()),
            ) == 1
            check(deleted) { "更正草稿状态已变化，请重新打开训练页" }
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun commitWorkoutCorrection(correctionDraftId: Long): WorkoutCorrectionResult {
        require(correctionDraftId > 0L) { "更正草稿不存在" }
        val db = writableDatabase
        db.beginTransaction()
        var historyChanged = false
        try {
            requireNoRewriteGuards(db)
            val originalId = correctionTarget(db, correctionDraftId)
                ?: throw IllegalArgumentException("该训练不是更正草稿")
            val draftSnapshot = preflightWorkoutCorrectionDraft(db, correctionDraftId, originalId)
            val sourceSnapshot = preflightWorkoutCorrectionSource(db, originalId)
            val original = sourceSnapshot.session
            check(draftSnapshot.baseRevision == original.correctionRevision) {
                "原训练更正版本已变化；未保存本次更正"
            }
            check(draftSnapshot.baseFingerprint == sourceSnapshot.fingerprint) {
                "原训练在更正草稿创建后已变化；未保存本次更正"
            }
            val correctionSets = draftSnapshot.sets
            require(correctionSets.any { it.completed && !it.isWarmup }) {
                "保存更正前至少保留一个正式完成组"
            }

            // This operation eventually deletes every PR row. Validate the
            // complete raw ledger while the original and draft are still intact.
            val previousPrTimes = preflightPrRebuild(db).achievedAtBySession
            val sourceRecheck = preflightWorkoutCorrectionSource(db, originalId)
            val draftRecheck = preflightWorkoutCorrectionDraft(db, correctionDraftId, originalId)
            check(
                sourceRecheck.session.correctionRevision == original.correctionRevision &&
                    sourceRecheck.fingerprint == sourceSnapshot.fingerprint &&
                    draftRecheck.fingerprint == draftSnapshot.fingerprint &&
                    draftRecheck.baseRevision == original.correctionRevision &&
                    draftRecheck.baseFingerprint == sourceSnapshot.fingerprint,
            ) { "训练更正快照已变化；未保存本次更正" }
            addRewriteGuard(db, original.id, "CORRECT")

            check(
                db.delete("workout_sets", "session_id = ?", arrayOf(correctionDraftId.toString())) ==
                    correctionSets.size,
            ) { "更正草稿训练组状态已变化" }
            check(
                db.delete(
                    "workout_sessions",
                    "id = ? AND status = 'DRAFT' AND correction_of_session_id = ? AND correction_revision = ? " +
                        "AND recorded_zone_id = ?",
                    arrayOf(
                        correctionDraftId.toString(),
                        original.id.toString(),
                        "0",
                        encodeWorkoutCorrectionBase(original.correctionRevision, sourceSnapshot.fingerprint),
                    ),
                ) == 1,
            ) { "更正草稿状态已变化" }
            maybeFailWorkoutRewriteForTest(WorkoutRewriteFailurePoint.AFTER_CORRECTION_DRAFT_REMOVED)

            check(
                db.update(
                    "workout_sessions",
                    ContentValues().apply { put("status", "DRAFT") },
                    "id = ? AND status = 'COMPLETED' AND correction_revision = ?",
                    arrayOf(original.id.toString(), original.correctionRevision.toString()),
                ) == 1,
            ) { "原完成训练状态已变化" }
            check(
                db.delete("workout_sets", "session_id = ?", arrayOf(original.id.toString())) ==
                    sourceSnapshot.sets.size,
            ) { "原完成训练组状态已变化" }
            correctionSets.groupBy { it.exerciseId }
                .toSortedMap()
                .forEach { (_, exerciseSets) ->
                    exerciseSets.sortedWith(compareBy<WorkoutSet> { it.setOrder }.thenBy { it.id })
                        .forEachIndexed { index, source ->
                            insertWorkoutSet(
                                db,
                                source.copy(
                                    id = 0L,
                                    sessionId = original.id,
                                    setOrder = index + 1,
                                    commitId = UUID.randomUUID().toString(),
                                ),
                            )
                        }
                }
            val correctedAt = System.currentTimeMillis()
            check(
                db.update(
                    "workout_sessions",
                    ContentValues().apply {
                        put("status", "COMPLETED")
                        put("correction_revision", original.correctionRevision + 1)
                        put("corrected_at", correctedAt)
                    },
                    "id = ? AND status = 'DRAFT' AND correction_revision = ?",
                    arrayOf(original.id.toString(), original.correctionRevision.toString()),
                ) == 1,
            ) { "无法锁定更正后的完成训练" }
            maybeFailWorkoutRewriteForTest(WorkoutRewriteFailurePoint.AFTER_ORIGINAL_SETS_REPLACED)

            val rebuiltCount = rebuildAllPrEvents(db, previousPrTimes)
            clearRewriteGuards(db)
            db.setTransactionSuccessful()
            historyChanged = true
            return WorkoutCorrectionResult(
                sessionId = original.id,
                correctionRevision = original.correctionRevision + 1,
                rebuiltPrEventCount = rebuiltCount,
            )
        } finally {
            db.endTransaction()
            if (historyChanged) invalidateWorkoutHistoryPagination()
        }
    }

    fun deleteWorkoutHistorySession(sessionId: Long): WorkoutHistoryDeleteResult {
        require(sessionId > 0L) { "训练历史不存在" }
        val db = writableDatabase
        db.beginTransaction()
        var historyChanged = false
        try {
            requireNoRewriteGuards(db)
            val session = workoutSession(db, sessionId, "COMPLETED", "CANCELLED")
                ?: throw IllegalArgumentException("训练历史不存在")
            check(
                db.rawQuery(
                    "SELECT 1 FROM workout_sessions WHERE status = 'DRAFT' AND correction_of_session_id = ? LIMIT 1",
                    arrayOf(session.id.toString()),
                ).use { !it.moveToFirst() },
            ) { "请先保存或放弃这场训练的更正草稿" }
            exactWorkoutSetsForRewrite(db, session.id, "待删除训练历史")
            exactRelatedPrEventsForRewrite(
                db = db,
                sessionId = session.id,
                label = "待删除训练历史",
                requireEmpty = session.status == WorkoutStatus.CANCELLED,
            )
            // The selected history row may own the very malformed set/PR that a
            // post-delete scan would no longer see. Validate the complete source
            // ledger before deleting any history row.
            val previousPrTimes = preflightPrRebuild(db).achievedAtBySession
            addRewriteGuard(db, session.id, "DELETE")
            db.delete("pr_events", "session_id = ?", arrayOf(session.id.toString()))
            db.delete("workout_sets", "session_id = ?", arrayOf(session.id.toString()))
            check(
                db.delete(
                    "workout_sessions",
                    "id = ? AND status IN ('COMPLETED','CANCELLED')",
                    arrayOf(session.id.toString()),
                ) == 1,
            ) { "训练历史状态已变化" }
            maybeFailWorkoutRewriteForTest(WorkoutRewriteFailurePoint.AFTER_HISTORY_DELETE)
            val rebuiltCount = rebuildAllPrEvents(db, previousPrTimes)
            clearRewriteGuards(db)
            db.setTransactionSuccessful()
            historyChanged = true
            return WorkoutHistoryDeleteResult(
                sessionId = session.id,
                recordedLocalDate = session.recordedLocalDate,
                rebuiltPrEventCount = rebuiltCount,
            )
        } finally {
            db.endTransaction()
            if (historyChanged) invalidateWorkoutHistoryPagination()
        }
    }

    private data class PrRebuildInput(
        val exercisesById: Map<Long, Exercise>,
        val completedSessions: List<WorkoutSession>,
        val setsBySessionId: Map<Long, List<WorkoutSet>>,
        val achievedAtBySession: Map<Long, Long>,
    )

    /**
     * Defensive readers deliberately skip malformed legacy rows so ordinary
     * screens can still open. A destructive PR rebuild cannot use that policy:
     * silently skipping even one session/set/exercise would permanently erase
     * some old PR history. Build and validate the complete transaction-local
     * snapshot before deleting the first PR row; any mismatch aborts the caller's
     * transaction and leaves both the original history and PR rows untouched.
     */
    private fun preflightPrRebuild(db: SQLiteDatabase): PrRebuildInput {
        val exercises = listExercises(db, includeArchived = true)
        val rawExerciseCount = scalarLong(db, "SELECT COUNT(*) FROM exercises")
        check(exercises.size.toLong() == rawExerciseCount) {
            "动作库含无法安全解析的旧数据；未更改训练历史或 PR"
        }

        val completedSessions = db.rawQuery(
            // Only one live workout is allowed. IDs preserve recording order even
            // when the wall clock moves backwards; a correction keeps its original ID.
            "SELECT * FROM workout_sessions WHERE status = 'COMPLETED' ORDER BY id ASC",
            null,
        ).use { cursor ->
            val rawCount = cursor.count
            val decoded = buildList {
                while (cursor.moveToNext()) cursor.toWorkoutSessionOrNull()?.let(::add)
            }
            check(decoded.size == rawCount && decoded.all { it.endedAtMillis != null }) {
                "完成训练含无法安全解析的旧数据；未更改训练历史或 PR"
            }
            decoded
        }

        check(
            scalarLong(
                db,
                "SELECT COUNT(*) FROM workout_sets ws " +
                    "LEFT JOIN workout_sessions s ON s.id = ws.session_id " +
                    "LEFT JOIN exercises e ON e.id = ws.exercise_id " +
                    "WHERE s.id IS NULL OR e.id IS NULL",
            ) == 0L,
        ) { "训练组存在悬挂的会话或动作引用；未更改训练历史或 PR" }

        val rawCompletedSetCount = scalarLong(
            db,
            "SELECT COUNT(*) FROM workout_sets ws " +
                "JOIN workout_sessions s ON s.id = ws.session_id WHERE s.status = 'COMPLETED'",
        )
        val decodedSets = querySets(
            selection = "s.status = 'COMPLETED'",
            args = emptyArray(),
            orderBy = "s.id ASC, ws.exercise_id ASC, ws.set_order ASC, ws.id ASC",
            db = db,
        )
        check(decodedSets.size.toLong() == rawCompletedSetCount) {
            "完成训练组含无法安全解析的旧数据；未更改训练历史或 PR"
        }
        val decodedExerciseIds = exercises.mapTo(hashSetOf()) { it.id }
        check(
            scalarLong(
                db,
                "SELECT COUNT(*) FROM workout_sets ws " +
                    "JOIN workout_sessions s ON s.id = ws.session_id " +
                    "LEFT JOIN exercises e ON e.id = ws.exercise_id " +
                    "WHERE s.status = 'COMPLETED' AND e.id IS NULL",
            ) == 0L,
        ) { "完成训练组引用了缺失动作；未更改训练历史或 PR" }
        check(decodedSets.all { it.exerciseId in decodedExerciseIds }) {
            "完成训练组动作无法重建；未更改训练历史或 PR"
        }
        val decodedPrEvents = db.rawQuery("SELECT * FROM pr_events ORDER BY id", null).use { cursor ->
            val rawCount = cursor.count
            val decoded = readPrEvents(cursor)
            check(decoded.size == rawCount) {
                "现有 PR 含无法安全解析的旧数据；未更改训练历史或 PR"
            }
            decoded
        }
        check(decodedPrEvents.all { it.exerciseId in decodedExerciseIds }) {
            "现有 PR 动作无法重建；未更改训练历史或 PR"
        }
        check(
            scalarLong(
                db,
                "SELECT COUNT(*) FROM pr_events pe " +
                    "LEFT JOIN workout_sessions s ON s.id = pe.session_id " +
                    "LEFT JOIN workout_sets ws ON ws.id = pe.set_id " +
                    "LEFT JOIN exercises e ON e.id = pe.exercise_id " +
                    "WHERE s.id IS NULL OR s.status <> 'COMPLETED' OR ws.id IS NULL OR e.id IS NULL " +
                    "OR ws.session_id <> pe.session_id OR ws.exercise_id <> pe.exercise_id",
            ) == 0L,
        ) { "现有 PR 引用不完整；未更改训练历史或 PR" }

        return PrRebuildInput(
            exercisesById = exercises.associateBy { it.id },
            completedSessions = completedSessions,
            setsBySessionId = decodedSets.groupBy { it.sessionId },
            achievedAtBySession = db.rawQuery(
                "SELECT session_id, MIN(achieved_at) FROM pr_events GROUP BY session_id", null,
            ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getLong(1)) } },
        )
    }

    private fun rebuildAllPrEvents(db: SQLiteDatabase, previousPrTimes: Map<Long, Long>): Int {
        val input = preflightPrRebuild(db)
        val guardedSessionIds = db.rawQuery(
            "SELECT DISTINCT session_id FROM pr_events ORDER BY session_id",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        guardedSessionIds.forEach { addRewriteGuard(db, it, "REBUILD_PR") }
        db.delete("pr_events", null, null)
        // A legacy/external database can contain duplicate logical PR rows only
        // after losing the unique index. Once the validated rows are gone, restore
        // the invariant before inserting the deterministic canonical rebuild.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_event_key " +
                "ON pr_events(session_id, exercise_id, pr_type, bucket_key)",
        )

        val historicalSets = mutableMapOf<Long, MutableList<WorkoutSet>>()
        var insertedCount = 0
        input.completedSessions.forEach { session ->
            val sessionSets = input.setsBySessionId[session.id].orEmpty()
            sessionSets.groupBy { it.exerciseId }.toSortedMap().forEach { (exerciseId, currentSets) ->
                val exercise = checkNotNull(input.exercisesById[exerciseId]) {
                    "完成训练组动作无法重建；未更改训练历史或 PR"
                }
                val decisions = PrCalculator.evaluateSession(
                    exercise.trackingType,
                    currentSets,
                    historicalSets[exerciseId].orEmpty(),
                )
                decisions.forEach { decision ->
                    insertPrDecision(
                        db = db,
                        sessionId = session.id,
                        exerciseId = exerciseId,
                        decision = decision,
                        // Keep the real completion timestamp rather than the
                        // clamped ended_at of a session spanning a clock rollback.
                        achievedAtMillis = previousPrTimes[session.id] ?: requireNotNull(session.endedAtMillis),
                    )
                    insertedCount += 1
                    maybeFailWorkoutRewriteForTest(WorkoutRewriteFailurePoint.DURING_PR_REBUILD)
                }
            }
            sessionSets.groupBy { it.exerciseId }.forEach { (exerciseId, sets) ->
                historicalSets.getOrPut(exerciseId, ::mutableListOf).addAll(sets)
            }
        }
        return insertedCount
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "无法读取训练历史完整性" }
        cursor.getLong(0)
    }

    private fun insertPrDecision(
        db: SQLiteDatabase,
        sessionId: Long,
        exerciseId: Long,
        decision: PrDecision,
        achievedAtMillis: Long,
    ): PersonalRecord {
        val set = decision.set
        val metric = decision.metric
        val id = db.insertOrThrow(
            "pr_events",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("exercise_id", exerciseId)
                put("set_id", set.id)
                put("pr_type", metric.type.name)
                put("bucket_key", metric.bucketKey)
                put("event_kind", decision.eventKind.name)
                put("value", metric.value)
                put("weight_kg", set.weightKg)
                put("reps", set.reps)
                put("duration_seconds", set.durationSeconds)
                put("achieved_at", achievedAtMillis)
            },
        )
        return PersonalRecord(
            id = id,
            exerciseId = exerciseId,
            setId = set.id,
            type = metric.type,
            bucketKey = metric.bucketKey,
            value = metric.value,
            weightKg = set.weightKg,
            reps = set.reps,
            durationSeconds = set.durationSeconds,
            achievedAtMillis = achievedAtMillis,
            eventKind = decision.eventKind,
        )
    }

    private fun workoutSession(db: SQLiteDatabase, sessionId: Long, vararg statuses: String): WorkoutSession? {
        val placeholders = statuses.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT * FROM workout_sessions WHERE id = ? AND status IN ($placeholders) LIMIT 1",
            (listOf(sessionId.toString()) + statuses).toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toWorkoutSessionOrNull() else null }
    }

    private fun correctionTarget(db: SQLiteDatabase, draftId: Long): Long? = db.rawQuery(
        "SELECT correction_of_session_id FROM workout_sessions WHERE id = ? AND status = 'DRAFT'",
        arrayOf(draftId.toString()),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun requireNoRewriteGuards(db: SQLiteDatabase) {
        check(
            db.rawQuery("SELECT 1 FROM workout_rewrite_guards LIMIT 1", null).use { !it.moveToFirst() },
        ) { "另一项训练历史维护尚未结束，请稍后重试" }
    }

    private fun addRewriteGuard(db: SQLiteDatabase, sessionId: Long, reason: String) {
        val inserted = db.insertWithOnConflict(
            "workout_rewrite_guards",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("reason", reason)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) {
            check(
                db.rawQuery(
                    "SELECT 1 FROM workout_rewrite_guards WHERE session_id = ? LIMIT 1",
                    arrayOf(sessionId.toString()),
                ).use { it.moveToFirst() },
            ) { "无法建立训练历史维护保护" }
        }
    }

    private fun clearRewriteGuards(db: SQLiteDatabase) {
        db.delete("workout_rewrite_guards", null, null)
    }

    private fun workoutHistorySummary(
        session: WorkoutSession,
        sets: List<WorkoutSet>,
        exercises: Map<Long, Exercise>,
    ): WorkoutHistorySummary {
        val completedWorkSets = sets.filter { it.completed && !it.isWarmup }
        val names = sets.mapNotNull { exercises[it.exerciseId]?.name }.distinct()
        val volumeKg = completedWorkSets.sumOf { set ->
            val exercise = exercises[set.exerciseId]
            if (exercise?.trackingType == TrackingType.WEIGHT_REPS) set.weightKg * set.reps else 0.0
        }
        return WorkoutHistorySummary(
            session = session,
            exerciseNames = names,
            completedSetCount = completedWorkSets.size,
            totalVolumeKg = volumeKg,
            failedSetCount = sets.count { !it.completed && !it.isWarmup },
        )
    }

    fun completedSetsForExercise(exerciseId: Long): List<WorkoutSet> = completedSetsForExercise(
        readableDatabase,
        exerciseId,
    )

    private fun completedSetsForExercise(db: SQLiteDatabase, exerciseId: Long): List<WorkoutSet> = querySets(
        "ws.exercise_id = ? AND s.status = 'COMPLETED' AND ws.completed = 1",
        arrayOf(exerciseId.toString()),
        "s.ended_at ASC, ws.set_order ASC",
        db,
    )

    private fun querySets(
        selection: String,
        args: Array<String>,
        orderBy: String,
        db: SQLiteDatabase = readableDatabase,
    ): List<WorkoutSet> =
        db.rawQuery(
            """
            SELECT ws.* FROM workout_sets ws
            JOIN workout_sessions s ON s.id = ws.session_id
            WHERE $selection ORDER BY $orderBy
            """.trimIndent(),
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.toWorkoutSetOrNull()?.let(::add)
                }
            }
        }

    fun completeWorkout(sessionId: Long): WorkoutCompletionResult {
        val db = writableDatabase
        db.beginTransaction()
        var historyChanged = false
        try {
            when (workoutStatus(db, sessionId)) {
                "COMPLETED" -> {
                    val existing = prEventsForSession(db, sessionId)
                    val result = workoutCompletionResult(db, sessionId, existing)
                    db.setTransactionSuccessful()
                    return result
                }
                "DRAFT" -> Unit
                "CANCELLED" -> throw IllegalStateException("A cancelled workout cannot be completed")
                else -> throw IllegalArgumentException("Workout does not exist")
            }

            check(correctionTarget(db, sessionId) == null) {
                "A correction draft must be saved with commitWorkoutCorrection"
            }
            val sessionSets = setsForSession(db, sessionId).filter { it.completed }
            require(sessionSets.isNotEmpty()) { "Complete at least one set before finishing the workout" }
            // A user can change the system clock (or restore a draft created
            // under a later clock) while training. Persist a monotonic session
            // boundary without rejecting the user's only exit paths.
            val rawNow = System.currentTimeMillis()
            val safeEnd = rawNow.coerceAtLeast(workoutStartedAt(db, sessionId))
            val zone = ZoneId.systemDefault()
            val exercisesById = listExercises(db, includeArchived = true).associateBy { it.id }
            val decisions = sessionSets.groupBy { it.exerciseId }.flatMap { (exerciseId, currentSets) ->
                val exercise = exercisesById[exerciseId] ?: return@flatMap emptyList()
                val history = completedSetsForExercise(db, exerciseId)
                PrCalculator.evaluateSession(exercise.trackingType, currentSets, history)
                    .map { exerciseId to it }
            }
            val updated = db.update(
                "workout_sessions",
                ContentValues().apply {
                    put("status", "COMPLETED")
                    put("ended_at", safeEnd)
                    put(
                        "recorded_local_date",
                        Instant.ofEpochMilli(rawNow).atZone(zone).toLocalDate().toString(),
                    )
                    put("recorded_zone_id", zone.id)
                    putNull("rest_timer_end_at")
                },
                "id = ? AND status = 'DRAFT'",
                arrayOf(sessionId.toString()),
            )
            check(updated == 1) { "Workout completion state changed unexpectedly" }
            val emitted = decisions.map { (exerciseId, decision) ->
                insertPrDecision(db, sessionId, exerciseId, decision, rawNow)
            }
            val result = workoutCompletionResult(db, sessionId, emitted)
            db.setTransactionSuccessful()
            historyChanged = true
            return result
        } finally {
            db.endTransaction()
            if (historyChanged) invalidateWorkoutHistoryPagination()
        }
    }

    private fun workoutStatus(db: SQLiteDatabase, sessionId: Long): String? = db.rawQuery(
        "SELECT status FROM workout_sessions WHERE id = ?",
        arrayOf(sessionId.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun workoutStartedAt(db: SQLiteDatabase, sessionId: Long): Long = db.rawQuery(
        "SELECT started_at FROM workout_sessions WHERE id = ?",
        arrayOf(sessionId.toString()),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Workout does not exist" }
        cursor.getLong(0)
    }

    private fun workoutCompletionResult(
        db: SQLiteDatabase,
        sessionId: Long,
        personalRecords: List<PersonalRecord>,
    ): WorkoutCompletionResult = db.rawQuery(
        "SELECT recorded_local_date, recorded_zone_id FROM workout_sessions " +
            "WHERE id = ? AND status = 'COMPLETED'",
        arrayOf(sessionId.toString()),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Completed workout facts are missing" }
        val recordedDate = cursor.getString(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val recordedZone = cursor.getString(1)?.takeIf(String::isNotBlank)
        check(recordedDate != null && recordedZone != null) { "Completed workout calendar facts are invalid" }
        WorkoutCompletionResult(
            personalRecords = personalRecords,
            recordedLocalDate = recordedDate,
            recordedZoneId = recordedZone,
        )
    }

    private fun prEventsForSession(db: SQLiteDatabase, sessionId: Long): List<PersonalRecord> = db.rawQuery(
        "SELECT * FROM pr_events WHERE session_id = ? ORDER BY achieved_at DESC, id DESC",
        arrayOf(sessionId.toString()),
    ).use(::readPrEvents)

    fun latestPrEvents(limit: Int = 20): List<PersonalRecord> = readableDatabase.rawQuery(
        """
        SELECT pe.* FROM pr_events pe
        JOIN workout_sessions s ON s.id = pe.session_id
        WHERE s.status = 'COMPLETED'
          AND pe.session_id = (
              SELECT id FROM workout_sessions
              WHERE status = 'COMPLETED'
              ORDER BY ended_at DESC, id DESC
              LIMIT 1
          )
        ORDER BY pe.achieved_at DESC, pe.id DESC
        LIMIT ?
        """.trimIndent(),
        arrayOf(limit.coerceIn(1, 100).toString()),
    ).use(::readPrEvents)

    private fun readPrEvents(cursor: Cursor): List<PersonalRecord> =
        buildList {
            while (cursor.moveToNext()) {
                runCatching {
                    val type = enumValueOrNull<PrType>(cursor.string("pr_type")) ?: return@runCatching null
                    val eventKind = enumValueOrNull<PrEventKind>(cursor.string("event_kind")) ?: return@runCatching null
                    PersonalRecord(
                        id = cursor.long("id"),
                        exerciseId = cursor.long("exercise_id"),
                        setId = cursor.long("set_id"),
                        type = type,
                        bucketKey = cursor.string("bucket_key"),
                        value = cursor.double("value"),
                        weightKg = cursor.double("weight_kg"),
                        reps = cursor.int("reps"),
                        durationSeconds = cursor.int("duration_seconds"),
                        achievedAtMillis = cursor.long("achieved_at"),
                        eventKind = eventKind,
                    )
                }.getOrNull()?.takeIf { record ->
                    record.id > 0L && record.exerciseId > 0L && record.setId > 0L &&
                        record.bucketKey.length <= 80 &&
                        record.value.isFinite() && record.value >= 0.0 &&
                        record.weightKg.isFinite() && record.weightKg in 0.0..1_000.0 &&
                        record.reps in 0..1_000 && record.durationSeconds in 0..86_400 &&
                        record.achievedAtMillis >= 0L
                }?.let(::add)
            }
        }

    fun prSummary(exerciseId: Long): PrSummary {
        val exercise = listExercises(includeArchived = true).firstOrNull { it.id == exerciseId }
            ?: return PrSummary(exerciseId, emptyList(), null)
        val sets = completedSetsForExercise(exerciseId).filter { it.completed && !it.isWarmup }
        val valid = when (exercise.trackingType) {
            TrackingType.WEIGHT_REPS -> sets.filter { it.loadGrams > 0 && it.reps > 0 }
            TrackingType.BODYWEIGHT_REPS -> sets.filter { it.loadGrams >= 0 && it.reps > 0 }
            TrackingType.ASSISTED_REPS -> sets.filter { it.loadGrams >= 0 && it.reps > 0 }
            TrackingType.DURATION -> sets.filter { it.durationSeconds > 0 }
        }
        val values = when (exercise.trackingType) {
            TrackingType.WEIGHT_REPS -> if (valid.isEmpty()) emptyList() else listOf(
                PrDisplayValue("最大重量", "${formatKg(valid.maxOf { it.weightKg })} kg"),
                PrDisplayValue(
                    "估算 1RM",
                    valid.map { PrCalculator.estimatedOneRepMax(it.weightKg, it.reps) }
                        .filter { it > 0.0 }
                        .maxOrNull()
                        ?.let { "${formatKg(it)} kg" }
                        ?: "—",
                ),
                PrDisplayValue("5RM（训练记录）", valid.filter { it.reps == 5 }.maxOfOrNull { it.weightKg }?.let { "${formatKg(it)} kg" } ?: "—"),
            )
            TrackingType.BODYWEIGHT_REPS -> if (valid.isEmpty()) emptyList() else listOf(
                PrDisplayValue("最高次数", "${valid.maxOf { it.reps }} 次"),
                PrDisplayValue("最大额外负重", "${formatKg(valid.maxOf { it.weightKg })} kg"),
            )
            TrackingType.ASSISTED_REPS -> if (valid.isEmpty()) emptyList() else {
                val leastAssistance = valid.minWithOrNull(
                    compareBy<WorkoutSet> { it.loadGrams }.thenByDescending { it.reps }.thenByDescending { it.id },
                )!!
                val mostReps = valid.maxWithOrNull(
                    compareBy<WorkoutSet> { it.reps }.thenBy { -it.loadGrams }.thenBy { it.id },
                )!!
                listOf(
                    PrDisplayValue("最少辅助重量（${leastAssistance.reps} 次）", "${formatKg(leastAssistance.weightKg)} kg"),
                    PrDisplayValue("最高次数（辅助 ${formatKg(mostReps.weightKg)} kg）", "${mostReps.reps} 次"),
                )
            }
            TrackingType.DURATION -> if (valid.isEmpty()) emptyList() else listOf(
                PrDisplayValue("最长时长", formatDuration(valid.maxOf { it.durationSeconds })),
            )
        }
        val latest = valid.lastOrNull()
        return PrSummary(
            exerciseId = exerciseId,
            values = values,
            latestSetLabel = latest?.let { setLabel(exercise.trackingType, it) },
        )
    }

    private fun setLabel(type: TrackingType, set: WorkoutSet): String = when (type) {
        TrackingType.WEIGHT_REPS -> "${formatKg(set.weightKg)} kg × ${set.reps}"
        TrackingType.BODYWEIGHT_REPS -> "额外 ${formatKg(set.weightKg)} kg × ${set.reps}"
        TrackingType.ASSISTED_REPS -> "辅助 ${formatKg(set.weightKg)} kg × ${set.reps}"
        TrackingType.DURATION -> formatDuration(set.durationSeconds)
    }

    private fun Cursor.toExerciseOrNull(): Exercise? = runCatching {
        val name = string("name")
        val category = string("category")
        val custom = int("is_custom")
        val primary = int("is_primary")
        val archived = int("archived")
        val definitionVersion = int("definition_version")
        val trackingType = enumValueOrNull<TrackingType>(string("tracking_type")) ?: return@runCatching null
        if (long("id") <= 0L || name.isBlank() || name.codePointLength() > MAX_EXERCISE_NAME_CODE_POINTS ||
            category.isBlank() || category.codePointLength() > MAX_EXERCISE_CATEGORY_CODE_POINTS ||
            custom !in 0..1 || primary !in 0..1 || archived !in 0..1 ||
            (archived == 1 && primary == 1) || definitionVersion < 1
        ) return@runCatching null
        Exercise(
            id = long("id"),
            name = name,
            category = category,
            aliases = string("aliases").split('|').filter { it.isNotBlank() }.take(50),
            isCustom = custom == 1,
            isPrimary = primary == 1,
            trackingType = trackingType,
            definitionVersion = definitionVersion,
            isArchived = archived == 1,
        )
    }.getOrNull()

    private fun Cursor.toWorkoutSessionOrNull(): WorkoutSession? = runCatching {
        val status = enumValueOrNull<WorkoutStatus>(string("status")) ?: return@runCatching null
        val id = long("id")
        val title = string("title")
        val started = long("started_at")
        val ended = nullableLong("ended_at")
        val restEnd = nullableLong("rest_timer_end_at")
        val restDuration = int("rest_duration_seconds")
        val correctionOf = nullableLong("correction_of_session_id")
        val correctionRevision = int("correction_revision")
        val correctedAt = nullableLong("corrected_at")
        if (id <= 0L || title.isBlank() || title.length > 160 || started < 0L ||
            (ended != null && ended < started) || restDuration !in 15..3_600 || (restEnd != null && restEnd < 0L) ||
            (correctionOf != null && correctionOf <= 0L) || correctionRevision < 0 ||
            (correctedAt != null && correctedAt < 0L) ||
            (correctionRevision == 0 && correctedAt != null) ||
            (correctionRevision > 0 && correctedAt == null) ||
            (correctionOf != null && status != WorkoutStatus.DRAFT)
        ) return@runCatching null

        val storedDate = nullableString("recorded_local_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val storedZone = nullableString("recorded_zone_id")?.takeIf {
            it.length <= 80 && runCatching { ZoneId.of(it) }.isSuccess
        }
        val fallbackZone = ZoneId.systemDefault()
        val historicalMillis = ended ?: started
        WorkoutSession(
            id = id,
            title = title,
            startedAtMillis = started,
            endedAtMillis = ended,
            status = status,
            restTimerEndAtMillis = restEnd,
            restDurationSeconds = restDuration,
            recordedLocalDate = if (status == WorkoutStatus.DRAFT) null else
                storedDate ?: Instant.ofEpochMilli(historicalMillis).atZone(fallbackZone).toLocalDate(),
            recordedZoneId = if (status == WorkoutStatus.DRAFT) null else storedZone ?: fallbackZone.id,
            correctionOfSessionId = correctionOf,
            correctionRevision = correctionRevision,
            correctedAtMillis = correctedAt,
        )
    }.getOrNull()

    private fun Cursor.toWorkoutSetOrNull(): WorkoutSet? = runCatching {
        val completedValue = int("completed")
        val warmupValue = int("is_warmup")
        if (completedValue !in 0..1 || warmupValue !in 0..1) return@runCatching null
        val set = WorkoutSet(
            id = long("id"),
            sessionId = long("session_id"),
            exerciseId = long("exercise_id"),
            setOrder = int("set_order"),
            loadGrams = long("load_grams"),
            reps = int("reps"),
            durationSeconds = int("duration_seconds"),
            completed = completedValue == 1,
            isWarmup = warmupValue == 1,
            rpe = nullableDouble("rpe"),
            rir = nullableDouble("rir"),
            note = string("note"),
            supersetId = nullableString("superset_id"),
            batchId = nullableString("batch_id"),
            commitId = string("commit_id"),
        )
        validateWorkoutSet(set)
        set
    }.getOrNull()

    private fun Cursor.toMealFoodRecordOrNull(): MealFoodRecord? = runCatching {
        val record = MealFoodRecord(
            id = long("food_id"),
            name = string("food_name"),
            grams = double("food_grams"),
            per100g = Nutrition(
                kcal = double("food_kcal"),
                carbsG = double("food_carbs"),
                proteinG = double("food_protein"),
                fatG = double("food_fat"),
            ),
            sourceName = string("food_source_name"),
            portionBasis = enumValueOrNull<PortionBasis>(string("food_portion_basis")) ?: return@runCatching null,
            evidenceTier = enumValueOrNull<EvidenceTier>(string("food_evidence_tier")) ?: return@runCatching null,
            calorieSource = enumValueOrNull<CalorieSource>(string("food_calorie_source")) ?: return@runCatching null,
        )
        val per100g = record.per100g
        if (record.id <= 0L || record.name.isBlank() || record.sourceName.isBlank() ||
            !record.grams.isFinite() || record.grams !in 1.0..5_000.0 ||
            !per100g.kcal.isFinite() || per100g.kcal !in 0.0..1_000.0 ||
            !per100g.carbsG.isFinite() || per100g.carbsG !in 0.0..100.0 ||
            !per100g.proteinG.isFinite() || per100g.proteinG !in 0.0..100.0 ||
            !per100g.fatG.isFinite() || per100g.fatG !in 0.0..100.0 ||
            per100g.carbsG + per100g.proteinG + per100g.fatG > 100.5
        ) null else record
    }.getOrNull()

    private fun Cursor.toMealRecordOrNull(): MealRecord? = runCatching {
        val nutrition = Nutrition(
            kcal = double("kcal"),
            carbsG = double("carbs_g"),
            proteinG = double("protein_g"),
            fatG = double("fat_g"),
        )
        val record = MealRecord(
            id = long("id"),
            commitId = string("commit_id"),
            date = LocalDate.parse(string("meal_date")),
            title = string("title"),
            nutrition = nutrition,
            evidenceTier = enumValueOrNull<EvidenceTier>(string("evidence_tier")) ?: return@runCatching null,
            confirmedAtMillis = long("confirmed_at"),
        )
        if (record.id <= 0L || record.commitId.isBlank() || record.title.isBlank() || record.confirmedAtMillis < 0L ||
            !nutrition.kcal.isFinite() || nutrition.kcal !in 0.0..50_000.0 ||
            !nutrition.carbsG.isFinite() || nutrition.carbsG !in 0.0..5_000.0 ||
            !nutrition.proteinG.isFinite() || nutrition.proteinG !in 0.0..5_000.0 ||
            !nutrition.fatG.isFinite() || nutrition.fatG !in 0.0..5_000.0
        ) null else record
    }.getOrNull()

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.double(column: String): Double = getDouble(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.nullableLong(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun Cursor.nullableDouble(column: String): Double? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getDouble)

    private data class SeedExercise(
        val key: String,
        val name: String,
        val aliases: List<String>,
        val category: String,
        val primary: Boolean = false,
        val trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    )

    companion object {
        private const val DATABASE_NAME = "fitness_ledger.db"
        private const val DATABASE_VERSION = 11
        private const val DEFAULT_HISTORY_PAGE_SIZE = 50
        private const val MAX_HISTORY_PAGE_SIZE = 200
        private const val HISTORY_SESSION_QUERY_MIN_OVERFETCH = 32
        private const val HISTORY_SESSION_QUERY_ROW_LIMIT = 256
        private const val MAX_HISTORY_OFFSET_ANCHORS = 512
        private val externalRestoreGeneration = AtomicLong(0L)
        internal fun currentRestoreGeneration(): Long = externalRestoreGeneration.get()

        /** Called only after an external restore generation is durable. */
        internal fun invalidateProcessCachesAfterExternalRestore() {
            externalRestoreGeneration.incrementAndGet()
        }

        private val defaultExercises = listOf(
            SeedExercise("barbell_bench_press", "杠铃卧推", listOf("卧推", "bench", "wotui"), "胸", true),
            SeedExercise("barbell_back_squat", "杠铃深蹲", listOf("深蹲", "squat", "shendun"), "腿", true),
            SeedExercise("conventional_deadlift", "传统硬拉", listOf("硬拉", "deadlift", "yingla"), "背", true),
            SeedExercise("overhead_press", "站姿推举", listOf("肩推", "推举", "ohp"), "肩", true),
            SeedExercise("pull_up", "引体向上", listOf("引体", "pullup", "yinti"), "背", trackingType = TrackingType.BODYWEIGHT_REPS),
            SeedExercise("barbell_row", "杠铃划船", listOf("划船", "row", "huachuan"), "背"),
            SeedExercise("lat_pulldown", "高位下拉", listOf("下拉", "pulldown"), "背"),
            SeedExercise("incline_dumbbell_press", "上斜哑铃卧推", listOf("上斜卧推", "incline press"), "胸"),
            SeedExercise("cable_fly", "绳索夹胸", listOf("夹胸", "飞鸟", "fly"), "胸"),
            SeedExercise("lateral_raise", "哑铃侧平举", listOf("侧平举", "lateral raise"), "肩"),
            SeedExercise("barbell_curl", "杠铃弯举", listOf("弯举", "curl"), "手臂"),
            SeedExercise("triceps_pushdown", "绳索下压", listOf("下压", "pushdown"), "手臂"),
            SeedExercise("leg_press", "腿举", listOf("leg press"), "腿"),
            SeedExercise("romanian_deadlift", "罗马尼亚硬拉", listOf("罗马尼亚", "rdl"), "腿"),
            SeedExercise("plank", "平板支撑", listOf("plank", "pingban"), "核心", trackingType = TrackingType.DURATION),
        )

        private fun formatKg(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
        private fun formatDuration(seconds: Int): String = if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}

private object DraftCodec {
    fun encode(draft: MealDraft): String = JSONObject().apply {
        put("id", draft.id)
        put("commitId", draft.commitId)
        put("photoUri", draft.photoUri)
        put("state", draft.state.name)
        put("evidenceTier", draft.evidenceTier.name)
        put("evidenceReason", draft.evidenceReason)
        put("providerLabel", draft.providerLabel)
        put("analysisMode", draft.analysisMode.name)
        if (draft.targetDate == null) put("targetDate", JSONObject.NULL) else put("targetDate", draft.targetDate.toString())
        if (draft.replacesMealId == null) put("replacesMealId", JSONObject.NULL) else put("replacesMealId", draft.replacesMealId)
        put("userReviewed", draft.userReviewed)
        put("unresolvedFlags", JSONArray(draft.unresolvedFlags.map { it.name }))
        put("items", JSONArray().apply {
            draft.items.forEach { put(it.toJson()) }
        })
        put("hypotheses", JSONArray().apply {
            draft.hypotheses.forEach { hypothesis ->
                put(JSONObject().apply {
                    put("labelId", hypothesis.labelId)
                    put("rawLabel", hypothesis.rawLabel)
                    put("displayName", hypothesis.displayName)
                    put("modelScore", hypothesis.modelScore)
                    put("canonicalKey", hypothesis.canonicalKey)
                    if (hypothesis.suggestedItem == null) {
                        put("suggestedItem", JSONObject.NULL)
                    } else {
                        put("suggestedItem", hypothesis.suggestedItem.toJson())
                    }
                })
            }
        })
    }.toString()

    fun decode(payload: String): MealDraft {
        val json = JSONObject(payload)
        val items = json.getJSONArray("items").mapObjects { it.toFoodDraftItem() }
        val hypotheses = buildList {
            val values = json.optJSONArray("hypotheses") ?: JSONArray()
            for (index in 0 until values.length()) {
                val hypothesis = values.getJSONObject(index)
                add(
                    FoodHypothesis(
                        labelId = hypothesis.getInt("labelId"),
                        rawLabel = hypothesis.getString("rawLabel"),
                        displayName = hypothesis.getString("displayName"),
                        modelScore = hypothesis.getDouble("modelScore"),
                        canonicalKey = hypothesis.getString("canonicalKey"),
                        suggestedItem = hypothesis.optJSONObject("suggestedItem")?.toFoodDraftItem(),
                    ),
                )
            }
        }
        return MealDraft(
            id = json.getString("id"),
            commitId = json.getString("commitId"),
            photoUri = json.getString("photoUri"),
            state = DraftState.valueOf(json.getString("state")),
            items = items,
            evidenceTier = EvidenceTier.valueOf(json.getString("evidenceTier")),
            evidenceReason = json.getString("evidenceReason"),
            unresolvedFlags = json.getJSONArray("unresolvedFlags").toStringSet { RiskFlag.valueOf(it) },
            userReviewed = json.getBoolean("userReviewed"),
            providerLabel = json.getString("providerLabel"),
            analysisMode = runCatching {
                val fallback = if (json.optString("providerLabel").contains("交互演示")) {
                    AnalysisMode.INTERACTIVE_DEMO.name
                } else AnalysisMode.REMOTE_AI.name
                AnalysisMode.valueOf(json.optString("analysisMode", fallback))
            }.getOrDefault(AnalysisMode.INTERACTIVE_DEMO),
            targetDate = json.optString("targetDate").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            replacesMealId = if (json.isNull("replacesMealId")) null else json.optLong("replacesMealId").takeIf { it > 0L },
            hypotheses = hypotheses,
        )
    }

    private fun FoodDraftItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("grams", grams)
        put("gramsMin", gramsMin)
        put("gramsMax", gramsMax)
        put("kcal", per100g.kcal)
        put("carbs", per100g.carbsG)
        put("protein", per100g.proteinG)
        put("fat", per100g.fatG)
        put("sourceName", sourceName)
        put("portionBasis", portionBasis.name)
        put("evidenceTier", evidenceTier.name)
        put("riskFlags", JSONArray(riskFlags.map { it.name }))
        put("alternatives", JSONArray(alternatives))
        put("userModified", userModified)
        put("calorieSource", calorieSource.name)
    }

    private fun JSONObject.toFoodDraftItem(): FoodDraftItem = FoodDraftItem(
        id = getString("id"),
        name = getString("name"),
        grams = getDouble("grams"),
        gramsMin = getDouble("gramsMin"),
        gramsMax = getDouble("gramsMax"),
        per100g = Nutrition(
            kcal = getDouble("kcal"),
            carbsG = getDouble("carbs"),
            proteinG = getDouble("protein"),
            fatG = getDouble("fat"),
        ),
        sourceName = getString("sourceName"),
        portionBasis = PortionBasis.valueOf(getString("portionBasis")),
        evidenceTier = EvidenceTier.valueOf(getString("evidenceTier")),
        riskFlags = getJSONArray("riskFlags").toStringSet<RiskFlag> { RiskFlag.valueOf(it) },
        alternatives = getJSONArray("alternatives").toStringList(),
        userModified = getBoolean("userModified"),
        calorieSource = runCatching {
            CalorieSource.valueOf(optString("calorieSource", CalorieSource.LABEL_OR_DATABASE.name))
        }.getOrDefault(CalorieSource.LABEL_OR_DATABASE),
    )

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private inline fun <reified T> JSONArray.toStringSet(transform: (String) -> T): Set<T> = buildSet {
        for (index in 0 until length()) add(transform(getString(index)))
    }

    private fun JSONArray.mapObjects(transform: (JSONObject) -> FoodDraftItem): List<FoodDraftItem> = buildList {
        for (index in 0 until length()) add(transform(getJSONObject(index)))
    }
}
