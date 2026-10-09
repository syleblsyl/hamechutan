package il.hamechutan.app.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.HebrewDate
import il.hamechutan.app.core.util.Money
import il.hamechutan.app.ui.*

class HomeScreen(act: MainActivity) : Screen(act) {
    override val title = "המחותן"
    override val subtitle: String get() = repo.wedding().title

    override fun actions() = listOf(
        TopAction(R.drawable.ic_search, "חיפוש") { push(SearchScreen(act)) },
        TopAction(R.drawable.ic_settings, "הגדרות") { push(SettingsScreen(act)) }
    )

    override fun build(): View {
        val d = repo.dashboard()
        val (sv, c) = ui.page(24)
        header(c, d)

        if (d.isEmpty && !app.prefs.welcomeDismissed) welcome(c)
        if (act.crashReportPending()) c.addView(ui.banner("האפליקציה נסגרה באופן לא צפוי בפעם הקודמת. אפשר לשתף את פרטי התקלה מתוך ההגדרות.", Tone.WARNING, R.drawable.ic_warning, "פתיחת ההגדרות") { push(SettingsScreen(act)) })

        val overdue = d.buckets[TaskBucket.OVERDUE].orEmpty()
        if (overdue.isNotEmpty()) c.addView(ui.banner("${if (overdue.size == 1) "משימה אחת" else "${overdue.size} משימות"} באיחור", Tone.DANGER, R.drawable.ic_warning, "להצגת המשימות") { act.openTab(Tab.TASKS) })
        val overduePay = d.upcomingPayments.filter { it.overdue }
        if (overduePay.isNotEmpty()) c.addView(ui.banner("${overduePay.size} תשלומים מתוכננים שמועדם עבר", Tone.WARNING, R.drawable.ic_payments))

        finance(c, d)
        quickActions(c)
        tasks(c, d)
        payments(c, d)
        agenda(c, d)
        return sv
    }

