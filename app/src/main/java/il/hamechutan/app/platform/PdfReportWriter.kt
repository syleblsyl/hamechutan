package il.hamechutan.app.platform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import il.hamechutan.app.core.report.Report
import il.hamechutan.app.core.report.ReportSection
import il.hamechutan.app.core.report.ReportTable
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a [Report] to an A4 PDF with right-to-left Hebrew layout, using only the platform
 * PdfDocument API. Tables break across pages and repeat their header row.
 */
class PdfReportWriter {
    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val contentW = pageW - 2 * margin
    private val footerH = 28f

    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val navy = Color.rgb(0x1F, 0x3A, 0x5F)
    private val gold = Color.rgb(0x9A, 0x7A, 0x3F)
    private val text = Color.rgb(0x1B, 0x1F, 0x24)
    private val text2 = Color.rgb(0x5B, 0x64, 0x70)
    private val muted = Color.rgb(0x9A, 0xA0, 0xA8)
    private val headBg = Color.rgb(0xE3, 0xEA, 0xF3)
    private val zebra = Color.rgb(0xF7, 0xF5, 0xF0)
    private val hiBg = Color.rgb(0xFB, 0xEF, 0xD9)
    private val line = Color.rgb(0xD5, 0xD0, 0xC4)

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0
    private lateinit var report: Report

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun tp(size: Float, color: Int = text, b: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (b) bold else regular
    }

    /** Numbers are bidi-isolated so amounts like "-500 ₪" read correctly inside Hebrew text. */
    private val numberToken = Regex("-?\\d[\\d,./:\\-]*\\d|-?\\d")
    private fun iso(s: String): String = s.replace(numberToken) { "⁦${it.value}⁩" }

