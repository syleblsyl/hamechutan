package il.hamechutan.app.ui.screens

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.model.SearchHit
import il.hamechutan.app.ui.*

class MoreScreen(act: MainActivity) : Screen(act) {
    override val title = "עוד"

    override fun build(): View {
        val (sv, c) = ui.page(24)
        val items = listOf(
            Triple("לוח שנה", R.drawable.ic_calendar) { push(CalendarScreen(act)) },
            Triple("הסעות", R.drawable.ic_bus) { push(TransportsScreen(act)) },
            Triple("מסמכים", R.drawable.ic_document) { push(DocumentsScreen(act)) },
            Triple("תקציב", R.drawable.ic_wallet) { push(BudgetScreen(act)) },
            Triple("דוחות PDF", R.drawable.ic_pdf) { push(ReportsScreen(act)) },
            Triple("חיפוש", R.drawable.ic_search) { push(SearchScreen(act)) },
            Triple("כל התשלומים", R.drawable.ic_payments) { push(PaymentsHistoryScreen(act)) },
            Triple("גיבוי ושחזור", R.drawable.ic_backup) { push(BackupScreen(act)) },
            Triple("הגדרות", R.drawable.ic_settings) { push(SettingsScreen(act)) }
        )
        items.chunked(3).forEach { rowItems ->
            val r = ui.row(Gravity.FILL_VERTICAL).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 10) }
            rowItems.forEachIndexed { i, (label, icon, action) ->
                if (i > 0) r.addView(ui.hspace(10))
                val tile = ui.card(8, 16, action).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = ui.lp(0, MATCH, 1f)
                    contentDescription = label
                }
                tile.addView(ui.iconBubble(icon, p.primary, p.primarySoft, 48), ui.lp(ui.dp(48), ui.dp(48), gravity = Gravity.CENTER_HORIZONTAL))
                tile.addView(ui.tv(label, TS.CAPTION_STRONG, p.text, 2).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(MATCH, WRAP, top = 8))
                r.addView(tile)
            }
            repeat(3 - rowItems.size) { r.addView(ui.hspace(10)); r.addView(View(act), ui.lp(0, 1, 1f)) }
            c.addView(r)
        }
        val last = app.prefs.lastBackupAt
        c.addView(ui.banner(
            if (last == 0L) "עדיין לא נוצר גיבוי. כל המידע נשמר רק בטלפון — מומלץ ליצור גיבוי ולשמור אותו מחוץ למכשיר."
            else "גיבוי אחרון: ${il.hamechutan.app.core.util.Dates.displayDateTime(last)}",
            if (last == 0L || System.currentTimeMillis() - last > 14L * 86_400_000) Tone.WARNING else Tone.SUCCESS, R.drawable.ic_backup,
            "לגיבוי ושחזור") { push(BackupScreen(act)) }, ui.lp(MATCH, WRAP, top = 6))
        return sv
    }
}

class SearchScreen(act: MainActivity) : Screen(act) {
    override val title = "חיפוש"
    private var query = ""
    private var kind: String? = null

    private val kinds = linkedMapOf(
        "SUPPLIER" to "ספקים", "EXPENSE" to "הוצאות", "PAYMENT" to "תשלומים", "TASK" to "משימות",
        "EVENT" to "אירועים", "TRANSPORT" to "הסעות", "DOCUMENT" to "מסמכים"
    )

    override fun build(): View {
        val (sv, c) = ui.page(24)
        val field = ui.textField("מה לחפש?", query, "שם ספק, טלפון, משימה, נהג, מסמך, הערה…")
        c.addView(field.root)
        val chipsBox = ui.col()
        val results = ui.col()
        c.addView(chipsBox)
        c.addView(results)
        fun render() {
            chipsBox.removeAllViews(); results.removeAllViews()
            val hits = repo.search(query)
            if (query.isBlank()) {
                results.addView(ui.tv("החיפוש פועל על כל המידע באפליקציה, ללא אינטרנט.", TS.CAPTION, p.text2), ui.lp(top = 8))
                return
            }
            val counts = hits.groupingBy { it.kind }.eachCount()
            val chips = mutableListOf<View>(ui.chip("הכל", kind == null, hits.size) { kind = null; render() })
            kinds.forEach { (k, label) -> counts[k]?.let { n -> chips += ui.chip(label, kind == k, n) { kind = k; render() } } }
            chipsBox.addView(ui.chipRow(chips).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }.also { it.setPadding(0, 0, 0, 0) })
            val shown = hits.filter { kind == null || it.kind == kind }
            if (shown.isEmpty()) { results.addView(ui.emptyState(R.drawable.ic_search, "לא נמצאו תוצאות", "נסו מילה אחרת או חלק מהמילה.")); return }
            shown.groupBy { it.kind }.forEach { (k, l) ->
                results.addView(ui.sectionHeader("${kinds[k]} (${l.size})"))
                listInCard(results, l.map { hitRow(it) })
            }
        }
        render()
        field.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; render() }
        })
        if (query.isEmpty()) field.edit.requestFocus()
        return sv
    }

    private fun hitRow(h: SearchHit): View {
        val icon = when (h.kind) {
            "SUPPLIER" -> R.drawable.ic_person; "EXPENSE" -> R.drawable.ic_receipt; "PAYMENT" -> R.drawable.ic_payments
            "TASK" -> R.drawable.ic_tasks; "EVENT" -> R.drawable.ic_calendar; "TRANSPORT" -> R.drawable.ic_bus; else -> R.drawable.ic_document
        }
        return ui.listRow(h.title, h.subtitle, icon, chevron = true) {
            when (h.kind) {
                "SUPPLIER" -> push(SupplierDetailScreen(act, h.id))
                "EXPENSE" -> push(ExpenseDetailScreen(act, h.id))
                "PAYMENT" -> push(PaymentDetailScreen(act, h.id))
                "TASK" -> push(TaskDetailScreen(act, h.id))
                "EVENT" -> push(EventDetailScreen(act, h.id))
                "TRANSPORT" -> push(TransportDetailScreen(act, h.id))
                "DOCUMENT" -> push(DocumentDetailScreen(act, h.id))
            }
        }
    }
}
