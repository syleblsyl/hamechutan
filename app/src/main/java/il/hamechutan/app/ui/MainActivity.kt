package il.hamechutan.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import il.hamechutan.app.R
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.license.LicenseManager
import il.hamechutan.app.platform.App
import il.hamechutan.app.platform.CrashLog
import il.hamechutan.app.ui.screens.*
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_OPEN = "il.hamechutan.app.OPEN"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val REQ_CAMERA = 50
        /** Set for an in-process restart (theme change) so the user is not asked for the PIN again. */
        var skipLockOnce = false
        private const val STATE_TAB = "tab"
        private const val STATE_SETTINGS = "reopen_settings"
    }

    lateinit var app: App
    lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private var fabView: View? = null
    private var lockView: View? = null
    /** Subscription overlay (login / periodic online check); always above the PIN lock. */
    private var licenseView: View? = null
    private var licenseRefreshRunning = false

    val stack = ArrayList<Screen>()
    var currentTab = Tab.HOME
        private set

    private var pendingOpen: Pair<String, Long>? = null
    private var lastStop = 0L
    private var suppressLockUntil = 0L
    private var started = false

    override fun attachBaseContext(base: Context) {
        val he = Locale.forLanguageTag("he-IL")
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(he)
        cfg.setLayoutDirection(he)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        app = application as App
        val dark = ThemeMode.isDark(this, app.repo.getSetting(Repo.KEY_THEME))
        setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        val scale = app.repo.getSetting(Repo.KEY_TEXT_SCALE)?.toFloatOrNull() ?: 1f
        ui = Ui(this, Palette(dark), scale, app.repo.getSetting(Repo.KEY_HEBREW_DATES) != "0")
        buildShell()
        applySecureFlag()

        val tab = savedInstanceState?.getString(STATE_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.HOME
        openTab(tab)
        if (intent?.getBooleanExtra(STATE_SETTINGS, false) == true) push(SettingsScreen(this))
        if (app.pin.isEnabled && !skipLockOnce) showLock()
        skipLockOnce = false
        applyLicenseGate()
        handleIntent(intent)
        handleOrphanCameraResult()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.name)
    }

    /** Re-creates the activity (theme/text size change) and returns to settings. */
    fun restartForSettings() {
        val i = Intent(this, MainActivity::class.java).putExtra(STATE_SETTINGS, true)
        skipLockOnce = !isLocked
        finish(); overridePendingTransition(0, 0)
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    fun applySecureFlag() {
        if (app.pin.isEnabled && app.prefs.sp.getBoolean("secure_screen", true)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ------------------------------------------------------------------ shell

    private fun buildShell() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root = FrameLayout(this).apply { setBackgroundColor(ui.p.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBar = ui.row().apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); minimumHeight = ui.dp(60); setBackgroundColor(ui.p.bg) }
        content = FrameLayout(this)
        bottomNav = ui.row(Gravity.CENTER).apply {
            setBackgroundColor(ui.p.surface)
            elevation = ui.dpf(6)
            setPadding(0, ui.dp(6), 0, ui.dp(8))
        }
        shell.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        shell.addView(View(this).apply { setBackgroundColor(ui.p.divider) }, LinearLayout.LayoutParams(MATCH, 1))
        shell.addView(bottomNav, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        buildBottomNav()
    }

    private fun buildBottomNav() {
        bottomNav.removeAllViews()
        Tab.values().forEach { t ->
            val sel = t == currentTab
            val item = ui.col().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(2), 0, ui.dp(2))
                background = ui.ripple(null, 14f)
                isClickable = true
                contentDescription = t.label
                setOnClickListener { if (stack.size == 1 && currentTab == t) scrollTop() else openTab(t) }
            }
            val pill = FrameLayout(this).apply {
                background = if (sel) ui.shape(ui.p.primarySoft, 15f) else null
            }
            pill.addView(ImageView(this).apply {
                setImageResource(t.icon); imageTintList = ColorStateList.valueOf(if (sel) ui.p.primary else ui.p.text2)
            }, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            item.addView(pill, LinearLayout.LayoutParams(ui.dp(56), ui.dp(30)))
            item.addView(ui.tv(t.label, if (sel) TS.LABEL else TS.SMALL, if (sel) ui.p.primary else ui.p.text2).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, ui.lp(WRAP, WRAP, top = 3))
            bottomNav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun scrollTop() { stack.lastOrNull()?.scrollView?.smoothScrollTo(0, 0) }

    private fun buildTopBar(s: Screen) {
        topBar.removeAllViews()
        if (stack.size > 1) {
            topBar.addView(ui.iconButton(R.drawable.ic_back, "חזרה", ui.p.text) { onBackPressed() })
        } else {
            topBar.addView(ui.hspace(12))
        }
        val titles = ui.col().apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        titles.addView(ui.tv(s.title, if (stack.size > 1) TS.TITLE else TS.DISPLAY, ui.p.text, 1))
        s.subtitle?.let { titles.addView(ui.tv(it, TS.CAPTION, ui.p.text2, 1)) }
        topBar.addView(titles, ui.lp(0, WRAP, 1f))
        s.actions().forEach { a -> topBar.addView(ui.iconButton(a.icon, a.label, ui.p.text) { a.onClick() }) }
    }

    // ------------------------------------------------------------------ navigation

    fun openTab(t: Tab) {
        saveScroll()
        currentTab = t
        stack.clear()
        stack.add(when (t) {
            Tab.HOME -> HomeScreen(this)
            Tab.TASKS -> TasksScreen(this)
            Tab.SUPPLIERS -> SuppliersScreen(this)
            Tab.EXPENSES -> ExpensesScreen(this)
            Tab.MORE -> MoreScreen(this)
        })
        buildBottomNav()
        render()
    }

    fun push(s: Screen) {
        saveScroll()
        stack.add(s)
        render()
    }

    fun pop() {
        if (stack.size <= 1) return
        ui.hideKeyboard(currentFocus)
        stack.removeAt(stack.size - 1)
        render()
    }

    /** Pops screens until [predicate] matches the top (or one screen is left). */
    fun popUntil(predicate: (Screen) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.size - 1)
        render()
    }

    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        stack.add(s)
        render()
    }

    fun refresh(s: Screen? = null) {
        if (s != null && stack.lastOrNull() !== s) return
        saveScroll()
        render()
    }

    private fun saveScroll() {
        stack.lastOrNull()?.let { s -> s.scrollView?.let { s.savedScroll = it.scrollY } }
    }

    private fun render() {
        val s = stack.lastOrNull() ?: return
        buildTopBar(s)
        content.removeAllViews()
        val v: View = try {
            if (s.keepView) (s.cachedView ?: s.build().also { s.cachedView = it }) else s.build()
        } catch (e: Exception) {
            android.util.Log.e("HaMechutan", "screen build failed", e)
            ui.col(24, 24).apply {
                addView(ui.banner("לא ניתן להציג את המסך. הנתונים שמורים ולא נפגעו.\n${e.javaClass.simpleName}: ${e.message ?: ""}", Tone.DANGER, R.drawable.ic_warning))
                addView(ui.button("חזרה למסך הראשי", BtnKind.TONAL) { openTab(Tab.HOME) })
            }
        }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        s.scrollView = v as? ScrollView
        s.scrollView?.let { sv -> val y = s.savedScroll; sv.post { sv.scrollTo(0, y) } }
        fabView = null
        s.fab()?.let { f -> content.addView(buildFab(f), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) }) }
        bottomNav.visibility = if (stack.size == 1) View.VISIBLE else View.GONE
    }

    private fun buildFab(f: Fab): View = ui.row(Gravity.CENTER).apply {
        background = ui.ripple(ui.shape(ui.p.primary, 18f), 18f)
        elevation = ui.dpf(6)
        setPadding(ui.dp(16), ui.dp(14), ui.dp(if (f.label != null) 20 else 16), ui.dp(14))
        addView(ImageView(this@MainActivity).apply { setImageResource(f.icon); imageTintList = ColorStateList.valueOf(ui.p.onPrimary) }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        if (f.label != null) addView(ui.tv(f.label, TS.BODY_STRONG, ui.p.onPrimary), ui.lp(WRAP, WRAP, start = 8))
        contentDescription = f.label ?: "הוספה"
        isClickable = true
        setOnClickListener { f.onClick() }
    }.also { fabView = it }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (lockView != null || licenseView != null) { moveTaskToBack(true); return }
        val s = stack.lastOrNull() ?: return super.onBackPressed()
        if (s.onBack()) return
        if (s.isDirty()) {
            ui.confirm("לצאת בלי לשמור?", "השינויים שהוזנו במסך זה לא יישמרו.", "יציאה בלי שמירה", "המשך עריכה", true) { pop() }
            return
        }
        when {
            stack.size > 1 -> pop()
            currentTab != Tab.HOME -> openTab(Tab.HOME)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ lifecycle / lock

    override fun onStart() {
        super.onStart()
        if (started && app.pin.isEnabled && lockView == null) {
            val away = SystemClock.elapsedRealtime() - lastStop
            val suppressed = SystemClock.elapsedRealtime() < suppressLockUntil
            val timeout = app.prefs.lockTimeoutSec * 1000L
            if ((!suppressed && away >= timeout) || away > 10 * 60_000L) showLock()
        }
        if (started) applyLicenseGate()
        if (started) refresh()
        started = true
    }

    override fun onResume() {
        super.onResume()
        app.scheduler.requestSync()
    }

    override fun onStop() {
        super.onStop()
        lastStop = SystemClock.elapsedRealtime()
    }

    /** Call before launching an external activity we expect to return from (camera, picker, share). */
    fun suppressLockBriefly() { suppressLockUntil = SystemClock.elapsedRealtime() + 10 * 60_000L }

    fun showLock() {
        if (lockView != null) return
        ui.hideKeyboard(currentFocus)
        val v = LockScreen(this) { unlock() }.build()
        lockView = v
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun unlock() {
        lockView?.let { root.removeView(it) }
        lockView = null
        suppressLockUntil = 0
        refresh()
        openPendingIfUnlocked()
    }

    private fun openPendingIfUnlocked() {
        if (isLocked) return
        pendingOpen?.let { (t, id) -> pendingOpen = null; openTarget(t, id) }
    }

    val isLocked: Boolean get() = lockView != null || licenseView != null

    // ------------------------------------------------------------------ subscription

    /** Shows the login / online-check overlay when needed; refreshes the subscription in the background when due. */
    fun applyLicenseGate() {
        when (val g = app.license.gate()) {
            is LicenseManager.Gate.Open -> {
                if (licenseView != null) closeLicense()
                if (app.license.needsRefresh()) refreshLicenseInBackground()
            }
            is LicenseManager.Gate.Login -> showLicense(LicenseScreen.Mode.Login(g.notice))
            is LicenseManager.Gate.Verify -> showLicense(LicenseScreen.Mode.Verify(g.reason))
        }
    }

    private fun showLicense(mode: LicenseScreen.Mode) {
        if (licenseView != null) return
        ui.hideKeyboard(currentFocus)
        val v = LicenseScreen(this, mode) { closeLicense() }.build()
        licenseView = v
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun closeLicense() {
        licenseView?.let { root.removeView(it) }
        licenseView = null
        refresh()
        openPendingIfUnlocked()
    }

    private fun refreshLicenseInBackground() {
        if (licenseRefreshRunning) return
        licenseRefreshRunning = true
        app.background({ app.license.verify() }) { r ->
            licenseRefreshRunning = false
            val res = r.getOrNull()
            if (res is LicenseManager.Result.Refused && res.revoked) showLicense(LicenseScreen.Mode.Login(res.message))
        }
    }

    /** "Check now" from the settings screen. */
    fun checkLicenseNow(done: () -> Unit) {
        app.background({ app.license.verify() }) { r ->
            when (val res = r.getOrNull()) {
                LicenseManager.Result.Ok -> ui.toast("המנוי תקין")
                is LicenseManager.Result.Refused -> if (res.revoked) showLicense(LicenseScreen.Mode.Login(res.message)) else ui.alert("בדיקת המנוי", res.message)
                is LicenseManager.Result.Offline -> ui.alert("לא ניתן לבדוק כרגע", if (res.detail.isBlank()) res.message else "${res.message}\n\n(${res.detail})")
                null -> ui.alert("שגיאה", r.exceptionOrNull()?.message ?: "")
            }
            done()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN) return
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        intent.action = null
        if (isLocked) pendingOpen = type to id else openTarget(type, id)
    }

    /** Opens the item a notification refers to (from the root of the current tab). */
    fun openTarget(type: String, id: Long) {
        val s = screenFor(type, id)
        if (s == null) { ui.toast("הפריט כבר אינו קיים"); return }
        if (stack.size > 1) openTab(currentTab)
        push(s)
    }

    fun screenFor(type: String, id: Long): Screen? {
        return when (type) {
            "TASK" -> repo().task(id)?.let { TaskDetailScreen(this, id) }
            "EVENT" -> repo().event(id)?.let { EventDetailScreen(this, id) }
            "TRANSPORT" -> repo().transport(id)?.let { TransportDetailScreen(this, id) }
            "EXPENSE_PAYMENT", "EXPENSE" -> repo().expense(id)?.let { ExpenseDetailScreen(this, id) }
            "PAY_EXPENSE" -> repo().expense(id)?.let { PaymentEditScreen(this, id, null) }
            "SUPPLIER" -> repo().supplier(id)?.let { SupplierDetailScreen(this, id) }
            else -> null
        }
    }

    private fun repo() = app.repo

    // ------------------------------------------------------------------ results & permissions

    private val resultCallbacks = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 100

    fun launchForResult(intent: Intent, requestCode: Int? = null, onResult: (resultCode: Int, data: Intent?) -> Unit) {
        val code = requestCode ?: nextRequest++
        resultCallbacks[code] = onResult
        suppressLockBriefly()
        try {
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            resultCallbacks.remove(code)
            ui.alert("לא נמצאה אפליקציה מתאימה", "לא נמצאה במכשיר אפליקציה שיכולה לבצע את הפעולה.")
        }
    }

    fun launch(intent: Intent, failMessage: String = "לא נמצאה במכשיר אפליקציה שיכולה לבצע את הפעולה.") {
        suppressLockBriefly()
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { ui.alert("לא ניתן לבצע", failMessage) }
        catch (e: SecurityException) { ui.alert("לא ניתן לבצע", failMessage) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cb = resultCallbacks.remove(requestCode)
        if (cb != null) cb(resultCode, data)
        else if (requestCode == REQ_CAMERA) handleOrphanCameraResult(resultCode)
    }

    /** If the process was killed while the camera was open, finish the capture after restart. */
    private fun handleOrphanCameraResult(resultCode: Int = RESULT_OK) {
        val pending = app.prefs.pendingCamera ?: return
        app.prefs.pendingCamera = null
        if (resultCode != RESULT_OK) return
        DocumentFlows.completeCameraFromPending(this, pending)
    }

    private val permCallbacks = HashMap<Int, (Boolean) -> Unit>()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permCallbacks.remove(requestCode)?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Makes sure notifications can be shown, explaining why first. Calls [then] in any case;
     * the reminder status shown in the app reflects whether it will really appear.
     */
    fun ensureNotificationPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            if (!app.scheduler.notificationsAllowed()) {
                ui.confirm("ההתראות חסומות", "ההתראות של האפליקציה כבויות בהגדרות המכשיר, ולכן התזכורות לא יוצגו. לפתוח את הגדרות ההתראות?", "פתיחת הגדרות", "לא עכשיו") { openNotificationSettings() }
            }
            then(); return
        }
        val firstTime = !app.prefs.notifPermissionAsked
        if (firstTime || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(this).setTitle("אישור התראות")
                .setMessage("כדי שהתזכורות למשימות, לאירועים, להסעות ולתשלומים יופיעו בטלפון בזמן, צריך לאשר לאפליקציה להציג התראות. ההתראות נוצרות במכשיר בלבד, ללא אינטרנט.")
                .setPositiveButton("המשך") { _, _ ->
                    app.prefs.notifPermissionAsked = true
                    val code = nextRequest++
                    permCallbacks[code] = { granted ->
                        if (!granted) ui.toast("ללא אישור התראות, התזכורות לא יוצגו")
                        app.scheduler.requestSync(); then()
                    }
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), code)
                }
                .setNegativeButton("לא עכשיו") { _, _ -> then() }
                .show()
        } else {
            ui.confirm("ההתראות חסומות", "בעבר נבחר שלא לאשר התראות. כדי לקבל תזכורות יש לאשר התראות בהגדרות האפליקציה.", "פתיחת הגדרות", "לא עכשיו") { openNotificationSettings() }
            then()
        }
    }

    fun openNotificationSettings() {
        launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    fun openAppSettings() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    fun dial(phone: String) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
    }

    fun whatsapp(phone: String) {
        var d = phone.filter { it.isDigit() }
        if (d.startsWith("0")) d = "972" + d.substring(1)
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d")), "לא נמצאה אפליקציה לפתיחת WhatsApp.")
    }

    fun crashReportPending(): Boolean = CrashLog.read(this) != null
}
