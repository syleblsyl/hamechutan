package il.hamechutan.app.core.license

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.net.ssl.SSLException

/*
 * Subscription licensing: one username + password per customer, active on one phone at a time.
 * The server (server/license/Code.gs, Google Apps Script) keeps the customers in a Google Sheet.
 * Only the username, password (at login), a device id and the phone model are sent — never wedding data.
 * Every answer from the server is RSA-signed over the request's random nonce, so it cannot be forged
 * or replayed, even on a filtered network that intercepts HTTPS.
 */

/** What the device remembers after a successful login. */
data class LicenseState(
    val username: String,
    val deviceId: String,
    /** Local time of the last approval from the server. */
    val verifiedAt: Long,
    /** Latest local time seen; a clock earlier than this means the clock was turned back. */
    val lastSeenAt: Long,
    /** Subscription end date "yyyy-MM-dd", or "" for none. */
    val expires: String,
)

interface LicenseStore {
    fun load(): LicenseState?
    fun save(s: LicenseState)
    fun clear()
    /** Message for the login screen after the license was revoked; kept across restarts. */
    var notice: String?
}

data class DeviceInfo(val id: String, val name: String)

class HttpReply(val status: Int, val body: String)

fun interface LicenseTransport {
    @Throws(IOException::class)
    fun post(body: String): HttpReply
}

/**
 * POSTs to the Apps Script web app. Google answers a POST with "302 Found" pointing to another host
 * (script.googleusercontent.com) that serves the result to a GET, so redirects are followed by hand.
 */
class HttpLicenseTransport(private val url: String, private val timeoutMs: Int = 25_000) : LicenseTransport {
    override fun post(body: String): HttpReply {
        var target = URL(url)
        var method = "POST"
        var payload: ByteArray? = body.toByteArray(Charsets.UTF_8)
        repeat(MAX_REDIRECTS + 1) {
            val c = target.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.useCaches = false
                c.requestMethod = method
                c.setRequestProperty("Accept", "application/json")
                payload?.let { bytes ->
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val status = c.responseCode
                if (status in 300..399) {
                    val location = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    val next = URL(target, location)
                    if (target.protocol == "https" && next.protocol != "https") throw IOException("refusing redirect to ${next.protocol}")
                    target = next
                    if (status != 307 && status != 308) { method = "GET"; payload = null }
                    return@repeat
                }
                val stream = if (status >= 400) c.errorStream else c.inputStream
                return HttpReply(status, stream?.use { readLimited(it) } ?: "")
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_BODY) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BODY = 64 * 1024
    }
}

enum class NetProblem { NO_CONNECTION, TIMEOUT, BAD_RESPONSE, SERVER_DOWN }

sealed class Outcome {
    data class Approved(val username: String, val expires: String, val serverTime: Long) : Outcome()
    data class Denied(val code: String) : Outcome()
    data class Unreachable(val problem: NetProblem, val detail: String) : Outcome()
}

class LicenseClient(
    private val transport: LicenseTransport,
    publicKeyBase64: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val publicKey: PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))

    fun activate(username: String, password: String, device: DeviceInfo, appVersion: String) =
        call("activate", username, password, device, appVersion)

    fun check(username: String, device: DeviceInfo, appVersion: String) =
        call("check", username, null, device, appVersion)

    private fun call(action: String, username: String, password: String?, device: DeviceInfo, appVersion: String): Outcome {
        val nonce = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val req = JSONObject()
            .put("v", PROTOCOL).put("action", action).put("username", username)
            .put("deviceId", device.id).put("deviceName", device.name).put("appVersion", appVersion).put("nonce", nonce)
        if (password != null) req.put("password", password)
        val reply = try {
            transport.post(req.toString())
        } catch (e: SocketTimeoutException) {
            return Outcome.Unreachable(NetProblem.TIMEOUT, e.toString())
        } catch (e: SSLException) {
            // Typically a filter intercepting HTTPS with a certificate the phone does not trust.
            return Outcome.Unreachable(NetProblem.BAD_RESPONSE, e.toString())
        } catch (e: UnknownHostException) {
            return Outcome.Unreachable(NetProblem.NO_CONNECTION, e.toString())
        } catch (e: ConnectException) {
            return Outcome.Unreachable(NetProblem.NO_CONNECTION, e.toString())
        } catch (e: NoRouteToHostException) {
            return Outcome.Unreachable(NetProblem.NO_CONNECTION, e.toString())
        } catch (e: IOException) {
            return Outcome.Unreachable(NetProblem.NO_CONNECTION, e.toString())
        }
        return interpret(reply, action, device.id, nonce)
    }

    private fun interpret(reply: HttpReply, action: String, deviceId: String, nonce: String): Outcome {
        if (reply.status >= 500) return Outcome.Unreachable(NetProblem.SERVER_DOWN, "HTTP ${reply.status}")
        val json = try {
            JSONObject(reply.body.trim())
        } catch (e: Exception) {
            return Outcome.Unreachable(NetProblem.BAD_RESPONSE, "HTTP ${reply.status}: ${reply.body.take(120)}")
        }
        val sig = json.optString("sig", "")
        if (sig.isEmpty()) {
            val code = json.optString("code", "")
            return Outcome.Unreachable(if (code == "not_configured") NetProblem.SERVER_DOWN else NetProblem.BAD_RESPONSE, "unsigned reply: $code")
        }
        val ok = json.optBoolean("ok", false)
        val code = json.optString("code", "")
        val username = json.optString("username", "")
        val expires = json.optString("expires", "")
        val serverTime = json.optLong("serverTime", -1)
        val payload = listOf(PROTOCOL.toString(), nonce, action, deviceId, if (ok) "1" else "0", code, username, expires, serverTime.toString())
            .joinToString("\n")
        val valid = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(payload.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(sig))
            }
        } catch (e: Exception) {
            false
        }
        if (!valid) return Outcome.Unreachable(NetProblem.BAD_RESPONSE, "signature does not verify")
        return if (ok) Outcome.Approved(username, expires, serverTime) else Outcome.Denied(code)
    }

    companion object {
        const val PROTOCOL = 1
    }
}

