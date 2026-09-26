package com.personal.fitnessledger.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteAnalysisHttpClientTest {
    @Test
    fun `redirect is rejected without opening or forwarding secrets to target`() {
        val endpoint = URL("https://proxy.example.test/analyze")
        val redirectTarget = URL("https://redirect.example.test/collect")
        val origin = FakeHttpURLConnection(endpoint, statusCode = 307, location = redirectTarget.toString())
        val target = FakeHttpURLConnection(redirectTarget, statusCode = 200)
        val openedUrls = mutableListOf<URL>()
        val client = RemoteAnalysisHttpClient(
            config = AnalysisServiceConfig(endpoint.toString(), "top-secret-token"),
            connectionFactory = { url ->
                openedUrls += url
                if (url == endpoint) origin else target
            },
        )

        val error = expectRemoteError {
            client.post(
                operationKey = "operation-123",
                requestBody = "{\"imageBase64\":\"private-photo\"}".toByteArray(),
            )
        }

        assertEquals(307, error.statusCode)
        assertEquals(listOf(endpoint), openedUrls)
        assertFalse(origin.instanceFollowRedirects)
        assertEquals("Bearer top-secret-token", origin.getRequestProperty("Authorization"))
        assertEquals("operation-123", origin.getRequestProperty("X-Idempotency-Key"))
        assertTrue(origin.writtenBytes.size() > 0)
        assertNull(target.getRequestProperty("Authorization"))
        assertNull(target.getRequestProperty("X-Idempotency-Key"))
        assertEquals(0, target.writtenBytes.size())
        assertFalse(target.disconnected)
        assertTrue(origin.disconnected)
    }

    @Test
    fun `every 3xx status has an explicit non forwarding failure reason`() {
        listOf(300, 301, 302, 303, 304, 305, 306, 307, 308, 399).forEach { status ->
            val endpoint = URL("https://proxy.example.test/analyze")
            val connection = FakeHttpURLConnection(endpoint, statusCode = status)
            val client = RemoteAnalysisHttpClient(
                config = AnalysisServiceConfig(endpoint.toString(), "token"),
                connectionFactory = { connection },
            )

            val error = expectRemoteError {
                client.post("operation-$status", "photo-$status".toByteArray())
            }
            val reason = analysisFailureReason(error)

            assertEquals(status, error.statusCode)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(reason.contains("重定向"))
            assertTrue(reason.contains("已拒绝请求"))
            assertTrue(reason.contains("HTTP $status"))
        }
    }

    private fun expectRemoteError(block: () -> Unit): RemoteAnalysisException {
        try {
            block()
            fail("Expected RemoteAnalysisException")
        } catch (error: RemoteAnalysisException) {
            return error
        }
        error("unreachable")
    }

    @Test
    fun `bad request explains parameter rejection instead of asserting missing vision support`() {
        val generic = analysisFailureReason(RemoteAnalysisException(400, null))
        assertTrue(generic.contains("HTTP 400"))
        assertTrue(generic.contains("思考参数"))
        assertFalse(generic.contains("确认使用支持图片"))
        val glm = analysisFailureReason(RemoteAnalysisException(400, null, AnalysisProviderFailure.GLM_THINKING_REQUIRED))
        assertTrue(glm.contains("不能关闭思考"))
        assertTrue(glm.contains("1210"))
    }

    @Test
    fun `provider parameter detail cannot override authentication or rate limit errors`() {
        assertTrue(analysisFailureReason(RemoteAnalysisException(401, null,
            AnalysisProviderFailure.GLM_THINKING_REQUIRED)).contains("拒绝访问"))
        assertTrue(analysisFailureReason(RemoteAnalysisException(429, 12,
            AnalysisProviderFailure.GLM_THINKING_REQUIRED)).contains("12 秒"))
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val statusCode: Int,
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        val writtenBytes = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream = writtenBytes

        override fun getResponseCode(): Int = statusCode

        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream("redirect rejected".toByteArray())

        override fun getHeaderField(name: String?): String? = when {
            name.equals("Location", ignoreCase = true) -> location
            else -> null
        }
    }
}