    private fun header(c: LinearLayout, d: Dashboard) {
        val w = d.wedding
        val card = ui.card(18, 16, { push(WeddingEditScreen(act)) }, p.primary, null)
        val top = ui.row()
        val texts = ui.col()
        texts.addView(ui.tv(w.title, TS.TITLE, p.onPrimary, 2))
        val wd = Dates.parseDate(w.date)
        if (wd != null) {
            texts.addView(ui.tv(Dates.displayWithDay(wd) + (if (ui.hebrewDates) " · " + HebrewDate.fromGregorian(wd).format() else ""), TS.CAPTION, p.onPrimary).apply { alpha = 0.85f }, ui.lp(top = 4))
        } else {
            texts.addView(ui.tv("לחצו כאן כדי להגדיר תאריך ומקום לחתונה", TS.CAPTION, p.onPrimary).apply { alpha = 0.85f }, ui.lp(top = 4))
        }
        w.venue?.let { texts.addView(ui.tv(it, TS.CAPTION, p.onPrimary).apply { alpha = 0.85f }, ui.lp(top = 2)) }
        top.addView(texts, ui.lp(0, WRAP, 1f))
        if (wd != null) {
            val days = Dates.daysBetween(repo.today(), wd)
            val box = ui.col().apply {
                gravity = Gravity.CENTER
                background = ui.shape(p.gold, 14f)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            }
            when {
                days > 0 -> {
                    box.addView(ui.tv(days.toString(), TS.AMOUNT_L, 0xFFFFFFFF.toInt()).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
                    box.addView(ui.tv(if (days == 1L) "יום" else "ימים", TS.SMALL, 0xFFFFFFFF.toInt()).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
                }
                days == 0L -> box.addView(ui.tv("היום!", TS.SUBTITLE, 0xFFFFFFFF.toInt()))
                else -> box.addView(ui.tv("מזל טוב", TS.CAPTION_STRONG, 0xFFFFFFFF.toInt()))
            }
            top.addView(box, ui.lp(WRAP, WRAP, start = 12))
        }
        card.addView(top)
        c.addView(card)
    }

    private fun welcome(c: LinearLayout) {
        val card = ui.card(18, 16, color = p.goldSoft, stroke = null)
        card.addView(ui.tv("ברוכים הבאים למחותן", TS.SUBTITLE))
        card.addView(ui.tv("כדאי להתחיל בהגדרת תקציב, הוספת הספקים שכבר סגרתם איתם ורשימת המשימות. כל המידע נשמר בטלפון בלבד ואינו נשלח לשום מקום.", TS.CAPTION, p.text2), ui.lp(top = 6))
        val r1 = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 12) }
        r1.addView(ui.button("הגדרת תקציב", BtnKind.PRIMARY, small = true) { push(BudgetScreen(act)) }, ui.lp(0, WRAP, 1f))
        r1.addView(ui.hspace(8))
        r1.addView(ui.button("הוספת ספק", BtnKind.SECONDARY, small = true) { push(SupplierEditScreen(act, null)) }, ui.lp(0, WRAP, 1f))
        card.addView(r1)
        val r2 = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 8) }
        r2.addView(ui.button("הוספת משימה", BtnKind.SECONDARY, small = true) { push(TaskEditScreen(act, null)) }, ui.lp(0, WRAP, 1f))
        r2.addView(ui.hspace(8))
        r2.addView(ui.button("לא עכשיו", BtnKind.TEXT, small = true) { app.prefs.welcomeDismissed = true; refresh() }, ui.lp(0, WRAP, 1f))
        card.addView(r2)
        c.addView(card)
    }

    private fun finance(c: LinearLayout, d: Dashboard) {
        val t = d.totals
        c.addView(ui.sectionHeader("מצב כספי", "תקציב ופירוט") { push(BudgetScreen(act)) })
        if (t.overBudget) c.addView(ui.banner("חריגה מהתקציב ב-${money(-(t.budgetLeftAgorot ?: 0))}", Tone.DANGER, R.drawable.ic_warning))
        val budget = t.budgetAgorot
        c.addView(ui.tileRow(
            ui.statTile("תקציב כולל", budget?.let { money(it) } ?: "לא הוגדר",
                budget?.let { b -> if (t.committedAgorot <= b) "נותרו ${money(b - t.committedAgorot)} לא מתוכננים" else "חריגה" } ?: "לחצו להגדרה",
                if (budget == null) p.text3 else p.text, R.drawable.ic_wallet) { push(BudgetScreen(act)) },
            ui.statTile("סך התחייבויות", money(t.committedAgorot), "${t.activeExpenseCount} התחייבויות פעילות", p.text, R.drawable.ic_receipt) { act.openTab(Tab.EXPENSES) }
        ))
        c.addView(ui.tileRow(
            ui.statTile("שולם בפועל", money(t.paidAgorot), "${Money.percent(t.paidAgorot, t.committedAgorot)}% מההתחייבויות", p.success, R.drawable.ic_check_circle) { push(PaymentsHistoryScreen(act)) },
            ui.statTile("יתרה לתשלום", money(t.remainingAgorot), if (t.creditAgorot > 0) "זכות אצל ספקים: ${money(t.creditAgorot)}" else "${t.openCommitmentsCount} התחייבויות פתוחות",
                if (t.remainingAgorot > 0) p.warning else p.text, R.drawable.ic_payments) { act.openTab(Tab.EXPENSES) }
        ))
        val up = d.upcomingPayments
        c.addView(ui.tileRow(
            ui.statTile("לספקים", money(t.committedSuppliersAgorot), "הוצאות ללא ספק: ${money(t.committedOtherAgorot)}", p.text, R.drawable.ic_suppliers) { act.openTab(Tab.SUPPLIERS) },
            ui.statTile("תשלומים קרובים", if (up.isEmpty()) "אין" else money(up.sumOf { it.amountAgorot }),
                if (up.isEmpty()) "ב-30 הימים הקרובים" else "${up.size} תשלומים · 30 יום", if (up.any { it.overdue }) p.danger else p.text, R.drawable.ic_calendar) { push(CalendarScreen(act)) }
        ))
        if (budget != null && budget > 0) {
            val box = ui.card(16, 12)
            val r = ui.row()
            r.addView(ui.tv("ניצול התקציב", TS.CAPTION_STRONG, p.text2), ui.lp(0, WRAP, 1f))
            r.addView(ui.tv("${Money.percent(t.committedAgorot, budget)}% מתוכנן · ${Money.percent(t.paidAgorot, budget)}% שולם", TS.CAPTION, p.text2))
            box.addView(r)
            box.addView(ui.progressBar(t.paidAgorot.toFloat() / budget, t.committedAgorot.toFloat() / budget,
                p.success, if (t.overBudget) p.dangerSoft else p.primarySoft, height = 10), ui.lp(MATCH, ui.dp(10), top = 8))
            c.addView(box)
        }
    }

    private fun quickActions(c: LinearLayout) {
        c.addView(ui.sectionHeader("פעולות מהירות"))
        val items = listOf(
            Triple("הוספת הוצאה", R.drawable.ic_receipt) { push(ExpenseEditScreen(act, null)) },
            Triple("הוספת ספק", R.drawable.ic_suppliers) { push(SupplierEditScreen(act, null)) },
            Triple("רישום תשלום", R.drawable.ic_payments) { pickExpenseForPayment() },
            Triple("הוספת משימה", R.drawable.ic_tasks) { push(TaskEditScreen(act, null)) },
            Triple("אירוע ביומן", R.drawable.ic_calendar) { push(EventEditScreen(act, null)) },
            Triple("הוספת הסעה", R.drawable.ic_bus) { push(TransportEditScreen(act, null)) },
            Triple("צילום / מסמך", R.drawable.ic_camera) { DocumentFlows.start(act, DocLink()) },
            Triple("חיפוש", R.drawable.ic_search) { push(SearchScreen(act)) }
        )
        val card = ui.card(6, 10)
        items.chunked(4).forEach { rowItems ->
            val r = ui.row(Gravity.TOP)
            rowItems.forEach { (label, icon, action) ->
                val cell = ui.col().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8))
                    background = ui.ripple(null, 12f)
                    isClickable = true
                    contentDescription = label
                    setOnClickListener { action() }
                }
                cell.addView(ui.iconBubble(icon, p.primary, p.primarySoft, 46), ui.lp(ui.dp(46), ui.dp(46), gravity = Gravity.CENTER_HORIZONTAL))
                cell.addView(ui.tv(label, TS.SMALL, p.text, 2).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(MATCH, WRAP, top = 6))
                r.addView(cell, ui.lp(0, WRAP, 1f))
            }
            card.addView(r)
        }
        c.addView(card)
    }

    private fun pickExpenseForPayment() {
        val open = repo.expenses().filter { !it.cancelled && it.remainingAgorot > 0 }
        if (open.isEmpty()) {
            ui.alert("אין התחייבויות פתוחות", "כדי לרשום תשלום צריך קודם הוצאה או ספק עם מחיר שסוכם ויתרה לתשלום.")
            return
        }
        ui.options("תשלום עבור…", open.map { e ->
            listOfNotNull(e.expense.name, e.supplierName?.takeIf { it != e.expense.name }).joinToString(" · ") + " — יתרה " + Money.format(e.remainingAgorot, isolate = false)
        }) { i -> push(PaymentEditScreen(act, open[i].id, null)) }
    }

    private fun tasks(c: LinearLayout, d: Dashboard) {
        c.addView(ui.sectionHeader("מה נשאר לעשות", "כל המשימות") { act.openTab(Tab.TASKS) })
        val shown = listOf(TaskBucket.OVERDUE, TaskBucket.URGENT, TaskBucket.TODAY, TaskBucket.WEEK)
        var any = false
        shown.forEach { b ->
            val list = d.buckets[b].orEmpty()
            if (list.isEmpty()) return@forEach
            any = true
            val tone = when (b) { TaskBucket.OVERDUE, TaskBucket.URGENT -> Tone.DANGER; TaskBucket.TODAY -> Tone.WARNING; else -> Tone.INFO }
            val head = ui.row().apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6)) }
            head.addView(ui.badge("${b.label} · ${list.size}", tone))
            c.addView(head)
            val rows = list.take(4).map { taskRow(it) }.toMutableList<View>()
            if (list.size > 4) rows += ui.listRow("ועוד ${list.size - 4} משימות…", null, chevron = true, titleColor = p.primary) { act.openTab(Tab.TASKS) }
            listInCard(c, rows)
        }
        if (!any) {
            val later = d.buckets[TaskBucket.LATER].orEmpty().size
            c.addView(ui.card().apply {
                addView(ui.tv(if (d.openTaskCount == 0) "אין משימות פתוחות." else "אין משימות דחופות או לשבוע הקרוב.", TS.BODY))
                if (later > 0) addView(ui.tv("$later משימות מתוכננות בהמשך", TS.CAPTION, p.text2), ui.lp(top = 2))
                addView(ui.button("הוספת משימה", BtnKind.TONAL, R.drawable.ic_add, small = true) { push(TaskEditScreen(act, null)) }, ui.lp(WRAP, WRAP, top = 10))
            })
        }
    }

    private fun payments(c: LinearLayout, d: Dashboard) {
        if (d.upcomingPayments.isEmpty()) return
        c.addView(ui.sectionHeader("תשלומים קרובים"))
        listInCard(c, d.upcomingPayments.take(5).map { u ->
            val date = Dates.parseDate(u.date)!!
            ui.listRow(u.expense.expense.name, listOfNotNull(Dates.relative(date, repo.today()) + " · " + Dates.display(date), u.expense.supplierName).joinToString(" · "),
                R.drawable.ic_payments, if (u.overdue) Tone.DANGER else Tone.GOLD,
                ui.trailingAmount(money(u.amountAgorot), if (u.overdue) p.danger else p.text, if (u.overdue) "באיחור" else null)) {
                push(ExpenseDetailScreen(act, u.expense.id))
            }
        })
    }

    private fun agenda(c: LinearLayout, d: Dashboard) {
        if (d.agenda.isEmpty()) return
        c.addView(ui.sectionHeader("השבוע ביומן", "ללוח השנה") { push(CalendarScreen(act)) })
        listInCard(c, d.agenda.take(6).map { calendarRow(it) })
    }
}

