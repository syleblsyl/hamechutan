package il.hamechutan.app.core

import com.sun.net.httpserver.HttpServer
import il.hamechutan.app.core.license.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.ZoneId
import java.util.Base64

class MemLicenseStore : LicenseStore {
    var s: LicenseState? = null
    override var notice: String? = null
    override fun load() = s
    override fun save(s: LicenseState) { this.s = s }
    override fun clear() { s = null }
}

/** Plays the server: answers each request with [answer], signed like Code.gs signs. */
class FakeServer(val keys: KeyPair = newKeys()) : LicenseTransport {
    var answer: (JSONObject) -> Pair<Boolean, String> = { true to "" } // ok, code
    var expires = ""
    var tamper: (JSONObject) -> Unit = {}
    var fail: IOException? = null
    val requests = ArrayList<JSONObject>()

    override fun post(body: String): HttpReply {
        fail?.let { throw it }
        val req = JSONObject(body)
        requests += req
        val (ok, code) = answer(req)
        val username = req.getString("username").lowercase().replace(Regex("[\\s-]"), "")
        val serverTime = 1_800_000_000_000L
        val payload = listOf("1", req.getString("nonce"), req.getString("action"), req.getString("deviceId"), if (ok) "1" else "0",
            code, username, if (ok) expires else "", serverTime.toString()).joinToString("\n")
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(payload.toByteArray()); sign() }
        val res = JSONObject().put("ok", ok).put("code", code).put("username", username).put("expires", if (ok) expires else "")
            .put("serverTime", serverTime).put("protocol", 1).put("sig", Base64.getEncoder().encodeToString(sig))
        tamper(res)
        return HttpReply(200, res.toString())
    }

    val publicKey: String get() = Base64.getEncoder().encodeToString(keys.public.encoded)

    companion object {
        fun newKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}

class LicenseTest {
    private val day = LicenseManager.DAY_MS
    private val device = DeviceInfo("dev-A", "Samsung A54")
    private var now = 1_800_000_000_000L
    private val store = MemLicenseStore()
    private val server = FakeServer()
    private fun manager(dev: DeviceInfo = device, srv: FakeServer = server) =
        LicenseManager(store, LicenseClient(srv, srv.publicKey), dev, "1.2.0", { now }, ZoneId.of("Asia/Jerusalem"))

    @Test fun loginOpensTheAppAndWorksOfflineForSevenDays() {
        val m = manager()
        assertEquals(LicenseManager.Gate.Login(null), m.gate())
        assertEquals(LicenseManager.Result.Ok, m.login(" 050-1234567 ", "12345678"))
        val req = server.requests.single()
        assertEquals("activate", req.getString("action"))
        assertEquals("12345678", req.getString("password"))
        assertEquals("dev-A", req.getString("deviceId"))
        assertEquals("Samsung A54", req.getString("deviceName"))
        assertEquals("username is sent as typed (trimmed); the server normalizes", "050-1234567", req.getString("username"))
        assertEquals("0501234567", store.s!!.username)

        assertEquals(7, (m.gate() as LicenseManager.Gate.Open).daysLeft)
        now += 3 * day
        assertEquals(4, (m.gate() as LicenseManager.Gate.Open).daysLeft)
        assertTrue(m.needsRefresh())
        now += 4 * day - 1
        assertTrue(m.gate() is LicenseManager.Gate.Open)
        now += 1
        assertTrue("after 7 days offline an online check is required", m.gate() is LicenseManager.Gate.Verify)
        assertEquals(LicenseManager.Result.Ok, m.verify())
        assertFalse(server.requests.last().has("password"))
        assertEquals(7, (m.gate() as LicenseManager.Gate.Open).daysLeft)
        assertFalse(m.needsRefresh())
    }

    @Test fun refusedLoginShowsTheReason() {
        val m = manager()
        server.answer = { false to "other_device" }
        val r = m.login("0501234567", "12345678") as LicenseManager.Result.Refused
        assertTrue(r.message.contains("טלפון אחר"))
        assertNull(store.s)
        server.answer = { false to "bad_credentials" }
        assertTrue((m.login("0501234567", "1") as LicenseManager.Result.Refused).message.contains("שגויים"))
        assertTrue((m.login("", "1") as LicenseManager.Result.Refused).message.contains("יש להזין"))
        assertEquals("empty fields are not sent", 2, server.requests.size)
    }

