package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import il.hamechutan.app.ui.*
import java.time.LocalDate
import java.time.LocalTime

fun money(a: Long) = Money.format(a)

fun Ui.expenseTone(s: ExpenseStatus) = when (s) {
    ExpenseStatus.UNPAID -> Tone.WARNING
    ExpenseStatus.PARTIAL -> Tone.INFO
    ExpenseStatus.PAID -> Tone.SUCCESS
    ExpenseStatus.CANCELLED -> Tone.NEUTRAL
}

fun Ui.supplierTone(s: SupplierStatus) = when (s) {
    SupplierStatus.NO_COMMITMENT -> Tone.NEUTRAL
    SupplierStatus.COMMITTED -> Tone.WARNING
    SupplierStatus.PARTIAL -> Tone.INFO
    SupplierStatus.PAID -> Tone.SUCCESS
    SupplierStatus.CANCELLED -> Tone.NEUTRAL
}

fun Ui.taskTone(t: Task, now: java.time.LocalDateTime): Tone = when {
    !t.status.isActive -> Tone.NEUTRAL
    il.hamechutan.app.core.data.TaskLogic.isOverdue(t, now) -> Tone.DANGER
    t.priority == Priority.URGENT -> Tone.DANGER
    t.priority == Priority.HIGH -> Tone.WARNING
    else -> Tone.INFO
}

/** Runs several repository writes atomically, then re-syncs reminders. */
fun Screen.tx(block: () -> Unit) {
    repo.db.transaction { block() }
    app.scheduler.sync()
}

/** A task row with a one-tap completion toggle. */
fun Screen.taskRow(tv: TaskView, showSupplier: Boolean = true, onChanged: () -> Unit = { refresh() }): View {
    val t = tv.task
    val now = repo.nowDateTime()
    val overdue = il.hamechutan.app.core.data.TaskLogic.isOverdue(t, now)
    val done = t.status == TaskStatus.DONE
    val r = ui.row().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8)); minimumHeight = ui.dp(60) }
    val check = ImageView(act).apply {
        setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_circle)
        imageTintList = android.content.res.ColorStateList.valueOf(if (done) p.success else if (overdue) p.danger else p.text3)
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        contentDescription = if (done) "סימון כלא בוצע" else "סימון כבוצע"
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(p.ripple), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
        setOnClickListener {
            ui.guard {
                if (t.status == TaskStatus.CANCELLED) return@guard
                repo.setTaskStatus(t.id, if (done) TaskStatus.OPEN else TaskStatus.DONE)
                if (!done) ui.toast("המשימה סומנה כבוצעה")
                onChanged()
            }
        }
    }
    r.addView(check, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    val mid = ui.col()
    mid.addView(ui.tv(t.title, TS.BODY_STRONG, if (done || t.status == TaskStatus.CANCELLED) p.text3 else p.text, 2).apply {
        if (done || t.status == TaskStatus.CANCELLED) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
    })
    val parts = mutableListOf<String>()
    parts += if (t.dueDate != null) Dates.whenLabel(t.dueDate, t.dueTime, repo.today()) else "ללא תאריך יעד"
    if (showSupplier && tv.supplierName != null) parts += tv.supplierName!!
    if (tv.expenseName != null && tv.expenseName != tv.supplierName) parts += tv.expenseName!!
    mid.addView(ui.tv(parts.joinToString(" · "), TS.CAPTION, if (overdue) p.danger else p.text2, 2), ui.lp(top = 2))
    r.addView(mid, ui.lp(0, WRAP, 1f, start = 4))
    when {
        overdue -> r.addView(ui.badge("באיחור", Tone.DANGER), ui.lp(WRAP, WRAP, start = 6))
        t.status == TaskStatus.CANCELLED -> r.addView(ui.badge("בוטלה", Tone.NEUTRAL), ui.lp(WRAP, WRAP, start = 6))
        t.priority == Priority.URGENT && t.status.isActive -> r.addView(ui.badge("דחוף", Tone.DANGER), ui.lp(WRAP, WRAP, start = 6))
        t.priority == Priority.HIGH && t.status.isActive -> r.addView(ui.badge("חשוב", Tone.WARNING), ui.lp(WRAP, WRAP, start = 6))
        t.status == TaskStatus.IN_PROGRESS -> r.addView(ui.badge("בתהליך", Tone.INFO), ui.lp(WRAP, WRAP, start = 6))
    }
    r.background = ui.ripple(null, 0f)
    r.isClickable = true
    r.setOnClickListener { push(TaskDetailScreen(act, t.id)) }
    r.setOnLongClickListener {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (t.status.isActive) {
            Priority.values().filter { it != t.priority }.forEach { pr ->
                items += "דחיפות: ${pr.label}" to { ui.guard { repo.setTaskPriority(t.id, pr); ui.toast("רמת הדחיפות עודכנה: ${pr.label}"); onChanged() } }
            }
            items += "סימון כבוצעה" to { ui.guard { repo.setTaskStatus(t.id, TaskStatus.DONE); onChanged() } }
        } else {
            items += "החזרה לפתוחה" to { ui.guard { repo.setTaskStatus(t.id, TaskStatus.OPEN); onChanged() } }
        }
        items += "עריכת המשימה" to { push(TaskEditScreen(act, t.id)) }
        ui.options(t.title, items.map { it.first }) { i -> items[i].second() }
        true
    }
    return r
}

