package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import il.hamechutan.app.R
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.platform.App
import il.hamechutan.app.platform.CrashLog
import il.hamechutan.app.ui.*
import java.time.LocalTime

class SettingsScreen(act: MainActivity) : Screen(act) {
    override val title = "הגדרות"

    override fun build(): View {
        val (sv, c) = ui.page(32)

        CrashLog.read(act)?.let { log ->
            c.addView(ui.banner("נשמר דוח תקלה מהפעלה קודמת. אפשר לשתף אותו כדי לאבחן את הבעיה.", Tone.WARNING, R.drawable.ic_warning, "שיתוף דוח התקלה") {
                val f = java.io.File(app.files.sharedDir, "hamechutan-crash.txt"); f.writeText(log)
                DocumentFlows.share(act, app.files.uriForShared(f, "דוח תקלה"), "text/plain", "דוח תקלה – המחותן")
            })
            c.addView(ui.button("מחיקת דוח התקלה", BtnKind.TEXT, small = true) { CrashLog.clear(act); refresh() })
        }

        c.addView(ui.sectionHeader("החתונה"))
        listInCard(c, listOf(
            ui.listRow("פרטי החתונה", repo.wedding().let { w -> listOfNotNull(w.title, w.date?.let { Dates.display(it) }, w.venue).joinToString(" · ") }, R.drawable.ic_rings, Tone.GOLD, chevron = true) { push(WeddingEditScreen(act)) },
            ui.listRow("תקציב", repo.wedding().budgetAgorot?.let { money(it) } ?: "לא הוגדר", R.drawable.ic_wallet, chevron = true) { push(BudgetScreen(act)) },
            ui.listRow("קטגוריות הוצאה", "${repo.categories().size} קטגוריות · שינוי שם, הוספה, סדר", R.drawable.ic_label, chevron = true) { push(CategoriesScreen(act)) },
            ui.listRow("אמצעי תשלום", "הוספת אמצעים מותאמים אישית", R.drawable.ic_card, chevron = true) { push(PaymentMethodsScreen(act)) }
        ))

        c.addView(ui.sectionHeader("תזכורות והתראות"))
        val notifOk = app.scheduler.notificationsAllowed()
        val exactOk = app.scheduler.canExact()
        val remCard = ui.listCard()
        val (sw, _) = ui.switchRow("הפעלת תזכורות", "כיבוי משבית את כל התזכורות בלי למחוק אותן", repo.remindersEnabled()) { v ->
            ui.guard { repo.setSetting(Repo.KEY_REMINDERS_ENABLED, if (v) "1" else "0"); refresh() }
        }
        remCard.addView(sw)
        remCard.addView(ui.divider(16))
        remCard.addView(ui.listRow("שעת ברירת מחדל", "לתזכורות של פריטים עם תאריך בלי שעה: ${Dates.displayTime(repo.defaultReminderTime())}", R.drawable.ic_clock, chevron = true) {
            val t = repo.defaultReminderTime()
            TimePickerDialog(act, { _, h, m -> ui.guard { repo.setSetting(Repo.KEY_DEFAULT_TIME, Dates.iso(LocalTime.of(h, m))); refresh() } }, t.hour, t.minute, true).show()
        })
        remCard.addView(ui.divider(16))
        remCard.addView(ui.listRow("כל התזכורות ומצבן", "מה מתוזמן בפועל במכשיר", R.drawable.ic_bell, chevron = true) { push(RemindersScreen(act)) })
        remCard.addView(ui.divider(16))
        remCard.addView(ui.listRow("הרשאת התראות", if (notifOk) "מאושרת" else "חסומה — התזכורות לא יוצגו", R.drawable.ic_bell, if (notifOk) Tone.SUCCESS else Tone.DANGER, chevron = true) {
            act.ensureNotificationPermission { refresh() }
        })
        remCard.addView(ui.divider(16))
        remCard.addView(ui.listRow("צליל ורטט ההתראות", "נקבעים בהגדרות המכשיר לערוץ \"תזכורות\"", R.drawable.ic_settings, chevron = true) {
            act.launch(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, il.hamechutan.app.platform.Notifier.CHANNEL_ID))
        })
        if (Build.VERSION.SDK_INT >= 31) {
            remCard.addView(ui.divider(16))
            remCard.addView(ui.listRow("תזמון מדויק", if (exactOk) "מאושר — התזכורות יופיעו בזמן" else "לא מאושר — התזכורות עלולות להתעכב", R.drawable.ic_clock,
                if (exactOk) Tone.SUCCESS else Tone.WARNING, chevron = !exactOk) { if (!exactOk) act.openExactAlarmSettings() })
        }
        remCard.addView(ui.divider(16))
        remCard.addView(ui.listRow("חיסכון בסוללה", "בחלק מהמכשירים כדאי להחריג את האפליקציה כדי שתזכורות לא יתעכבו", R.drawable.ic_info, Tone.NEUTRAL, chevron = true) {
            act.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        c.addView(remCard)

        if (app.license.enabled) {
            c.addView(ui.sectionHeader("מנוי"))
            val sub = ui.card()
            val st = app.license.state()
            if (st != null) {
                sub.addView(ui.kv("שם משתמש", st.username))
                sub.addView(ui.kv("הטלפון הזה", app.license.deviceName))
                sub.addView(ui.kv("נבדק לאחרונה", Dates.displayDateTime(st.verifiedAt)))
                sub.addView(ui.kv("תוקף המנוי", if (st.expires.isEmpty()) "ללא הגבלה" else Dates.display(st.expires)))
            }
            sub.addView(ui.tv("האפליקציה בודקת את המנוי באינטרנט מדי פעם. בלי חיבור היא ממשיכה לעבוד עד ${app.license.offlineDays} ימים מהבדיקה האחרונה. " +
                "לשרת נשלחים רק שם המשתמש ומזהה הטלפון. למעבר לטלפון חדש פנו למוכר.", TS.SMALL, p.text3), ui.lp(top = 6, bottom = 8))
            sub.addView(ui.button("בדיקת המנוי עכשיו", BtnKind.TONAL, R.drawable.ic_check_circle, small = true) {
                ui.toast("בודק…"); act.checkLicenseNow { refresh() }
            }, ui.lp(WRAP, WRAP))
            c.addView(sub)
        }

        c.addView(ui.sectionHeader("אבטחה"))
        val sec = ui.listCard()
        val pinOn = app.pin.isEnabled
        val (pinSw, _) = ui.switchRow("נעילה בקוד PIN", if (pinOn) "האפליקציה ננעלת בפתיחה" else "כבויה", pinOn) { v ->
            if (v && !app.pin.isEnabled) PinFlows.setup(act) { refresh() }
            else if (!v && app.pin.isEnabled) PinFlows.disable(act) { refresh() }
        }
        sec.addView(pinSw)
        if (pinOn) {
            sec.addView(ui.divider(16))
            sec.addView(ui.listRow("שינוי קוד PIN", null, R.drawable.ic_lock, chevron = true) { PinFlows.change(act) { refresh() } })
            sec.addView(ui.divider(16))
            val t = app.prefs.lockTimeoutSec
            val labels = listOf(0 to "מיד ביציאה", 60 to "אחרי דקה", 300 to "אחרי 5 דקות", 900 to "אחרי רבע שעה")
            sec.addView(ui.listRow("נעילה אוטומטית", labels.firstOrNull { it.first == t }?.second ?: "$t שניות", R.drawable.ic_clock, chevron = true) {
                ui.options("נעילה אחרי יציאה מהאפליקציה", labels.map { it.second }) { i -> app.prefs.lockTimeoutSec = labels[i].first; refresh() }
            })
            sec.addView(ui.divider(16))
            sec.addView(ui.listRow("קוד שחזור חדש", "אם קוד השחזור הקודם אבד", R.drawable.ic_backup, chevron = true) { PinFlows.newRecoveryCode(act) })
            sec.addView(ui.divider(16))
            val (secSw, _) = ui.switchRow("הסתרת התוכן במסך האפליקציות האחרונות", "מונע גם צילומי מסך באפליקציה", app.prefs.sp.getBoolean("secure_screen", true)) { v ->
                app.prefs.sp.edit().putBoolean("secure_screen", v).apply(); act.applySecureFlag()
            }
            sec.addView(secSw)
        }
        c.addView(sec)

        c.addView(ui.sectionHeader("תצוגה"))
        val disp = ui.card(16, 14)
        disp.addView(ui.fieldLabel("ערכת צבעים"))
        val themes = listOf(ThemeMode.SYSTEM to "לפי המכשיר", ThemeMode.LIGHT to "בהירה", ThemeMode.DARK to "כהה")
        val curTheme = repo.getSetting(Repo.KEY_THEME) ?: ThemeMode.SYSTEM
        disp.addView(ui.segmented(themes.map { it.second }, themes.indexOfFirst { it.first == curTheme }.coerceAtLeast(0)) { i ->
            ui.guard { repo.setSetting(Repo.KEY_THEME, themes[i].first); act.restartForSettings() }
        })
        disp.addView(ui.fieldLabel("גודל טקסט"))
        val scales = listOf("1.0" to "רגיל", "1.15" to "גדול", "1.3" to "גדול מאוד")
        val curScale = repo.getSetting(Repo.KEY_TEXT_SCALE) ?: "1.0"
        disp.addView(ui.segmented(scales.map { it.second }, scales.indexOfFirst { it.first == curScale }.coerceAtLeast(0)) { i ->
            ui.guard { repo.setSetting(Repo.KEY_TEXT_SCALE, scales[i].first); act.restartForSettings() }
        })
        c.addView(disp)
        val heb = ui.listCard()
        heb.addView(ui.switchRow("הצגת תאריך עברי", "לצד התאריך הלועזי ובלוח השנה", repo.getSetting(Repo.KEY_HEBREW_DATES) != "0") { v ->
            ui.guard { repo.setSetting(Repo.KEY_HEBREW_DATES, if (v) "1" else "0"); act.restartForSettings() }
        }.first)
        c.addView(heb)

        c.addView(ui.sectionHeader("נתונים"))
        listInCard(c, listOf(
            ui.listRow("גיבוי ושחזור", app.prefs.lastBackupAt.takeIf { it > 0 }?.let { "גיבוי אחרון: " + Dates.displayDateTime(it) } ?: "עדיין לא נוצר גיבוי", R.drawable.ic_backup, chevron = true) { push(BackupScreen(act)) },
            ui.listRow("דוחות PDF", null, R.drawable.ic_pdf, chevron = true) { push(ReportsScreen(act)) }
        ))

        c.addView(ui.sectionHeader("אודות"))
        val about = ui.card()
        about.addView(ui.kv("גרסה", "${App.VERSION_NAME} (${App.VERSION_CODE})"))
        app.updater.available()?.let { u -> about.addView(ui.kv("גרסה חדשה זמינה", u.versionName, p.info, strong = true)) }
        about.addView(ui.button(if (app.updater.available() != null) "להתקנת הגרסה החדשה" else "בדיקת עדכונים", BtnKind.TONAL, R.drawable.ic_backup, small = true) {
            app.updater.available()?.let { UpdateFlows.showAvailable(act, it) } ?: UpdateFlows.checkNow(act)
        }, ui.lp(WRAP, WRAP, top = 4, bottom = 6))
        about.addView(ui.kv("מכשיר", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"))
        about.addView(ui.button("פניות והערות: וואטסאפ ${il.hamechutan.app.core.license.Support.DISPLAY}", BtnKind.TEXT, R.drawable.ic_whatsapp, small = true) {
            act.whatsapp(il.hamechutan.app.core.license.Support.PHONE)
        }, ui.lp(WRAP, WRAP, bottom = 4))
        about.addView(ui.kv("ספקים / הוצאות / משימות", "${repo.suppliers().size} / ${repo.expenses().size} / ${repo.tasks().size}"))
        about.addView(ui.kv("מסמכים", "${repo.documents().size}"))
        about.addView(ui.tv(if (app.license.enabled) "כל נתוני החתונה נשמרים במכשיר בלבד ואינם נשלחים לשום מקום. החיבור לאינטרנט משמש רק לבדיקת המנוי ולבדיקת עדכונים."
            else "כל המידע נשמר במכשיר בלבד.", TS.SMALL, p.text3), ui.lp(top = 6))
        about.addView(ui.tv("גופן: Heebo (רישיון SIL Open Font License).", TS.SMALL, p.text3), ui.lp(top = 2))
        c.addView(about)
        return sv
    }
}

class WeddingEditScreen(act: MainActivity) : Screen(act) {
    override val title = "פרטי החתונה"
    override val keepView = true
    private lateinit var titleF: Ui.TextInput
    private lateinit var date: Ui.DateInput
    private lateinit var venue: Ui.TextInput
    private lateinit var notes: Ui.TextInput
    private var initial = ""

    override fun actions() = listOf(TopAction(R.drawable.ic_save, "שמירה") { save() })

    override fun build(): View {
        val w = repo.wedding()
        val (sv, c) = ui.page(32)
        val card = ui.card(16, 16)
        titleF = ui.textField("שם", w.title, "למשל: חתונת משה ורבקה", required = true)
        date = ui.dateField("תאריך החתונה", w.date)
        venue = ui.textField("מקום / אולם", w.venue)
        notes = ui.textField("הערות", w.notes, lines = 3)
        listOf(titleF.root, date.picker.root, venue.root, notes.root).forEach { card.addView(it) }
        c.addView(card)
        c.addView(ui.button("שמירה", BtnKind.PRIMARY, R.drawable.ic_save) { save() })
        c.addView(ui.tv("המבנה מוכן לפתיחת חתונה נוספת בעתיד, כשנתוני החתונה הנוכחית יישמרו.", TS.SMALL, p.text3), ui.lp(top = 12))
        initial = snap()
        return sv
    }

    private fun snap() = listOf(titleF.value, date.iso, venue.value, notes.value).joinToString("|")
    override fun isDirty() = ::titleF.isInitialized && snap() != initial

    private fun save() {
        if (titleF.clean == null) { titleF.error("יש למלא שם"); return }
        ui.guard { repo.updateWedding(titleF.value, date.iso, venue.clean, notes.clean); initial = snap(); ui.toast("נשמר"); close() }
    }
}

class CategoriesScreen(act: MainActivity) : Screen(act) {
    override val title = "קטגוריות"
    override fun fab() = Fab(R.drawable.ic_add, "קטגוריה") {
        inputDialog("קטגוריה חדשה", "שם הקטגוריה", null) { n -> ui.guard { repo.addCategory(n); refresh() } }
    }

    override fun build(): View {
        val cats = repo.categories()
        val (sv, c) = ui.page()
        c.addView(ui.tv("אפשר לשנות שמות, להוסיף קטגוריות ולשנות את הסדר. מחיקת קטגוריה שיש בה הוצאות מחייבת העברתן לקטגוריה אחרת.", TS.CAPTION, p.text2), ui.lp(bottom = 12))
        listInCard(c, cats.mapIndexed { i, cat ->
            val used = repo.categoryUsage(cat.id)
            val trailing = ui.row().apply {
                if (i > 0) addView(ui.iconButton(R.drawable.ic_expand, "העברה למעלה", p.text2) { ui.guard { repo.moveCategory(cat.id, -1); refresh() } }.apply { rotation = 180f })
                if (i < cats.size - 1) addView(ui.iconButton(R.drawable.ic_expand, "העברה למטה", p.text2) { ui.guard { repo.moveCategory(cat.id, 1); refresh() } })
            }
            ui.listRow(cat.name, listOfNotNull("$used הוצאות", cat.budgetAgorot?.let { "תקציב ${money(it)}" }).joinToString(" · "), R.drawable.ic_label, trailing = trailing) {
                ui.options(cat.name, listOf("שינוי שם", "מחיקה")) { a ->
                    if (a == 0) inputDialog("שינוי שם", "שם הקטגוריה", cat.name) { n -> ui.guard { repo.updateCategory(cat.id, n, cat.budgetAgorot); refresh() } }
                    else delete(cat.id, cat.name, used)
                }
            }
        }, "אין קטגוריות.")
        return sv
    }

    private fun delete(id: Long, name: String, used: Int) {
        if (used == 0) {
            ui.confirm("מחיקת קטגוריה", "הקטגוריה \"$name\" תימחק.", "מחיקה", destructive = true) { ui.guard { repo.deleteCategory(id, null); refresh() } }
            return
        }
        val others = repo.categories().filter { it.id != id }
        if (others.isEmpty()) { ui.alert("לא ניתן למחוק", "יש להוסיף קטגוריה אחרת להעברת ההוצאות."); return }
        ui.options("להעביר $used הוצאות אל…", others.map { it.name }) { i ->
            ui.confirm("מחיקת קטגוריה", "$used הוצאות יועברו ל\"${others[i].name}\" והקטגוריה \"$name\" תימחק.", "העברה ומחיקה", destructive = true) {
                ui.guard { repo.deleteCategory(id, others[i].id); refresh() }
            }
        }
    }
}

class PaymentMethodsScreen(act: MainActivity) : Screen(act) {
    override val title = "אמצעי תשלום"
    override fun fab() = Fab(R.drawable.ic_add, "אמצעי תשלום") {
        inputDialog("אמצעי תשלום חדש", "שם", null) { n -> ui.guard { repo.addPaymentMethod(n); refresh() } }
    }

    override fun build(): View {
        val (sv, c) = ui.page()
        listInCard(c, repo.paymentMethods().map { m ->
            ui.listRow(m.name, if (m.isBuiltIn) "מובנה" else "מותאם אישית", R.drawable.ic_card, if (m.isBuiltIn) Tone.NEUTRAL else Tone.INFO) {
                if (m.isBuiltIn) return@listRow
                ui.options(m.name, listOf("שינוי שם", "מחיקה")) { a ->
                    if (a == 0) inputDialog("שינוי שם", "שם", m.name) { n -> ui.guard { repo.renamePaymentMethod(m.id, n); refresh() } }
                    else ui.confirm("מחיקה", "אם נעשה באמצעי זה שימוש בתשלומים, הוא יוסתר מהרשימה אך יישמר בהיסטוריה.", "מחיקה", destructive = true) {
                        ui.guard { val deleted = repo.deletePaymentMethod(m.id); ui.toast(if (deleted) "נמחק" else "הוסתר (קיים בהיסטוריית תשלומים)"); refresh() }
                    }
                }
            }
        })
        return sv
    }
}

/** All reminders and their real scheduling state on this device. */
class RemindersScreen(act: MainActivity) : Screen(act) {
    override val title = "תזכורות"

    override fun build(): View {
        app.scheduler.sync()
        val list = repo.reminderStatuses()
        val (sv, c) = ui.page(32)
        if (!repo.remindersEnabled()) c.addView(ui.banner("התזכורות כבויות בהגדרות האפליקציה.", Tone.WARNING, R.drawable.ic_bell_off))
        if (!app.scheduler.notificationsAllowed()) c.addView(ui.banner("ההתראות של האפליקציה חסומות במכשיר — שום תזכורת לא תוצג.", Tone.DANGER, R.drawable.ic_warning, "אישור התראות") { act.ensureNotificationPermission { refresh() } })
        if (!app.scheduler.canExact()) c.addView(ui.banner("אין הרשאה לתזמון מדויק — התזכורות עלולות להתעכב.", Tone.WARNING, R.drawable.ic_clock, "מתן הרשאה") { act.openExactAlarmSettings() })
        if (list.isEmpty()) {
            c.addView(ui.emptyState(R.drawable.ic_bell, "אין תזכורות", "אפשר להוסיף תזכורת מתוך משימה, אירוע, הסעה או תשלום מתוכנן."))
            return sv
        }
        val armed = app.scheduler.armed()
        c.addView(ui.card().apply {
            addView(ui.kv("תזכורות שמתוזמנות כעת במכשיר", armed.size.toString(), strong = true))
            addView(ui.kv("סה״כ תזכורות", list.size.toString()))
        })
        val sorted = list.sortedWith(compareBy({ it.state != Repo.ReminderState.PLANNED }, { it.triggerAt ?: Long.MAX_VALUE }))
        listInCard(c, sorted.map { st ->
            val us = app.scheduler.uiStatus(st)
            val title = st.info?.title ?: "פריט שנמחק"
            ui.listRow(title, reminderLabel(st.reminder.toSpec()), if (st.reminder.muted) R.drawable.ic_bell_off else R.drawable.ic_bell,
                if (us.ok) Tone.SUCCESS else Tone.WARNING, third = us.text, chevron = st.info != null) {
                val t = st.reminder.targetType.name
                act.screenFor(if (t == "EXPENSE_PAYMENT") "EXPENSE" else t, st.reminder.targetId)?.let { push(it) }
            }
        })
        c.addView(ui.tv("התזכורות נשמרות גם אחרי סגירת האפליקציה והפעלה מחדש של הטלפון. בחלק מהמכשירים מצב חיסכון בסוללה עלול לעכב אותן.", TS.SMALL, p.text3), ui.lp(top = 8))
        return sv
    }
}
