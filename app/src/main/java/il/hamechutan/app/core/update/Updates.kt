package il.hamechutan.app.core.update

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * In-app update check against GitHub Releases (the public downloads repository).
 * The app asks GitHub for the latest release, compares versions, and can download the APK.
 * Installing is done by Android's package installer, which only accepts an APK signed with the same key;
 * the platform layer also checks the signature before handing the file to the installer.
 */

data class AvailableUpdate(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val pageUrl: String,
)

object UpdateCheck {
    /** Only APKs from these URL prefixes are accepted (protects against a forged API answer). */
    val TRUSTED_PREFIXES = listOf("https://github.com/syleblsyl/")

    /**
     * Parses GitHub's `GET /repos/{owner}/{repo}/releases/latest` answer.
     * Returns null for drafts, prereleases, missing APK assets or untrusted download links.
     */
    fun parseLatest(json: String, trusted: List<String> = TRUSTED_PREFIXES): AvailableUpdate? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val version = o.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
        if (!isVersion(version)) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (trusted.none { url.startsWith(it) }) continue
            return AvailableUpdate(
                versionName = version,
                notes = o.optString("body", "").trim(),
                apkUrl = url,
                apkName = name,
                apkSize = a.optLong("size", -1),
                pageUrl = o.optString("html_url", "").takeIf { u -> trusted.any { u.startsWith(it) } } ?: url,
            )
        }
        return null
    }

    fun isVersion(v: String) = Regex("^\\d+(\\.\\d+){0,3}$").matches(v)

    /** Numeric comparison of dotted versions: 1.10.0 > 1.9.3, 1.2 == 1.2.0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: AvailableUpdate?, currentVersion: String): Boolean =
        candidate != null && isVersion(currentVersion) && compareVersions(candidate.versionName, currentVersion) > 0

    /** Release notes as written in CHANGELOG.md, without markdown bullets, for a plain dialog. */
    fun plainNotes(notes: String): String = notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.substring(2) else it }
}

sealed class CheckResult {
    data class Available(val update: AvailableUpdate) : CheckResult()
    /** No newer version ([latest] = the newest version found, if any). */
    data class UpToDate(val latest: String?) : CheckResult()
    data class Failed(val reason: String) : CheckResult()
}

/** Talks to GitHub. [repos] are tried in order; the first that has a release wins. */
class UpdateClient(
    private val repos: List<String>,
    private val userAgent: String,
    private val apiBase: String = "https://api.github.com",
    private val timeoutMs: Int = 20_000,
    private val trusted: List<String> = UpdateCheck.TRUSTED_PREFIXES,
) {
    fun check(currentVersion: String): CheckResult {
        var lastError: String? = null
        var latestSeen: String? = null
        for (repo in repos) {
            val c = try {
                (URL("$apiBase/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs; readTimeout = timeoutMs
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", userAgent)
                }
            } catch (e: IOException) { lastError = e.toString(); continue }
            try {
                val status = c.responseCode
                if (status == 404) continue // repository missing or private: try the next one
                if (status != 200) { lastError = "HTTP $status"; continue }
                val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val u = UpdateCheck.parseLatest(body, trusted) ?: continue
                latestSeen = u.versionName
                return if (UpdateCheck.isNewer(u, currentVersion)) CheckResult.Available(u) else CheckResult.UpToDate(u.versionName)
            } catch (e: IOException) {
                lastError = e.toString()
            } finally {
                c.disconnect()
            }
        }
        return if (lastError != null && latestSeen == null) CheckResult.Failed(lastError!!) else CheckResult.UpToDate(latestSeen)
    }

    /**
     * Downloads the APK to [dest] (through a temporary file), reporting progress 0..100 (or -1 when the size
     * is unknown). Throws IOException on failure or when the size does not match what GitHub announced.
     */
    fun download(update: AvailableUpdate, dest: File, progress: (Int) -> Unit) {
        if (trusted.none { update.apkUrl.startsWith(it) }) throw IOException("untrusted download URL")
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        val c = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs * 3
            instanceFollowRedirects = true // GitHub redirects the asset to its download host
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = if (update.apkSize > 0) update.apkSize else c.contentLengthLong
            var done = 0L
            var lastPct = -2
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            if (update.apkSize > 0 && done != update.apkSize) throw IOException("size mismatch: $done != ${update.apkSize}")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("cannot move the downloaded file")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }
}
