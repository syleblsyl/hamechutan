package il.hamechutan.app.core.report

import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import java.time.LocalDate

enum class ReportType(val label: String, val description: String, val usesRange: Boolean) {
    FINANCIAL_SUMMARY("דוח פיננסי כללי", "תקציב, התחייבויות, תשלומים, יתרות והתפלגות לפי קטגוריות", false),
    COMMITMENTS_VS_PAYMENTS("התחייבויות מול תשלומים", "כל התחייבות פעם אחת: מחיר שסוכם, שולם ויתרה", false),
    OPEN_BALANCES("יתרות פתוחות לפי ספק", "ספקים שנותר לשלם להם", false),
    BY_CATEGORY("הוצאות לפי קטגוריה", "פירוט ההוצאות בכל קטגוריה עם סיכומים", false),
    PAYMENT_HISTORY("היסטוריית תשלומים", "כל התשלומים וההחזרים לפי תאריך", true),
    TASKS("משימות", "משימות פתוחות ומשימות שהושלמו", true),
    CALENDAR("אירועי לוח שנה", "אירועים, משימות, הסעות ומועדי תשלום", true),
    TRANSPORTS("הסעות", "שעות, מקומות ונהגים", true),
    DOCUMENTS("מסמכים", "רשימת המסמכים והקבלות והשיוך שלהם", true)
}

data class ReportColumn(val title: String, val weight: Float, val numeric: Boolean = false)

data class ReportTable(
    val columns: List<ReportColumn>,
    val rows: List<List<String>>,
    val totals: List<String>? = null,
    /** Row indexes to highlight (e.g. overdue, cancelled). */
    val highlight: Set<Int> = emptySet(),
    val muted: Set<Int> = emptySet()
)

data class ReportSection(
    val heading: String?,
    val keyValues: List<Pair<String, String>> = emptyList(),
    val table: ReportTable? = null,
    val note: String? = null,
    val emptyText: String? = null
)

data class Report(val type: ReportType, val title: String, val subtitle: String, val generatedAt: String, val sections: List<ReportSection>)

class ReportBuilder(private val repo: Repo) {

