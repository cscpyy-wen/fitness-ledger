package com.personal.fitnessledger.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke test: loads and invokes the real bundled AIY Food V1 model. */
@RunWith(AndroidJUnit4::class)
class OnDevicePhotoAnalyzerInstrumentedTest {
    @Test
    fun bundledModelAppliesTheSameLocalizedFailClosedPolicyToCameraAndGalleryPhotos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jpeg = syntheticJpeg()
        val cameraUri = Uri.parse("content://fitness-ledger-test/camera/synthetic-food-frame.jpg")
        val galleryUri = Uri.parse("content://fitness-ledger-test/gallery/synthetic-food-frame.jpg")

        // Construction is cheap; analyze() triggers asset verification, LiteRT model loading,
        // tensor shape/type/quantization checks, preprocessing, and real CPU inference.
        val analyzer = OnDevicePhotoAnalyzer(context)
        val drafts = listOf(
            analyzer.analyze(cameraUri, jpeg),
            analyzer.analyze(galleryUri, jpeg),
        )
        assertEquals(
            drafts[0].hypotheses.map { it.displayName to (it.suggestedItem != null) },
            drafts[1].hypotheses.map { it.displayName to (it.suggestedItem != null) },
        )

        drafts.forEachIndexed { index, draft ->
            assertEquals(AnalysisMode.ON_DEVICE_AI, draft.analysisMode)
            assertEquals(if (index == 0) cameraUri.toString() else galleryUri.toString(), draft.photoUri)
            assertTrue(draft.items.size <= 1)
            assertTrue(draft.hypotheses.size <= 5)
            assertTrue(draft.providerLabel.length in 1..256)
            assertTrue(draft.evidenceReason.length in 1..512)
            assertTrue(draft.unresolvedFlags.size <= RiskFlag.entries.size)

            draft.hypotheses.forEach { hypothesis ->
                assertTrue(hypothesis.labelId in 1 until 2024)
                assertTrue(hypothesis.rawLabel.length in 1..512)
                assertTrue(hypothesis.displayName.length in 1..512)
                assertTrue(
                    "candidate must be localized or explicitly marked uncatalogued",
                    hypothesis.displayName.any { it in '\u4e00'..'\u9fff' },
                )
                assertTrue(!hypothesis.displayName.contains(hypothesis.rawLabel, ignoreCase = false))
                assertTrue(hypothesis.canonicalKey.length in 1..512)
                assertTrue(hypothesis.modelScore.isFinite())
                assertTrue(hypothesis.modelScore in 0.0..1.0)
                hypothesis.suggestedItem?.assertBounded()
            }
            draft.items.forEach { item -> item.assertBounded() }
        }
    }

    private fun syntheticJpeg(): ByteArray {
        val width = 320
        val height = 240
        val pixels = IntArray(width * height) { offset ->
            val x = offset % width
            val y = offset / width
            val red = (x * 255 / (width - 1)).coerceIn(0, 255)
            val green = (y * 255 / (height - 1)).coerceIn(0, 255)
            val blue = ((x + y) * 255 / (width + height - 2)).coerceIn(0, 255)
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray().also { encoded -> check(encoded.isNotEmpty()) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun FoodDraftItem.assertBounded() {
        assertTrue(name.length in 1..512)
        assertTrue(sourceName.length in 1..512)
        assertTrue(grams.isFinite() && grams in 1.0..5_000.0)
        assertTrue(gramsMin.isFinite() && gramsMin in 1.0..5_000.0)
        assertTrue(gramsMax.isFinite() && gramsMax in gramsMin..5_000.0)
        assertTrue(per100g.kcal.isFinite() && per100g.kcal in 0.0..1_000.0)
        assertTrue(per100g.carbsG.isFinite() && per100g.carbsG in 0.0..100.0)
        assertTrue(per100g.proteinG.isFinite() && per100g.proteinG in 0.0..100.0)
        assertTrue(per100g.fatG.isFinite() && per100g.fatG in 0.0..100.0)
        assertTrue(alternatives.size <= 4)
        assertTrue(riskFlags.size <= RiskFlag.entries.size)
    }
}
