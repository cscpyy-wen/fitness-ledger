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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class WorkoutDatabaseTest {
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
    fun onlyOneDraftExistsAndEmptyWorkoutCancellationDeletesWithoutHistory() {
        val first = database.startWorkout("第一场")
        val secondStart = database.startWorkout("重复点击")

        assertEquals(first.id, secondStart.id)
        assertTrue(database.cancelWorkout(first.id))
        assertFalse(database.cancelWorkout(first.id))
        assertNull(database.activeWorkout())
        assertTrue(database.listWorkoutHistory().isEmpty())
        assertEquals(0, database.writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workout_sessions WHERE id = ?",
            arrayOf(first.id.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })

        val next = database.startWorkout("第二场")
        assertNotEquals(first.id, next.id)
    }

    @Test
    fun nonEmptyCancellationKeepsHistoryAndCancelledWorkoutIsImmutable() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("保留取消历史")
        val set = database.addWorkoutSet(workoutSet(session.id, exercise.id, 80_000L, completed = true))

        assertTrue(database.cancelWorkout(session.id))
        val history = database.listWorkoutHistory().single()
        assertEquals(session.id, history.session.id)
        assertEquals(WorkoutStatus.CANCELLED, history.session.status)
        assertEquals(1, history.completedSetCount)
        assertEquals(listOf(set.id), database.setsForSession(session.id).map { it.id })
        assertFalse(database.cancelWorkout(session.id))
        assertThrows(IllegalStateException::class.java) { database.completeWorkout(session.id) }
        assertThrows(IllegalArgumentException::class.java) { database.updateWorkoutSet(set.copy(reps = 6)) }
        assertFalse(database.deleteWorkoutSet(set.id))
        assertThrows(SQLiteConstraintException::class.java) {
            database.addWorkoutSet(workoutSet(session.id, exercise.id, 82_500L, completed = true))
        }
    }

    @Test
    fun draftSetsCanBeDeletedButCompletedWorkoutIsImmutableAndCompletionCanRetry() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("状态机测试")
        val completed = database.addWorkoutSet(
            workoutSet(session.id, exercise.id, 80_000L, completed = true),
        )
        database.addWorkoutSet(
            workoutSet(session.id, exercise.id, 120_000L, completed = false),
        )

        val firstEvents = database.completeWorkout(session.id)
        val retryEvents = database.completeWorkout(session.id)

        assertTrue(firstEvents.personalRecords.isNotEmpty())
        assertEquals(
            firstEvents.personalRecords.map { it.id }.sorted(),
            retryEvents.personalRecords.map { it.id }.sorted(),
        )
        assertEquals(firstEvents.recordedLocalDate, retryEvents.recordedLocalDate)
        assertEquals(firstEvents.recordedZoneId, retryEvents.recordedZoneId)
        assertEquals(listOf(completed.id), database.completedSetsForExercise(exercise.id).map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            database.updateWorkoutSet(completed.copy(reps = 6))
        }
        assertFalse(database.deleteWorkoutSet(completed.id))
        assertThrows(SQLiteConstraintException::class.java) {
            database.addWorkoutSet(workoutSet(session.id, exercise.id, 82_500L, completed = true))
        }
    }

    @Test
    fun completionAfterWallClockRollbackKeepsSafeEndButRecordsRealCalendarAndPrTime() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("回拨后完成")
        val set = database.addWorkoutSet(workoutSet(session.id, exercise.id, 80_000L, completed = true))
        val futureStartedAt = System.currentTimeMillis() + 86_400_000L
        database.writableDatabase.execSQL(
            "UPDATE workout_sessions SET started_at = ? WHERE id = ? AND status = 'DRAFT'",
            arrayOf(futureStartedAt, session.id),
        )

        database.close()
        database = FitnessDatabase(context)
        val zone = ZoneId.systemDefault()
        val beforeCompletion = System.currentTimeMillis()
        val first = database.completeWorkout(session.id)
        val afterCompletion = System.currentTimeMillis()
        val retry = database.completeWorkout(session.id)

        val completed = requireNotNull(database.workoutHistoryDetail(session.id)).summary.session
        assertEquals(futureStartedAt, completed.endedAtMillis)
        assertEquals(first.recordedLocalDate, completed.recordedLocalDate)
        assertEquals(zone.id, first.recordedZoneId)
        assertEquals(zone.id, completed.recordedZoneId)
        assertFalse(first.recordedLocalDate.isBefore(Instant.ofEpochMilli(beforeCompletion).atZone(zone).toLocalDate()))
        assertFalse(first.recordedLocalDate.isAfter(Instant.ofEpochMilli(afterCompletion).atZone(zone).toLocalDate()))
        assertTrue(first.recordedLocalDate.isBefore(Instant.ofEpochMilli(futureStartedAt).atZone(zone).toLocalDate()))
        assertTrue(first.personalRecords.isNotEmpty())
        assertEquals(first.personalRecords.map { it.id }.sorted(), retry.personalRecords.map { it.id }.sorted())
        assertEquals(first.recordedLocalDate, retry.recordedLocalDate)
        assertEquals(first.recordedZoneId, retry.recordedZoneId)
        database.readableDatabase.rawQuery(
            "SELECT achieved_at, set_id FROM pr_events WHERE session_id = ? ORDER BY id",
            arrayOf(session.id.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val achievedAt = cursor.getLong(0)
                assertTrue(achievedAt in beforeCompletion..afterCompletion)
                assertTrue(achievedAt < futureStartedAt)
                assertEquals(first.recordedLocalDate, Instant.ofEpochMilli(achievedAt).atZone(zone).toLocalDate())
                assertEquals(set.id, cursor.getLong(1))
            }
        }
    }

    @Test
    fun nonEmptyCancellationAfterWallClockRollbackSurvivesRestart() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("回拨后取消")
        val set = database.addWorkoutSet(workoutSet(session.id, exercise.id, 60_000L, completed = true))
        val futureStartedAt = System.currentTimeMillis() + 86_400_000L
        database.writableDatabase.execSQL(
            "UPDATE workout_sessions SET started_at = ? WHERE id = ? AND status = 'DRAFT'",
            arrayOf(futureStartedAt, session.id),
        )

        database.close()
        database = FitnessDatabase(context)
        val zone = ZoneId.systemDefault()
        val beforeCancellation = System.currentTimeMillis()
        assertTrue(database.cancelWorkout(session.id))
        val afterCancellation = System.currentTimeMillis()
        assertFalse(database.cancelWorkout(session.id))

        val cancelled = requireNotNull(database.workoutHistoryDetail(session.id)).summary.session
        assertEquals(WorkoutStatus.CANCELLED, cancelled.status)
        assertEquals(futureStartedAt, cancelled.endedAtMillis)
        val recordedDate = requireNotNull(cancelled.recordedLocalDate)
        assertEquals(zone.id, cancelled.recordedZoneId)
        assertFalse(recordedDate.isBefore(Instant.ofEpochMilli(beforeCancellation).atZone(zone).toLocalDate()))
        assertFalse(recordedDate.isAfter(Instant.ofEpochMilli(afterCancellation).atZone(zone).toLocalDate()))
        assertTrue(recordedDate.isBefore(Instant.ofEpochMilli(futureStartedAt).atZone(zone).toLocalDate()))
        assertEquals(listOf(set.id), database.setsForSession(session.id).map { it.id })
        assertTrue(database.latestPrEvents().isEmpty())
    }

    @Test
    fun confirmedFiveByFiveBatchCanBeUndoneAtomicallyAndOrdersCompact() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("5x5")
        database.addWorkoutSet(workoutSet(session.id, exercise.id, 60_000L, completed = true))
        val batch = List(5) { index ->
            workoutSet(session.id, exercise.id, 80_000L, completed = true).copy(
                batchId = "batch-five",
                commitId = "batch-five:$index",
            )
        }
        val firstWrite = database.addWorkoutSets(batch)
        val retriedWrite = database.addWorkoutSets(batch)

        assertEquals(6, database.setsForSession(session.id).size)
        assertEquals(firstWrite.map { it.id }, retriedWrite.map { it.id })
        assertEquals(5, database.deleteWorkoutSetBatch("batch-five"))
        val remaining = database.setsForSession(session.id)
        assertEquals(1, remaining.size)
        assertEquals(1, remaining.single().setOrder)
    }

    @Test
    fun completedWorkoutHistoryKeepsEverySetAndSummaryAcrossRestart() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("历史测试")
        database.addWorkoutSet(workoutSet(session.id, exercise.id, 80_000L, completed = true))
        database.addWorkoutSet(workoutSet(session.id, exercise.id, 100_000L, completed = true).copy(reps = 3))
        database.completeWorkout(session.id)

        val summary = database.listWorkoutHistory().single()
        assertEquals(WorkoutStatus.COMPLETED, summary.session.status)
        assertEquals(2, summary.completedSetCount)
        assertEquals(700.0, summary.totalVolumeKg, 0.001)
        assertEquals(listOf(5, 3), database.workoutHistoryDetail(session.id).sets.map { it.set.reps })

        database.close()
        database = FitnessDatabase(context)
        assertEquals(2, database.workoutHistoryDetail(session.id).sets.size)
    }

    @Test
    fun completedWorkoutsForDateFiltersExactStableDateAndSummarizesMatchingSession() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
            val included = database.startWorkout("目标日期已完成")
            database.addWorkoutSet(workoutSet(included.id, exercise.id, 80_000L, completed = true))
            database.addWorkoutSet(
                workoutSet(included.id, exercise.id, 100_000L, completed = true).copy(reps = 3),
            )
            database.addWorkoutSet(
                workoutSet(included.id, exercise.id, 20_000L, completed = true).copy(isWarmup = true),
            )
            database.addWorkoutSet(workoutSet(included.id, exercise.id, 120_000L, completed = false))
            database.completeWorkout(included.id)
            val targetDate = checkNotNull(database.workoutHistoryDetail(included.id).summary.recordedLocalDate)

            val cancelled = database.startWorkout("同日取消")
            database.addWorkoutSet(workoutSet(cancelled.id, exercise.id, 60_000L, completed = true))
            assertTrue(database.cancelWorkout(cancelled.id))

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
            val adjacent = database.startWorkout("相邻日期已完成")
            database.addWorkoutSet(workoutSet(adjacent.id, exercise.id, 50_000L, completed = true))
            database.completeWorkout(adjacent.id)
            val adjacentRecordedDate = checkNotNull(
                database.workoutHistoryDetail(adjacent.id).summary.recordedLocalDate,
            )
            assertNotEquals(targetDate, adjacentRecordedDate)

            val summary = database.completedWorkoutsForDate(targetDate).single()
            assertEquals(included.id, summary.session.id)
            assertEquals(listOf(exercise.name), summary.exerciseNames)
            assertEquals(2, summary.completedSetCount)
            assertEquals(700.0, summary.totalVolumeKg, 0.001)
            assertEquals(1, summary.failedSetCount)
            assertEquals(targetDate, summary.recordedLocalDate)
            assertEquals(
                listOf(adjacent.id),
                database.completedWorkoutsForDate(adjacentRecordedDate).map { it.session.id },
            )
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun completionReceiptKeepsPersistedCalendarFactsAcrossTimezoneChangeAndRetry() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
            val session = database.startWorkout("跨时区完成回执")
            database.addWorkoutSet(workoutSet(session.id, exercise.id, 80_000L, completed = true))

            val first = database.completeWorkout(session.id)
            assertEquals("Pacific/Kiritimati", first.recordedZoneId)

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
            val retry = database.completeWorkout(session.id)

            assertEquals(first.recordedLocalDate, retry.recordedLocalDate)
            assertEquals(first.recordedZoneId, retry.recordedZoneId)
            assertEquals(
                listOf(session.id),
                database.completedWorkoutsForDate(first.recordedLocalDate).map { it.session.id },
            )
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun advancedSetFieldsAndRestTimerPersistAndFailedSetNeverCreatesPr() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("高级训练记录")
        val failed = database.addWorkoutSet(
            workoutSet(session.id, exercise.id, 100_000L, completed = false).copy(
                reps = 0,
                rpe = 10.0,
                rir = 0.0,
                note = "离胸失败",
                supersetId = "A",
            ),
        )
        val completed = database.addWorkoutSet(
            workoutSet(session.id, exercise.id, 80_000L, completed = true).copy(
                rpe = 8.5,
                rir = 1.5,
                note = "动作稳定",
                supersetId = "A",
            ),
        )
        val timer = database.startRestTimer(session.id, 90)

        assertEquals(90, timer.restDurationSeconds)
        assertNotNull(timer.restTimerEndAtMillis)
        database.close()
        database = FitnessDatabase(context)

        val restored = database.activeWorkout()
        assertEquals(90, restored?.restDurationSeconds)
        assertNotNull(restored?.restTimerEndAtMillis)
        val restoredSets = database.setsForSession(session.id)
        assertEquals("离胸失败", restoredSets.first { it.id == failed.id }.note)
        assertEquals(0.0, restoredSets.first { it.id == failed.id }.rir ?: -1.0, 0.001)
        assertEquals("A", restoredSets.first { it.id == completed.id }.supersetId)

        val events = database.completeWorkout(session.id)
        assertTrue(events.personalRecords.isNotEmpty())
        assertTrue(events.personalRecords.none { it.setId == failed.id })
        val summary = database.listWorkoutHistory().single()
        assertEquals(1, summary.completedSetCount)
        assertEquals(1, summary.failedSetCount)
        assertNull(summary.session.restTimerEndAtMillis)
    }

    @Test
    fun processRestartClearsRestDeadlineMadeImplausibleByWallClockRollback() {
        val syntheticWallNow = 1_000_000L
        val session = database.startWorkout("回拨后的休息计时")
        database.startRestTimer(session.id, 120)
        database.writableDatabase.execSQL(
            "UPDATE workout_sessions SET rest_timer_end_at = ? WHERE id = ?",
            arrayOf(syntheticWallNow + 86_400_000L, session.id),
        )

        database.close()
        database = FitnessDatabase(context)
        val reconciled = database.activeWorkoutWithRestTimerReconciled(syntheticWallNow)

        assertEquals(session.id, reconciled?.id)
        assertNull(reconciled?.restTimerEndAtMillis)
        assertNull(database.activeWorkout()?.restTimerEndAtMillis)
    }

    @Test
    fun workoutHistoryUsesStableBoundedPagesWithoutLoss() {
        val expected = insertHistoricalSessions(validCount = 105)

        val first = database.listWorkoutHistory(limit = 50, offset = 0)
        val second = database.listWorkoutHistory(limit = 50, offset = 50)
        val third = database.listWorkoutHistory(limit = 50, offset = 100)
        val history = first + second + third
        assertEquals(listOf(50, 50, 5), listOf(first.size, second.size, third.size))
        assertEquals(105, history.map { it.session.id }.distinct().size)
        assertEquals(expected.asReversed(), history.map { it.session.title })
        val stats = database.workoutHistoryQueryStatsForTest()
        assertTrue(stats.maxSessionRowsReturnedByOneQuery <= stats.maxSessionRowsRequestedByOneQuery)
        assertTrue(stats.maxSessionRowsRequestedByOneQuery <= 256)
    }

    @Test
    fun corruptHistoryRowsDoNotTruncateOrShiftValidPages() {
        val expected = insertHistoricalSessions(validCount = 55, corruptEvery = 4)

        val first = database.listWorkoutHistory(limit = 50, offset = 0)
        val second = database.listWorkoutHistory(limit = 50, offset = 50)
        val secondPageStats = database.workoutHistoryQueryStatsForTest()
        assertEquals(50, first.size)
        assertEquals(5, second.size)
        assertEquals(55, (first + second).map { it.session.id }.distinct().size)
        assertEquals(expected.asReversed(), (first + second).map { it.session.title })
        assertTrue((first + second).none { it.session.title.startsWith("损坏") })
        assertTrue(secondPageStats.sessionRowsReturned < 20)
    }

    @Test
    fun keysetHistoryPagesBoundEverySqlChunkAcrossLargeCorruptLedger() {
        val expected = insertHistoricalSessions(
            validCount = 1_005,
            corruptEvery = 5,
            sameTimestampGroupSize = 3,
            mixStatuses = true,
        )
        val actual = mutableListOf<String>()
        val seenStatuses = mutableSetOf<WorkoutStatus>()
        var after: WorkoutHistoryCursor? = null
        do {
            val page = database.listWorkoutHistoryPage(limit = 73, after = after)
            assertTrue(page.items.size <= 73)
            actual += page.items.map { it.session.title }
            seenStatuses += page.items.map { it.session.status }
            val stats = database.workoutHistoryQueryStatsForTest()
            assertTrue(stats.sessionQueryCount >= 1)
            assertTrue(stats.maxSessionRowsReturnedByOneQuery <= stats.maxSessionRowsRequestedByOneQuery)
            assertTrue(stats.maxSessionRowsRequestedByOneQuery <= 256)
            after = page.nextCursor
        } while (after != null)

        assertEquals(1_005, actual.size)
        assertEquals(1_005, actual.distinct().size)
        assertEquals(expected.asReversed(), actual)
        assertEquals(setOf(WorkoutStatus.COMPLETED, WorkoutStatus.CANCELLED), seenStatuses)
    }

    @Test
    fun deepKeysetQueryUsesExpressionIndexRangeSearch() {
        assertThrows(IllegalArgumentException::class.java) {
            database.listWorkoutHistoryPage(after = WorkoutHistoryCursor(sortAtMillis = -1L, sessionId = 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.listWorkoutHistoryPage(after = WorkoutHistoryCursor(sortAtMillis = 1L, sessionId = 0L))
        }
        insertDeepHistorySessions(20_000)
        val anchor = database.writableDatabase.rawQuery(
            "SELECT COALESCE(ended_at, started_at), id FROM workout_sessions " +
                "WHERE status IN ('COMPLETED','CANCELLED') " +
                "ORDER BY COALESCE(ended_at, started_at) DESC, id DESC LIMIT 1 OFFSET 19000",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            WorkoutHistoryCursor(sortAtMillis = cursor.getLong(0), sessionId = cursor.getLong(1))
        }

        val plan = database.workoutHistoryQueryPlanForTest(anchor)
        assertTrue(plan.any {
            it.contains("SEARCH", ignoreCase = true) &&
                it.contains("idx_workout_history_sort_v7", ignoreCase = true)
        })
        assertFalse(plan.any { it.contains("SCAN workout_sessions", ignoreCase = true) })
        assertFalse(plan.any { it.contains("TEMP B-TREE", ignoreCase = true) })

        val page = database.listWorkoutHistoryPage(limit = 50, after = anchor)
        assertEquals(50, page.items.size)
        assertEquals(50, page.items.map { it.session.id }.distinct().size)
        assertTrue(page.items.all { summary ->
            val sortAtMillis = summary.session.endedAtMillis ?: summary.session.startedAtMillis
            sortAtMillis < anchor.sortAtMillis ||
                (sortAtMillis == anchor.sortAtMillis && summary.session.id < anchor.sessionId)
        })
    }

    @Test
    fun keysetDoesNotTreatPartiallyConsumedShortSqlResultAsExhausted() {
        val expected = insertHistoricalSessions(validCount = 80)

        val first = database.listWorkoutHistoryPage(limit = 50)
        assertEquals(50, first.items.size)
        assertNotNull(first.nextCursor)
        val second = database.listWorkoutHistoryPage(limit = 50, after = first.nextCursor)

        assertEquals(30, second.items.size)
        assertNull(second.nextCursor)
        assertEquals(expected.asReversed(), (first.items + second.items).map { it.session.title })
    }

    @Test
    fun singleSetCommitIdIsIdempotentAndRejectsPayloadReuse() {
        val exercise = database.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = database.startWorkout("幂等测试")
        val request = workoutSet(session.id, exercise.id, 80_000L, completed = true)
            .copy(commitId = "single-set-stable-id")

        val first = database.addWorkoutSet(request)
        val retry = database.addWorkoutSet(request)

        assertEquals(first.id, retry.id)
        assertEquals(1, database.setsForSession(session.id).size)
        assertThrows(IllegalArgumentException::class.java) {
            database.addWorkoutSet(request.copy(loadGrams = 90_000L))
        }
        assertEquals(1, database.setsForSession(session.id).size)
    }

    @Test
    fun fiveByFiveCommitJournalSurvivesRepositoryRecreationUntilAcknowledged() {
        val repositoryOne = FitnessRepository(context)
        val exercise = repositoryOne.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repositoryOne.startWorkout("持久幂等")
        val loadGrams = 77_500L
        val firstCommit = "five-by-five-recreation"
        repositoryOne.prepareFiveByFiveCommit(session.id, exercise.id, loadGrams, firstCommit)
        val repositoryTwo = FitnessRepository(context)
        assertEquals(firstCommit, repositoryTwo.pendingFiveByFiveCommit()?.batchId)
        repositoryTwo.commitPendingFiveByFive()
        val repositoryAfterProcessRecreation = FitnessRepository(context)
        val pending = requireNotNull(repositoryAfterProcessRecreation.pendingFiveByFiveCommit())
        assertTrue(repositoryAfterProcessRecreation.isFiveByFiveCommitVisible(pending))
        assertEquals(5, repositoryAfterProcessRecreation.setsForSession(session.id).size)
        repositoryAfterProcessRecreation.acknowledgeFiveByFiveCommit(firstCommit)
        assertNull(FitnessRepository(context).pendingFiveByFiveCommit())
    }

    @Test
    fun assistedSummaryKeepsEveryDisplayedExtremePairedWithItsCondition() {
        val exercise = database.addCustomExercise("辅助引体", "背", TrackingType.ASSISTED_REPS, true)
        val session = database.startWorkout("辅助动作")
        database.addWorkoutSet(workoutSet(session.id, exercise.id, 5_000L, true).copy(reps = 1))
        database.addWorkoutSet(workoutSet(session.id, exercise.id, 20_000L, true).copy(reps = 12))
        database.completeWorkout(session.id)

        val values = database.prSummary(exercise.id).values.associate { it.label to it.value }
        assertEquals("5 kg", values["最少辅助重量（1 次）"])
        assertEquals("12 次", values["最高次数（辅助 20 kg）"])
    }

    @Test
    fun customExerciseNamesUseUnicodeWhitespaceUniquenessAndCodePointLimits() {
        val created = database.addCustomExercise("  Cable　 Row  ", "背", TrackingType.WEIGHT_REPS, false)
        assertEquals("Cable　 Row", created.name)
        val conflict = assertThrows(IllegalArgumentException::class.java) {
            database.addCustomExercise("cable row", "背", TrackingType.WEIGHT_REPS, false)
        }
        assertTrue(conflict.message.orEmpty().contains("同名"))

        val sixtyEmoji = buildString { repeat(60) { append("😀") } }
        assertEquals(60, sixtyEmoji.codePointCount(0, sixtyEmoji.length))
        database.addCustomExercise(sixtyEmoji, "其他", TrackingType.DURATION, false)
        assertThrows(IllegalArgumentException::class.java) {
            database.addCustomExercise(sixtyEmoji + "x", "其他", TrackingType.DURATION, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.addCustomExercise("合法名称", "x".repeat(31), TrackingType.DURATION, false)
        }
    }

    @Test
    fun customExerciseEditArchiveAndRestorePreserveIdentityTrackingAndHistory() {
        val created = database.addCustomExercise("器械划船", "背", TrackingType.WEIGHT_REPS, true)
        val session = database.startWorkout("自定义动作历史")
        database.addWorkoutSet(workoutSet(session.id, created.id, 60_000L, true).copy(reps = 8))
        database.completeWorkout(session.id)

        val edited = database.updateCustomExercise(created.id, "坐姿器械划船", "背部", isPrimary = true)
        assertEquals(created.id, edited.id)
        assertEquals(created.trackingType, edited.trackingType)
        assertEquals(created.definitionVersion + 1, edited.definitionVersion)
        assertEquals("坐姿器械划船", edited.name)
        assertEquals("背部", edited.category)
        assertTrue(edited.isPrimary)
        assertFalse(edited.isArchived)

        val archived = database.setCustomExerciseArchived(created.id, archived = true)
        assertEquals(created.id, archived.id)
        assertEquals(created.trackingType, archived.trackingType)
        assertTrue(archived.isArchived)
        assertFalse(archived.isPrimary)
        assertEquals(edited.definitionVersion + 1, archived.definitionVersion)
        assertFalse(database.listExercises().any { it.id == created.id })
        assertTrue(database.listExercises(includeArchived = true).any { it.id == created.id && it.isArchived })
        assertThrows(IllegalArgumentException::class.java) {
            database.setExercisePrimary(created.id, true)
        }

        val editedWhileArchived = database.updateCustomExercise(
            created.id,
            "坐姿划船（归档）",
            "背部",
            isPrimary = true,
        )
        assertTrue(editedWhileArchived.isArchived)
        assertFalse(editedWhileArchived.isPrimary)
        assertEquals(archived.definitionVersion + 1, editedWhileArchived.definitionVersion)

        val restored = database.setCustomExerciseArchived(created.id, archived = false)
        assertEquals(created.id, restored.id)
        assertEquals(created.trackingType, restored.trackingType)
        assertFalse(restored.isArchived)
        assertFalse(restored.isPrimary)
        assertEquals(editedWhileArchived.definitionVersion + 1, restored.definitionVersion)
        assertTrue(database.listExercises().any { it.id == created.id })
        assertEquals(created.id, database.workoutHistoryDetail(session.id).sets.single().exercise.id)
        assertTrue(database.workoutHistoryDetail(session.id).personalRecords.all { it.exerciseId == created.id })
    }

    @Test
    fun customExerciseEditUsesGlobalNormalizedNameUniquenessAndRejectsBuiltins() {
        val first = database.addCustomExercise("Cable　 Row", "背", TrackingType.WEIGHT_REPS, false)
        val second = database.addCustomExercise("胸托划船", "背", TrackingType.WEIGHT_REPS, false)
        val sameNormalizedSelf = database.updateCustomExercise(
            first.id,
            "cable row",
            "背部",
            isPrimary = false,
        )
        assertEquals(first.id, sameNormalizedSelf.id)
        assertEquals(first.trackingType, sameNormalizedSelf.trackingType)

        database.setCustomExerciseArchived(second.id, archived = true)
        val conflict = assertThrows(IllegalArgumentException::class.java) {
            database.updateCustomExercise(first.id, " 胸托划船 ", "背", isPrimary = false)
        }
        assertTrue(conflict.message.orEmpty().contains("同名"))
        assertEquals("cable row", database.listExercises(includeArchived = true).first { it.id == first.id }.name)

        val builtin = database.listExercises().first { !it.isCustom }
        assertThrows(IllegalArgumentException::class.java) {
            database.updateCustomExercise(builtin.id, "不可编辑", "其他", isPrimary = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.setCustomExerciseArchived(builtin.id, archived = true)
        }
    }

    @Test
    fun versionTwoMigrationKeepsNewestDraftAndPreservesAndReordersSets() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    status TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE meal_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    meal_id INTEGER NOT NULL,
                    item_name TEXT NOT NULL,
                    grams REAL NOT NULL,
                    kcal_per_100g REAL NOT NULL,
                    carbs_per_100g REAL NOT NULL,
                    protein_per_100g REAL NOT NULL,
                    fat_per_100g REAL NOT NULL,
                    source_name TEXT NOT NULL,
                    portion_basis TEXT NOT NULL,
                    evidence_tier TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    exercise_id INTEGER NOT NULL,
                    set_order INTEGER NOT NULL,
                    load_grams INTEGER NOT NULL,
                    reps INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL,
                    is_warmup INTEGER NOT NULL,
                    rpe REAL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE body_measurements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_date TEXT NOT NULL,
                    weight_kg REAL NOT NULL,
                    waist_cm REAL
                )
                """.trimIndent(),
            )
            legacy.execSQL("INSERT INTO workout_sessions(id,title,started_at,status) VALUES(1,'old',100,'DRAFT')")
            legacy.execSQL("INSERT INTO workout_sessions(id,title,started_at,status) VALUES(2,'new',200,'DRAFT')")
            legacy.execSQL("INSERT INTO workout_sets(session_id,exercise_id,set_order,load_grams,reps,completed,is_warmup) VALUES(2,7,1,80000,5,1,0)")
            legacy.execSQL("INSERT INTO workout_sets(session_id,exercise_id,set_order,load_grams,reps,completed,is_warmup) VALUES(2,7,1,82500,5,1,0)")
            legacy.version = 2
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase

        assertEquals(2L, database.activeWorkout()?.id)
        assertEquals(listOf(1, 2), database.setsForSession(2).map { it.setOrder })
        val oldStatus = upgraded.rawQuery("SELECT status FROM workout_sessions WHERE id = 1", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertEquals("CANCELLED", oldStatus)
        val hasBatchColumn = upgraded.rawQuery("PRAGMA table_info(workout_sets)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "batch_id") found = true
            }
            found
        }
        assertTrue(hasBatchColumn)
        val upgradedSetColumns = upgraded.rawQuery("PRAGMA table_info(workout_sets)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(setOf("rir", "note", "superset_id").all { it in upgradedSetColumns })
        val upgradedSessionColumns = upgraded.rawQuery("PRAGMA table_info(workout_sessions)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(setOf("rest_timer_end_at", "rest_duration_seconds").all { it in upgradedSessionColumns })
        val hasCalorieSource = upgraded.rawQuery("PRAGMA table_info(meal_items)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "calorie_source") found = true
            }
            found
        }
        assertTrue(hasCalorieSource)
        upgraded.rawQuery("SELECT 1 FROM saved_foods LIMIT 1", null).use { cursor ->
            assertEquals(1, cursor.columnCount)
        }
    }

    @Test
    fun versionFourMigrationAddsAdvancedTrainingColumnsWithoutLosingDraftSet() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    status TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    exercise_id INTEGER NOT NULL,
                    set_order INTEGER NOT NULL,
                    load_grams INTEGER NOT NULL,
                    reps INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL,
                    is_warmup INTEGER NOT NULL,
                    rpe REAL,
                    batch_id TEXT
                )
                """.trimIndent(),
            )
            legacy.execSQL("INSERT INTO workout_sessions(id,title,started_at,status) VALUES(1,'v4 draft',100,'DRAFT')")
            legacy.execSQL(
                "INSERT INTO workout_sets(id,session_id,exercise_id,set_order,load_grams,reps,completed,is_warmup,rpe) " +
                    "VALUES(1,1,7,1,80000,5,1,0,8.5)",
            )
            legacy.version = 4
        }

        database = FitnessDatabase(context)
        val restoredSession = database.activeWorkout()
        val restoredSet = database.setsForSession(1).single()

        assertEquals("v4 draft", restoredSession?.title)
        assertEquals(120, restoredSession?.restDurationSeconds)
        assertNull(restoredSession?.restTimerEndAtMillis)
        assertEquals(8.5, restoredSet.rpe ?: 0.0, 0.001)
        assertNull(restoredSet.rir)
        assertEquals("", restoredSet.note)
        assertNull(restoredSet.supersetId)
    }

    @Test
    fun versionThreeMigrationReachesLatestSchema() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE meal_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, meal_id INTEGER NOT NULL,
                    item_name TEXT NOT NULL, grams REAL NOT NULL, kcal_per_100g REAL NOT NULL,
                    carbs_per_100g REAL NOT NULL, protein_per_100g REAL NOT NULL,
                    fat_per_100g REAL NOT NULL, source_name TEXT NOT NULL,
                    portion_basis TEXT NOT NULL, evidence_tier TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
                    started_at INTEGER NOT NULL, ended_at INTEGER, status TEXT NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
                    exercise_id INTEGER NOT NULL, set_order INTEGER NOT NULL,
                    load_grams INTEGER NOT NULL, reps INTEGER NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0, completed INTEGER NOT NULL,
                    is_warmup INTEGER NOT NULL, rpe REAL, batch_id TEXT
                )
                """.trimIndent(),
            )
            legacy.version = 3
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase
        assertEquals(11, upgraded.version)
        assertTrue(upgraded.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_workout_history_sort_v7' LIMIT 1",
            null,
        ).use { it.moveToFirst() })
        assertTrue(tableColumns(upgraded, "workout_sets").containsAll(listOf("commit_id", "rir", "note", "superset_id")))
        assertTrue(tableColumns(upgraded, "workout_sessions").containsAll(listOf("recorded_local_date", "recorded_zone_id")))
        assertTrue(tableColumns(upgraded, "meal_items").contains("calorie_source"))
    }

    @Test
    fun versionFiveMigrationBackfillsStableDateCommitIdsAndResolvesDuplicateExerciseNames() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        val completedAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE exercises (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, builtin_key TEXT UNIQUE, name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL, aliases TEXT NOT NULL, category TEXT NOT NULL,
                    is_custom INTEGER NOT NULL, is_primary INTEGER NOT NULL, tracking_type TEXT NOT NULL,
                    definition_version INTEGER NOT NULL DEFAULT 1, archived INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, started_at INTEGER NOT NULL,
                    ended_at INTEGER, status TEXT NOT NULL, rest_timer_end_at INTEGER,
                    rest_duration_seconds INTEGER NOT NULL DEFAULT 120
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE workout_sets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
                    exercise_id INTEGER NOT NULL, set_order INTEGER NOT NULL, load_grams INTEGER NOT NULL,
                    reps INTEGER NOT NULL, duration_seconds INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL, is_warmup INTEGER NOT NULL, rpe REAL, rir REAL,
                    note TEXT NOT NULL DEFAULT '', superset_id TEXT, batch_id TEXT
                )
                """.trimIndent(),
            )
            // A real v5 database has this lifecycle trigger. The v6 commit-id
            // backfill must temporarily remove it or historical completed sets
            // abort the entire upgrade.
            legacy.execSQL(
                """
                CREATE TRIGGER workout_set_update_requires_draft
                BEFORE UPDATE ON workout_sets
                WHEN COALESCE((SELECT status FROM workout_sessions WHERE id = OLD.session_id), '') <> 'DRAFT'
                  OR NEW.session_id <> OLD.session_id
                BEGIN SELECT RAISE(ABORT, 'sets can only be changed in their draft workout'); END
                """.trimIndent(),
            )
            legacy.execSQL("INSERT INTO exercises(id,builtin_key,name,normalized_name,aliases,category,is_custom,is_primary,tracking_type,definition_version,archived) VALUES(1,'cable_row','Cable Row','cable row','','背',0,1,'WEIGHT_REPS',1,0)")
            legacy.execSQL("INSERT INTO exercises(id,name,normalized_name,aliases,category,is_custom,is_primary,tracking_type,definition_version,archived) VALUES(2,' cable　 row ','cable　 row','','背',1,0,'WEIGHT_REPS',1,0)")
            legacy.execSQL("INSERT INTO workout_sessions(id,title,started_at,ended_at,status) VALUES(1,'跨时区',${completedAt - 3_600_000L},$completedAt,'COMPLETED')")
            legacy.execSQL("INSERT INTO workout_sets(id,session_id,exercise_id,set_order,load_grams,reps,completed,is_warmup) VALUES(1,1,1,1,80000,5,1,0)")
            legacy.version = 5
        }

        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            database = FitnessDatabase(context)
            val upgraded = database.writableDatabase
            assertEquals(11, upgraded.version)
            assertEquals("legacy-set-1", upgraded.rawQuery("SELECT commit_id FROM workout_sets WHERE id=1", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            })
            assertEquals(2, database.listExercises(includeArchived = true).map { it.name }.distinct().size)

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val history = database.listWorkoutHistory(limit = 10).single()
            assertEquals(LocalDate.of(2026, 1, 1), history.recordedLocalDate)
            assertEquals("Asia/Shanghai", history.recordedZoneId)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun versionSevenMigrationCreatesCompletedWorkoutDatePartialIndexWithoutLosingRows() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
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
                    recorded_zone_id TEXT
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO workout_sessions(" +
                    "id,title,started_at,ended_at,status,recorded_local_date,recorded_zone_id" +
                    ") VALUES(1,'v7 completed',100,200,'COMPLETED','2026-08-29','Asia/Shanghai')",
            )
            legacy.execSQL(
                "INSERT INTO workout_sessions(" +
                    "id,title,started_at,ended_at,status,recorded_local_date,recorded_zone_id" +
                    ") VALUES(2,'v7 cancelled',300,400,'CANCELLED','2026-08-29','Asia/Shanghai')",
            )
            legacy.version = 7
        }

        database = FitnessDatabase(context)
        val upgraded = database.writableDatabase

        assertEquals(11, upgraded.version)
        assertEquals(2, upgraded.rawQuery("SELECT COUNT(*) FROM workout_sessions", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })
        val indexSql = upgraded.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'idx_completed_workout_date_v8'",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertTrue(
            indexSql.replace(Regex("\\s+"), " ").contains(
                "ON workout_sessions(recorded_local_date, ended_at DESC, id DESC)",
                ignoreCase = true,
            ),
        )
        assertTrue(indexSql.contains("WHERE status = 'COMPLETED'", ignoreCase = true))
        assertEquals(
            listOf("recorded_local_date", "ended_at", "id"),
            upgraded.rawQuery("PRAGMA index_info(idx_completed_workout_date_v8)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            },
        )
    }

    @Test
    fun databaseGuardsFutureWritesAndDefensiveReadersSkipCorruptRows() {
        val db = database.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO body_measurements(recorded_date,weight_kg) VALUES('bad-date',10)")
        }

        db.execSQL("DROP TRIGGER validate_body_measurements_values_v6_insert")
        db.execSQL("INSERT INTO body_measurements(recorded_date,weight_kg) VALUES('bad-date',80)")
        assertTrue(database.listBodyMeasurements().isEmpty())

        val validExerciseCount = database.listExercises(includeArchived = true).size
        db.execSQL("DROP TRIGGER validate_exercises_values_v6_insert")
        db.execSQL(
            "INSERT INTO exercises(name,normalized_name,aliases,category,is_custom,is_primary,tracking_type,definition_version,archived) " +
                "VALUES('损坏动作','损坏动作','','其他',1,0,'UNKNOWN_TYPE',1,0)",
        )
        assertEquals(validExerciseCount, database.listExercises(includeArchived = true).size)
    }

    private fun tableColumns(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun insertHistoricalSessions(
        validCount: Int,
        corruptEvery: Int? = null,
        sameTimestampGroupSize: Int = 1,
        mixStatuses: Boolean = false,
    ): List<String> {
        require(validCount > 0)
        require(sameTimestampGroupSize > 0)
        val db = database.writableDatabase
        if (corruptEvery != null) {
            require(corruptEvery >= 2)
            db.execSQL("DROP TRIGGER validate_workout_sessions_values_v6_insert")
        }
        val statement = db.compileStatement(
            "INSERT INTO workout_sessions(" +
                "title,started_at,ended_at,status,rest_duration_seconds,recorded_local_date,recorded_zone_id" +
                ") VALUES(?,?,?,?,?,?,?)",
        )
        val titles = mutableListOf<String>()
        var physicalIndex = 0
        db.beginTransaction()
        try {
            while (titles.size < validCount) {
                val corrupt = corruptEvery != null && physicalIndex % corruptEvery == 0
                val title = if (corrupt) "损坏历史 $physicalIndex" else "有效历史 ${titles.size}"
                val startedAt = 1_000_000L + (physicalIndex / sameTimestampGroupSize) * 2L
                statement.clearBindings()
                statement.bindString(1, title)
                statement.bindLong(2, startedAt)
                statement.bindLong(3, startedAt + 1L)
                statement.bindString(
                    4,
                    if (mixStatuses && physicalIndex % 2 == 0) "COMPLETED" else "CANCELLED",
                )
                statement.bindLong(5, if (corrupt) 1L else 120L)
                statement.bindString(6, "2026-01-01")
                statement.bindString(7, "Asia/Shanghai")
                statement.executeInsert()
                if (!corrupt) titles += title
                physicalIndex += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            statement.close()
        }
        return titles
    }

    private fun insertDeepHistorySessions(count: Int) {
        require(count >= 20_000)
        val db = database.writableDatabase
        val statement = db.compileStatement(
            "INSERT INTO workout_sessions(" +
                "title,started_at,ended_at,status,rest_duration_seconds,recorded_local_date,recorded_zone_id" +
                ") VALUES(?,?,?,?,?,?,?)",
        )
        db.beginTransaction()
        try {
            repeat(count) { index ->
                val startedAt = 10_000_000L + (index / 4) * 2L
                statement.clearBindings()
                statement.bindString(1, "深页历史 $index")
                statement.bindLong(2, startedAt)
                statement.bindLong(3, startedAt + 1L)
                statement.bindString(4, if (index % 2 == 0) "COMPLETED" else "CANCELLED")
                statement.bindLong(5, 120L)
                statement.bindString(6, "2026-01-01")
                statement.bindString(7, "Asia/Shanghai")
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            statement.close()
        }
    }

    private fun workoutSet(
        sessionId: Long,
        exerciseId: Long,
        loadGrams: Long,
        completed: Boolean,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setOrder = 1,
        loadGrams = loadGrams,
        reps = 5,
        completed = completed,
    )

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
    }
}
