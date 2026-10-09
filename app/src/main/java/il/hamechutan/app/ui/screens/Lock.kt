package il.hamechutan.app.ui.screens

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import il.hamechutan.app.R
import il.hamechutan.app.platform.CrashLog
import il.hamechutan.app.platform.PinManager
import il.hamechutan.app.ui.*

/** Full-screen lock overlay with a numeric keypad. */
class LockScreen(private val act: MainActivity, private val onUnlock: () -> Unit) {
    private val ui = act.ui
    private val p = ui.p
    private val pin = act.app.pin
    private val entered = StringBuilder()
    private lateinit var dots: LinearLayout
    private lateinit var msg: TextView
    private lateinit var keypad: LinearLayout
    private var busy = false
    private val ticker = object : Runnable {
        override fun run() {
            val until = pin.lockedUntil()
            if (until > 0) {
                val s = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                msg.text = "יותר מדי ניסיונות שגויים. אפשר לנסות שוב בעוד ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
                msg.setTextColor(p.danger)
                keypad.alpha = 0.4f
                act.app.main.postDelayed(this, 1000)
            } else {
                keypad.alpha = 1f
                if (msg.text.startsWith("יותר")) { msg.text = ""; }
            }
        }
    }

    fun build(): View {
        val root = ui.col().apply {
            setBackgroundColor(p.bg)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(24))
            isClickable = true // block touches to the app underneath
            isFocusable = true
        }
        root.addView(ui.iconBubble(R.drawable.ic_rings, p.gold, p.goldSoft, 72), ui.lp(ui.dp(72), ui.dp(72), gravity = Gravity.CENTER_HORIZONTAL))
        root.addView(ui.tv("המחותן", TS.TITLE).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(top = 14))
        root.addView(ui.tv("הזינו את קוד ה-PIN", TS.BODY, p.text2).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, ui.lp(top = 4))
        dots = ui.row(Gravity.CENTER).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        root.addView(dots, ui.lp(MATCH, ui.dp(28), top = 24))
        msg = ui.tv("", TS.CAPTION_STRONG, p.danger).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; minLines = 2 }
        root.addView(msg, ui.lp(top = 8))
        keypad = ui.col().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        keys.forEach { rowK ->
            val r = ui.row(Gravity.CENTER).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            rowK.forEach { k -> r.addView(key(k), ui.lp(ui.dp(76), ui.dp(64), start = 8, end = 8)) }
            keypad.addView(r, ui.lp(WRAP, WRAP, bottom = 8, gravity = Gravity.CENTER_HORIZONTAL))
        }
        root.addView(keypad, ui.lp(WRAP, WRAP, top = 8, gravity = Gravity.CENTER_HORIZONTAL))
        root.addView(ui.button("שכחתי את הקוד", BtnKind.TEXT, small = true) { PinFlows.forgot(act, onUnlock) }, ui.lp(WRAP, WRAP, top = 12, gravity = Gravity.CENTER_HORIZONTAL))
        renderDots()
        act.app.main.post(ticker)
        return ui.scroll(root).apply { setBackgroundColor(p.bg); isClickable = true }
    }

    private fun key(k: String): View = ui.tv(if (k == "⌫") "" else k, TS.TITLE).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        if (k.isEmpty()) { visibility = View.INVISIBLE; return@apply }
        if (k == "⌫") {
            val d = act.getDrawable(R.drawable.ic_backspace)!!.mutate(); d.setTint(p.text2)
            d.setBounds(0, 0, ui.dp(26), ui.dp(26)); setCompoundDrawables(d, null, null, null)
            contentDescription = "מחיקה"
        }
        background = ui.ripple(ui.shape(if (k == "⌫") 0 else p.surface, 32f, if (k == "⌫") null else p.divider), 32f)
        setOnClickListener { press(k) }
    }

    private fun renderDots() {
        dots.removeAllViews()
        val n = pin.pinLength
        for (i in 0 until n) {
            dots.addView(View(act).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    if (i < entered.length) setColor(p.primary) else { setColor(0); setStroke(ui.dp(2), p.outline) }
                }
            }, ui.lp(ui.dp(16), ui.dp(16), start = 8, end = 8))
        }
    }

    private fun press(k: String) {
        if (busy || pin.lockedUntil() > 0) return
        if (k == "⌫") { if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1); renderDots(); return }
        if (entered.length >= pin.pinLength) return
        entered.append(k)
        renderDots()
        if (entered.length == pin.pinLength) verify()
    }

    private fun verify() {
        busy = true
        val code = entered.toString()
        msg.setTextColor(p.text2); msg.text = "בודק…"
        act.app.background({ pin.verifyPin(code) }) { r ->
            busy = false
            entered.clear(); renderDots()
            when (val res = r.getOrNull()) {
                PinManager.Result.Ok -> { act.app.main.removeCallbacks(ticker); onUnlock() }
                is PinManager.Result.Wrong -> { msg.setTextColor(p.danger); msg.text = "קוד שגוי. " + if (res.attemptsBeforeLock > 0) "נותרו ${res.attemptsBeforeLock} ניסיונות לפני השהיה." else "" }
                is PinManager.Result.Locked -> act.app.main.post(ticker)
                null -> { msg.setTextColor(p.danger); msg.text = "שגיאה בבדיקת הקוד" }
            }
        }
    }
}

