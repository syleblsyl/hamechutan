package il.hamechutan.app.platform

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import il.hamechutan.app.R
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.model.EventStatus
import il.hamechutan.app.core.model.TargetType
import il.hamechutan.app.core.model.TaskStatus
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.ui.MainActivity
import org.json.JSONObject

/**
 * Keeps AlarmManager in sync with the reminders in the database.
 *
 * The set of alarms that should exist is computed by the core ([Repo.plannedAlarms]); every sync
 * cancels alarms that are no longer wanted and (re)arms all wanted ones. What was actually handed to
 * AlarmManager is recorded, so the UI can show a reminder as scheduled only when it really is.
 */
class ReminderScheduler(private val app: App) {
    private val am = app.getSystemService(AlarmManager::class.java)
    private val syncRunnable = Runnable { sync() }

    data class Armed(val triggerAt: Long, val exact: Boolean)

    fun canExact(): Boolean = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true

    fun notificationsAllowed(): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val ch = nm.getNotificationChannel(Notifier.CHANNEL_ID)
        return ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Coalesces several changes into one sync on the main thread. */
    fun requestSync() {
        app.main.removeCallbacks(syncRunnable)
        app.main.post(syncRunnable)
    }

    @Synchronized
    fun sync() {
        val repo = app.repo
        val now = System.currentTimeMillis()
        val wanted = try { repo.plannedAlarms(now) } catch (e: Exception) { android.util.Log.e("HaMechutan", "plan failed", e); return }
        val previous = armed()
        val wantedIds = wanted.map { it.reminderId }.toSet()
        previous.keys.filter { it !in wantedIds }.forEach { cancel(it) }
        val result = LinkedHashMap<Long, Armed>()
        val exactOk = canExact()
        for (a in wanted) {
            val pi = firePending(a.reminderId, a.triggerAt)
            var exact = exactOk
            try {
                if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerAt, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerAt, pi)
            } catch (e: SecurityException) {
                exact = false
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerAt, pi) } catch (e2: Exception) { continue }
            } catch (e: Exception) {
                android.util.Log.e("HaMechutan", "arm failed", e); continue
            }
            result[a.reminderId] = Armed(a.triggerAt, exact)
        }
        saveArmed(result)
    }

    private fun cancel(id: Long) {
        val pi = PendingIntent.getBroadcast(app, id.toInt(), fireIntent(id, 0), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    private fun fireIntent(id: Long, triggerAt: Long) = Intent(app, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION_FIRE)
        .setData(Uri.parse("hamechutan://reminder/$id"))
        .putExtra(ReminderReceiver.EXTRA_ID, id)
        .putExtra(ReminderReceiver.EXTRA_TRIGGER, triggerAt)

    private fun firePending(id: Long, triggerAt: Long): PendingIntent =
        PendingIntent.getBroadcast(app, id.toInt(), fireIntent(id, triggerAt), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun armed(): Map<Long, Armed> {
        val s = app.prefs.sp.getString("armed_alarms", null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            val m = LinkedHashMap<Long, Armed>()
            o.keys().forEach { k -> val a = o.getJSONObject(k.toString()); m[k.toString().toLong()] = Armed(a.getLong("t"), a.getBoolean("e")) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveArmed(m: Map<Long, Armed>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("t", v.triggerAt).put("e", v.exact)) }
        app.prefs.sp.edit().putString("armed_alarms", o.toString()).apply()
    }

    /** Human status for a reminder, based on what is really armed and on device permissions. */
    data class UiStatus(val text: String, val ok: Boolean, val problem: Problem?)
    enum class Problem { NOTIFICATIONS_BLOCKED, NOT_EXACT, NOT_ARMED }

    fun uiStatus(st: Repo.ReminderStatus): UiStatus {
        val armed = armed()[st.reminder.id]
        return when (st.state) {
            Repo.ReminderState.PLANNED -> when {
                armed == null -> UiStatus("לא תוזמנה במכשיר. נסו לפתוח מחדש את האפליקציה.", false, Problem.NOT_ARMED)
                !notificationsAllowed() -> UiStatus("ההתראות של האפליקציה חסומות במכשיר — התזכורת לא תוצג", false, Problem.NOTIFICATIONS_BLOCKED)
                !armed.exact -> UiStatus("מתוזמנת ל-${Dates.displayDateTime(st.triggerAt!!)} (בקירוב — אין הרשאה לתזמון מדויק)", true, Problem.NOT_EXACT)
                else -> UiStatus("מתוזמנת ל-${Dates.displayDateTime(st.triggerAt!!)}", true, null)
            }
            Repo.ReminderState.MUTED -> UiStatus("מושתקת — לא תוצג התראה", false, null)
            Repo.ReminderState.NO_DATE -> UiStatus("לא תוזמנה: לפריט אין תאריך", false, null)
            Repo.ReminderState.PASSED -> UiStatus("לא תוזמנה: מועד התזכורת עבר", false, null)
            Repo.ReminderState.FIRED -> UiStatus("ההתראה כבר הוצגה (${st.triggerAt?.let { Dates.displayDateTime(it) } ?: ""})", true, null)
            Repo.ReminderState.INACTIVE -> UiStatus("לא פעילה: ${st.reason ?: "הפריט אינו פעיל"}", false, null)
            Repo.ReminderState.DISABLED -> UiStatus("התזכורות כבויות בהגדרות האפליקציה", false, null)
        }
    }
}

object Notifier {
    const val CHANNEL_ID = "reminders"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "תזכורות", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "תזכורות למשימות, אירועים, הסעות ותשלומים"
            enableVibration(true)
            enableLights(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openIntent(ctx: Context, type: String, id: Long): Intent =
        Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN)
            .setData(Uri.parse("hamechutan://open/$type/$id"))
            .putExtra(MainActivity.EXTRA_TYPE, type)
            .putExtra(MainActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun actionIntent(ctx: Context, action: String, rid: Long) = PendingIntent.getBroadcast(
        ctx, (rid * 10 + action.hashCode() % 10).toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("hamechutan://reminder/$rid/$action"))
            .putExtra(ReminderReceiver.EXTRA_ID, rid), flags()
    )

    fun show(ctx: Context, rid: Long, info: Repo.TargetInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(ctx, (rid * 10 + 1).toInt(), openIntent(ctx, info.type.name, info.id), flags())
        val b = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rings)
            .setContentTitle(info.title)
            .setContentText(info.body)
            .setStyle(Notification.BigTextStyle().bigText(info.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setColor(0xFF1F3A5F.toInt())
            .setShowWhen(true)
        fun action(icon: Int, title: String, pi: PendingIntent) =
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build())
        when (info.type) {
            TargetType.TASK -> action(R.drawable.ic_check_circle, "סמן כבוצע", actionIntent(ctx, ReminderReceiver.ACTION_DONE, rid))
            TargetType.EVENT -> action(R.drawable.ic_check_circle, "סמן כהושלם", actionIntent(ctx, ReminderReceiver.ACTION_DONE, rid))
            TargetType.EXPENSE_PAYMENT -> {
                val pay = PendingIntent.getActivity(ctx, (rid * 10 + 2).toInt(), openIntent(ctx, "PAY_EXPENSE", info.id), flags())
                action(R.drawable.ic_payments, "רישום תשלום", pay)
            }
            TargetType.TRANSPORT -> {}
        }
        if (!info.phone.isNullOrBlank()) {
            val dial = PendingIntent.getActivity(ctx, (rid * 10 + 3).toInt(),
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + info.phone.filter { it.isDigit() || it == '+' })), flags())
            action(R.drawable.ic_phone, if (info.type == TargetType.TRANSPORT) "התקשרות לנהג" else "התקשרות", dial)
        }
        action(R.drawable.ic_clock, "עוד שעה", actionIntent(ctx, ReminderReceiver.ACTION_SNOOZE, rid))
        action(R.drawable.ic_bell_off, "השתקה", actionIntent(ctx, ReminderReceiver.ACTION_MUTE, rid))
        try { nm.notify(rid.toInt(), b.build()) } catch (e: SecurityException) { /* permission revoked */ }
    }

    fun cancel(ctx: Context, rid: Long) = ctx.getSystemService(NotificationManager::class.java).cancel(rid.toInt())
}

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "il.hamechutan.app.REMINDER_FIRE"
        const val ACTION_DONE = "il.hamechutan.app.REMINDER_DONE"
        const val ACTION_MUTE = "il.hamechutan.app.REMINDER_MUTE"
        const val ACTION_SNOOZE = "il.hamechutan.app.REMINDER_SNOOZE"
        const val EXTRA_ID = "rid"
        const val EXTRA_TRIGGER = "trigger"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as App
        val repo = app.repo
        val rid = intent.getLongExtra(EXTRA_ID, -1)
        if (rid < 0) return
        try {
            when (intent.action) {
                ACTION_FIRE -> {
                    val r = repo.reminder(rid) ?: return
                    val st = repo.reminderStatus(r)
                    val now = System.currentTimeMillis()
                    // Only notify if the reminder is still due now (target may have been edited/cancelled).
                    if (st.state == Repo.ReminderState.PLANNED && st.triggerAt != null && st.triggerAt <= now + 60_000 && st.info != null) {
                        Notifier.show(ctx, rid, st.info!!)
                        repo.markReminderFired(rid, st.triggerAt)
                        app.scheduler.sync()
                    } else {
                        app.scheduler.sync()
                    }
                }
                ACTION_DONE -> {
                    val r = repo.reminder(rid)
                    if (r != null) when (r.targetType) {
                        TargetType.TASK -> repo.setTaskStatus(r.targetId, TaskStatus.DONE)
                        TargetType.EVENT -> repo.setEventStatus(r.targetId, EventStatus.DONE)
                        else -> {}
                    }
                    Notifier.cancel(ctx, rid)
                    app.scheduler.sync()
                }
                ACTION_MUTE -> { repo.setReminderMuted(rid, true); Notifier.cancel(ctx, rid); app.scheduler.sync() }
                ACTION_SNOOZE -> {
                    repo.snoozeReminder(rid, System.currentTimeMillis() + 60 * 60_000L)
                    Notifier.cancel(ctx, rid)
                    app.scheduler.sync() // arm the snoozed alarm before the receiver returns
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HaMechutan", "reminder action failed", e)
        }
    }
}

/** Re-arms all reminders after reboot, app update, clock/timezone change or exact-alarm permission change. */
class SystemEventsReceiver : BroadcastReceiver() {
    private val allowed = setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        "android.intent.action.QUICKBOOT_POWERON"
    )

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in allowed) return
        try { (ctx.applicationContext as App).scheduler.sync() } catch (e: Exception) { android.util.Log.e("HaMechutan", "resync failed", e) }
    }
}