    private fun layout(s: String, paint: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        iso(s).let { t -> StaticLayout.Builder.obtain(t, 0, t.length, paint, width.toInt().coerceAtLeast(10)) }
            .setAlignment(align).setTextDirection(TextDirectionHeuristics.RTL).setLineSpacing(0f, 1.08f).setIncludePad(false).build()

    private fun draw(l: StaticLayout, x: Float, top: Float) {
        canvas.save(); canvas.translate(x, top); l.draw(canvas); canvas.restore()
    }

    fun write(r: Report, out: File) {
        report = r
        doc = PdfDocument()
        try {
            newPage(first = true)
            r.sections.forEach { section(it) }
            finishPage()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun newPage(first: Boolean = false) {
        finishPage()
        pageNo++
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
        page = doc.startPage(info)
        canvas = page!!.canvas
        canvas.drawColor(Color.WHITE)
        y = margin
        if (first) {
            val brand = layout("המחותן · ניהול חתונה", tp(9f, gold, true), contentW)
            draw(brand, margin, y); y += brand.height + 4
            val t = layout(report.title, tp(20f, navy, true), contentW)
            draw(t, margin, y); y += t.height + 4
            val s = layout(report.subtitle, tp(10f, text2), contentW)
            draw(s, margin, y); y += s.height + 8
            fill.color = gold; canvas.drawRect(margin, y, margin + contentW, y + 1.5f, fill)
            y += 14
        } else {
            val t = layout(report.title, tp(9f, text2, true), contentW)
            draw(t, margin, y); y += t.height + 6
            fill.color = line; canvas.drawRect(margin, y, margin + contentW, y + 0.7f, fill)
            y += 10
        }
    }

    private fun finishPage() {
        val pg = page ?: return
        val f = layout("עמוד $pageNo · הופק ב-${report.generatedAt}", tp(8f, muted), contentW, Layout.Alignment.ALIGN_CENTER)
        draw(f, margin, pageH - margin + 6)
        doc.finishPage(pg)
        page = null
    }

    private fun ensure(h: Float): Boolean {
        if (y + h > pageH - margin - footerH) { newPage(); return true }
        return false
    }

    private fun section(s: ReportSection) {
        s.heading?.let {
            val l = layout(it, tp(13f, navy, true), contentW)
            ensure(l.height + 40f)
            draw(l, margin, y); y += l.height + 3
            fill.color = gold; canvas.drawRect(margin + contentW - 40, y, margin + contentW, y + 1.2f, fill)
            y += 8
        }
        if (s.keyValues.isNotEmpty()) keyValues(s.keyValues)
        s.table?.let { if (it.rows.isEmpty() && s.emptyText != null) emptyLine(s.emptyText) else table(it) }
        if (s.table == null && s.keyValues.isEmpty() && s.emptyText != null) emptyLine(s.emptyText)
        s.note?.let {
            val l = layout(it, tp(8.5f, text2), contentW)
            ensure(l.height + 4f); draw(l, margin, y); y += l.height + 4
        }
        y += 12
    }

    private fun emptyLine(t: String) {
        val l = layout(t, tp(10f, text2), contentW)
        ensure(l.height + 6f); draw(l, margin, y); y += l.height + 6
    }

    private fun keyValues(kv: List<Pair<String, String>>) {
        val labelW = contentW * 0.6f
        val valueW = contentW * 0.4f
        kv.forEachIndexed { i, (k, v) ->
            val indent = k.startsWith("  ")
            val lk = layout(k.trim(), tp(10.5f, if (indent) text2 else text), labelW - (if (indent) 14 else 0))
            val lv = layout(v, tp(10.5f, text, !indent), valueW, Layout.Alignment.ALIGN_OPPOSITE)
            val h = maxOf(lk.height, lv.height) + 8f
            ensure(h)
            if (i % 2 == 0) { fill.color = zebra; canvas.drawRect(margin, y, margin + contentW, y + h, fill) }
            draw(lk, margin + valueW, y + 4)
            draw(lv, margin + 4, y + 4)
            y += h
        }
        y += 6
    }

    private fun table(t: ReportTable) {
        val total = t.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = t.columns.map { it.weight / total * contentW }
        // x-positions from the right edge (RTL: column 0 is rightmost)
        val rights = mutableListOf<Float>()
        var r = margin + contentW
        widths.forEach { w -> rights += r; r -= w }
        val pad = 4f

        fun rowLayouts(cells: List<String>, paint: TextPaint): List<StaticLayout> =
            cells.mapIndexed { i, c -> layout(c, paint, widths[i] - 2 * pad) }

        fun drawRow(ls: List<StaticLayout>, bg: Int?, borderTop: Boolean = false): Float {
            val h = (ls.maxOfOrNull { it.height } ?: 0) + 2 * pad + 2
            if (bg != null) { fill.color = bg; canvas.drawRect(margin, y, margin + contentW, y + h, fill) }
            if (borderTop) { fill.color = navy; canvas.drawRect(margin, y, margin + contentW, y + 0.9f, fill) }
            ls.forEachIndexed { i, l -> draw(l, rights[i] - widths[i] + pad, y + pad + 1) }
            fill.color = line; canvas.drawRect(margin, y + h - 0.4f, margin + contentW, y + h, fill)
            return h
        }

        val headPaint = tp(9.5f, navy, true)
        val head = rowLayouts(t.columns.map { it.title }, headPaint)
        val headH = (head.maxOfOrNull { it.height } ?: 0) + 2 * pad + 2
        ensure(headH + 24f)
        y += drawRow(head, headBg)
        t.rows.forEachIndexed { i, cells ->
            val paint = tp(9.5f, if (i in t.muted) muted else text)
            val ls = rowLayouts(cells, paint)
            val h = (ls.maxOfOrNull { it.height } ?: 0) + 2 * pad + 2
            if (ensure(h)) y += drawRow(head, headBg)
            y += drawRow(ls, when { i in t.highlight -> hiBg; i % 2 == 1 -> zebra; else -> null })
        }
        t.totals?.let { tot ->
            val ls = rowLayouts(tot, tp(9.5f, text, true))
            val h = (ls.maxOfOrNull { it.height } ?: 0) + 2 * pad + 2
            ensure(h)
            y += drawRow(ls, null, borderTop = true)
        }
        y += 4
    }

    @Suppress("unused")
    private fun box(rect: RectF, color: Int) { fill.color = color; canvas.drawRoundRect(rect, 6f, 6f, fill) }
}
