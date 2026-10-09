package il.hamechutan.app.ui.screens

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.HebrewDate
import il.hamechutan.app.ui.*
import java.time.LocalDate
import java.time.YearMonth

class CalendarScreen(act: MainActivity) : Screen(act) {
    override val title = "לוח שנה"
    private var month: YearMonth = YearMonth.from(LocalDate.now())
    private var selected: LocalDate = LocalDate.now()
    private var mode = 0

    override fun fab() = Fab(R.drawable.ic_add, "אירוע") { push(EventEditScreen(act, null, presetDate = Dates.iso(selected))) }

    override fun build(): View {
        val (sv, c) = ui.page()
        c.addView(ui.segmented(listOf("חודש", "רשימה"), mode) { mode = it; refresh() })
        if (mode == 0) monthView(c) else agenda(c)
        return sv
    }

    private fun typeColor(t: CalendarItemType): Int = when (t) {
        CalendarItemType.EVENT -> p.info
        CalendarItemType.TASK -> p.warning
        CalendarItemType.TRANSPORT -> p.success
        CalendarItemType.PAYMENT_DUE -> p.gold
        CalendarItemType.KEY_DATE -> p.primary
        else -> p.danger
    }

    private fun monthView(c: LinearLayout) {
        val first = month.atDay(1)
        val gridStart = first.minusDays(Dates.dayIndex(first).toLong())
        val gridEnd = gridStart.plusDays(41)
        val items = repo.calendarItems(gridStart, gridEnd).groupBy { it.date }
        val today = repo.today()

        val card = ui.card(8, 10)
        val nav = ui.row()
        val prev = ui.iconButton(R.drawable.ic_chevron, "החודש הקודם", p.text) { month = month.minusMonths(1); selected = month.atDay(1); refresh() }
        prev.rotation = 180f
        nav.addView(prev)
        val titles = ui.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
        titles.addView(ui.tv(Dates.monthTitle(month.year, month.monthValue), TS.SUBTITLE).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
        if (ui.hebrewDates) {
            val h1 = HebrewDate.fromGregorian(first); val h2 = HebrewDate.fromGregorian(month.atEndOfMonth())
            val label = if (h1.month == h2.month) "${h1.monthName()} ${HebrewDate.gematria(h1.year % 1000)}" else "${h1.monthName()} – ${h2.monthName()} ${HebrewDate.gematria(h2.year % 1000)}"
            titles.addView(ui.tv(label, TS.SMALL, p.text2).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
        }
        nav.addView(titles, ui.lp(0, WRAP, 1f))
        nav.addView(ui.iconButton(R.drawable.ic_chevron, "החודש הבא", p.text) { month = month.plusMonths(1); selected = month.atDay(1); refresh() })
        card.addView(nav)
        if (month != YearMonth.from(today)) card.addView(ui.button("חזרה להיום", BtnKind.TEXT, small = true) { month = YearMonth.from(today); selected = today; refresh() }, ui.lp(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL))

        val head = ui.row()
        Dates.DAY_LETTERS.forEach { d -> head.addView(ui.tv(d, TS.SMALL, p.text3).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(0, WRAP, 1f)) }
        card.addView(head, ui.lp(MATCH, WRAP, top = 6, bottom = 4))
        var day = gridStart
        repeat(6) {
            val week = ui.row()
            repeat(7) {
                val d = day
                val inMonth = d.month == month.month
                val dayItems = items[Dates.iso(d)].orEmpty()
                val cell = ui.col().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ui.dp(4), 0, ui.dp(4))
                    val sel = d == selected
                    background = when {
                        sel -> ui.shape(p.primary, 12f)
                        d == today -> ui.shape(0, 12f, p.primary, 1.5f)
                        else -> ui.ripple(null, 12f)
                    }
                    isClickable = true
                    contentDescription = Dates.displayLong(d)
                    setOnClickListener { selected = d; refresh() }
                }
                val fg = when { d == selected -> p.onPrimary; !inMonth -> p.text3; else -> p.text }
                cell.addView(ui.tv(d.dayOfMonth.toString(), if (d == today) TS.BODY_STRONG else TS.BODY, fg).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
                if (ui.hebrewDates) cell.addView(ui.tv(HebrewDate.fromGregorian(d).dayLabel(), TS.SMALL, if (d == selected) p.onPrimary else p.text3).apply {
                    textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 9.5f * ui.scale
                })
                val dots = ui.row(Gravity.CENTER).apply { minimumHeight = ui.dp(6) }
                dayItems.map { it.type }.distinct().take(3).forEach { t ->
                    dots.addView(View(act).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (d == selected) p.onPrimary else typeColor(t)) }
                    }, ui.lp(ui.dp(5), ui.dp(5), start = 1, end = 1))
                }
                cell.addView(dots, ui.lp(MATCH, ui.dp(6), top = 2))
                week.addView(cell, ui.lp(0, WRAP, 1f, start = 1, end = 1))
                day = day.plusDays(1)
            }
            card.addView(week, ui.lp(MATCH, WRAP, bottom = 2))
        }
        c.addView(card)
        // legend
        val legend = ui.row().apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)) }
        listOf(CalendarItemType.EVENT, CalendarItemType.TASK, CalendarItemType.TRANSPORT, CalendarItemType.PAYMENT_DUE, CalendarItemType.KEY_DATE).forEach { t ->
            legend.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(typeColor(t)) } }, ui.lp(ui.dp(7), ui.dp(7), end = 3))
            legend.addView(ui.tv(t.label, TS.SMALL, p.text3), ui.lp(WRAP, WRAP, end = 8))
        }
        c.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(legend) })

        val sel = items[Dates.iso(selected)].orEmpty()
        c.addView(ui.sectionHeader((ui.dateLabel(Dates.iso(selected)) ?: ""), "הוספה") { addFor(selected) })
        listInCard(c, sel.map { calendarRow(it, showDate = false) }, "אין פריטים ביום זה.")
    }

    private fun addFor(d: LocalDate) {
        val iso = Dates.iso(d)
        ui.options("הוספה ל-${Dates.display(d).replace("⁦", "").replace("⁩", "")}", listOf("אירוע / פגישה", "משימה", "הסעה")) { i ->
            when (i) {
                0 -> push(EventEditScreen(act, null, presetDate = iso))
                1 -> push(TaskEditScreen(act, null, presetDate = iso))
                2 -> push(TransportEditScreen(act, null, presetDate = iso))
            }
        }
    }

    private fun agenda(c: LinearLayout) {
        val today = repo.today()
        val items = repo.calendarItems(today.minusDays(7), today.plusDays(180)).filter { !it.cancelled }
        if (items.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_calendar, "אין פריטים קרובים", "אירועים, משימות עם תאריך, הסעות ומועדי תשלום יופיעו כאן."))
            return
        }
        items.groupBy { it.date }.forEach { (date, l) ->
            val d = Dates.parseDate(date)!!
            c.addView(ui.sectionHeader(Dates.relative(d, today).let { r -> if (kotlin.math.abs(Dates.daysBetween(today, d)) <= 2) "$r · " else "" } + (ui.dateLabel(date) ?: "")))
            listInCard(c, l.map { calendarRow(it, showDate = false) })
        }
    }
}

class EventDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "אירוע"

    override fun actions() = listOf(
        TopAction(R.drawable.ic_edit, "עריכה") { push(EventEditScreen(act, id)) },
        TopAction(R.drawable.ic_delete, "מחיקה") {
            ui.confirm("מחיקת אירוע", "האירוע והתזכורת שלו יימחקו לצמיתות.", "מחיקה", destructive = true) { ui.guard { repo.deleteEvent(id); ui.toast("האירוע נמחק"); close() } }
        }
    )

    override fun build(): View {
        val ev = repo.event(id) ?: return ui.emptyState(R.drawable.ic_warning, "האירוע אינו קיים", null)
        val e = ev.event
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        val r = ui.row()
        r.addView(ui.tv(e.title, TS.TITLE, if (e.status == EventStatus.ACTIVE) p.text else p.text3), ui.lp(0, WRAP, 1f))
        r.addView(ui.badge(e.status.label, when (e.status) { EventStatus.DONE -> Tone.SUCCESS; EventStatus.CANCELLED -> Tone.NEUTRAL; else -> Tone.INFO }))
        card.addView(r)
        card.addView(ui.kv("מועד", dateLine(e.date, e.time)), ui.lp(top = 6))
        e.location?.let { card.addView(ui.kv("מקום", it)) }
        e.notes?.let { card.addView(ui.tv(it, TS.BODY), ui.lp(top = 8)) }
        c.addView(card)
        val actions = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }
        if (e.status == EventStatus.ACTIVE) {
            actions.addView(ui.button("סימון כהושלם", BtnKind.PRIMARY, R.drawable.ic_check_circle, small = true) { ui.guard { repo.setEventStatus(id, EventStatus.DONE); refresh() } }, ui.lp(0, WRAP, 1f))
            actions.addView(ui.hspace(8))
            actions.addView(ui.button("ביטול האירוע", BtnKind.SECONDARY, small = true) {
                ui.confirm("ביטול אירוע", "האירוע יסומן כבוטל והתזכורת שלו לא תופעל.", "ביטול האירוע") { ui.guard { repo.setEventStatus(id, EventStatus.CANCELLED); refresh() } }
            }, ui.lp(0, WRAP, 1f))
        } else {
            actions.addView(ui.button("החזרה לפעיל", BtnKind.TONAL, R.drawable.ic_undo, small = true) { ui.guard { repo.setEventStatus(id, EventStatus.ACTIVE); refresh() } }, ui.lp(0, WRAP, 1f))
        }
        c.addView(actions)
        val links = mutableListOf<View>()
        e.supplierId?.let { sid -> repo.supplierSummary(sid)?.let { links += supplierRow(it) } }
        e.expenseId?.let { eid -> repo.expense(eid)?.let { links += expenseRow(it) } }
        if (links.isNotEmpty()) { c.addView(ui.sectionHeader("מקושר ל")); listInCard(c, links) }
        e.supplierId?.let { sid -> repo.supplier(sid)?.phone?.let { ph -> c.addView(ui.card().apply { phoneActions(this, ph, "טלפון הספק") }) } }
        if (e.status == EventStatus.ACTIVE) reminderCard(c, TargetType.EVENT, id, "בחרו מתי להזכיר על האירוע.")
        documentsSection(c, repo.documentsFor("event_id", id), DocLink(eventId = id))
        return sv
    }
}