object PinFlows {

    private fun pinField(ui: Ui, label: String) = ui.textField(label, null, "4–8 ספרות", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
        edit.textDirection = View.TEXT_DIRECTION_LTR; edit.textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    /** Asks for a new PIN twice; calls [onPin] with it. */
    private fun askNewPin(act: MainActivity, title: String, onPin: (String) -> Unit, onCancel: () -> Unit) {
        val ui = act.ui
        val body = ui.col()
        val a = pinField(ui, "קוד PIN חדש")
        val b = pinField(ui, "הקלדה חוזרת של הקוד")
        body.addView(a.root); body.addView(b.root)
        val d = AlertDialog.Builder(act).setTitle(title).setView(ui.dialogBody(body))
            .setPositiveButton("המשך", null).setNegativeButton("ביטול") { _, _ -> onCancel() }.setCancelable(false).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                a.error(null); b.error(null)
                when {
                    !act.app.pin.isValidPinFormat(a.value) -> a.error("הקוד צריך להיות 4 עד 8 ספרות")
                    a.value != b.value -> b.error("הקודים אינם זהים")
                    a.value.toSet().size == 1 || "0123456789".contains(a.value) || "9876543210".contains(a.value) -> a.error("קוד פשוט מדי (ספרות זהות או רצף). בחרו קוד אחר.")
                    else -> { d.dismiss(); onPin(a.value) }
                }
            }
        }
        d.show()
    }

    /** Verifies the current PIN; calls [onOk] when correct. */
    private fun askCurrentPin(act: MainActivity, title: String, onOk: () -> Unit, onCancel: () -> Unit) {
        val ui = act.ui
        val f = pinField(ui, "קוד ה-PIN הנוכחי")
        val d = AlertDialog.Builder(act).setTitle(title).setView(ui.dialogBody(f.root))
            .setPositiveButton("אישור", null).setNegativeButton("ביטול") { _, _ -> onCancel() }.setCancelable(false).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = f.value
                act.app.background({ act.app.pin.verifyPin(v) }) { r ->
                    when (val res = r.getOrNull()) {
                        PinManager.Result.Ok -> { d.dismiss(); onOk() }
                        is PinManager.Result.Wrong -> f.error("קוד שגוי")
                        is PinManager.Result.Locked -> f.error("נחסם זמנית עקב ניסיונות שגויים")
                        null -> f.error("שגיאה")
                    }
                }
            }
        }
        d.show()
    }

    fun setup(act: MainActivity, done: () -> Unit) {
        AlertDialog.Builder(act).setTitle("נעילה בקוד PIN")
            .setMessage("האפליקציה תבקש קוד בכל פתיחה. הקוד נשמר במכשיר רק בצורה מוצפנת (גיבוב), ולא ניתן לשחזר אותו מהמכשיר.\n\n" +
                "לאחר הגדרת הקוד יוצג קוד שחזור חד-פעמי — רשמו אותו במקום בטוח. בעזרתו אפשר לאפס קוד שנשכח בלי לאבד נתונים.")
            .setPositiveButton("הגדרת קוד") { _, _ ->
                askNewPin(act, "הגדרת קוד PIN", { pinValue ->
                    act.ui.toast("שומר…")
                    act.app.background({ act.app.pin.setPin(pinValue) }) { r ->
                        r.onSuccess { code -> act.applySecureFlag(); showRecoveryCode(act, code, done) }.onFailure { act.ui.alert("שגיאה", it.message ?: ""); done() }
                    }
                }, done)
            }
            .setNegativeButton("ביטול") { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }

    fun change(act: MainActivity, done: () -> Unit) {
        askCurrentPin(act, "שינוי קוד PIN", {
            askNewPin(act, "קוד PIN חדש", { v ->
                act.app.background({ act.app.pin.changePinKeepRecovery(v) }) { r -> act.ui.toast(if (r.isSuccess) "הקוד שונה" else "שגיאה"); done() }
            }, done)
        }, done)
    }

    fun disable(act: MainActivity, done: () -> Unit) {
        askCurrentPin(act, "ביטול הנעילה", { act.app.pin.disable(); act.applySecureFlag(); act.ui.toast("הנעילה בוטלה"); done() }, done)
    }

    fun newRecoveryCode(act: MainActivity) {
        askCurrentPin(act, "קוד שחזור חדש", {
            act.app.background({ act.app.pin.regenerateRecoveryCode() }) { r -> r.onSuccess { showRecoveryCode(act, it) {} } }
        }, {})
    }

    private fun showRecoveryCode(act: MainActivity, code: String, done: () -> Unit) {
        val ui = act.ui
        val body = ui.col()
        body.addView(ui.tv("זהו קוד השחזור. הוא יוצג פעם אחת בלבד. רשמו אותו ושמרו מחוץ לטלפון (למשל על דף).", TS.CAPTION, ui.p.text2))
        body.addView(ui.tv(code, TS.TITLE, ui.p.primary).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER; textDirection = View.TEXT_DIRECTION_LTR; setTextIsSelectable(true)
            background = ui.shape(ui.p.primarySoft, 12f); setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14))
            letterSpacing = 0.08f
        }, ui.lp(top = 12, bottom = 8))
        body.addView(ui.button("העתקה", BtnKind.TEXT, R.drawable.ic_copy, small = true) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("recovery", code))
            ui.toast("הקוד הועתק")
        })
        val cb = CheckBox(act).apply { text = "רשמתי את הקוד במקום בטוח"; setTextColor(ui.p.text) }
        body.addView(cb)
        val d = AlertDialog.Builder(act).setTitle("קוד שחזור").setView(ui.dialogBody(body)).setPositiveButton("סיום", null).setCancelable(false).create()
        d.setOnShowListener {
            val b = d.getButton(AlertDialog.BUTTON_POSITIVE)
            b.isEnabled = false
            cb.setOnCheckedChangeListener { _, v -> b.isEnabled = v }
            b.setOnClickListener { d.dismiss(); done() }
        }
        d.show()
    }

    /** Forgotten PIN: verify the recovery code, or (last resort, clearly warned) erase all data. */
    fun forgot(act: MainActivity, onUnlock: () -> Unit) {
        val ui = act.ui
        ui.options("שכחתי את הקוד", listOf("כניסה בעזרת קוד השחזור", "מחיקת כל הנתונים והתחלה מחדש")) { i ->
            if (i == 0) recovery(act, onUnlock) else wipe(act, onUnlock)
        }
    }

    private fun recovery(act: MainActivity, onUnlock: () -> Unit) {
        val ui = act.ui
        val f = ui.textField("קוד השחזור", null, "XXXX-XXXX-XXXX", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        f.edit.textDirection = View.TEXT_DIRECTION_LTR
        val d = AlertDialog.Builder(act).setTitle("כניסה בעזרת קוד שחזור").setView(ui.dialogBody(f.root))
            .setPositiveButton("אישור", null).setNegativeButton("ביטול", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = f.value
                act.app.background({ act.app.pin.verifyRecovery(v) }) { r ->
                    when (val res = r.getOrNull()) {
                        PinManager.Result.Ok -> {
                            d.dismiss()
                            askNewPin(act, "בחירת קוד PIN חדש", { pinValue ->
                                act.app.background({ act.app.pin.setPin(pinValue) }) { rr ->
                                    rr.onSuccess { code -> onUnlock(); showRecoveryCode(act, code) {} }
                                }
                            }, { onUnlock(); act.app.pin.disable(); act.applySecureFlag(); ui.toast("הנעילה בוטלה") })
                        }
                        is PinManager.Result.Wrong -> f.error("קוד שחזור שגוי" + if (res.attemptsBeforeLock > 0) " (נותרו ${res.attemptsBeforeLock} ניסיונות)" else "")
                        is PinManager.Result.Locked -> f.error("נחסם זמנית עקב ניסיונות שגויים. נסו שוב מאוחר יותר.")
                        null -> f.error("שגיאה")
                    }
                }
            }
        }
        d.show()
    }

    private fun wipe(act: MainActivity, onUnlock: () -> Unit) {
        val ui = act.ui
        val body = ui.col()
        body.addView(ui.tv("פעולה זו תמחק לצמיתות את כל המידע באפליקציה: ספקים, הוצאות, תשלומים, משימות, אירועים, הסעות, מסמכים ותמונות, וגם את הנעילה.\n\n" +
            "אם יש לכם קובץ גיבוי, אפשר לשחזר ממנו אחרי המחיקה (מתוך \"גיבוי ושחזור\").\n\nלאישור, הקלידו את המילה: מחק", TS.CAPTION, ui.p.text))
        val f = ui.textField("אישור", null)
        body.addView(f.root, ui.lp(top = 10))
        val d = AlertDialog.Builder(act).setTitle("מחיקת כל הנתונים").setView(ui.dialogBody(body))
            .setPositiveButton("מחיקה לצמיתות", null).setNegativeButton("ביטול", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.p.danger)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (f.value.trim() != "מחק") { f.error("יש להקליד: מחק"); return@setOnClickListener }
                d.dismiss()
                act.app.background({
                    act.app.repo.wipeAll()
                    act.app.files.docsDir.listFiles()?.forEach { it.delete() }
                    act.app.pin.disable()
                    act.app.prefs.sp.edit().clear().commit()
                    CrashLog.clear(act)
                }) { r ->
                    act.app.scheduler.sync()
                    act.applySecureFlag()
                    onUnlock()
                    act.openTab(Tab.HOME)
                    ui.alert(if (r.isSuccess) "הנתונים נמחקו" else "שגיאה", if (r.isSuccess) "האפליקציה אופסה. אפשר להתחיל מחדש או לשחזר מגיבוי." else (r.exceptionOrNull()?.message ?: ""))
                }
            }
        }
        d.show()
    }
}
