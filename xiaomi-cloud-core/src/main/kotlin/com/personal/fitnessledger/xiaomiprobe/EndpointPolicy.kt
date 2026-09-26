// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object EndpointPolicy {
    const val HEALTH_HOST = "hlth.io.mi.com"
    const val STS_HOST = "sts-hlth.io.mi.com"
    const val WEIGHT_PATH = "/app/v1/data/get_fitness_data_by_time"

    fun parse(url: String): URI {
        if (url.length !in 1..16384 || url.any { it <= ' ' || it == '\\' }) unsafe()
        val uri = try { URI(url) } catch (_: Exception) { unsafe() }
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in listOf(-1, 443) || uri.host.isNullOrBlank()) unsafe()
        return uri
    }

    fun isAccountHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return normalized == "account.xiaomi.com" || normalized.endsWith(".account.xiaomi.com")
    }

    fun account(url: String): URI = parse(url).also { if (!isAccountHost(it.host)) unsafe() }

    fun network(url: String): URI = parse(url).also {
        if (!isAccountHost(it.host) && it.host != STS_HOST && it.host != HEALTH_HOST) unsafe()
    }

    fun health(url: String): URI = parse(url).also {
        if (it.host != HEALTH_HOST || it.rawPath != WEIGHT_PATH || it.rawQuery != null) unsafe()
    }

    /** Login ticket exchange must never become a generic authenticated health request. */
    fun tokenExchange(url: String): URI = parse(url).also {
        if (it.host != STS_HOST || it.rawPath != "/healthapp/sts") unsafe()
    }

    fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        encode(it.key) + "=" + encode(it.value)
    }

    fun withQuery(url: String, values: Map<String, String>): String =
        url + (if (URI(url).rawQuery == null) "?" else "&") + form(values)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun unsafe(): Nothing = throw ProbeException(ProbeProblem.UNSAFE_RESPONSE)
}
