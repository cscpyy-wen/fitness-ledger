package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkoutPlannerStoreTest {
    private lateinit var context: Context
    private lateinit var store: WorkoutPlannerStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE).edit().clear().commit()
        store = WorkoutPlannerStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun editorAndActivePlanRoundTripThenReconcileCommittedPlanItem() {
        val draft = editor(commitId = "planned-set-1")
        val item = planItem(commitId = "planned-set-1")
        store.beginSession(
            draft,
            ActiveWorkoutPlan(11L, "模板：A", listOf(item), 1L, 1_000L),
        )

        val restored = store.load(activeSessionId = 11L, committedSetIds = emptySet())
        assertEquals(draft, restored.editorDraft)
        assertEquals(listOf(item), restored.activePlan?.items)

        val reconciled = store.load(activeSessionId = 11L, committedSetIds = setOf("planned-set-1"))
        assertNull(reconciled.activePlan)
        assertEquals("77.5", reconciled.editorDraft?.weightText)
        assertTrue(reconciled.editorDraft?.commitId != "planned-set-1")
    }

    @Test
    fun templatesAndBarConfigurationShareOneDurableEnvelope() {
        val now = 2_000L
        store.saveTemplate(WorkoutTemplate("t1", "推拉腿 A", listOf(planItem("unused")), now, now))
        store.saveBarbellBarWeight(15.0)

        val restored = WorkoutPlannerStore(context).load(activeSessionId = null, committedSetIds = emptySet())
        assertEquals("推拉腿 A", restored.templates.single().name)
        assertEquals(15.0, restored.barbellBarWeightKg, 0.0)
    }

    @Test
    fun fullTemplateStoreRejectsNewEntriesWithoutEvictionEvenAfterClockRollback() {
        repeat(100) { index ->
            val timestamp = 10_000L + index
            store.saveTemplate(WorkoutTemplate("template-$index", "模板 $index", listOf(planItem("set-$index")), timestamp, timestamp))
        }
        val expected = store.load(null, emptySet()).templates
        val preferences = context.getSharedPreferences("workout_planner", Context.MODE_PRIVATE)
        val expectedEnvelope = preferences.getString("workout_planner_envelope_v1", null)

        listOf(20_000L, 1_000L).forEach { timestamp ->
            assertThrows(IllegalArgumentException::class.java) {
                store.saveTemplate(WorkoutTemplate("overflow-$timestamp", "新增模板", listOf(planItem("overflow")), timestamp, timestamp))
            }
            assertEquals(expectedEnvelope, preferences.getString("workout_planner_envelope_v1", null))
            assertEquals(expected, WorkoutPlannerStore(context).load(null, emptySet()).templates)
        }

        val updated = expected.last().copy(name = "更新已有模板", updatedAtMillis = 30_000L)
        store.saveTemplate(updated)
        val restored = WorkoutPlannerStore(context).load(null, emptySet()).templates
        assertEquals(100, restored.size)
        assertEquals(expected.map { it.id }.toSet(), restored.map { it.id }.toSet())
        assertEquals(updated, restored.single { it.id == updated.id })
    }

    @Test
    fun clearedSessionTombstoneRejectsLateHigherRevisionSaveAndSubmission() {
        val original = editor(commitId = "cleared-commit")
        store.beginSession(original, null)
        store.clearSession(original.sessionId)
        val late = original.copy(revision = 999L, updatedAtMillis = 9_999L)

        assertTrue(!store.saveEditorDraft(late))
        assertThrows(IllegalArgumentException::class.java) {
            store.completeSubmission(
                sessionId = original.sessionId,
                committedId = original.commitId,
                nextDraft = late,
            )
        }
        val reopened = WorkoutPlannerStore(context).load(
            activeSessionId = null,
            committedSetIds = emptySet(),
        )
        assertNull(reopened.editorDraft)
        assertNull(reopened.activePlan)
    }

    private fun editor(commitId: String) = WorkoutEditorDraft(
        sessionId = 11L,
        selectedExerciseId = 7L,
        weightText = "77.5",
        repsText = "7",
        durationText = "60",
        rpeText = "9.5",
        rirText = "1",
        noteText = "top",
        isWarmup = false,
        isFailed = false,
        autoRest = true,
        restSeconds = 120,
        selectedSupersetId = "A",
        commitId = commitId,
        revision = 1L,
        updatedAtMillis = 1_000L,
    )

    private fun planItem(commitId: String) = WorkoutPlanItem(
        id = "item-1",
        plannedCommitId = commitId,
        exerciseId = 7L,
        weightKg = 77.5,
        reps = 7,
        durationSeconds = 0,
        isWarmup = false,
        rpe = 9.5,
        rir = 1.0,
        note = "top",
        supersetId = "A",
        autoRest = true,
        restSeconds = 120,
    )
}
