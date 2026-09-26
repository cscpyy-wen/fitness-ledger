package com.personal.fitnessledger.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutCorrectionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: FitnessDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = FitnessDatabase(context)
        database.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun clockRollbackCannotChangeExistingPrsWhenUnrelatedHistoryIsDeletedOrCorrected() {
        val exercise = weightExercises().first()
        val first = database.startWorkout("future-clock baseline")
        database.addWorkoutSet(workoutSet(first.id, exercise.id, 100_000L, 5))
        database.writableDatabase.execSQL(
            "UPDATE workout_sessions SET started_at = ? WHERE id = ? AND status = 'DRAFT'",
            arrayOf(System.currentTimeMillis() + 86_400_000L, first.id),
        )
        database.completeWorkout(first.id)
        val secondId = completeSession("normal-clock improvement", exercise, 110_000L, 5)
        fun signature(id: Long) = database.workoutHistoryDetail(id).personalRecords
            .map { listOf(it.type, it.bucketKey, it.value, it.eventKind, it.achievedAtMillis) }
            .toSet()
        val firstBefore = signature(first.id)
        val secondBefore = signature(secondId)
        assertTrue(database.workoutHistoryDetail(secondId).personalRecords.any { it.eventKind == PrEventKind.BROKEN })

        val cancelled = database.startWorkout("unrelated cancellation")
        // Empty cancelled workouts are intentionally discarded, so retain one
        // failed set to exercise deletion of an actual CANCELLED history row.
        database.addWorkoutSet(workoutSet(cancelled.id, exercise.id, 90_000L, 1).copy(completed = false))
        database.cancelWorkout(cancelled.id)
        database.deleteWorkoutHistorySession(cancelled.id)
        assertEquals(firstBefore, signature(first.id))
        assertEquals(secondBefore, signature(secondId))

        val correction = database.startWorkoutCorrection(first.id)
        database.commitWorkoutCorrection(correction.id)
        assertEquals(firstBefore, signature(first.id))
        assertEquals(secondBefore, signature(secondId))
    }

    @Test
    fun correctionDraftSurvivesReopenAndAbandonLeavesOriginalByteForByteEquivalent() {
        val exercise = weightExercises().first()
        val originalId = completeSession("原场", exercise, 80_000L, 5)
        val originalBefore = database.workoutHistoryDetail(originalId)

        val correction = database.startWorkoutCorrection(originalId)
        val correctionSetsBefore = database.setsForSession(correction.id)
        assertEquals(originalId, correction.correctionOfSessionId)
        assertEquals(originalBefore, database.workoutHistoryDetail(originalId))

        database.close()
        database = FitnessDatabase(context)
        val recovered = requireNotNull(database.activeWorkout())
        assertEquals(correction.id, recovered.id)
        assertEquals(originalId, recovered.correctionOfSessionId)
        assertEquals(correctionSetsBefore, database.setsForSession(recovered.id))
        assertEquals(originalBefore, database.workoutHistoryDetail(originalId))

        assertTrue(database.abandonWorkoutCorrection(recovered.id))
        assertNull(database.activeWorkout())
        assertEquals(originalBefore, database.workoutHistoryDetail(originalId))
        assertEquals(0, guardCount())
    }

    @Test
    fun correctionCanChangeExerciseAndAllSetFieldsDeleteAndAddWhilePreservingOriginalReceipt() {
        val (firstExercise, secondExercise) = weightExercises().take(2)
        val originalId = completeSession("动作误录", firstExercise, 80_000L, 5)
        val original = database.workoutHistoryDetail(originalId).summary.session
        val correction = database.startWorkoutCorrection(originalId)
        val copied = database.setsForSession(correction.id).single()

        database.updateWorkoutSet(
            copied.copy(
                exerciseId = secondExercise.id,
                loadGrams = 92_500L,
                reps = 4,
                rpe = 8.5,
                rir = 1.0,
                note = "动作、重量、次数与强度均已更正",
            ),
        )
        val extra = database.addWorkoutSet(
            workoutSet(correction.id, secondExercise.id, 70_000L, 8).copy(note = "新增组"),
        )
        assertTrue(database.deleteWorkoutSet(extra.id))
        database.addWorkoutSet(
            workoutSet(correction.id, secondExercise.id, 75_000L, 6).copy(note = "替代新增组"),
        )

        val result = database.commitWorkoutCorrection(correction.id)
        val corrected = database.workoutHistoryDetail(originalId)

        assertEquals(originalId, result.sessionId)
        assertEquals(1, result.correctionRevision)
        assertEquals(original.id, corrected.summary.session.id)
        assertEquals(original.startedAtMillis, corrected.summary.session.startedAtMillis)
        assertEquals(original.endedAtMillis, corrected.summary.session.endedAtMillis)
        assertEquals(original.recordedLocalDate, corrected.summary.session.recordedLocalDate)
        assertEquals(original.recordedZoneId, corrected.summary.session.recordedZoneId)
        assertEquals(1, corrected.summary.session.correctionRevision)
        assertNotNull(corrected.summary.session.correctedAtMillis)
        assertNull(database.activeWorkout())
        assertEquals(listOf(secondExercise.id, secondExercise.id), corrected.sets.map { it.exercise.id })
        assertEquals(listOf(1, 2), corrected.sets.map { it.set.setOrder })
        assertEquals(listOf(92_500L, 75_000L), corrected.sets.map { it.set.loadGrams })
        assertEquals(listOf(4, 6), corrected.sets.map { it.set.reps })
        assertEquals(8.5, corrected.sets.first().set.rpe ?: Double.NaN, 0.0)
        assertEquals(1.0, corrected.sets.first().set.rir ?: Double.NaN, 0.0)
        assertEquals("动作、重量、次数与强度均已更正", corrected.sets.first().set.note)
        assertTrue(corrected.personalRecords.isNotEmpty())
        assertEquals(0, guardCount())
        assertEquals("ok", pragmaSingleText("integrity_check"))
        assertEquals(0, database.writableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { it.count })
    }

    @Test
    fun repeatedCorrectionCarriesBaseRevisionAcrossReopenAndAdvancesExactlyOnce() {
        val exercise = weightExercises().first()
        val originalId = completeSession("重复更正", exercise, 80_000L, 5)
        val firstDraft = database.startWorkoutCorrection(originalId)
        assertEquals(1, database.commitWorkoutCorrection(firstDraft.id).correctionRevision)

        val secondDraft = database.startWorkoutCorrection(originalId)
        val secondSets = database.setsForSession(secondDraft.id)
        database.close()
        database = FitnessDatabase(context)
        val recovered = requireNotNull(database.activeWorkout())
        assertEquals(secondDraft.id, recovered.id)
        assertEquals(0, recovered.correctionRevision)
        assertEquals(secondSets, database.setsForSession(recovered.id))

        val result = database.commitWorkoutCorrection(recovered.id)

        assertEquals(2, result.correctionRevision)
        assertEquals(2, database.workoutHistoryDetail(originalId).summary.session.correctionRevision)
        assertEquals(0, guardCount())
    }

    @Test
    fun correctingEarlySessionDeterministicallyRecalculatesLaterPrEvents() {
        val exercise = weightExercises().first()
        val earlyId = completeSession("早期", exercise, 80_000L, 5)
        val laterId = completeSession("后期", exercise, 100_000L, 5)
        assertTrue(database.workoutHistoryDetail(laterId).personalRecords.any { it.eventKind == PrEventKind.BROKEN })

        val correction = database.startWorkoutCorrection(earlyId)
        val copied = database.setsForSession(correction.id).single()
        database.updateWorkoutSet(copied.copy(loadGrams = 110_000L))
        database.commitWorkoutCorrection(correction.id)

        val earlyEvents = database.workoutHistoryDetail(earlyId).personalRecords
        val laterEvents = database.workoutHistoryDetail(laterId).personalRecords
        assertTrue(earlyEvents.isNotEmpty())
        assertTrue(earlyEvents.all { it.eventKind == PrEventKind.BASELINE })
        assertFalse(laterEvents.any { it.eventKind == PrEventKind.BROKEN })
        assertEquals(laterEvents.map { it.id }.distinct().size, laterEvents.size)
        assertEquals(0, danglingPrCount())
    }

    @Test
    fun deletingEarlySessionMakesRemainingSessionBaselineAndUpdatesHistoryAndDateSummary() {
        val exercise = weightExercises().first()
        val earlyId = completeSession("删除早期", exercise, 80_000L, 5)
        val date = requireNotNull(database.workoutHistoryDetail(earlyId).summary.recordedLocalDate)
        val laterId = completeSession("保留后期", exercise, 100_000L, 5)

        val result = database.deleteWorkoutHistorySession(earlyId)

        assertEquals(earlyId, result.sessionId)
        assertThrows(IllegalArgumentException::class.java) { database.workoutHistoryDetail(earlyId) }
        assertEquals(listOf(laterId), database.listWorkoutHistory().map { it.session.id })
        assertEquals(listOf(laterId), database.completedWorkoutsForDate(date).map { it.session.id })
        val remainingEvents = database.workoutHistoryDetail(laterId).personalRecords
        assertTrue(remainingEvents.isNotEmpty())
        assertTrue(remainingEvents.all { it.eventKind == PrEventKind.BASELINE })
        assertEquals(0, danglingPrCount())
        assertEquals(0, guardCount())
    }

    @Test
    fun directSqlCannotRewriteCompletedOrCancelledReceiptsSetsOrPrAndPrParentMustMatch() {
        val (exercise, otherExercise) = weightExercises().take(2)
        val completedId = completeSession("锁定完成态", exercise, 80_000L, 5)
        val completedDetail = database.workoutHistoryDetail(completedId)
        val completedSetId = completedDetail.sets.single().set.id
        val pr = completedDetail.personalRecords.first()
        val cancelled = database.startWorkout("锁定取消态")
        val cancelledSet = database.addWorkoutSet(workoutSet(cancelled.id, exercise.id, 60_000L, 6))
        assertTrue(database.cancelWorkout(cancelled.id))
        val db = database.writableDatabase

        assertConstraint { db.execSQL("UPDATE workout_sessions SET title = '篡改' WHERE id = $completedId") }
        assertConstraint { db.execSQL("DELETE FROM workout_sessions WHERE id = $completedId") }
        assertConstraint { db.execSQL("UPDATE workout_sessions SET recorded_zone_id = 'UTC' WHERE id = ${cancelled.id}") }
        assertConstraint { db.execSQL("DELETE FROM workout_sessions WHERE id = ${cancelled.id}") }
        assertConstraint { db.execSQL("UPDATE workout_sets SET reps = 99 WHERE id = $completedSetId") }
        assertConstraint { db.execSQL("DELETE FROM workout_sets WHERE id = $completedSetId") }
        assertConstraint { db.execSQL("UPDATE workout_sets SET note = '篡改' WHERE id = ${cancelledSet.id}") }
        assertConstraint { db.execSQL("DELETE FROM workout_sets WHERE id = ${cancelledSet.id}") }
        assertConstraint { db.execSQL("UPDATE pr_events SET value = value + 1 WHERE id = ${pr.id}") }
        assertConstraint { db.execSQL("DELETE FROM pr_events WHERE id = ${pr.id}") }
        assertConstraint {
            db.execSQL(
                "INSERT INTO pr_events(session_id,exercise_id,set_id,pr_type,bucket_key,event_kind,value," +
                    "weight_kg,reps,duration_seconds,achieved_at) VALUES(" +
                    "$completedId,${otherExercise.id},$completedSetId,'MAX_WEIGHT','','BASELINE',80,80,5,0,1)",
            )
        }

        val draft = database.startWorkout("PR 不得挂草稿")
        val draftSet = database.addWorkoutSet(workoutSet(draft.id, exercise.id, 50_000L, 5))
        assertConstraint {
            db.execSQL(
                "INSERT INTO pr_events(session_id,exercise_id,set_id,pr_type,bucket_key,event_kind,value," +
                    "weight_kg,reps,duration_seconds,achieved_at) VALUES(" +
                    "${draft.id},${exercise.id},${draftSet.id},'MAX_WEIGHT','','BASELINE',50,50,5,0,1)",
            )
        }
    }

    @Test
    fun rebuildPrGuardOnlyUnlocksPrDeletionNotCompletedSessionOrSets() {
        val exercise = weightExercises().first()
        val completedId = completeSession("PR 重建最小权限", exercise, 80_000L, 5)
        val detail = database.workoutHistoryDetail(completedId)
        val setId = detail.sets.single().set.id
        val prId = detail.personalRecords.first().id
        val db = database.writableDatabase
        db.execSQL(
            "INSERT INTO workout_rewrite_guards(session_id,reason,created_at) VALUES(?, 'REBUILD_PR', ?)",
            arrayOf(completedId, System.currentTimeMillis()),
        )

        assertConstraint { db.execSQL("UPDATE workout_sessions SET title = '越权' WHERE id = $completedId") }
        assertConstraint { db.execSQL("UPDATE workout_sessions SET status = 'DRAFT' WHERE id = $completedId") }
        assertConstraint { db.execSQL("DELETE FROM workout_sessions WHERE id = $completedId") }
        assertConstraint { db.execSQL("UPDATE workout_sets SET reps = 99 WHERE id = $setId") }
        assertConstraint { db.execSQL("DELETE FROM workout_sets WHERE id = $setId") }
        assertEquals(1, db.delete("pr_events", "id = ?", arrayOf(prId.toString())))

        db.delete("workout_rewrite_guards", "session_id = ?", arrayOf(completedId.toString()))
        assertEquals("PR 重建最小权限", database.workoutHistoryDetail(completedId).summary.session.title)
        assertEquals(5, database.workoutHistoryDetail(completedId).sets.single().set.reps)
        assertEquals(0, guardCount())
    }

    @Test
    fun injectedFailuresRollbackOriginalAndKeepCorrectionDraftRecoverable() {
        val exercise = weightExercises().first()
        val originalId = completeSession("回滚", exercise, 80_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val changed = database.setsForSession(correction.id).single().copy(loadGrams = 90_000L, reps = 4)
        database.updateWorkoutSet(changed)
        val originalBefore = database.workoutHistoryDetail(originalId)
        val draftBefore = requireNotNull(database.activeWorkout())
        val draftSetsBefore = database.setsForSession(correction.id)

        listOf(
            WorkoutRewriteFailurePoint.AFTER_CORRECTION_DRAFT_REMOVED,
            WorkoutRewriteFailurePoint.AFTER_ORIGINAL_SETS_REPLACED,
            WorkoutRewriteFailurePoint.DURING_PR_REBUILD,
        ).forEach { point ->
            database.failNextWorkoutRewriteForTest(point)
            assertThrows(IllegalStateException::class.java) {
                database.commitWorkoutCorrection(correction.id)
            }
            assertEquals(originalBefore, database.workoutHistoryDetail(originalId))
            assertEquals(draftBefore, database.activeWorkout())
            assertEquals(draftSetsBefore, database.setsForSession(correction.id))
            assertEquals(0, guardCount())
        }

        database.commitWorkoutCorrection(correction.id)
        assertNull(database.activeWorkout())
        assertEquals(90_000L, database.workoutHistoryDetail(originalId).sets.single().set.loadGrams)
    }

    @Test
    fun deleteFailureAfterHistoryRemovalRollsBackSessionSetsAndPr() {
        val exercise = weightExercises().first()
        val sessionId = completeSession("删除回滚", exercise, 82_500L, 5)
        val before = database.workoutHistoryDetail(sessionId)
        val rawBefore = workoutLedgerRows()

        database.failNextWorkoutRewriteForTest(WorkoutRewriteFailurePoint.AFTER_HISTORY_DELETE)
        assertThrows(IllegalStateException::class.java) {
            database.deleteWorkoutHistorySession(sessionId)
        }

        assertEquals(before, database.workoutHistoryDetail(sessionId))
        assertEquals(rawBefore, workoutLedgerRows())
        assertEquals(0, guardCount())
        assertEquals(0, danglingPrCount())
    }

    @Test
    fun deletingCancelledHistoryRejectsMalformedTargetSetBeforeAnyDelete() {
        val exercise = weightExercises().first()
        val cancelled = database.startWorkout("取消场畸形组")
        val set = database.addWorkoutSet(workoutSet(cancelled.id, exercise.id, 60_000L, 6))
        assertTrue(database.cancelWorkout(cancelled.id))
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sets",
        ) {
            db.execSQL("UPDATE workout_sets SET completed = 2 WHERE id = ${set.id}")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.deleteWorkoutHistorySession(cancelled.id)
        }

        assertTrue(failure.message.orEmpty().contains("待删除训练历史 训练组含无法安全解析"))
        assertEquals(before, workoutLedgerRows())
        assertEquals("CANCELLED", rawSessionStatus(cancelled.id))
        assertEquals(0, guardCount())
    }

    @Test
    fun startCorrectionRejectsUndecodableSourceSetWithoutChangingAnyLedgerRow() {
        val exercise = weightExercises().first()
        val originalId = completeSession("不可解码组", exercise, 80_000L, 5)
        val setId = database.workoutHistoryDetail(originalId).sets.single().set.id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sets",
        ) {
            db.execSQL("UPDATE workout_sets SET completed = 2 WHERE id = $setId")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.startWorkoutCorrection(originalId)
        }

        assertTrue(failure.message.orEmpty().contains("训练组含无法安全解析"))
        assertEquals(before, workoutLedgerRows())
        assertNull(database.activeWorkout())
        assertEquals(0, guardCount())
    }

    @Test
    fun startCorrectionRejectsDanglingExerciseSetWithoutChangingAnyLedgerRow() {
        val exercise = weightExercises().first()
        val originalId = completeSession("悬挂动作组", exercise, 80_000L, 5)
        val setId = database.workoutHistoryDetail(originalId).sets.single().set.id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sets",
        ) {
            db.setForeignKeyConstraintsEnabled(false)
            try {
                db.execSQL("UPDATE workout_sets SET exercise_id = 999999 WHERE id = $setId")
            } finally {
                db.setForeignKeyConstraintsEnabled(true)
            }
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.startWorkoutCorrection(originalId)
        }

        assertTrue(failure.message.orEmpty().contains("训练组引用了缺失动作"))
        assertEquals(before, workoutLedgerRows())
        assertNull(database.activeWorkout())
        assertEquals(0, guardCount())
    }

    @Test
    fun startCorrectionRejectsUndecodableAssociatedPrWithoutChangingAnyLedgerRow() {
        val exercise = weightExercises().first()
        val originalId = completeSession("不可解码 PR", exercise, 80_000L, 5)
        val prId = database.workoutHistoryDetail(originalId).personalRecords.first().id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "pr_events",
        ) {
            db.execSQL("UPDATE pr_events SET pr_type = 'BROKEN_LEGACY' WHERE id = $prId")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.startWorkoutCorrection(originalId)
        }

        assertTrue(failure.message.orEmpty().contains("关联 PR 含无法安全解析"))
        assertEquals(before, workoutLedgerRows())
        assertNull(database.activeWorkout())
        assertEquals(0, guardCount())
    }

    @Test
    fun commitCorrectionRejectsValidButChangedOriginalFingerprintBeforeAnyDelete() {
        val exercise = weightExercises().first()
        val originalId = completeSession("指纹漂移", exercise, 80_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val originalSetId = database.workoutHistoryDetail(originalId).sets.single().set.id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sets",
        ) {
            db.execSQL("UPDATE workout_sets SET note = '外部有效改动' WHERE id = $originalSetId")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.commitWorkoutCorrection(correction.id)
        }

        assertTrue(failure.message.orEmpty().contains("更正草稿创建后已变化"))
        assertEquals(before, workoutLedgerRows())
        assertEquals("COMPLETED", rawSessionStatus(originalId))
        assertEquals(correction.id, requireNotNull(database.activeWorkout()).id)
        assertEquals(0, guardCount())
    }

    @Test
    fun commitCorrectionRejectsOriginalRevisionDriftBeforeAnyDelete() {
        val exercise = weightExercises().first()
        val originalId = completeSession("版本漂移", exercise, 80_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sessions",
        ) {
            db.execSQL(
                "UPDATE workout_sessions SET correction_revision = 1, corrected_at = ? WHERE id = ?",
                arrayOf(System.currentTimeMillis(), originalId),
            )
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.commitWorkoutCorrection(correction.id)
        }

        assertTrue(failure.message.orEmpty().contains("更正版本已变化"))
        assertEquals(before, workoutLedgerRows())
        assertEquals("COMPLETED", rawSessionStatus(originalId))
        assertEquals(correction.id, requireNotNull(database.activeWorkout()).id)
        assertEquals(0, guardCount())
    }

    @Test
    fun commitCorrectionRejectsUndecodableDraftSetBeforeAnyDelete() {
        val exercise = weightExercises().first()
        val originalId = completeSession("草稿畸形", exercise, 80_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val draftSetId = database.setsForSession(correction.id).single().id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "workout_sets",
        ) {
            db.execSQL("UPDATE workout_sets SET completed = 2 WHERE id = $draftSetId")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.commitWorkoutCorrection(correction.id)
        }

        assertTrue(failure.message.orEmpty().contains("更正草稿 训练组含无法安全解析"))
        assertEquals(before, workoutLedgerRows())
        assertEquals("COMPLETED", rawSessionStatus(originalId))
        assertEquals(correction.id, requireNotNull(database.activeWorkout()).id)
        assertEquals(0, guardCount())
    }

    @Test
    fun malformedLegacyExerciseAbortsPrRebuildWithoutDroppingOldPrOrCorrectionDraft() {
        val exercise = weightExercises().first()
        val malformedUnrelatedExercise = weightExercises().first { it.id != exercise.id }
        val originalId = completeSession("保留旧 PR", exercise, 80_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val prCountBefore = database.writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pr_events",
            null,
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "exercises",
        ) {
            db.execSQL(
                "UPDATE exercises SET tracking_type = 'BROKEN_LEGACY' WHERE id = ${malformedUnrelatedExercise.id}",
            )
        }
        val rawBefore = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.commitWorkoutCorrection(correction.id)
        }

        assertTrue(failure.message.orEmpty().contains("动作库含无法安全解析"))
        assertEquals(rawBefore, workoutLedgerRows())
        assertEquals("COMPLETED", rawSessionStatus(originalId))
        assertEquals(correction.id, requireNotNull(database.activeWorkout()).id)
        assertEquals(
            prCountBefore,
            db.rawQuery("SELECT COUNT(*) FROM pr_events", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            },
        )
        assertEquals(0, guardCount())
    }

    @Test
    fun malformedUnrelatedPrAbortsFullRebuildBeforeAnyPrOrHistoryDelete() {
        val exercise = weightExercises().first()
        val originalId = completeSession("更正目标", exercise, 80_000L, 5)
        val unrelatedId = completeSession("畸形 PR 所属场", exercise, 90_000L, 5)
        val correction = database.startWorkoutCorrection(originalId)
        val malformedPrId = database.workoutHistoryDetail(unrelatedId).personalRecords.first().id
        val db = database.writableDatabase
        injectExternalCorruptionWithTriggersRestored(
            db,
            "pr_events",
        ) {
            db.execSQL("UPDATE pr_events SET event_kind = 'BROKEN_LEGACY' WHERE id = $malformedPrId")
        }
        val before = workoutLedgerRows()

        val failure = assertThrows(IllegalStateException::class.java) {
            database.commitWorkoutCorrection(correction.id)
        }

        assertTrue(failure.message.orEmpty().contains("现有 PR 含无法安全解析"))
        assertEquals(before, workoutLedgerRows())
        assertEquals("COMPLETED", rawSessionStatus(originalId))
        assertEquals(correction.id, requireNotNull(database.activeWorkout()).id)
        assertEquals(0, guardCount())
    }

    @Test
    fun duplicateLegacyPrRowsAreNormalizedAndUniqueIndexIsRestoredByRebuild() {
        val exercise = weightExercises().first()
        val originalId = completeSession("重复 PR", exercise, 80_000L, 5)
        val db = database.writableDatabase
        db.execSQL("DROP INDEX IF EXISTS idx_pr_event_key")
        db.execSQL(
            "INSERT INTO pr_events(session_id,exercise_id,set_id,pr_type,bucket_key,event_kind,value," +
                "weight_kg,reps,duration_seconds,achieved_at) " +
                "SELECT session_id,exercise_id,set_id,pr_type,bucket_key,event_kind,value," +
                "weight_kg,reps,duration_seconds,achieved_at FROM pr_events WHERE session_id = $originalId LIMIT 1",
        )
        assertTrue(duplicatePrKeyCount() > 0)

        val correction = database.startWorkoutCorrection(originalId)
        database.commitWorkoutCorrection(correction.id)

        assertEquals(0, duplicatePrKeyCount())
        assertTrue(indexExists(db, "idx_pr_event_key"))
        assertTrue(database.workoutHistoryDetail(originalId).personalRecords.isNotEmpty())
        assertEquals(0, danglingPrCount())
        assertEquals(0, guardCount())
    }

    @Test
    fun onOpenSkipsDeleteForEmptyGuardTableButCleansActualResidualRows() {
        assertEquals(0, database.workoutRewriteGuardCleanupDeleteAttemptsForTest())
        val exercise = weightExercises().first()
        val completedId = completeSession("残留 guard", exercise, 80_000L, 5)
        database.writableDatabase.execSQL(
            "INSERT INTO workout_rewrite_guards(session_id,reason,created_at) VALUES(?, 'REBUILD_PR', ?)",
            arrayOf(completedId, System.currentTimeMillis()),
        )
        assertEquals(1, guardCount())

        database.close()
        database = FitnessDatabase(context)
        database.writableDatabase
        assertEquals(1, database.workoutRewriteGuardCleanupDeleteAttemptsForTest())
        assertEquals(0, guardCount())

        database.close()
        database = FitnessDatabase(context)
        database.writableDatabase
        assertEquals(0, database.workoutRewriteGuardCleanupDeleteAttemptsForTest())
        assertEquals(0, guardCount())
    }

    @Test
    fun versionNineMigrationAddsCorrectionSchemaAndLocksExistingReceiptWithoutChangingIds() {
        val freshSchema = workoutCorrectionSchemaSignature(database.writableDatabase)
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE exercises(id INTEGER PRIMARY KEY AUTOINCREMENT,builtin_key TEXT UNIQUE,name TEXT NOT NULL," +
                    "normalized_name TEXT NOT NULL,aliases TEXT NOT NULL,category TEXT NOT NULL,is_custom INTEGER NOT NULL," +
                    "is_primary INTEGER NOT NULL,tracking_type TEXT NOT NULL,definition_version INTEGER NOT NULL DEFAULT 1," +
                    "archived INTEGER NOT NULL DEFAULT 0)",
            )
            legacy.execSQL(
                "CREATE TABLE workout_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,started_at INTEGER NOT NULL," +
                    "ended_at INTEGER,status TEXT NOT NULL,rest_timer_end_at INTEGER,rest_duration_seconds INTEGER NOT NULL DEFAULT 120," +
                    "recorded_local_date TEXT,recorded_zone_id TEXT)",
            )
            legacy.execSQL(
                "CREATE TABLE workout_sets(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE," +
                    "exercise_id INTEGER NOT NULL REFERENCES exercises(id),set_order INTEGER NOT NULL,load_grams INTEGER NOT NULL,reps INTEGER NOT NULL," +
                    "duration_seconds INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL,is_warmup INTEGER NOT NULL,rpe REAL,rir REAL,note TEXT NOT NULL DEFAULT ''," +
                    "superset_id TEXT,batch_id TEXT,commit_id TEXT NOT NULL)",
            )
            legacy.execSQL(
                "CREATE TABLE pr_events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE," +
                    "exercise_id INTEGER NOT NULL REFERENCES exercises(id),set_id INTEGER NOT NULL REFERENCES workout_sets(id) ON DELETE CASCADE," +
                    "pr_type TEXT NOT NULL,bucket_key TEXT NOT NULL DEFAULT '',event_kind TEXT NOT NULL,value REAL NOT NULL,weight_kg REAL NOT NULL," +
                    "reps INTEGER NOT NULL,duration_seconds INTEGER NOT NULL DEFAULT 0,achieved_at INTEGER NOT NULL)",
            )
            legacy.execSQL("INSERT INTO exercises VALUES(1,'bench','卧推','卧推','','胸',0,1,'WEIGHT_REPS',1,0)")
            legacy.execSQL("INSERT INTO workout_sessions VALUES(7,'v9 完成场',100,200,'COMPLETED',NULL,120,'2026-08-30','Asia/Shanghai')")
            legacy.execSQL("INSERT INTO workout_sessions VALUES(8,'v9 畸形保留场',300,NULL,'COMPLETED',NULL,1,'2026-08-30','Asia/Shanghai')")
            legacy.execSQL("INSERT INTO workout_sets VALUES(11,7,1,1,80000,5,0,1,0,8.5,1,'原备注',NULL,NULL,'legacy-11')")
            legacy.execSQL("INSERT INTO pr_events VALUES(13,7,1,11,'MAX_WEIGHT','','BASELINE',80,80,5,0,200)")
            legacy.execSQL(
                "CREATE TRIGGER validate_workout_sessions_values_v6_update BEFORE UPDATE ON workout_sessions " +
                    "WHEN NEW.rest_duration_seconds < 15 " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid workout_sessions values'); END",
            )
            legacy.version = 9
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase
        assertEquals(11, upgraded.version)
        assertTrue(tableColumns(upgraded, "workout_sessions").containsAll(
            setOf("correction_of_session_id", "correction_revision", "corrected_at"),
        ))
        assertTrue(tableExists(upgraded, "workout_rewrite_guards"))
        assertEquals(7L, database.workoutHistoryDetail(7).summary.session.id)
        assertEquals(11L, database.workoutHistoryDetail(7).sets.single().set.id)
        assertEquals(13L, database.workoutHistoryDetail(7).personalRecords.single().id)
        upgraded.rawQuery(
            "SELECT ended_at, rest_duration_seconds FROM workout_sessions WHERE id = 8",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(300L, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
        }
        assertTrue(triggerExists(upgraded, "validate_workout_sessions_values_v6_update"))
        assertConstraint { upgraded.execSQL("UPDATE workout_sessions SET title = '篡改' WHERE id = 7") }
        assertEquals(0, guardCount())
        assertEquals(freshSchema, workoutCorrectionSchemaSignature(upgraded))

        val rawBeforeRejectedStart = workoutLedgerRows()
        val failure = assertThrows(IllegalStateException::class.java) {
            database.startWorkoutCorrection(8)
        }
        assertTrue(failure.message.orEmpty().contains("无法安全解析"))
        assertEquals(rawBeforeRejectedStart, workoutLedgerRows())
        assertEquals(0, guardCount())
    }

    private fun completeSession(title: String, exercise: Exercise, loadGrams: Long, reps: Int): Long {
        val session = database.startWorkout(title)
        database.addWorkoutSet(workoutSet(session.id, exercise.id, loadGrams, reps))
        database.completeWorkout(session.id)
        return session.id
    }

    private fun workoutSet(
        sessionId: Long,
        exerciseId: Long,
        loadGrams: Long,
        reps: Int,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setOrder = 1,
        loadGrams = loadGrams,
        reps = reps,
        completed = true,
    )

    private fun weightExercises(): List<Exercise> =
        database.listExercises().filter { it.trackingType == TrackingType.WEIGHT_REPS }

    private fun guardCount(): Int = database.writableDatabase.rawQuery(
        "SELECT COUNT(*) FROM workout_rewrite_guards",
        null,
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }

    private fun danglingPrCount(): Int = database.writableDatabase.rawQuery(
        "SELECT COUNT(*) FROM pr_events pe LEFT JOIN workout_sessions s ON s.id=pe.session_id " +
            "LEFT JOIN workout_sets ws ON ws.id=pe.set_id WHERE s.id IS NULL OR ws.id IS NULL " +
            "OR ws.session_id<>pe.session_id OR ws.exercise_id<>pe.exercise_id",
        null,
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }

    private fun duplicatePrKeyCount(): Int = database.writableDatabase.rawQuery(
        "SELECT COUNT(*) FROM (SELECT 1 FROM pr_events " +
            "GROUP BY session_id, exercise_id, pr_type, bucket_key HAVING COUNT(*) > 1)",
        null,
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }

    private fun workoutLedgerRows(): Map<String, List<String>> = linkedMapOf(
        "workout_sessions" to rawRows("workout_sessions"),
        "workout_sets" to rawRows("workout_sets"),
        "pr_events" to rawRows("pr_events"),
        "workout_rewrite_guards" to rawRows("workout_rewrite_guards"),
    )

    private fun rawRows(table: String): List<String> = database.writableDatabase.rawQuery(
        "SELECT * FROM $table ORDER BY rowid",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    buildString {
                        repeat(cursor.columnCount) { column ->
                            val value = when (cursor.getType(column)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "N"
                                android.database.Cursor.FIELD_TYPE_INTEGER -> "I${cursor.getLong(column)}"
                                android.database.Cursor.FIELD_TYPE_FLOAT ->
                                    "F${java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))}"
                                android.database.Cursor.FIELD_TYPE_STRING -> "S${cursor.getString(column)}"
                                android.database.Cursor.FIELD_TYPE_BLOB ->
                                    "B${cursor.getBlob(column).joinToString(",") { (it.toInt() and 0xff).toString() }}"
                                else -> error("Unsupported cursor field type")
                            }
                            append(value.length).append(':').append(value).append('|')
                        }
                    },
                )
            }
        }
    }

    private fun rawSessionStatus(sessionId: Long): String = database.writableDatabase.rawQuery(
        "SELECT status FROM workout_sessions WHERE id = ?",
        arrayOf(sessionId.toString()),
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }

    private fun pragmaSingleText(name: String): String = database.writableDatabase.rawQuery(
        "PRAGMA $name",
        null,
    ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }

    private fun assertConstraint(block: () -> Unit) {
        assertThrows(SQLiteConstraintException::class.java, block)
    }

    /**
     * Models a database that was corrupted by an older build or an external
     * writer without weakening the schema used by the production operation
     * under test. Fresh and upgraded schemas need not retain the same generations
     * of validators, so discover every trigger on the target table whose header
     * fires on UPDATE. All definitions that actually exist are restored from
     * sqlite_master after the one corrupting statement and verified present
     * before control returns to the test.
     */
    private fun injectExternalCorruptionWithTriggersRestored(
        db: android.database.sqlite.SQLiteDatabase,
        tableName: String,
        corruption: () -> Unit,
    ) {
        val identifier = Regex("[A-Za-z0-9_]+")
        require(identifier.matches(tableName)) { "Unsafe table name: $tableName" }
        val updateTriggerHeader = Regex(
            "\\b(?:BEFORE|AFTER|INSTEAD\\s+OF)\\s+UPDATE(?:\\s+OF\\b|\\s+ON\\b)",
            RegexOption.IGNORE_CASE,
        )
        val triggerSqlByName = linkedMapOf<String, String>()
        db.rawQuery(
            "SELECT name,sql FROM sqlite_master " +
                "WHERE type='trigger' AND tbl_name=? AND sql IS NOT NULL ORDER BY name",
            arrayOf(tableName),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val triggerName = cursor.getString(0)
                val triggerSql = cursor.getString(1)
                val beginIndex = triggerSql.indexOf("BEGIN", ignoreCase = true)
                val triggerHeader = if (beginIndex >= 0) triggerSql.substring(0, beginIndex) else triggerSql
                if (updateTriggerHeader.containsMatchIn(triggerHeader)) {
                    triggerSqlByName[triggerName] = triggerSql
                }
            }
        }
        check(triggerSqlByName.isNotEmpty()) {
            "No UPDATE trigger found before corruption on table: $tableName"
        }
        triggerSqlByName.keys.forEach { triggerName ->
            require(identifier.matches(triggerName)) { "Unsafe trigger name: $triggerName" }
            db.execSQL("DROP TRIGGER $triggerName")
        }
        try {
            corruption()
        } finally {
            triggerSqlByName.values.forEach { triggerSql -> db.execSQL(triggerSql) }
        }
        triggerSqlByName.keys.forEach { triggerName ->
            assertTrue(
                "Production trigger was not restored after external-corruption setup: $triggerName",
                triggerExists(db, triggerName),
            )
        }
    }

    private fun tableColumns(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun tableExists(db: android.database.sqlite.SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun triggerExists(db: android.database.sqlite.SQLiteDatabase, trigger: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1",
            arrayOf(trigger),
        ).use { it.moveToFirst() }

    private fun indexExists(db: android.database.sqlite.SQLiteDatabase, index: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=? LIMIT 1",
            arrayOf(index),
        ).use { it.moveToFirst() }

    private fun workoutCorrectionSchemaSignature(
        db: android.database.sqlite.SQLiteDatabase,
    ): List<String> = buildList {
        db.rawQuery("PRAGMA table_info(workout_sessions)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name.startsWith("correction_") || name == "corrected_at") {
                    add(
                        "column:$name:" +
                            cursor.getString(cursor.getColumnIndexOrThrow("type")) + ":" +
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) + ":" +
                            (cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")) ?: "NULL"),
                    )
                }
            }
        }
        val names = listOf(
            "workout_rewrite_guards",
            "idx_workout_correction_target_v10",
            "validate_workout_session_update",
            "workout_session_locked_update_v10",
            "workout_session_locked_delete_v10",
            "workout_correction_values_insert_v10",
            "workout_correction_values_update_v10",
            "workout_guard_values_insert_v10",
            "workout_guard_values_update_v10",
            "workout_set_insert_requires_draft",
            "workout_set_update_requires_draft",
            "workout_set_delete_requires_draft",
            "pr_event_parent_consistency_v10",
            "pr_event_update_locked_v10",
            "pr_event_delete_guarded_v10",
        )
        val placeholders = names.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT type,name,sql FROM sqlite_master WHERE name IN ($placeholders) ORDER BY type,name",
            names.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    "${cursor.getString(0)}:${cursor.getString(1)}:" +
                        cursor.getString(2).trim().replace(Regex("\\s+"), " "),
                )
            }
        }
    }.sorted()

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
    }
}
