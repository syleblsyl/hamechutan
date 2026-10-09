package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import il.hamechutan.app.ui.*

class SuppliersScreen(act: MainActivity) : Screen(act) {
    override val title = "ספקים"
    private var filter: SupplierStatus? = null
    private var showCancelled = false

    override fun actions() = listOf(TopAction(R.drawable.ic_search, "חיפוש") { push(SearchScreen(act)) })
    override fun fab() = Fab(R.drawable.ic_add, "ספק חדש") { push(SupplierEditScreen(act, null)) }

    override fun build(): View {
        val all = repo.suppliers()
        val (sv, c) = ui.page()
        if (all.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_suppliers, "עדיין אין ספקים",
                "הוסיפו כאן את הספקים — אולם, צלם, תזמורת, חנות בגדים — עם המחיר שסוכם, הטלפון ושעות ההגעה.",
                "הוספת ספק") { push(SupplierEditScreen(act, null)) })
            return sv
        }
        val active = all.filter { !it.supplier.cancelled }
        val committed = active.sumOf { it.committedAgorot }
        val remaining = active.sumOf { it.remainingAgorot }
        c.addView(ui.card().apply {
            addView(ui.tv("${active.size} ספקים פעילים", TS.BODY_STRONG))
            addView(ui.tv("התחייבויות ${money(committed)} · יתרה לתשלום ${money(remaining)}", TS.CAPTION, p.text2), ui.lp(top = 2))
        })
        val counts = all.groupingBy { it.status }.eachCount()
        val chips = mutableListOf<View>(ui.chip("הכל", filter == null, all.size) { filter = null; refresh() })
        SupplierStatus.values().forEach { st ->
            val n = counts[st] ?: 0
            if (n > 0) chips += ui.chip(st.label, filter == st, n) { filter = st; refresh() }
        }
        c.addView(ui.chipRow(chips).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }.also { it.setPadding(0, 0, 0, 0) })
        val list = all.filter { filter == null || it.status == filter }
        listInCard(c, list.map { supplierRow(it) }, "אין ספקים במצב זה")
        return sv
    }
}

class SupplierDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title: String get() = repo.supplier(id)?.name ?: "ספק"

    override fun actions() = listOf(
        TopAction(R.drawable.ic_edit, "עריכה") { push(SupplierEditScreen(act, id)) },
        TopAction(R.drawable.ic_more, "פעולות נוספות") { moreActions() }
    )

    override fun build(): View {
        val s = repo.supplierSummary(id) ?: return ui.emptyState(R.drawable.ic_warning, "הספק אינו קיים", null)
        val sp = s.supplier
        val (sv, c) = ui.page(32)

        // contact card
        val head = ui.card()
        val r = ui.row()
        r.addView(ui.iconBubble(R.drawable.ic_person, p.primary, p.primarySoft, 48), ui.lp(ui.dp(48), ui.dp(48), end = 12))
        val t = ui.col()
        t.addView(ui.tv(sp.name, TS.TITLE, if (sp.cancelled) p.text3 else p.text, 2))
        listOfNotNull(sp.service, s.categoryName).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { t.addView(ui.tv(it, TS.CAPTION, p.text2)) }
        r.addView(t, ui.lp(0, WRAP, 1f))
        r.addView(ui.badge(s.status.label, ui.supplierTone(s.status)))
        head.addView(r)
        phoneActions(head, sp.phone)
        sp.contactName?.let { head.addView(ui.kv("איש קשר", it)) }
        sp.arrivalInfo?.let { head.addView(ui.kv("שעות הגעה", it)) }
        sp.notes?.let { head.addView(ui.tv(it, TS.CAPTION, p.text2), ui.lp(top = 6)) }
        c.addView(head)
        if (sp.cancelled) c.addView(ui.banner("ההתקשרות עם הספק בוטלה. היסטוריית התשלומים נשמרת.", Tone.NEUTRAL, R.drawable.ic_cancel, "שחזור הספק") {
            ui.guard { repo.restoreSupplier(id); refresh() }
        })

        // money
        val exps = repo.expensesForSupplier(id)
        val active = exps.filter { !it.cancelled }
        val moneyCard = ui.card()
        moneyCard.addView(ui.tv("התחייבות ותשלומים", TS.SUBTITLE))
        if (exps.isEmpty()) {
            moneyCard.addView(ui.tv("לא נרשמה התחייבות כספית לספק זה.", TS.CAPTION, p.text2), ui.lp(top = 6))
            if (!sp.cancelled) moneyCard.addView(ui.button("הוספת מחיר שסוכם", BtnKind.TONAL, R.drawable.ic_add, small = true) {
                push(ExpenseEditScreen(act, null, presetSupplier = id))
            }, ui.lp(WRAP, WRAP, top = 10))
        } else {
            moneyCard.addView(ui.kv("מחיר שסוכם", money(s.committedAgorot), strong = true))
            moneyCard.addView(ui.kv("שולם עד כה", money(s.paidAgorot), p.success))
            val credit = active.sumOf { maxOf(-it.remainingAgorot, 0) }
            moneyCard.addView(ui.kv("יתרה לתשלום", money(s.remainingAgorot), if (s.remainingAgorot > 0) p.warning else p.text, true))
            if (credit > 0) moneyCard.addView(ui.kv("זכות אצל הספק", money(credit), p.info))
            if (s.committedAgorot > 0) moneyCard.addView(ui.progressBar(s.paidAgorot.toFloat() / s.committedAgorot, 1f, p.success, p.primarySoft), ui.lp(MATCH, ui.dp(8), top = 8))
            if (active.isNotEmpty()) {
                val btns = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 12) }
                if (active.any { it.remainingAgorot > 0 }) {
                    btns.addView(ui.button("רישום תשלום", BtnKind.PRIMARY, R.drawable.ic_payments, small = true) { payment(active) }, ui.lp(0, WRAP, 1f))
                    btns.addView(ui.hspace(8))
                }
                btns.addView(ui.button("התחייבות נוספת", BtnKind.SECONDARY, R.drawable.ic_add, small = true) {
                    push(ExpenseEditScreen(act, null, presetSupplier = id))
                }, ui.lp(0, WRAP, 1f))
                moneyCard.addView(btns)
            }
        }
        c.addView(moneyCard)

        if (exps.size > 1 || exps.any { it.expense.name != sp.name }) {
            c.addView(ui.sectionHeader("התחייבויות (${exps.size})"))
            listInCard(c, exps.map { expenseRow(it, showSupplier = false) })
        } else if (exps.size == 1) {
            c.addView(ui.button("פרטי ההתחייבות, מועדים ותזכורת תשלום", BtnKind.TEXT, R.drawable.ic_receipt) { push(ExpenseDetailScreen(act, exps[0].id)) },
                ui.lp(MATCH, WRAP, bottom = 8))
        }

        val pays = repo.paymentsForSupplier(id)
        if (pays.isNotEmpty()) {
            c.addView(ui.sectionHeader("היסטוריית תשלומים (${pays.size})"))
            listInCard(c, pays.map { paymentRow(it, showExpense = exps.size > 1) })
        }

        // dates: events + expense key dates
        val events = repo.eventsForSupplier(id)
        c.addView(ui.sectionHeader("פגישות ומועדים", "הוספה") { push(EventEditScreen(act, null, presetSupplier = id)) })
        val dateRows = mutableListOf<View>()
        events.forEach { ev ->
            dateRows += calendarRow(CalendarItem(CalendarItemType.EVENT, ev.event.id, ev.event.date, ev.event.time, ev.event.title, ev.event.location,
                ev.event.status == EventStatus.DONE, ev.event.status == EventStatus.CANCELLED))
        }
        exps.filter { it.expense.keyDate != null && !it.cancelled }.forEach { e ->
            dateRows += calendarRow(CalendarItem(CalendarItemType.KEY_DATE, e.id, e.expense.keyDate!!, e.expense.keyTime, e.expense.keyDateLabel ?: e.expense.name, null, false))
        }
        listInCard(c, dateRows, "אין פגישות או מועדים. אפשר להוסיף פגישה, מועד אספקה או איסוף.")

        val tasks = repo.tasksForSupplier(id)
        c.addView(ui.sectionHeader("משימות", "הוספה") { push(TaskEditScreen(act, null, presetSupplier = id)) })
        listInCard(c, tasks.map { taskRow(it, showSupplier = false) }, "אין משימות מקושרות לספק.")

        documentsSection(c, repo.documentsForSupplier(id), DocLink(supplierId = id))
        return sv
    }

    private fun payment(active: List<ExpenseSummary>) {
        val open = active.filter { it.remainingAgorot > 0 }
        if (open.size == 1) push(PaymentEditScreen(act, open[0].id, null))
        else ui.options("תשלום עבור…", open.map { "${it.expense.name} — יתרה ${Money.format(it.remainingAgorot, isolate = false)}" }) { i ->
            push(PaymentEditScreen(act, open[i].id, null))
        }
    }

    private fun moreActions() {
        val s = repo.supplier(id) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "הוספת משימה" to { push(TaskEditScreen(act, null, presetSupplier = id)) }
        items += "צירוף מסמך / צילום" to { DocumentFlows.start(act, DocLink(supplierId = id)) }
        if (!s.cancelled) items += "ביטול ההתקשרות עם הספק" to { cancelSupplierDialog(id) { refresh() } }
        else items += "שחזור הספק" to { ui.guard { repo.restoreSupplier(id); refresh() } }
        items += "מחיקת הספק" to { deleteSupplier() }
        ui.options(s.name, items.map { it.first }) { items[it].second() }
    }

    private fun deleteSupplier() {
        val l = repo.supplierLinks(id)
        if (l.expenses > 0) {
            ui.alert("לא ניתן למחוק", "לספק יש ${l.expenses} התחייבויות כספיות${if (l.payments > 0) " ו-${l.payments} תשלומים" else ""}. כדי לשמור על היסטוריה כספית תקינה לא מוחקים ספק כזה. אפשר לבטל את ההתקשרות איתו במקום.")
            return
        }
        val linked = listOfNotNull(
            l.tasks.takeIf { it > 0 }?.let { "$it משימות" }, l.events.takeIf { it > 0 }?.let { "$it אירועים" },
            l.documents.takeIf { it > 0 }?.let { "$it מסמכים" }, l.transports.takeIf { it > 0 }?.let { "$it הסעות" }
        )
        val msg = "הספק יימחק לצמיתות." + if (linked.isNotEmpty()) "\n\nהפריטים המקושרים (${linked.joinToString(", ")}) יישמרו, אך ללא קישור לספק." else ""
        ui.confirm("מחיקת ספק", msg, "מחיקה", destructive = true) { ui.guard { repo.deleteSupplier(id); ui.toast("הספק נמחק"); close() } }
    }
}

