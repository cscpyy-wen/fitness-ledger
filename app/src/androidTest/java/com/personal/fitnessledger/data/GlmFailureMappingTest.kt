package com.personal.fitnessledger.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlmFailureMappingTest {
    private val config = AnalysisServiceConfig(
        "https://open.bigmodel.cn/api/paas/v4/chat/completions", "synthetic-private-key",
        AnalysisTransport.VISION_API, "glm-5.3-flash",
    )

    @Test fun officialThinkingRejectionHasActionableSafeLocalMessage() {
        for (code in listOf<Any>("1210", 1210)) {
            val body = errorBody(code, "该模型始终思考，不支持关闭思考；请使用 low、high 或 max。")
            val failure = classifyAnalysisProviderFailure(config, 400, body)
            assertEquals(AnalysisProviderFailure.GLM_THINKING_REQUIRED, failure)
            val message = analysisFailureReason(RemoteAnalysisException(400, null, failure))
            assertTrue(message.contains("不能关闭思考"))
            assertTrue(message.contains("1210"))
            assertTrue(message.contains("未入账"))
            assertFalse(message.contains("确认使用支持图片"))
        }
    }

    @Test fun generic1210DoesNotClaimEveryParameterFailureIsThinkingRelated() {
        for (message in listOf("参数 max_tokens 错误", "", "synthetic-private-key private-image")) {
            val failure = classifyAnalysisProviderFailure(config, 400, errorBody("1210", message))
            assertEquals(AnalysisProviderFailure.GLM_INVALID_PARAMETERS, failure)
            val safe = analysisFailureReason(RemoteAnalysisException(400, null, failure))
            assertTrue(safe.contains("请求参数"))
            assertFalse(safe.contains("不能关闭思考"))
            assertFalse(safe.contains("synthetic-private-key"))
            assertFalse(safe.contains("private-image"))
        }
    }

    @Test fun foreignHostsProxyAndOtherStatusesCannotClaimGlmFailures() {
        val body = errorBody("1210", "不支持关闭思考")
        for (foreign in listOf(
            config.copy(endpointUrl = "https://open.bigmodel.cn.example.test/v1/chat/completions"),
            config.copy(endpointUrl = "https://api.deepseek.com/chat/completions"),
            config.copy(transport = AnalysisTransport.MEAL_PROXY),
        )) assertNull(classifyAnalysisProviderFailure(foreign, 400, body))
        for (status in listOf(200, 302, 401, 403, 404, 429, 500)) {
            assertNull(classifyAnalysisProviderFailure(config, status, body))
        }
        assertEquals(AnalysisProviderFailure.GLM_THINKING_REQUIRED, classifyAnalysisProviderFailure(
            config.copy(endpointUrl = "https://OPEN.BIGMODEL.CN/api/paas/v4/chat/completions"), 400, body))
    }

    @Test fun malformedOrUnknownResponsesKeepTheSafeHttpFallback() {
        for (body in listOf("", "not-json", "{}", "[]", "{\"error\":null}",
            "{\"error\":{\"code\":{\"value\":1210}}}", errorBody("9999", "不支持关闭思考"))) {
            assertNull(classifyAnalysisProviderFailure(config, 400, body))
        }
        val message = analysisFailureReason(RemoteAnalysisException(400, null))
        assertTrue(message.contains("HTTP 400"))
        assertTrue(message.contains("思考参数"))
    }

    @Test fun errorTextCannotEscapeIntoExceptionOrUiAndTransportDoesNotRetry() {
        var calls = 0
        val connection = object : HttpURLConnection(URL(config.endpointUrl)) {
            var disconnected = false
            override fun connect() = Unit
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = 400
            override fun getErrorStream() = ByteArrayInputStream(errorBody("1210",
                "不支持关闭思考 synthetic-private-key private-image private-note").toByteArray())
        }
        val client = RemoteAnalysisHttpClient(config) { calls++; connection }
        val error = runCatching { client.post("synthetic-operation", byteArrayOf(1, 2)) }.exceptionOrNull()
        assertTrue(error is RemoteAnalysisException)
        error as RemoteAnalysisException
        assertEquals(AnalysisProviderFailure.GLM_THINKING_REQUIRED, error.providerFailure)
        assertEquals(1, calls)
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
        assertNull(error.cause)
        for (secret in listOf("synthetic-private-key", "private-image", "private-note")) {
            assertFalse(error.toString().contains(secret))
            assertFalse(analysisFailureReason(error).contains(secret))
        }
    }

    private fun errorBody(code: Any, message: String): String = JSONObject()
        .put("error", JSONObject().put("code", code).put("message", message)).toString()
}
