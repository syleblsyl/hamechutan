package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import il.hamechutan.app.ui.*

class ExpensesScreen(act: MainActivity) : Screen(act) {
    override val title = "הוצאות"
    private var mode = 0
    private var status: ExpenseStatus? = null
    private var categoryId: Long? = null
    private var categoryAll = true

    override fun actions() = listOf(
        TopAction(R.drawable.ic_chart, "תקציב") { push(BudgetScreen(act)) },
        TopAction(R.drawable.ic_search, "חיפוש") { push(SearchScreen(act)) }
    )
    override fun fab() = Fab(R.drawable.ic_add, "הוצאה חדשה") { push(ExpenseEditScreen(act, null)) }

    override fun build(): View {
        val all = repo.expenses()
        val (sv, c) = ui.page()
        val t = repo.financeTotals(all)
        val sum = ui.card(16, 14, { push(BudgetScreen(act)) })
        val r = ui.row()
        fun cell(label: String, v: String, color: Int) = ui.col().apply {
            addView(ui.tv(label, TS.SMALL, p.text2)); addView(ui.tv(v, TS.BODY_STRONG, color, 1))
        }
        r.addView(cell("התחייבויות", money(t.committedAgorot), p.text), ui.lp(0, WRAP, 1f))
        r.addView(cell("שולם", money(t.paidAgorot), p.success), ui.lp(0, WRAP, 1f))
        r.addView(cell("יתרה", money(t.remainingAgorot), if (t.remainingAgorot > 0) p.warning else p.text), ui.lp(0, WRAP, 1f))
        sum.addView(r)
        t.budgetAgorot?.let { b ->
            sum.addView(ui.progressBar(t.paidAgorot.toFloat() / b, t.committedAgorot.toFloat() / b, p.success, if (t.overBudget) p.dangerSoft else p.primarySoft), ui.lp(MATCH, ui.dp(8), top = 10))
            sum.addView(ui.tv(if (t.overBudget) "חריגה מהתקציב ב-${money(-(t.budgetLeftAgorot ?: 0))}" else "מתוך תקציב ${money(b)}", TS.SMALL, if (t.overBudget) p.danger else p.text3), ui.lp(top = 4))
        }
        c.addView(sum)
        if (all.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_receipt, "עדיין אין הוצאות",
                "הוסיפו התחייבויות והוצאות: מחיר שסוכם, ספק, קטגוריה ותשלומים. ספק עם מחיר שסוכם מופיע כאן אוטומטית.", "הוספת הוצאה") { push(ExpenseEditScreen(act, null)) })
            return sv
        }
        c.addView(ui.segmented(listOf("רשימה", "קטגוריות", "ספקים", "מצב", "תאריכים"), mode) { mode = it; refresh() })
        // filters
        val chips = mutableListOf<View>()
        chips += ui.chip("כל המצבים", status == null) { status = null; refresh() }
        ExpenseStatus.values().forEach { st ->
            val n = all.count { it.status == st }
            if (n > 0) chips += ui.chip(st.label, status == st, n) { status = st; refresh() }
        }
        val catLabel = if (categoryAll) "קטגוריה: הכל" else "קטגוריה: " + (categoryId?.let { repo.category(it)?.name } ?: "ללא")
        chips += ui.chip(catLabel, !categoryAll) {
            val cats = repo.categories()
            val labels = listOf("הכל", "ללא קטגוריה") + cats.map { it.name }
            ui.options("סינון לפי קטגוריה", labels) { i ->
                when (i) { 0 -> { categoryAll = true; categoryId = null }; 1 -> { categoryAll = false; categoryId = null }; else -> { categoryAll = false; categoryId = cats[i - 2].id } }
                refresh()
            }
        }
        c.addView(ui.chipRow(chips).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }.also { it.setPadding(0, 0, 0, 0) })

        val list = all.filter { (status == null || it.status == status) && (categoryAll || it.expense.categoryId == categoryId) }
        if (list.isEmpty()) { c.addView(ui.card().apply { addView(ui.tv("אין הוצאות שמתאימות לסינון.", TS.CAPTION, p.text2)) }); return sv }
        when (mode) {
            0 -> listInCard(c, list.map { expenseRow(it) })
            1 -> grouped(c, list.groupBy { it.categoryName ?: "ללא קטגוריה" }, showSupplier = true)
            2 -> grouped(c, list.groupBy { it.supplierName ?: "ללא ספק" }, showSupplier = false)
            3 -> grouped(c, ExpenseStatus.values().associate { s -> s.label to list.filter { it.status == s } }.filter { it.value.isNotEmpty() }, true)
            4 -> byDate(c, list)
        }
        return sv
    }

    private fun grouped(c: LinearLayout, groups: Map<String, List<ExpenseSummary>>, showSupplier: Boolean) {
        groups.forEach { (name, l) ->
            val committed = l.sumOf { it.effectiveCommitmentAgorot }
            val paid = l.sumOf { it.paidAgorot }
            val rem = l.filter { !it.cancelled }.sumOf { maxOf(it.remainingAgorot, 0) }
            val head = ui.col().apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(6)) }
            head.addView(ui.tv(name, TS.SUBTITLE))
            head.addView(ui.tv("סוכם ${money(committed)} · שולם ${money(paid)} · יתרה ${money(rem)}", TS.CAPTION, p.text2))
            c.addView(head)
            listInCard(c, l.map { expenseRow(it, showSupplier) })
        }
    }

    private fun byDate(c: LinearLayout, list: List<ExpenseSummary>) {
        fun keyDate(e: ExpenseSummary) = listOfNotNull(e.expense.nextPaymentDate.takeIf { e.remainingAgorot > 0 && !e.cancelled }, e.expense.keyDate)
            .minOrNull() ?: e.lastPaymentDate ?: Dates.iso(Dates.fromMillis(e.expense.createdAt).toLocalDate())
        val sorted = list.sortedBy { keyDate(it) }
        sorted.groupBy { keyDate(it).substring(0, 7) }.forEach { (ym, l) ->
            val y = ym.substring(0, 4).toInt(); val m = ym.substring(5, 7).toInt()
            c.addView(ui.sectionHeader(Dates.monthTitle(y, m)))
            listInCard(c, l.map { expenseRow(it) })
        }
        c.addView(ui.tv("המיון לפי המועד הקרוב: תשלום מתוכנן, מועד חשוב, תשלום אחרון או תאריך יצירה.", TS.SMALL, p.text3), ui.lp(top = 4))
    }
}

class ExpenseDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title: String get() = repo.expense(id)?.expense?.name ?: "הוצאה"

    override fun actions() = listOf(
        TopAction(R.drawable.ic_edit, "עריכה") { push(ExpenseEditScreen(act, id)) },
        TopAction(R.drawable.ic_more, "פעולות נוספות") { moreActions() }
    )

    override fun build(): View {
        val e = repo.expense(id) ?: return ui.emptyState(R.drawable.ic_warning, "ההוצאה אינה קיימת", null)
        val x = e.expense
        val (sv, c) = ui.page(32)

        val head = ui.card()
        val hr = ui.row()
        val ht = ui.col()
        ht.addView(ui.tv(x.name, TS.TITLE, if (e.cancelled) p.text3 else p.text, 2))
        e.categoryName?.let { ht.addView(ui.tv(it, TS.CAPTION, p.text2)) }
        hr.addView(ht, ui.lp(0, WRAP, 1f))
        hr.addView(ui.badge(e.status.label, ui.expenseTone(e.status)))
        head.addView(hr)
        if (x.supplierId != null) {
            head.addView(ui.listRow(e.supplierName ?: "", "ספק — לחצו לכרטיס הספק", R.drawable.ic_person, chevron = true) {
                push(SupplierDetailScreen(act, x.supplierId))
            }.apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        }
        phoneActions(head, x.phone ?: e.supplierPhone)
        c.addView(head)

        if (e.cancelled) c.addView(ui.banner(
            "ההתחייבות בוטלה${x.cancelledAt?.let { " ב-" + Dates.displayDateTime(it) } ?: ""}." +
                (if (e.paidAgorot > 0) " סכום ששולם ולא הוחזר: ${money(e.paidAgorot)} — נספר כעלות בפועל." else "") +
                (x.cancelNote?.let { "\n$it" } ?: ""), Tone.NEUTRAL, R.drawable.ic_cancel))
        if (e.isOverpaid) c.addView(ui.banner("שולם יותר מהמחיר שסוכם. יתרת זכות אצל הספק: ${money(-e.remainingAgorot)}", Tone.INFO))

        val m = ui.card()
        m.addView(ui.kv("מחיר שסוכם", money(x.agreedAgorot) + if (e.cancelled) " (בוטל)" else "", strong = true))
        m.addView(ui.kv("שולם עד כה", money(e.paidAgorot), p.success))
        if (!e.cancelled) m.addView(ui.kv(if (e.remainingAgorot >= 0) "יתרה לתשלום" else "זכות", money(kotlin.math.abs(e.remainingAgorot)),
            if (e.remainingAgorot > 0) p.warning else p.text, true))
        e.lastPaymentDate?.let { m.addView(ui.kv("תשלום אחרון", Dates.display(it))) }
        if (x.agreedAgorot > 0 && !e.cancelled) m.addView(ui.progressBar(e.paidAgorot.toFloat() / x.agreedAgorot, 1f, p.success, p.primarySoft), ui.lp(MATCH, ui.dp(8), top = 8))
        if (!e.cancelled && e.remainingAgorot > 0) {
            m.addView(ui.button("רישום תשלום", BtnKind.PRIMARY, R.drawable.ic_payments) { push(PaymentEditScreen(act, id, null)) }, ui.lp(MATCH, WRAP, top = 12))
        }
        c.addView(m)

        // dates
        if (x.nextPaymentDate != null || x.keyDate != null) {
            val dc = ui.card()
            x.nextPaymentDate?.let { npd ->
                dc.addView(ui.tv("תשלום עתידי מתוכנן", TS.CAPTION_STRONG, p.text2))
                dc.addView(ui.tv(dateLine(npd) + (x.nextPaymentAgorot?.let { " · ${money(it)}" } ?: ""), TS.BODY_STRONG,
                    if (Dates.parseDate(npd)?.isBefore(repo.today()) == true && e.remainingAgorot > 0) p.danger else p.text), ui.lp(bottom = 8))
            }
            x.keyDate?.let { kd ->
                dc.addView(ui.tv(x.keyDateLabel ?: "מועד חשוב", TS.CAPTION_STRONG, p.text2))
                dc.addView(ui.tv(dateLine(kd, x.keyTime), TS.BODY_STRONG))
            }
            c.addView(dc)
        }
        if (x.nextPaymentDate != null && !e.cancelled && e.remainingAgorot > 0) {
            reminderCard(c, TargetType.EXPENSE_PAYMENT, id, "אפשר לקבל תזכורת לפני התשלום המתוכנן.")
        }
        x.notes?.let { c.addView(ui.card().apply { addView(ui.tv("הערות", TS.CAPTION_STRONG, p.text2)); addView(ui.tv(it, TS.BODY)) }) }

        val pays = repo.payments(id)
        c.addView(ui.sectionHeader("היסטוריית תשלומים (${pays.size})", if (!e.cancelled) "רישום תשלום" else null) { push(PaymentEditScreen(act, id, null)) })
        listInCard(c, pays.map { paymentRow(it) }, "עדיין לא נרשמו תשלומים.")

        val tasks = repo.tasksForExpense(id)
        c.addView(ui.sectionHeader("משימות", "הוספה") { push(TaskEditScreen(act, null, presetSupplier = x.supplierId, presetExpense = id)) })
        listInCard(c, tasks.map { taskRow(it) }, "אין משימות מקושרות.")

        val events = repo.eventsForExpense(id)
        if (events.isNotEmpty()) {
            c.addView(ui.sectionHeader("אירועים"))
            listInCard(c, events.map { ev -> calendarRow(CalendarItem(CalendarItemType.EVENT, ev.event.id, ev.event.date, ev.event.time, ev.event.title, ev.event.location,
                ev.event.status == EventStatus.DONE, ev.event.status == EventStatus.CANCELLED)) })
        }

        documentsSection(c, repo.documentsForExpense(id), DocLink(expenseId = id))
        auditList(c, repo.auditForExpense(id))
        return sv
    }

    private fun moreActions() {
        val e = repo.expense(id) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "צירוף מסמך / קבלה" to { DocumentFlows.start(act, DocLink(expenseId = id)) }
        items += "הוספת אירוע / מועד ביומן" to { push(EventEditScreen(act, null, presetSupplier = e.expense.supplierId, presetExpense = id)) }
        if (!e.cancelled) items += "ביטול ההתחייבות" to { cancelExpenseDialog(e) }
        else items += "שחזור ההתחייבות" to { ui.guard { repo.restoreExpense(id); ui.toast("ההתחייבות שוחזרה"); refresh() } }
        items += "מחיקת ההוצאה" to {
            if (e.paymentCount > 0) ui.alert("לא ניתן למחוק", "להוצאה יש ${e.paymentCount} רשומות תשלום. היסטוריה כספית לא נמחקת — אפשר לבטל את ההתחייבות במקום.")
            else {
                val l = repo.expenseLinks(id)
                val linked = listOfNotNull(l.tasks.takeIf { it > 0 }?.let { "$it משימות" }, l.documents.takeIf { it > 0 }?.let { "$it מסמכים" }, l.events.takeIf { it > 0 }?.let { "$it אירועים" })
                ui.confirm("מחיקת הוצאה", "ההוצאה \"${e.expense.name}\" תימחק לצמיתות." + if (linked.isNotEmpty()) "\nהפריטים המקושרים (${linked.joinToString(", ")}) יישמרו ללא קישור." else "",
                    "מחיקה", destructive = true) { ui.guard { repo.deleteExpense(id); ui.toast("ההוצאה נמחקה"); close() } }
            }
        }
        ui.options(e.expense.name, items.map { it.first }) { items[it].second() }
    }

    private fun cancelExpenseDialog(e: ExpenseSummary) {
        val body = ui.col()
        body.addView(ui.tv("ההתחייבות תסומן כמבוטלת. היסטוריית התשלומים נשמרת ולא תתאפשר רישום תשלומים חדשים אליה.", TS.CAPTION, p.text2), ui.lp(bottom = 12))
        val refund = if (e.paidAgorot > 0) ui.moneyField("כמה הוחזר מתוך ${Money.format(e.paidAgorot, isolate = false)} ששולמו", null,
            helper = "השאירו ריק אם לא הוחזר כסף. הסכום שלא הוחזר ייחשב כעלות בפועל.") else null
        val date = if (e.paidAgorot > 0) ui.dateField("תאריך ההחזר", Dates.iso(repo.today()), required = true) else null
        val methods = repo.paymentMethods()
        val method = if (e.paidAgorot > 0) ui.choiceField("אמצעי ההחזר", "לא צוין", { methods.map { it.id } }, null, { mid -> methods.first { it.id == mid }.name }) else null
        refund?.let { body.addView(it.root) }; date?.let { body.addView(it.picker.root) }; method?.let { body.addView(it.picker.root) }
        val note = ui.textField("סיבת הביטול (לא חובה)", null)
        body.addView(note.root)
        val d = AlertDialog.Builder(act).setTitle("ביטול ההתחייבות").setView(ui.dialogBody(body))
            .setPositiveButton("ביטול ההתחייבות", null).setNegativeButton("חזרה", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(p.danger)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var amount = 0L
                if (refund != null) when (val r = ui.readMoney(refund, false)) {
                    is Money.Parse.Error -> return@setOnClickListener
                    is Money.Parse.Ok -> amount = r.agorot
                    else -> {}
                }
                ui.guard {
                    repo.cancelExpense(id, amount, date?.iso, method?.value, note.clean)
                    d.dismiss(); ui.toast("ההתחייבות בוטלה"); refresh()
                }
            }
        }
        d.show()
    }
}