class EventEditScreen(
    act: MainActivity, private val id: Long?, private val presetDate: String? = null,
    private val presetSupplier: Long? = null, private val presetExpense: Long? = null
) : Screen(act) {
    override val title = if (id == null) "אירוע חדש" else "עריכת אירוע"
    override val keepView = true
    private lateinit var titleF: Ui.TextInput
    private lateinit var date: Ui.DateInput
    private lateinit var time: Ui.TimeInput
    private lateinit var location: Ui.TextInput
    private lateinit var supplier: Ui.Choice<Long>
    private lateinit var expense: Ui.Choice<Long>
    private lateinit var notes: Ui.TextInput
    private lateinit var remPicker: Ui.Picker
    private var reminder: ReminderSpec? = null
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val e = id?.let { repo.event(it)?.event }
        reminder = id?.let { repo.reminderFor(TargetType.EVENT, it)?.toSpec() }
        val (sv, c) = ui.page(32)
        val main = ui.card(16, 16)
        titleF = ui.textField("כותרת", e?.title, "למשל: פגישה עם הזמר, מדידת חליפה, איסוף שטריימל", required = true)
        date = ui.dateField("תאריך", e?.date ?: presetDate ?: Dates.iso(repo.today()), required = true)
        time = ui.timeField("שעה", e?.time)
        location = ui.textField("מקום", e?.location)
        listOf(titleF.root, date.picker.root, time.picker.root, location.root).forEach { main.addView(it) }
        c.addView(main)
        val links = ui.card(16, 16)
        supplier = supplierChoice("ספק", e?.supplierId ?: presetSupplier) { sid ->
            val ex = expense.value?.let { repo.expense(it) }
            if (ex != null && sid != null && ex.expense.supplierId != sid) expense.set(null)
        }
        expense = expenseChoice("הוצאה", e?.expenseId ?: presetExpense, { supplier.value })
        links.addView(supplier.picker.root); links.addView(expense.picker.root)
        c.addView(links)
        val rem = ui.card(16, 16)
        remPicker = ui.picker("תזכורת", "ללא תזכורת", R.drawable.ic_bell) { act.chooseReminder(reminder) { s -> reminder = s; remPicker.set(s?.let { reminderLabel(it) }) } }
        remPicker.set(reminder?.let { reminderLabel(it) })
        rem.addView(remPicker.root)
        notes = ui.textField("הערות", e?.notes, lines = 3)
        rem.addView(notes.root)
        c.addView(rem)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        initial = snapshot()
        return sv
    }

    private fun snapshot() = listOf(titleF.value, date.iso, time.iso, location.value, supplier.value, expense.value, notes.value, reminder).joinToString("|")
    override fun isDirty() = ::titleF.isInitialized && snapshot() != initial

    private fun save() {
        titleF.error(null)
        if (titleF.clean == null) { titleF.error("יש למלא כותרת"); return }
        val d = date.iso ?: run { date.picker.error("יש לבחור תאריך"); return }
        val status = id?.let { repo.event(it)?.event?.status } ?: EventStatus.ACTIVE
        val input = EventInput(titleF.value, d, time.iso, location.clean, supplier.value, expense.value, notes.clean, status)
        val doSave = {
            ui.guard {
                var newId = id
                tx {
                    newId = repo.saveEvent(input, id)
                    if (repo.reminderFor(TargetType.EVENT, newId!!)?.toSpec() != reminder) repo.setReminder(TargetType.EVENT, newId!!, reminder)
                }
                initial = snapshot()
                ui.toast("האירוע נשמר")
                if (id == null) act.replaceTop(EventDetailScreen(act, newId!!)) else close()
            }
        }
        if (reminder != null) act.ensureNotificationPermission { doSave() } else doSave()
    }
}

