package il.hamechutan.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import il.hamechutan.app.core.update.AvailableUpdate
import il.hamechutan.app.core.update.CheckResult
import il.hamechutan.app.core.update.UpdateCheck
import il.hamechutan.app.core.update.UpdateClient
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * App updates from GitHub Releases: a daily background check, download, signature check and hand-off to
 * Android's package installer. The installer itself also refuses an APK that is not signed with our key.
 */
class Updater(private val ctx: Context, private val files: DocFiles) {
    private val sp = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = UpdateClient(REPOS, "HaMechutan/${App.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")

    /** A downloaded and verified APK waiting for the "install unknown apps" permission. */
    var pendingInstall: File? = null

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - sp.getLong(K_LAST, 0) >= AUTO_INTERVAL_MS || now < sp.getLong(K_LAST, 0)

    /** Network call: run on a background thread. Remembers a found update so the banner survives restarts. */
    fun check(): CheckResult {
        val r = client.check(App.VERSION_NAME)
        val ed = sp.edit().putLong(K_LAST, System.currentTimeMillis())
        when (r) {
            is CheckResult.Available -> ed.putString(K_FOUND, toJson(r.update))
            is CheckResult.UpToDate -> ed.remove(K_FOUND)
            is CheckResult.Failed -> {}
        }
        ed.apply()
        return r
    }

    /** The newest known update that is newer than the installed version, or null. */
    fun available(): AvailableUpdate? {
        val u = sp.getString(K_FOUND, null)?.let { fromJson(it) } ?: return null
        return if (UpdateCheck.isNewer(u, App.VERSION_NAME)) u else null
    }

    /** True when the user chose "not now" for this exact version (the banner stays hidden; Settings still offers it). */
    fun isDismissed(u: AvailableUpdate) = sp.getString(K_DISMISSED, null) == u.versionName
    fun dismiss(u: AvailableUpdate) = sp.edit().putString(K_DISMISSED, u.versionName).apply()

    /** Network call: run on a background thread. Returns the verified APK file. */
    fun download(u: AvailableUpdate, progress: (Int) -> Unit): File {
        files.sharedDir.listFiles()?.filter { it.name.startsWith("HaMechutan-") && (it.name.endsWith(".apk") || it.name.endsWith(".part")) }?.forEach { it.delete() }
        val dest = File(files.sharedDir, "HaMechutan-${u.versionName}.apk")
        client.download(u, dest, progress)
        verify(dest)?.let { problem -> dest.delete(); throw UpdateRejected(problem) }
        return dest
    }

    class UpdateRejected(message: String) : Exception(message)

    /** Null when the APK is ours (same package, newer version, same signing key); otherwise a Hebrew reason. */
    fun verify(apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "הקובץ שהורד פגום. נסו להוריד שוב."
        if (archive.packageName != ctx.packageName) return "הקובץ שהורד אינו עדכון של המחותן."
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) return "הגרסה שהורדה אינה חדשה מהגרסה המותקנת."
        val mine = certs(installed)
        val theirs = certs(archive)
        // Some Android versions do not report an archive's certificates; the installer still enforces them.
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs.intersect(mine).isEmpty())
            return "הקובץ שהורד אינו חתום בחתימה של המחותן, ולכן לא יותקן."
        return null
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun installIntent(apk: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(files.uriForShared(apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun versionCode(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certs(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        return sigs?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    private fun toJson(u: AvailableUpdate) = JSONObject().put("v", u.versionName).put("notes", u.notes).put("url", u.apkUrl)
        .put("name", u.apkName).put("size", u.apkSize).put("page", u.pageUrl).toString()

    private fun fromJson(s: String): AvailableUpdate? = try {
        val o = JSONObject(s)
        AvailableUpdate(o.getString("v"), o.optString("notes"), o.getString("url"), o.optString("name"), o.optLong("size", -1), o.optString("page"))
            .takeIf { u -> UpdateCheck.TRUSTED_PREFIXES.any { u.apkUrl.startsWith(it) } }
    } catch (e: Exception) { null }

    companion object {
        /** Public downloads repository first; the original repository is a fallback until it becomes private. */
        val REPOS = listOf("syleblsyl/hamechutan-releases", "syleblsyl/hamechutan")
        const val AUTO_INTERVAL_MS = 20L * 60 * 60 * 1000
        private const val K_LAST = "last_check"; private const val K_FOUND = "found"; private const val K_DISMISSED = "dismissed"
    }
}