class ExpenseEditScreen(
    act: MainActivity, private val id: Long?, private val presetSupplier: Long? = null, private val presetCategory: Long? = null
) : Screen(act) {
    override val title = if (id == null) "הוצאה חדשה" else "עריכת הוצאה"
    override val keepView = true

    private lateinit var name: Ui.TextInput
    private lateinit var price: Ui.TextInput
    private lateinit var supplier: Ui.Choice<Long>
    private lateinit var category: Ui.Choice<Long>
    private lateinit var nextDate: Ui.DateInput
    private lateinit var nextAmount: Ui.TextInput
    private lateinit var keyDate: Ui.DateInput
    private lateinit var keyTime: Ui.TimeInput
    private lateinit var keyLabel: Ui.TextInput
    private lateinit var phone: Ui.TextInput
    private lateinit var notes: Ui.TextInput
    private var paidNow = false
    private var paidDate: Ui.DateInput? = null
    private var paidMethod: Ui.Choice<Long>? = null
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val e = id?.let { repo.expense(it)?.expense }
        val (sv, c) = ui.page(32)
        val main = ui.card(16, 16)
        val presetSupName = presetSupplier?.let { repo.supplier(it)?.name }
        name = ui.textField("שם ההוצאה", e?.name ?: presetSupName, "למשל: צילום, שמלה, אולם", required = true)
        price = ui.moneyField("מחיר שסוכם (₪)", e?.agreedAgorot, required = true)
        supplier = supplierChoice("ספק", e?.supplierId ?: presetSupplier) { sid ->
            if (name.clean == null && sid != null) repo.supplier(sid)?.let { name.set(it.name) }
            if (category.value == null && sid != null) repo.supplier(sid)?.categoryId?.let { category.set(it) }
        }
        category = categoryChoice(e?.categoryId ?: presetCategory ?: presetSupplier?.let { repo.supplier(it)?.categoryId })
        main.addView(name.root); main.addView(price.root); main.addView(supplier.picker.root); main.addView(category.picker.root)
        c.addView(main)

        if (id == null) {
            val paidCard = ui.listCard()
            val box = ui.col(16, 0)
            val methods = repo.paymentMethods()
            paidDate = ui.dateField("תאריך התשלום", Dates.iso(repo.today()), required = true)
            paidMethod = ui.choiceField("אמצעי תשלום", "לא צוין", { methods.map { it.id } }, null, { mid -> methods.first { it.id == mid }.name })
            box.addView(paidDate!!.picker.root); box.addView(paidMethod!!.picker.root)
            box.visibility = View.GONE
            val (sw, _) = ui.switchRow("שולם במלואו כבר עכשיו", "לרכישה קטנה ששולמה במקום — יירשם גם התשלום", false) { v -> paidNow = v; box.visibility = if (v) View.VISIBLE else View.GONE }
            paidCard.addView(sw); paidCard.addView(box)
            c.addView(paidCard)
        }

        // secondary fields
        val moreBox = ui.col()
        val more = ui.card(16, 16)
        nextDate = ui.dateField("תאריך תשלום עתידי", e?.nextPaymentDate, helper = "יופיע בתשלומים הקרובים ובלוח השנה; אפשר להגדיר לו תזכורת")
        nextAmount = ui.moneyField("סכום התשלום העתידי (₪)", e?.nextPaymentAgorot, helper = "ריק = כל היתרה")
        keyLabel = ui.textField("מועד חשוב — תיאור", e?.keyDateLabel, "למשל: אספקה, מדידה, איסוף")
        keyDate = ui.dateField("תאריך המועד", e?.keyDate)
        keyTime = ui.timeField("שעת המועד", e?.keyTime)
        phone = ui.phoneField("טלפון (אם שונה מהספק)", e?.phone)
        notes = ui.textField("הערות", e?.notes, lines = 3)
        listOf(nextDate.picker.root, nextAmount.root, keyLabel.root, keyDate.picker.root, keyTime.picker.root, phone.root, notes.root).forEach { more.addView(it) }
        moreBox.addView(more)
        val hasExtra = e != null && listOf(e.nextPaymentDate, e.keyDate, e.phone, e.notes, e.keyDateLabel).any { it != null }
        moreBox.visibility = if (hasExtra) View.VISIBLE else View.GONE
        if (!hasExtra) {
            val toggle = ui.button("פרטים נוספים: תשלום עתידי, מועד, טלפון והערות", BtnKind.TEXT, R.drawable.ic_expand)
            { }
            toggle.setOnClickListener { moreBox.visibility = View.VISIBLE; toggle.visibility = View.GONE }
            c.addView(toggle, ui.lp(MATCH, WRAP, bottom = 8))
        }
        c.addView(moreBox)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        initial = snapshot()
        return sv
    }

    private fun snapshot() = listOf(name.value, price.value, supplier.value, category.value, nextDate.iso, nextAmount.value, keyDate.iso, keyTime.iso,
        keyLabel.value, phone.value, notes.value).joinToString("|")
    override fun isDirty() = ::name.isInitialized && snapshot() != initial

    private fun save() {
        name.error(null)
        if (name.clean == null) { name.error("יש למלא שם להוצאה"); return }
        val pr = ui.readMoney(price, true) as? Money.Parse.Ok ?: return
        val na = when (val r = ui.readMoney(nextAmount, false)) { is Money.Parse.Ok -> r.agorot.takeIf { it > 0 }; is Money.Parse.Error -> return; else -> null }
        if (keyTime.iso != null && keyDate.iso == null) { keyDate.picker.error("יש לבחור תאריך למועד"); return }
        val input = ExpenseInput(name.value, category.value, supplier.value, pr.agorot, nextDate.iso, na, keyDate.iso, keyTime.iso, keyLabel.clean, phone.clean, notes.clean)
        val check = repo.validateExpense(input, id)
        if (!check.ok) { ui.alert("לא ניתן לשמור", check.errors.joinToString("\n")); return }
        val doSave = {
            ui.guard {
                val newId = if (id == null && paidNow) repo.addPaidExpense(input, paidDate!!.iso!!, paidMethod?.value, null) else repo.saveExpense(input, id)
                ui.toast("ההוצאה נשמרה")
                initial = snapshot()
                if (id == null) act.replaceTop(ExpenseDetailScreen(act, newId)) else close()
            }
        }
        if (check.warnings.isNotEmpty()) ui.confirm("לתשומת לבך", check.warnings.joinToString("\n\n"), "שמירה") { doSave() } else doSave()
    }
}

