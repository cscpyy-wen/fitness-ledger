package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleSetCommitJournalTest {
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
    fun journalSurvivesRepositoryRecreationAndOneCommitIdWritesOneRow() {
        val firstRepository = FitnessRepository(context)
        val exercise = firstRepository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = firstRepository.startWorkout("单组持久幂等")
        val input = WorkoutSetInput(
            weightKg = 80.0,
            reps = 8,
            restSecondsAfter = null,
            commitId = "single-operation-stable-id",
        )
        firstRepository.prepareSingleSetCommit(session.id, exercise.id, input, nowMillis = 10_000L)

        val recreated = FitnessRepository(context)
        assertEquals(input, recreated.pendingSingleSetCommit()?.input)
        val first = recreated.commitPendingSingleSet(nowMillis = 10_000L)
        val retry = recreated.commitPendingSingleSet(nowMillis = 10_001L)

        assertEquals(first.set.id, retry.set.id)
        assertEquals(1, recreated.setsForSession(session.id).size)
        assertNotNull(recreated.pendingSingleSetCommit())

        recreated.acknowledgeSingleSetCommit(input.commitId)
        assertNull(FitnessRepository(context).pendingSingleSetCommit())
    }

    @Test
    fun queuedDifferentIntentIsRejectedUntilCommittedReceiptIsAcknowledged() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("防连点")
        val first = WorkoutSetInput(weightKg = 100.0, reps = 5, commitId = "first-click")
        repository.prepareSingleSetCommit(session.id, exercise.id, first)
        repository.commitPendingSingleSet()

        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareSingleSetCommit(
                session.id,
                exercise.id,
                first.copy(commitId = "queued-second-click"),
            )
        }
        assertEquals(1, repository.setsForSession(session.id).size)

        repository.acknowledgeSingleSetCommit(first.commitId)
        repository.prepareSingleSetCommit(
            session.id,
            exercise.id,
            first.copy(commitId = "deliberate-next-set"),
        )
        repository.commitPendingSingleSet()
        assertEquals(2, repository.setsForSession(session.id).size)
    }

    @Test
    fun committedJournalCannotBeDiscardedAsUncommittedWork() {
        val repository = FitnessRepository(context)
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout("提交确认")
        val pending = repository.prepareSingleSetCommit(
            session.id,
            exercise.id,
            WorkoutSetInput(weightKg = 60.0, reps = 10, commitId = "committed-cannot-discard"),
        )
        repository.commitPendingSingleSet()

        assertThrows(IllegalStateException::class.java) {
            repository.discardUncommittedSingleSet(pending)
        }
        assertNotNull(repository.pendingSingleSetCommit())
        assertEquals(1, repository.setsForSession(session.id).size)
    }

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
    }
}
