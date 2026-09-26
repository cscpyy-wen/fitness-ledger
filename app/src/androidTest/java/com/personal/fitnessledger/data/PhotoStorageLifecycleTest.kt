package com.personal.fitnessledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoStorageLifecycleTest {
    private lateinit var context: Context
    private lateinit var cacheDirectory: File
    private lateinit var filesDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CAMERA_STATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        cacheDirectory = File(context.cacheDir, "meal_photos").apply {
            deleteRecursively()
            mkdirs()
        }
        filesDirectory = File(context.filesDir, "meal_photos").apply {
            deleteRecursively()
            mkdirs()
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(CAMERA_STATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        cacheDirectory.deleteRecursively()
        filesDirectory.deleteRecursively()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun repositoryConstructionDoesNotScanFilesAndExplicitReconciliationProtectsCurrentCapture() {
        val pendingUri = PhotoStorage.createPendingCameraUri(context)
        val pendingFile = File(cacheDirectory, pendingUri.lastPathSegment!!.substringAfterLast('/'))
        pendingFile.writeBytes(byteArrayOf(1, 2, 3))

        val oldOrphan = File(cacheDirectory, "meal_old-orphan.jpg").apply {
            writeBytes(byteArrayOf(4))
            setLastModified(System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1_000L)
        }
        val freshOrphan = File(cacheDirectory, "meal_fresh-orphan.jpg").apply {
            writeBytes(byteArrayOf(5))
        }

        val repository = FitnessRepository(context)

        assertTrue(pendingFile.exists())
        assertTrue(oldOrphan.exists())
        assertTrue(freshOrphan.exists())

        val recovered = repository.reconcilePendingCameraCapture(hasDurableDraft = false)

        assertEquals(pendingUri, recovered?.uri)
        assertTrue(PhotoStorage.pendingCameraCapture(context)?.uri == pendingUri)
        assertEquals(
            PhotoStorage.PendingCameraPhase.LAUNCHED,
            PhotoStorage.pendingCameraCapture(context)?.phase,
        )
        assertFalse(oldOrphan.exists())
        assertTrue(freshOrphan.exists())

        PhotoStorage.cancelPendingCameraCapture(context, pendingUri)
        assertFalse(pendingFile.exists())
        assertNull(PhotoStorage.pendingCameraCapture(context))
    }

    @Test
    fun deliveredAndAnalyzingCameraPhasesSurviveProcessStyleRecreationUntilConsumed() {
        val pendingUri = PhotoStorage.createPendingCameraUri(context)
        assertTrue(PhotoStorage.markPendingCameraResultReceived(context, pendingUri))
        assertEquals(
            PhotoStorage.PendingCameraPhase.RESULT_RECEIVED,
            PhotoStorage.pendingCameraCapture(context)?.phase,
        )

        val repository = FitnessRepository(context)
        val recovered = repository.reconcilePendingCameraCapture(hasDurableDraft = false)
        assertEquals(pendingUri, recovered?.uri)
        assertEquals(
            PhotoStorage.PendingCameraPhase.RESULT_RECEIVED,
            PhotoStorage.pendingCameraCapture(context)?.phase,
        )
        assertTrue(PhotoStorage.markPendingCameraAnalyzing(context, pendingUri))
        assertEquals(
            PhotoStorage.PendingCameraPhase.ANALYZING,
            PhotoStorage.pendingCameraCapture(context)?.phase,
        )

        PhotoStorage.completePendingCameraCapture(context, pendingUri)
        assertNull(PhotoStorage.pendingCameraCapture(context))
    }

    @Test
    fun newCaptureExpiresOnlyAgedLaunchedLeaseAndPreservesTargetDate() {
        val oldUri = PhotoStorage.createPendingCameraUri(
            context = context,
            nowMillis = 1_000L,
            targetDateEpochDay = 20_000L,
        )
        val oldFile = File(cacheDirectory, oldUri.lastPathSegment!!.substringAfterLast('/'))
        oldFile.writeBytes(byteArrayOf(1, 2, 3))

        val pending = FitnessRepository(context).prepareCameraCapture(
            nowMillis = 1_000L + CAMERA_LEASE_MILLIS + 1L,
            targetDateEpochDay = 20_001L,
        )

        assertFalse(oldFile.exists())
        assertFalse(oldUri == pending.uri)
        assertEquals(PhotoStorage.PendingCameraPhase.LAUNCHED, pending.phase)
        assertEquals(20_001L, pending.targetDateEpochDay)
    }

    @Test
    fun agedDeliveredAndAnalyzingCapturesAreNeverExpiredAsLaunchedWork() {
        val resultUri = PhotoStorage.createPendingCameraUri(context, nowMillis = 1_000L)
        val resultFile = File(cacheDirectory, resultUri.lastPathSegment!!.substringAfterLast('/')).apply {
            writeBytes(byteArrayOf(7))
        }
        assertTrue(PhotoStorage.markPendingCameraResultReceived(context, resultUri))

        val delivered = FitnessRepository(context).prepareCameraCapture(
            nowMillis = 1_000L + CAMERA_LEASE_MILLIS + 1L,
        )
        assertEquals(resultUri, delivered.uri)
        assertEquals(PhotoStorage.PendingCameraPhase.RESULT_RECEIVED, delivered.phase)
        assertTrue(resultFile.exists())
        assertFalse(PhotoStorage.cancelPendingCameraCapture(context, resultUri))
        assertTrue(PhotoStorage.markPendingCameraAnalyzing(context, resultUri))

        val analyzing = FitnessRepository(context).reconcilePendingCameraCapture(
            hasDurableDraft = false,
            nowMillis = 1_000L + 2L * CAMERA_LEASE_MILLIS,
        )
        assertEquals(PhotoStorage.PendingCameraPhase.ANALYZING, analyzing?.phase)
        assertTrue(resultFile.exists())
    }

    @Test
    fun cameraPhaseTransitionsRejectUndeliveredAnalysisAndLateCancellation() {
        val pendingUri = PhotoStorage.createPendingCameraUri(context)

        assertFalse(PhotoStorage.markPendingCameraAnalyzing(context, pendingUri))
        assertTrue(PhotoStorage.markPendingCameraResultReceived(context, pendingUri))
        assertFalse(PhotoStorage.cancelPendingCameraCapture(context, pendingUri))
        assertTrue(PhotoStorage.markPendingCameraAnalyzing(context, pendingUri))
        assertTrue(PhotoStorage.completePendingCameraCapture(context, pendingUri))
        assertNull(PhotoStorage.pendingCameraCapture(context))
    }

    @Test
    fun durableDraftAcknowledgementNeverDeletesAnUndeliveredCameraFile() {
        val pendingUri = PhotoStorage.createPendingCameraUri(context)
        val pendingFile = File(cacheDirectory, pendingUri.lastPathSegment!!.substringAfterLast('/')).apply {
            writeBytes(byteArrayOf(8))
        }
        val repository = FitnessRepository(context)

        val launched = repository.reconcilePendingCameraCapture(hasDurableDraft = true)
        assertEquals(PhotoStorage.PendingCameraPhase.LAUNCHED, launched?.phase)
        assertTrue(pendingFile.exists())

        assertTrue(PhotoStorage.markPendingCameraResultReceived(context, pendingUri))
        assertNull(repository.reconcilePendingCameraCapture(hasDurableDraft = true))
        assertFalse(pendingFile.exists())
        assertNull(PhotoStorage.pendingCameraCapture(context))
    }

    @Test
    fun cancellationClearsOnlyTheMatchingPendingFile() {
        val pendingUri = PhotoStorage.createPendingCameraUri(context)
        val pendingFile = File(cacheDirectory, pendingUri.lastPathSegment!!.substringAfterLast('/'))
        val unrelated = File(cacheDirectory, "meal_unrelated.jpg").apply { writeBytes(byteArrayOf(9)) }

        PhotoStorage.cancelPendingCameraCapture(context, pendingUri)

        assertFalse(pendingFile.exists())
        assertTrue(unrelated.exists())
        assertNull(PhotoStorage.pendingCameraCapture(context))
    }

    @Test
    fun retryReusesDurableStrippedPhotoBytesAndOperationUri() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val durableUri = PhotoStorage.persist(context, jpeg)

        assertArrayEquals(jpeg, PhotoStorage.readPersistedJpeg(context, durableUri))
        val draft = ConfigurablePhotoAnalyzer(AnalysisServiceConfig()).analyze(context, durableUri)

        assertEquals(durableUri.toString(), draft.photoUri)
        assertEquals(1, filesDirectory.listFiles().orEmpty().size)
        assertArrayEquals(jpeg, PhotoStorage.readPersistedJpeg(context, durableUri))

        val repository = FitnessRepository(context)
        repository.saveDraft(draft)
        val replacement = draft.copy(id = "same-photo-retry-draft")
        repository.saveDraft(replacement)
        assertArrayEquals(jpeg, PhotoStorage.readPersistedJpeg(context, durableUri))
        repository.discardDraft(draft.copy(id = "stale-unsaved-result"))
        assertArrayEquals(jpeg, PhotoStorage.readPersistedJpeg(context, durableUri))
        repository.discardDraft(replacement)
        assertNull(PhotoStorage.readPersistedJpeg(context, durableUri))
    }

    private companion object {
        const val DATABASE_NAME = "fitness_ledger.db"
        const val CAMERA_STATE_PREFERENCES = "fitness_camera_state"
        const val CAMERA_LEASE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
