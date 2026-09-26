// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.coroutines.coroutineContext

internal class HttpReply(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    override fun toString(): String = "HttpReply(status=$status, body=REDACTED)"
}

internal interface ProbeTransport : AutoCloseable {
    suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null,
                        timeoutMillis: Int = 20000, maximumBytes: Int = 2 * 1024 * 1024): HttpReply
}

internal class UrlConnectionTransport : ProbeTransport {
    private val connections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    @Volatile private var closed = false

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                                 timeoutMillis: Int, maximumBytes: Int): HttpReply = withContext(Dispatchers.IO) {
        EndpointPolicy.network(url)
        ensureActive()
        if (closed) throw ProbeException(ProbeProblem.AUTH_REQUIRED)
        val connection = URL(url).openConnection() as HttpURLConnection
        synchronized(connections) {
            if (closed) { connection.disconnect(); throw ProbeException(ProbeProblem.AUTH_REQUIRED) }
            connections.add(connection)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) ->
                if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                    throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
                }
                connection.setRequestProperty(name, value)
            }
            body?.let {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(it.size)
                connection.outputStream.use { output -> output.write(it) }
            }
            val status = connection.responseCode
            if (connection.contentLengthLong > maximumBytes) throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maximumBytes) throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
                    output.write(buffer, 0, count)
                }
            }
            // HttpURLConnection's status line has a null key despite the platform generic type.
            val platformHeaders: Map<String?, List<String>> = connection.headerFields
            val responseHeaders = platformHeaders.entries.mapNotNull { (key, value) -> key?.let { it to value } }.toMap()
            HttpReply(status, responseHeaders, output.toByteArray())
        } finally {
            connections.remove(connection)
            connection.disconnect()
        }
    }

    override fun close() {
        synchronized(connections) {
            closed = true
            connections.toList().forEach { it.disconnect() }
            connections.clear()
        }
    }
}
