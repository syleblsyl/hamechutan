package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.view.View
import il.hamechutan.app.R
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import il.hamechutan.app.ui.*

class PaymentEditScreen(act: MainActivity, private val expenseId: Long, private val paymentId: Long?) : Screen(act) {
    override val title = if (paymentId == null) "רישום תשלום" else "עריכת תשלום"
    override val keepView = true

    private lateinit var amount: Ui.TextInput
    private lateinit var date: Ui.DateInput
    private lateinit var note: Ui.TextInput
    private lateinit var otherText: Ui.TextInput
    private var methodId: Long? = null
    private var saveMethod = false
    private var kind = PaymentKind.PAYMENT
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val e = repo.expense(expenseId) ?: return ui.emptyState(R.drawable.ic_warning, "ההוצאה אינה קיימת", null)
        val existing = paymentId?.let { repo.payment(it)?.payment }
        existing?.let { methodId = it.methodId; kind = it.kind }
        val (sv, c) = ui.page(32)

        val ctxCard = ui.card(16, 12, color = p.surfaceAlt, stroke = null)
        ctxCard.addView(ui.tv(e.expense.name + (e.supplierName?.takeIf { it != e.expense.name }?.let { " · $it" } ?: ""), TS.BODY_STRONG))
        ctxCard.addView(ui.tv("סוכם ${money(e.expense.agreedAgorot)} · שולם ${money(e.paidAgorot)} · יתרה ${money(maxOf(e.remainingAgorot, 0))}", TS.CAPTION, p.text2), ui.lp(top = 2))
        c.addView(ctxCard)

        val main = ui.card(16, 16)
        if (existing?.kind == PaymentKind.REFUND) main.addView(ui.banner("זוהי רשומת החזר (כסף שחזר מהספק).", Tone.WARNING, R.drawable.ic_undo))
        amount = ui.moneyField(if (kind == PaymentKind.REFUND) "סכום ההחזר (₪)" else "סכום התשלום (₪)", existing?.amountAgorot, required = true)
        main.addView(amount.root)
        if (existing == null && e.remainingAgorot > 0) {
            val quick = mutableListOf<View>()
            quick += ui.chip("כל היתרה · ${Money.format(e.remainingAgorot, isolate = false)}", false) { amount.set(Money.inputValue(e.remainingAgorot)) }
            e.expense.nextPaymentAgorot?.takeIf { it < e.remainingAgorot }?.let { np ->
                quick += ui.chip("התשלום המתוכנן · ${Money.format(np, isolate = false)}", false) { amount.set(Money.inputValue(np)) }
            }
            main.addView(ui.chipRow(quick).also { it.setPadding(0, 0, 0, 0); (it.getChildAt(0) as android.widget.LinearLayout).setPadding(0, 0, 0, 0) }, ui.lp(MATCH, WRAP, top = -6, bottom = 14))
        }
        date = ui.dateField("תאריך התשלום", existing?.date ?: Dates.iso(repo.today()), required = true)
        main.addView(date.picker.root)

