package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BodyMeasurementFormJournalTest {
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
    fun rawDateWeightAndWaistSurviveRepositoryAndProcessEquivalentRecreation() {
        val draft = BodyMeasurementFormDraft(
            id = "body-process-recreation",
            measurementId = 42L,
            date = LocalDate.now().minusDays(12),
            weightText = "79.",
            waistText = "-",
            shortcutRequestId = "shortcut-process-recreation",
            revision = 8L,
            updatedAtMillis = 8_000L,
        )

        assertTrue(FitnessRepository(context).saveBodyMeasurementFormDraft(draft))

        assertEquals(draft, FitnessRepository(context).bodyMeasurementFormDraft())
    }

    @Test
    fun staleRevisionCannotOverwriteNewestRawInput() {
        val repository = FitnessRepository(context)
        val newest = draft(revision = 9L, updatedAtMillis = 9_000L, weightText = "78.35")
        val lateOlder = newest.copy(revision = 8L, updatedAtMillis = 10_000L, weightText = "80")

        assertTrue(repository.saveBodyMeasurementFormDraft(newest))
        assertFalse(repository.saveBodyMeasurementFormDraft(lateOlder))
        assertEquals(newest, FitnessRepository(context).bodyMeasurementFormDraft())
    }

    @Test
    fun explicitClearTombstonePreventsLateAutosaveResurrection() {
        val repository = FitnessRepository(context)
        val original = draft(id = "body-discard", revision = 4L, updatedAtMillis = 4_000L)
            .copy(shortcutRequestId = "shortcut-discard")
        assertTrue(repository.saveBodyMeasurementFormDraft(original))

        assertTrue(repository.clearBodyMeasurementFormDraft(original.id, original.shortcutRequestId))

        assertNull(repository.bodyMeasurementFormDraft())
        assertEquals(
            original.shortcutRequestId,
            FitnessRepository(context).resolvedBodyMeasurementShortcutRequestId(),
        )
        assertFalse(
            FitnessRepository(context).saveBodyMeasurementFormDraft(
                original.copy(revision = 5L, updatedAtMillis = 5_000L, waistText = "84"),
            ),
        )
        assertNull(FitnessRepository(context).bodyMeasurementFormDraft())

        assertFalse(FitnessRepository(context).acknowledgeBodyMeasurementShortcutRequest("another-request"))
        assertEquals(
            original.shortcutRequestId,
            FitnessRepository(context).resolvedBodyMeasurementShortcutRequestId(),
        )
        assertTrue(FitnessRepository(context).acknowledgeBodyMeasurementShortcutRequest("shortcut-discard"))
        assertNull(FitnessRepository(context).resolvedBodyMeasurementShortcutRequestId())
    }

    @Test
    fun shortcutReceiptIsWrittenEvenWhenInitialAutosaveNeverReachedPreferences() {
        val repository = FitnessRepository(context)

        assertTrue(
            repository.clearBodyMeasurementFormDraft(
                formId = "never-persisted-form",
                resolvedShortcutRequestId = "shortcut-never-persisted",
            ),
        )

        assertEquals(
            "shortcut-never-persisted",
            FitnessRepository(context).resolvedBodyMeasurementShortcutRequestId(),
        )
    }

    @Test
    fun staleAcknowledgementCannotDeleteAnewerShortcutReceipt() {
        val repository = FitnessRepository(context)
        assertTrue(repository.clearBodyMeasurementFormDraft("old-form", "old-request"))
        assertTrue(repository.clearBodyMeasurementFormDraft("new-form", "new-request"))

        assertFalse(repository.acknowledgeBodyMeasurementShortcutRequest("old-request"))
        assertEquals("new-request", repository.resolvedBodyMeasurementShortcutRequestId())
        assertTrue(repository.acknowledgeBodyMeasurementShortcutRequest("new-request"))
        assertNull(repository.resolvedBodyMeasurementShortcutRequestId())
    }

    @Test
    fun successfulLedgerWriteCanBeFollowedByDraftClearWithoutLosingMeasurement() {
        val repository = FitnessRepository(context)
        val form = draft(id = "body-commit", measurementId = 0L)
        assertTrue(repository.saveBodyMeasurementFormDraft(form))
        val measurement = BodyMeasurement(
            date = form.date,
            weightKg = 77.4,
            waistCm = 83.2,
        )

        repository.saveBodyMeasurement(measurement)
        repository.clearBodyMeasurementFormDraft(form.id)

        assertNull(FitnessRepository(context).bodyMeasurementFormDraft())
        val stored = repository.listBodyMeasurements().single()
        assertTrue(stored.id > 0L)
        assertEquals(measurement.date, stored.date)
        assertEquals(measurement.weightKg, stored.weightKg, 0.0)
        assertEquals(measurement.waistCm, stored.waistCm)
    }

    @Test
    fun sqliteFormReceiptMakesPostCommitProcessDeathAndChangedRetryIdempotent() {
        val repository = FitnessRepository(context)
        val form = draft(id = "body-idempotent", measurementId = 0L)
            .copy(shortcutRequestId = "shortcut-idempotent")
        assertTrue(repository.saveBodyMeasurementFormDraft(form))
        val first = BodyMeasurement(date = form.date, weightKg = 77.4, waistCm = 83.2)

        val committedId = repository.saveBodyMeasurement(first, formCommitId = form.id)
        // Simulate process death before SharedPreferences cleanup. A retry with
        // a payload that is now business-invalid must still return the durable
        // receipt instead of validating first or inserting a second row.
        val retry = first.copy(date = LocalDate.now().plusDays(1), weightKg = 70.0)
        assertTrue(retry.validationError() != null)
        val retryId = FitnessRepository(context).saveBodyMeasurement(
            retry,
            formCommitId = form.id,
        )

        assertEquals(committedId, retryId)
        assertEquals(listOf(first.date), repository.listBodyMeasurements().map { it.date })
        assertNull(FitnessRepository(context).bodyMeasurementFormDraft())
        assertEquals(
            "shortcut-idempotent",
            FitnessRepository(context).resolvedBodyMeasurementShortcutRequestId(),
        )
    }

    @Test
    fun deletingCommittedMeasurementDoesNotResurrectUnclearedRawForm() {
        val repository = FitnessRepository(context)
        val form = draft(id = "body-deleted-after-commit", measurementId = 0L)
            .copy(shortcutRequestId = "shortcut-deleted-after-commit")
        assertTrue(repository.saveBodyMeasurementFormDraft(form))
        val measurement = BodyMeasurement(date = form.date, weightKg = 76.8, waistCm = 82.5)
        val committedId = repository.saveBodyMeasurement(measurement, formCommitId = form.id)

        // Simulate preference cleanup never running, then the user deleting the
        // saved ledger row before the next process starts.
        assertTrue(repository.deleteBodyMeasurement(committedId))

        val recreated = FitnessRepository(context)
        assertNull(recreated.bodyMeasurementFormDraft())
        assertEquals("shortcut-deleted-after-commit", recreated.resolvedBodyMeasurementShortcutRequestId())
        assertEquals(
            committedId,
            recreated.saveBodyMeasurement(
                measurement.copy(date = measurement.date.plusDays(1), weightKg = 70.0),
                formCommitId = form.id,
            ),
        )
        assertTrue(recreated.listBodyMeasurements().isEmpty())
    }

    @Test
    fun expiredReceiptRemainsIdempotentWhileRawFormCleanupHasFailed() {
        val repository = FitnessRepository(context)
        val form = draft(id = "body-expired-protected", measurementId = 0L)
        assertTrue(repository.saveBodyMeasurementFormDraft(form))
        val measurement = BodyMeasurement(date = form.date, weightKg = 75.8, waistCm = 81.5)
        val committedId = repository.saveBodyMeasurement(measurement, formCommitId = form.id)
        assertTrue(repository.deleteBodyMeasurement(committedId))

        val inspector = FitnessDatabase(context)
        inspector.writableDatabase.execSQL(
            "UPDATE body_measurement_form_commits SET committed_at = ? WHERE form_id = ?",
            arrayOf<Any?>(
                System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L,
                form.id,
            ),
        )
        // Models a failed SharedPreferences cleanup: pruning may remove other
        // old metadata, but the still-recoverable raw form must be protected.
        inspector.pruneBodyMeasurementCommitReceipts(protectedFormId = form.id)
        assertEquals(committedId, inspector.bodyMeasurementCommitId(form.id))
        inspector.close()

        val retriedId = FitnessRepository(context).saveBodyMeasurement(
            measurement.copy(date = measurement.date.plusDays(1), weightKg = 70.0),
            formCommitId = form.id,
        )
        assertEquals(committedId, retriedId)
        assertTrue(FitnessRepository(context).listBodyMeasurements().isEmpty())
    }

    @Test
    fun expiredReceiptIsRemovedOnlyAfterRawFormCleanupSucceeds() {
        val repository = FitnessRepository(context)
        val form = draft(id = "body-expired-cleared", measurementId = 0L)
        assertTrue(repository.saveBodyMeasurementFormDraft(form))
        repository.saveBodyMeasurement(
            BodyMeasurement(date = form.date, weightKg = 74.8, waistCm = 80.5),
            formCommitId = form.id,
        )
        FitnessDatabase(context).use { inspector ->
            inspector.writableDatabase.execSQL(
                "UPDATE body_measurement_form_commits SET committed_at = ? WHERE form_id = ?",
                arrayOf<Any?>(
                    System.currentTimeMillis() - FORM_RECEIPT_SAFE_REPLAY_WINDOW_MILLIS - 1_000L,
                    form.id,
                ),
            )
        }

        assertNull(FitnessRepository(context).bodyMeasurementFormDraft())
        FitnessDatabase(context).use { inspector ->
            assertNull(inspector.bodyMeasurementCommitId(form.id))
        }
    }

    private fun draft(
        id: String = "body-form",
        measurementId: Long = 17L,
        revision: Long = 1L,
        updatedAtMillis: Long = 1_000L,
        weightText: String = "79.2",
    ) = BodyMeasurementFormDraft(
        id = id,
        measurementId = measurementId,
        date = LocalDate.now().minusDays(3),
        weightText = weightText,
        waistText = "85.1",
        revision = revision,
        updatedAtMillis = updatedAtMillis,
    )

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val SETTINGS_NAME = "fitness_settings"
    }
}
