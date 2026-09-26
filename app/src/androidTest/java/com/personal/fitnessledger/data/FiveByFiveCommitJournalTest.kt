package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FiveByFiveCommitJournalTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun journalSurvivesRecreationAndRetryWritesExactlyOneFiveRowBatch() {
        val first = FitnessRepository(context)
        val exercise = first.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = first.startWorkout("5×5 持久幂等")
        first.prepareFiveByFiveCommit(session.id, exercise.id, 80_000L, "stable-five-operation")

        val recreated = FitnessRepository(context)
        assertEquals("stable-five-operation", recreated.pendingFiveByFiveCommit()?.batchId)
        val committed = recreated.commitPendingFiveByFive()
        val retry = recreated.commitPendingFiveByFive()

        assertEquals(committed.sets.map { it.id }, retry.sets.map { it.id })
        assertEquals(5, recreated.setsForSession(session.id).size)
        assertNotNull(recreated.pendingFiveByFiveCommit())

        recreated.acknowledgeFiveByFiveCommit("stable-five-operation")
        assertNull(FitnessRepository(context).pendingFiveByFiveCommit())
    }

    @Test
    fun queuedDifferentBatchIsRejectedUntilReceiptIsAcknowledged() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("防 5×5 连点")
        repository.prepareFiveByFiveCommit(session.id, exercise.id, 100_000L, "first-five-click")
        repository.commitPendingFiveByFive()

        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFiveByFiveCommit(session.id, exercise.id, 100_000L, "queued-second-click")
        }
        assertEquals(5, repository.setsForSession(session.id).size)

        repository.acknowledgeFiveByFiveCommit("first-five-click")
        repository.prepareFiveByFiveCommit(session.id, exercise.id, 100_000L, "deliberate-next-batch")
        repository.commitPendingFiveByFive()
        assertEquals(10, repository.setsForSession(session.id).size)
    }

    @Test
    fun twentyConcurrentReplaysOfOneDialogStillWriteOneFiveRowBatch() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("20 路并发 5×5")
        val batchId = "one-dialog-twenty-workers"
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(20)

        try {
            val futures = (0 until 20).map {
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    val concurrentRepository = FitnessRepository(context)
                    concurrentRepository.prepareFiveByFiveCommit(
                        session.id,
                        exercise.id,
                        80_000L,
                        batchId,
                    )
                    concurrentRepository.commitPendingFiveByFive()
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val sets = repository.setsForSession(session.id)
        assertEquals(5, sets.size)
        assertEquals(setOf(batchId), sets.mapNotNull { it.batchId }.toSet())
        assertEquals((0 until 5).map { "$batchId:$it" }.toSet(), sets.map { it.commitId }.toSet())
    }

    @Test
    fun staleCallbackAfterAcknowledgementReusesDialogBatchIdWithoutDuplicates() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("旧回调重放")
        val dialogBatchId = "same-visible-dialog"

        repository.prepareFiveByFiveCommit(session.id, exercise.id, 60_000L, dialogBatchId)
        repository.commitPendingFiveByFive()
        repository.acknowledgeFiveByFiveCommit(dialogBatchId)

        // A stale callback from the already-disposed confirmation dialog must
        // still replay the original operation, never create another batch.
        repository.prepareFiveByFiveCommit(session.id, exercise.id, 60_000L, dialogBatchId)
        repository.commitPendingFiveByFive()
        assertEquals(5, repository.setsForSession(session.id).size)
        repository.acknowledgeFiveByFiveCommit(dialogBatchId)
    }

    @Test
    fun committedFiveByFiveCannotBeDiscardedAsUncommittedWork() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("提交确认")
        val pending = repository.prepareFiveByFiveCommit(
            session.id,
            exercise.id,
            70_000L,
            "committed-five-cannot-discard",
        )
        repository.commitPendingFiveByFive()

        assertThrows(IllegalStateException::class.java) {
            repository.discardUncommittedFiveByFive(pending)
        }
        assertNotNull(repository.pendingFiveByFiveCommit())
        assertEquals(5, repository.setsForSession(session.id).size)
    }

    @Test
    fun oversizedBatchIdIsRejectedBeforeItCanPoisonTheJournal() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("批次标识边界")

        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareFiveByFiveCommit(
                session.id,
                exercise.id,
                80_000L,
                "x".repeat(WORKOUT_BATCH_ID_MAX_LENGTH + 1),
            )
        }
        assertNull(repository.pendingFiveByFiveCommit())
        assertEquals(0, repository.setsForSession(session.id).size)
    }

    @Test
    fun alpha01UncommittedThreeKeyJournalMigratesAndCommitsExactlyOnce() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("alpha01 未提交迁移")
        writeAlpha01Journal(exercise.id, 82_500L, "legacy-uncommitted-five")

        val migrated = FitnessRepository(context)
        val pending = requireNotNull(migrated.pendingFiveByFiveCommit())
        assertEquals(session.id, pending.sessionId)
        assertEquals("legacy-uncommitted-five", pending.batchId)
        migrated.commitPendingFiveByFive()
        migrated.commitPendingFiveByFive()

        assertEquals(5, migrated.setsForSession(session.id).size)
        migrated.acknowledgeFiveByFiveCommit(pending.batchId)
        assertNull(FitnessRepository(context).pendingFiveByFiveCommit())
    }

    @Test
    fun alpha01CommittedThreeKeyJournalStaysAtFiveRowsUntilAcknowledged() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("alpha01 已提交迁移")
        val batchId = "legacy-committed-five"
        repository.addWorkoutSets(
            List(5) { index ->
                WorkoutSet(
                    sessionId = session.id,
                    exerciseId = exercise.id,
                    setOrder = index + 1,
                    loadGrams = 90_000L,
                    reps = 5,
                    completed = true,
                    batchId = batchId,
                    commitId = "$batchId:$index",
                )
            },
        )
        writeAlpha01Journal(exercise.id, 90_000L, batchId)

        val migrated = FitnessRepository(context)
        val pending = requireNotNull(migrated.pendingFiveByFiveCommit())
        assertNotNull(pending)
        migrated.commitPendingFiveByFive()
        assertEquals(5, migrated.setsForSession(session.id).size)
        assertNotNull(migrated.pendingFiveByFiveCommit())

        migrated.acknowledgeFiveByFiveCommit(batchId)
        assertNull(FitnessRepository(context).pendingFiveByFiveCommit())
    }

    @Test
    fun orphanedAlpha01JournalWithoutRowsOrActiveSessionIsSafelyCleared() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        writeAlpha01Journal(exercise.id, 75_000L, "legacy-orphan-five")

        assertNull(FitnessRepository(context).pendingFiveByFiveCommit())
        val preferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
        assertFalse(preferences.contains("pending_5x5_exercise_id"))
        assertFalse(preferences.contains("pending_5x5_load_grams"))
        assertFalse(preferences.contains("pending_5x5_commit_id"))
        assertFalse(preferences.contains("pending_5x5_v2"))
    }

    private fun writeAlpha01Journal(exerciseId: Long, loadGrams: Long, batchId: String) {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("pending_5x5_exercise_id", exerciseId)
            .putLong("pending_5x5_load_grams", loadGrams)
            .putString("pending_5x5_commit_id", batchId)
            .commit()
    }

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
    }
}
