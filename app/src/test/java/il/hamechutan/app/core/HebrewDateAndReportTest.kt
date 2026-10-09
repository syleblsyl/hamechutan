package il.hamechutan.app.core

import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.report.ReportBuilder
import il.hamechutan.app.core.report.ReportType
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.HebrewDate
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HebrewDateAndReportTest {

    /**
     * Cross-checks our Hebrew calendar against reference data generated with the pyluach library:
     * the Gregorian date of the 1st of every Hebrew month 1950–2100. Checking every month start and
     * the day before it verifies all month lengths and leap years in the range.
     */
    @Test fun hebrewDatesMatchReference() {
        val lines = javaClass.getResourceAsStream("/hebrew_month_starts.csv")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
        assertTrue(lines.size > 1800)
        for (l in lines) {
            val (g, y, m) = l.split(',')
            val d = LocalDate.parse(g)
            assertEquals(l, HebrewDate(y.toInt(), m.toInt(), 1), HebrewDate.fromGregorian(d))
            val prev = HebrewDate.fromGregorian(d.minusDays(1))
            assertEquals("month length before $l", HebrewDate.lastDayOfMonth(prev.year, prev.month), prev.day)
            assertEquals(d, HebrewDate.toGregorian(HebrewDate(y.toInt(), m.toInt(), 1)))
        }
        // round trip for every day
        var d = LocalDate.of(1950, 1, 1)
        while (d.year <= 2100) { assertEquals(d, HebrewDate.toGregorian(HebrewDate.fromGregorian(d))); d = d.plusDays(1) }
    }

    @Test fun hebrewFormatting() {
        assertEquals("ט״ו", HebrewDate.gematria(15))
        assertEquals("ט״ז", HebrewDate.gematria(16))
        assertEquals("א׳", HebrewDate.gematria(1))
        assertEquals("ל׳", HebrewDate.gematria(30))
        assertEquals("כ״ז", HebrewDate.gematria(27))
        assertEquals("תשפ״ז", HebrewDate.gematria(787))
        assertEquals("תשע״ה", HebrewDate.gematria(775))
        // Rosh Hashana 5787 = 12 Sep 2026
        assertEquals("א׳ תשרי תשפ״ז", HebrewDate.fromGregorian(LocalDate.of(2026, 9, 12)).format())
        assertEquals("יום שבת", Dates.dayName(LocalDate.of(2026, 10, 10)))
        assertEquals("יום ראשון", Dates.dayName(LocalDate.of(2026, 10, 11)))
    }

    @Test fun allReportsBuildFromRealData() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", phone = "050-1", initialAgreedAgorot = 800000), null)
        val eid = repo.expensesForSupplier(sid).single().id
        repo.saveExpense(ExpenseInput("צלם", null, sid, 800000, nextPaymentDate = "2026-10-20"), eid)
        repo.addPayment(PaymentInput(eid, 200000, "2026-10-01", null))
        val e2 = repo.saveExpense(ExpenseInput("מתנות", null, null, 50000), null)
        repo.addPayment(PaymentInput(e2, 10000, "2026-10-02", null))
        val e3 = repo.saveExpense(ExpenseInput("זמר", null, null, 300000), null)
        repo.addPayment(PaymentInput(e3, 100000, "2026-10-02", null))
        repo.cancelExpense(e3, 40000, "2026-10-03", null, null)
        repo.saveTask(TaskInput("משימה פתוחה", dueDate = "2026-10-01"), null)
        repo.saveTask(TaskInput("משימה גמורה", status = TaskStatus.DONE), null)
        repo.saveEvent(EventInput("אירוע", "2026-10-12"), null)
        repo.saveTransport(TransportInput(date = "2026-10-18", departPlace = "א", destination = "ב"), null)
        repo.addDocument(DocumentInput("קבלה", DocType.RECEIPT, "a.jpg", "image/jpeg", 1, expenseId = eid))
        val b = ReportBuilder(repo)
        ReportType.values().forEach { t ->
            val r = b.build(t, null, null)
            assertEquals(t.label, r.title)
            assertTrue(t.name, r.sections.isNotEmpty())
        }
        val fin = b.build(ReportType.FINANCIAL_SUMMARY, null, null).sections[0].keyValues.toMap()
        // 8000 + 500 + cancelled singer kept cost (1000-400 = 600)
        assertEquals("9,100 ₪", fin["סך ההתחייבויות"])
        assertEquals("2,700 ₪", fin["שולם בפועל"])
        assertEquals("6,400 ₪", fin["יתרה לתשלום"])
        val hist = b.build(ReportType.PAYMENT_HISTORY, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)).sections[0].table!!
        assertEquals(3, hist.rows.size)
        assertEquals(1, hist.highlight.size) // the refund
        val tasks = b.build(ReportType.TASKS, null, null)
        assertEquals(1, tasks.sections[0].table!!.rows.size)
        assertEquals(1, tasks.sections[0].table!!.highlight.size) // overdue
        val bal = b.build(ReportType.OPEN_BALANCES, null, null).sections[0].table!!
        assertEquals(listOf("צלם"), bal.rows.map { it[0] })
    }
}