    @Test fun revokedLicenseReturnsToLoginButKeepsNothingElse() {
        val m = manager()
        m.login("0501234567", "12345678")
        for (code in LicenseManager.REVOKING) {
            m.login("0501234567", "12345678")
            server.answer = { req -> if (req.getString("action") == "check") false to code else true to "" }
            val r = m.verify() as LicenseManager.Result.Refused
            assertTrue(r.revoked)
            assertNull(store.s)
            val g = m.gate() as LicenseManager.Gate.Login
            assertEquals(LicenseText.denial(code), g.notice)
            server.answer = { true to "" }
        }
        m.login("0501234567", "12345678")
        assertNull("notice cleared after a new login", store.notice)
    }

    @Test fun networkProblemsNeverRevoke() {
        val m = manager()
        m.login("0501234567", "12345678")
        val before = store.s
        now += day
        server.fail = java.net.UnknownHostException("script.google.com")
        assertEquals(LicenseText.network(NetProblem.NO_CONNECTION), (m.verify() as LicenseManager.Result.Offline).message)
        server.fail = java.net.SocketTimeoutException("slow")
        assertEquals(LicenseText.network(NetProblem.TIMEOUT), (m.verify() as LicenseManager.Result.Offline).message)
        server.fail = javax.net.ssl.SSLHandshakeException("filter certificate")
        assertTrue((m.verify() as LicenseManager.Result.Offline).message.contains("מסונן"))
        server.fail = null
        server.answer = { false to "rate_limited" }
        assertTrue(m.verify() is LicenseManager.Result.Offline)
        assertEquals(before!!.verifiedAt, store.s!!.verifiedAt)
        assertTrue(m.gate() is LicenseManager.Gate.Open)
    }

    @Test fun forgedOrReplayedAnswersAreRejected() {
        val m = manager()
        m.login("0501234567", "12345678")
        val verifiedAt = store.s!!.verifiedAt
        now += 8 * day
        assertTrue(m.gate() is LicenseManager.Gate.Verify)

        // A filter/proxy that turns a refusal into an approval
        server.answer = { false to "blocked" }
        server.tamper = { it.put("ok", true).put("code", "") }
        assertTrue(m.verify() is LicenseManager.Result.Offline)
        // An approval with a later expiry written in
        server.answer = { true to "" }
        server.tamper = { it.put("expires", "2099-01-01") }
        assertTrue(m.verify() is LicenseManager.Result.Offline)
        // An approval signed by somebody else's key
        val impostor = FakeServer()
        assertTrue(LicenseManager(store, LicenseClient(impostor, server.publicKey), device, "1.2.0", { now }).verify() is LicenseManager.Result.Offline)
        // Replaying an old genuine answer (signed for another nonce)
        server.tamper = {}
        val old = server.post(JSONObject().put("action", "check").put("username", "0501234567").put("deviceId", "dev-A").put("nonce", "old-nonce-1234567890").toString())
        val replay = LicenseClient({ old }, server.publicKey)
        assertTrue(LicenseManager(store, replay, device, "1.2.0", { now }).verify() is LicenseManager.Result.Offline)
        // A non-JSON block page
        assertTrue(LicenseManager(store, LicenseClient({ HttpReply(200, "<html>חסום</html>") }, server.publicKey), device, "1.2.0", { now }).verify() is LicenseManager.Result.Offline)

        assertEquals("nothing was accepted", verifiedAt, store.s!!.verifiedAt)
        assertTrue(m.gate() is LicenseManager.Gate.Verify)
        assertEquals(LicenseManager.Result.Ok, m.verify())
        assertTrue(m.gate() is LicenseManager.Gate.Open)
    }

