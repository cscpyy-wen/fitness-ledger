package com.personal.fitnessledger.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.data.FitnessRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AdditionalMealPromptViewModelTest {
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

    @Test fun saveUsesTheSettingsWriteGateAndRefreshesCannotRestoreThePreviousPrompt() {
        val model = newModel()
        awaitState(model) { it.isInitialized }
        val saved = AtomicBoolean(false)
        val secondRejected = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            model.refreshAll()
            model.saveAnalysisAdditionalPrompt(" 白色餐盘\r\n米饭熟重200g ") { saved.set(it) }
            assertTrue(model.state.isSavingAnalysisConfig)
            model.saveAnalysisAdditionalPrompt("不应覆盖") { secondRejected.set(!it) }
            model.refreshAll()
        }
        awaitState(model) { saved.get() && !it.isSavingAnalysisConfig && it.analysisAdditionalPrompt == "白色餐盘\n米饭熟重200g" }
        assertTrue(secondRejected.get())
        instrumentation.runOnMainSync { model.refreshAll() }
        awaitState(model) { it.isInitialized && it.analysisAdditionalPrompt == "白色餐盘\n米饭熟重200g" }
        FitnessRepository(context).use { assertEquals("白色餐盘\n米饭熟重200g", it.analysisAdditionalPrompt()) }
        val restarted = newModel()
        awaitState(restarted) { it.isInitialized && it.analysisAdditionalPrompt == "白色餐盘\n米饭熟重200g" }
    }

    @Test fun invalidSaveReturnsFailureWithoutExposingTheNoteOrLosingTheSavedValue() {
        FitnessRepository(context).use { it.saveAnalysisAdditionalPrompt("此前提示词") }
        val model = newModel()
        awaitState(model) { it.isInitialized && it.analysisAdditionalPrompt == "此前提示词" }
        val rejected = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            model.saveAnalysisAdditionalPrompt("private-meal-note\u0000") { rejected.set(!it) }
        }
        awaitState(model) { rejected.get() && !it.isSavingAnalysisConfig }
        val state = currentState(model)
        assertEquals("此前提示词", state.analysisAdditionalPrompt)
        assertFalse(state.message.orEmpty().contains("private-meal-note"))
        val cleared = AtomicBoolean(false)
        instrumentation.runOnMainSync { model.saveAnalysisAdditionalPrompt(" \n\t ") { cleared.set(it) } }
        awaitState(model) { cleared.get() && it.analysisAdditionalPrompt.isEmpty() }
    }

    private fun newModel(): AppViewModel {
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(context); store.put("model", model) }
        return model
    }

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
        fail("ViewModel did not reach the expected prompt state")
    }
}
