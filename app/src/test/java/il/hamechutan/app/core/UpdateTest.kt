package il.hamechutan.app.core

import com.sun.net.httpserver.HttpServer
import il.hamechutan.app.core.update.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files

class UpdateTest {
    private fun release(tag: String, apkUrl: String, size: Long, draft: Boolean = false, pre: Boolean = false, apkName: String = "HaMechutan-${tag.removePrefix("v")}.apk") =
        JSONObject().put("tag_name", tag).put("name", "המחותן ${tag.removePrefix("v")}").put("draft", draft).put("prerelease", pre)
            .put("html_url", "https://github.com/syleblsyl/hamechutan-releases/releases/tag/$tag")
            .put("body", "## ${tag.removePrefix("v")}\n- מסך בית חדש\n- לוח שנה עברי\n")
            .put("assets", JSONArray().put(JSONObject().put("name", "notes.txt").put("size", 3).put("browser_download_url", "https://github.com/syleblsyl/x/notes.txt"))
                .put(JSONObject().put("name", apkName).put("size", size).put("browser_download_url", apkUrl)))
            .toString()

    @Test fun parsesGitHubLatestRelease() {
        val u = UpdateCheck.parseLatest(release("v1.3.0", "https://github.com/syleblsyl/hamechutan-releases/releases/download/v1.3.0/HaMechutan-1.3.0.apk", 512000))!!
        assertEquals("1.3.0", u.versionName)
        assertEquals("HaMechutan-1.3.0.apk", u.apkName)
        assertEquals(512000L, u.apkSize)
        assertTrue(u.apkUrl.endsWith("/HaMechutan-1.3.0.apk"))
        assertEquals("https://github.com/syleblsyl/hamechutan-releases/releases/tag/v1.3.0", u.pageUrl)
        assertEquals("• מסך בית חדש\n• לוח שנה עברי", UpdateCheck.plainNotes(u.notes))
    }

    @Test fun rejectsDraftsPrereleasesAndForeignDownloads() {
        val ok = "https://github.com/syleblsyl/hamechutan-releases/releases/download/v1.3.0/a.apk"
        assertNull(UpdateCheck.parseLatest(release("v1.3.0", ok, 1, draft = true)))
        assertNull(UpdateCheck.parseLatest(release("v1.3.0", ok, 1, pre = true)))
        assertNull("APK hosted elsewhere is ignored", UpdateCheck.parseLatest(release("v1.3.0", "https://evil.example/a.apk", 1)))
        assertNull("other GitHub accounts are ignored", UpdateCheck.parseLatest(release("v1.3.0", "https://github.com/someone/x/a.apk", 1)))
        assertNull("no APK asset", UpdateCheck.parseLatest(release("v1.3.0", ok, 1, apkName = "readme.md")))
        assertNull("bad tag", UpdateCheck.parseLatest(release("latest", ok, 1)))
        assertNull(UpdateCheck.parseLatest("<html>blocked</html>"))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(UpdateCheck.compareVersions("1.10.0", "1.9.3") > 0)
        assertEquals(0, UpdateCheck.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateCheck.compareVersions("2.0", "1.99.99") > 0)
        assertTrue(UpdateCheck.compareVersions("1.2.0", "1.2.1") < 0)
        val u = AvailableUpdate("1.3.0", "", "u", "n", 1, "p")
        assertTrue(UpdateCheck.isNewer(u, "1.2.0"))
        assertFalse(UpdateCheck.isNewer(u, "1.3.0"))
        assertFalse(UpdateCheck.isNewer(u, "1.4.0"))
        assertFalse("unknown current version: never claim an update", UpdateCheck.isNewer(u, "?"))
        assertFalse(UpdateCheck.isNewer(null, "1.0.0"))
    }

    /** Local server playing api.github.com + the asset download host (with GitHub's redirect). */
    private fun withServer(block: (base: String, set: (String, Int, Any) -> Unit) -> Unit) {
        val routes = HashMap<String, Pair<Int, ByteArray>>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val path = ex.requestURI.path
            if (path.startsWith("/dl/")) {
                ex.responseHeaders.add("Location", "http://127.0.0.1:${http.address.port}/blob${path.removePrefix("/dl")}")
                ex.sendResponseHeaders(302, -1); ex.close(); return@createContext
            }
            assertNotNull("User-Agent is required by GitHub", ex.requestHeaders.getFirst("User-Agent"))
            val (code, body) = routes[path] ?: (404 to "{\"message\":\"Not Found\"}".toByteArray())
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        http.start()
        try {
            block("http://127.0.0.1:${http.address.port}") { path, code, body ->
                routes[path] = code to (if (body is ByteArray) body else body.toString().toByteArray())
            }
        } finally { http.stop(0) }
    }

    @Test fun checksReposInOrderAndDownloadsThroughTheRedirect() = withServer { base, set ->
        val apk = ByteArray(200_000) { (it % 251).toByte() }
        set("/blob/HaMechutan-1.3.0.apk", 200, apk)
        set("/repos/me/old/releases/latest", 200, release("v1.3.0", "$base/dl/HaMechutan-1.3.0.apk", apk.size.toLong()))
        val client = UpdateClient(listOf("me/new-missing", "me/old"), "HaMechutan-test", base, 5000, listOf(base))

        val r = client.check("1.2.0") as CheckResult.Available
        assertEquals("1.3.0", r.update.versionName)
        assertEquals(CheckResult.UpToDate("1.3.0"), client.check("1.3.0"))

        val dir = Files.createTempDirectory("upd").toFile()
        val dest = File(dir, "HaMechutan-1.3.0.apk")
        val seen = ArrayList<Int>()
        client.download(r.update, dest) { seen += it }
        assertArrayEquals(apk, dest.readBytes())
        assertEquals(100, seen.last())
        assertTrue("progress only grows", seen.zipWithNext().all { (a, b) -> b >= a })

        // A truncated or wrong-size file is never left behind
        val wrong = r.update.copy(apkSize = apk.size + 10L)
        val dest2 = File(dir, "x.apk")
        try { client.download(wrong, dest2) {}; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("size")) }
        assertFalse(dest2.exists()); assertFalse(File(dir, "x.apk.part").exists())

        // Untrusted host
        try { client.download(r.update.copy(apkUrl = "https://evil.example/a.apk"), dest2) {}; fail() } catch (e: IOException) { }
    }

    @Test fun reportsFailuresWithoutClaimingAnUpdate() = withServer { base, set ->
        set("/repos/me/a/releases/latest", 500, "oops")
        val client = UpdateClient(listOf("me/a"), "t", base, 3000, listOf(base))
        assertTrue(client.check("1.0.0") is CheckResult.Failed)
        assertEquals("both repos missing: nothing to update", CheckResult.UpToDate(null), UpdateClient(listOf("x/y", "x/z"), "t", base, 3000, listOf(base)).check("1.0.0"))
        val down = UpdateClient(listOf("me/a"), "t", "http://127.0.0.1:1", 1000, listOf(base))
        assertTrue(down.check("1.0.0") is CheckResult.Failed)
    }
}
