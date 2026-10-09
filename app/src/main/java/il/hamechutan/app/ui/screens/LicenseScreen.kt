package il.hamechutan.app.ui.screens

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import il.hamechutan.app.R
import il.hamechutan.app.core.license.LicenseManager
import il.hamechutan.app.ui.*

/**
 * Full-screen overlay shown until the subscription is confirmed: the login form, or a request to go online
 * for the periodic check. The wedding data underneath is never touched.
 */
class LicenseScreen(private val act: MainActivity, private var mode: Mode, private val onOpen: () -> Unit) {
    sealed class Mode {
        data class Login(val notice: String?) : Mode()
        data class Verify(val reason: String) : Mode()
    }

    private val ui = act.ui
    private val p = ui.p
    private val lic = act.app.license
    private lateinit var holder: FrameLayout
    private var busy = false
    private var autoChecked = false

    fun build(): View {
        holder = FrameLayout(act).apply {
            setBackgroundColor(p.bg)
            isClickable = true // blocks touches to the app underneath
            isFocusable = true
        }
        render()
        return holder
    }

    private fun render() {
        holder.removeAllViews()
        val root = ui.col().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(24))
        }
        root.addView(ui.iconBubble(R.drawable.ic_rings, p.gold, p.goldSoft, 72), ui.lp(ui.dp(72), ui.dp(72), gravity = Gravity.CENTER_HORIZONTAL))
        root.addView(centered(ui.tv("המחותן", TS.TITLE)), ui.lp(top = 14))
        when (val m = mode) {
            is Mode.Login -> login(root, m)
            is Mode.Verify -> verify(root, m)
        }
        holder.addView(ui.scroll(root).apply { setBackgroundColor(p.bg) }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun centered(t: TextView) = t.apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }

    private fun message(): TextView = centered(ui.tv("", TS.CAPTION_STRONG, p.danger)).apply { visibility = View.GONE }

    private fun show(msg: TextView, text: String?, color: Int = p.danger) {
        msg.text = text ?: ""
        msg.setTextColor(color)
        msg.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setBusy(btn: TextView, on: Boolean, label: String) {
        busy = on
        btn.text = if (on) "רגע…" else label
        btn.alpha = if (on) 0.6f else 1f
    }

    private fun login(root: LinearLayout, m: Mode.Login) {
        root.addView(centered(ui.tv("כניסה למנויים", TS.BODY, p.text2)), ui.lp(top = 4, bottom = 18))
        m.notice?.let { root.addView(ui.banner(it, Tone.WARNING, R.drawable.ic_warning)) }

        val card = ui.card(16, 18)
        val user = ui.textField("שם משתמש", null, "לדוגמה: 0501234567",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val pass = ui.textField("סיסמה", null, "8 ספרות", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        for (f in listOf(user, pass)) {
            f.edit.textDirection = View.TEXT_DIRECTION_LTR
            f.edit.textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        user.edit.imeOptions = EditorInfo.IME_ACTION_NEXT
        pass.edit.imeOptions = EditorInfo.IME_ACTION_DONE
        card.addView(user.root)
        card.addView(pass.root)
        val msg = message()
        card.addView(msg, ui.lp(bottom = 10))
        lateinit var btn: TextView
        val submit = {
            if (!busy) {
                user.error(null); pass.error(null)
                when {
                    user.value.isBlank() -> user.error("יש להזין שם משתמש")
                    pass.value.isBlank() -> pass.error("יש להזין סיסמה")
                    else -> {
                        ui.hideKeyboard(pass.edit)
                        setBusy(btn, true, "כניסה")
                        show(msg, null)
                        val u = user.value; val pw = pass.value
                        act.app.background({ lic.login(u, pw) }) { r ->
                            setBusy(btn, false, "כניסה")
                            when (val res = r.getOrNull()) {
                                LicenseManager.Result.Ok -> { ui.toast("ברוכים הבאים!"); onOpen() }
                                is LicenseManager.Result.Refused -> show(msg, res.message)
                                is LicenseManager.Result.Offline -> show(msg, res.message)
                                null -> show(msg, "שגיאה: ${r.exceptionOrNull()?.message ?: ""}")
                            }
                        }
                    }
                }
            }
        }
        btn = ui.button("כניסה", BtnKind.PRIMARY) { submit() }
        pass.edit.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        card.addView(btn, ui.lp(MATCH, WRAP))
        root.addView(card)

        root.addView(centered(ui.tv("החשבון פעיל בטלפון אחד בלבד.\nהטלפון הזה: ${lic.deviceName}", TS.CAPTION, p.text2)), ui.lp(top = 6))
        root.addView(centered(ui.tv("פרטי הכניסה נמסרים עם רכישת האפליקציה. נתוני החתונה נשמרים רק בטלפון ואינם נשלחים לשום מקום.", TS.SMALL, p.text3)), ui.lp(top = 10))
    }

    private fun verify(root: LinearLayout, m: Mode.Verify) {
        root.addView(centered(ui.tv("בדיקת מנוי", TS.BODY, p.text2)), ui.lp(top = 4, bottom = 18))
        root.addView(ui.banner(m.reason, Tone.INFO, R.drawable.ic_info))
        val msg = message()
        root.addView(msg, ui.lp(bottom = 10))
        lateinit var btn: TextView
        val check = {
            if (!busy) {
                setBusy(btn, true, "בדיקה עכשיו")
                show(msg, "בודק…", p.text2)
                act.app.background({ lic.verify() }) { r ->
                    setBusy(btn, false, "בדיקה עכשיו")
                    when (val res = r.getOrNull()) {
                        LicenseManager.Result.Ok -> { ui.toast("המנוי אושר"); onOpen() }
                        is LicenseManager.Result.Refused ->
                            if (res.revoked) { mode = Mode.Login(res.message); render() } else show(msg, res.message)
                        is LicenseManager.Result.Offline -> show(msg, res.message)
                        null -> show(msg, "שגיאה: ${r.exceptionOrNull()?.message ?: ""}")
                    }
                }
            }
        }
        btn = ui.button("בדיקה עכשיו", BtnKind.PRIMARY, R.drawable.ic_check_circle) { check() }
        root.addView(btn, ui.lp(MATCH, WRAP))
        lic.state()?.let { s ->
            root.addView(centered(ui.tv("שם משתמש: ${s.username}\nהטלפון הזה: ${lic.deviceName}", TS.CAPTION, p.text2)), ui.lp(top = 16))
        }
        if (!autoChecked) { autoChecked = true; act.app.main.post { check() } }
    }
}
