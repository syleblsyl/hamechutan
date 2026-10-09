package il.hamechutan.app.ui.screens

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import il.hamechutan.app.R
import il.hamechutan.app.core.backup.BackupException
import il.hamechutan.app.core.backup.BackupManager
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.platform.App
import il.hamechutan.app.ui.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupScreen(act: MainActivity) : Screen(act) {
    override val title = "גיבוי ושחזור"

    private fun manager() = BackupManager(repo, app.files.docsDir, app.files.workDir, App.VERSION_NAME)
    private val safetyDir: File get() = File(act.filesDir, "safety_backups").apply { mkdirs() }

    private val tableLabels = linkedMapOf(
        "supplier" to "ספקים", "expense" to "הוצאות", "payment" to "תשלומים", "task" to "משימות",
        "event" to "אירועים", "transport" to "הסעות", "document" to "מסמכים", "reminder" to "תזכורות", "category" to "קטגוריות"
    )

    override fun build(): View {
        val (sv, c) = ui.page(32)
        val last = app.prefs.lastBackupAt
        val docs = app.files.docsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val info = ui.card(16, 16)
        info.addView(ui.tv("כל המידע של האפליקציה שמור בטלפון בלבד.", TS.BODY_STRONG))
        info.addView(ui.tv("קובץ הגיבוי (ZIP) כולל את כל הנתונים — ספקים, התחייבויות, תשלומים, משימות, אירועים, הסעות, תזכורות, קטגוריות והגדרות — וגם את כל המסמכים, התמונות והקבלות עצמם. אפשר להעביר אותו למכשיר אחר ולשחזר שם.", TS.CAPTION, p.text2), ui.lp(top = 4))
        info.addView(ui.kv("גיבוי אחרון", if (last > 0) Dates.displayDateTime(last) else "עדיין לא נוצר", if (last > 0) p.text else p.warning), ui.lp(top = 8))
        info.addView(ui.kv("מסמכים ותמונות", "${docs.size} קבצים · ${formatSize(docs.sumOf { it.length() })}"))
        c.addView(info)
        c.addView(ui.button("יצירת גיבוי", BtnKind.PRIMARY, R.drawable.ic_save) { create() }, ui.lp(MATCH, WRAP, bottom = 10))
        c.addView(ui.button("שחזור מקובץ גיבוי", BtnKind.SECONDARY, R.drawable.ic_backup) { pickRestore() }, ui.lp(MATCH, WRAP, bottom = 6))
        c.addView(ui.tv("לפני כל שחזור נשמר אוטומטית גיבוי של המצב הנוכחי, כדי שאפשר יהיה לחזור אליו.", TS.SMALL, p.text3), ui.lp(bottom = 12))

        val safety = safetyDir.listFiles()?.filter { it.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (safety.isNotEmpty()) {
            c.addView(ui.sectionHeader("גיבויים אוטומטיים (לפני שחזור)"))
            listInCard(c, safety.map { f ->
                ui.listRow("מצב לפני שחזור", Dates.displayDateTime(f.lastModified()) + " · " + formatSize(f.length()), R.drawable.ic_history, Tone.NEUTRAL, chevron = true) {
                    ui.options("גיבוי אוטומטי", listOf("שחזור למצב זה", "שיתוף / שמירה")) { i ->
                        if (i == 0) inspectThenRestore(f, deleteAfter = false) else share(f)
                    }
                }
            })
        }
        c.addView(ui.sectionHeader("המלצות"))
        c.addView(ui.card().apply {
            addView(ui.tv("• שמרו את קובץ הגיבוי מחוץ לטלפון: בדוא״ל, בכונן ענן או במחשב.\n• צרו גיבוי חדש אחרי שינויים חשובים (תשלום גדול, ספק חדש).\n• במעבר לטלפון חדש: התקינו את האפליקציה, פתחו \"גיבוי ושחזור\" ובחרו את קובץ הגיבוי.", TS.CAPTION, p.text2))
        })
        return sv
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun create() {
        ui.toast("יוצר גיבוי…")
        val file = File(app.files.sharedDir, "hamechutan-backup-${stamp()}.zip")
        app.background({
            app.files.sharedDir.listFiles()?.filter { it.name.startsWith("hamechutan-backup-") && it != file }?.forEach { it.delete() }
            val m = manager()
            val created = FileOutputStream(file).use { m.create(it) }
            val verified = m.inspect(file) // re-read and verify checksums of what was just written
            if (verified.counts != created.counts || verified.fileCount != created.fileCount) throw BackupException("בדיקת הגיבוי נכשלה: התוכן שנקרא אינו תואם לתוכן שנכתב.")
            verified
        }) { r ->
            r.onSuccess { info ->
                app.prefs.lastBackupAt = System.currentTimeMillis()
                val msg = StringBuilder("הגיבוי נוצר ונבדק בהצלחה (${formatSize(file.length())}).\n\n")
                msg.append(summary(info))
                if (info.missingFiles.isNotEmpty()) msg.append("\n\nשימו לב: ${info.missingFiles.size} קבצי מסמכים לא נמצאו במכשיר ולכן לא נכללו.")
                msg.append("\n\nכעת שמרו את הקובץ מחוץ לטלפון.")
                AlertDialog.Builder(act).setTitle("הגיבוי מוכן").setMessage(msg)
                    .setPositiveButton("שמירה במכשיר / בכונן") { _, _ -> saveAs(file) }
                    .setNeutralButton("שיתוף") { _, _ -> share(file) }
                    .setNegativeButton("סגירה", null).show()
                refresh()
            }.onFailure { e -> ui.alert("יצירת הגיבוי נכשלה", e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun summary(info: BackupManager.Info): String {
        val parts = tableLabels.mapNotNull { (k, label) -> info.counts[k]?.takeIf { it > 0 }?.let { "$label: $it" } }
        return listOfNotNull(
            info.weddingTitle,
            "נוצר: " + (runCatching { Dates.displayDateTime(Instant.parse(info.createdAt).toEpochMilli()) }.getOrNull() ?: info.createdAt).replace("⁦", "").replace("⁩", ""),
            "גרסת אפליקציה: ${info.appVersion}",
            parts.joinToString(" · ").ifEmpty { "אין רשומות" },
            "קבצים מצורפים: ${info.fileCount} (${formatSize(info.totalFileBytes)})"
        ).joinToString("\n")
    }

    private fun share(f: File) = DocumentFlows.share(act, app.files.uriForShared(copyToShared(f), f.name), "application/zip", "גיבוי המחותן")

    private fun copyToShared(f: File): File {
        if (f.parentFile == app.files.sharedDir) return f
        val out = File(app.files.sharedDir, f.name); f.copyTo(out, overwrite = true); return out
    }

    private fun saveAs(f: File) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, f.name)
        act.launchForResult(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launchForResult
            app.background({
                (act.contentResolver.openOutputStream(uri) ?: error("לא ניתן לכתוב לקובץ")).use { out -> f.inputStream().use { it.copyTo(out) } }
            }) { r -> if (r.isSuccess) ui.toast("קובץ הגיבוי נשמר") else ui.alert("השמירה נכשלה", r.exceptionOrNull()?.message ?: "") }
        }
    }

    private fun pickRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        act.launchForResult(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launchForResult
            val tmp = File(app.files.workDir, "restore_input.zip")
            ui.toast("קורא את קובץ הגיבוי…")
            app.background({
                (act.contentResolver.openInputStream(uri) ?: error("לא ניתן לקרוא את הקובץ")).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            }) { r -> r.onSuccess { inspectThenRestore(tmp, deleteAfter = true) }.onFailure { ui.alert("לא ניתן לקרוא את הקובץ", it.message ?: "") } }
        }
    }

    /** Validates fully first; only after explicit confirmation replaces the current data. */
    private fun inspectThenRestore(file: File, deleteAfter: Boolean) {
        app.background({ manager().inspect(file) }) { r ->
            r.onFailure { e ->
                if (deleteAfter) file.delete()
                ui.alert("קובץ הגיבוי אינו תקין", (e.message ?: e.javaClass.simpleName) + "\n\nהנתונים הקיימים לא שונו.")
            }.onSuccess { info ->
                val msg = "הגיבוי נבדק ונמצא תקין:\n\n${summary(info)}\n\n" +
                    "שחזור יחליף את כל הנתונים הנוכחיים באפליקציה בנתוני הגיבוי.\nלפני השחזור יישמר גיבוי אוטומטי של המצב הנוכחי."
                AlertDialog.Builder(act).setTitle("שחזור מגיבוי").setMessage(msg)
                    .setPositiveButton("שחזור והחלפת הנתונים") { _, _ -> restore(file, deleteAfter) }
                    .setNegativeButton("ביטול") { _, _ -> if (deleteAfter) file.delete() }
                    .show().getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.danger)
            }
        }
    }

    private fun restore(file: File, deleteAfter: Boolean) {
        ui.toast("משחזר…")
        app.background({
            val m = manager()
            val src = if (file.parentFile == safetyDir) File(app.files.workDir, "restore_from_safety.zip").also { file.copyTo(it, overwrite = true) } else file
            // safety copy of the current state (keep the 3 most recent)
            val safety = File(safetyDir, "before-restore-${stamp()}.zip")
            FileOutputStream(safety).use { m.create(it) }
            m.inspect(safety)
            safetyDir.listFiles()?.filter { it.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
            val info = m.restore(src)
            if (src != file) src.delete()
            info
        }) { r ->
            if (deleteAfter) file.delete()
            r.onSuccess { info ->
                app.scheduler.sync()
                act.openTab(Tab.HOME)
                ui.alert("השחזור הושלם", "הנתונים שוחזרו מהגיבוי.\n\n${summary(info)}" +
                    if (info.missingFiles.isNotEmpty()) "\n\n${info.missingFiles.size} קבצי מסמכים היו חסרים כבר בזמן יצירת הגיבוי." else "")
            }.onFailure { e -> ui.alert("השחזור נכשל", (e.message ?: e.javaClass.simpleName) + "\n\nהנתונים הקיימים לא שונו.") }
        }
    }
}
