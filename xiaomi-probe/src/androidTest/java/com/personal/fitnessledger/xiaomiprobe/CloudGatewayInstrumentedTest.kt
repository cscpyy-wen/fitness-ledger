// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.fitnessledger.xiaomiprobe.protocol.CloudCipher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Entirely synthetic transport: never logs in, connects to Xiaomi, or touches user data. */
@RunWith(AndroidJUnit4::class)
class CloudGatewayInstrumentedTest {
    private val now = 1789980000L
    private val security = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test fun loginAndSevenDayQueryOnlyUseOwnWeightEndpoint() = runBlocking {
        val fake = FakeTransport(row(70.25))
        val gateway = gateway(fake)
        val session = gateway.awaitLogin(gateway.beginLogin())
        val result = gateway.readRecentWeights(session)
        assertEquals(1, result.records.size)
        assertEquals(70.25, result.records.single().weightKg, 0.00001)
        assertNull(result.records.single().bodyFatPercent)
        assertNull(result.records.single().waterPercent)
        assertFalse(result.incomplete)
        assertEquals(7, fake.queries.size)
        fake.queries.forEach { assertEquals("weight", it.getString("key")) }
        assertEquals(now - 7 * 86400, fake.queries.first().getLong("start_time"))
        assertEquals(now, fake.queries.last().getLong("end_time"))
        assertFalse(fake.seenHeaders.any { it.contains("passToken") })
        gateway.close()
        assertTrue(fake.closed)
    }

