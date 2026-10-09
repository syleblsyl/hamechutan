package il.hamechutan.app.core

import il.hamechutan.app.core.backup.BackupException
import il.hamechutan.app.core.backup.BackupManager
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.db.Schema
import il.hamechutan.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupTest {

    private class Env(val repo: Repo, val docs: File, val work: File) {
        val bm = BackupManager(repo, docs, work, "1.0-test")
    }

    private fun env(): Env {
        val root = Files.createTempDirectory("hm").toFile()
        val docs = File(root, "docs").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        return Env(TestDb.fresh(), docs, work)
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** Fills an environment like a real user would: supplier, expense, payments, task+reminder, event, transport, documents (real bytes). */
    private fun populate(e: Env): Map<String, Long> {
        val r = e.repo
        r.updateWedding("חתונת לנדאו", "2026-11-20", "אולמי הנסיכה", "הערה")
        r.setBudget(15000000)
        val cat = r.addCategory("קטגוריה מותאמת")
        val method = r.addPaymentMethod("שוברים")
        val sid = r.saveSupplier(SupplierInput("צלם", "צילום", cat, "050-1111111", "יוסי", "19:30 באולם", "הערות ספק", 800000), null)
        val eid = r.expensesForSupplier(sid).single().id
        r.saveExpense(r.expense(eid)!!.expense.let { x -> ExpenseInput(x.name, x.categoryId, sid, x.agreedAgorot, "2026-10-30", 200000, "2026-11-20", "19:00", "הגעה לאולם", null, "הערות הוצאה") }, eid)
        val p1 = r.addPayment(PaymentInput(eid, 200000, "2026-10-01", r.paymentMethods().first { it.code == "CASH" }.id, note = "מקדמה")).paymentId
        r.addPayment(PaymentInput(eid, 100000, "2026-10-05", method))
        val tid = r.saveTask(TaskInput("לשלם לצלם", "יתרה", "2026-10-30", "10:00", Priority.HIGH, TaskStatus.IN_PROGRESS, sid, eid, notes = "n"), null)
        r.setReminder(TargetType.TASK, tid, ReminderSpec(ReminderMode.OFFSET, 60))
        r.setReminder(TargetType.EXPENSE_PAYMENT, eid, ReminderSpec(ReminderMode.OFFSET, 1440))
        val evId = r.saveEvent(EventInput("פגישה עם הצלם", "2026-10-15", "20:00", "בית", sid, eid, "x"), null)
        val trId = r.saveTransport(TransportInput("הסעת משפחה", "2026-11-20", "17:00", "ירושלים", "אולם", "משה", "052-2222222", null, "אוטובוס"), null)
        r.saveExpense(ExpenseInput("מתנות", null, null, 50000), null)
        // documents with real content, including a binary "photo"
        val img = ByteArray(300_000).also { java.util.Random(42).nextBytes(it) }
        File(e.docs, "receipt_1.jpg").writeBytes(img)
        File(e.docs, "contract_1.pdf").writeBytes("%PDF-1.4 fake contract".toByteArray())
        val d1 = r.addDocument(DocumentInput("קבלה מקדמה", DocType.RECEIPT, "receipt_1.jpg", "image/jpeg", img.size.toLong(), paymentId = p1))
        r.addDocument(DocumentInput("חוזה צלם", DocType.CONTRACT, "contract_1.pdf", "application/pdf", 22, supplierId = sid, expenseId = eid))
        r.setSetting(Repo.KEY_THEME, "dark")
        return mapOf("sid" to sid, "eid" to eid, "tid" to tid, "ev" to evId, "tr" to trId, "d1" to d1)
    }

    private fun dump(r: Repo): Map<String, List<Map<String, Any?>>> =
        Schema.TABLES.associateWith { t -> r.db.rows("SELECT * FROM $t ORDER BY rowid").map { it.values } }

    /** Acceptance scenario "תרחיש גיבוי" incl. move to another device (fresh install). */
    @Test fun fullBackupAndRestoreOnNewDevice() {
        val src = env()
        val ids = populate(src)
        val zip = File(src.work, "backup.zip")
        val info = FileOutputStream(zip).use { src.bm.create(it) }
        assertTrue(zip.length() > 300_000)
        assertEquals(2, info.fileCount)
        assertTrue(info.missingFiles.isEmpty())
        // archive really contains the files
        ZipFile(zip).use { z ->
            assertNotNull(z.getEntry("files/receipt_1.jpg")); assertNotNull(z.getEntry("files/contract_1.pdf"))
            assertNotNull(z.getEntry("data.json")); assertNotNull(z.getEntry("manifest.json"))
        }
        val inspected = src.bm.inspect(zip)
        assertEquals(1, inspected.counts["supplier"]); assertEquals(2, inspected.counts["payment"]); assertEquals(2, inspected.counts["document"])

        // "other device": brand new database + empty docs folder
        val dst = env()
        dst.repo.saveSupplier(SupplierInput("ספק שיימחק"), null)
        var changes = 0
        dst.repo.onChange = { changes++ }
        dst.bm.restore(zip)
        assertTrue("app notified to reschedule reminders", changes > 0)

        assertEquals(dump(src.repo), dump(dst.repo))
        assertEquals(sha(File(src.docs, "receipt_1.jpg")), sha(File(dst.docs, "receipt_1.jpg")))
        assertEquals(sha(File(src.docs, "contract_1.pdf")), sha(File(dst.docs, "contract_1.pdf")))
        val e = dst.repo.expense(ids["eid"]!!)!!
        assertEquals(300000, e.paidAgorot); assertEquals(500000, e.remainingAgorot)
        assertEquals(800000, dst.repo.financeTotals().committedSuppliersAgorot)
        assertEquals(15000000L, dst.repo.wedding().budgetAgorot)
        assertEquals("חתונת לנדאו", dst.repo.wedding().title)
        assertEquals("dark", dst.repo.getSetting(Repo.KEY_THEME))
        assertEquals(2, dst.repo.plannedAlarms().size)
        assertNull("old data replaced", dst.repo.suppliers().firstOrNull { it.supplier.name == "ספק שיימחק" })
        assertEquals("receipt_1.jpg", dst.repo.documentsForSupplier(ids["sid"]!!).first { it.doc.type == DocType.RECEIPT }.doc.fileName)
    }

    private fun rewrite(src: File, dst: File, transform: (String, ByteArray) -> ByteArray?) {
        ZipFile(src).use { z ->
            ZipOutputStream(FileOutputStream(dst)).use { out ->
                z.entries().toList().forEach { en ->
                    val bytes = z.getInputStream(en).readBytes()
                    val nb = transform(en.name, bytes) ?: return@forEach
                    out.putNextEntry(ZipEntry(en.name)); out.write(nb); out.closeEntry()
                }
            }
        }
    }

    private fun assertRejectedAndUntouched(dst: Env, bad: File, expect: String) {
        val before = dump(dst.repo)
        val docsBefore = dst.docs.listFiles()!!.map { it.name to sha(it) }.toSet()
        try { dst.bm.inspect(bad); fail("inspect should fail: $expect") } catch (e: BackupException) { assertTrue(e.message!!, e.message!!.isNotBlank()) }
        try { dst.bm.restore(bad); fail("restore should fail: $expect") } catch (e: BackupException) {}
        assertEquals("data untouched after failed restore ($expect)", before, dump(dst.repo))
        assertEquals(docsBefore, dst.docs.listFiles()!!.map { it.name to sha(it) }.toSet())
    }

    @Test fun corruptedBackupsAreRejectedWithoutChangingData() {
        val src = env(); populate(src)
        val zip = File(src.work, "b.zip")
        FileOutputStream(zip).use { src.bm.create(it) }
        val dst = env(); populate(dst)
        dst.repo.saveSupplier(SupplierInput("ספק מקומי"), null)

        // truncated file (interrupted transfer)
        val truncated = File(src.work, "t.zip"); truncated.writeBytes(zip.readBytes().copyOf((zip.length() / 2).toInt()))
        assertRejectedAndUntouched(dst, truncated, "truncated")
        // not a zip
        val txt = File(src.work, "x.zip"); txt.writeText("hello")
        assertRejectedAndUntouched(dst, txt, "not zip")
        // tampered data
        val tData = File(src.work, "td.zip")
        rewrite(zip, tData) { n, b -> if (n == "data.json") String(b).replace("צלם", "צלמ").toByteArray() else b }
        assertRejectedAndUntouched(dst, tData, "tampered data")
        // tampered attachment
        val tFile = File(src.work, "tf.zip")
        rewrite(zip, tFile) { n, b -> if (n == "files/receipt_1.jpg") b.copyOf(b.size - 10) else b }
        assertRejectedAndUntouched(dst, tFile, "tampered file")
        // missing attachment
        val mFile = File(src.work, "mf.zip")
        rewrite(zip, mFile) { n, b -> if (n == "files/contract_1.pdf") null else b }
        assertRejectedAndUntouched(dst, mFile, "missing file")
        // newer version
        val newer = File(src.work, "nv.zip")
        rewrite(zip, newer) { n, b -> if (n == "manifest.json") String(b).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99").toByteArray() else b }
        assertRejectedAndUntouched(dst, newer, "newer version")
        // foreign zip
        val foreign = File(src.work, "fz.zip")
        rewrite(zip, foreign) { n, b -> if (n == "manifest.json") String(b).replace("hamechutan-backup", "other").toByteArray() else b }
        assertRejectedAndUntouched(dst, foreign, "foreign")
    }

    @Test fun unknownAndMissingColumnsAreTolerated() {
        val src = env(); populate(src)
        val zip = File(src.work, "b.zip")
        FileOutputStream(zip).use { src.bm.create(it) }
        // simulate a backup from a future minor version with an extra column, and an older one lacking a nullable column
        val modified = File(src.work, "m.zip")
        var newSha = ""
        var newSize = 0
        val data = ZipFile(zip).use { z -> String(z.getInputStream(z.getEntry("data.json")).readBytes()) }
        val j = org.json.JSONObject(data)
        val sup = j.getJSONObject("tables").getJSONObject("supplier")
        val oldCols = sup.getJSONArray("columns")
        val oldRows = sup.getJSONArray("rows")
        // drop "arrival_info" and add an unknown "future_field"
        val idx = (0 until oldCols.length()).first { oldCols.getString(it) == "arrival_info" }
        val cols = org.json.JSONArray()
        for (c in 0 until oldCols.length()) if (c != idx) cols.put(oldCols.getString(c))
        cols.put("future_field")
        val rows = org.json.JSONArray()
        for (i in 0 until oldRows.length()) {
            val r = oldRows.getJSONArray(i); val nr = org.json.JSONArray()
            for (c in 0 until r.length()) if (c != idx) nr.put(r.get(c))
            nr.put("x"); rows.put(nr)
        }
        sup.put("columns", cols); sup.put("rows", rows)
        val nd = j.toString().toByteArray()
        newSha = MessageDigest.getInstance("SHA-256").digest(nd).joinToString("") { "%02x".format(it) }; newSize = nd.size
        rewrite(zip, modified) { n, b ->
            when (n) {
                "data.json" -> nd
                "manifest.json" -> { val m = org.json.JSONObject(String(b)); m.getJSONObject("data").put("sha256", newSha).put("size", newSize); m.toString().toByteArray() }
                else -> b
            }
        }
        val dst = env()
        dst.bm.restore(modified)
        val s = dst.repo.suppliers().single()
        assertEquals("צלם", s.supplier.name)
        assertNull(s.supplier.arrivalInfo)
        assertEquals(300000, s.paidAgorot)
    }

    @Test fun missingFileOnDiskIsReportedNotFatal() {
        val src = env(); populate(src)
        File(src.docs, "contract_1.pdf").delete()
        val zip = File(src.work, "b.zip")
        val info = FileOutputStream(zip).use { src.bm.create(it) }
        assertEquals(listOf("contract_1.pdf"), info.missingFiles)
        val dst = env()
        val rinfo = dst.bm.restore(zip)
        assertEquals(listOf("contract_1.pdf"), rinfo.missingFiles)
        assertEquals(2, dst.repo.documents().size)
        assertTrue(File(dst.docs, "receipt_1.jpg").exists())
    }
}
