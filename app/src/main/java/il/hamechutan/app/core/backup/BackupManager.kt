package il.hamechutan.app.core.backup

import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.db.Schema
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Backup format (ZIP):
 *   manifest.json  - format id, versions, creation time, row counts, SHA-256 of every entry
 *   data.json      - every table as {columns:[...], rows:[[...]]}; unknown columns are ignored on restore,
 *                    missing columns get their defaults, so older/newer field sets stay compatible
 *   files/<name>   - the actual attached documents/photos/receipts (not just paths)
 *
 * Restore never touches existing data until the whole archive has been validated (checksums, JSON,
 * versions, referenced files). The database part runs in one transaction with foreign-key checks; the
 * documents folder is swapped only together with a successful database commit.
 */
class BackupManager(private val repo: Repo, private val docsDir: File, private val workDir: File, private val appVersion: String) {

    companion object {
        const val FORMAT_ID = "hamechutan-backup"
        const val FORMAT_VERSION = 1
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    }

    data class Info(
        val createdAt: String, val appVersion: String, val schemaVersion: Int, val formatVersion: Int,
        val weddingTitle: String?, val counts: Map<String, Int>, val fileCount: Int, val totalFileBytes: Long,
        val missingFiles: List<String>
    )

    // ------------------------------------------------------------------ create