/**
 * Decides whether the app may open, using only what is stored on the device, and talks to the server
 * to log in and to re-verify. Wedding data is never touched: a revoked license only shows the login screen.
 *
 * [client] == null means licensing is disabled (a development build without a server configured).
 */
class LicenseManager(
    private val store: LicenseStore,
    private val client: LicenseClient?,
    private val device: DeviceInfo,
    private val appVersion: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    val offlineDays: Int = 7,
) {
    val enabled: Boolean get() = client != null
    val deviceName: String get() = device.name

    sealed class Gate {
        /** The app may be used. [daysLeft] = days until an online check is required. */
        data class Open(val state: LicenseState?, val daysLeft: Int) : Gate()
        data class Login(val notice: String?) : Gate()
        /** Logged in, but the app must reach the server before it can be used. */
        data class Verify(val reason: String) : Gate()
    }

    sealed class Result {
        object Ok : Result()
        /** The server refused. [revoked]: the stored license was removed; show the login screen. */
        data class Refused(val message: String, val revoked: Boolean) : Result()
        /** The server could not be reached or its answer could not be trusted; nothing changed. */
        data class Offline(val message: String) : Result()
    }

    fun state(): LicenseState? = store.load()

    fun gate(): Gate {
        if (client == null) return Gate.Open(null, Int.MAX_VALUE)
        val s = store.load() ?: return Gate.Login(store.notice)
        if (s.deviceId != device.id) {
            store.clear()
            return Gate.Login(LicenseText.MOVED_DEVICE)
        }
        val now = clock()
        if (now + CLOCK_TOLERANCE_MS < s.lastSeenAt) return Gate.Verify(LicenseText.CLOCK_CHANGED)
        val offlineMs = offlineDays * DAY_MS
        if (now - s.verifiedAt >= offlineMs) return Gate.Verify(LicenseText.offlineTooLong(offlineDays))
        if (isPastExpiry(s.expires, now)) return Gate.Verify(LicenseText.EXPIRY_PASSED)
        if (now > s.lastSeenAt) store.save(s.copy(lastSeenAt = now))
        val daysLeft = ((s.verifiedAt + offlineMs - now + DAY_MS - 1) / DAY_MS).toInt()
        return Gate.Open(s, daysLeft)
    }

    /** True when a background check is due (twice a day while the app is used online). */
    fun needsRefresh(): Boolean {
        val s = store.load() ?: return false
        val now = clock()
        return now - s.verifiedAt >= REFRESH_MS || now + CLOCK_TOLERANCE_MS < s.lastSeenAt
    }

    fun login(username: String, password: String): Result {
        val c = client ?: return Result.Ok
        val u = username.trim()
        val p = password.trim()
        if (u.isEmpty() || p.isEmpty()) return Result.Refused(LicenseText.MISSING_FIELDS, false)
        return when (val o = c.activate(u, p, device, appVersion)) {
            is Outcome.Approved -> {
                val now = clock()
                store.save(LicenseState(o.username, device.id, now, now, o.expires))
                store.notice = null
                Result.Ok
            }
            is Outcome.Denied -> Result.Refused(LicenseText.denial(o.code), false)
            is Outcome.Unreachable -> Result.Offline(LicenseText.network(o.problem))
        }
    }

    fun verify(): Result {
        val c = client ?: return Result.Ok
        val s = store.load() ?: return Result.Refused(store.notice ?: "", true)
        return when (val o = c.check(s.username, device, appVersion)) {
            is Outcome.Approved -> {
                if (o.username != s.username) return Result.Offline(LicenseText.network(NetProblem.BAD_RESPONSE))
                val now = clock()
                store.save(s.copy(verifiedAt = now, lastSeenAt = now, expires = o.expires))
                Result.Ok
            }
            is Outcome.Denied -> if (o.code in REVOKING) {
                val msg = LicenseText.denial(o.code)
                store.clear()
                store.notice = msg
                Result.Refused(msg, true)
            } else {
                Result.Offline(LicenseText.denial(o.code))
            }
            is Outcome.Unreachable -> Result.Offline(LicenseText.network(o.problem))
        }
    }

    private fun isPastExpiry(expires: String, now: Long): Boolean {
        if (expires.isEmpty()) return false
        val end = try { LocalDate.parse(expires) } catch (e: Exception) { return false }
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().isAfter(end)
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val REFRESH_MS = 12L * 60 * 60 * 1000
        const val CLOCK_TOLERANCE_MS = 10L * 60 * 1000
        /** Answers that mean this phone may no longer use the account. */
        val REVOKING = setOf("blocked", "expired", "other_device", "not_activated", "unknown_user")
    }
}