class BudgetScreen(act: MainActivity) : Screen(act) {
    override val title = "תקציב"

    override fun build(): View {
        val list = repo.expenses()
        val t = repo.financeTotals(list)
        val (sv, c) = ui.page(32)
        val bc = ui.card(16, 16)
        val budget = ui.moneyField("תקציב יעד לחתונה (₪)", t.budgetAgorot, helper = "הסכום שהוקצה לחתונה. ההשוואה היא מול סך ההתחייבויות.")
        bc.addView(budget.root)
        val br = ui.row()
        br.addView(ui.button("שמירת התקציב", BtnKind.PRIMARY, small = true) {
            when (val r = ui.readMoney(budget, false)) {
                is Money.Parse.Ok -> ui.guard { repo.setBudget(r.agorot); ui.toast("התקציב נשמר"); refresh() }
                is Money.Parse.Empty -> ui.guard { repo.setBudget(null); ui.toast("התקציב הוסר"); refresh() }
                else -> {}
            }
        })
        bc.addView(br)
        c.addView(bc)

        if (t.overBudget) c.addView(ui.banner("סך ההתחייבויות (${money(t.committedAgorot)}) חורג מהתקציב ב-${money(-(t.budgetLeftAgorot ?: 0))}", Tone.DANGER, R.drawable.ic_warning))
        val sc = ui.card()
        sc.addView(ui.tv("סיכום", TS.SUBTITLE), ui.lp(bottom = 4))
        sc.addView(ui.kv("תקציב שהוגדר", t.budgetAgorot?.let { money(it) } ?: "לא הוגדר"))
        sc.addView(ui.kv("סך ההתחייבויות", money(t.committedAgorot), strong = true))
        sc.addView(ui.kv("  לספקים", money(t.committedSuppliersAgorot), p.text2))
        sc.addView(ui.kv("  הוצאות ללא ספק", money(t.committedOtherAgorot), p.text2))
        sc.addView(ui.kv("שולם בפועל", money(t.paidAgorot), p.success, true))
        sc.addView(ui.kv("יתרה לתשלום (טרם נפרע)", money(t.remainingAgorot), if (t.remainingAgorot > 0) p.warning else p.text, true))
        if (t.creditAgorot > 0) sc.addView(ui.kv("זכות אצל ספקים", money(t.creditAgorot), p.info))
        t.budgetLeftAgorot?.let { sc.addView(ui.kv(if (it >= 0) "נותר בתקציב לתכנון" else "חריגה", money(kotlin.math.abs(it)), if (it >= 0) p.text else p.danger, true)) }
        sc.addView(ui.tv("ההתחייבויות והתשלומים מוצגים בנפרד: תשלום אינו מתווסף להתחייבות אלא מקטין את היתרה.", TS.SMALL, p.text3), ui.lp(top = 6))
        c.addView(sc)

        c.addView(ui.sectionHeader("לפי קטגוריות", "ניהול קטגוריות") { push(CategoriesScreen(act)) })
        val cats = repo.categoryTotals(list).filter { it.count > 0 || it.budgetAgorot != null }
        if (cats.isEmpty()) c.addView(ui.card().apply { addView(ui.tv("עדיין אין הוצאות.", TS.CAPTION, p.text2)) })
        val total = maxOf(t.committedAgorot, 1)
        cats.forEach { ct ->
            val card = ui.card(16, 12, { if (ct.categoryId != null) categoryBudget(ct) })
            val r = ui.row()
            r.addView(ui.tv(ct.name, TS.BODY_STRONG), ui.lp(0, WRAP, 1f))
            r.addView(ui.tv(money(ct.committedAgorot), TS.BODY_STRONG))
            card.addView(r)
            val over = ct.budgetAgorot != null && ct.committedAgorot > ct.budgetAgorot
            val base = (ct.budgetAgorot ?: total).coerceAtLeast(1)
            card.addView(ui.progressBar(ct.paidAgorot.toFloat() / base, ct.committedAgorot.toFloat() / base, p.success, if (over) p.dangerSoft else p.primarySoft), ui.lp(MATCH, ui.dp(8), top = 8))
            card.addView(ui.tv(listOfNotNull("שולם ${money(ct.paidAgorot)}", "יתרה ${money(ct.remainingAgorot)}",
                ct.budgetAgorot?.let { "תקציב ${money(it)}" }, if (over) "חריגה!" else null).joinToString(" · "), TS.CAPTION, if (over) p.danger else p.text2), ui.lp(top = 6))
            c.addView(card)
        }
        c.addView(ui.tv("לחיצה על קטגוריה מאפשרת להגדיר לה תקציב משלה.", TS.SMALL, p.text3))

        val up = repo.upcomingPayments(list, 60)
        if (up.isNotEmpty()) {
            c.addView(ui.sectionHeader("תשלומים מתוכננים (60 יום)"))
            listInCard(c, up.map { u ->
                ui.listRow(u.expense.expense.name, Dates.display(u.date) + (u.expense.supplierName?.let { " · $it" } ?: ""), R.drawable.ic_payments,
                    if (u.overdue) Tone.DANGER else Tone.GOLD, ui.trailingAmount(money(u.amountAgorot), if (u.overdue) p.danger else p.text, if (u.overdue) "באיחור" else null)) {
                    push(ExpenseDetailScreen(act, u.expense.id))
                }
            })
        }
        return sv
    }