    @Test fun malformedRowsAreMarkedIncompleteInsteadOfEmptySuccess() = runBlocking {
        val fake = FakeTransport().apply { rows = JSONArray().put("invalid-row") }
        val gateway = gateway(fake)
        val result = gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin()))
        assertEquals(1, result.rejectedCount)
        assertTrue(result.incomplete)
        assertTrue(result.records.isEmpty())
        gateway.close()
    }

    @Test fun corruptRevisionVetoesSameIdentityValidRow() = runBlocking {
        val fake = FakeTransport().apply { rows = JSONArray().put(row(70.25)).put(row(70.25).put("value", "broken-json")) }
        val gateway = gateway(fake)
        val result = gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin()))
        assertEquals(2, result.rejectedCount)
        assertTrue(result.records.isEmpty())
        assertTrue(result.incomplete)
        gateway.close()
    }

    @Test fun nestedInvalidUnitDoesNotBecomeUnspecifiedKg() = runBlocking {
        val sample = row(70.25)
        sample.getJSONObject("value").put("unit", JSONObject().put("nested", "lbs"))
        val fake = FakeTransport(sample)
        val gateway = gateway(fake)
        val result = gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin()))
        assertEquals(1, result.rejectedCount)
        assertTrue(result.records.isEmpty())
        gateway.close()
    }

    @Test fun optionalOriginalCompositionIsPreserved() = runBlocking {
        val sample = row(70.25)
        sample.getJSONObject("value").put("body_fat_rate", 19.1).put("moisture_rate", 53.2)
        val fake = FakeTransport(sample)
        val gateway = gateway(fake)
        val value = gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin())).records.single()
        assertEquals(19.1, value.bodyFatPercent!!, 0.00001)
        assertEquals(53.2, value.waterPercent!!, 0.00001)
        gateway.close()
    }

    @Test fun numericPaginationMarkerMeansPartialButNullCursorDoesNot() = runBlocking {
        for (flag in listOf(0, 1)) {
            val fake = FakeTransport(row(70.25)).apply { paginationFlag = flag }
            val gateway = gateway(fake)
            val result = gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin()))
            assertEquals(flag == 1, result.incomplete)
            gateway.close()
        }
    }

    @Test fun authMissingEncryptedUserIdNeverReportsLoggedIn() = runBlocking {
        val fake = FakeTransport().apply { missingCUserId = true }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSUPPORTED) { gateway.awaitLogin(gateway.beginLogin()) }
        assertTrue(fake.queries.isEmpty())
        gateway.close()
    }

    @Test fun maliciousLoginUrlIsStoppedBeforeDisplayOrAdditionalRequest() = runBlocking {
        val fake = FakeTransport().apply { maliciousLogin = true }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSAFE_RESPONSE) { gateway.beginLogin() }
        assertEquals(1, fake.calls)
        gateway.close()
    }

    @Test fun maliciousRedirectNeverReceivesCookies() = runBlocking {
        val fake = FakeTransport().apply { maliciousRedirect = true }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSAFE_RESPONSE) { gateway.awaitLogin(gateway.beginLogin()) }
        assertFalse(fake.hosts.contains("evil.test"))
        gateway.close()
    }

    @Test fun authCannotRedirectToAnotherHealthApi() = runBlocking {
        val fake = FakeTransport().apply { exchangeLocation = "https://hlth.io.mi.com/app/v1/relatives/get_relative_list" }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSAFE_RESPONSE) { gateway.awaitLogin(gateway.beginLogin()) }
        assertFalse(fake.hosts.contains("hlth.io.mi.com"))
        gateway.close()
    }

    @Test fun htmlInsteadOfStsAcknowledgementDoesNotCountAsAuthenticated() = runBlocking {
        val fake = FakeTransport().apply { stsAcknowledgement = "<html>Login required</html>" }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSUPPORTED) { gateway.awaitLogin(gateway.beginLogin()) }
        assertTrue(fake.queries.isEmpty())
        gateway.close()
    }

    @Test fun accountOnlyServiceTokenIsNeverPromotedToHealthService() = runBlocking {
        val fake = FakeTransport().apply { stsCookie = null; accountServiceToken = true }
        val gateway = gateway(fake)
        expectProblem(ProbeProblem.UNSUPPORTED) { gateway.awaitLogin(gateway.beginLogin()) }
        assertTrue(fake.queries.isEmpty())
        gateway.close()
    }

    @Test fun explicitStsCookieDomainWithoutLeadingDotWorksOnAndroid() = runBlocking {
        val fake = FakeTransport(row(70.25)).apply { stsCookie = "serviceToken=synthetic-service; Domain=io.mi.com; Path=/; Secure" }
        val gateway = gateway(fake)
        assertEquals(1, gateway.readRecentWeights(gateway.awaitLogin(gateway.beginLogin())).records.size)
        gateway.close()
    }

    @Test fun closedGatewayCannotUseRetainedSessionOrStartAgain() = runBlocking {
        val fake = FakeTransport()
        val gateway = gateway(fake)
        val session = gateway.awaitLogin(gateway.beginLogin())
        gateway.close()
        expectProblem(ProbeProblem.AUTH_REQUIRED) { gateway.readRecentWeights(session) }
        expectProblem(ProbeProblem.AUTH_REQUIRED) { gateway.beginLogin() }
        assertTrue(fake.queries.isEmpty())
    }

    private fun gateway(fake: FakeTransport) = XiaomiCloudGateway(fake, { now * 1000 }, { 1000L })
    private fun row(weight: Double) = JSONObject().put("key", "weight").put("time", now - 60).put("sid", "synthetic-source")
        .put("value", JSONObject().put("weight", weight))

    private suspend fun expectProblem(problem: ProbeProblem, action: suspend () -> Any) {
        try { action(); fail("Expected a safe failure") }
        catch (expected: ProbeException) {
            assertEquals(problem, expected.problem)
            assertNull(expected.cause)
            assertFalse(expected.message.orEmpty().contains("synthetic-secret"))
        }
    }

    private inner class FakeTransport(sample: JSONObject? = null) : ProbeTransport {
        var rows = JSONArray().apply { sample?.let { put(it) } }
        var paginationFlag = 0
        var missingCUserId = false
        var maliciousLogin = false
        var maliciousRedirect = false
        var exchangeLocation = "https://sts-hlth.io.mi.com/healthapp/sts?ticket=synthetic-secret"
        var stsAcknowledgement = "ok"
        var stsCookie: String? = "serviceToken=synthetic-service; Domain=.io.mi.com; Path=/; Secure"
        var accountServiceToken = false
        var closed = false
        var calls = 0
        val hosts = mutableListOf<String>()
        val queries = mutableListOf<JSONObject>()
        val seenHeaders = mutableListOf<String>()
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                                     timeoutMillis: Int, maximumBytes: Int): HttpReply {
            calls++
            val uri = URI(url)
            hosts.add(uri.host)
            seenHeaders.add(headers["Cookie"].orEmpty())
            return when {
                uri.path == "/longPolling/loginUrl" -> reply(JSONObject().put("code", 0).put("timeout", 300)
                    .put("loginUrl", if (maliciousLogin) "https://evil.test/" else "https://c3.account.xiaomi.com/synthetic")
                    .put("lp", "https://c3.lp.account.xiaomi.com/synthetic-poll"))
                uri.path == "/synthetic-poll" -> {
                    val data = JSONObject().put("ssecurity", security).put("userId", "123456")
                    .put("passToken", "MUST_NOT_RETAIN")
                    .put("cUserId", if (missingCUserId) "" else "synthetic-encrypted-user")
                    .put("location", exchangeLocation)
                    HttpReply(200, if (accountServiceToken) mapOf("Set-Cookie" to listOf("serviceToken=synthetic-account-only; Path=/; Secure")) else emptyMap(), data.toString().toByteArray())
                }
                uri.host == "sts-hlth.io.mi.com" -> if (maliciousRedirect) {
                    HttpReply(302, mapOf("Location" to listOf("https://evil.test/")), ByteArray(0))
                } else {
                    HttpReply(200, stsCookie?.let { mapOf("Set-Cookie" to listOf(it)) } ?: emptyMap(), stsAcknowledgement.toByteArray())
                }
                uri.path == EndpointPolicy.WEIGHT_PATH -> {
                    assertEquals("POST", method)
                    val fields = body!!.toString(Charsets.UTF_8).split('&').associate {
                        val parts = it.split('=', limit = 2)
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    }
                    queries.add(JSONObject(CloudCipher.decryptResponse(security, fields.getValue("_nonce"), fields.getValue("data"))))
                    reply(JSONObject().put("code", 0).put("result", JSONObject().put("data_list", if (queries.size == 1) rows else JSONArray())
                        .put("has_more", paginationFlag).put("next_key", JSONObject.NULL)))
                }
                else -> throw AssertionError("Unexpected test request")
            }
        }
        override fun close() { closed = true }
        private fun reply(json: JSONObject) = HttpReply(200, emptyMap(), json.toString().toByteArray(Charsets.UTF_8))
    }
}
