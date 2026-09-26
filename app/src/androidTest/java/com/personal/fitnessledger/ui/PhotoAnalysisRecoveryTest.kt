package com.personal.fitnessledger.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PhotoAnalysisRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun reset() {
        context.deleteDatabase("fitness_ledger.db")
        listOf("fitness_settings", "fitness_camera_state", "workout_planner").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, "meal_photos").deleteRecursively()
        File(context.cacheDir, "meal_photos").deleteRecursively()
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { store.clear() }
        reset()
    }

    @Test fun preparationMustPersistBeforeAnyTransportAndFailureStopsAnalysis() {
        val uri = PhotoStorage.persist(context, strippedJpeg())
        var prepared = false
        val failure = runCatching {
            ConfigurablePhotoAnalyzer(AnalysisServiceConfig(), onPhotoPrepared = {
                assertEquals(uri, it)
                prepared = true
                throw java.io.IOException("synthetic persistence failure")
            }).analyze(context, uri)
        }.exceptionOrNull()
        assertTrue(prepared)
        assertTrue(failure is java.io.IOException)
    }

    @Test fun galleryRequestLeavesDurablePhotoAndOriginalDateBeforeResultIsApplied() {
        val uri = PhotoStorage.persist(context, strippedJpeg())
        val date = LocalDate.now().minusDays(2)
        FitnessRepository(context).use { repository ->
            // No configured provider: no network traffic is possible in this test.
            repository.analyzePhoto(uri, date)
        }
        FitnessRepository(context).use { restarted ->
            val recovered = requireNotNull(restarted.latestDraft())
            assertEquals(uri.toString(), recovered.photoUri)
            assertEquals(date, recovered.targetDate)
            assertEquals(DraftState.ANALYSIS_FAILED, recovered.state)
            assertTrue(recovered.evidenceReason.contains("不会自动重发"))
            assertFalse(recovered.userReviewed)
            assertNotNull(PhotoStorage.readPersistedJpeg(context, uri))
            assertTrue(restarted.mealsForDate(date).isEmpty())
        }
    }

    @Test fun legacyInFlightCameraRecoversLocallyWithoutAutomaticallyAnalyzingAgain() {
        val date = LocalDate.now().minusDays(1)
        val uri = PhotoStorage.createPendingCameraUri(context, targetDateEpochDay = date.toEpochDay())
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            bitmap.recycle()
        }
        assertTrue(PhotoStorage.markPendingCameraResultReceived(context, uri))
        assertTrue(PhotoStorage.markPendingCameraAnalyzing(context, uri))
        val model = newModel()
        awaitState(model) { it.isInitialized }
        val state = currentState(model)
        val recovered = requireNotNull(state.mealDraft)
        assertEquals(DraftState.ANALYSIS_FAILED, recovered.state)
        assertTrue(recovered.evidenceReason.contains("不会自动重发"))
        assertFalse(state.isAnalyzingPhoto)
        assertEquals(date, recovered.targetDate)
        assertNull(PhotoStorage.pendingCameraCapture(context))
        FitnessRepository(context).use { assertTrue(it.mealsForDate(date).isEmpty()) }
    }

    @Test fun failedRetryPreservesEditedFoodsDateIdentityAndDurablePhoto() {
        val uri = PhotoStorage.persist(context, strippedJpeg())
        val original = DemoPhotoAnalyzer.analyze(uri).copy(
            analysisMode = AnalysisMode.ON_DEVICE_AI,
            targetDate = LocalDate.now().minusDays(3),
            items = DemoPhotoAnalyzer.analyze(uri).items.take(1).map { it.withGrams(123.0) },
        )
        FitnessRepository(context).use { it.saveDraft(original) }
        val model = newModel()
        awaitState(model) { it.isInitialized && it.mealDraft != null }
        instrumentation.runOnMainSync { model.retryDraftAnalysis() }
        awaitState(model) { !it.isAnalyzingPhoto && it.message?.contains("原草稿和修改已保留") == true }
        assertEquals(original, currentState(model).mealDraft)
        FitnessRepository(context).use { repository ->
            assertEquals(original, repository.latestDraft())
            assertTrue(repository.mealsForDate(requireNotNull(original.targetDate)).isEmpty())
        }
        assertNotNull(PhotoStorage.readPersistedJpeg(context, uri))
    }

    private fun newModel(): AppViewModel {
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(context); store.put("model", model) }
        return model
    }

    private fun strippedJpeg() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

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
        fail("ViewModel did not reach expected recovery state")
    }
}