    @Test fun turningTheClockBackRequiresAnOnlineCheck() {
        val m = manager()
        m.login("0501234567", "12345678")
        now += 5 * day
        assertTrue(m.gate() is LicenseManager.Gate.Open)
        now -= 5 * day // back to the login time: would buy 5 more offline days
        assertEquals(LicenseManager.Gate.Verify(LicenseText.CLOCK_CHANGED), m.gate())
        assertTrue(m.needsRefresh())
        assertEquals(LicenseManager.Result.Ok, m.verify())
        assertTrue(m.gate() is LicenseManager.Gate.Open)
        now -= 5 * 60 * 1000 // small adjustments (network time sync) are tolerated
        assertTrue(m.gate() is LicenseManager.Gate.Open)
    }

    @Test fun anotherDeviceCannotUseCopiedState() {
        manager().login("0501234567", "12345678")
        val other = manager(DeviceInfo("dev-B", "Pixel"))
        assertEquals(LicenseManager.Gate.Login(LicenseText.MOVED_DEVICE), other.gate())
        assertNull(store.s)
    }

    @Test fun expiryDateIsEnforcedLocallyToo() {
        val m = manager()
        server.expires = "2027-01-15"
        m.login("0501234567", "12345678")
        assertEquals("2027-01-15", store.s!!.expires)
        now = java.time.LocalDate.of(2027, 1, 15).atTime(23, 0).atZone(ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
        store.save(store.s!!.copy(verifiedAt = now - day, lastSeenAt = now - day))
        assertTrue("valid through the last day", m.gate() is LicenseManager.Gate.Open)
        now += 2 * 60 * 60 * 1000
        assertEquals(LicenseManager.Gate.Verify(LicenseText.EXPIRY_PASSED), m.gate())
    }

    @Test fun prefsStoreRoundTripsAndKeepsTheNoticeSeparately() {
        val sp = FakePrefs()
        val st = il.hamechutan.app.platform.PrefsLicenseStore(sp)
        assertNull(st.load())
        val s = LicenseState("0501234567", "dev-A", 10L, 20L, "2027-01-01")
        st.save(s)
        assertEquals(s, il.hamechutan.app.platform.PrefsLicenseStore(sp).load())
        st.notice = "נחסם"
        st.clear()
        assertNull(st.load())
        assertEquals("notice survives clear() so the login screen can explain why", "נחסם", st.notice)
        st.notice = null
        assertFalse(sp.contains("notice"))
        assertFalse("the password is never stored", sp.map.keys.any { it.contains("pass") })
    }

    @Test fun developmentBuildWithoutServerIsOpen() {
        val m = LicenseManager(store, null, device, "1.2.0", { now })
        assertFalse(m.enabled)
        assertTrue(m.gate() is LicenseManager.Gate.Open)
        assertEquals(LicenseManager.Result.Ok, m.login("x", "y"))
    }

    /** The real HTTP transport against a local server that behaves like Apps Script (302 to another URL). */
    @Test fun httpTransportFollowsTheAppsScriptRedirect() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = http.address.port
        var stored = ""
        http.createContext("/exec") { ex ->
            stored = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.responseHeaders.add("Location", "http://127.0.0.1:$port/echo?x=1")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        http.createContext("/echo") { ex ->
            assertEquals("GET", ex.requestMethod)
            val reply = server.post(stored).body.toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong()); ex.responseBody.use { it.write(reply) }
        }
        http.createContext("/filtered") { ex ->
            val b = "<html>האתר חסום</html>".toByteArray()
            ex.sendResponseHeaders(403, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.createContext("/broken") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }
        // The request is accepted but the answer (after Google's redirect) never comes: a filter holding the reply host
        http.createContext("/exec-held") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "http://localhost:$port/held")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        http.createContext("/held") { ex -> Thread.sleep(2500); ex.sendResponseHeaders(500, -1); ex.close() }
        http.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        http.start()
        try {
            fun m(path: String) = LicenseManager(store, LicenseClient(HttpLicenseTransport("http://127.0.0.1:$port$path", 5000), server.publicKey), device, "1.2.0", { now })
            assertEquals(LicenseManager.Result.Ok, m("/exec").login("0501234567", "12345678"))
            assertEquals(LicenseManager.Result.Ok, m("/exec").verify())
            assertTrue((m("/filtered").verify() as LicenseManager.Result.Offline).message.contains("מסונן"))
            assertEquals(LicenseText.network(NetProblem.SERVER_DOWN), (m("/broken").verify() as LicenseManager.Result.Offline).message)
            val held = LicenseManager(store, LicenseClient(HttpLicenseTransport("http://127.0.0.1:$port/exec-held", 1000), server.publicKey), device, "1.2.0", { now }).verify()
                as LicenseManager.Result.Offline
            assertEquals(LicenseText.network(NetProblem.REPLY_BLOCKED), held.message)
            assertTrue("detail names the host that did not answer: ${held.detail}", held.detail.contains("SocketTimeoutException @ localhost"))
            http.stop(0)
            val down = m("/exec").verify() as LicenseManager.Result.Offline
            assertEquals(LicenseText.network(NetProblem.NO_CONNECTION), down.message)
            assertTrue(down.detail.contains("@ 127.0.0.1"))
            assertNotNull("still logged in after all failures", store.s)
        } finally {
            http.stop(0)
        }
    }

