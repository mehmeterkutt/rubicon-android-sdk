package com.rubicon.analytics.internal.util

import com.rubicon.analytics.testing.SeededRandom
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Tests may use java.time; SDK code never does (Serialization 2).
class IsoDatesTest {
    private val reference = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        "0, 1970-01-01T00:00:00.000Z",
        "999, 1970-01-01T00:00:00.999Z",
        "-1, 1969-12-31T23:59:59.999Z",
        "-2208988800000, 1900-01-01T00:00:00.000Z",
        "946684799999, 1999-12-31T23:59:59.999Z",
        "946684800000, 2000-01-01T00:00:00.000Z",
        "951782400000, 2000-02-29T00:00:00.000Z",
        "1709210096789, 2024-02-29T12:34:56.789Z",
        "4107542400000, 2100-03-01T00:00:00.000Z",
        "1759147200123, 2025-09-29T12:00:00.123Z",
        "-62167219200000, 0000-01-01T00:00:00.000Z",
        "253402300799999, 9999-12-31T23:59:59.999Z",
    )
    fun `formats known instants`(
        millis: Long,
        expected: String,
    ) {
        assertEquals(expected, IsoDates.format(millis))
        assertEquals(expected, reference.format(Instant.ofEpochMilli(millis)))
    }

    @Test
    fun `instants outside the years 0000 to 9999 are unsupported`() {
        assertNull(IsoDates.format(IsoDates.MIN_MILLIS - 1))
        assertNull(IsoDates.format(IsoDates.MAX_MILLIS + 1))
        assertNull(IsoDates.format(Long.MIN_VALUE))
        assertNull(IsoDates.format(Long.MAX_VALUE))
    }

    @Test
    fun `agrees with java time on 100 000 random instants`() {
        val random = SeededRandom(2024)
        repeat(100_000) {
            val millis = random.nextLong(IsoDates.MIN_MILLIS, IsoDates.MAX_MILLIS + 1)
            assertEquals(reference.format(Instant.ofEpochMilli(millis)), IsoDates.format(millis), "at $millis")
        }
    }

    @Test
    fun `day arithmetic round-trips over the whole supported range`() {
        val first = IsoDates.MIN_MILLIS / 86_400_000
        val last = IsoDates.MAX_MILLIS / 86_400_000
        for (day in first..last) {
            val (year, month, dayOfMonth) = IsoDates.civilFromDays(day).toList()
            assertEquals(day, IsoDates.daysFromCivil(year, month, dayOfMonth))
        }
        assertArrayEquals(intArrayOf(1970, 1, 1), IsoDates.civilFromDays(0))
    }
}