fun Screen.paymentRow(pv: PaymentView, showExpense: Boolean = false): View {
    val pmt = pv.payment
    val refund = pmt.kind == PaymentKind.REFUND
    val sub = listOfNotNull(Dates.display(pmt.date), pv.methodLabel, if (showExpense) pv.expenseName else null).joinToString(" · ")
    return ui.listRow(
        if (refund) "החזר ${money(pmt.amountAgorot)}" else money(pmt.amountAgorot), sub,
        if (refund) R.drawable.ic_undo else R.drawable.ic_payments, if (refund) Tone.WARNING else Tone.SUCCESS,
        third = pmt.note, chevron = true
    ) { push(PaymentDetailScreen(act, pmt.id)) }
}

/** Cancels a supplier engagement, asking how money already paid was handled (refund amount per commitment). */
fun Screen.cancelSupplierDialog(supplierId: Long, done: () -> Unit) {
    val exps = repo.expensesForSupplier(supplierId).filter { !it.cancelled }
    val paid = exps.filter { it.paidAgorot > 0 }
    val body = ui.col()
    body.addView(ui.tv("ההתקשרות תסומן כמבוטלת וכל ההתחייבויות הפעילות שלה (${exps.size}) יבוטלו. היסטוריית התשלומים נשמרת.", TS.CAPTION, p.text2), ui.lp(bottom = 12))
    val fields = paid.associate { e ->
        e.id to ui.moneyField("כמה הוחזר מתוך ${Money.format(e.paidAgorot, isolate = false)} ששולמו (${e.expense.name})", null,
            helper = "השאירו ריק אם לא הוחזר כסף. הסכום שלא הוחזר ייחשב כעלות בפועל.")
    }
    fields.values.forEach { body.addView(it.root) }
    val date = if (paid.isNotEmpty()) ui.dateField("תאריך ההחזר", Dates.iso(repo.today()), required = true) else null
    val methods = repo.paymentMethods()
    val method = if (paid.isNotEmpty()) ui.choiceField("אמצעי ההחזר", "לא צוין", { methods.map { it.id } }, null, { id -> methods.first { it.id == id }.name }) else null
    date?.let { body.addView(it.picker.root) }
    method?.let { body.addView(it.picker.root) }
    val note = ui.textField("הערה (סיבת הביטול)", null)
    body.addView(note.root)
    val d = AlertDialog.Builder(act).setTitle("ביטול ההתקשרות").setView(ui.dialogBody(body))
        .setPositiveButton("ביטול ההתקשרות", null).setNegativeButton("חזרה", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(p.danger)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val refunds = HashMap<Long, Repo.RefundSpec>()
            for ((eid, f) in fields) {
                when (val r = ui.readMoney(f, false)) {
                    is Money.Parse.Error -> return@setOnClickListener
                    is Money.Parse.Ok -> if (r.agorot > 0) refunds[eid] = Repo.RefundSpec(r.agorot, date?.iso, method?.value)
                    else -> {}
                }
            }
            ui.guard {
                repo.cancelSupplier(supplierId, refunds, note.clean)
                d.dismiss(); ui.toast("ההתקשרות בוטלה"); done()
            }
        }
    }
    d.show()
}

