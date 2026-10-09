package il.hamechutan.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.platform.DocFiles
import il.hamechutan.app.ui.*
import org.json.JSONObject
import java.io.File

/** What a new document should be attached to. */
data class DocLink(
    val supplierId: Long? = null, val expenseId: Long? = null, val paymentId: Long? = null,
    val taskId: Long? = null, val eventId: Long? = null, val transportId: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        supplierId?.let { put("s", it) }; expenseId?.let { put("e", it) }; paymentId?.let { put("p", it) }
        taskId?.let { put("t", it) }; eventId?.let { put("v", it) }; transportId?.let { put("r", it) }
    }
    companion object {
        fun fromJson(o: JSONObject) = DocLink(
            o.optLong("s").takeIf { o.has("s") }, o.optLong("e").takeIf { o.has("e") }, o.optLong("p").takeIf { o.has("p") },
            o.optLong("t").takeIf { o.has("t") }, o.optLong("v").takeIf { o.has("v") }, o.optLong("r").takeIf { o.has("r") }
        )
    }
}

object DocumentFlows {

    fun start(act: MainActivity, link: DocLink, type: DocType? = null) {
        act.ui.options("הוספת מסמך", listOf("צילום במצלמה", "בחירת תמונה מהגלריה", "בחירת קובץ (PDF ועוד)")) { i ->
            when (i) {
                0 -> camera(act, link, type)
                1 -> pickImage(act, link, type)
                2 -> pickFile(act, link, type)
            }
        }
    }

    fun camera(act: MainActivity, link: DocLink, type: DocType?) {
        val files = act.app.files
        val f = files.newCameraFile()
        val uri = files.uriForCamera(f)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("photo", uri)
        // Persisted so the capture can be completed even if Android kills the app while the camera is open.
        act.app.prefs.pendingCamera = JSONObject().put("file", f.path).put("link", link.toJson()).put("type", type?.name ?: "").toString()
        act.launchForResult(intent, MainActivity.REQ_CAMERA) { code, _ ->
            act.app.prefs.pendingCamera = null
            if (code == Activity.RESULT_OK) importCamera(act, f, link, type) else f.delete()
        }
    }

    fun completeCameraFromPending(act: MainActivity, pending: String) {
        try {
            val o = JSONObject(pending)
            val f = File(o.getString("file"))
            if (!f.exists() || f.length() == 0L) return
            importCamera(act, f, DocLink.fromJson(o.getJSONObject("link")), o.optString("type").takeIf { it.isNotEmpty() }?.let { DocType.of(it) })
        } catch (e: Exception) { android.util.Log.e("HaMechutan", "pending camera", e) }
    }

    private fun importCamera(act: MainActivity, f: File, link: DocLink, type: DocType?) {
        act.ui.toast("שומר את התמונה…")
        act.app.background({ act.app.files.importCamera(f) }) { r ->
            r.onSuccess { imp -> act.push(DocumentEditScreen(act, null, imp, link, type ?: DocType.RECEIPT)) }
                .onFailure { e -> act.ui.alert("לא ניתן לשמור את התמונה", e.message ?: "") }
        }
    }

    fun pickImage(act: MainActivity, link: DocLink, type: DocType?) {
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        launchPick(act, intent, link, type ?: DocType.RECEIPT)
    }

    fun pickFile(act: MainActivity, link: DocLink, type: DocType?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        launchPick(act, intent, link, type)
    }

    private fun launchPick(act: MainActivity, intent: Intent, link: DocLink, type: DocType?) {
        act.launchForResult(intent) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launchForResult
            act.ui.toast("מעתיק את הקובץ…")
            act.app.background({ act.app.files.importUri(uri) }) { r ->
                r.onSuccess { imp ->
                    val t = type ?: if (imp.mime?.startsWith("image/") == true) DocType.RECEIPT else if (imp.mime == "application/pdf") DocType.INVOICE else DocType.OTHER
                    act.push(DocumentEditScreen(act, null, imp, link, t))
                }.onFailure { e -> act.ui.alert("לא ניתן לצרף את הקובץ", e.message ?: "") }
            }
        }
    }

    fun openExternal(act: MainActivity, d: DocumentRec) {
        val uri = act.app.files.uriForDoc(d.fileName, d.title)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType ?: act.app.files.mimeFor(d.fileName) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(d.title, uri)
        act.launch(Intent.createChooser(i, "פתיחה באמצעות"), "לא נמצאה במכשיר אפליקציה שיכולה לפתוח קובץ מסוג זה.")
    }

    fun share(act: MainActivity, uri: Uri, mime: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(title, uri)
        act.launch(Intent.createChooser(i, "שיתוף"))
    }
}

