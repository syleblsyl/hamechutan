package il.hamechutan.app.platform

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import il.hamechutan.app.core.data.Repo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {

    lateinit var repo: Repo
    lateinit var files: DocFiles
    lateinit var scheduler: ReminderScheduler
    lateinit var prefs: Prefs
    lateinit var pin: PinManager

    /** Background thread for heavy work (backup, restore, PDF, image processing). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        prefs = Prefs(this)
        repo = Repo(AndroidSqlDb(DbHelper(this).writableDatabase))
        files = DocFiles(this)
        pin = PinManager(getSharedPreferences("security", Context.MODE_PRIVATE))
        scheduler = ReminderScheduler(this)
        Notifier.ensureChannel(this)
        repo.onChange = { scheduler.sync() } // synchronous so shown reminder status is always current
        try {
            repo.cleanupOrphanReminders()
            scheduler.sync()
        } catch (e: Exception) {
            android.util.Log.e("HaMechutan", "initial sync failed", e)
        }
    }

    /** Runs [work] on the background thread and delivers the result (or error) on the main thread. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = try { Result.success(work()) } catch (e: Throwable) { Result.failure(e) }
            main.post { done(r) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
        /** Version comes from version.properties via the APK manifest (single source of truth). */
        val VERSION_NAME: String get() = try {
            instance.packageManager.getPackageInfo(instance.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        val VERSION_CODE: Long get() = try {
            val pi = instance.packageManager.getPackageInfo(instance.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }
}

/** Device-local preferences (not part of the wedding data). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    var lockTimeoutSec: Int
        get() = sp.getInt("lock_timeout", 60)
        set(v) = sp.edit().putInt("lock_timeout", v).apply()

    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0)
        set(v) = sp.edit().putLong("last_backup_at", v).apply()

    var notifPermissionAsked: Boolean
        get() = sp.getBoolean("notif_asked", false)
        set(v) = sp.edit().putBoolean("notif_asked", v).apply()

    var welcomeDismissed: Boolean
        get() = sp.getBoolean("welcome_dismissed", false)
        set(v) = sp.edit().putBoolean("welcome_dismissed", v).apply()

    var pendingCamera: String?
        get() = sp.getString("pending_camera", null)
        set(v) = sp.edit().putString("pending_camera", v).apply()
}

/** Saves uncaught crashes to a file so the user can share them after testing on a real phone. */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(ctx.filesDir, FILE).writeText(
                    "המחותן ${App.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n${java.util.Date()}\nthread: ${t.name}\n\n$sw"
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }
    fun file(ctx: Context) = File(ctx.filesDir, FILE)
}