// ------------------------------------------------------------------ transports

class TransportsScreen(act: MainActivity) : Screen(act) {
    override val title = "הסעות"
    private var showPast = false
    override fun fab() = Fab(R.drawable.ic_add, "הסעה חדשה") { push(TransportEditScreen(act, null)) }

    override fun build(): View {
        val all = repo.transports()
        val (sv, c) = ui.page()
        if (all.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_bus, "אין הסעות", "רשמו שעת יציאה, מקום, יעד ופרטי נהג. אפשר להתקשר לנהג בלחיצה.", "הוספת הסעה") { push(TransportEditScreen(act, null)) })
            return sv
        }
        val today = Dates.iso(repo.today())
        val upcoming = all.filter { it.date >= today }
        val past = all.filter { it.date < today }
        if (upcoming.isEmpty()) c.addView(ui.card().apply { addView(ui.tv("אין הסעות קרובות.", TS.CAPTION, p.text2)) })
        upcoming.groupBy { it.date }.forEach { (d, l) ->
            c.addView(ui.sectionHeader(ui.dateLabel(d) ?: d))
            listInCard(c, l.map { transportRow(it) })
        }
        if (past.isNotEmpty()) {
            c.addView(ui.button(if (showPast) "הסתרת הסעות שעברו" else "הצגת ${past.size} הסעות שעברו", BtnKind.TEXT) { showPast = !showPast; refresh() })
            if (showPast) listInCard(c, past.reversed().map { transportRow(it) })
        }
        return sv
    }
}

fun Screen.transportRow(t: Transport): View {
    val sub = listOfNotNull(t.departTime?.let { "יציאה " + Dates.displayTime(it) }, t.departPlace?.let { "מ: $it" },
        t.driverName?.let { "נהג: $it" }).joinToString(" · ")
    val trailing = if (!t.driverPhone.isNullOrBlank()) ui.iconButton(R.drawable.ic_phone, "התקשרות לנהג", p.primary) { act.dial(t.driverPhone) } else null
    return ui.listRow(t.displayTitle, sub, R.drawable.ic_bus, Tone.SUCCESS, trailing, third = t.notes) { push(TransportDetailScreen(act, t.id)) }
}

class TransportDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "הסעה"
    override fun actions() = listOf(
        TopAction(R.drawable.ic_edit, "עריכה") { push(TransportEditScreen(act, id)) },
        TopAction(R.drawable.ic_delete, "מחיקה") {
            ui.confirm("מחיקת הסעה", "ההסעה והתזכורת שלה יימחקו לצמיתות.", "מחיקה", destructive = true) { ui.guard { repo.deleteTransport(id); ui.toast("ההסעה נמחקה"); close() } }
        }
    )

    override fun build(): View {
        val t = repo.transport(id) ?: return ui.emptyState(R.drawable.ic_warning, "ההסעה אינה קיימת", null)
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        card.addView(ui.tv(t.displayTitle, TS.TITLE))
        card.addView(ui.kv("תאריך", dateLine(t.date)), ui.lp(top = 6))
        card.addView(ui.kv("שעת יציאה", t.departTime?.let { Dates.displayTime(it) } ?: "לא נקבעה", strong = true))
        card.addView(ui.kv("נקודת יציאה", t.departPlace ?: "—"))
        card.addView(ui.kv("יעד", t.destination ?: "—"))
        card.addView(ui.kv("נהג", t.driverName ?: "לא ידוע"))
        phoneActions(card, t.driverPhone, "טלפון הנהג")
        t.notes?.let { card.addView(ui.tv(it, TS.BODY), ui.lp(top = 8)) }
        c.addView(card)
        if (!t.driverPhone.isNullOrBlank()) c.addView(ui.button("התקשרות לנהג", BtnKind.PRIMARY, R.drawable.ic_phone) { act.dial(t.driverPhone) }, ui.lp(MATCH, WRAP, bottom = 12))
        t.supplierId?.let { sid -> repo.supplierSummary(sid)?.let { listInCard(c, listOf(supplierRow(it))) } }
        reminderCard(c, TargetType.TRANSPORT, id, "תזכורת לפני היציאה.")
        documentsSection(c, repo.documentsFor("transport_id", id), DocLink(transportId = id))
        return sv
    }
}