    private fun categoryBudget(ct: CategoryTotals) {
        val f = ui.moneyField("תקציב לקטגוריה \"${ct.name}\" (₪)", ct.budgetAgorot, helper = "ריק = ללא תקציב נפרד")
        val d = AlertDialog.Builder(act).setTitle("תקציב לקטגוריה").setView(ui.dialogBody(f.root))
            .setPositiveButton("שמירה", null).setNegativeButton("ביטול", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = when (val r = ui.readMoney(f, false)) { is Money.Parse.Ok -> r.agorot; is Money.Parse.Error -> return@setOnClickListener; else -> null }
                ui.guard { repo.updateCategory(ct.categoryId!!, repo.category(ct.categoryId)!!.name, v); d.dismiss(); refresh() }
            }
        }
        d.show()
    }
}

class PaymentsHistoryScreen(act: MainActivity) : Screen(act) {
    override val title = "כל התשלומים"

    override fun build(): View {
        val all = repo.allPayments()
        val (sv, c) = ui.page(32)
        if (all.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_payments, "עדיין לא נרשמו תשלומים", "תשלומים נרשמים מתוך כרטיס ספק או הוצאה."))
            return sv
        }
        c.addView(ui.card().apply {
            addView(ui.kv("סה״כ שולם (נטו)", money(all.sumOf { it.payment.signedAgorot }), p.success, true))
            addView(ui.kv("מספר רשומות", all.size.toString()))
        })
        all.groupBy { it.payment.date.substring(0, 7) }.forEach { (ym, l) ->
            c.addView(ui.sectionHeader(Dates.monthTitle(ym.substring(0, 4).toInt(), ym.substring(5, 7).toInt()) + " · " + money(l.sumOf { it.payment.signedAgorot })))
            listInCard(c, l.map { paymentRow(it, showExpense = true) })
        }
        return sv
    }
}
