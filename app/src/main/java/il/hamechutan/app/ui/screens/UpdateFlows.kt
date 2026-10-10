package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import il.hamechutan.app.core.update.AvailableUpdate
import il.hamechutan.app.core.update.CheckResult
import il.hamechutan.app.core.update.UpdateCheck
import il.hamechutan.app.platform.App
import il.hamechutan.app.platform.Updater
import il.hamechutan.app.ui.*
import java.io.File
import java.io.IOException

/** Dialogs for checking, downloading and installing an app update. */
object UpdateFlows {

    /** "Check for updates" from Settings. */
    fun checkNow(act: MainActivity) {
        act.ui.toast("בודקים אם יש גרסה חדשה…")
        act.app.background({ act.app.updater.check() }) { r ->
            when (val res = r.getOrNull()) {
                is CheckResult.Available -> showAvailable(act, res.update)
                is CheckResult.UpToDate -> act.ui.alert("אין עדכון חדש", "יש לכם את הגרסה האחרונה (${App.VERSION_NAME}).")
                is CheckResult.Failed, null -> act.ui.alert("לא ניתן לבדוק כרגע",
                    "אין חיבור לשרת העדכונים. נסו שוב מאוחר יותר.\n\nאם יש לכם אינטרנט מסונן, בקשו מחברת הסינון לאשר את הכתובות github.com ו-api.github.com.")
            }
        }
    }

    fun showAvailable(act: MainActivity, u: AvailableUpdate) {
        val notes = UpdateCheck.plainNotes(u.notes)
        val size = if (u.apkSize > 0) " · ${"%.1f".format(u.apkSize / 1_048_576.0)} MB" else ""
        AlertDialog.Builder(act)
            .setTitle("גרסה חדשה: ${u.versionName}")
            .setMessage((if (notes.isNotEmpty()) "מה חדש:\n$notes\n\n" else "") +
                "הגרסה המותקנת: ${App.VERSION_NAME}$size\nכל הנתונים שלכם נשמרים בעדכון.")
            .setPositiveButton("הורדה והתקנה") { _, _ -> downloadAndInstall(act, u) }
            .setNegativeButton("לא עכשיו") { _, _ -> act.app.updater.dismiss(u); act.refresh() }
            .show()
    }

    private fun downloadAndInstall(act: MainActivity, u: AvailableUpdate) {
        val ui = act.ui
        val body = ui.col()
        val label = ui.tv("מורידים את העדכון…", TS.BODY)
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        body.addView(label)
        body.addView(bar, ui.lp(MATCH, WRAP, top = 12))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(act).setTitle("עדכון לגרסה ${u.versionName}").setView(ui.dialogBody(body))
            .setNegativeButton("ביטול") { _, _ -> cancelled.set(true) }
            .setCancelable(false).show()
        act.suppressLockBriefly()
        act.app.background({
            act.app.updater.download(u) { pct ->
                if (cancelled.get()) throw IOException("cancelled")
                act.app.main.post {
                    if (pct >= 0) { bar.isIndeterminate = false; bar.progress = pct; label.text = "מורידים את העדכון… $pct%" }
                }
            }
        }) { r ->
            if (dlg.isShowing) dlg.dismiss()
            if (cancelled.get()) return@background
            r.onSuccess { apk -> install(act, apk) }
            r.onFailure { e ->
                if (e is Updater.UpdateRejected) ui.alert("העדכון לא הותקן", e.message ?: "")
                else ui.alert("ההורדה לא הושלמה", "נסו שוב בעוד רגע. אם יש לכם אינטרנט מסונן, בקשו מחברת הסינון לאשר את github.com ואת objects.githubusercontent.com.")
            }
        }
    }

    /** Hands the verified APK to Android's installer, asking once for the "install unknown apps" permission. */
    fun install(act: MainActivity, apk: File) {
        val up = act.app.updater
        if (!up.canInstall()) {
            up.pendingInstall = apk
            AlertDialog.Builder(act).setTitle("אישור התקנת עדכונים")
                .setMessage("כדי שהאפליקציה תוכל להתקין את העדכון, Android מבקש אישור חד-פעמי.\n\nבמסך שייפתח הפעילו את \"לאפשר ממקור זה\" וחזרו לאפליקציה. ההתקנה תתחיל מיד.")
                .setPositiveButton("פתיחת ההגדרה") { _, _ ->
                    act.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("לא עכשיו") { _, _ -> up.pendingInstall = null }
                .show()
            return
        }
        up.pendingInstall = null
        act.launch(up.installIntent(apk), "לא נמצא במכשיר מתקין אפליקציות.")
    }

    /** Called from onResume: continues an install that waited for the permission. */
    fun resumePending(act: MainActivity) {
        val apk = act.app.updater.pendingInstall ?: return
        if (act.app.updater.canInstall() && apk.exists()) install(act, apk)
    }
}