class SupplierEditScreen(act: MainActivity, private val id: Long?) : Screen(act) {
    override val title = if (id == null) "ספק חדש" else "עריכת ספק"
    override val keepView = true

    private lateinit var name: Ui.TextInput
    private lateinit var service: Ui.TextInput
    private lateinit var category: Ui.Choice<Long>
    private lateinit var phone: Ui.TextInput
    private lateinit var contact: Ui.TextInput
    private lateinit var arrival: Ui.TextInput
    private var price: Ui.TextInput? = null
    private lateinit var notes: Ui.TextInput
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val s = id?.let { repo.supplier(it) }
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        name = ui.textField("שם הספק", s?.name, "למשל: צילום כהן", required = true)
        service = ui.textField("תחום השירות", s?.service, "צלם, זמר, אולם, חייט…")
        category = categoryChoice(s?.categoryId)
        phone = ui.phoneField("טלפון", s?.phone)
        contact = ui.textField("איש קשר", s?.contactName, "שם איש הקשר")
        card.addView(name.root); card.addView(service.root); card.addView(category.picker.root)
        card.addView(phone.root); card.addView(contact.root)
        c.addView(card)

        val priceCard = ui.card(16, 16)
        priceCard.addView(ui.tv("מחיר שסוכם", TS.SUBTITLE), ui.lp(bottom = 8))
        val active = id?.let { repo.expensesForSupplier(it).filter { e -> !e.cancelled } } ?: emptyList()
        when {
            id == null || active.isEmpty() -> {
                price = ui.moneyField("מחיר שסוכם (₪)", null, helper = "אם הוזן מחיר תיווצר התחייבות כספית אחת לספק, והתשלומים יירשמו אליה. אפשר להשאיר ריק ולהוסיף בהמשך.")
                priceCard.addView(price!!.root)
            }
            active.size == 1 -> {
                price = ui.moneyField("מחיר שסוכם (₪)", active[0].expense.agreedAgorot,
                    helper = "שולם עד כה ${Money.format(active[0].paidAgorot)}. שינוי המחיר יעדכן את ההתחייבות הקיימת ויירשם ביומן השינויים.")
                priceCard.addView(price!!.root)
            }
            else -> priceCard.addView(ui.tv("לספק ${active.size} התחייבויות. את המחירים עורכים מתוך כל התחייבות בנפרד.", TS.CAPTION, p.text2))
        }
        c.addView(priceCard)

        val more = ui.card(16, 16)
        arrival = ui.textField("שעות הגעה", s?.arrivalInfo, "למשל: הגעה לאולם 18:30, חופה 19:30")
        notes = ui.textField("הערות", s?.notes, lines = 3)
        more.addView(arrival.root); more.addView(notes.root)
        c.addView(more)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        initial = snapshot()
        return sv
    }

    private fun snapshot() = listOf(name.value, service.value, category.value, phone.value, contact.value, arrival.value, notes.value, price?.value).joinToString("|")
    override fun isDirty() = ::name.isInitialized && snapshot() != initial

    private fun save() {
        name.error(null)
        if (name.clean == null) { name.error("יש למלא שם ספק"); return }
        var priceAgorot: Long? = null
        price?.let { f ->
            when (val r = ui.readMoney(f, false)) {
                is Money.Parse.Error -> return
                is Money.Parse.Ok -> priceAgorot = r.agorot.takeIf { it > 0 }
                else -> {}
            }
        }
        val input = SupplierInput(name.value, service.clean, category.value, phone.clean, contact.clean, arrival.clean, notes.clean, priceAgorot)
        val check = repo.validateSupplier(input, id)
        if (!check.ok) { ui.alert("לא ניתן לשמור", check.errors.joinToString("\n")); return }
        val doSave = {
            ui.guard {
                val newId = repo.saveSupplier(input, id)
                ui.toast("הספק נשמר")
                initial = snapshot()
                if (id == null) act.replaceTop(SupplierDetailScreen(act, newId)) else close()
            }
        }
        if (check.warnings.isNotEmpty()) ui.confirm("לתשומת לבך", check.warnings.joinToString("\n\n"), "שמירה") { doSave() } else doSave()
    }
}