/** Renders PDF pages (or an image) into a column using platform renderers only. */
object Previews {
    fun pdfPages(act: MainActivity, file: File, maxPages: Int = 6): View {
        val ui = act.ui
        val col = ui.col()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val width = (act.resources.displayMetrics.widthPixels - ui.dp(32)).coerceIn(400, 900)
                    for (i in 0 until minOf(r.pageCount, maxPages)) {
                        r.openPage(i).use { page ->
                            val h = (width.toFloat() * page.height / page.width).toInt()
                            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            col.addView(ImageView(act).apply {
                                setImageBitmap(bmp); adjustViewBounds = true
                                background = ui.shape(Color.WHITE, 4f, ui.p.outline)
                                setPadding(1, 1, 1, 1)
                            }, ui.lp(MATCH, WRAP, bottom = 10))
                        }
                    }
                    if (r.pageCount > maxPages) col.addView(ui.tv("מוצגים $maxPages עמודים ראשונים מתוך ${r.pageCount}. לצפייה במסמך המלא: פתיחה באפליקציה חיצונית או שיתוף.", TS.CAPTION, ui.p.text2))
                }
            }
        } catch (e: Exception) {
            col.addView(ui.banner("לא ניתן להציג את קובץ ה-PDF בתוך האפליקציה. אפשר לפתוח אותו באפליקציה חיצונית.", Tone.WARNING, R.drawable.ic_warning))
        }
        return col
    }
}

fun Screen.docRow(dv: DocumentView, showLink: Boolean = true): View {
    val d = dv.doc
    val r = ui.row().apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); minimumHeight = ui.dp(64) }
    val thumb = if (d.isImage) app.files.thumbnail(d.fileName, ui.dp(48)) else null
    if (thumb != null) {
        r.addView(ImageView(act).apply {
            setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.shape(p.surfaceAlt, 10f); clipToOutline = true
        }, ui.lp(ui.dp(48), ui.dp(48), end = 12))
    } else {
        r.addView(ui.iconBubble(if (d.isPdf) R.drawable.ic_pdf else if (d.isImage) R.drawable.ic_image else R.drawable.ic_document, p.gold, p.goldSoft, 48),
            ui.lp(ui.dp(48), ui.dp(48), end = 12))
    }
    val mid = ui.col()
    mid.addView(ui.tv(d.title, TS.BODY_STRONG, p.text, 2))
    mid.addView(ui.tv(listOfNotNull(d.type.label, Dates.display(d.docDate ?: Dates.iso(Dates.fromMillis(d.createdAt).toLocalDate()))).joinToString(" · "), TS.CAPTION, p.text2))
    if (showLink && dv.linkLabel.isNotEmpty()) mid.addView(ui.tv(dv.linkLabel, TS.SMALL, p.text3, 2))
    if (!app.files.file(d.fileName).exists()) mid.addView(ui.tv("הקובץ חסר במכשיר", TS.SMALL, p.danger))
    r.addView(mid, ui.lp(0, WRAP, 1f))
    r.background = ui.ripple(null, 0f)
    r.isClickable = true
    r.setOnClickListener { push(DocumentDetailScreen(act, d.id)) }
    return r
}

fun Screen.documentsSection(c: LinearLayout, docs: List<DocumentView>, link: DocLink, title: String = "מסמכים וקבלות", type: DocType? = null) {
    c.addView(ui.sectionHeader("$title${if (docs.isNotEmpty()) " (${docs.size})" else ""}", "הוספה") { DocumentFlows.start(act, link, type) })
    if (docs.isEmpty()) {
        val card = ui.card()
        card.addView(ui.tv("אין מסמכים מצורפים.", TS.CAPTION, p.text2))
        val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, top = 10) }
        r.addView(ui.button("צילום", BtnKind.TONAL, R.drawable.ic_camera, small = true) { DocumentFlows.camera(act, link, type) }, ui.lp(0, WRAP, 1f))
        r.addView(ui.hspace(8))
        r.addView(ui.button("קובץ / תמונה", BtnKind.SECONDARY, R.drawable.ic_attach, small = true) { DocumentFlows.start(act, link, type) }, ui.lp(0, WRAP, 1f))
        card.addView(r)
        c.addView(card)
    } else listInCard(c, docs.map { docRow(it, showLink = false) })
}

