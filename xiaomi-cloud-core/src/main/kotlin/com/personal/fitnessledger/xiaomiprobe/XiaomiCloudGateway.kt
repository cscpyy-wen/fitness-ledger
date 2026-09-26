// SPDX-License-Identifier: GPL-3.0-or-later
// Experimental protocol interoperability; references and source license in module README/NOTICE.
package com.personal.fitnessledger.xiaomiprobe

import android.os.SystemClock
import com.personal.fitnessledger.xiaomiprobe.protocol.CloudCipher
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightReadResult
import com.personal.fitnessledger.xiaomiprobe.protocol.WeightRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64

/** No files, preferences, WebView, analytics, broad-data queries, or ledger writes. */
class XiaomiCloudGateway internal constructor(
    private val transport: ProbeTransport = UrlConnectionTransport(),
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ProbeGateway {
    private val owner = Any()
    private val cookies = SessionCookies()
    private val deviceId = "an_" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var closed = false
    private var activeChallenge: LoginChallenge? = null

    override suspend fun beginLogin(): LoginChallenge = safe {
        requireOpen()
        cookies.clear()
        activeChallenge = null
        val url = EndpointPolicy.withQuery("https://account.xiaomi.com/longPolling/loginUrl", linkedMapOf(
            "_qrsize" to "480", "qs" to "%3Fsid%3Dmiothealth%26_json%3Dtrue",
            "callback" to "https://sts-hlth.io.mi.com/healthapp/sts", "_hasLogo" to "false",
            "sid" to "miothealth", "serviceParam" to "", "_locale" to "zh_CN", "_dc" to wallMillis().toString(),
        ))
        val response = request("GET", url)
        requireSuccess(response)
        val data = json(response)
        if (data.optInt("code", -1) != 0) throw ProbeException(ProbeProblem.REJECTED)
        val loginUrl = data.optString("loginUrl")
        val pollingUrl = data.optString("lp")
        val qrUrl = data.optString("qr")
        EndpointPolicy.account(loginUrl)
        EndpointPolicy.account(pollingUrl)
        val duration = data.optLong("timeout", 300).coerceIn(1, 300) * 1000
        var image: ByteArray? = null
        if (qrUrl.isNotBlank()) {
            EndpointPolicy.account(qrUrl)
            try {
                val qr = request("GET", qrUrl, maximumBytes = 1024 * 1024)
                if (qr.status == 200 && qr.header("Content-Type")?.startsWith("image/") == true) image = qr.body
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { /* Browser login is available without the optional QR image. */ }
        }
        requireOpen()
        LoginChallenge(loginUrl, image, elapsedMillis() + duration, pollingUrl, owner).also { activeChallenge = it }
    }

    override suspend fun awaitLogin(challenge: LoginChallenge): XiaomiSession = safe {
        if (challenge.owner !== owner || activeChallenge !== challenge) throw ProbeException(ProbeProblem.AUTH_REQUIRED)
        while (elapsedMillis() < challenge.expiresAtElapsedMillis) {
            currentCoroutineContext().ensureActive()
            requireOpen()
            val response = try {
                val remaining = (challenge.expiresAtElapsedMillis - elapsedMillis()).toInt().coerceIn(1000, 20000)
                request("GET", challenge.pollingUrl, timeoutMillis = remaining)
            } catch (_: SocketTimeoutException) { delay(500); continue }
            if (response.status == 408) { delay(500); continue }
            requireSuccess(response)
            val data = json(response)
            if (data.has("code") && data.optInt("code", -1) != 0) throw ProbeException(ProbeProblem.REJECTED)
            val security = safeText(data.optString("ssecurity"))
            val userId = safeText(data.optString("userId"))
            val cUserId = safeText(data.optString("cUserId").ifBlank { cookies.accountValue("cUserId") })
            if (!userId.matches(Regex("[0-9]{1,24}")) || security.isBlank() || cUserId.isBlank()) {
                throw ProbeException(ProbeProblem.UNSUPPORTED)
            }
            val decodedSecurity = try { Base64.getDecoder().decode(security) } catch (_: IllegalArgumentException) {
                throw ProbeException(ProbeProblem.UNSUPPORTED)
            }
            if (decodedSecurity.size !in 16..256) throw ProbeException(ProbeProblem.UNSUPPORTED)
            // Deliberately discard account-wide passToken. This probe never refreshes or persists it.
            var serviceToken = cookies.healthServiceToken()
            val location = data.optString("location")
            if (location.isNotBlank()) {
                var destination = EndpointPolicy.tokenExchange(location)
                var finished = false
                for (hop in 0 until 5) {
                    val exchange = request("GET", destination.toASCIIString())
                    serviceToken = cookies.healthServiceToken()
                    if (exchange.status in 300..399) {
                        val redirect = exchange.header("Location") ?: throw ProbeException(ProbeProblem.UNSUPPORTED)
                        destination = EndpointPolicy.tokenExchange(destination.resolve(redirect).toASCIIString())
                        // Never take credentials out of a query string or follow a third-party redirect.
                    } else {
                        requireSuccess(exchange)
                        finished = true
                        break
                    }
                }
                if (!finished) throw ProbeException(ProbeProblem.UNSUPPORTED)
            }
            if (serviceToken.isBlank()) throw ProbeException(ProbeProblem.UNSUPPORTED)
            // STS activation uses only Xiaomi's allowlisted endpoint; a failure is not silently hidden.
            val sts = request("GET", EndpointPolicy.withQuery("https://sts-hlth.io.mi.com/healthapp/sts", linkedMapOf(
                "d" to deviceId, "ticket" to "0", "pwd" to "0", "p_ts" to wallMillis().toString(),
                "fid" to "0", "p_lm" to "2", "p_ur" to "CN",
            )))
            requireSuccess(sts)
            if (sts.body.toString(Charsets.UTF_8).trim() != "ok") throw ProbeException(ProbeProblem.UNSUPPORTED)
            serviceToken = cookies.healthServiceToken()
            if (serviceToken.isBlank()) throw ProbeException(ProbeProblem.AUTH_REQUIRED)
            requireOpen()
            activeChallenge = null
            cookies.clear()
            return@safe XiaomiSession(safeText(serviceToken), security, userId, cUserId, owner)
        }
        throw ProbeException(ProbeProblem.LOGIN_EXPIRED)
    }

    override suspend fun readRecentWeights(session: XiaomiSession): WeightReadResult = readWeightWindow(session, 7)

    internal fun resumeSession(payload: String): XiaomiSession {
        requireOpen()
        val json = JSONObject(payload)
        val token = safeText(json.getString("serviceToken"))
        val security = safeText(json.getString("security"))
        val user = safeText(json.getString("userId"))
        val cuser = safeText(json.getString("cUserId"))
        require(token.isNotBlank() && cuser.isNotBlank() && user.matches(Regex("[0-9]{1,24}")))
        require(Base64.getDecoder().decode(security).size in 16..256)
        return XiaomiSession(token,security,user,cuser,owner)
    }

    suspend fun readWeightWindow(session: XiaomiSession, days: Int): WeightReadResult = safe {
        require(days in 1..56)
        requireOpen()
        if (session.owner !== owner) throw ProbeException(ProbeProblem.AUTH_REQUIRED)
        val end = wallMillis() / 1000
        val start = end - days * 86400L
        val rows = mutableListOf<Map<String, Any?>>()
        var malformedRows = 0
        var incomplete = false
        var cursor = start
        while (cursor < end) {
            currentCoroutineContext().ensureActive()
            val sliceEnd = minOf(cursor + 86400, end)
            val query = JSONObject().put("key", "weight").put("start_time", cursor).put("end_time", sliceEnd)
                .put("reverse", false).put("limit", 100)
            val encoded = CloudCipher.encryptRequest("POST", EndpointPolicy.WEIGHT_PATH, session.security, query.toString())
            val endpoint = "https://${EndpointPolicy.HEALTH_HOST}${EndpointPolicy.WEIGHT_PATH}"
            EndpointPolicy.health(endpoint)
            val response = transport.execute("POST", endpoint, mapOf(
                "User-Agent" to "Android-12-3.53.1-vivo-V2284A",
                "region_tag" to "cn", "handleparams" to "true",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Cookie" to "cUserId=${safeText(session.cUserId)}; userId=${safeText(session.userId)}; serviceToken=${safeText(session.serviceToken)}",
            ), EndpointPolicy.form(encoded).toByteArray(Charsets.UTF_8))
            requireSuccess(response)
            val body = response.body.toString(Charsets.UTF_8)
            // Plain JSON is accepted only as a well-formed API envelope (some failures are unencrypted).
            val decoded = if (body.trimStart().startsWith("{")) body else CloudCipher.decryptResponse(
                session.security, encoded.getValue("_nonce"), body.trim(),
            )
            val envelope = JSONObject(decoded)
            when (envelope.optInt("code", -1)) {
                0 -> Unit
                401, 70016, -10001 -> throw ProbeException(ProbeProblem.AUTH_REQUIRED)
                else -> throw ProbeException(ProbeProblem.REJECTED)
            }
            val result = envelope.optJSONObject("result") ?: throw ProbeException(ProbeProblem.UNSUPPORTED)
            val list = result.optJSONArray("data_list") ?: throw ProbeException(ProbeProblem.UNSUPPORTED)
            if (list.length() > 1000) throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
            // The bounded experiment does not pretend that a limited page is complete history.
            if (list.length() >= 100 || paginationMayContinue(result)) incomplete = true
            for (index in 0 until list.length()) {
                val row = list.optJSONObject(index)
                if (row == null) { malformedRows++; continue }
                val value = when (val rawValue = row.opt("value")) {
                    is JSONObject -> rawValue
                    is String -> try { JSONObject(rawValue) } catch (_: Exception) { null }
                    else -> null
                }
                val parsed = jsonMap(row).toMutableMap()
                // Preserve the identity of a malformed revision so it can veto a conflicting
                // otherwise-valid record; never choose a revision based on arrival order.
                parsed["value"] = value?.let(::jsonMap)
                rows.add(parsed)
            }
            cursor = sliceEnd
        }
        requireOpen()
        val parsed = WeightRecords.parse(rows, start, end, nowEpochSeconds = end)
        parsed.copy(rejectedCount = parsed.rejectedCount + malformedRows,
            incomplete = incomplete || parsed.incomplete || malformedRows > 0)
    }

    private suspend fun request(method: String, url: String, timeoutMillis: Int = 20000,
                                maximumBytes: Int = 2 * 1024 * 1024): HttpReply {
        requireOpen()
        val uri = EndpointPolicy.network(url)
        val cookieHeader = cookies.header(uri)
        val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        if (EndpointPolicy.isAccountHost(uri.host)) headers["Cookie"] = (cookieHeader + "; deviceId=$deviceId").trimStart(';', ' ')
        else if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        val result = transport.execute(method, url, headers, timeoutMillis = timeoutMillis, maximumBytes = maximumBytes)
        requireOpen()
        cookies.receive(uri, result.headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten())
        return result
    }

    private fun json(reply: HttpReply): JSONObject = JSONObject(reply.body.toString(Charsets.UTF_8).removePrefix("&&&START&&&"))

    private fun jsonMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key ->
        when (val item = value.opt(key)) {
            JSONObject.NULL -> null
            is String, is Number, is Boolean -> item
            else -> InvalidField // Invalid supplied fields must not silently become "missing".
        }
    }

    private object InvalidField

    private fun paginationMayContinue(result: JSONObject): Boolean {
        val more = when (val flag = result.opt("has_more")) {
            null, JSONObject.NULL, false -> false
            is Number -> flag.toDouble() != 0.0
            is String -> flag.lowercase() !in setOf("", "0", "false")
            else -> true // Unknown pagination shape is not proof of a complete result.
        }
        val next = when (val key = result.opt("next_key")) {
            null, JSONObject.NULL -> false
            is String -> key.isNotBlank()
            else -> true
        }
        return more || next
    }

    private fun requireSuccess(reply: HttpReply) {
        when {
            reply.status == 401 -> throw ProbeException(ProbeProblem.AUTH_REQUIRED)
            reply.status == 403 || reply.status == 429 -> throw ProbeException(ProbeProblem.REJECTED)
            reply.status !in 200..299 -> throw ProbeException(ProbeProblem.NETWORK)
        }
    }

    private fun safeText(value: String): String {
        if (value.length > 16384 || value.any { it <= ' ' || it == ';' || it == ',' || it.code > 126 }) {
            throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
        }
        return value
    }

    private fun requireOpen() { if (closed) throw ProbeException(ProbeProblem.AUTH_REQUIRED) }

    private suspend fun <T> safe(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: ProbeException) { currentCoroutineContext().ensureActive(); throw known }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); throw ProbeException(ProbeProblem.NETWORK) }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); throw ProbeException(ProbeProblem.UNSUPPORTED) }
    }

    override fun close() {
        closed = true
        activeChallenge?.qrImage?.fill(0)
        activeChallenge = null
        cookies.clear()
        transport.close()
    }
}
