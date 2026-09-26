// SPDX-License-Identifier: GPL-3.0-or-later
package com.personal.fitnessledger.xiaomiprobe.protocol

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightRecordsTest {
    private val start = Instant.parse("2026-09-01T00:00:00Z").epochSecond
    private val end = Instant.parse("2026-09-21T00:00:00Z").epochSecond
    private val measured = start + 3_600

    @Test
    fun `reads only supplied original fields and keeps their precision`() {
        val result = parse(row(weight = "73.425", fat = "18.3", water = BigDecimal("61.125")))

        assertEquals(1, result.records.size)
        assertEquals(
            WeightRecord(Instant.ofEpochSecond(measured), 73.425, 18.3, 61.125, "source-a"),
            result.records.single(),
        )
        assertEquals(0, result.rejectedCount)
        assertFalse(result.incomplete)
    }

    @Test
    fun `missing and zero composition remain null without derived defaults`() {
        val missing = row(time = measured)
        val zero = row(time = measured + 1, fat = "0.0", water = -0.0)
        val result = parse(missing, zero)

        assertEquals(2, result.records.size)
        result.records.forEach {
            assertNull(it.bodyFatPercent)
            assertNull(it.waterPercent)
        }
        assertFalse(result.incomplete)
    }

    @Test
    fun `profile bmi and alternate fields never supply weight or composition`() {
        val input = mapOf<String, Any?>(
            "key" to "weight", "time" to measured, "sid" to "source-a",
            "weight" to 72.0, "body_fat_rate" to 20.0, "moisture_rate" to 60.0,
            "value" to mapOf("bmi" to 22, "profile_weight" to 72, "bodyFat" to 20),
        )

        assertRejected(parse(input), 1)
        val weightOnly = row() + ("profile" to mapOf("body_fat_rate" to 22, "moisture_rate" to 60))
        val record = parse(weightOnly).records.single()
        assertNull(record.bodyFatPercent)
        assertNull(record.waterPercent)
    }

    @Test
    fun `rejects missing nonfinite and implausible weights`() {
        val invalid = listOf(null, 0, -70, 0.999, 500.001, "NaN", Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "1e309", "not a number", true)
        val result = parse(*invalid.mapIndexed { index, weight -> row(time = measured + index, weight = weight) }.toTypedArray())

        assertRejected(result, invalid.size)
    }

    @Test
    fun `accepts numerical guard boundaries without claiming a scale capacity`() {
        val result = parse(row(weight = 1), row(time = measured + 1, weight = 500))

        assertEquals(listOf(500.0, 1.0), result.records.map { it.weightKg })
        assertEquals(0, result.rejectedCount)
    }

    @Test
    fun `rejects corrupt composition rather than replacing it with an estimate`() {
        val invalid = listOf(-0.1, 100.01, "NaN", Double.POSITIVE_INFINITY, "unknown", true)
        val rows = invalid.flatMapIndexed { index, value ->
            listOf(row(time = measured + index * 2, fat = value), row(time = measured + index * 2 + 1, water = value))
        }

        assertRejected(parse(*rows.toTypedArray()), rows.size)
    }

    @Test
    fun `positive percentages keep original scale without fraction conversion`() {
        val record = parse(row(fat = 0.25, water = 100)).records.single()

        assertEquals(0.25, record.bodyFatPercent!!, 0.0)
        assertEquals(100.0, record.waterPercent!!, 0.0)
    }

    @Test
    fun `unknown explicit units are rejected and no pounds conversion is inferred`() {
        val invalidUnits = listOf("lb", "lbs", "jin", "g", "", 0, 1)
        val rows = invalidUnits.mapIndexed { index, unit ->
            row(time = measured + index, valueExtras = mapOf("unit" to unit))
        }

        assertRejected(parse(*rows.toTypedArray()), rows.size)
        assertEquals(160.0, parse(row(weight = 160)).records.single().weightKg, 0.0)
        assertEquals(73.425, parse(row(valueExtras = mapOf("unit" to " KG "))).records.single().weightKg, 0.0)
    }

    @Test
    fun `explicit conflicting outer unit cannot bypass validation`() {
        val record = row(valueExtras = mapOf("unit" to "kg")) + ("unit" to "lbs")

        assertRejected(parse(record), 1)
    }

    @Test
    fun `missing malformed fractional and millisecond times are rejected`() {
        val invalidTimes = listOf(null, "", "yesterday", measured + 0.5, Double.NaN,
            measured * 1_000, -1, 0, Long.MAX_VALUE, "9223372036854775808", true)

        assertRejected(parse(*invalidTimes.map { row(time = it) }.toTypedArray()), invalidTimes.size)
    }

    @Test
    fun `accepts exact epoch seconds from normal numeric representations`() {
        val inputs = listOf(measured, measured.toString(), "$measured.000", BigDecimal(measured), measured.toDouble())
        val result = parse(*inputs.mapIndexed { index, time -> row(time = time, source = "source-$index") }.toTypedArray())

        assertEquals(inputs.size, result.records.size)
        assertTrue(result.records.all { it.measuredAt == Instant.ofEpochSecond(measured) })
    }

    @Test
    fun `range is inclusive but out of range records are rejected`() {
        val result = parse(row(time = start - 1), row(time = start), row(time = end), row(time = end + 1))

        assertEquals(listOf(end, start), result.records.map { it.measuredAt.epochSecond })
        assertEquals(2, result.rejectedCount)
        assertTrue(result.incomplete)
    }

    @Test
    fun `future records are rejected even when requested end is later`() {
        val result = WeightRecords.parse(listOf(row(time = end), row(time = end + 1)), start, end + 100, end)

        assertEquals(listOf(end), result.records.map { it.measuredAt.epochSecond })
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun `same day measurements retain distinct instants and deterministic ordering`() {
        val rows = listOf(row(time = measured), row(time = measured + 60), row(time = measured, source = "source-b"))
        val result = parse(*rows.toTypedArray())

        assertEquals(result, parse(*rows.reversed().toTypedArray()))
        assertEquals(listOf(measured + 60, measured, measured), result.records.map { it.measuredAt.epochSecond })
        assertEquals(listOf("source-a", "source-a", "source-b"), result.records.map { it.source })
    }

    @Test
    fun `exact normalized repetitions coalesce without marking the read incomplete`() {
        val result = parse(row(), row(weight = "73.425"), row())

        assertEquals(1, result.records.size)
        assertEquals(0, result.rejectedCount)
        assertFalse(result.incomplete)
    }

    @Test
    fun `conflicting revisions exclude the full group regardless of order`() {
        val rows = listOf(row(), row(), row(weight = 74), row(time = measured + 1))
        val result = parse(*rows.toTypedArray())

        assertEquals(result, parse(*rows.reversed().toTypedArray()))
        assertEquals(listOf(measured + 1), result.records.map { it.measuredAt.epochSecond })
        assertEquals(3, result.rejectedCount)
        assertTrue(result.incomplete)
    }

    @Test
    fun `composition revisions are conflicts even if weight matches`() {
        assertRejected(parse(row(fat = 20), row(fat = 21)), 2)
        assertRejected(parse(row(water = 50), row(water = 51)), 2)
        assertRejected(parse(row(), row(fat = 20)), 2)
    }

    @Test
    fun `malformed revision cannot silently leave a valid competing revision selected`() {
        val rows = listOf(row(), row(weight = "broken"), row(source = "source-b"))
        val result = parse(*rows.toTypedArray())

        assertEquals(result, parse(*rows.reversed().toTypedArray()))
        assertEquals(listOf("source-b"), result.records.map { it.source })
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun `missing source is explicitly unknown and never labeled as a device model`() {
        val result = parse(row(source = null), row(time = measured + 1, source = " "))

        assertEquals(listOf("unknown", "unknown"), result.records.map { it.source })
        assertRejected(parse(row(source = 123)), 1)
    }

    @Test
    fun `source identity length is bounded without truncating or merging identifiers`() {
        val source = "a".repeat(255)
        val result = parse(row(source = source), row(source = source + "b"))

        assertEquals(listOf(source, source + "b"), result.records.map { it.source })
        assertRejected(parse(row(source = source + "bc")), 1)
        assertEquals("  source-a  ", parse(row(source = "  source-a  ")).records.single().source)
    }

    @Test
    fun `source control and bidirectional characters are rejected instead of removed`() {
        val controls = listOf('\u0000', '\n', '\r', '\t', '\u007F', '\u0085', '\u061C', '\u200E', '\u200F') +
            ('\u2028'..'\u202E') + ('\u2066'..'\u2069')
        val invalid = controls.map { "source-${it}a" } + "\n"

        assertRejected(parse(*invalid.map { row(source = it) }.toTypedArray()), invalid.size)
    }

    @Test
    fun `malformed payloads and nonweight keys are rejected without fallback`() {
        val rows = listOf(emptyMap(), row() - "key", row() + ("key" to "profile"),
            row() - "value", row() + ("value" to "{\"weight\":73}"), row() + ("value" to listOf(73)))

        assertRejected(parse(*rows.toTypedArray()), rows.size)
    }

    @Test
    fun `numeric syntax and excessive numeric strings are bounded`() {
        val invalid = listOf("0x1.0p6", "73kg", "7,3", "7_3", "7".repeat(65), "1e999999999999")

        assertRejected(parse(*invalid.mapIndexed { index, weight -> row(time = measured + index, weight = weight) }.toTypedArray()), invalid.size)
        assertEquals(73.425, parse(row(weight = " 7.3425e1 ")).records.single().weightKg, 0.0)
    }

    @Test
    fun `empty response stays empty and does not synthesize measurements`() {
        val result = parse()

        assertTrue(result.records.isEmpty())
        assertEquals(0, result.rejectedCount)
        assertFalse(result.incomplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid query interval fails as caller error`() {
        WeightRecords.parse(listOf(row()), end, start, end)
    }

    private fun parse(vararg rows: Map<String, Any?>): WeightReadResult =
        WeightRecords.parse(rows.toList(), start, end, end)

    private fun row(
        time: Any? = measured,
        weight: Any? = 73.425,
        fat: Any? = null,
        water: Any? = null,
        source: Any? = "source-a",
        valueExtras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "key" to "weight", "time" to time, "sid" to source,
        "value" to (mapOf("weight" to weight, "body_fat_rate" to fat, "moisture_rate" to water) + valueExtras),
    )

    private fun assertRejected(result: WeightReadResult, count: Int) {
        assertTrue(result.records.isEmpty())
        assertEquals(count, result.rejectedCount)
        assertTrue(result.incomplete)
    }
}