class DocumentsScreen(act: MainActivity) : Screen(act) {
    override val title = "מסמכים"
    private var query = ""
    private var type: DocType? = null
    private var listBox: LinearLayout? = null

    override fun fab() = Fab(R.drawable.ic_add, "הוספה") { DocumentFlows.start(act, DocLink()) }

    override fun build(): View {
        val all = repo.documents()
        val (sv, c) = ui.page()
        if (all.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_document, "אין מסמכים", "צלמו קבלות, חוזים והצעות מחיר וצרפו אותם לספק, להוצאה או לתשלום. הקבצים נשמרים בטלפון ונכללים בגיבוי.", "הוספת מסמך") {
                DocumentFlows.start(act, DocLink())
            })
            return sv
        }
        val search = ui.textField("חיפוש", query, "שם מסמך, ספק, הוצאה…")
        search.root.layoutParams = ui.lp(MATCH, WRAP, bottom = 8)
        c.addView(search.root)
        val chips = mutableListOf<View>(ui.chip("הכל", type == null, all.size) { type = null; refresh() })
        DocType.values().forEach { t -> val n = all.count { it.doc.type == t }; if (n > 0) chips += ui.chip(t.label, type == t, n) { type = t; refresh() } }
        c.addView(ui.chipRow(chips).apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 12) }.also { it.setPadding(0, 0, 0, 0) })
        val box = ui.col()
        listBox = box
        c.addView(box)
        fun fill() {
            box.removeAllViews()
            val q = query.trim()
            val l = all.filter { (type == null || it.doc.type == type) &&
                (q.isEmpty() || listOfNotNull(it.doc.title, it.doc.notes, it.doc.originalName, it.linkLabel).any { s -> s.contains(q, true) }) }
            listInCard(box, l.map { docRow(it) }, "לא נמצאו מסמכים.")
        }
        fill()
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; fill() }
        })
        return sv
    }
}

class DocumentDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "מסמך"

    override fun actions() = listOf(
        TopAction(R.drawable.ic_share, "שיתוף") {
            repo.document(id)?.doc?.let { d -> DocumentFlows.share(act, app.files.uriForDoc(d.fileName, d.title), d.mimeType ?: "*/*", d.title) }
        },
        TopAction(R.drawable.ic_edit, "עריכה") { repo.document(id)?.let { push(DocumentEditScreen(act, id, null, DocLink(), it.doc.type)) } },
        TopAction(R.drawable.ic_delete, "מחיקה") { delete() }
    )

    override fun build(): View {
        val dv = repo.document(id) ?: return ui.emptyState(R.drawable.ic_warning, "המסמך אינו קיים", null)
        val d = dv.doc
        val (sv, c) = ui.page(32)
        val f = app.files.file(d.fileName)
        val info = ui.card()
        info.addView(ui.tv(d.title, TS.TITLE))
        info.addView(ui.tv(listOfNotNull(d.type.label, d.docDate?.let { Dates.display(it) }, if (f.exists()) "${(f.length() + 1023) / 1024} KB" else null).joinToString(" · "), TS.CAPTION, p.text2))
        d.notes?.let { info.addView(ui.tv(it, TS.BODY), ui.lp(top = 6)) }
        c.addView(info)
        val links = mutableListOf<View>()
        d.supplierId?.let { s -> links += ui.listRow(dv.supplierName ?: "", "ספק", R.drawable.ic_person, chevron = true) { push(SupplierDetailScreen(act, s)) } }
        d.expenseId?.let { e -> links += ui.listRow(dv.expenseName ?: "", "הוצאה", R.drawable.ic_receipt, chevron = true) { push(ExpenseDetailScreen(act, e)) } }
        d.paymentId?.let { pid -> links += ui.listRow(dv.paymentLabel ?: "", "תשלום", R.drawable.ic_payments, Tone.SUCCESS, chevron = true) { push(PaymentDetailScreen(act, pid)) } }
        d.taskId?.let { t -> links += ui.listRow(dv.taskTitle ?: "", "משימה", R.drawable.ic_tasks, chevron = true) { push(TaskDetailScreen(act, t)) } }
        d.eventId?.let { e -> links += ui.listRow(dv.eventTitle ?: "", "אירוע", R.drawable.ic_calendar, chevron = true) { push(EventDetailScreen(act, e)) } }
        d.transportId?.let { t -> links += ui.listRow(dv.transportTitle ?: "", "הסעה", R.drawable.ic_bus, chevron = true) { push(TransportDetailScreen(act, t)) } }
        if (links.isNotEmpty()) { c.addView(ui.sectionHeader("משויך ל")); listInCard(c, links) }

        if (!f.exists()) {
            c.addView(ui.banner("קובץ המסמך אינו נמצא במכשיר. ייתכן שהגיבוי שוחזר בלי הקובץ.", Tone.DANGER, R.drawable.ic_warning))
            return sv
        }
        c.addView(ui.button("פתיחה באפליקציה חיצונית", BtnKind.SECONDARY, R.drawable.ic_open) { DocumentFlows.openExternal(act, d) }, ui.lp(MATCH, WRAP, bottom = 12))
        when {
            d.isImage -> {
                val bmp = app.files.loadImage(d.fileName, 2000)
                if (bmp != null) c.addView(ImageView(act).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    background = ui.shape(p.surface, 12f, p.divider); clipToOutline = true
                    setOnClickListener { DocumentFlows.openExternal(act, d) }
                }, ui.lp(MATCH, WRAP))
                else c.addView(ui.banner("לא ניתן להציג את התמונה בתוך האפליקציה.", Tone.WARNING))
            }
            d.isPdf -> c.addView(Previews.pdfPages(act, f))
            else -> c.addView(ui.card().apply { addView(ui.tv("אין תצוגה מקדימה לסוג קובץ זה. אפשר לפתוח אותו באפליקציה חיצונית.", TS.CAPTION, p.text2)) })
        }
        return sv
    }

    private fun delete() {
        ui.confirm("מחיקת מסמך", "המסמך והקובץ שלו יימחקו מהמכשיר לצמיתות.", "מחיקה", destructive = true) {
            ui.guard { val name = repo.deleteDocument(id); app.files.delete(name); ui.toast("המסמך נמחק"); close() }
        }
    }
}

