package com.personal.fitnessledger.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyAdditionalMealPromptTest {
    private val uri = Uri.parse("content://com.personal.fitnessledger.fileprovider/prompt_test.jpg")
    private val config = AnalysisServiceConfig("https://proxy.example.test/analyze", "private-api-key")

    @Test fun nonemptyNoteIsSentAndChangedNoteCreatesANewExplicitRetryKey() {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        fun analyze(note: String) = RemotePhotoAnalyzer(config.copy(additionalPrompt = note)) { key, bytes ->
            requests += key to JSONObject(bytes.toString(Charsets.UTF_8))
            RESPONSE
        }.analyze(uri, byteArrayOf(1, 2, 3))
        val draft = analyze(" 米饭熟重200g\r\n白色方形餐盘 ")
        analyze("米饭熟重200g\n白色方形餐盘")
        analyze("米饭熟重300g\n白色方形餐盘")
        assertEquals(3, requests.size)
        assertEquals("米饭熟重200g\n白色方形餐盘", requests.first().second.getString("additionalPrompt"))
        assertEquals(requests[0].first, requests[1].first)
        assertNotEquals(requests[0].first, requests[2].first)
        assertFalse(requests[0].first.contains("米饭"))
        assertFalse(requests[0].second.toString().contains("private-api-key"))
        assertFalse(draft.userReviewed)
    }

    @Test fun absentOrWhitespaceNotePreservesTheLegacyRequestAndOperationKey() {
        listOf("", " \r\n\t ").forEach { note ->
            RemotePhotoAnalyzer(config.copy(additionalPrompt = note)) { key, bytes ->
                assertEquals(uri.analysisOperationKey(), key)
                val request = JSONObject(bytes.toString(Charsets.UTF_8))
                assertFalse(request.has("additionalPrompt"))
                assertEquals("zh-CN", request.getString("locale"))
                assertTrue(request.has("imageBase64"))
                RESPONSE
            }.analyze(uri, byteArrayOf(1, 2, 3))
        }
    }

    @Test fun invalidNoteFailsBeforeAnyRequest() {
        var calls = 0
        val analyzer = RemotePhotoAnalyzer(config.copy(additionalPrompt = "private-note\u0000")) { _, _ ->
            calls += 1
            RESPONSE
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { analyzer.analyze(uri, byteArrayOf(1, 2, 3)) }
        assertEquals(0, calls)
        assertFalse(failure.message.orEmpty().contains("private-note"))
    }

    private companion object {
        const val RESPONSE = """{"items":[{"name":"米饭","grams":200,"per100g":{"kcal":116,"carbsG":25.9,"proteinG":2.6,"fatG":0.3}}]}"""
    }
}
