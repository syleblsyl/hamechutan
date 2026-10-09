package il.hamechutan.app.core.util

import java.time.LocalDate

/**
 * Gregorian -> Hebrew calendar conversion (arithmetic algorithm from "Calendrical Calculations",
 * Dershowitz & Reingold). Months are numbered from Nisan = 1; Tishrei = 7; Adar II = 13.
 */
data class HebrewDate(val year: Int, val month: Int, val day: Int) {

    val isLeapYear get() = isLeap(year)

    fun monthName(): String = when (month) {
        1 -> "ניסן"; 2 -> "אייר"; 3 -> "סיון"; 4 -> "תמוז"; 5 -> "אב"; 6 -> "אלול"
        7 -> "תשרי"; 8 -> "חשון"; 9 -> "כסלו"; 10 -> "טבת"; 11 -> "שבט"
        12 -> if (isLeapYear) "אדר א׳" else "אדר"
        13 -> "אדר ב׳"
        else -> "?"
    }

    /** "כ״ז תשרי תשפ״ז" */
    fun format(withYear: Boolean = true): String {
        val d = gematria(day)
        return if (withYear) "$d ${monthName()} ${gematria(year % 1000)}" else "$d ${monthName()}"
    }

    /** Short day label for calendar cells: "כ״ז" */
    fun dayLabel(): String = gematria(day)

    companion object {
        private const val EPOCH = -1373427L // R.D. of 1 Tishrei AM 1

        fun isLeap(y: Int) = Math.floorMod(7L * y + 1, 19L) < 7

        private fun lastMonth(y: Int) = if (isLeap(y)) 13 else 12

        private fun elapsedDays(y: Int): Long {
            val monthsElapsed = Math.floorDiv(235L * y - 234, 19L)
            val partsElapsed = 12084L + 13753L * monthsElapsed
            val days = 29L * monthsElapsed + Math.floorDiv(partsElapsed, 25920L)
            return if (Math.floorMod(3 * (days + 1), 7L) < 3) days + 1 else days
        }

        private fun yearLengthCorrection(y: Int): Long {
            val ny0 = elapsedDays(y - 1)
            val ny1 = elapsedDays(y)
            val ny2 = elapsedDays(y + 1)
            return when {
                ny2 - ny1 == 356L -> 2
                ny1 - ny0 == 382L -> 1
                else -> 0
            }
        }

        private fun newYear(y: Int): Long = EPOCH + elapsedDays(y) + yearLengthCorrection(y)

        private fun daysInYear(y: Int): Long = newYear(y + 1) - newYear(y)

        private fun longMarheshvan(y: Int) = daysInYear(y).let { it == 355L || it == 385L }
        private fun shortKislev(y: Int) = daysInYear(y).let { it == 353L || it == 383L }

        fun lastDayOfMonth(y: Int, m: Int): Int = when {
            m == 2 || m == 4 || m == 6 || m == 10 || m == 13 -> 29
            m == 12 && !isLeap(y) -> 29
            m == 8 && !longMarheshvan(y) -> 29
            m == 9 && shortKislev(y) -> 29
            else -> 30
        }

        private fun fixedFromHebrew(y: Int, m: Int, d: Int): Long {
            var total = newYear(y) + d - 1
            if (m < 7) {
                for (mm in 7..lastMonth(y)) total += lastDayOfMonth(y, mm)
                for (mm in 1 until m) total += lastDayOfMonth(y, mm)
            } else {
                for (mm in 7 until m) total += lastDayOfMonth(y, mm)
            }
            return total
        }

        private fun rataDie(g: LocalDate): Long = g.toEpochDay() + 719163L

        fun fromGregorian(g: LocalDate): HebrewDate {
            val date = rataDie(g)
            val approx = Math.floorDiv((date - EPOCH) * 98496L, 35975351L).toInt() + 1
            var year = approx - 1
            while (newYear(year + 1) <= date) year++
            val start = if (date < fixedFromHebrew(year, 1, 1)) 7 else 1
            var month = start
            while (date > fixedFromHebrew(year, month, lastDayOfMonth(year, month))) month++
            val day = (date - fixedFromHebrew(year, month, 1) + 1).toInt()
            return HebrewDate(year, month, day)
        }

        fun toGregorian(h: HebrewDate): LocalDate = LocalDate.ofEpochDay(fixedFromHebrew(h.year, h.month, h.day) - 719163L)

        /** Hebrew numerals with geresh/gershayim, e.g. 15 -> ט״ו, 787 -> תשפ״ז, 1 -> א׳ */
        fun gematria(n: Int): String {
            if (n <= 0) return n.toString()
            var v = n % 1000
            val sb = StringBuilder()
            while (v >= 400) { sb.append('ת'); v -= 400 }
            val hundreds = listOf("", "ק", "ר", "ש")
            sb.append(hundreds[v / 100]); v %= 100
            if (v == 15) sb.append("טו")
            else if (v == 16) sb.append("טז")
            else {
                val tens = listOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
                val ones = listOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")
                sb.append(tens[v / 10]).append(ones[v % 10])
            }
            val s = sb.toString()
            return if (s.length == 1) "$s׳" else s.substring(0, s.length - 1) + "״" + s.last()
        }
    }
}
