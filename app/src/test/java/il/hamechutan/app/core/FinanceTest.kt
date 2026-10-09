package il.hamechutan.app.core

import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.report.ReportBuilder
import il.hamechutan.app.core.report.ReportType
import il.hamechutan.app.core.util.Money
import org.junit.Assert.*
import org.junit.Test

class FinanceTest {
    private fun ils(n: Long) = n * 100
    private fun method(repo: il.hamechutan.app.core.data.Repo, code: String) = repo.paymentMethods().first { it.code == code }.id

    /** Acceptance scenario "תרחיש כספי" from the spec. */
    @Test fun photographerScenario() {
        val repo = TestDb.fresh()
        val cat = repo.categories().first { it.name == "צילום ווידאו" }.id
        val sid = repo.saveSupplier(SupplierInput("צלם", service = "צילום", categoryId = cat, phone = "050-1234567", initialAgreedAgorot = ils(8000)), null)
        val exps = repo.expensesForSupplier(sid)
        assertEquals("exactly one commitment created", 1, exps.size)
        val eid = exps[0].id
        repo.addPayment(PaymentInput(eid, ils(2000), "2026-10-01", method(repo, "CASH")))
        repo.addPayment(PaymentInput(eid, ils(1000), "2026-10-05", method(repo, "TRANSFER")))

        val e = repo.expense(eid)!!
        assertEquals(ils(3000), e.paidAgorot)
        assertEquals(ils(5000), e.remainingAgorot)
        assertEquals(ExpenseStatus.PARTIAL, e.status)
        assertEquals("2026-10-05", e.lastPaymentDate)

        val s = repo.supplierSummary(sid)!!
        assertEquals(SupplierStatus.PARTIAL, s.status)
        assertEquals(ils(8000), s.committedAgorot)
        assertEquals(ils(3000), s.paidAgorot)
        assertEquals(ils(5000), s.remainingAgorot)

        val tot = repo.financeTotals()
        assertEquals(ils(8000), tot.committedAgorot)
        assertEquals(ils(3000), tot.paidAgorot)
        assertEquals(ils(5000), tot.remainingAgorot)

        // commitment appears once in the overall report
        val rep = ReportBuilder(repo).build(ReportType.COMMITMENTS_VS_PAYMENTS, null, null)
        val rows = rep.sections.mapNotNull { it.table }.flatMap { it.rows }
        assertEquals(1, rows.count { it[0] == "צלם" })
        assertEquals(1, rows.size)

        // history has both payments with date and method
        val hist = repo.payments(eid)
        assertEquals(2, hist.size)
        assertEquals("2026-10-05", hist[0].payment.date); assertEquals("העברה בנקאית", hist[0].methodLabel)
        assertEquals("2026-10-01", hist[1].payment.date); assertEquals("מזומן", hist[1].methodLabel)
        val ph = ReportBuilder(repo).build(ReportType.PAYMENT_HISTORY, null, null).sections.first().table!!
        assertEquals(2, ph.rows.size)
        assertTrue(ph.rows.any { it.contains("מזומן") } && ph.rows.any { it.contains("העברה בנקאית") })
    }

    @Test fun commitmentAndPaymentAreNotAdded() {
        val repo = TestDb.fresh()
        val eid = repo.saveExpense(ExpenseInput("אולם", null, null, ils(10000)), null)
        repo.addPayment(PaymentInput(eid, ils(3000), "2026-10-01", null))
        val t = repo.financeTotals()
        assertEquals(ils(10000), t.committedAgorot)
        assertEquals(ils(3000), t.paidAgorot)
        assertEquals(ils(7000), t.remainingAgorot)
    }

