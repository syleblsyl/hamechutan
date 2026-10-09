package il.hamechutan.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.report.ReportBuilder
import il.hamechutan.app.core.report.ReportType
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.platform.PdfReportWriter
import il.hamechutan.app.ui.*
import java.io.File
import java.time.LocalDate

class ReportsScreen(act: MainActivity) : Screen(act) {
    override val title = "דוחות PDF"
    override val keepView = true
    private var type = ReportType.FINANCIAL_SUMMARY
    private lateinit var from: Ui.DateInput
    private lateinit var to: Ui.DateInput
    private lateinit var rangeBox: LinearLayout
    private lateinit var typeBox: LinearLayout

    override fun build(): View {
        val (sv, c) = ui.page(32)
        c.addView(ui.tv("הדוחות מופקים מהנתונים השמורים באפליקציה, בעברית, וניתנים לשמירה במכשיר ולשיתוף.", TS.CAPTION, p.text2), ui.lp(bottom = 12))
        typeBox = ui.listCard()
        c.addView(typeBox)
        rangeBox = ui.card(16, 14)
        rangeBox.addView(ui.tv("טווח תאריכים", TS.SUBTITLE), ui.lp(bottom = 8))
        val presets = listOf("הכל", "30 ימים אחרונים", "החודש", "90 הימים הבאים")
        rangeBox.addView(ui.chipRow(presets.mapIndexed { i, s -> ui.chip(s, false) { preset(i) } }).also { it.setPadding(0, 0, 0, 0); (it.getChildAt(0) as LinearLayout).setPadding(0, 0, 0, 0) }, ui.lp(MATCH, WRAP, bottom = 12))
        from = ui.dateField("מתאריך", null)
        to = ui.dateField("עד תאריך", null)
        rangeBox.addView(from.picker.root); rangeBox.addView(to.picker.root)
        c.addView(rangeBox)
        renderTypes()
        c.addView(ui.button("יצירת PDF", BtnKind.PRIMARY, R.drawable.ic_pdf) { generate() })
        return sv
    }

    private fun renderTypes() {
        typeBox.removeAllViews()
        ReportType.values().forEachIndexed { i, t ->
            if (i > 0) typeBox.addView(ui.divider(16))
            val sel = t == type
            typeBox.addView(ui.listRow(t.label, t.description, if (sel) R.drawable.ic_check_circle else R.drawable.ic_circle, if (sel) Tone.SUCCESS else Tone.NEUTRAL,
                titleColor = if (sel) p.primary else p.text) { type = t; renderTypes() })
        }
        rangeBox.visibility = if (type.usesRange) View.VISIBLE else View.GONE
    }

    private fun preset(i: Int) {
        val today = repo.today()
        when (i) {
            0 -> { from.set(null); to.set(null) }
            1 -> { from.set(Dates.iso(today.minusDays(30))); to.set(Dates.iso(today)) }
            2 -> { from.set(Dates.iso(today.withDayOfMonth(1))); to.set(Dates.iso(today.withDayOfMonth(today.lengthOfMonth()))) }
            3 -> { from.set(Dates.iso(today)); to.set(Dates.iso(today.plusDays(90))) }
        }
    }

    private fun generate() {
        val f = if (type.usesRange) Dates.parseDate(from.iso) else null
        val t = if (type.usesRange) Dates.parseDate(to.iso) else null
        if (f != null && t != null && f.isAfter(t)) { to.picker.error("תאריך הסיום לפני תאריך ההתחלה"); return }
        to.picker.error(null)
        ui.toast("מפיק את הדוח…")
        val rt = type
        app.background({
            val report = ReportBuilder(repo).build(rt, f, t)
            val file = File(app.files.sharedDir, "report_${rt.name.lowercase()}_${System.currentTimeMillis()}.pdf")
            PdfReportWriter().write(report, file)
            file to report.title
        }) { r ->
            r.onSuccess { (file, title) -> push(PdfPreviewScreen(act, file, "$title ${Dates.iso(LocalDate.now())}")) }
                .onFailure { e -> ui.alert("לא ניתן להפיק את הדוח", "${e.javaClass.simpleName}: ${e.message ?: ""}") }
        }
    }
}

class PdfPreviewScreen(act: MainActivity, private val file: File, private val displayName: String) : Screen(act) {
    override val title = "תצוגת הדוח"
    override val keepView = true

    override fun actions() = listOf(TopAction(R.drawable.ic_share, "שיתוף") { share() })

    override fun build(): View {
        val (sv, c) = ui.page(32)
        val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }
        r.addView(ui.button("שמירה במכשיר", BtnKind.PRIMARY, R.drawable.ic_save, small = true) { save() }, ui.lp(0, WRAP, 1f))
        r.addView(ui.hspace(8))
        r.addView(ui.button("שיתוף", BtnKind.SECONDARY, R.drawable.ic_share, small = true) { share() }, ui.lp(0, WRAP, 1f))
        c.addView(r)
        c.addView(Previews.pdfPages(act, file, 8))
        return sv
    }

    private fun share() = DocumentFlows.share(act, app.files.uriForShared(file, displayName), "application/pdf", displayName)

    private fun save() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "$displayName.pdf")
        act.launchForResult(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launchForResult
            app.background({
                (act.contentResolver.openOutputStream(uri) ?: error("לא ניתן לכתוב לקובץ")).use { out -> file.inputStream().use { it.copyTo(out) } }
            }) { r -> if (r.isSuccess) ui.toast("הדוח נשמר") else ui.alert("השמירה נכשלה", r.exceptionOrNull()?.message ?: "") }
        }
    }
}