/** Expense row with amounts and status. */
fun Screen.expenseRow(e: ExpenseSummary, showSupplier: Boolean = true): View {
    val sub = listOfNotNull(e.categoryName, if (showSupplier) e.supplierName else null).joinToString(" · ").ifEmpty { null }
    val third = when {
        e.cancelled -> "בוטל" + if (e.paidAgorot > 0) " · נשאר אצל הספק ${money(e.paidAgorot)}" else ""
        e.isOverpaid -> "שולם ${money(e.paidAgorot)} · זכות ${money(-e.remainingAgorot)}"
        else -> "סוכם ${money(e.expense.agreedAgorot)} · שולם ${money(e.paidAgorot)}"
    }
    val trailing = ui.trailingAmount(
        if (e.cancelled) money(e.effectiveCommitmentAgorot) else money(maxOf(e.remainingAgorot, 0)),
        if (!e.cancelled && e.remainingAgorot > 0) p.text else p.text2,
        if (e.cancelled) "עלות בפועל" else "יתרה",
        ui.badge(e.status.label, ui.expenseTone(e.status))
    )
    return ui.listRow(e.expense.name, sub, null, trailing = trailing, third = third,
        titleColor = if (e.cancelled) p.text3 else p.text) { push(ExpenseDetailScreen(act, e.id)) }
}