    /**
     * End-to-end: the real server code (Code.gs, run by Node in tools/run-tests.sh) against this client.
     * Skipped when Node is not available.
     */
    @Test fun endToEndWithRealServerCode() {
        val base = System.getenv("LICENSE_E2E_BASE")
        assumeTrue("LICENSE_E2E_BASE not set (Node harness not running)", base != null)
        val pub = System.getenv("LICENSE_E2E_PUBKEY")!!
        fun admin(path: String, body: JSONObject): JSONObject {
            val c = URL("$base/admin/$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        }
        val created = admin("add", JSONObject().put("username", "052-777 8888"))
        val password = created.getString("password")
        val storeA = MemLicenseStore(); val storeB = MemLicenseStore()
        fun mgr(s: LicenseStore, d: DeviceInfo, url: String = "$base/macros/s/test/exec") =
            LicenseManager(s, LicenseClient(HttpLicenseTransport(url, 10_000), pub), d, "1.2.0", { now })
        val a = mgr(storeA, DeviceInfo("e2e-phone-A", "Galaxy A54"))
        val b = mgr(storeB, DeviceInfo("e2e-phone-B", "Pixel 8"))

        assertTrue((a.login("0527778888", "00000000") as LicenseManager.Result.Refused).message.contains("שגויים"))
        assertEquals(LicenseManager.Result.Ok, a.login("052-7778888", password))
        assertEquals("0527778888", storeA.s!!.username)
        assertEquals(LicenseManager.Result.Ok, a.verify())

        val refused = b.login("0527778888", password) as LicenseManager.Result.Refused
        assertEquals(LicenseText.denial("other_device"), refused.message)
        assertFalse(refused.revoked)

        admin("release", JSONObject().put("username", "0527778888"))
        val revoked = a.verify() as LicenseManager.Result.Refused
        assertTrue(revoked.revoked)
        assertEquals(LicenseManager.Gate.Login(LicenseText.denial("not_activated")), a.gate())
        assertEquals(LicenseManager.Result.Ok, b.login("0527778888", password))
        assertTrue(b.gate() is LicenseManager.Gate.Open)

        admin("status", JSONObject().put("username", "0527778888").put("status", "חסום"))
        assertEquals(LicenseManager.Result.Refused(LicenseText.denial("blocked"), true), b.verify())

        // A filter page and a key mismatch are never mistaken for an answer
        val c = mgr(MemLicenseStore(), DeviceInfo("e2e-phone-C", "x"), "$base/filtered")
        assertTrue(c.login("0527778888", password) is LicenseManager.Result.Offline)
        val wrongKey = LicenseManager(MemLicenseStore(), LicenseClient(HttpLicenseTransport("$base/macros/s/test/exec", 10_000), FakeServer().publicKey),
            DeviceInfo("e2e-phone-D", "x"), "1.2.0", { now })
        assertTrue(wrongKey.login("0527778888", password) is LicenseManager.Result.Offline)
        println("LICENSE E2E: passed against the real Code.gs at $base")
    }
}
