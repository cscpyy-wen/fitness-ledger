package com.personal.fitnessledger.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.data.AnalysisMode
import com.personal.fitnessledger.data.CalorieSource
import com.personal.fitnessledger.data.EvidenceTier
import com.personal.fitnessledger.data.FitnessRepository
import com.personal.fitnessledger.data.FoodDraftItem
import com.personal.fitnessledger.data.MealDraft
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.PortionBasis
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MealHistoryViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()

    @Before fun reset() {
        context.deleteDatabase("fitness_ledger.db")
        listOf("fitness_settings", "fitness_camera_state", "workout_planner").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { store.clear() }
        reset()
    }

    @Test fun deletingMealImmediatelyRefreshesDetailsTotalsAndRecentFoodUsage() {
        val date = LocalDate.now()
        val foods = listOf(food("合成米饭", 200.0), food("合成饮品", 100.0))
        val id = FitnessRepository(context).use {
            it.commitDraft(MealDraft(photoUri = "", items = foods, evidenceTier = EvidenceTier.B,
                evidenceReason = "合成数据", unresolvedFlags = emptySet(), userReviewed = true,
                providerLabel = "测试", analysisMode = AnalysisMode.MANUAL), date)
        }
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(context); store.put("meal-history", model) }
        awaitState(model) { it.isInitialized && it.selectedDateMeals.singleOrNull()?.id == id }
        val before = currentState(model)
        assertEquals(2, before.selectedDateMeals.single().items.size)
        assertEquals(2, before.savedFoods.size)
        assertTrue(before.savedFoods.all { it.useCount == 1 })
        assertEquals(300.0, before.todayNutrition.kcal, 0.0)

        instrumentation.runOnMainSync { model.deleteMeal(id) }
        awaitState(model) { !it.isCommittingMeal && it.selectedDateMeals.isEmpty() &&
            it.savedFoods.size == 2 && it.savedFoods.all { food -> food.useCount == 0 } }
        val after = currentState(model)
        assertTrue(after.todayMeals.isEmpty())
        assertEquals(Nutrition(), after.todayNutrition)
        assertEquals(Nutrition(), after.selectedDateNutrition)
        assertEquals(date, after.selectedFoodDate)
        FitnessRepository(context).use {
            assertTrue(it.mealsForDate(date).isEmpty())
            assertTrue(it.listSavedFoods().all { food -> food.useCount == 0 })
        }
    }

    private fun food(name: String, grams: Double) = FoodDraftItem(name = name, grams = grams,
        gramsMin = grams, gramsMax = grams, per100g = Nutrition(100.0, 25.0, 0.0, 0.0),
        sourceName = "合成测试", portionBasis = PortionBasis.USER_WEIGHT, evidenceTier = EvidenceTier.B,
        calorieSource = CalorieSource.DERIVED_FROM_MACROS)

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
        fail("ViewModel did not refresh the complete meal ledger snapshot")
    }
}
