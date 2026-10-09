package il.hamechutan.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/** Local storage of attached documents and photos (app-private), plus shareable temp files. */
class DocFiles(private val ctx: Context) {
    val docsDir: File = File(ctx.filesDir, "docs").apply { mkdirs() }
    val sharedDir: File get() = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val cameraDir: File get() = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val workDir: File get() = File(ctx.filesDir, "work").apply { mkdirs() }

    data class Imported(val fileName: String, val mime: String?, val size: Long, val originalName: String?)

    private val rnd = SecureRandom()

    fun newName(ext: String): String {
        val r = ByteArray(4).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val e = ext.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        return "doc_${System.currentTimeMillis()}_$r.$e"
    }

    fun file(name: String) = File(docsDir, name)

    fun extFor(mime: String?, fallbackName: String?): String {
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrEmpty()) return fromMime
        val fromName = fallbackName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 6 }
        return fromName ?: "bin"
    }

    fun mimeFor(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Copies a picked document (content:// URI) into private storage. Large photos are recompressed. */
    fun importUri(uri: Uri): Imported {
        val cr = ctx.contentResolver
        var display: String? = null
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) display = c.getString(0)
            }
        } catch (_: Exception) {}
        val mime = cr.getType(uri) ?: display?.let { mimeFor(it) }
        val name = newName(extFor(mime, display))
        val target = file(name)
        (cr.openInputStream(uri) ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ שנבחר")).use { input ->
            FileOutputStream(target).use { input.copyTo(it) }
        }
        if (target.length() == 0L) { target.delete(); throw IllegalStateException("הקובץ שנבחר ריק") }
        if (mime != null && (mime == "image/jpeg" || mime == "image/png" || mime == "image/heic" || mime == "image/webp") && target.length() > 2_500_000) {
            compressImage(target)?.let { out -> target.delete(); return Imported(out.name, "image/jpeg", out.length(), display) }
        }
        return Imported(name, mime, target.length(), display)
    }

    /** A temp file for the camera app to write into. */
    fun newCameraFile(): File = File(cameraDir, "cam_${System.currentTimeMillis()}.jpg")

    /** Moves a camera capture into private storage (rotated per EXIF and size-limited). */
    fun importCamera(src: File): Imported {
        if (!src.exists() || src.length() == 0L) throw IllegalStateException("לא התקבלה תמונה מהמצלמה")
        val out = compressImage(src) ?: run {
            val name = newName("jpg"); src.copyTo(file(name), overwrite = true); file(name)
        }
        src.delete()
        return Imported(out.name, "image/jpeg", out.length(), null)
    }

    /** Decodes with sampling, applies EXIF rotation, saves as JPEG (max 2400px). Returns new file or null. */
    private fun compressImage(src: File): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
            var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val maxSide = maxOf(bmp.width, bmp.height)
            if (maxSide > 2400) {
                val f = 2400f / maxSide
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            }
            val rot = try {
                when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            val out = file(newName("jpg"))
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            out
        } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
    }

    fun delete(name: String?) { if (name != null && !name.contains('/')) file(name).delete() }

    private val thumbs = LruCache<String, Bitmap>(24)

    /** Small preview for image documents (null for non-images or unreadable files). */
    fun thumbnail(name: String, sizePx: Int): Bitmap? {
        thumbs.get("$name@$sizePx")?.let { return it }
        val f = file(name)
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return null
            var s = 1
            while (minOf(o.outWidth, o.outHeight) / (s * 2) >= sizePx) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.also { thumbs.put("$name@$sizePx", it) }
        } catch (e: Throwable) { null }
    }

    /** Large image for in-app viewing, limited to [maxSide] pixels. */
    fun loadImage(name: String, maxSide: Int): Bitmap? = try {
        val f = file(name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    fun uriForDoc(name: String, displayName: String? = null): Uri = DocProvider.uri("docs", name, displayName)
    fun uriForShared(f: File, displayName: String? = null): Uri = DocProvider.uri("shared", f.name, displayName)
    fun uriForCamera(f: File): Uri = DocProvider.uri("camera", f.name, null)
}

/**
 * Minimal FileProvider replacement (AndroidX is not available): serves files from the docs, shared and
 * camera folders through content:// URIs with per-intent grants. Not exported.
 */
class DocProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "il.hamechutan.app.files"
        fun uri(root: String, name: String, display: String?): Uri {
            val b = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(root).appendPath(name)
            if (!display.isNullOrBlank()) b.appendQueryParameter("name", display)
            return b.build()
        }
    }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val segs = uri.pathSegments
        if (segs.size != 2) throw SecurityException("bad uri")
        val ctx = context!!
        val dir = when (segs[0]) {
            "docs" -> File(ctx.filesDir, "docs")
            "shared" -> File(ctx.cacheDir, "shared")
            "camera" -> File(ctx.cacheDir, "camera")
            else -> throw SecurityException("bad root")
        }
        val f = File(dir, segs[1])
        if (f.canonicalFile.parentFile != dir.canonicalFile) throw SecurityException("path escape")
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolve(uri)
        if (mode.contains('w')) f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val display = uri.getQueryParameter("name")?.let { n ->
            val ext = f.name.substringAfterLast('.', "")
            val safe = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
            if (ext.isNotEmpty() && !safe.endsWith(".$ext")) "$safe.$ext" else safe
        } ?: f.name
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> display
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
