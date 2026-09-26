package com.personal.fitnessledger.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.data.AnalysisMode
import com.personal.fitnessledger.data.CalorieSource
import com.personal.fitnessledger.data.DraftState
import com.personal.fitnessledger.data.EvidenceTier
import com.personal.fitnessledger.data.FitnessRepository
import com.personal.fitnessledger.data.FoodDraftItem
import com.personal.fitnessledger.data.MealDraft
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PortionBasis
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FoodLedgerWorkoutRefreshViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()

    @Before
    fun reset() {
        context.deleteDatabase("fitness_ledger.db")
        listOf("fitness_settings", "fitness_camera_state", "workout_planner").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { store.clear() }
        reset()
    }

    @Test
    fun confirmingMealRefreshesWorkoutsFromTheSameDayAsTodayNutrition() {
        val date = LocalDate.now()
        val draft = reviewedMeal(date)
        FitnessRepository(context).use { it.saveDraft(draft) }
        val model = initializedModel()
        assertEquals(draft.id, currentState(model).mealDraft?.id)
        assertTrue(currentState(model).todayCompletedWorkouts.isEmpty())

        // A separate writer changes the real repository after the ViewModel's
        // initial snapshot. The meal operation must refresh the stale training
        // slice together with loadedDate; no private state or clock mutation.
        val workoutId = FitnessRepository(context).use { completeWorkout(it, "确认前完成训练") }
        assertTrue(currentState(model).todayCompletedWorkouts.isEmpty())

        instrumentation.runOnMainSync { model.confirmDraft() }
        awaitState(model) { !it.isCommittingMeal && it.mealDraft == null && it.mealCommitReceipt != null }
        val after = currentState(model)
        assertEquals(date, after.loadedDate)
        assertEquals(date, after.selectedFoodDate)
        assertEquals(1, after.todayMeals.size)
        assertEquals(date, after.todayMeals.single().date)
        assertEquals(Nutrition(200.0, 50.0, 0.0, 0.0), after.todayNutrition)
        assertEquals(after.todayNutrition, after.selectedDateNutrition)
        assertWorkoutsMatchLoadedDate(after, workoutId)
    }

    @Test
    fun deletingMealReplacesStaleWorkoutsWhileRefreshingTodayNutrition() {
        val date = LocalDate.now()
        var originalWorkoutId = 0L
        val mealId = FitnessRepository(context).use {
            originalWorkoutId = completeWorkout(it, "初次快照中的训练")
            it.commitDraft(reviewedMeal(date), date)
        }
        val model = initializedModel()
        val before = currentState(model)
        assertEquals(listOf(originalWorkoutId), before.todayCompletedWorkouts.map { it.session.id })
        assertEquals(mealId, before.selectedDateMeals.single().id)

        val replacementWorkoutId = FitnessRepository(context).use {
            it.deleteWorkoutHistorySession(originalWorkoutId)
            completeWorkout(it, "删除餐食前完成的新训练")
        }
        assertEquals(listOf(originalWorkoutId), currentState(model).todayCompletedWorkouts.map { it.session.id })

        instrumentation.runOnMainSync { model.deleteMeal(mealId) }
        awaitState(model) { !it.isCommittingMeal && it.selectedDateMeals.isEmpty() }
        val after = currentState(model)
        assertEquals(date, after.loadedDate)
        assertEquals(date, after.selectedFoodDate)
        assertTrue(after.todayMeals.isEmpty())
        assertEquals(Nutrition(), after.todayNutrition)
        assertEquals(Nutrition(), after.selectedDateNutrition)
        assertNull(after.mealDraft)
        assertTrue(after.savedFoods.isNotEmpty())
        assertTrue(after.savedFoods.all { it.useCount == 0 })
        assertWorkoutsMatchLoadedDate(after, replacementWorkoutId)
    }

    private fun initializedModel(): AppViewModel {
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync {
            model = AppViewModel(context)
            store.put("food-ledger-workout-refresh", model)
        }
        awaitState(model) { it.isInitialized }
        return model
    }

    private fun completeWorkout(repository: FitnessRepository, title: String): Long {
        val exercise = repository.listExercises().first { it.trackingType == TrackingType.WEIGHT_REPS }
        val session = repository.startWorkout(title)
        repository.addWorkoutSet(WorkoutSet(
            sessionId = session.id,
            exerciseId = exercise.id,
            setOrder = 1,
            loadGrams = 60_000L,
            reps = 5,
            completed = true,
            isWarmup = false,
        ))
        val result = repository.completeWorkout(session.id)
        assertEquals(LocalDate.now(), result.recordedLocalDate)
        return session.id
    }

    private fun assertWorkoutsMatchLoadedDate(state: AppUiState, expectedWorkoutId: Long) {
        assertEquals(listOf(expectedWorkoutId), state.todayCompletedWorkouts.map { it.session.id })
        assertTrue(state.todayCompletedWorkouts.all { it.recordedLocalDate == state.loadedDate })
        FitnessRepository(context).use {
            assertEquals(it.completedWorkoutsForDate(state.loadedDate), state.todayCompletedWorkouts)
        }
    }

    private fun reviewedMeal(date: LocalDate) = MealDraft(
        photoUri = "",
        state = DraftState.READY_TO_CONFIRM,
        items = listOf(FoodDraftItem(
            name = "快照测试米饭",
            grams = 200.0,
            gramsMin = 200.0,
            gramsMax = 200.0,
            per100g = Nutrition(100.0, 25.0, 0.0, 0.0),
            sourceName = "合成测试",
            portionBasis = PortionBasis.USER_WEIGHT,
            evidenceTier = EvidenceTier.B,
            calorieSource = CalorieSource.DERIVED_FROM_MACROS,
        )),
        evidenceTier = EvidenceTier.B,
        evidenceReason = "合成测试数据",
        unresolvedFlags = emptySet(),
        userReviewed = true,
        providerLabel = "测试",
        analysisMode = AnalysisMode.MANUAL,
        targetDate = date,
    )

    private fun currentState(model: AppViewModel): AppUiState {
        lateinit var state: AppUiState
        instrumentation.runOnMainSync { state = model.state }
        return state
    }

    private fun awaitState(model: AppViewModel, predicate: (AppUiState) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate(currentState(model))) return
            Thread.sleep(20)
        }
        fail("ViewModel did not finish refreshing the food ledger")
    }
}
