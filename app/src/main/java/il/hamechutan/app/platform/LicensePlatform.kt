package il.hamechutan.app.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import il.hamechutan.app.core.license.DeviceInfo
import il.hamechutan.app.core.license.HttpLicenseTransport
import il.hamechutan.app.core.license.LicenseClient
import il.hamechutan.app.core.license.LicenseManager
import il.hamechutan.app.core.license.LicenseState
import il.hamechutan.app.core.license.LicenseStore
import java.security.MessageDigest
import java.util.UUID

/**
 * License state in its own preferences file ("license"), so wiping the wedding data
 * (or restoring a backup) never logs the user out, and logging out never touches the data.
 */
class PrefsLicenseStore(private val sp: SharedPreferences) : LicenseStore {
    override fun load(): LicenseState? {
        val u = sp.getString(USER, null) ?: return null
        return LicenseState(u, sp.getString(DEVICE, "") ?: "", sp.getLong(VERIFIED, 0), sp.getLong(SEEN, 0), sp.getString(EXPIRES, "") ?: "")
    }

    override fun save(s: LicenseState) {
        sp.edit().putString(USER, s.username).putString(DEVICE, s.deviceId).putLong(VERIFIED, s.verifiedAt)
            .putLong(SEEN, s.lastSeenAt).putString(EXPIRES, s.expires).commit()
    }

    override fun clear() {
        sp.edit().remove(USER).remove(DEVICE).remove(VERIFIED).remove(SEEN).remove(EXPIRES).commit()
    }

    override var notice: String?
        get() = sp.getString(NOTICE, null)
        set(v) { sp.edit().apply { if (v == null) remove(NOTICE) else putString(NOTICE, v) }.commit() }

    private companion object {
        const val USER = "username"; const val DEVICE = "device_id"; const val VERIFIED = "verified_at"
        const val SEEN = "last_seen_at"; const val EXPIRES = "expires"; const val NOTICE = "notice"
    }
}

object DeviceIdentity {
    /**
     * A stable id for this phone + app. ANDROID_ID is already specific to the app's signing key (Android 8+),
     * stays the same after reinstalling, and changes only after a factory reset. It is hashed before use.
     */
    fun info(ctx: Context, sp: SharedPreferences): DeviceInfo {
        val androidId = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (e: Exception) { null }
        val raw = if (!androidId.isNullOrBlank() && androidId != BROKEN_ID) "aid:$androidId" else fallbackId(sp)
        val digest = MessageDigest.getInstance("SHA-256").digest("hamechutan:$raw".toByteArray())
        val id = digest.take(16).joinToString("") { "%02x".format(it) }
        return DeviceInfo(id, modelName())
    }

    fun modelName(): String {
        val maker = (Build.MANUFACTURER ?: "").trim().replaceFirstChar { it.uppercase() }
        val model = (Build.MODEL ?: "").trim()
        return when {
            model.isEmpty() -> maker.ifEmpty { "Android" }
            model.lowercase().startsWith(maker.lowercase()) -> model
            else -> "$maker $model"
        }
    }

    private fun fallbackId(sp: SharedPreferences): String =
        sp.getString("fallback_id", null) ?: ("uuid:" + UUID.randomUUID()).also { sp.edit().putString("fallback_id", it).commit() }

    /** A value some old emulators/devices return for every device. */
    private const val BROKEN_ID = "9774d56d682e549c"
}

object LicenseSetup {
    fun create(ctx: Context, appVersion: String): LicenseManager {
        val sp = ctx.getSharedPreferences("license", Context.MODE_PRIVATE)
        val client = if (LicenseConfig.SERVER_URL.isNotBlank() && LicenseConfig.PUBLIC_KEY.isNotBlank())
            LicenseClient(HttpLicenseTransport(LicenseConfig.SERVER_URL), LicenseConfig.PUBLIC_KEY) else null
        return LicenseManager(PrefsLicenseStore(sp), client, DeviceIdentity.info(ctx, sp), appVersion)
    }
}