/** Metadata form for a new (just imported) or existing document. */
class DocumentEditScreen(
    act: MainActivity, private val id: Long?, private val imported: DocFiles.Imported?, private val link: DocLink, private val defaultType: DocType?
) : Screen(act) {
    override val title = if (id == null) "מסמך חדש" else "עריכת מסמך"
    override val keepView = true
    private lateinit var titleF: Ui.TextInput
    private lateinit var date: Ui.DateInput
    private lateinit var supplier: Ui.Choice<Long>
    private lateinit var expense: Ui.Choice<Long>
    private lateinit var notes: Ui.TextInput
    private var type = DocType.OTHER
    private var paymentId: Long? = null
    private var taskId: Long? = null
    private var eventId: Long? = null
    private var transportId: Long? = null
    private var saved = false

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val existing = id?.let { repo.document(it)?.doc }
        type = existing?.type ?: defaultType ?: DocType.OTHER
        paymentId = existing?.paymentId ?: link.paymentId
        taskId = existing?.taskId ?: link.taskId
        eventId = existing?.eventId ?: link.eventId
        transportId = existing?.transportId ?: link.transportId
        val pay = paymentId?.let { repo.payment(it) }
        val fileName = existing?.fileName ?: imported!!.fileName
        val (sv, c) = ui.page(32)

        if ((existing?.isImage ?: (imported?.mime?.startsWith("image/") == true))) {
            app.files.loadImage(fileName, 900)?.let { b ->
                c.addView(ImageView(act).apply { setImageBitmap(b); adjustViewBounds = true; maxHeight = ui.dp(260); background = ui.shape(p.surface, 12f, p.divider); clipToOutline = true },
                    ui.lp(MATCH, WRAP, bottom = 12))
            }
        } else imported?.let { c.addView(ui.banner("הקובץ \"${it.originalName ?: it.fileName}\" נשמר במכשיר.", Tone.SUCCESS, R.drawable.ic_check_circle)) }

        val main = ui.card(16, 16)
        val defaultTitle = existing?.title ?: run {
            val ctxName = pay?.let { "${it.expenseName} ${Dates.display(it.payment.date).replace("⁦", "").replace("⁩", "")}" }
                ?: link.expenseId?.let { repo.expense(it)?.expense?.name } ?: link.supplierId?.let { repo.supplier(it)?.name }
                ?: link.taskId?.let { repo.task(it)?.task?.title } ?: link.eventId?.let { repo.event(it)?.event?.title }
                ?: link.transportId?.let { repo.transport(it)?.displayTitle } ?: imported?.originalName?.substringBeforeLast('.')
            listOfNotNull(type.label, ctxName).joinToString(" – ")
        }
        titleF = ui.textField("שם המסמך", defaultTitle, required = true)
        main.addView(titleF.root)
        main.addView(ui.fieldLabel("סוג המסמך"))
        val types = DocType.values().toList()
        val tbox = ui.col()
        fun renderTypes() {
            tbox.removeAllViews()
            types.chunked(3).forEach { rowT ->
                val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 8) }
                rowT.forEach { t -> r.addView(ui.chip(t.label, type == t) { type = t; renderTypes() }) }
                tbox.addView(r)
            }
        }
        renderTypes()
        main.addView(tbox, ui.lp(MATCH, WRAP, bottom = 6))
        date = ui.dateField("תאריך המסמך", existing?.docDate ?: Dates.iso(repo.today()))
        main.addView(date.picker.root)
        c.addView(main)

        val links = ui.card(16, 16)
        links.addView(ui.tv("שיוך", TS.SUBTITLE), ui.lp(bottom = 8))
        supplier = supplierChoice("ספק", if (id != null) existing?.supplierId else link.supplierId)
        expense = expenseChoice("הוצאה", if (id != null) existing?.expenseId else link.expenseId, { supplier.value })
        links.addView(supplier.picker.root); links.addView(expense.picker.root)
        fun fixedLink(label: String, value: String?, clear: () -> Unit) {
            if (value == null) return
            val r = ui.row().apply { layoutParams = ui.lp(MATCH, WRAP, bottom = 8) }
            r.addView(ui.tv("$label: $value", TS.CAPTION_STRONG, p.text2), ui.lp(0, WRAP, 1f))
            r.addView(ui.button("הסרה", BtnKind.TEXT, small = true) { clear(); r.visibility = View.GONE })
            links.addView(r)
        }
        fixedLink("תשלום", pay?.let { "${il.hamechutan.app.core.util.Money.format(it.payment.amountAgorot)} · ${it.expenseName}" }) { paymentId = null }
        fixedLink("משימה", taskId?.let { repo.task(it)?.task?.title }) { taskId = null }
        fixedLink("אירוע", eventId?.let { repo.event(it)?.event?.title }) { eventId = null }
        fixedLink("הסעה", transportId?.let { repo.transport(it)?.displayTitle }) { transportId = null }
        links.addView(ui.tv("מסמך של תשלום מופיע גם בכרטיס ההוצאה והספק — אין צורך לשייך אותו שוב.", TS.SMALL, p.text3))
        c.addView(links)
        notes = ui.textField("הערות", existing?.notes, lines = 2)
        c.addView(ui.card(16, 16).apply { addView(notes.root) })
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        return sv
    }

    override fun isDirty() = id == null && !saved

    override fun onBack(): Boolean {
        if (id == null && !saved) {
            ui.confirm("לבטל את צירוף המסמך?", "הקובץ שצולם או נבחר לא יישמר.", "ביטול הצירוף", "המשך") {
                saved = true
                app.files.delete(imported?.fileName)
                close()
            }
            return true
        }
        return false
    }

    private fun save() {
        titleF.error(null)
        if (titleF.clean == null) { titleF.error("יש לתת שם למסמך"); return }
        ui.guard {
            if (id == null) {
                val imp = imported!!
                repo.addDocument(DocumentInput(titleF.value, type, imp.fileName, imp.mime, imp.size, imp.originalName, date.iso,
                    supplier.value, expense.value, paymentId, taskId, eventId, transportId, notes.clean))
            } else {
                val d = repo.document(id)!!.doc
                repo.updateDocument(id, DocumentInput(titleF.value, type, d.fileName, d.mimeType, d.sizeBytes, d.originalName, date.iso,
                    supplier.value, expense.value, paymentId, taskId, eventId, transportId, notes.clean))
            }
            saved = true
            ui.toast("המסמך נשמר")
            close()
        }
    }
}
