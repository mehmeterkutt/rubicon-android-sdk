package com.rubicon.analytics.internal.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest

class HttpDatesAndHashingTest {
    @Nested
    inner class `http dates` {
        @Test
        fun `parses the IMF-fixdate example of RFC 9110`() {
            assertEquals(784_111_777_000L, HttpDates.parse("Sun, 06 Nov 1994 08:49:37 GMT"))
        }

        @Test
        fun `parses other valid dates`() {
            assertEquals(0L, HttpDates.parse("Thu, 01 Jan 1970 00:00:00 GMT"))
            assertEquals(1_709_210_096_000L, HttpDates.parse("Thu, 29 Feb 2024 12:34:56 GMT"))
            assertEquals(1_759_147_320_000L, HttpDates.parse("Mon, 29 Sep 2025 12:02:00 GMT"))
        }

        @Test
        fun `follows the leap year rule for centuries`() {
            assertEquals(951_782_400_000L, HttpDates.parse("Tue, 29 Feb 2000 00:00:00 GMT"))
            assertNull(HttpDates.parse("Mon, 29 Feb 2100 00:00:00 GMT"))
        }

        @Test
        fun `ignores whitespace around the value`() {
            assertEquals(784_111_777_000L, HttpDates.parse("  Sun, 06 Nov 1994 08:49:37 GMT \t"))
        }

        @Test
        fun `a leap second counts as the next minute`() {
            assertEquals(HttpDates.parse("Sat, 31 Dec 2016 23:59:59 GMT")!! + 1_000, HttpDates.parse("Sat, 31 Dec 2016 23:59:60 GMT"))
        }

        @Test
        fun `does not check the day name`() {
            assertEquals(784_111_777_000L, HttpDates.parse("Mon, 06 Nov 1994 08:49:37 GMT"))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                // The obsolete forms of RFC 9110 count as absent.
                "Sunday, 06-Nov-94 08:49:37 GMT",
                "Sun Nov  6 08:49:37 1994",
                // Seconds are Retry-After's other form, parsed elsewhere.
                "120",
                "",
                "Sun, 06 Nov 1994 08:49:37 gmt",
                "sun, 06 Nov 1994 08:49:37 GMT",
                "Sun 06 Nov 1994 08:49:37 GMT",
                "Sun, 6 Nov 1994 08:49:37 GMT",
                "Sun, 06 Nov 94 08:49:37 GMT",
                "Sun, 06 November 1994 08:49:37 GMT",
                "Sun, 06 Nov 1994 08:49:37 UTC",
                "Sun, 06 Nov 1994 08:49:37 +0000",
                "Sun, 06 Nov 1994 08:49 GMT",
                "Sun, 06 Nov 1994 08:49:37 GMT extra",
                "Sun, 31 Nov 1994 08:49:37 GMT",
                "Wed, 29 Feb 2023 00:00:00 GMT",
                "Sun, 00 Nov 1994 08:49:37 GMT",
                "Sun, 06 Nov 1994 24:00:00 GMT",
                "Sun, 06 Nov 1994 08:60:00 GMT",
                "Sun, 06 Nov 1994 08:49:61 GMT",
            ],
        )
        fun `rejects anything else`(value: String) {
            assertNull(HttpDates.parse(value))
        }
    }

    @Nested
    inner class `sha-256` {
        @Test
        fun `matches the published test vectors`() {
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"))
            assertEquals(
                "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
                Hashing.sha256Hex("The quick brown fox jumps over the lazy dog"),
            )
        }

        @Test
        fun `hashes the UTF-8 bytes`() {
            val text = "rk_live_ğüşıöç"
            val expected =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

            assertEquals(expected, Hashing.sha256Hex(text))
        }
    }
}