    @Test fun overpaymentNeedsExplicitDecision() {
        val repo = TestDb.fresh()
        val eid = repo.saveExpense(ExpenseInput("זמר", null, null, ils(5000)), null)
        repo.addPayment(PaymentInput(eid, ils(4000), "2026-10-01", null))
        val c = repo.checkPayment(PaymentInput(eid, ils(1500), "2026-10-02", null))
        assertTrue(c is PaymentCheck.Overpay)
        c as PaymentCheck.Overpay
        assertEquals(ils(1000), c.remainingAgorot); assertEquals(ils(500), c.excessAgorot)
        try { repo.addPayment(PaymentInput(eid, ils(1500), "2026-10-02", null)); fail("must reject silently fixing") } catch (e: ValidationException) {}
        assertEquals("rejected payment not saved", ils(4000), repo.expense(eid)!!.paidAgorot)

        repo.addPayment(PaymentInput(eid, ils(1500), "2026-10-02", null), OverpayPolicy.ALLOW_CREDIT)
        val e = repo.expense(eid)!!
        assertEquals(-ils(500), e.remainingAgorot)
        assertTrue(e.isOverpaid)
        assertEquals(ExpenseStatus.PAID, e.status)
        val t = repo.financeTotals()
        assertEquals(0, t.remainingAgorot); assertEquals(ils(500), t.creditAgorot)

        val e2 = repo.saveExpense(ExpenseInput("תזמורת", null, null, ils(1000)), null)
        repo.addPayment(PaymentInput(e2, ils(1200), "2026-10-02", null), OverpayPolicy.RAISE_AGREED)
        assertEquals(ils(1200), repo.expense(e2)!!.expense.agreedAgorot)
        assertEquals(0, repo.expense(e2)!!.remainingAgorot)
        assertTrue(repo.auditForExpense(e2).any { it.action == "PRICE" })
    }

    @Test fun editingPaymentExcludesItselfFromCheck() {
        val repo = TestDb.fresh()
        val eid = repo.saveExpense(ExpenseInput("פרחים", null, null, ils(3000)), null)
        val pid = repo.addPayment(PaymentInput(eid, ils(3000), "2026-10-01", null)).paymentId
        assertEquals(PaymentCheck.Ok, repo.checkPayment(PaymentInput(eid, ils(2500), "2026-10-01", null), pid))
        repo.updatePayment(pid, PaymentInput(eid, ils(2500), "2026-10-03", null))
        assertEquals(ils(500), repo.expense(eid)!!.remainingAgorot)
        assertTrue(repo.auditForExpense(eid).any { it.action == "EDIT" })
    }