    /** Writes a complete backup to [out]. Returns its info (including documents missing on disk, if any). */
    fun create(out: OutputStream): Info {
        val db = repo.db
        val data = JSONObject()
        val tables = JSONObject()
        val counts = LinkedHashMap<String, Int>()
        db.transaction {
            for (t in Schema.TABLES) {
                val cols = columnsOf(t)
                val rowsJson = JSONArray()
                db.rows("SELECT * FROM $t ORDER BY rowid").forEach { r ->
                    val arr = JSONArray()
                    cols.forEach { c -> arr.put(toJson(r.values[c])) }
                    rowsJson.put(arr)
                }
                tables.put(t, JSONObject().put("columns", JSONArray(cols)).put("rows", rowsJson))
                counts[t] = rowsJson.length()
            }
        }
        data.put("schemaVersion", Schema.VERSION)
        data.put("tables", tables)
        val dataBytes = data.toString().toByteArray(Charsets.UTF_8)

        val fileNames = repo.allDocumentFileNames()
        val missing = mutableListOf<String>()
        val filesJson = JSONArray()
        var totalBytes = 0L
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(dataBytes)
            zip.closeEntry()
            for (name in fileNames) {
                val f = File(docsDir, name)
                if (!SAFE_NAME.matches(name) || !f.isFile) { missing += name; continue }
                zip.putNextEntry(ZipEntry("files/$name"))
                val md = MessageDigest.getInstance("SHA-256")
                var size = 0L
                FileInputStream(f).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        zip.write(buf, 0, n); md.update(buf, 0, n); size += n
                    }
                }
                zip.closeEntry()
                totalBytes += size
                filesJson.put(JSONObject().put("name", name).put("size", size).put("sha256", hex(md.digest())))
            }
            val manifest = JSONObject()
                .put("format", FORMAT_ID)
                .put("formatVersion", FORMAT_VERSION)
                .put("schemaVersion", Schema.VERSION)
                .put("appVersion", appVersion)
                .put("createdAt", Instant.ofEpochMilli(repo.now()).toString())
                .put("weddingTitle", repo.wedding().title)
                .put("counts", JSONObject(counts as Map<*, *>))
                .put("data", JSONObject().put("name", "data.json").put("size", dataBytes.size).put("sha256", hex(sha256(dataBytes))))
                .put("files", filesJson)
                .put("missingFiles", JSONArray(missing))
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return Info(Instant.ofEpochMilli(repo.now()).toString(), appVersion, Schema.VERSION, FORMAT_VERSION, repo.wedding().title,
            counts, filesJson.length(), totalBytes, missing)
    }

    // ------------------------------------------------------------------ inspect / validate

    private class Loaded(val info: Info, val data: JSONObject, val files: List<Pair<String, String>>)

    /** Fully validates a backup file without changing anything. Throws [BackupException] with a Hebrew message. */
    fun inspect(file: File): Info = load(file).info

    private fun load(file: File): Loaded {
        val zip = try { ZipFile(file) } catch (e: Exception) {
            throw BackupException("הקובץ שנבחר אינו קובץ גיבוי תקין (לא ניתן לפתוח אותו כקובץ ZIP).", e)
        }
        zip.use { z ->
            val manifestEntry = z.getEntry("manifest.json") ?: throw BackupException("הקובץ אינו גיבוי של אפליקציית המחותן (חסר קובץ manifest).")
            val manifest = try { JSONObject(readText(z, manifestEntry, 5_000_000)) } catch (e: BackupException) { throw e } catch (e: Exception) {
                throw BackupException("קובץ התיאור של הגיבוי פגום.", e)
            }
            if (manifest.optString("format") != FORMAT_ID) throw BackupException("הקובץ אינו גיבוי של אפליקציית המחותן.")
            val formatVersion = manifest.optInt("formatVersion", -1)
            val schemaVersion = manifest.optInt("schemaVersion", -1)
            if (formatVersion < 1 || schemaVersion < 1) throw BackupException("פרטי הגרסה בגיבוי חסרים או פגומים.")
            if (formatVersion > FORMAT_VERSION || schemaVersion > Schema.VERSION) {
                throw BackupException("הגיבוי נוצר בגרסה חדשה יותר של האפליקציה (${manifest.optString("appVersion")}). יש לעדכן את האפליקציה ואז לשחזר.")
            }
            // data.json
            val dataMeta = manifest.optJSONObject("data") ?: throw BackupException("הגיבוי פגום: חסר תיאור הנתונים.")
            val dataEntry = z.getEntry("data.json") ?: throw BackupException("הגיבוי פגום: קובץ הנתונים חסר.")
            val dataBytes = readBytes(z, dataEntry, 500_000_000)
            if (hex(sha256(dataBytes)) != dataMeta.optString("sha256")) throw BackupException("הגיבוי פגום: קובץ הנתונים אינו תואם לחתימת הבדיקה (ייתכן שהקובץ נקטע בהעברה).")
            val data = try { JSONObject(String(dataBytes, Charsets.UTF_8)) } catch (e: Exception) { throw BackupException("הגיבוי פגום: לא ניתן לקרוא את הנתונים.", e) }
            val tables = data.optJSONObject("tables") ?: throw BackupException("הגיבוי פגום: אין טבלאות נתונים.")
            if (!tables.has("wedding") || tables.getJSONObject("wedding").getJSONArray("rows").length() == 0) {
                throw BackupException("הגיבוי פגום: חסרים פרטי החתונה.")
            }
            val counts = LinkedHashMap<String, Int>()
            tables.keys().forEach { k ->
                val t = k.toString()
                val tj = tables.getJSONObject(t)
                val cols = tj.getJSONArray("columns")
                val rows = tj.getJSONArray("rows")
                for (i in 0 until rows.length()) {
                    if (rows.getJSONArray(i).length() != cols.length()) throw BackupException("הגיבוי פגום: שורה לא תקינה בטבלה $t.")
                }
                counts[t] = rows.length()
            }
            // files
            val filesJson = manifest.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<Pair<String, String>>()
            var total = 0L
            for (i in 0 until filesJson.length()) {
                val fj = filesJson.getJSONObject(i)
                val name = fj.getString("name")
                if (!SAFE_NAME.matches(name)) throw BackupException("הגיבוי מכיל שם קובץ לא תקין.")
                val entry = z.getEntry("files/$name") ?: throw BackupException("הגיבוי פגום: הקובץ המצורף \"$name\" חסר.")
                val sha = fj.getString("sha256")
                val (size, digest) = digestEntry(z, entry)
                if (digest != sha || size != fj.optLong("size", -1)) throw BackupException("הגיבוי פגום: הקובץ המצורף \"$name\" פגום.")
                total += size
                if (total > MAX_TOTAL_BYTES) throw BackupException("הגיבוי גדול מדי.")
                files += name to sha
            }
            // every document row must have its file in the archive (unless it was already missing when backed up)
            val missingAtBackup = manifest.optJSONArray("missingFiles")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val inArchive = files.map { it.first }.toSet()
            tables.optJSONObject("document")?.let { dt ->
                val cols = dt.getJSONArray("columns")
                val idx = (0 until cols.length()).firstOrNull { cols.getString(it) == "file_name" } ?: throw BackupException("הגיבוי פגום: טבלת המסמכים חסרה עמודה.")
                val rows = dt.getJSONArray("rows")
                for (i in 0 until rows.length()) {
                    val fn = rows.getJSONArray(i).getString(idx)
                    if (fn !in inArchive && fn !in missingAtBackup) throw BackupException("הגיבוי פגום: הקובץ של מסמך \"$fn\" חסר.")
                }
            }
            val info = Info(
                manifest.optString("createdAt"), manifest.optString("appVersion"), schemaVersion, formatVersion,
                manifest.optString("weddingTitle").ifEmpty { null }, counts, files.size, total, missingAtBackup
            )
            return Loaded(info, data, files)
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Replaces all current data with the backup. Validates everything first; on any failure the current
     * data and documents stay exactly as they were.
     */
    fun restore(file: File): Info {
        val loaded = load(file)
        val staging = File(workDir, "restore_staging_${System.nanoTime()}")
        val oldDocs = File(workDir, "docs_before_restore_${System.nanoTime()}")
        staging.mkdirs()
        try {
            // 1. extract and re-verify files into a staging folder
            ZipFile(file).use { z ->
                for ((name, sha) in loaded.files) {
                    val entry = z.getEntry("files/$name")!!
                    val target = File(staging, name)
                    val md = MessageDigest.getInstance("SHA-256")
                    z.getInputStream(entry).use { input -> FileOutputStream(target).use { out -> copyDigest(input, out, md) } }
                    if (hex(md.digest()) != sha) throw BackupException("הקובץ \"$name\" נפגם בזמן החילוץ.")
                }
            }
            // 2. swap documents folder (old one kept until the database commit succeeds)
            val hadDocs = docsDir.exists()
            if (hadDocs && !docsDir.renameTo(oldDocs)) throw BackupException("לא ניתן להכין את תיקיית המסמכים לשחזור.")
            if (!staging.renameTo(docsDir)) {
                if (hadDocs) oldDocs.renameTo(docsDir)
                throw BackupException("לא ניתן להעביר את המסמכים המשוחזרים למקומם.")
            }
            // 3. database in one transaction
            try {
                writeTables(loaded.data)
            } catch (e: Exception) {
                docsDir.deleteRecursively()
                if (hadDocs) oldDocs.renameTo(docsDir) else docsDir.mkdirs()
                throw if (e is BackupException) e else BackupException("שחזור הנתונים נכשל. הנתונים הקיימים לא שונו. (${e.message})", e)
            }
            oldDocs.deleteRecursively()
            repo.invalidateCaches()
            repo.onChange?.invoke()
            return loaded.info
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun writeTables(data: JSONObject) {
        val db = repo.db
        val tables = data.getJSONObject("tables")
        db.transaction {
            db.exec("PRAGMA defer_foreign_keys = ON")
            for (t in Schema.TABLES.reversed()) db.exec("DELETE FROM $t")
            for (t in Schema.TABLES) {
                val tj = tables.optJSONObject(t) ?: continue
                val current = columnsOf(t).toSet()
                val cols = tj.getJSONArray("columns")
                val colNames = (0 until cols.length()).map { cols.getString(it) }
                val keep = colNames.indices.filter { colNames[it] in current }
                if (keep.isEmpty()) continue
                val sql = "INSERT INTO $t (${keep.joinToString(",") { colNames[it] }}) VALUES (${keep.joinToString(",") { "?" }})"
                val rows = tj.getJSONArray("rows")
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONArray(i)
                    db.insert(sql, *keep.map { fromJson(r.opt(it)) }.toTypedArray())
                }
            }
            val violations = db.rows("PRAGMA foreign_key_check")
            if (violations.isNotEmpty()) throw BackupException("הגיבוי מכיל קישורים לא תקינים בין רשומות (${violations.size}). השחזור בוטל והנתונים הקיימים לא שונו.")
            if (db.rows("SELECT id FROM wedding WHERE is_active=1").isEmpty()) throw BackupException("בגיבוי אין חתונה פעילה.")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun columnsOf(table: String): List<String> = repo.db.rows("PRAGMA table_info($table)").map { it.str("name") }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Long, is Int -> (v as Number).toLong()
        is Double -> v
        is String -> v
        is ByteArray -> throw BackupException("ערך בינארי אינו נתמך בגיבוי")
        else -> v.toString()
    }

    private fun fromJson(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is Int -> v.toLong()
        is Long -> v
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9e15) v.toLong() else v
        is Number -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v
        else -> v.toString()
    }

    private fun readBytes(z: ZipFile, e: ZipEntry, limit: Long): ByteArray {
        z.getInputStream(e).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > limit) throw BackupException("קובץ בגיבוי גדול מהצפוי.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun readText(z: ZipFile, e: ZipEntry, limit: Long) = String(readBytes(z, e, limit), Charsets.UTF_8)

    private fun digestEntry(z: ZipFile, e: ZipEntry): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        val size = z.getInputStream(e).use { copyDigest(it, null, md) }
        return size to hex(md.digest())
    }

    private fun copyDigest(input: InputStream, out: OutputStream?, md: MessageDigest): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            md.update(buf, 0, n); out?.write(buf, 0, n); total += n
            if (total > MAX_TOTAL_BYTES) throw BackupException("קובץ בגיבוי גדול מדי.")
        }
        return total
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