    private fun m(a: Long) = Money.format(a, isolate = false)
    private fun d(s: String?) = s?.let { Dates.parseDate(it)?.let { x -> x.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) } } ?: ""
    private fun t(s: String?) = s ?: ""

    fun build(type: ReportType, from: LocalDate?, to: LocalDate?): Report {
        val w = repo.wedding()
        val rangeText = when {
            !type.usesRange -> "מצב נכון ל-${d(Dates.iso(repo.today()))}"
            from == null && to == null -> "כל התאריכים"
            else -> "טווח: ${from?.let { d(Dates.iso(it)) } ?: "התחלה"} – ${to?.let { d(Dates.iso(it)) } ?: "סוף"}"
        }
        val subtitle = listOfNotNull(w.title, w.date?.let { "תאריך החתונה: ${d(it)}" }, rangeText).joinToString(" · ")
        val generated = Dates.displayDateTime(repo.now()).replace("⁦", "").replace("⁩", "")
        val f = from?.let { Dates.iso(it) } ?: "0000-00-00"
        val tt = to?.let { Dates.iso(it) } ?: "9999-99-99"
        val sections = when (type) {
            ReportType.FINANCIAL_SUMMARY -> financial()
            ReportType.COMMITMENTS_VS_PAYMENTS -> commitments()
            ReportType.OPEN_BALANCES -> balances()
            ReportType.BY_CATEGORY -> byCategory()
            ReportType.PAYMENT_HISTORY -> paymentHistory(f, tt)
            ReportType.TASKS -> tasks(f, tt, from != null || to != null)
            ReportType.CALENDAR -> calendar(from ?: LocalDate.of(2000, 1, 1), to ?: LocalDate.of(2100, 12, 31))
            ReportType.TRANSPORTS -> transports(f, tt)
            ReportType.DOCUMENTS -> documents(f, tt, from != null || to != null)
        }
        return Report(type, type.label, subtitle, generated, sections)
    }

    private fun financial(): List<ReportSection> {
        val list = repo.expenses()
        val tot = repo.financeTotals(list)
        val kv = mutableListOf<Pair<String, String>>()
        kv += "תקציב יעד" to (tot.budgetAgorot?.let { m(it) } ?: "לא הוגדר")
        kv += "סך ההתחייבויות" to m(tot.committedAgorot)
        kv += "  מתוכן לספקים" to m(tot.committedSuppliersAgorot)
        kv += "  מתוכן הוצאות ללא ספק" to m(tot.committedOtherAgorot)
        kv += "שולם בפועל" to m(tot.paidAgorot)
        kv += "יתרה לתשלום" to m(tot.remainingAgorot)
        if (tot.creditAgorot > 0) kv += "יתרות זכות (תשלום יתר)" to m(tot.creditAgorot)
        tot.budgetLeftAgorot?.let { kv += (if (it >= 0) "נותר בתקציב (מעבר להתחייבויות)" else "חריגה מהתקציב") to m(kotlin.math.abs(it)) }
        kv += "התחייבויות פתוחות" to "${tot.openCommitmentsCount}"
        val cats = repo.categoryTotals(list).filter { it.count > 0 || it.budgetAgorot != null }
        val catTable = ReportTable(
            listOf(ReportColumn("קטגוריה", 2.2f), ReportColumn("תקציב", 1.2f, true), ReportColumn("התחייבויות", 1.3f, true),
                ReportColumn("שולם", 1.2f, true), ReportColumn("יתרה", 1.2f, true)),
            cats.map { listOf(it.name, it.budgetAgorot?.let { b -> m(b) } ?: "—", m(it.committedAgorot), m(it.paidAgorot), m(it.remainingAgorot)) },
            listOf("סה״כ", tot.budgetAgorot?.let { m(it) } ?: "", m(tot.committedAgorot), m(tot.paidAgorot), m(tot.remainingAgorot)),
            highlight = cats.indices.filter { i -> cats[i].budgetAgorot?.let { b -> cats[i].committedAgorot > b } == true }.toSet()
        )
        val up = repo.upcomingPayments(list, 60)
        val upTable = ReportTable(
            listOf(ReportColumn("תאריך", 1.1f), ReportColumn("הוצאה", 2.2f), ReportColumn("ספק", 1.6f), ReportColumn("סכום", 1.2f, true)),
            up.map { listOf(d(it.date), it.expense.expense.name, t(it.expense.supplierName), m(it.amountAgorot)) },
            highlight = up.indices.filter { up[it].overdue }.toSet()
        )
        return listOf(
            ReportSection("סיכום", kv, note = if (tot.overBudget) "שימו לב: סך ההתחייבויות חורג מהתקציב שהוגדר." else null),
            ReportSection("לפי קטגוריות", table = catTable, emptyText = "אין הוצאות"),
            ReportSection("תשלומים מתוכננים (60 יום, כולל באיחור)", table = upTable, emptyText = "אין תשלומים מתוכננים")
        )
    }

    private fun commitments(): List<ReportSection> {
        val list = repo.expenses().sortedWith(compareBy({ it.cancelled }, { it.supplierName ?: "￿" }, { it.expense.name }))
        val tot = repo.financeTotals(list)
        val table = ReportTable(
            listOf(ReportColumn("התחייבות", 2.0f), ReportColumn("ספק", 1.5f), ReportColumn("קטגוריה", 1.4f),
                ReportColumn("מחיר שסוכם", 1.2f, true), ReportColumn("שולם", 1.1f, true), ReportColumn("יתרה", 1.1f, true), ReportColumn("מצב", 1.1f)),
            list.map {
                listOf(it.expense.name, t(it.supplierName), t(it.categoryName),
                    if (it.cancelled) "${m(it.expense.agreedAgorot)} (בוטל)" else m(it.expense.agreedAgorot),
                    m(it.paidAgorot), m(it.remainingAgorot), it.status.label)
            },
            listOf("סה״כ", "", "", m(tot.committedAgorot), m(tot.paidAgorot), m(tot.remainingAgorot), ""),
            highlight = list.indices.filter { list[it].isOverpaid }.toSet(),
            muted = list.indices.filter { list[it].cancelled }.toSet()
        )
        return listOf(
            ReportSection(null, listOf("סך ההתחייבויות" to m(tot.committedAgorot), "שולם בפועל" to m(tot.paidAgorot), "יתרה לתשלום" to m(tot.remainingAgorot))),
            ReportSection("פירוט", table = table, emptyText = "אין התחייבויות",
                note = "בהתחייבות שבוטלה נספר בסיכום רק הסכום ששולם ולא הוחזר. ההתחייבות והתשלום מוצגים בנפרד ואינם מחוברים זה לזה.")
        )
    }

    private fun balances(): List<ReportSection> {
        val sup = repo.suppliers().filter { it.remainingAgorot > 0 }.sortedByDescending { it.remainingAgorot }
        val exps = repo.expenses()
        val table = ReportTable(
            listOf(ReportColumn("ספק", 2.0f), ReportColumn("טלפון", 1.4f), ReportColumn("התחייבות", 1.2f, true),
                ReportColumn("שולם", 1.2f, true), ReportColumn("יתרה", 1.2f, true), ReportColumn("תשלום הבא", 1.2f)),
            sup.map { s ->
                val next = exps.filter { it.expense.supplierId == s.supplier.id && !it.cancelled && it.remainingAgorot > 0 }
                    .mapNotNull { it.expense.nextPaymentDate }.minOrNull()
                listOf(s.supplier.name, t(s.supplier.phone), m(s.committedAgorot), m(s.paidAgorot), m(s.remainingAgorot), d(next))
            },
            listOf("סה״כ", "", m(sup.sumOf { it.committedAgorot }), m(sup.sumOf { it.paidAgorot }), m(sup.sumOf { it.remainingAgorot }), "")
        )
        val other = exps.filter { it.expense.supplierId == null && !it.cancelled && it.remainingAgorot > 0 }
        val otherTable = ReportTable(
            listOf(ReportColumn("הוצאה", 2.4f), ReportColumn("מחיר שסוכם", 1.2f, true), ReportColumn("שולם", 1.2f, true), ReportColumn("יתרה", 1.2f, true)),
            other.map { listOf(it.expense.name, m(it.expense.agreedAgorot), m(it.paidAgorot), m(it.remainingAgorot)) }
        )
        return listOf(
            ReportSection("ספקים עם יתרה לתשלום", table = table, emptyText = "אין יתרות פתוחות לספקים"),
            ReportSection("הוצאות ללא ספק עם יתרה", table = otherTable, emptyText = "אין")
        )
    }

    private fun byCategory(): List<ReportSection> {
        val list = repo.expenses()
        val out = mutableListOf<ReportSection>()
        repo.categoryTotals(list).filter { it.count > 0 }.forEach { c ->
            val items = list.filter { it.expense.categoryId == c.categoryId }
            out += ReportSection(
                c.name,
                listOfNotNull(c.budgetAgorot?.let { "תקציב לקטגוריה" to m(it) }),
                ReportTable(
                    listOf(ReportColumn("הוצאה", 2.2f), ReportColumn("ספק", 1.6f), ReportColumn("מחיר שסוכם", 1.2f, true),
                        ReportColumn("שולם", 1.1f, true), ReportColumn("יתרה", 1.1f, true), ReportColumn("מצב", 1.1f)),
                    items.map { listOf(it.expense.name, t(it.supplierName), m(it.effectiveCommitmentAgorot), m(it.paidAgorot), m(it.remainingAgorot), it.status.label) },
                    listOf("סה״כ", "", m(c.committedAgorot), m(c.paidAgorot), m(c.remainingAgorot), ""),
                    muted = items.indices.filter { items[it].cancelled }.toSet()
                )
            )
        }
        if (out.isEmpty()) out += ReportSection(null, emptyText = "אין הוצאות")
        return out
    }

    private fun paymentHistory(f: String, tt: String): List<ReportSection> {
        val ps = repo.allPayments().filter { it.payment.date in f..tt }.sortedWith(compareBy({ it.payment.date }, { it.payment.id }))
        val total = ps.sumOf { it.payment.signedAgorot }
        val table = ReportTable(
            listOf(ReportColumn("תאריך", 1.1f), ReportColumn("הוצאה", 1.9f), ReportColumn("ספק", 1.5f), ReportColumn("אמצעי תשלום", 1.5f),
                ReportColumn("סכום", 1.2f, true), ReportColumn("הערה", 1.6f)),
            ps.map {
                listOf(d(it.payment.date), it.expenseName, t(it.supplierName), it.methodLabel,
                    if (it.payment.kind == PaymentKind.REFUND) "החזר ${m(-it.payment.amountAgorot)}" else m(it.payment.amountAgorot), t(it.payment.note))
            },
            listOf("סה״כ נטו", "", "", "", m(total), ""),
            highlight = ps.indices.filter { ps[it].payment.kind == PaymentKind.REFUND }.toSet()
        )
        return listOf(ReportSection(null, listOf("מספר רשומות" to "${ps.size}", "סה״כ נטו" to m(total)), table, emptyText = "אין תשלומים בטווח"))
    }

    private fun tasks(f: String, tt: String, ranged: Boolean): List<ReportSection> {
        val all = repo.tasks().filter { tv -> !ranged || (tv.task.dueDate != null && tv.task.dueDate in f..tt) }
        val now = repo.nowDateTime()
        val open = all.filter { it.task.status.isActive }.sortedWith(il.hamechutan.app.core.data.TaskLogic.comparator)
        val done = all.filter { it.task.status == TaskStatus.DONE }
        val cancelled = all.filter { it.task.status == TaskStatus.CANCELLED }
        fun table(l: List<TaskView>, withOverdue: Boolean) = ReportTable(
            listOf(ReportColumn("משימה", 2.4f), ReportColumn("תאריך יעד", 1.3f), ReportColumn("עדיפות", 0.9f), ReportColumn("מצב", 0.9f), ReportColumn("ספק", 1.5f)),
            l.map { listOf(it.task.title, (d(it.task.dueDate) + (it.task.dueTime?.let { x -> " $x" } ?: "")).trim(), it.task.priority.label, it.task.status.label, t(it.supplierName)) },
            highlight = if (withOverdue) l.indices.filter { il.hamechutan.app.core.data.TaskLogic.isOverdue(l[it].task, now) }.toSet() else emptySet()
        )
        return listOf(
            ReportSection("פתוחות (${open.size})", table = table(open, true), emptyText = "אין משימות פתוחות",
                note = if (open.any { il.hamechutan.app.core.data.TaskLogic.isOverdue(it.task, now) }) "שורות מודגשות: משימות באיחור" else null),
            ReportSection("הושלמו (${done.size})", table = table(done, false), emptyText = "אין"),
            ReportSection("בוטלו (${cancelled.size})", table = table(cancelled, false), emptyText = "אין")
        )
    }

    private fun calendar(from: LocalDate, to: LocalDate): List<ReportSection> {
        val items = repo.calendarItems(from, to)
        val table = ReportTable(
            listOf(ReportColumn("תאריך", 1.1f), ReportColumn("שעה", 0.7f), ReportColumn("סוג", 1.1f), ReportColumn("כותרת", 2.4f), ReportColumn("פרטים", 2.0f)),
            items.map { listOf(d(it.date), t(it.time), it.type.label, it.title + if (it.done) " ✓" else "", t(it.subtitle)) },
            muted = items.indices.filter { items[it].done || items[it].cancelled }.toSet()
        )
        return listOf(ReportSection(null, table = table, emptyText = "אין פריטים בטווח"))
    }

    private fun transports(f: String, tt: String): List<ReportSection> {
        val l = repo.transports().filter { it.date in f..tt }
        val table = ReportTable(
            listOf(ReportColumn("תאריך", 1.1f), ReportColumn("שעה", 0.7f), ReportColumn("יציאה מ", 1.6f), ReportColumn("יעד", 1.6f),
                ReportColumn("נהג", 1.3f), ReportColumn("טלפון", 1.3f), ReportColumn("הערות", 1.5f)),
            l.map { listOf(d(it.date), t(it.departTime), t(it.departPlace), t(it.destination), t(it.driverName), t(it.driverPhone), t(it.notes)) }
        )
        return listOf(ReportSection(null, table = table, emptyText = "אין הסעות"))
    }

    private fun documents(f: String, tt: String, ranged: Boolean): List<ReportSection> {
        val l = repo.documents().filter { dv ->
            val date = dv.doc.docDate ?: Dates.iso(Dates.fromMillis(dv.doc.createdAt, repo.clock.zone).toLocalDate())
            !ranged || date in f..tt
        }
        val table = ReportTable(
            listOf(ReportColumn("מסמך", 2.0f), ReportColumn("סוג", 1.0f), ReportColumn("תאריך", 1.1f), ReportColumn("משויך ל", 3.0f), ReportColumn("קובץ", 0.9f)),
            l.map {
                listOf(it.doc.title, it.doc.type.label,
                    d(it.doc.docDate ?: Dates.iso(Dates.fromMillis(it.doc.createdAt, repo.clock.zone).toLocalDate())),
                    it.linkLabel.replace("⁦", "").replace("⁩", ""),
                    when { it.doc.isImage -> "תמונה"; it.doc.isPdf -> "PDF"; else -> "קובץ" })
            }
        )
        return listOf(ReportSection(null, table = table, emptyText = "אין מסמכים"))
    }
}