    @Test fun cancellationKeepsHistoryAndHandlesPaidMoney() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("תזמורת", initialAgreedAgorot = ils(12000)), null)
        val eid = repo.expensesForSupplier(sid).single().id
        repo.addPayment(PaymentInput(eid, ils(3000), "2026-09-01", method(repo, "CHECK")))
        try { repo.cancelExpense(eid, ils(4000), "2026-10-01", null, null); fail() } catch (e: ValidationException) {}
        repo.cancelSupplier(sid, mapOf(eid to il.hamechutan.app.core.data.Repo.RefundSpec(ils(1000), "2026-10-02", method(repo, "TRANSFER"))), null)
        val e = repo.expense(eid)!!
        assertEquals(ExpenseStatus.CANCELLED, e.status)
        assertEquals(ils(2000), e.paidAgorot)                 // 3000 paid - 1000 refunded
        assertEquals(ils(2000), e.effectiveCommitmentAgorot)  // money not returned is a real cost
        assertEquals(0, e.remainingAgorot)
        assertEquals("history kept", 2, repo.payments(eid).size)
        assertEquals(SupplierStatus.CANCELLED, repo.supplierSummary(sid)!!.status)
        val t = repo.financeTotals()
        assertEquals(ils(2000), t.committedAgorot); assertEquals(ils(2000), t.paidAgorot); assertEquals(0, t.remainingAgorot)
        // no new payments on a cancelled commitment
        assertTrue(repo.checkPayment(PaymentInput(eid, ils(100), "2026-10-03", null)) is PaymentCheck.Error)
        // cannot restore the expense while the supplier is cancelled; restore supplier, then expense
        try { repo.restoreExpense(eid); fail() } catch (e2: ValidationException) {}
        repo.restoreSupplier(sid); repo.restoreExpense(eid)
        assertEquals(ils(10000), repo.expense(eid)!!.remainingAgorot)
    }

    @Test fun deletionPolicies() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("מעצב", initialAgreedAgorot = ils(5000)), null)
        val eid = repo.expensesForSupplier(sid).single().id
        val tid = repo.saveTask(TaskInput("לתאם עם המעצב", supplierId = sid), null)
        repo.addPayment(PaymentInput(eid, ils(1000), "2026-10-01", null))
        try { repo.deleteExpense(eid); fail("expense with payments must not be deleted") } catch (e: ValidationException) {}
        try { repo.deleteSupplier(sid); fail("supplier with commitments must not be deleted") } catch (e: ValidationException) {}
        assertNotNull(repo.expense(eid)); assertNotNull(repo.supplier(sid))

        val pid = repo.payments(eid).single().payment.id
        repo.deletePayment(pid, "הוזן בטעות")
        assertEquals(0, repo.expense(eid)!!.paidAgorot)
        assertTrue(repo.auditForExpense(eid).any { it.action == "DELETE" && it.details!!.contains("הוזן בטעות") })
        repo.deleteExpense(eid)
        repo.deleteSupplier(sid)
        assertNull(repo.supplier(sid))
        val t = repo.task(tid)!!
        assertNull("task kept but detached", t.task.supplierId)
    }

    @Test fun supplierPriceEditsSameCommitment() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", initialAgreedAgorot = ils(8000)), null)
        repo.saveSupplier(SupplierInput("צלם וידאו", initialAgreedAgorot = ils(9000)), sid)
        val list = repo.expensesForSupplier(sid)
        assertEquals("no duplicate commitment", 1, list.size)
        assertEquals(ils(9000), list[0].expense.agreedAgorot)
        assertEquals("auto-named commitment follows supplier rename", "צלם וידאו", list[0].expense.name)
        assertEquals(1, repo.expenses().size)
        // supplier without price -> no commitment
        val s2 = repo.saveSupplier(SupplierInput("זמר"), null)
        assertEquals(SupplierStatus.NO_COMMITMENT, repo.supplierSummary(s2)!!.status)
        assertTrue(repo.validateExpense(ExpenseInput("זמר נוסף", null, sid, ils(100)), null).warnings.isNotEmpty())
    }

    @Test fun supplierStatuses() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("קייטרינג", initialAgreedAgorot = ils(1000)), null)
        val eid = repo.expensesForSupplier(sid).single().id
        assertEquals(SupplierStatus.COMMITTED, repo.supplierSummary(sid)!!.status)
        repo.addPayment(PaymentInput(eid, ils(400), "2026-10-01", null))
        assertEquals(SupplierStatus.PARTIAL, repo.supplierSummary(sid)!!.status)
        repo.addPayment(PaymentInput(eid, ils(600), "2026-10-02", null))
        assertEquals(SupplierStatus.PAID, repo.supplierSummary(sid)!!.status)
        assertEquals(ExpenseStatus.PAID, repo.expense(eid)!!.status)
    }

    @Test fun validationAndIntegrity() {
        val repo = TestDb.fresh()
        try { repo.saveExpense(ExpenseInput("  ", null, null, ils(10)), null); fail() } catch (e: ValidationException) {}
        try { repo.saveExpense(ExpenseInput("x", null, null, 0), null); fail() } catch (e: ValidationException) {}
        try { repo.saveExpense(ExpenseInput("x", null, 9999, ils(10)), null); fail("missing supplier") } catch (e: ValidationException) {}
        try { repo.saveExpense(ExpenseInput("x", 9999, null, ils(10)), null); fail("missing category") } catch (e: ValidationException) {}
        try { repo.saveExpense(ExpenseInput("x", null, null, ils(10), nextPaymentDate = "2026-13-40"), null); fail() } catch (e: ValidationException) {}
        val eid = repo.saveExpense(ExpenseInput("x", null, null, ils(10)), null)
        assertTrue(repo.checkPayment(PaymentInput(eid, 0, "2026-10-01", null)) is PaymentCheck.Error)
        assertTrue(repo.checkPayment(PaymentInput(eid, 100, "bad", null)) is PaymentCheck.Error)
        assertTrue(repo.checkPayment(PaymentInput(eid, 100, "2026-10-01", 9999)) is PaymentCheck.Error)
        // database-level FK and CHECK constraints
        try { repo.db.insert("INSERT INTO payment (expense_id, amount_agorot, paid_date, created_at, updated_at) VALUES (9999, 100, '2026-01-01', 0, 0)"); fail("FK") } catch (e: Exception) {}
        try { repo.db.insert("INSERT INTO payment (expense_id, amount_agorot, paid_date, created_at, updated_at) VALUES (?, -5, '2026-01-01', 0, 0)", eid); fail("CHECK") } catch (e: Exception) {}
    }

    @Test fun atomicQuickPaidExpense() {
        val repo = TestDb.fresh()
        try {
            repo.addPaidExpense(ExpenseInput("מתנה", null, null, ils(300)), "2026-10-01", 9999, null)
            fail()
        } catch (e: ValidationException) {}
        assertEquals("rolled back - no half-saved expense", 0, repo.expenses().size)
        repo.addPaidExpense(ExpenseInput("מתנה", null, null, ils(300)), "2026-10-01", method(repo, "CREDIT"), null)
        val e = repo.expenses().single()
        assertEquals(ExpenseStatus.PAID, e.status)
        assertEquals(1, e.paymentCount)
    }

    @Test fun budgetAndCategories() {
        val repo = TestDb.fresh()
        repo.setBudget(ils(20000))
        val cats = repo.categories()
        val hall = cats.first { it.name == "אולם וקייטרינג" }.id
        val misc = cats.first { it.name == "שונות" }.id
        repo.saveExpense(ExpenseInput("אולם", hall, null, ils(15000)), null)
        val e2 = repo.saveExpense(ExpenseInput("שונות", misc, null, ils(6000)), null)
        repo.addPayment(PaymentInput(e2, ils(1000), "2026-10-01", null))
        val t = repo.financeTotals()
        assertTrue(t.overBudget)
        assertEquals(-ils(1000), t.budgetLeftAgorot)
        val ct = repo.categoryTotals()
        assertEquals(ils(15000), ct.first { it.categoryId == hall }.committedAgorot)
        assertEquals(ils(5000), ct.first { it.categoryId == misc }.remainingAgorot)
        // rename + add + delete with move
        repo.updateCategory(misc, "הוצאות שונות", null)
        val newCat = repo.addCategory("חלוקת צדקה")
        try { repo.addCategory("חלוקת צדקה"); fail("duplicate") } catch (e: ValidationException) {}
        try { repo.deleteCategory(misc, null); fail("in use") } catch (e: ValidationException) {}
        repo.deleteCategory(misc, newCat)
        assertEquals(newCat, repo.expense(e2)!!.expense.categoryId)
        assertNull(repo.category(misc))
    }

    @Test fun refundRecordsAndCustomMethods() {
        val repo = TestDb.fresh()
        val eid = repo.saveExpense(ExpenseInput("בגדים", null, null, ils(2000)), null)
        val custom = repo.addPaymentMethod("שוברים")
        repo.addPayment(PaymentInput(eid, ils(2000), "2026-10-01", custom))
        repo.addPayment(PaymentInput(eid, ils(500), "2026-10-03", null, kind = PaymentKind.REFUND))
        assertEquals(ils(1500), repo.expense(eid)!!.paidAgorot)
        assertEquals(ils(500), repo.expense(eid)!!.remainingAgorot)
        assertFalse("used custom method is archived, not deleted", repo.deletePaymentMethod(custom))
        assertEquals("שוברים", repo.payments(eid).last().methodLabel)
        try { repo.deletePaymentMethod(method(repo, "CASH")); fail("built-in") } catch (e: ValidationException) {}
        val other = method(repo, "OTHER")
        repo.addPayment(PaymentInput(eid, ils(100), "2026-10-04", other, methodNote = "פייבוקס"))
        assertEquals("אחר: פייבוקס", repo.payments(eid).first().methodLabel)
    }

    @Test fun moneyParsingAndFormatting() {
        assertEquals(Money.Parse.Ok(800000), Money.parse("8000"))
        assertEquals(Money.Parse.Ok(800000), Money.parse("8,000"))
        assertEquals(Money.Parse.Ok(800000), Money.parse("8,000 ₪"))
        assertEquals(Money.Parse.Ok(125050), Money.parse("1250.5"))
        assertEquals(Money.Parse.Ok(125050), Money.parse("1250,50"))
        assertEquals(Money.Parse.Ok(100000), Money.parse("1,000"))
        assertEquals(Money.Parse.Empty, Money.parse("  "))
        assertTrue(Money.parse("-5") is Money.Parse.Error)
        assertTrue(Money.parse("abc") is Money.Parse.Error)
        assertTrue(Money.parse("1.234") is Money.Parse.Error)
        assertEquals("8,000", Money.number(800000))
        assertEquals("1,250.50", Money.number(125050))
        assertEquals("-500", Money.number(-50000))
        assertEquals("1,234,567", Money.number(123456700))
        assertEquals("8,000 ₪", Money.format(800000, isolate = false))
        assertEquals("1250.5", Money.inputValue(125050))
        assertEquals("8000", Money.inputValue(800000))
    }
}
