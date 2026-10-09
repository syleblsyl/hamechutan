package il.hamechutan.app.ui.screens

import android.view.View
import il.hamechutan.app.R
import il.hamechutan.app.core.data.TaskLogic
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.ui.*

class TasksScreen(act: MainActivity) : Screen(act) {
    override val title = "משימות"
    private var view = 0 // 0 open (grouped), 1 overdue, 2 done, 3 cancelled, 4 all
    private var supplierFilter: Long? = null

    override fun actions() = listOf(TopAction(R.drawable.ic_search, "חיפוש") { push(SearchScreen(act)) })
    override fun fab() = Fab(R.drawable.ic_add, "משימה חדשה") { push(TaskEditScreen(act, null)) }

    override fun build(): View {
        val all = repo.tasks().filter { supplierFilter == null || it.task.supplierId == supplierFilter }
        val (sv, c) = ui.page()
        if (all.isEmpty() && supplierFilter == null) {
            c.addView(ui.emptyState(R.drawable.ic_tasks, "אין עדיין משימות",
                "רשמו כאן את כל מה שצריך לעשות: לשלם לצלם, לתאם עם הזמר, לאסוף שטריימל, לתאם הסעה…", "הוספת משימה") { push(TaskEditScreen(act, null)) })
            return sv
        }
        val now = repo.nowDateTime()
        val open = all.filter { it.task.status.isActive }
        val overdue = open.filter { TaskLogic.isOverdue(it.task, now) }
        val done = all.filter { it.task.status == TaskStatus.DONE }
        val cancelled = all.filter { it.task.status == TaskStatus.CANCELLED }
        val chips = mutableListOf<View>(
            ui.chip("מה נשאר", view == 0, open.size) { view = 0; refresh() },
            ui.chip("באיחור", view == 1, overdue.size) { view = 1; refresh() },
            ui.chip("הושלמו", view == 2, done.size) { view = 2; refresh() }
        )
        if (cancelled.isNotEmpty()) chips += ui.chip("בוטלו", view == 3, cancelled.size) { view = 3; refresh() }
        chips += ui.chip("הכל", view == 4, all.size) { view = 4; refresh() }
        chips += ui.chip(supplierFilter?.let { "ספק: " + (repo.supplier(it)?.name ?: "") } ?: "כל הספקים", supplierFilter != null) {
            val sups = repo.suppliers()
            ui.options("סינון לפי ספק", listOf("כל הספקים") + sups.map { it.supplier.name }) { i -> supplierFilter = if (i == 0) null else sups[i - 1].supplier.id; refresh() }
        }
        c.addView(ui.chipRow(chips).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }.also { it.setPadding(0, 0, 0, 0) })

        when (view) {
            0 -> {
                val buckets = TaskLogic.bucketize(all, now)
                var any = false
                buckets.forEach { (b, list) ->
                    if (list.isEmpty()) return@forEach
                    any = true
                    val tone = when (b) { TaskBucket.OVERDUE, TaskBucket.URGENT -> Tone.DANGER; TaskBucket.TODAY -> Tone.WARNING; TaskBucket.WEEK -> Tone.INFO; else -> Tone.NEUTRAL }
                    val head = ui.row().apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(6)) }
                    head.addView(ui.badge(b.label, tone))
                    head.addView(ui.tv("  ${list.size}", TS.CAPTION, p.text2))
                    c.addView(head)
                    listInCard(c, list.map { taskRow(it) })
                }
                if (!any) c.addView(ui.emptyState(R.drawable.ic_check_circle, "כל המשימות הושלמו", "אין משימות פתוחות כרגע."))
            }
            1 -> listInCard(c, overdue.sortedWith(TaskLogic.comparator).map { taskRow(it) }, "אין משימות באיחור.")
            2 -> listInCard(c, done.sortedByDescending { it.task.completedAt ?: 0 }.map { taskRow(it) }, "אין משימות שהושלמו.")
            3 -> listInCard(c, cancelled.map { taskRow(it) }, "אין משימות שבוטלו.")
            4 -> listInCard(c, all.sortedWith(TaskLogic.comparator).map { taskRow(it) })
        }
        return sv
    }
}

class TaskDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "משימה"

    override fun actions() = listOf(
        TopAction(R.drawable.ic_edit, "עריכה") { push(TaskEditScreen(act, id)) },
        TopAction(R.drawable.ic_delete, "מחיקה") { delete() }
    )

    override fun build(): View {
        val tv = repo.task(id) ?: return ui.emptyState(R.drawable.ic_warning, "המשימה אינה קיימת", "ייתכן שנמחקה.")
        val t = tv.task
        val now = repo.nowDateTime()
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        val r = ui.row()
        r.addView(ui.tv(t.title, TS.TITLE, if (t.status.isActive) p.text else p.text3), ui.lp(0, WRAP, 1f))
        r.addView(ui.badge(t.status.label, when (t.status) { TaskStatus.DONE -> Tone.SUCCESS; TaskStatus.CANCELLED -> Tone.NEUTRAL; TaskStatus.IN_PROGRESS -> Tone.INFO; else -> Tone.WARNING }))
        card.addView(r)
        if (TaskLogic.isOverdue(t, now)) card.addView(ui.badge("באיחור", Tone.DANGER), ui.lp(WRAP, WRAP, top = 6))
        card.addView(ui.kv("תאריך יעד", if (t.dueDate != null) dateLine(t.dueDate, t.dueTime) else "ללא"), ui.lp(top = 6))
        if (t.status.isActive) {
            card.addView(ui.fieldLabel("רמת דחיפות (אפשר לשנות בכל עת)"), ui.lp(top = 8))
            card.addView(ui.segmented(Priority.values().map { it.label }, Priority.values().indexOf(t.priority)) { i ->
                ui.guard { repo.setTaskPriority(id, Priority.values()[i]); ui.toast("רמת הדחיפות עודכנה: ${Priority.values()[i].label}"); refresh() }
            })
        } else {
            card.addView(ui.kv("עדיפות", t.priority.label, if (t.priority == Priority.URGENT) p.danger else p.text))
        }
        t.completedAt?.let { card.addView(ui.kv("הושלמה", Dates.displayDateTime(it), p.success)) }
        t.description?.let { card.addView(ui.tv(it, TS.BODY), ui.lp(top = 8)) }
        t.notes?.let { card.addView(ui.tv("הערות: $it", TS.CAPTION, p.text2), ui.lp(top = 8)) }
        c.addView(card)

        val actions = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }
        if (t.status.isActive) {
            actions.addView(ui.button("סימון כבוצעה", BtnKind.PRIMARY, R.drawable.ic_check_circle, small = true) {
                ui.guard { repo.setTaskStatus(id, TaskStatus.DONE); ui.toast("המשימה סומנה כבוצעה"); refresh() }
            }, ui.lp(0, WRAP, 1f))
            actions.addView(ui.hspace(8))
            if (t.status == TaskStatus.OPEN) actions.addView(ui.button("בתהליך", BtnKind.SECONDARY, small = true) {
                ui.guard { repo.setTaskStatus(id, TaskStatus.IN_PROGRESS); refresh() }
            }, ui.lp(0, WRAP, 1f))
            else actions.addView(ui.button("ביטול המשימה", BtnKind.SECONDARY, small = true) {
                ui.confirm("ביטול משימה", "המשימה תסומן כבוטלה ותזכורת שלה לא תופעל. אפשר להחזיר אותה בהמשך.", "ביטול המשימה") {
                    ui.guard { repo.setTaskStatus(id, TaskStatus.CANCELLED); refresh() }
                }
            }, ui.lp(0, WRAP, 1f))
        } else {
            actions.addView(ui.button("החזרה לפתוחה", BtnKind.TONAL, R.drawable.ic_undo, small = true) {
                ui.guard { repo.setTaskStatus(id, TaskStatus.OPEN); refresh() }
            }, ui.lp(0, WRAP, 1f))
        }
        c.addView(actions)

        val links = mutableListOf<View>()
        t.supplierId?.let { sid -> repo.supplierSummary(sid)?.let { s -> links += supplierRow(s) } }
        t.expenseId?.let { eid -> repo.expense(eid)?.let { e -> links += expenseRow(e) } }
        if (links.isNotEmpty()) {
            c.addView(ui.sectionHeader("מקושר ל"))
            listInCard(c, links)
            t.expenseId?.let { eid -> repo.expense(eid)?.takeIf { !it.cancelled && it.remainingAgorot > 0 }?.let {
                c.addView(ui.button("רישום תשלום להוצאה", BtnKind.TONAL, R.drawable.ic_payments) { push(PaymentEditScreen(act, eid, null)) }, ui.lp(MATCH, WRAP, bottom = 12))
            } }
        }
        if (t.status.isActive) reminderCard(c, TargetType.TASK, id, if (t.dueDate == null) "כדי להזכיר ביחס למועד יש להגדיר תאריך יעד, או לבחור מועד אחר." else "בחרו מתי להזכיר.")
        else repo.reminderFor(TargetType.TASK, id)?.let { c.addView(ui.banner("לתזכורת של משימה ${t.status.label} אין השפעה — היא לא תופעל.", Tone.NEUTRAL, R.drawable.ic_bell_off)) }
        documentsSection(c, repo.documentsFor("task_id", id), DocLink(taskId = id))
        return sv
    }

    private fun delete() {
        ui.confirm("מחיקת משימה", "המשימה והתזכורת שלה יימחקו לצמיתות. מסמכים מצורפים יישמרו במסך המסמכים.", "מחיקה", destructive = true) {
            ui.guard { repo.deleteTask(id); ui.toast("המשימה נמחקה"); close() }
        }
    }
}

