package il.hamechutan.app.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

/** Date helpers. Storage: dates "yyyy-MM-dd", times "HH:mm". Display: Hebrew. */
object Dates {
    private val ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val ISO_TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private const val LRI = '⁦'
    private const val PDI = '⁩'

    val MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
    /** Index 0 = Sunday. */
    val DAY_NAMES = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    val DAY_LETTERS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")

    fun parseDate(s: String?): LocalDate? = try {
        if (s.isNullOrBlank()) null else LocalDate.parse(s.trim(), ISO_DATE)
    } catch (e: Exception) { null }

    fun parseTime(s: String?): LocalTime? = try {
        if (s.isNullOrBlank()) null else LocalTime.parse(s.trim(), ISO_TIME)
    } catch (e: Exception) { null }

    fun iso(d: LocalDate): String = d.format(ISO_DATE)
    fun iso(t: LocalTime): String = t.format(ISO_TIME)

    fun isValidDate(s: String?) = parseDate(s) != null
    fun isValidTime(s: String?) = parseTime(s) != null

    /** Sunday-based index 0..6 */
    fun dayIndex(d: LocalDate): Int = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value

    fun dayName(d: LocalDate) = "יום " + DAY_NAMES[dayIndex(d)]
    fun dayShort(d: LocalDate) = "יום " + DAY_LETTERS[dayIndex(d)]

    /** "09/10/2026" isolated for RTL. */
    fun display(d: LocalDate): String = "$LRI${d.format(DISPLAY_DATE)}$PDI"
    fun display(iso: String?): String = parseDate(iso)?.let { display(it) } ?: ""
    fun displayTime(t: LocalTime): String = "$LRI${t.format(ISO_TIME)}$PDI"
    fun displayTime(iso: String?): String = parseTime(iso)?.let { displayTime(it) } ?: ""

    /** "יום ה׳, 09/10/2026" */
    fun displayWithDay(d: LocalDate): String = "${dayShort(d)}, ${display(d)}"

    /** "9 באוקטובר 2026" */
    fun displayLong(d: LocalDate): String = "${d.dayOfMonth} ב${MONTHS[d.monthValue - 1]} ${d.year}"

    fun monthTitle(year: Int, month: Int) = "${MONTHS[month - 1]} $year"

    /** Relative label: "היום", "מחר", "אתמול", "בעוד 5 ימים", "לפני 3 ימים". */
    fun relative(d: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "היום"
            diff == 1L -> "מחר"
            diff == 2L -> "מחרתיים"
            diff == -1L -> "אתמול"
            diff > 0 -> "בעוד ${daysText(diff)}"
            else -> "לפני ${daysText(-diff)}"
        }
    }

    fun daysText(n: Long): String = when (n) {
        1L -> "יום אחד"
        2L -> "יומיים"
        else -> "$n ימים"
    }

    /** "היום · 18:00" style label combining relative date and optional time. */
    fun whenLabel(date: String?, time: String?, today: LocalDate): String {
        val d = parseDate(date) ?: return "ללא תאריך"
        val rel = relative(d, today)
        val base = if (kotlin.math.abs(ChronoUnit.DAYS.between(today, d)) <= 2) rel else "${dayShort(d)} ${display(d)}"
        val t = parseTime(time)
        return if (t != null) "$base · ${displayTime(t)}" else base
    }

    fun toMillis(d: LocalDate, t: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()

    fun fromMillis(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    fun displayDateTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = fromMillis(ms, zone)
        return "${display(dt.toLocalDate())} ${displayTime(dt.toLocalTime())}"
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}