fun Screen.supplierRow(s: SupplierSummary): View {
    val sub = listOfNotNull(s.supplier.service, s.categoryName).joinToString(" · ").ifEmpty { null }
    val trailing = ui.col().apply {
        gravity = Gravity.END
        if (s.committedAgorot > 0 || s.status != SupplierStatus.NO_COMMITMENT) {
            addView(ui.tv(money(s.remainingAgorot), TS.BODY_STRONG, if (s.remainingAgorot > 0) p.text else p.text2).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
            addView(ui.tv("יתרה", TS.SMALL, p.text3).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
        }
        addView(ui.badge(s.status.label, ui.supplierTone(s.status)), ui.lp(WRAP, WRAP, top = 4, gravity = Gravity.END))
    }
    return ui.listRow(s.supplier.name, sub, R.drawable.ic_person, if (s.supplier.cancelled) Tone.NEUTRAL else Tone.INFO,
        trailing, third = s.supplier.phone, titleColor = if (s.supplier.cancelled) p.text3 else p.text) { push(SupplierDetailScreen(act, s.supplier.id)) }
}

fun Screen.listInCard(container: LinearLayout, rows: List<View>, empty: String? = null) {
    if (rows.isEmpty()) {
        if (empty != null) container.addView(ui.card().apply { addView(ui.tv(empty, TS.CAPTION, p.text2)) })
        return
    }
    val card = ui.listCard()
    rows.forEachIndexed { i, v ->
        if (i > 0) card.addView(ui.divider(16))
        card.addView(v)
    }
    container.addView(card)
}

// ------------------------------------------------------------------ reminders

private val reminderOptions = listOf<Pair<String, ReminderSpec?>>("ללא תזכורת" to null) +
    ReminderSpec.OFFSET_CHOICES.map { (m, l) -> l to ReminderSpec(ReminderMode.OFFSET, m) }

fun reminderLabel(spec: ReminderSpec?): String = when {
    spec == null -> "ללא תזכורת"
    spec.mode == ReminderMode.OFFSET -> ReminderSpec.offsetLabel(spec.offsetMinutes ?: 0)
    else -> "ב-" + Dates.displayDateTime(spec.absoluteAt ?: 0)
}

fun Reminder.toSpec() = ReminderSpec(mode, offsetMinutes, absoluteAt)

/** Lets the user choose a reminder; custom picks a date and time. */
fun MainActivity.chooseReminder(current: ReminderSpec?, onChosen: (ReminderSpec?) -> Unit) {
    val labels = reminderOptions.map { it.first } + "מועד אחר (תאריך ושעה)…"
    val checked = reminderOptions.indexOfFirst { it.second == current }.let { if (it < 0 && current != null) labels.size - 1 else it }
    AlertDialog.Builder(this).setTitle("מתי להזכיר?")
        .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
            d.dismiss()
            if (which < reminderOptions.size) onChosen(reminderOptions[which].second)
            else {
                val now = LocalDate.now()
                DatePickerDialog(this, { _, y, m, day ->
                    TimePickerDialog(this, { _, h, min ->
                        onChosen(ReminderSpec(ReminderMode.ABSOLUTE, absoluteAt = Dates.toMillis(LocalDate.of(y, m + 1, day), LocalTime.of(h, min))))
                    }, 9, 0, true).show()
                }, now.year, now.monthValue - 1, now.dayOfMonth).show()
            }
        }
        .setNegativeButton("סגירה", null).show()
}

/**
 * Reminder card for a detail screen: shows the real scheduling status (as armed in AlarmManager),
 * and lets the user change, mute or remove the reminder.
 */
fun Screen.reminderCard(container: LinearLayout, type: TargetType, targetId: Long, hint: String) {
    val r = repo.reminderFor(type, targetId)
    val card = ui.card()
    val head = ui.row()
    head.addView(ui.icon(if (r?.muted == true) R.drawable.ic_bell_off else R.drawable.ic_bell, p.gold, 20), ui.lp(ui.dp(20), ui.dp(20), end = 8))
    head.addView(ui.tv("תזכורת", TS.SUBTITLE), ui.lp(0, WRAP, 1f))
    card.addView(head)
    if (r == null) {
        card.addView(ui.tv("אין תזכורת. $hint", TS.CAPTION, p.text2), ui.lp(top = 6))
        card.addView(ui.button("הוספת תזכורת", BtnKind.TONAL, R.drawable.ic_add, small = true) {
            act.chooseReminder(null) { spec ->
                if (spec != null) act.ensureNotificationPermission { ui.guard { repo.setReminder(type, targetId, spec); refresh() } }
            }
        }, ui.lp(WRAP, WRAP, top = 10))
    } else {
        val st = repo.reminderStatus(r)
        val us = app.scheduler.uiStatus(st)
        card.addView(ui.tv(reminderLabel(r.toSpec()), TS.BODY_STRONG), ui.lp(top = 6))
        card.addView(ui.tv(us.text, TS.CAPTION, if (us.ok) p.success else p.warning), ui.lp(top = 2))
        when (us.problem) {
            il.hamechutan.app.platform.ReminderScheduler.Problem.NOTIFICATIONS_BLOCKED ->
                card.addView(ui.button("אישור התראות", BtnKind.TEXT, small = true) { act.ensureNotificationPermission { refresh() } })
            il.hamechutan.app.platform.ReminderScheduler.Problem.NOT_EXACT ->
                card.addView(ui.button("מתן הרשאה לתזמון מדויק", BtnKind.TEXT, small = true) { act.openExactAlarmSettings() })
            else -> {}
        }
        val actions = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 8) }
        actions.addView(ui.button("שינוי", BtnKind.TONAL, R.drawable.ic_edit, small = true) {
            act.chooseReminder(r.toSpec()) { spec -> ui.guard { repo.setReminder(type, targetId, spec); refresh() } }
        })
        actions.addView(ui.hspace(8))
        actions.addView(ui.button(if (r.muted) "ביטול השתקה" else "השתקה", BtnKind.SECONDARY, if (r.muted) R.drawable.ic_bell else R.drawable.ic_bell_off, small = true) {
            ui.guard { repo.setReminderMuted(r.id, !r.muted); refresh() }
        })
        actions.addView(ui.hspace(8))
        actions.addView(ui.button("הסרה", BtnKind.DANGER_TEXT, small = true) {
            ui.confirm("הסרת התזכורת", "התזכורת תוסר. הפריט עצמו יישאר.", "הסרה") { ui.guard { repo.setReminder(type, targetId, null); refresh() } }
        })
        card.addView(actions)
    }
    container.addView(card)
}

// ------------------------------------------------------------------ pickers