        main.addView(ui.fieldLabel("אמצעי תשלום"))
        val methods = repo.paymentMethods().toMutableList()
        existing?.methodId?.let { mid -> if (methods.none { it.id == mid }) repo.paymentMethod(mid)?.let { methods += it } }
        val chipsBox = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL }
        otherText = ui.textField("פירוט אמצעי התשלום", existing?.methodNote, "למשל: פייבוקס, שובר, צ׳ק דחוי")
        val saveBox = ui.switchRow("לשמור כאמצעי תשלום קבוע", "יופיע ברשימה בפעם הבאה", false) { saveMethod = it }.first
        fun renderMethods() {
            chipsBox.removeAllViews()
            methods.chunked(3).forEach { rowM ->
                val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 8) }
                rowM.forEach { m -> r.addView(ui.chip(m.name, methodId == m.id) { methodId = if (methodId == m.id) null else m.id; renderMethods() }) }
                chipsBox.addView(r)
            }
            val isOther = methods.firstOrNull { it.id == methodId }?.isOther == true
            otherText.root.visibility = if (isOther || (methodId == null && !otherText.clean.isNullOrEmpty())) View.VISIBLE else View.GONE
            saveBox.visibility = if (isOther) View.VISIBLE else View.GONE
        }
        renderMethods()
        main.addView(chipsBox, ui.lp(MATCH, WRAP, bottom = 6))
        main.addView(otherText.root)
        main.addView(saveBox)
        note = ui.textField("הערה", existing?.note, "למשל: מקדמה, תשלום שני, מספר צ׳ק")
        main.addView(note.root)
        c.addView(main)

        c.addView(ui.button(if (paymentId == null) "שמירת התשלום" else "שמירת השינויים", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        if (paymentId != null) c.addView(ui.button("מחיקת רשומת התשלום", BtnKind.DANGER_TEXT, R.drawable.ic_delete) { delete() }, ui.lp(MATCH, WRAP, top = 8))
        else c.addView(ui.tv("אחרי השמירה אפשר לצלם או לצרף את הקבלה.", TS.SMALL, p.text3).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(top = 10))
        initial = snapshot()
        return sv
    }

    private fun snapshot() = listOf(amount.value, date.iso, methodId, otherText.value, note.value).joinToString("|")
    override fun isDirty() = ::amount.isInitialized && snapshot() != initial

    private fun save() {
        val a = ui.readMoney(amount, true) as? Money.Parse.Ok ?: return
        val d = date.iso ?: run { date.picker.error("יש לבחור תאריך"); return }
        val isOther = methodId?.let { repo.paymentMethod(it)?.isOther } == true
        val doIt = {
            ui.guard {
                var mid = methodId
                var mnote = if (isOther) otherText.clean else null
                if (isOther && saveMethod && mnote != null) { mid = repo.addPaymentMethod(mnote); mnote = null }
                val input = PaymentInput(expenseId, a.agorot, d, mid, mnote, note.clean, kind)
                when (val chk = repo.checkPayment(input, paymentId)) {
                    is PaymentCheck.Error -> ui.alert("לא ניתן לשמור", chk.message)
                    is PaymentCheck.Overpay -> overpay(input, chk)
                    PaymentCheck.Ok -> commit(input, OverpayPolicy.REJECT)
                }
            }
        }
        if (Dates.parseDate(d)?.isAfter(repo.today().plusDays(1)) == true) {
            ui.confirm("תאריך עתידי", "תאריך התשלום (${Dates.display(d)}) הוא בעתיד. תשלום נרשם כשולם בפועל — למשל צ׳ק דחוי שכבר נמסר. להמשיך?\nלתשלום שעדיין לא בוצע עדיף להגדיר \"תאריך תשלום עתידי\" בהוצאה.", "רישום בכל זאת") { doIt() }
        } else doIt()
    }

    private fun overpay(input: PaymentInput, c: PaymentCheck.Overpay) {
        val msg = "התשלום גבוה מהיתרה לתשלום (${Money.format(maxOf(c.remainingAgorot, 0))}) ב-${Money.format(c.excessAgorot)}.\n\nאיך לטפל בהפרש?"
        AlertDialog.Builder(act).setTitle("תשלום גבוה מהיתרה").setMessage(msg)
            .setPositiveButton("עדכון המחיר שסוכם ל-${Money.format(c.newPaidAgorot, isolate = false)}") { _, _ -> ui.guard { commit(input, OverpayPolicy.RAISE_AGREED) } }
            .setNeutralButton("רישום כזכות אצל הספק") { _, _ -> ui.guard { commit(input, OverpayPolicy.ALLOW_CREDIT) } }
            .setNegativeButton("חזרה לתיקון", null).show()
    }

    private fun commit(input: PaymentInput, policy: OverpayPolicy) {
        val res = if (paymentId == null) repo.addPayment(input, policy) else repo.updatePayment(paymentId, input, policy)
        initial = snapshot()
        ui.toast(if (paymentId == null) "התשלום נרשם" else "התשלום עודכן")
        val afterTasks = {
            if (paymentId == null) {
                ui.options("לצרף קבלה לתשלום?", listOf("צילום הקבלה", "בחירת קובץ / תמונה", "לא עכשיו")) { i ->
                    when (i) {
                        0 -> DocumentFlows.camera(act, DocLink(paymentId = res.paymentId), DocType.RECEIPT)
                        1 -> DocumentFlows.pickFile(act, DocLink(paymentId = res.paymentId), DocType.RECEIPT)
                    }
                }
            }
            close()
        }
        if (res.relatedOpenTasks.isNotEmpty()) {
            val tasks = res.relatedOpenTasks
            val checked = BooleanArray(tasks.size)
            AlertDialog.Builder(act).setTitle("ההוצאה שולמה במלואה")
                .setMultiChoiceItems(tasks.map { it.task.title }.toTypedArray(), checked) { _, w, v -> checked[w] = v }
                .setPositiveButton("סימון המסומנות כבוצעו") { _, _ ->
                    ui.guard { tasks.forEachIndexed { i, t -> if (checked[i]) repo.setTaskStatus(t.id, TaskStatus.DONE) } }
                    afterTasks()
                }
                .setNegativeButton("לא לשנות משימות") { _, _ -> afterTasks() }
                .setCancelable(false)
                .show()
        } else afterTasks()
    }

    private fun delete() {
        val f = ui.textField("סיבת המחיקה (תירשם ביומן השינויים)", null, "למשל: הוזן בטעות")
        val d = AlertDialog.Builder(act).setTitle("מחיקת רשומת תשלום")
            .setView(ui.dialogBody(ui.col().apply {
                addView(ui.tv("רשומת התשלום תימחק והיתרה תחושב מחדש. המחיקה תתועד ביומן השינויים של ההוצאה.", TS.CAPTION, p.text2), ui.lp(bottom = 10))
                addView(f.root)
            }))
            .setPositiveButton("מחיקה", null).setNegativeButton("ביטול", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(p.danger)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                ui.guard { repo.deletePayment(paymentId!!, f.clean); d.dismiss(); ui.toast("רשומת התשלום נמחקה"); initial = snapshot(); act.popUntil { it is ExpenseDetailScreen || it is SupplierDetailScreen || act.stack.size == 1 } }
            }
        }
        d.show()
    }
}

class PaymentDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "פרטי תשלום"

    override fun actions() = listOf(TopAction(R.drawable.ic_edit, "עריכה") {
        repo.payment(id)?.let { push(PaymentEditScreen(act, it.payment.expenseId, id)) }
    })

    override fun build(): View {
        val pv = repo.payment(id) ?: return ui.emptyState(R.drawable.ic_warning, "התשלום אינו קיים", "ייתכן שנמחק.")
        val pm = pv.payment
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        card.addView(ui.tv(if (pm.kind == PaymentKind.REFUND) "החזר מהספק" else "תשלום", TS.CAPTION_STRONG, p.text2))
        card.addView(ui.tv(money(pm.amountAgorot), TS.AMOUNT_L, if (pm.kind == PaymentKind.REFUND) p.warning else p.success), ui.lp(top = 2, bottom = 8))
        card.addView(ui.kv("תאריך", dateLine(pm.date)))
        card.addView(ui.kv("אמצעי תשלום", pv.methodLabel))
        pm.note?.let { card.addView(ui.kv("הערה", it)) }
        card.addView(ui.kv("נרשם", Dates.displayDateTime(pm.createdAt), p.text3))
        if (pm.updatedAt != pm.createdAt) card.addView(ui.kv("עודכן", Dates.displayDateTime(pm.updatedAt), p.text3))
        c.addView(card)
        listInCard(c, listOfNotNull(
            ui.listRow(pv.expenseName, "הוצאה", R.drawable.ic_receipt, chevron = true) { push(ExpenseDetailScreen(act, pm.expenseId)) },
            pv.supplierId?.let { sid -> ui.listRow(pv.supplierName ?: "", "ספק", R.drawable.ic_person, chevron = true) { push(SupplierDetailScreen(act, sid)) } }
        ))
        documentsSection(c, repo.documentsFor("payment_id", id), DocLink(paymentId = id), "קבלות ומסמכים", DocType.RECEIPT)
        return sv
    }
}