/** A calendar item row, opening the underlying record. */
fun Screen.calendarRow(it: CalendarItem, showDate: Boolean = true): View {
    val icon = when (it.type) {
        CalendarItemType.EVENT -> R.drawable.ic_calendar
        CalendarItemType.TASK -> R.drawable.ic_tasks
        CalendarItemType.TRANSPORT -> R.drawable.ic_bus
        CalendarItemType.PAYMENT_DUE -> R.drawable.ic_payments
        CalendarItemType.KEY_DATE -> R.drawable.ic_label
        else -> R.drawable.ic_rings
    }
    val tone = when (it.type) {
        CalendarItemType.PAYMENT_DUE -> Tone.GOLD
        CalendarItemType.TRANSPORT -> Tone.SUCCESS
        CalendarItemType.WEDDING -> Tone.GOLD
        CalendarItemType.TASK -> Tone.WARNING
        else -> Tone.INFO
    }
    val d = Dates.parseDate(it.date)!!
    val whenText = listOfNotNull(
        if (showDate) Dates.relative(d, repo.today()).let { r -> if (kotlin.math.abs(Dates.daysBetween(repo.today(), d)) <= 2) r else Dates.dayShort(d) + " " + Dates.display(d) } else null,
        it.time?.let { t -> Dates.displayTime(t) }
    ).joinToString(" · ")
    val sub = listOfNotNull(whenText.ifEmpty { null }, it.subtitle).joinToString(" · ")
    val trailing = when {
        it.cancelled -> ui.badge("בוטל", Tone.NEUTRAL)
        it.done -> ui.badge("הושלם", Tone.SUCCESS)
        else -> ui.badge(it.type.label, tone)
    }
    return ui.listRow(it.title, sub, icon, tone, trailing, titleColor = if (it.done || it.cancelled) p.text3 else p.text) {
        when (it.type) {
            CalendarItemType.EVENT -> push(EventDetailScreen(act, it.id))
            CalendarItemType.TASK -> push(TaskDetailScreen(act, it.id))
            CalendarItemType.TRANSPORT -> push(TransportDetailScreen(act, it.id))
            CalendarItemType.PAYMENT_DUE, CalendarItemType.KEY_DATE -> push(ExpenseDetailScreen(act, it.id))
            else -> push(WeddingEditScreen(act))
        }
    }
}
