package com.nuvio.iptv.model

/**
 * Minimal calendar maths so the engine does not depend on java.time (absent on Android 24/25
 * without desugaring, and not available in Kotlin common code).
 */
data class CivilTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    fun toEpochMillis(offsetMinutes: Int = 0): Long {
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1_000L
    }

    companion object {
        fun fromEpochMillis(epochMs: Long, offsetMinutes: Int = 0): CivilTime {
            val totalSeconds = Math.floorDiv(epochMs, 1_000L) + offsetMinutes * 60L
            val days = Math.floorDiv(totalSeconds, 86_400L)
            val secOfDay = Math.floorMod(totalSeconds, 86_400L).toInt()
            val (y, m, d) = civilFromDays(days)
            return CivilTime(y, m, d, secOfDay / 3_600, (secOfDay / 60) % 60, secOfDay % 60)
        }

        // Howard Hinnant's days_from_civil / civil_from_days.
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1L else year.toLong()
            val era = Math.floorDiv(y, 400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
            val z = daysSinceEpoch + 719_468
            val era = Math.floorDiv(z, 146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return Triple(y, m, d)
        }
    }
}

internal fun Int.pad2(): String = toString().padStart(2, '0')
internal fun Int.pad4(): String = toString().padStart(4, '0')