/** User-facing texts (Hebrew, plural address). */
object LicenseText {
    const val MISSING_FIELDS = "יש להזין שם משתמש וסיסמה."
    const val MOVED_DEVICE = "פרטי הכניסה שמורים לטלפון אחר. התחברו מחדש."
    const val CLOCK_CHANGED = "השעון בטלפון שונה. כדי להמשיך, התחברו לאינטרנט לבדיקת המנוי. הנתונים שלכם שמורים."
    const val EXPIRY_PASSED = "תאריך תוקף המנוי עבר. התחברו לאינטרנט כדי לבדוק אם הוא חודש. הנתונים שלכם שמורים."

    fun offlineTooLong(days: Int) =
        "עברו $days ימים מאז בדיקת המנוי האחרונה. כדי להמשיך, התחברו לאינטרנט לבדיקה קצרה. הנתונים שלכם שמורים."

    fun denial(code: String): String = when (code) {
        "bad_credentials" -> "שם המשתמש או הסיסמה שגויים."
        "rate_limited" -> "יותר מדי ניסיונות שגויים. נסו שוב בעוד רבע שעה."
        "blocked" -> "החשבון חסום. לפרטים פנו למוכר."
        "expired" -> "תוקף המנוי הסתיים. לחידוש פנו למוכר."
        "other_device" -> "החשבון כבר פעיל בטלפון אחר. כדי לעבור לטלפון הזה, פנו למוכר ובקשו לשחרר את הטלפון הקודם."
        "not_activated" -> "החשבון נותק מהטלפון הזה. כדי להמשיך, התחברו שוב."
        "unknown_user" -> "החשבון לא נמצא. לפרטים פנו למוכר."
        else -> "שרת המנויים החזיר שגיאה. נסו שוב מאוחר יותר."
    }

    fun network(p: NetProblem): String = when (p) {
        NetProblem.NO_CONNECTION -> "אין חיבור לאינטרנט. התחברו לרשת ונסו שוב."
        NetProblem.TIMEOUT -> "השרת לא הגיב בזמן. נסו שוב."
        NetProblem.SERVER_DOWN -> "שרת המנויים אינו זמין כרגע. נסו שוב מאוחר יותר."
        NetProblem.BAD_RESPONSE -> "לא התקבלה תשובה תקינה משרת המנויים. אם יש לכם אינטרנט מסונן, " +
            "בקשו מחברת הסינון לאשר את הכתובות script.google.com ו-script.googleusercontent.com."
    }
}
