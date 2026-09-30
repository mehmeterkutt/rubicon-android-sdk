package com.rubicon.analytics.internal.util

import com.rubicon.analytics.RubiconInternal

/**
 * Formats epoch milliseconds as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC, the one date format of the
 * wire (Event Model 5, Serialization 4). Pure calendar arithmetic: no `java.time`, no
 * `SimpleDateFormat`, independent of locale and thread-safe.
 */
@RubiconInternal
public object IsoDates {
    /** 0000-01-01T00:00:00.000Z */
    public const val MIN_MILLIS: Long = -62_167_219_200_000L

    /** 9999-12-31T23:59:59.999Z */
    public const val MAX_MILLIS: Long = 253_402_300_799_999L

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Returns `null` outside the years 0000 to 9999, which are unsupported (Serialization 4). */
    public fun format(epochMillis: Long): String? {
        if (epochMillis !in MIN_MILLIS..MAX_MILLIS) return null
        val days = epochMillis.floorDiv(MILLIS_PER_DAY)
        val millisOfDay = epochMillis.mod(MILLIS_PER_DAY)
        val date = civilFromDays(days)
        val out = StringBuilder(24)
        out
            .pad(date[0], 4)
            .append('-')
            .pad(date[1], 2)
            .append('-')
            .pad(date[2], 2)
            .append('T')
        out.pad((millisOfDay / 3_600_000).toInt(), 2).append(':')
        out.pad((millisOfDay / 60_000 % 60).toInt(), 2).append(':')
        out.pad((millisOfDay / 1_000 % 60).toInt(), 2).append('.')
        out.pad((millisOfDay % 1_000).toInt(), 3).append('Z')
        return out.toString()
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date; [month] is 1 to 12. */
    internal fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        // Howard Hinnant's algorithm, with years starting in March so the leap day comes last.
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yearOfEra = y - era * 400
        val monthFromMarch = (month + 9) % 12
        val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** The year, month (1 to 12) and day of [days] since 1970-01-01. */
    internal fun civilFromDays(days: Long): IntArray {
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthFromMarch = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthFromMarch + 2) / 5 + 1).toInt()
        val month = (if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return intArrayOf(year, month, day)
    }

    private fun StringBuilder.pad(
        value: Int,
        width: Int,
    ): StringBuilder {
        val text = value.toString()
        repeat(width - text.length) { append('0') }
        return append(text)
    }
}
