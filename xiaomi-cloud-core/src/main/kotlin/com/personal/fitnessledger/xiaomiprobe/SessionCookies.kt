// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe

import java.net.URI
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** A bounded, process-local RFC 6265 cookie jar with credential issuer provenance. */
internal class SessionCookies(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class Key(val name: String, val domain: String, val path: String)
    private class Entry(
        val key: Key,
        val value: String,
        val hostOnly: Boolean,
        val expiresAt: Long?,
        val issuerHost: String,
        val issuerPath: String,
        val created: Long,
        val updated: Long,
    )

    private val entries = linkedMapOf<Key, Entry>()
    private var sequence = 0L

    /** Invalid or out-of-scope Set-Cookie values are discarded, never logged. */
    @Synchronized
    fun receive(uri: URI, headers: List<String>) {
        val host = permittedHost(uri) ?: return
        val now = nowMillis()
        removeExpired(now)
        for (header in headers) {
            if (header.length !in 1..MAX_HEADER_CHARS || header.any { it.code !in 0x20..0x7e && it != '\t' }) continue
            val fields = header.split(';')
            val nameValue = fields.first().trim()
            val equals = nameValue.indexOf('=')
            if (equals < 1) continue
            val name = nameValue.substring(0, equals).trim()
            if (name !in ALLOWED_NAMES) continue
            var value = nameValue.substring(equals + 1).trim()
            if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) value = value.substring(1, value.length - 1)
            if (value.length > MAX_VALUE_CHARS || value.any { !isCookieOctet(it) }) continue

            var domain = host
            var hostOnly = true
            var path = defaultPath(uri.rawPath)
            var expiresAt: Long? = null
            var maxAge: Long? = null
            var invalid = false
            for (field in fields.drop(1)) {
                val part = field.trim()
                val separator = part.indexOf('=')
                val attribute = (if (separator < 0) part else part.substring(0, separator)).trim().lowercase(Locale.ROOT)
                val argument = if (separator < 0) "" else part.substring(separator + 1).trim()
                when (attribute) {
                    "domain" -> {
                        val candidate = argument.removePrefix(".").lowercase(Locale.ROOT)
                        if (!validDomain(candidate) || !domainMatches(host, candidate)) {
                            invalid = true
                            break
                        }
                        domain = candidate
                        hostOnly = false
                    }
                    "path" -> {
                        if (argument.length > MAX_PATH_CHARS || argument.any { it.code !in 0x20..0x7e }) {
                            invalid = true
                            break
                        }
                        // Non-absolute or empty Path attributes use the request directory.
                        path = argument.takeIf { it.startsWith('/') } ?: defaultPath(uri.rawPath)
                    }
                    "max-age" -> parseMaxAge(argument)?.let { maxAge = it }
                    "expires" -> parseCookieDate(argument)?.let { expiresAt = it }
                    // All connections are HTTPS. Secure/HttpOnly/SameSite do not widen scope.
                }
            }
            if (invalid) continue
            maxAge?.let { seconds ->
                expiresAt = when {
                    seconds <= 0 -> Long.MIN_VALUE
                    seconds > (Long.MAX_VALUE - now.coerceAtLeast(0)) / 1000 -> Long.MAX_VALUE
                    else -> now + seconds * 1000
                }
            }
            val key = Key(name, domain, path)
            if (expiresAt != null && expiresAt!! <= now) {
                entries.remove(key)
                continue
            }
            val order = ++sequence
            entries[key] = Entry(
                key, value, hostOnly, expiresAt, host, uri.rawPath.orEmpty().ifEmpty { "/" },
                entries[key]?.created ?: order, order,
            )
            while (entries.size > MAX_COOKIES) entries.remove(entries.keys.first())
        }
    }

    /** Returns only cookies whose original scope covers this permitted HTTPS URI. */
    @Synchronized
    fun header(uri: URI): String {
        val host = permittedHost(uri) ?: return ""
        removeExpired(nowMillis())
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        return entries.values.asSequence()
            .filter { entry ->
                (if (entry.hostOnly) host == entry.key.domain else domainMatches(host, entry.key.domain)) &&
                    pathMatches(path, entry.key.path)
            }
            .sortedWith(compareByDescending<Entry> { it.key.path.length }.thenBy { it.created })
            .joinToString("; ") { "${it.key.name}=${it.value}" }
    }

    /** Account identifiers may only be supplied by the Xiaomi account service. */
    @Synchronized
    fun accountValue(name: String): String {
        if (name !in ALLOWED_NAMES) return ""
        removeExpired(nowMillis())
        return entries.values.filter { it.key.name == name && EndpointPolicy.isAccountHost(it.issuerHost) }
            .maxByOrNull { it.updated }?.value.orEmpty()
    }

    /**
     * The observed service flow intentionally transfers an STS-issued token into
     * the health request. Scope matching alone cannot identify it: a valid token
     * can be host-only for STS. Require the exact issuer endpoint instead.
     */
    @Synchronized
    fun healthServiceToken(): String {
        removeExpired(nowMillis())
        return entries.values.filter {
            it.key.name == "serviceToken" && it.issuerHost == EndpointPolicy.STS_HOST &&
                it.issuerPath == STS_PATH
        }.maxByOrNull { it.updated }?.value.orEmpty()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        sequence = 0L
    }

    override fun toString(): String = "SessionCookies(REDACTED)"

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { (_, entry) -> entry.expiresAt?.let { it <= now } == true }
    }

    private fun permittedHost(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https" || uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawFragment != null) return null
        return host.takeIf {
            EndpointPolicy.isAccountHost(it) || it == EndpointPolicy.STS_HOST || it == EndpointPolicy.HEALTH_HOST
        }
    }

    private fun validDomain(domain: String): Boolean {
        if (domain.length !in 1..253 || !domain.split('.').all { DOMAIN_LABEL.matches(it) }) return false
        // These are the registrable Xiaomi domains and service subdomains; public
        // suffixes and unrelated domains are never accepted as cookie domains.
        return domain in setOf("xiaomi.com", "mi.com", "io.mi.com", EndpointPolicy.STS_HOST, EndpointPolicy.HEALTH_HOST) ||
            EndpointPolicy.isAccountHost(domain)
    }

    private fun domainMatches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean =
        requestPath == cookiePath || (requestPath.startsWith(cookiePath) &&
            (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'))

    private fun defaultPath(rawPath: String?): String {
        if (rawPath.isNullOrEmpty() || !rawPath.startsWith('/')) return "/"
        val lastSlash = rawPath.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else rawPath.substring(0, lastSlash)
    }

    private fun isCookieOctet(char: Char): Boolean = char.code in 0x21..0x7e && char !in "\",;\\"

    private fun parseMaxAge(value: String): Long? {
        if (!MAX_AGE.matches(value)) return null
        val digits = value.removePrefix("-").trimStart('0')
        if (value.startsWith('-') || digits.isEmpty()) return 0
        return digits.toLongOrNull() ?: Long.MAX_VALUE
    }

    /** RFC 6265 section 5.1.1, including legacy cookie dates and two-digit years. */
    private fun parseCookieDate(value: String): Long? {
        var day: Int? = null
        var month: Int? = null
        var year: Int? = null
        var time: List<Int>? = null
        for (token in value.split(DATE_DELIMITERS)) {
            if (time == null) {
                val match = COOKIE_TIME.matchEntire(token)
                if (match != null) {
                    time = match.groupValues.drop(1).map(String::toInt)
                    continue
                }
            }
            if (day == null) {
                val match = COOKIE_DAY.matchEntire(token)
                if (match != null) {
                    day = match.groupValues[1].toInt()
                    continue
                }
            }
            if (month == null) {
                val index = MONTHS.indexOf(token.take(3).lowercase(Locale.ROOT))
                if (index >= 0 && (token.length == 3 || token[3] !in '0'..'9')) {
                    month = index + 1
                    continue
                }
            }
            if (year == null) {
                val match = COOKIE_YEAR.matchEntire(token)
                if (match != null) year = match.groupValues[1].toInt()
            }
        }
        val parsedDay = day ?: return null
        val parsedMonth = month ?: return null
        var parsedYear = year ?: return null
        val parsedTime = time ?: return null
        if (parsedYear in 70..99) parsedYear += 1900
        if (parsedYear in 0..69) parsedYear += 2000
        if (parsedYear < 1601) return null
        return try {
            LocalDateTime.of(parsedYear, parsedMonth, parsedDay, parsedTime[0], parsedTime[1], parsedTime[2])
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    private companion object {
        const val MAX_COOKIES = 32
        const val MAX_HEADER_CHARS = 8192
        const val MAX_VALUE_CHARS = 4096
        const val MAX_PATH_CHARS = 2048
        const val STS_PATH = "/healthapp/sts"
        val ALLOWED_NAMES = setOf("serviceToken", "cUserId", "userId", "deviceId")
        val DOMAIN_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        val MAX_AGE = Regex("-?[0-9]+")
        val DATE_DELIMITERS = Regex("[\\x09\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+")
        val COOKIE_TIME = Regex("([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})(?:[^0-9].*)?")
        val COOKIE_DAY = Regex("([0-9]{1,2})(?:[^0-9].*)?")
        val COOKIE_YEAR = Regex("([0-9]{2,4})(?:[^0-9].*)?")
        val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    }
}
