// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe.protocol

import java.time.Instant

data class WeightRecord(
    val measuredAt: Instant,
    val weightKg: Double,
    val bodyFatPercent: Double?,
    val waterPercent: Double?,
    val source: String,
)

data class WeightReadResult(
    val records: List<WeightRecord>,
    /** Input rows excluded as invalid or ambiguous; exact repetitions are not rejections. */
    val rejectedCount: Int,
    /** Also set by the caller when fetching or decoding the response was incomplete. */
    val incomplete: Boolean = false,
)

/**
 * Reads normalized original `weight` data_list entries, never account/profile defaults.
 * The network layer must normalize each value JSON object to a Map and enforce account scope.
 * This parser does not prove which person or scale produced an entry.
 *
 * The researched source expresses weight in kg and time in epoch seconds. No magnitude-based
 * unit or timestamp conversion is performed. Only an absent unit or explicit "kg" is accepted.
 * The broad 1..500 kg guard rejects implausible input; it is not a device capability claim.
 */
object WeightRecords {
    const val UNKNOWN_SOURCE = "unknown"

    private val decimalNumber = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    /**
     * The requested interval is inclusive. Future records are rejected even if end is future.
     * Return newest first, then by source, independently of the server's entry ordering.
     * Inject nowEpochSeconds for deterministic tests; the three-argument call uses the clock.
     */
    fun parse(
        rows: List<Map<String, Any?>>,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): WeightReadResult {
        require(startEpochSeconds >= 0 && endEpochSeconds >= startEpochSeconds) {
            "The requested epoch-second interval is invalid"
        }
        require(endEpochSeconds <= Instant.MAX.epochSecond) { "The requested end exceeds Instant" }
        require(nowEpochSeconds in 0..Instant.MAX.epochSecond) { "The current timestamp is invalid" }

        var rejectedCount = 0
        val groups = linkedMapOf<Identity, MutableList<WeightRecord?>>()
        for (row in rows) {
            val identity = identity(row, startEpochSeconds, endEpochSeconds, nowEpochSeconds)
            if (identity == null) {
                rejectedCount++
                continue
            }
            groups.getOrPut(identity) { mutableListOf() }.add(measurement(row, identity))
        }

        val accepted = mutableListOf<WeightRecord>()
        for (candidates in groups.values) {
            val distinct = candidates.distinct()
            val record = distinct.singleOrNull()
            if (record == null) {
                // A malformed or conflicting revision makes the entire identity ambiguous.
                // Never let response ordering select a winning revision silently.
                rejectedCount += candidates.size
            } else {
                accepted.add(record)
            }
        }

        return WeightReadResult(
            records = accepted.sortedWith(compareByDescending<WeightRecord> { it.measuredAt }.thenBy { it.source }),
            rejectedCount = rejectedCount,
            incomplete = rejectedCount > 0,
        )
    }

    private data class Identity(val epochSeconds: Long, val source: String)

    private fun identity(
        row: Map<String, Any?>,
        start: Long,
        end: Long,
        now: Long,
    ): Identity? {
        if (row["key"] != "weight") return null
        val seconds = epochSeconds(row["time"]) ?: return null
        if (seconds <= 0 || seconds < start || seconds > end || seconds > now) return null
        val rawSource = row["sid"]
        val source = when (rawSource) {
            null -> UNKNOWN_SOURCE
            is String -> {
                if (rawSource.length > 256 || rawSource.any(::unsafeSourceCharacter)) return null
                rawSource.takeUnless(String::isBlank) ?: UNKNOWN_SOURCE
            }
            else -> return null
        }
        return Identity(seconds, source)
    }

    private fun unsafeSourceCharacter(character: Char): Boolean =
        character.isISOControl() || character in '\u2028'..'\u202E' || character in '\u2066'..'\u2069' ||
            character == '\u061C' || character == '\u200E' || character == '\u200F'

    private fun measurement(row: Map<String, Any?>, identity: Identity): WeightRecord? {
        val value = row["value"] as? Map<*, *> ?: return null
        // The original schema has no numeric-unit mapping. Reject rather than guessing.
        for (unit in listOf(row["unit"], value["unit"])) {
            if (unit != null && !(unit is String && unit.trim().equals("kg", ignoreCase = true))) return null
        }
        val weight = finiteNumber(value["weight"])?.takeIf { it in 1.0..500.0 } ?: return null
        val fat = percentage(value["body_fat_rate"]) ?: return null
        val water = percentage(value["moisture_rate"]) ?: return null
        return WeightRecord(
            measuredAt = Instant.ofEpochSecond(identity.epochSeconds),
            weightKg = weight,
            bodyFatPercent = fat.value,
            waterPercent = water.value,
            source = identity.source,
        )
    }

    /** Wrapper distinguishes an absent original metric from an invalid supplied metric. */
    private data class Percentage(val value: Double?)

    private fun percentage(raw: Any?): Percentage? {
        if (raw == null) return Percentage(null)
        val parsed = finiteNumber(raw) ?: return null
        if (parsed < 0.0 || parsed > 100.0) return null
        return Percentage(parsed.takeUnless { it == 0.0 })
    }

    private fun finiteNumber(raw: Any?): Double? = numericText(raw)?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun epochSeconds(raw: Any?): Long? {
        val decimal = numericText(raw)?.toBigDecimalOrNull() ?: return null
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun numericText(raw: Any?): String? {
        val text = when (raw) {
            is Number -> raw.toString()
            is String -> raw.trim()
            else -> return null
        }
        // Reject unusual numeric syntax and boundedly parse hostile or corrupted strings.
        return text.takeIf { it.length in 1..64 && decimalNumber.matches(it) }
    }
}