class TaskEditScreen(
    act: MainActivity, private val id: Long?, private val presetSupplier: Long? = null,
    private val presetExpense: Long? = null, private val presetDate: String? = null
) : Screen(act) {
    override val title = if (id == null) "משימה חדשה" else "עריכת משימה"
    override val keepView = true

    private lateinit var titleF: Ui.TextInput
    private lateinit var due: Ui.DateInput
    private lateinit var time: Ui.TimeInput
    private lateinit var supplier: Ui.Choice<Long>
    private lateinit var expense: Ui.Choice<Long>
    private lateinit var desc: Ui.TextInput
    private lateinit var notes: Ui.TextInput
    private lateinit var reminderPicker: Ui.Picker
    private var priority = Priority.NORMAL
    private var status = TaskStatus.OPEN
    private var reminder: ReminderSpec? = null
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val tv = id?.let { repo.task(it) }
        val t = tv?.task
        t?.let { priority = it.priority; status = it.status }
        reminder = id?.let { repo.reminderFor(TargetType.TASK, it)?.toSpec() }
        val (sv, c) = ui.page(32)
        val main = ui.card(16, 16)
        titleF = ui.textField("כותרת", t?.title, "מה צריך לעשות?", required = true)
        main.addView(titleF.root)
        if (id == null) {
            val sugg = listOf("לשלם ל", "לתאם עם", "לאסוף", "לבדוק", "להביא", "להזמין")
            main.addView(ui.chipRow(sugg.map { s -> ui.chip(s + "…", false) {
                val sn = supplier.value?.let { repo.supplier(it)?.name }
                val prefix = s == "לשלם ל"
                titleF.set(when { sn == null && prefix -> s; sn == null -> "$s "; prefix -> "$s$sn"; else -> "$s $sn" })
                titleF.edit.setSelection(titleF.edit.text.length); titleF.edit.requestFocus()
            } }).also { it.setPadding(0, 0, 0, 0); (it.getChildAt(0) as android.widget.LinearLayout).setPadding(0, 0, 0, 0) }, ui.lp(MATCH, WRAP, top = -6, bottom = 14))
        }
        due = ui.dateField("תאריך יעד", t?.dueDate ?: presetDate) { updateReminderLabel() }
        time = ui.timeField("שעה", t?.dueTime, "לא חובה")
        main.addView(due.picker.root); main.addView(time.picker.root)
        main.addView(ui.fieldLabel("עדיפות"))
        main.addView(ui.segmented(Priority.values().map { it.label }, Priority.values().indexOf(priority)) { priority = Priority.values()[it] })
        if (id != null) {
            main.addView(ui.fieldLabel("מצב"))
            main.addView(ui.segmented(TaskStatus.values().map { it.label }, TaskStatus.values().indexOf(status)) { status = TaskStatus.values()[it] })
        }
        c.addView(main)

        val links = ui.card(16, 16)
        links.addView(ui.tv("קישורים", TS.SUBTITLE), ui.lp(bottom = 10))
        supplier = supplierChoice("ספק", t?.supplierId ?: presetSupplier ?: presetExpense?.let { repo.expense(it)?.expense?.supplierId }) { sid ->
            val e = expense.value?.let { repo.expense(it) }
            if (e != null && sid != null && e.expense.supplierId != sid) expense.set(null)
        }
        expense = expenseChoice("הוצאה / התחייבות", t?.expenseId ?: presetExpense, { supplier.value }) { eid ->
            eid?.let { repo.expense(it)?.expense?.supplierId }?.let { if (supplier.value == null) supplier.set(it) }
        }
        links.addView(supplier.picker.root); links.addView(expense.picker.root)
        links.addView(ui.tv("קישור לספק או להוצאה מאפשר לפתוח אותם ישירות מהמשימה ולרשום תשלום.", TS.SMALL, p.text3))
        c.addView(links)

        val rem = ui.card(16, 16)
        reminderPicker = ui.picker("תזכורת", "ללא תזכורת", R.drawable.ic_bell) {
            act.chooseReminder(reminder) { spec -> reminder = spec; updateReminderLabel() }
        }
        rem.addView(reminderPicker.root)
        c.addView(rem)
        updateReminderLabel()

        val more = ui.card(16, 16)
        desc = ui.textField("תיאור", t?.description, lines = 2)
        notes = ui.textField("הערות", t?.notes, lines = 2)
        more.addView(desc.root); more.addView(notes.root)
        c.addView(more)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        initial = snapshot()
        return sv
    }

    private fun updateReminderLabel() {
        if (!::reminderPicker.isInitialized) return
        reminderPicker.set(reminder?.let { reminderLabel(it) })
        reminderPicker.error(if (reminder?.mode == ReminderMode.OFFSET && due.iso == null) "לתזכורת ביחס למועד צריך תאריך יעד" else null)
    }

    private fun snapshot() = listOf(titleF.value, due.iso, time.iso, priority, status, supplier.value, expense.value, desc.value, notes.value, reminder).joinToString("|")
    override fun isDirty() = ::titleF.isInitialized && snapshot() != initial

    private fun save() {
        titleF.error(null)
        if (titleF.clean == null) { titleF.error("יש למלא כותרת"); return }
        if (time.iso != null && due.iso == null) { due.picker.error("כדי להגדיר שעה יש לבחור תאריך"); return }
        if (reminder?.mode == ReminderMode.OFFSET && due.iso == null) { updateReminderLabel(); return }
        val input = TaskInput(titleF.value, desc.clean, due.iso, time.iso, priority, status, supplier.value, expense.value, null, notes.clean)
        val doSave = {
            ui.guard {
                var newId = id
                tx {
                    newId = repo.saveTask(input, id)
                    val old = repo.reminderFor(TargetType.TASK, newId!!)?.toSpec()
                    if (old != reminder) repo.setReminder(TargetType.TASK, newId!!, reminder)
                }
                initial = snapshot()
                ui.toast("המשימה נשמרה")
                if (id == null) act.replaceTop(TaskDetailScreen(act, newId!!)) else close()
            }
        }
        if (reminder != null) act.ensureNotificationPermission { doSave() } else doSave()
    }
}
