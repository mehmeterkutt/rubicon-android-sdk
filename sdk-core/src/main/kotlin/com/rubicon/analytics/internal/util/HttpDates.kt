package com.rubicon.analytics.internal.util

import com.rubicon.analytics.RubiconInternal

/**
 * Parses the HTTP date form of `Retry-After` (Retry 3, HTTP 4): the IMF-fixdate of RFC 9110,
 * section 5.6.7, such as `Sun, 06 Nov 1994 08:49:37 GMT`.
 *
 * The two obsolete forms that RFC 9110 asks recipients to accept (RFC 850, `Sunday, 06-Nov-94 ...`,
 * and asctime, `Sun Nov  6 08:49:37 1994`) are not parsed: they give `null`, and the header then
 * counts as absent (plan section 15). The day name is not checked against the date, since RFC 9110
 * makes it redundant.
 */
@RubiconInternal
public object HttpDates {
    private val IMF_FIXDATE =
        Regex(
            """(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) """ +
                """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT""",
        )
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Epoch milliseconds, or `null` when [value] is not an IMF-fixdate or not a real date. */
    public fun parse(value: String): Long? {
        val match = IMF_FIXDATE.matchEntire(value.trim()) ?: return null
        val (_, dayText, monthText, yearText, hourText, minuteText, secondText) = match.destructured
        val year = yearText.toInt()
        val month = MONTHS.indexOf(monthText) + 1
        val day = dayText.toInt()
        val hour = hourText.toInt()
        val minute = minuteText.toInt()
        // 60 is a leap second (RFC 9110 allows it); it counts as the first second of the next minute.
        val second = secondText.toInt()
        if (day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 60) return null
        val days = IsoDates.daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute) * 60_000 + second * 1_000L
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
}
