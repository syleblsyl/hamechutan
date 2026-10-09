package il.hamechutan.app.core.util

import kotlin.math.abs

/** Money helpers. All amounts are Long agorot (1 ₪ = 100 agorot). */
object Money {
    const val MAX_AGOROT = 100_000_000_00L // 100 million ₪ sanity limit

    private const val LRI = '⁦'
    private const val PDI = '⁩'

    /** "8,000 ₪" / "1,250.50 ₪". Number is bidi-isolated so it renders correctly in RTL text. */
    fun format(agorot: Long, withSymbol: Boolean = true, isolate: Boolean = true): String {
        val num = number(agorot)
        val n = if (isolate) "$LRI$num$PDI" else num
        return if (withSymbol) "$n ₪" else n
    }

    /** Plain number "8,000" or "-1,250.50" without symbol or isolation. */
    fun number(agorot: Long): String {
        val neg = agorot < 0
        val a = abs(agorot)
        val shekels = a / 100
        val ag = a % 100
        val whole = group(shekels)
        val s = if (ag == 0L) whole else whole + "." + ag.toString().padStart(2, '0')
        return if (neg) "-$s" else s
    }

    /** Value suitable for pre-filling an input field: "8000" or "1250.5". */
    fun inputValue(agorot: Long): String {
        val shekels = agorot / 100
        val ag = abs(agorot % 100)
        if (ag == 0L) return shekels.toString()
        val frac = ag.toString().padStart(2, '0').trimEnd('0')
        return "$shekels.$frac"
    }

    private fun group(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }

    sealed class Parse {
        data class Ok(val agorot: Long) : Parse()
        data class Error(val message: String) : Parse()
        object Empty : Parse()
    }

    /**
     * Parses user input: accepts "8000", "8,000", "8 000", "8000.5", "8000,50" (comma as decimal
     * separator only when followed by 1-2 digits at the end), optional ₪ sign.
     */
    fun parse(input: String?): Parse {
        if (input == null) return Parse.Empty
        var s = input.trim().replace("₪", "").replace("ש\"ח", "").replace("ש״ח", "")
            .replace("⁦", "").replace("⁩", "").replace("‏", "").replace("‎", "")
            .replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return Parse.Empty
        if (s.startsWith("-")) return Parse.Error("הסכום חייב להיות חיובי")
        // Decimal comma: "1250,5" or "1250,50" (but not "1,250")
        val decimalComma = Regex("^\\d+,\\d{1,2}$")
        if (decimalComma.matches(s) && !Regex("^\\d{1,3},\\d{3}$").matches(s)) s = s.replace(',', '.')
        s = s.replace(",", "")
        if (!Regex("^\\d+(\\.\\d{0,2})?$").matches(s)) {
            return if (Regex("^\\d+\\.\\d{3,}$").matches(s)) Parse.Error("אפשר להזין עד שתי ספרות אחרי הנקודה")
            else Parse.Error("יש להזין סכום תקין במספרים")
        }
        val parts = s.split('.')
        val whole = parts[0].trimStart('0').ifEmpty { "0" }
        if (whole.length > 9) return Parse.Error("הסכום גדול מדי")
        val frac = if (parts.size > 1) parts[1].padEnd(2, '0') else "00"
        val agorot = whole.toLong() * 100 + frac.toLong()
        if (agorot > MAX_AGOROT) return Parse.Error("הסכום גדול מדי")
        return Parse.Ok(agorot)
    }

    fun percent(part: Long, total: Long): Int =
        if (total <= 0) 0 else ((part.toDouble() / total.toDouble()) * 100.0).toInt()
}