class TransportEditScreen(act: MainActivity, private val id: Long?, private val presetDate: String? = null) : Screen(act) {
    override val title = if (id == null) "הסעה חדשה" else "עריכת הסעה"
    override val keepView = true
    private lateinit var titleF: Ui.TextInput
    private lateinit var date: Ui.DateInput
    private lateinit var time: Ui.TimeInput
    private lateinit var from: Ui.TextInput
    private lateinit var to: Ui.TextInput
    private lateinit var driver: Ui.TextInput
    private lateinit var phone: Ui.TextInput
    private lateinit var supplier: Ui.Choice<Long>
    private lateinit var notes: Ui.TextInput
    private lateinit var remPicker: Ui.Picker
    private var reminder: ReminderSpec? = null
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val t = id?.let { repo.transport(it) }
        reminder = id?.let { repo.reminderFor(TargetType.TRANSPORT, it)?.toSpec() }
        val (sv, c) = ui.page(32)
        val main = ui.card(16, 16)
        titleF = ui.textField("כינוי (לא חובה)", t?.title, "למשל: הסעת המשפחה לאולם")
        date = ui.dateField("תאריך ההסעה", t?.date ?: presetDate ?: repo.wedding().date ?: Dates.iso(repo.today()), required = true)
        time = ui.timeField("שעת יציאה", t?.departTime)
        from = ui.textField("נקודת יציאה", t?.departPlace, "כתובת או מקום")
        to = ui.textField("יעד", t?.destination, "אולם, בית כנסת…")
        listOf(titleF.root, date.picker.root, time.picker.root, from.root, to.root).forEach { main.addView(it) }
        c.addView(main)
        val d = ui.card(16, 16)
        driver = ui.textField("שם הנהג", t?.driverName)
        phone = ui.phoneField("טלפון הנהג", t?.driverPhone)
        supplier = supplierChoice("חברת הסעות (ספק)", t?.supplierId)
        notes = ui.textField("הערות", t?.notes, lines = 2)
        remPicker = ui.picker("תזכורת", "ללא תזכורת", R.drawable.ic_bell) { act.chooseReminder(reminder) { s -> reminder = s; remPicker.set(s?.let { reminderLabel(it) }) } }
        remPicker.set(reminder?.let { reminderLabel(it) })
        listOf(driver.root, phone.root, supplier.picker.root, remPicker.root, notes.root).forEach { d.addView(it) }
        c.addView(d)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        initial = snapshot()
        return sv
    }

    private fun snapshot() = listOf(titleF.value, date.iso, time.iso, from.value, to.value, driver.value, phone.value, supplier.value, notes.value, reminder).joinToString("|")
    override fun isDirty() = ::titleF.isInitialized && snapshot() != initial

    private fun save() {
        val dd = date.iso ?: run { date.picker.error("יש לבחור תאריך"); return }
        if (from.clean == null && to.clean == null && titleF.clean == null) { from.error("יש למלא לפחות נקודת יציאה או יעד"); return }
        val input = TransportInput(titleF.clean, dd, time.iso, from.clean, to.clean, driver.clean, phone.clean, supplier.value, notes.clean)
        val doSave = {
            ui.guard {
                var newId = id
                tx {
                    newId = repo.saveTransport(input, id)
                    if (repo.reminderFor(TargetType.TRANSPORT, newId!!)?.toSpec() != reminder) repo.setReminder(TargetType.TRANSPORT, newId!!, reminder)
                }
                initial = snapshot()
                ui.toast("ההסעה נשמרה")
                if (id == null) act.replaceTop(TransportDetailScreen(act, newId!!)) else close()
            }
        }
        if (reminder != null) act.ensureNotificationPermission { doSave() } else doSave()
    }
}
