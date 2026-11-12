package gay.benjifox.foxyphotos.core.platform

/** Wall-clock time in epoch milliseconds. */
expect fun currentTimeMillis(): Long

/** ISO-8601 UTC rendering of an epoch-millisecond timestamp, e.g. 2026-11-12T21:38:22.123Z. */
expect fun formatEpochMillisIso(millis: Long): String

/** ISO-8601 UTC rendering of the current wall-clock time. */
expect fun formatCurrentTimeIso(): String

/** Incremental SHA-256 digest over bounded reads. */
expect class Sha256Digest() {
    fun update(buffer: ByteArray, offset: Int, count: Int)

    fun digestHex(): String

    fun close()
}

/**
 * Pure-Kotlin ISO-8601 rendering used where a platform date API is not worth the
 * seam. Matches java.time.Instant.toString() for millisecond inputs, so both
 * platforms render identical diagnostics.
 */
internal fun iso8601FromEpochMillis(millis: Long): String = buildString {
    val totalSeconds = millis.floorDiv(1000L)
    val fraction = millis.mod(1000L)
    val days = totalSeconds.floorDiv(86_400L)
    val secondsOfDay = totalSeconds.mod(86_400L)
    val (year, month, day) = civilFromDays(days)
    append(year.toString().padStart(4, '0'))
    append('-')
    append(month.toString().padStart(2, '0'))
    append('-')
    append(day.toString().padStart(2, '0'))
    append('T')
    append((secondsOfDay / 3600L).toString().padStart(2, '0'))
    append(':')
    append(((secondsOfDay % 3600L) / 60L).toString().padStart(2, '0'))
    append(':')
    append((secondsOfDay % 60L).toString().padStart(2, '0'))
    if (fraction != 0L) {
        append('.')
        append(fraction.toString().padStart(3, '0'))
    }
    append('Z')
}

/** Howard Hinnant's civil-from-days algorithm; [days] counts from 1970-01-01. */
private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthPrime + 2) / 5 + 1).toInt()
    val month = (if (monthPrime < 10) monthPrime + 3 else monthPrime - 9).toInt()
    return Triple(if (month <= 2) year + 1 else year, month, day)
}