fun Screen.supplierChoice(label: String, value: Long?, required: Boolean = false, onChange: ((Long?) -> Unit)? = null): Ui.Choice<Long> {
    lateinit var ch: Ui.Choice<Long>
    ch = ui.choiceField(label, "ללא ספק", {
        repo.suppliers().filter { !it.supplier.cancelled || it.supplier.id == ch.value }.map { it.supplier.id }
    }, value, { id -> repo.supplier(id)?.name ?: "" }, required,
        extraAction = "+ ספק חדש…" to {
            quickAddSupplier { id -> ch.set(id); onChange?.invoke(id) }
        }, onChange = onChange)
    return ch
}

fun Screen.quickAddSupplier(onCreated: (Long) -> Unit) {
    val body = ui.col()
    val name = ui.textField("שם הספק", null, required = true)
    val phone = ui.phoneField("טלפון", null)
    body.addView(name.root); body.addView(phone.root)
    val d = AlertDialog.Builder(act).setTitle("ספק חדש").setView(ui.dialogBody(body))
        .setPositiveButton("הוספה", null).setNegativeButton("ביטול", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (name.clean == null) { name.error("יש למלא שם"); return@setOnClickListener }
            ui.guard {
                val id = repo.saveSupplier(SupplierInput(name.value, phone = phone.clean), null)
                d.dismiss(); onCreated(id)
            }
        }
    }
    d.show()
}

fun Screen.categoryChoice(value: Long?, onChange: ((Long?) -> Unit)? = null): Ui.Choice<Long> {
    lateinit var ch: Ui.Choice<Long>
    ch = ui.choiceField("קטגוריה", "ללא קטגוריה", { repo.categories().map { it.id } }, value,
        { id -> repo.category(id)?.name ?: "" },
        extraAction = "+ קטגוריה חדשה…" to {
            inputDialog("קטגוריה חדשה", "שם הקטגוריה", null) { n -> ui.guard { val id = repo.addCategory(n); ch.set(id); onChange?.invoke(id) } }
        }, onChange = onChange)
    return ch
}

fun Screen.expenseChoice(label: String, value: Long?, supplierFilter: () -> Long?, onChange: ((Long?) -> Unit)? = null): Ui.Choice<Long> =
    ui.choiceField(label, "ללא הוצאה", {
        val sf = supplierFilter()
        repo.expenses().filter { (!it.cancelled || it.id == value) && (sf == null || it.expense.supplierId == sf) }.map { it.id }
    }, value, { id -> repo.expense(id)?.let { e -> e.expense.name + (e.supplierName?.takeIf { it != e.expense.name }?.let { " · $it" } ?: "") } ?: "" },
        onChange = onChange)

fun Screen.inputDialog(title: String, label: String, initial: String?, multiline: Boolean = false, onOk: (String) -> Unit) {
    val f = ui.textField(label, initial, lines = if (multiline) 3 else 1)
    val d = AlertDialog.Builder(act).setTitle(title).setView(ui.dialogBody(f.root))
        .setPositiveButton("אישור", null).setNegativeButton("ביטול", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = f.clean
            if (v == null) { f.error("יש למלא ערך"); return@setOnClickListener }
            d.dismiss(); onOk(v)
        }
    }
    d.show()
    f.edit.requestFocus()
}

fun Screen.phoneActions(container: LinearLayout, phone: String?, label: String = "טלפון") {
    if (phone.isNullOrBlank()) return
    val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 4) }
    r.addView(ui.tv("$label: ", TS.BODY, p.text2))
    r.addView(ui.tv("⁦$phone⁩", TS.BODY_STRONG), ui.lp(0, WRAP, 1f))
    r.addView(ui.iconButton(R.drawable.ic_whatsapp, "WhatsApp", p.success) { act.whatsapp(phone) })
    r.addView(ui.iconButton(R.drawable.ic_phone, "חיוג", p.primary) { act.dial(phone) })
    container.addView(r)
}

fun Screen.dateLine(iso: String?, time: String? = null): String {
    val d = Dates.parseDate(iso) ?: return "ללא תאריך"
    val base = ui.dateLabel(iso) ?: Dates.display(d)
    return if (time != null) "$base · ${Dates.displayTime(time)}" else base
}

fun Screen.auditList(container: LinearLayout, entries: List<AuditEntry>) {
    if (entries.isEmpty()) return
    container.addView(ui.sectionHeader("יומן שינויים"))
    val card = ui.card()
    entries.take(30).forEachIndexed { i, a ->
        if (i > 0) card.addView(ui.divider(), ui.lp(MATCH, 1, top = 8, bottom = 8))
        card.addView(ui.tv(a.details ?: a.action, TS.CAPTION))
        card.addView(ui.tv(Dates.displayDateTime(a.createdAt), TS.SMALL, p.text3), ui.lp(top = 2))
    }
    container.addView(card)
}
