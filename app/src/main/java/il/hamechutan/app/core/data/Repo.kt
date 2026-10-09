package il.hamechutan.app.core.data

import il.hamechutan.app.core.db.Row
import il.hamechutan.app.core.db.SqlDb
import il.hamechutan.app.core.db.first
import il.hamechutan.app.core.db.list
import il.hamechutan.app.core.db.longValue
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.Money
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Repository: the single place where business data is read and written.
 * Every multi-row change runs inside a transaction. Money totals are always derived
 * from payment records, never stored twice.
 */
class Repo(val db: SqlDb, val clock: Clock = Clock.systemDefaultZone()) {

    /** Invoked after every committed mutation (used by the app to re-sync reminders). */
    var onChange: (() -> Unit)? = null

    fun now(): Long = clock.millis()
    fun today(): LocalDate = LocalDate.now(clock)
    fun nowDateTime(): LocalDateTime = LocalDateTime.now(clock)

    private var cachedWeddingId: Long? = null

    val weddingId: Long
        get() = cachedWeddingId ?: (db.first("SELECT id FROM wedding WHERE is_active=1 ORDER BY id LIMIT 1") { it.long("id") }
            ?: error("No active wedding")).also { cachedWeddingId = it }

    fun invalidateCaches() { cachedWeddingId = null }

    internal fun <T> mutate(block: () -> T): T {
        val outer = !db.inTransaction
        val r = db.transaction(block)
        if (outer) onChange?.invoke()
        return r
    }

    // ------------------------------------------------------------------ helpers

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun requireText(v: String?, field: String): String =
        v.clean() ?: throw ValidationException("יש למלא $field")

    private fun checkDate(v: String?, field: String): String? {
        val c = v.clean() ?: return null
        if (!Dates.isValidDate(c)) throw ValidationException("$field אינו תאריך תקין")
        return c
    }

    private fun checkTime(v: String?, field: String): String? {
        val c = v.clean() ?: return null
        if (!Dates.isValidTime(c)) throw ValidationException("$field אינה שעה תקינה")
        return c
    }

    private fun exists(table: String, id: Long?): Boolean =
        id == null || db.longValue("SELECT COUNT(*) FROM $table WHERE id=?", id) > 0

    private fun requireExists(table: String, id: Long?, label: String) {
        if (!exists(table, id)) throw ValidationException("$label שנבחר/ה אינו/ה קיים/ת")
    }

    private fun audit(entityType: String, entityId: Long, expenseId: Long?, action: String, details: String) {
        db.insert(
            "INSERT INTO audit_log (wedding_id, entity_type, entity_id, expense_id, action, details, created_at) VALUES (?,?,?,?,?,?,?)",
            weddingId, entityType, entityId, expenseId, action, details, now()
        )
    }

    // ------------------------------------------------------------------ settings

    fun getSetting(key: String): String? = db.first("SELECT value FROM app_setting WHERE key=?", key) { it.strOrNull("value") }

    fun setSetting(key: String, value: String?) = mutate {
        if (db.update("UPDATE app_setting SET value=? WHERE key=?", value, key) == 0) {
            db.insert("INSERT INTO app_setting (key, value) VALUES (?, ?)", key, value)
        }
    }

    /** Time used for reminders of items that have a date but no time. */
    fun defaultReminderTime(): LocalTime = Dates.parseTime(getSetting(KEY_DEFAULT_TIME)) ?: LocalTime.of(9, 0)

    // ------------------------------------------------------------------ wedding

    private fun mapWedding(r: Row) = Wedding(
        r.long("id"), r.str("title"), r.strOrNull("wedding_date"), r.strOrNull("venue"),
        r.longOrNull("budget_agorot"), r.strOrNull("notes")
    )

    fun wedding(): Wedding = db.first("SELECT * FROM wedding WHERE id=?", weddingId, mapper = ::mapWedding)!!

    fun updateWedding(title: String, date: String?, venue: String?, notes: String?) = mutate {
        val t = requireText(title, "שם לחתונה")
        val d = checkDate(date, "תאריך החתונה")
        db.update("UPDATE wedding SET title=?, wedding_date=?, venue=?, notes=? WHERE id=?", t, d, venue.clean(), notes.clean(), weddingId)
    }

    fun setBudget(agorot: Long?) = mutate {
        if (agorot != null && agorot < 0) throw ValidationException("תקציב לא יכול להיות שלילי")
        val old = wedding().budgetAgorot
        db.update("UPDATE wedding SET budget_agorot=? WHERE id=?", agorot, weddingId)
        if (old != agorot) audit("WEDDING", weddingId, null, "BUDGET",
            "תקציב: ${old?.let { Money.format(it) } ?: "לא הוגדר"} ← ${agorot?.let { Money.format(it) } ?: "לא הוגדר"}")
    }

    // ------------------------------------------------------------------ categories

    private fun mapCategory(r: Row) = Category(r.long("id"), r.str("name"), r.longOrNull("budget_agorot"), r.int("sort_order"), r.bool("archived"))

    fun categories(includeArchived: Boolean = false): List<Category> = db.list(
        "SELECT * FROM category WHERE wedding_id=? ${if (includeArchived) "" else "AND archived=0"} ORDER BY sort_order, id",
        weddingId, mapper = ::mapCategory
    )

    fun category(id: Long): Category? = db.first("SELECT * FROM category WHERE id=?", id, mapper = ::mapCategory)

    private fun checkCategoryName(name: String, exceptId: Long?): String {
        val n = requireText(name, "שם קטגוריה")
        val dup = categories().any { it.name.trim() == n && it.id != exceptId }
        if (dup) throw ValidationException("כבר קיימת קטגוריה בשם \"$n\"")
        return n
    }

    fun addCategory(name: String, budget: Long? = null): Long = mutate {
        val n = checkCategoryName(name, null)
        val order = db.longValue("SELECT COALESCE(MAX(sort_order),0)+1 FROM category WHERE wedding_id=?", weddingId)
        db.insert("INSERT INTO category (wedding_id, name, budget_agorot, sort_order) VALUES (?,?,?,?)", weddingId, n, budget, order)
    }

    fun updateCategory(id: Long, name: String, budget: Long?) = mutate {
        val n = checkCategoryName(name, id)
        if (budget != null && budget < 0) throw ValidationException("תקציב לא יכול להיות שלילי")
        db.update("UPDATE category SET name=?, budget_agorot=? WHERE id=?", n, budget, id)
    }

    fun categoryUsage(id: Long): Int = db.longValue("SELECT COUNT(*) FROM expense WHERE category_id=?", id).toInt()

    /** Deletes a category. If expenses use it, they must be moved to [moveTo]. */
    fun deleteCategory(id: Long, moveTo: Long?) = mutate {
        val used = categoryUsage(id)
        if (used > 0) {
            if (moveTo == null || moveTo == id) throw ValidationException("יש לבחור קטגוריה אחרת להעברת $used הוצאות")
            requireExists("category", moveTo, "הקטגוריה")
            db.update("UPDATE expense SET category_id=? WHERE category_id=?", moveTo, id)
            db.update("UPDATE supplier SET category_id=? WHERE category_id=?", moveTo, id)
        }
        db.update("DELETE FROM category WHERE id=?", id)
    }

    fun moveCategory(id: Long, delta: Int) = mutate {
        val list = categories().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return@mutate
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        list.forEachIndexed { idx, c -> db.update("UPDATE category SET sort_order=? WHERE id=?", idx, c.id) }
    }

    // ------------------------------------------------------------------ payment methods

    private fun mapMethod(r: Row) = PaymentMethod(r.long("id"), r.str("name"), r.strOrNull("code"), r.int("sort_order"), r.bool("archived"))

    fun paymentMethods(includeArchived: Boolean = false): List<PaymentMethod> = db.list(
        "SELECT * FROM payment_method ${if (includeArchived) "" else "WHERE archived=0"} ORDER BY CASE WHEN code='OTHER' THEN 1 ELSE 0 END, sort_order, id",
        mapper = ::mapMethod
    )

    fun paymentMethod(id: Long): PaymentMethod? = db.first("SELECT * FROM payment_method WHERE id=?", id, mapper = ::mapMethod)

    fun addPaymentMethod(name: String): Long = mutate {
        val n = requireText(name, "שם אמצעי התשלום")
        paymentMethods(true).firstOrNull { it.name.trim() == n }?.let {
            if (it.archived) db.update("UPDATE payment_method SET archived=0 WHERE id=?", it.id)
            return@mutate it.id
        }
        val order = db.longValue("SELECT COALESCE(MAX(sort_order),0)+1 FROM payment_method")
        db.insert("INSERT INTO payment_method (name, sort_order) VALUES (?, ?)", n, order)
    }

    fun renamePaymentMethod(id: Long, name: String) = mutate {
        val m = paymentMethod(id) ?: throw ValidationException("אמצעי התשלום אינו קיים")
        if (m.isBuiltIn) throw ValidationException("לא ניתן לשנות שם של אמצעי תשלום מובנה")
        val n = requireText(name, "שם אמצעי התשלום")
        if (paymentMethods(true).any { it.name.trim() == n && it.id != id }) throw ValidationException("כבר קיים אמצעי תשלום בשם זה")
        db.update("UPDATE payment_method SET name=? WHERE id=?", n, id)
    }

    /** Returns true if deleted, false if archived because payments reference it. */
    fun deletePaymentMethod(id: Long): Boolean = mutate {
        val m = paymentMethod(id) ?: throw ValidationException("אמצעי התשלום אינו קיים")
        if (m.isBuiltIn) throw ValidationException("לא ניתן למחוק אמצעי תשלום מובנה")
        val used = db.longValue("SELECT COUNT(*) FROM payment WHERE method_id=?", id)
        if (used > 0) { db.update("UPDATE payment_method SET archived=1 WHERE id=?", id); false }
        else { db.update("DELETE FROM payment_method WHERE id=?", id); true }
    }

    // ------------------------------------------------------------------ expenses (commitments)

    private fun mapExpense(r: Row) = Expense(
        r.long("id"), r.str("name"), r.longOrNull("category_id"), r.longOrNull("supplier_id"), r.long("agreed_agorot"),
        r.strOrNull("next_payment_date"), r.longOrNull("next_payment_agorot"), r.strOrNull("key_date"), r.strOrNull("key_time"),
        r.strOrNull("key_date_label"), r.strOrNull("phone"), r.strOrNull("notes"), r.bool("cancelled"),
        r.longOrNull("cancelled_at"), r.strOrNull("cancel_note"), r.long("created_at"), r.long("updated_at")
    )

    private val EXPENSE_SELECT = """
        SELECT e.*, c.name AS category_name, s.name AS supplier_name, s.phone AS supplier_phone,
          COALESCE((SELECT SUM(CASE WHEN p.kind='REFUND' THEN -p.amount_agorot ELSE p.amount_agorot END) FROM payment p WHERE p.expense_id=e.id), 0) AS paid_net,
          (SELECT MAX(p.paid_date) FROM payment p WHERE p.expense_id=e.id AND p.kind='PAYMENT') AS last_paid,
          (SELECT COUNT(*) FROM payment p WHERE p.expense_id=e.id) AS pay_count
        FROM expense e
        LEFT JOIN category c ON c.id=e.category_id
        LEFT JOIN supplier s ON s.id=e.supplier_id
    """.trimIndent()

    private fun mapExpenseSummary(r: Row) = ExpenseSummary(
        mapExpense(r), r.strOrNull("category_name"), r.strOrNull("supplier_name"), r.strOrNull("supplier_phone"),
        r.long("paid_net"), r.strOrNull("last_paid"), r.int("pay_count")
    )

    fun expenses(): List<ExpenseSummary> =
        db.list("$EXPENSE_SELECT WHERE e.wedding_id=? ORDER BY e.cancelled, e.created_at DESC, e.id DESC", weddingId, mapper = ::mapExpenseSummary)

    fun expense(id: Long): ExpenseSummary? = db.first("$EXPENSE_SELECT WHERE e.id=?", id, mapper = ::mapExpenseSummary)

    fun expensesForSupplier(supplierId: Long): List<ExpenseSummary> =
        db.list("$EXPENSE_SELECT WHERE e.supplier_id=? ORDER BY e.cancelled, e.created_at", supplierId, mapper = ::mapExpenseSummary)

    data class Check(val errors: List<String>, val warnings: List<String>) {
        val ok get() = errors.isEmpty()
    }

    fun validateExpense(input: ExpenseInput, id: Long?): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (input.name.clean() == null) errors += "יש למלא שם להוצאה"
        if (input.agreedAgorot <= 0) errors += "יש להזין מחיר שסוכם גדול מאפס"
        if (input.agreedAgorot > Money.MAX_AGOROT) errors += "הסכום גדול מדי"
        if (input.categoryId != null && !exists("category", input.categoryId)) errors += "הקטגוריה שנבחרה אינה קיימת"
        if (input.supplierId != null && !exists("supplier", input.supplierId)) errors += "הספק שנבחר אינו קיים"
        if (input.nextPaymentDate.clean() != null && !Dates.isValidDate(input.nextPaymentDate)) errors += "תאריך התשלום העתידי אינו תקין"
        if (input.keyDate.clean() != null && !Dates.isValidDate(input.keyDate)) errors += "המועד החשוב אינו תאריך תקין"
        if (input.keyTime.clean() != null && !Dates.isValidTime(input.keyTime)) errors += "שעת המועד אינה תקינה"
        if (input.nextPaymentAgorot != null && input.nextPaymentAgorot <= 0) errors += "סכום התשלום העתידי חייב להיות חיובי"
        if (id != null) {
            val cur = expense(id)
            if (cur == null) errors += "ההוצאה אינה קיימת"
            else if (!cur.cancelled && input.agreedAgorot in 1 until cur.paidAgorot) {
                warnings += "המחיר החדש (${Money.format(input.agreedAgorot)}) נמוך מהסכום שכבר שולם (${Money.format(cur.paidAgorot)}). " +
                    "תיווצר יתרת זכות של ${Money.format(cur.paidAgorot - input.agreedAgorot)} אצל הספק."
            }
        }
        if (id == null && input.supplierId != null) {
            val existing = expensesForSupplier(input.supplierId).filter { !it.cancelled }
            if (existing.isNotEmpty()) warnings += "לספק זה כבר קיימת התחייבות (${existing.joinToString { it.expense.name }}). " +
                "ההוצאה החדשה תיספר בנפרד ותתווסף לסך ההתחייבויות."
        }
        return Check(errors, warnings)
    }

    fun saveExpense(input: ExpenseInput, id: Long?): Long = mutate {
        val check = validateExpense(input, id)
        if (!check.ok) throw ValidationException(check.errors.first())
        val t = now()
        val name = input.name.trim()
        val nextDate = input.nextPaymentDate.clean()
        if (id == null) {
            val newId = db.insert(
                """INSERT INTO expense (wedding_id, name, category_id, supplier_id, agreed_agorot, next_payment_date, next_payment_agorot,
                   key_date, key_time, key_date_label, phone, notes, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                weddingId, name, input.categoryId, input.supplierId, input.agreedAgorot, nextDate,
                if (nextDate == null) null else input.nextPaymentAgorot, input.keyDate.clean(), input.keyTime.clean(),
                input.keyDateLabel.clean(), input.phone.clean(), input.notes.clean(), t, t
            )
            audit("EXPENSE", newId, newId, "CREATE", "נוצרה התחייבות \"$name\" בסך ${Money.format(input.agreedAgorot)}")
            newId
        } else {
            val old = expense(id)!!
            db.update(
                """UPDATE expense SET name=?, category_id=?, supplier_id=?, agreed_agorot=?, next_payment_date=?, next_payment_agorot=?,
                   key_date=?, key_time=?, key_date_label=?, phone=?, notes=?, updated_at=? WHERE id=?""",
                name, input.categoryId, input.supplierId, input.agreedAgorot, nextDate,
                if (nextDate == null) null else input.nextPaymentAgorot, input.keyDate.clean(), input.keyTime.clean(),
                input.keyDateLabel.clean(), input.phone.clean(), input.notes.clean(), t, id
            )
            if (old.expense.agreedAgorot != input.agreedAgorot) {
                audit("EXPENSE", id, id, "PRICE", "המחיר שסוכם עודכן מ-${Money.format(old.expense.agreedAgorot)} ל-${Money.format(input.agreedAgorot)}")
            }
            if (old.expense.supplierId != input.supplierId) {
                // keep linked tasks consistent with the new supplier
                db.update("UPDATE task SET supplier_id=? WHERE expense_id=?", input.supplierId, id)
            }
            if (old.expense.nextPaymentDate != nextDate) clearSnooze(TargetType.EXPENSE_PAYMENT, id)
            id
        }
    }

    /** Quick add: an expense that is paid in full right now, as one atomic operation. */
    fun addPaidExpense(input: ExpenseInput, date: String, methodId: Long?, methodNote: String?): Long = mutate {
        val id = saveExpense(input, null)
        addPayment(PaymentInput(id, input.agreedAgorot, date, methodId, methodNote), OverpayPolicy.REJECT)
        id
    }

    data class LinkCounts(val tasks: Int, val events: Int, val documents: Int, val transports: Int, val expenses: Int, val payments: Int)

    fun expenseLinks(id: Long) = LinkCounts(
        db.longValue("SELECT COUNT(*) FROM task WHERE expense_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM event WHERE expense_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM document WHERE expense_id=?", id).toInt(),
        0, 0,
        db.longValue("SELECT COUNT(*) FROM payment WHERE expense_id=?", id).toInt()
    )

    /**
     * Cancels a commitment. Payment history is kept. [refundAgorot] (0..paid) is recorded as a REFUND
     * record; whatever was paid and not refunded remains as the actual cost of the cancelled commitment.
     */
    fun cancelExpense(id: Long, refundAgorot: Long, refundDate: String?, refundMethodId: Long?, note: String?) = mutate {
        val e = expense(id) ?: throw ValidationException("ההוצאה אינה קיימת")
        if (e.cancelled) throw ValidationException("ההתחייבות כבר בוטלה")
        if (refundAgorot < 0) throw ValidationException("סכום ההחזר אינו תקין")
        if (refundAgorot > maxOf(e.paidAgorot, 0)) throw ValidationException("סכום ההחזר גבוה מהסכום ששולם (${Money.format(e.paidAgorot)})")
        if (refundAgorot > 0) {
            val d = checkDate(refundDate, "תאריך ההחזר") ?: throw ValidationException("יש לבחור תאריך החזר")
            requireExists("payment_method", refundMethodId, "אמצעי התשלום")
            val t = now()
            val pid = db.insert(
                "INSERT INTO payment (expense_id, amount_agorot, kind, paid_date, method_id, note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                id, refundAgorot, PaymentKind.REFUND.name, d, refundMethodId, "החזר בעקבות ביטול ההתחייבות", t, t
            )
            audit("PAYMENT", pid, id, "REFUND", "נרשם החזר של ${Money.format(refundAgorot)} בתאריך ${Dates.display(d)}")
        }
        db.update("UPDATE expense SET cancelled=1, cancelled_at=?, cancel_note=?, updated_at=? WHERE id=?", now(), note.clean(), now(), id)
        val kept = e.paidAgorot - refundAgorot
        audit("EXPENSE", id, id, "CANCEL", "ההתחייבות בוטלה." + if (kept > 0) " סכום שלא הוחזר: ${Money.format(kept)}" else "")
    }

    fun restoreExpense(id: Long) = mutate {
        val e = expense(id) ?: throw ValidationException("ההוצאה אינה קיימת")
        if (!e.cancelled) return@mutate
        e.expense.supplierId?.let { sid ->
            if (supplier(sid)?.cancelled == true) throw ValidationException("ההתקשרות עם הספק מבוטלת. יש לשחזר קודם את הספק.")
        }
        db.update("UPDATE expense SET cancelled=0, cancelled_at=NULL, updated_at=? WHERE id=?", now(), id)
        audit("EXPENSE", id, id, "RESTORE", "ההתחייבות שוחזרה")
    }

    /** Deletes an expense only if it has no payment history. Links (tasks/docs/events) are detached. */
    fun deleteExpense(id: Long) = mutate {
        val links = expenseLinks(id)
        if (links.payments > 0) throw ValidationException("לא ניתן למחוק הוצאה שיש לה היסטוריית תשלומים. אפשר לבטל אותה במקום.")
        deleteReminderFor(TargetType.EXPENSE_PAYMENT, id)
        db.update("UPDATE task SET expense_id=NULL WHERE expense_id=?", id)
        db.update("UPDATE event SET expense_id=NULL WHERE expense_id=?", id)
        db.update("UPDATE document SET expense_id=NULL WHERE expense_id=?", id)
        db.update("DELETE FROM expense WHERE id=?", id)
    }

    // ------------------------------------------------------------------ payments

    private fun mapPayment(r: Row) = Payment(
        r.long("id"), r.long("expense_id"), r.long("amount_agorot"), PaymentKind.valueOf(r.str("kind")), r.str("paid_date"),
        r.longOrNull("method_id"), r.strOrNull("method_note"), r.strOrNull("note"), r.long("created_at"), r.long("updated_at")
    )

    private val PAYMENT_SELECT = """
        SELECT p.*, e.name AS expense_name, e.supplier_id AS supplier_id, s.name AS supplier_name, m.name AS method_name
        FROM payment p JOIN expense e ON e.id=p.expense_id
        LEFT JOIN supplier s ON s.id=e.supplier_id
        LEFT JOIN payment_method m ON m.id=p.method_id
    """.trimIndent()

    private fun mapPaymentView(r: Row) = PaymentView(
        mapPayment(r), r.str("expense_name"), r.longOrNull("supplier_id"), r.strOrNull("supplier_name"), r.strOrNull("method_name")
    )

    fun payments(expenseId: Long): List<PaymentView> =
        db.list("$PAYMENT_SELECT WHERE p.expense_id=? ORDER BY p.paid_date DESC, p.id DESC", expenseId, mapper = ::mapPaymentView)

    fun paymentsForSupplier(supplierId: Long): List<PaymentView> =
        db.list("$PAYMENT_SELECT WHERE e.supplier_id=? ORDER BY p.paid_date DESC, p.id DESC", supplierId, mapper = ::mapPaymentView)

    fun allPayments(): List<PaymentView> =
        db.list("$PAYMENT_SELECT WHERE e.wedding_id=? ORDER BY p.paid_date DESC, p.id DESC", weddingId, mapper = ::mapPaymentView)

    fun payment(id: Long): PaymentView? = db.first("$PAYMENT_SELECT WHERE p.id=?", id, mapper = ::mapPaymentView)

    /** Validates a payment before saving. [editingId] excludes that payment from the current total. */
    fun checkPayment(input: PaymentInput, editingId: Long? = null): PaymentCheck {
        if (input.amountAgorot <= 0) return PaymentCheck.Error("יש להזין סכום גדול מאפס")
        if (input.amountAgorot > Money.MAX_AGOROT) return PaymentCheck.Error("הסכום גדול מדי")
        if (!Dates.isValidDate(input.date)) return PaymentCheck.Error("יש לבחור תאריך תשלום תקין")
        val e = expense(input.expenseId) ?: return PaymentCheck.Error("ההוצאה אינה קיימת")
        if (input.methodId != null && paymentMethod(input.methodId) == null) return PaymentCheck.Error("אמצעי התשלום אינו קיים")
        val editing = editingId?.let { payment(it)?.payment }
        if (editingId != null && editing == null) return PaymentCheck.Error("התשלום אינו קיים")
        if (editing != null && editing.expenseId != input.expenseId) return PaymentCheck.Error("לא ניתן להעביר תשלום להוצאה אחרת")
        val paidOther = e.paidAgorot - (editing?.signedAgorot ?: 0)
        return when (input.kind) {
            PaymentKind.PAYMENT -> {
                if (e.cancelled) return PaymentCheck.Error("ההתחייבות בוטלה. לא ניתן לרשום אליה תשלום חדש.")
                val newPaid = paidOther + input.amountAgorot
                if (newPaid > e.expense.agreedAgorot) PaymentCheck.Overpay(e.expense.agreedAgorot - paidOther, newPaid - e.expense.agreedAgorot, newPaid)
                else PaymentCheck.Ok
            }
            PaymentKind.REFUND -> {
                if (input.amountAgorot > paidOther) PaymentCheck.Error("סכום ההחזר גבוה מהסכום ששולם (${Money.format(paidOther)})")
                else PaymentCheck.Ok
            }
        }
    }

    private fun applyCheck(input: PaymentInput, editingId: Long?, policy: OverpayPolicy) {
        when (val c = checkPayment(input, editingId)) {
            is PaymentCheck.Error -> throw ValidationException(c.message)
            is PaymentCheck.Overpay -> when (policy) {
                OverpayPolicy.REJECT -> throw ValidationException(
                    "התשלום גבוה מהיתרה לתשלום (${Money.format(maxOf(c.remainingAgorot, 0))}) ב-${Money.format(c.excessAgorot)}")
                OverpayPolicy.ALLOW_CREDIT -> {}
                OverpayPolicy.RAISE_AGREED -> {
                    val e = expense(input.expenseId)!!
                    db.update("UPDATE expense SET agreed_agorot=?, updated_at=? WHERE id=?", c.newPaidAgorot, now(), input.expenseId)
                    audit("EXPENSE", input.expenseId, input.expenseId, "PRICE",
                        "המחיר שסוכם עודכן מ-${Money.format(e.expense.agreedAgorot)} ל-${Money.format(c.newPaidAgorot)} בעקבות תשלום")
                }
            }
            PaymentCheck.Ok -> {}
        }
    }

    private fun methodLabel(methodId: Long?, note: String?): String {
        val m = methodId?.let { paymentMethod(it)?.name }
        return listOfNotNull(m, note.clean()).joinToString(": ").ifEmpty { "לא צוין" }
    }

    fun addPayment(input: PaymentInput, policy: OverpayPolicy = OverpayPolicy.REJECT): PaymentResult = mutate {
        applyCheck(input, null, policy)
        val t = now()
        val id = db.insert(
            "INSERT INTO payment (expense_id, amount_agorot, kind, paid_date, method_id, method_note, note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
            input.expenseId, input.amountAgorot, input.kind.name, input.date, input.methodId, input.methodNote.clean(), input.note.clean(), t, t
        )
        db.update("UPDATE expense SET updated_at=? WHERE id=?", t, input.expenseId)
        audit("PAYMENT", id, input.expenseId, if (input.kind == PaymentKind.REFUND) "REFUND" else "ADD",
            "${input.kind.label} ${Money.format(input.amountAgorot)} · ${Dates.display(input.date)} · ${methodLabel(input.methodId, input.methodNote)}")
        resultFor(id, input.expenseId)
    }

    fun updatePayment(id: Long, input: PaymentInput, policy: OverpayPolicy = OverpayPolicy.REJECT): PaymentResult = mutate {
        val old = payment(id)?.payment ?: throw ValidationException("התשלום אינו קיים")
        applyCheck(input, id, policy)
        db.update(
            "UPDATE payment SET amount_agorot=?, kind=?, paid_date=?, method_id=?, method_note=?, note=?, updated_at=? WHERE id=?",
            input.amountAgorot, input.kind.name, input.date, input.methodId, input.methodNote.clean(), input.note.clean(), now(), id
        )
        db.update("UPDATE expense SET updated_at=? WHERE id=?", now(), input.expenseId)
        audit("PAYMENT", id, input.expenseId, "EDIT",
            "תשלום עודכן: ${Money.format(old.amountAgorot)} (${Dates.display(old.date)}) ← ${Money.format(input.amountAgorot)} (${Dates.display(input.date)}) · ${methodLabel(input.methodId, input.methodNote)}")
        resultFor(id, input.expenseId)
    }

    private fun resultFor(paymentId: Long, expenseId: Long): PaymentResult {
        val e = expense(expenseId)!!
        val related = if (e.remainingAgorot <= 0) tasksForExpense(expenseId).filter { it.task.status.isActive } else emptyList()
        return PaymentResult(paymentId, e.remainingAgorot, related)
    }

    /** Deletes a payment record (e.g. a mistaken entry). The deletion itself is recorded in the audit log. */
    fun deletePayment(id: Long, reason: String?) = mutate {
        val p = payment(id) ?: throw ValidationException("התשלום אינו קיים")
        db.update("UPDATE task SET payment_id=NULL WHERE payment_id=?", id)
        db.update("UPDATE document SET payment_id=NULL WHERE payment_id=?", id)
        db.update("DELETE FROM payment WHERE id=?", id)
        db.update("UPDATE expense SET updated_at=? WHERE id=?", now(), p.payment.expenseId)
        audit("PAYMENT", id, p.payment.expenseId, "DELETE",
            "נמחק ${p.payment.kind.label} של ${Money.format(p.payment.amountAgorot)} מתאריך ${Dates.display(p.payment.date)} (${p.methodLabel})" +
                (reason.clean()?.let { ". סיבה: $it" } ?: ""))
    }

    fun auditForExpense(expenseId: Long): List<AuditEntry> = db.list(
        "SELECT * FROM audit_log WHERE expense_id=? ORDER BY created_at DESC, id DESC", expenseId
    ) { AuditEntry(it.long("id"), it.str("entity_type"), it.long("entity_id"), it.str("action"), it.strOrNull("details"), it.long("created_at")) }

    // ------------------------------------------------------------------ suppliers

    private fun mapSupplier(r: Row) = Supplier(
        r.long("id"), r.str("name"), r.strOrNull("service"), r.longOrNull("category_id"), r.strOrNull("phone"),
        r.strOrNull("contact_name"), r.strOrNull("arrival_info"), r.strOrNull("notes"), r.bool("cancelled"),
        r.long("created_at"), r.long("updated_at")
    )

    fun supplier(id: Long): Supplier? = db.first("SELECT * FROM supplier WHERE id=?", id, mapper = ::mapSupplier)

    private fun summarize(s: Supplier, catName: String?, exps: List<ExpenseSummary>): SupplierSummary {
        val active = exps.filter { !it.cancelled }
        val committed = exps.sumOf { it.effectiveCommitmentAgorot }
        val paid = exps.sumOf { it.paidAgorot }
        val remaining = active.sumOf { maxOf(it.remainingAgorot, 0) }
        val status = when {
            s.cancelled -> SupplierStatus.CANCELLED
            active.isEmpty() -> SupplierStatus.NO_COMMITMENT
            active.all { it.status == ExpenseStatus.PAID } -> SupplierStatus.PAID
            active.any { it.paidAgorot > 0 } -> SupplierStatus.PARTIAL
            else -> SupplierStatus.COMMITTED
        }
        return SupplierSummary(s, catName, status, committed, paid, remaining, active.size, exps.size)
    }

    fun suppliers(): List<SupplierSummary> {
        val byS = expenses().groupBy { it.expense.supplierId }
        val cats = categories(true).associateBy { it.id }
        return db.list("SELECT * FROM supplier WHERE wedding_id=? ORDER BY cancelled, name COLLATE NOCASE", weddingId, mapper = ::mapSupplier)
            .map { s -> summarize(s, s.categoryId?.let { cats[it]?.name }, byS[s.id] ?: emptyList()) }
    }

    fun supplierSummary(id: Long): SupplierSummary? {
        val s = supplier(id) ?: return null
        return summarize(s, s.categoryId?.let { category(it)?.name }, expensesForSupplier(id))
    }

    /** The supplier's single active commitment, if exactly one exists (used by the supplier form's price field). */
    fun primaryCommitment(supplierId: Long): ExpenseSummary? =
        expensesForSupplier(supplierId).filter { !it.cancelled }.singleOrNull()

    fun validateSupplier(input: SupplierInput, id: Long?): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (input.name.clean() == null) errors += "יש למלא שם ספק"
        if (input.categoryId != null && !exists("category", input.categoryId)) errors += "הקטגוריה שנבחרה אינה קיימת"
        val price = input.initialAgreedAgorot
        if (price != null && price < 0) errors += "המחיר שסוכם אינו תקין"
        if (id != null && price != null) {
            val active = expensesForSupplier(id).filter { !it.cancelled }
            if (active.size == 1 && price in 1 until active[0].paidAgorot) {
                warnings += "המחיר החדש נמוך מהסכום שכבר שולם (${Money.format(active[0].paidAgorot)}). תיווצר יתרת זכות אצל הספק."
            }
            if (active.size == 1 && price == 0L) errors += "לא ניתן לאפס מחיר של התחייבות קיימת. אפשר לבטל את ההתחייבות."
        }
        val name = input.name.clean()
        if (name != null && db.longValue("SELECT COUNT(*) FROM supplier WHERE wedding_id=? AND trim(name)=? AND id<>?", weddingId, name, id ?: -1) > 0) {
            warnings += "כבר קיים ספק בשם \"$name\""
        }
        return Check(errors, warnings)
    }

    /**
     * Creates/updates a supplier. [SupplierInput.initialAgreedAgorot] is the agreed price of the supplier's
     * commitment: on create it creates the linked expense; on update it edits the single active commitment
     * (or creates one if none exists). It never creates a duplicate commitment.
     */
    fun saveSupplier(input: SupplierInput, id: Long?): Long = mutate {
        val check = validateSupplier(input, id)
        if (!check.ok) throw ValidationException(check.errors.first())
        val t = now()
        val name = input.name.trim()
        val sid = if (id == null) {
            db.insert(
                """INSERT INTO supplier (wedding_id, name, service, category_id, phone, contact_name, arrival_info, notes, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?)""",
                weddingId, name, input.service.clean(), input.categoryId, input.phone.clean(), input.contactName.clean(),
                input.arrivalInfo.clean(), input.notes.clean(), t, t
            )
        } else {
            val old = supplier(id) ?: throw ValidationException("הספק אינו קיים")
            db.update(
                """UPDATE supplier SET name=?, service=?, category_id=?, phone=?, contact_name=?, arrival_info=?, notes=?, updated_at=? WHERE id=?""",
                name, input.service.clean(), input.categoryId, input.phone.clean(), input.contactName.clean(),
                input.arrivalInfo.clean(), input.notes.clean(), t, id
            )
            if (old.name != name) db.update("UPDATE expense SET name=?, updated_at=? WHERE supplier_id=? AND name=?", name, t, id, old.name)
            id
        }
        val price = input.initialAgreedAgorot
        if (price != null && price > 0) {
            val active = expensesForSupplier(sid).filter { !it.cancelled }
            when (active.size) {
                0 -> saveExpense(ExpenseInput(name, input.categoryId, sid, price, phone = null), null)
                1 -> {
                    val e = active[0].expense
                    if (e.agreedAgorot != price || (input.categoryId != null && e.categoryId == null)) {
                        saveExpense(
                            ExpenseInput(e.name, e.categoryId ?: input.categoryId, sid, price, e.nextPaymentDate, e.nextPaymentAgorot,
                                e.keyDate, e.keyTime, e.keyDateLabel, e.phone, e.notes), e.id
                        )
                    }
                }
                else -> {} // several commitments: edited individually from the expense screens
            }
        }
        sid
    }

    data class RefundSpec(val amountAgorot: Long, val date: String?, val methodId: Long?)

    /** Marks the engagement with a supplier as cancelled and cancels its active commitments. */
    fun cancelSupplier(id: Long, refunds: Map<Long, RefundSpec>, note: String?) = mutate {
        val s = supplier(id) ?: throw ValidationException("הספק אינו קיים")
        if (s.cancelled) return@mutate
        expensesForSupplier(id).filter { !it.cancelled }.forEach { e ->
            val r = refunds[e.id]
            cancelExpense(e.id, r?.amountAgorot ?: 0, r?.date, r?.methodId, note ?: "ההתקשרות עם הספק בוטלה")
        }
        db.update("UPDATE supplier SET cancelled=1, cancelled_at=?, updated_at=? WHERE id=?", now(), now(), id)
        audit("SUPPLIER", id, null, "CANCEL", "ההתקשרות עם ${s.name} בוטלה")
    }

    fun restoreSupplier(id: Long) = mutate {
        db.update("UPDATE supplier SET cancelled=0, cancelled_at=NULL, updated_at=? WHERE id=?", now(), id)
        audit("SUPPLIER", id, null, "RESTORE", "ההתקשרות עם הספק שוחזרה")
    }

    fun supplierLinks(id: Long) = LinkCounts(
        db.longValue("SELECT COUNT(*) FROM task WHERE supplier_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM event WHERE supplier_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM document WHERE supplier_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM transport WHERE supplier_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM expense WHERE supplier_id=?", id).toInt(),
        db.longValue("SELECT COUNT(*) FROM payment p JOIN expense e ON e.id=p.expense_id WHERE e.supplier_id=?", id).toInt()
    )

    /** Deletes a supplier with no financial records. Tasks, events, documents and transports are kept and detached. */
    fun deleteSupplier(id: Long) = mutate {
        val links = supplierLinks(id)
        if (links.expenses > 0) throw ValidationException("לא ניתן למחוק ספק שיש לו התחייבויות כספיות. אפשר לבטל את ההתקשרות במקום.")
        db.update("UPDATE task SET supplier_id=NULL WHERE supplier_id=?", id)
        db.update("UPDATE event SET supplier_id=NULL WHERE supplier_id=?", id)
        db.update("UPDATE document SET supplier_id=NULL WHERE supplier_id=?", id)
        db.update("UPDATE transport SET supplier_id=NULL WHERE supplier_id=?", id)
        db.update("DELETE FROM supplier WHERE id=?", id)
    }

    // ------------------------------------------------------------------ tasks

    private fun mapTask(r: Row) = Task(
        r.long("id"), r.str("title"), r.strOrNull("description"), r.strOrNull("due_date"), r.strOrNull("due_time"),
        Priority.of(r.int("priority")), TaskStatus.valueOf(r.str("status")), r.longOrNull("supplier_id"), r.longOrNull("expense_id"),
        r.longOrNull("payment_id"), r.strOrNull("notes"), r.longOrNull("completed_at"), r.long("created_at"), r.long("updated_at")
    )

    private val TASK_SELECT = """
        SELECT t.*, s.name AS supplier_name, e.name AS expense_name FROM task t
        LEFT JOIN supplier s ON s.id=t.supplier_id LEFT JOIN expense e ON e.id=t.expense_id
    """.trimIndent()

    private fun mapTaskView(r: Row) = TaskView(mapTask(r), r.strOrNull("supplier_name"), r.strOrNull("expense_name"))

    fun tasks(): List<TaskView> =
        db.list("$TASK_SELECT WHERE t.wedding_id=? ORDER BY t.due_date IS NULL, t.due_date, t.due_time, t.priority DESC, t.id", weddingId, mapper = ::mapTaskView)

    fun task(id: Long): TaskView? = db.first("$TASK_SELECT WHERE t.id=?", id, mapper = ::mapTaskView)
    fun tasksForSupplier(id: Long): List<TaskView> = db.list("$TASK_SELECT WHERE t.supplier_id=? ORDER BY t.status IN ('DONE','CANCELLED'), t.due_date IS NULL, t.due_date", id, mapper = ::mapTaskView)
    fun tasksForExpense(id: Long): List<TaskView> = db.list("$TASK_SELECT WHERE t.expense_id=? ORDER BY t.status IN ('DONE','CANCELLED'), t.due_date IS NULL, t.due_date", id, mapper = ::mapTaskView)

    fun saveTask(input: TaskInput, id: Long?): Long = mutate {
        val title = requireText(input.title, "כותרת למשימה")
        val dd = checkDate(input.dueDate, "תאריך היעד")
        val dt = checkTime(input.dueTime, "שעת היעד")
        if (dt != null && dd == null) throw ValidationException("לא ניתן להגדיר שעה בלי תאריך יעד")
        requireExists("supplier", input.supplierId, "הספק")
        requireExists("expense", input.expenseId, "ההוצאה")
        requireExists("payment", input.paymentId, "התשלום")
        var supplierId = input.supplierId
        if (input.expenseId != null) {
            val e = expense(input.expenseId)!!
            if (supplierId == null) supplierId = e.expense.supplierId
            else if (e.expense.supplierId != null && e.expense.supplierId != supplierId)
                throw ValidationException("ההוצאה שנבחרה שייכת לספק אחר")
        }
        val t = now()
        val completedAt = if (input.status == TaskStatus.DONE) t else null
        if (id == null) {
            db.insert(
                """INSERT INTO task (wedding_id, title, description, due_date, due_time, priority, status, supplier_id, expense_id, payment_id, notes, completed_at, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                weddingId, title, input.description.clean(), dd, dt, input.priority.value, input.status.name, supplierId,
                input.expenseId, input.paymentId, input.notes.clean(), completedAt, t, t
            )
        } else {
            val old = task(id)?.task ?: throw ValidationException("המשימה אינה קיימת")
            db.update(
                """UPDATE task SET title=?, description=?, due_date=?, due_time=?, priority=?, status=?, supplier_id=?, expense_id=?, payment_id=?, notes=?,
                   completed_at=?, updated_at=? WHERE id=?""",
                title, input.description.clean(), dd, dt, input.priority.value, input.status.name, supplierId, input.expenseId,
                input.paymentId, input.notes.clean(), if (input.status == TaskStatus.DONE) (old.completedAt ?: t) else null, t, id
            )
            if (old.dueDate != dd || old.dueTime != dt) clearSnooze(TargetType.TASK, id)
            id
        }
    }

    /** Changes only the urgency of an existing task (quick action from lists and the task card). */
    fun setTaskPriority(id: Long, priority: Priority) = mutate {
        if (db.update("UPDATE task SET priority=?, updated_at=? WHERE id=?", priority.value, now(), id) == 0)
            throw ValidationException("המשימה אינה קיימת")
    }

    fun setTaskStatus(id: Long, status: TaskStatus) = mutate {
        val t = now()
        db.update("UPDATE task SET status=?, completed_at=?, updated_at=? WHERE id=?", status.name, if (status == TaskStatus.DONE) t else null, t, id)
    }

    fun deleteTask(id: Long) = mutate {
        deleteReminderFor(TargetType.TASK, id)
        db.update("UPDATE document SET task_id=NULL WHERE task_id=?", id)
        db.update("DELETE FROM task WHERE id=?", id)
    }

    fun taskBuckets(): LinkedHashMap<TaskBucket, List<TaskView>> = TaskLogic.bucketize(tasks(), nowDateTime())

    // ------------------------------------------------------------------ events

    private fun mapEvent(r: Row) = EventRec(
        r.long("id"), r.str("title"), r.str("event_date"), r.strOrNull("event_time"), r.strOrNull("location"),
        r.longOrNull("supplier_id"), r.longOrNull("expense_id"), r.strOrNull("notes"), EventStatus.valueOf(r.str("status")),
        r.long("created_at"), r.long("updated_at")
    )

    private val EVENT_SELECT = """
        SELECT ev.*, s.name AS supplier_name, e.name AS expense_name FROM event ev
        LEFT JOIN supplier s ON s.id=ev.supplier_id LEFT JOIN expense e ON e.id=ev.expense_id
    """.trimIndent()

    private fun mapEventView(r: Row) = EventView(mapEvent(r), r.strOrNull("supplier_name"), r.strOrNull("expense_name"))

    fun events(): List<EventView> = db.list("$EVENT_SELECT WHERE ev.wedding_id=? ORDER BY ev.event_date, ev.event_time IS NULL, ev.event_time", weddingId, mapper = ::mapEventView)
    fun event(id: Long): EventView? = db.first("$EVENT_SELECT WHERE ev.id=?", id, mapper = ::mapEventView)
    fun eventsForSupplier(id: Long): List<EventView> = db.list("$EVENT_SELECT WHERE ev.supplier_id=? ORDER BY ev.event_date, ev.event_time", id, mapper = ::mapEventView)
    fun eventsForExpense(id: Long): List<EventView> = db.list("$EVENT_SELECT WHERE ev.expense_id=? ORDER BY ev.event_date, ev.event_time", id, mapper = ::mapEventView)

    fun saveEvent(input: EventInput, id: Long?): Long = mutate {
        val title = requireText(input.title, "כותרת לאירוע")
        val d = checkDate(input.date, "תאריך האירוע") ?: throw ValidationException("יש לבחור תאריך לאירוע")
        val tm = checkTime(input.time, "שעת האירוע")
        requireExists("supplier", input.supplierId, "הספק")
        requireExists("expense", input.expenseId, "ההוצאה")
        var supplierId = input.supplierId
        if (input.expenseId != null && supplierId == null) supplierId = expense(input.expenseId)!!.expense.supplierId
        val t = now()
        if (id == null) {
            db.insert(
                "INSERT INTO event (wedding_id, title, event_date, event_time, location, supplier_id, expense_id, notes, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                weddingId, title, d, tm, input.location.clean(), supplierId, input.expenseId, input.notes.clean(), input.status.name, t, t
            )
        } else {
            val old = event(id)?.event ?: throw ValidationException("האירוע אינו קיים")
            db.update(
                "UPDATE event SET title=?, event_date=?, event_time=?, location=?, supplier_id=?, expense_id=?, notes=?, status=?, updated_at=? WHERE id=?",
                title, d, tm, input.location.clean(), supplierId, input.expenseId, input.notes.clean(), input.status.name, t, id
            )
            if (old.date != d || old.time != tm) clearSnooze(TargetType.EVENT, id)
            id
        }
    }

    fun setEventStatus(id: Long, status: EventStatus) = mutate {
        db.update("UPDATE event SET status=?, updated_at=? WHERE id=?", status.name, now(), id)
    }

    fun deleteEvent(id: Long) = mutate {
        deleteReminderFor(TargetType.EVENT, id)
        db.update("UPDATE document SET event_id=NULL WHERE event_id=?", id)
        db.update("DELETE FROM event WHERE id=?", id)
    }

    // ------------------------------------------------------------------ transports

    private fun mapTransport(r: Row) = Transport(
        r.long("id"), r.strOrNull("title"), r.str("transport_date"), r.strOrNull("depart_time"), r.strOrNull("depart_place"),
        r.strOrNull("destination"), r.strOrNull("driver_name"), r.strOrNull("driver_phone"), r.longOrNull("supplier_id"),
        r.strOrNull("notes"), r.long("created_at"), r.long("updated_at")
    )

    fun transports(): List<Transport> = db.list("SELECT * FROM transport WHERE wedding_id=? ORDER BY transport_date, depart_time IS NULL, depart_time", weddingId, mapper = ::mapTransport)
    fun transport(id: Long): Transport? = db.first("SELECT * FROM transport WHERE id=?", id, mapper = ::mapTransport)

    fun saveTransport(input: TransportInput, id: Long?): Long = mutate {
        val d = checkDate(input.date, "תאריך ההסעה") ?: throw ValidationException("יש לבחור תאריך להסעה")
        val tm = checkTime(input.departTime, "שעת היציאה")
        if (input.departPlace.clean() == null && input.destination.clean() == null && input.title.clean() == null)
            throw ValidationException("יש למלא לפחות נקודת יציאה או יעד")
        requireExists("supplier", input.supplierId, "הספק")
        val t = now()
        if (id == null) {
            db.insert(
                """INSERT INTO transport (wedding_id, title, transport_date, depart_time, depart_place, destination, driver_name, driver_phone, supplier_id, notes, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                weddingId, input.title.clean(), d, tm, input.departPlace.clean(), input.destination.clean(), input.driverName.clean(),
                input.driverPhone.clean(), input.supplierId, input.notes.clean(), t, t
            )
        } else {
            val old = transport(id) ?: throw ValidationException("ההסעה אינה קיימת")
            db.update(
                """UPDATE transport SET title=?, transport_date=?, depart_time=?, depart_place=?, destination=?, driver_name=?, driver_phone=?, supplier_id=?, notes=?, updated_at=? WHERE id=?""",
                input.title.clean(), d, tm, input.departPlace.clean(), input.destination.clean(), input.driverName.clean(),
                input.driverPhone.clean(), input.supplierId, input.notes.clean(), t, id
            )
            if (old.date != d || old.departTime != tm) clearSnooze(TargetType.TRANSPORT, id)
            id
        }
    }

    fun deleteTransport(id: Long) = mutate {
        deleteReminderFor(TargetType.TRANSPORT, id)
        db.update("UPDATE document SET transport_id=NULL WHERE transport_id=?", id)
        db.update("DELETE FROM transport WHERE id=?", id)
    }

    // ------------------------------------------------------------------ documents

    private fun mapDocument(r: Row) = DocumentRec(
        r.long("id"), r.str("title"), DocType.of(r.strOrNull("doc_type")), r.str("file_name"), r.strOrNull("mime_type"),
        r.longOrNull("size_bytes"), r.strOrNull("original_name"), r.strOrNull("doc_date"), r.longOrNull("supplier_id"),
        r.longOrNull("expense_id"), r.longOrNull("payment_id"), r.longOrNull("task_id"), r.longOrNull("event_id"),
        r.longOrNull("transport_id"), r.strOrNull("notes"), r.long("created_at")
    )

    private val DOC_SELECT = """
        SELECT d.*, s.name AS supplier_name, e.name AS expense_name,
          CASE WHEN p.id IS NULL THEN NULL ELSE pe.name || ' ' || p.paid_date END AS payment_label, p.amount_agorot AS payment_amount,
          t.title AS task_title, ev.title AS event_title,
          CASE WHEN tr.id IS NULL THEN NULL ELSE COALESCE(tr.title, tr.destination, tr.depart_place, 'הסעה') END AS transport_title
        FROM document d
        LEFT JOIN supplier s ON s.id=d.supplier_id
        LEFT JOIN expense e ON e.id=d.expense_id
        LEFT JOIN payment p ON p.id=d.payment_id
        LEFT JOIN expense pe ON pe.id=p.expense_id
        LEFT JOIN task t ON t.id=d.task_id
        LEFT JOIN event ev ON ev.id=d.event_id
        LEFT JOIN transport tr ON tr.id=d.transport_id
    """.trimIndent()

    private fun mapDocView(r: Row): DocumentView {
        val payLabel = r.strOrNull("payment_label")?.let { label ->
            val amt = r.longOrNull("payment_amount")
            val parts = label.split(' ')
            val date = parts.lastOrNull()
            val name = label.removeSuffix(" $date")
            "${amt?.let { Money.format(it) } ?: ""} · $name · ${Dates.display(date)}"
        }
        return DocumentView(mapDocument(r), r.strOrNull("supplier_name"), r.strOrNull("expense_name"), payLabel,
            r.strOrNull("task_title"), r.strOrNull("event_title"), r.strOrNull("transport_title"))
    }

    fun documents(): List<DocumentView> = db.list("$DOC_SELECT WHERE d.wedding_id=? ORDER BY d.created_at DESC, d.id DESC", weddingId, mapper = ::mapDocView)
    fun document(id: Long): DocumentView? = db.first("$DOC_SELECT WHERE d.id=?", id, mapper = ::mapDocView)

    /** Documents of a supplier, including those attached to its expenses and payments (same records, no copies). */
    fun documentsForSupplier(id: Long): List<DocumentView> = db.list(
        """$DOC_SELECT WHERE d.supplier_id=? OR d.expense_id IN (SELECT id FROM expense WHERE supplier_id=?)
           OR d.payment_id IN (SELECT p2.id FROM payment p2 JOIN expense e2 ON e2.id=p2.expense_id WHERE e2.supplier_id=?)
           ORDER BY d.created_at DESC""", id, id, id, mapper = ::mapDocView
    )

    fun documentsForExpense(id: Long): List<DocumentView> = db.list(
        "$DOC_SELECT WHERE d.expense_id=? OR d.payment_id IN (SELECT id FROM payment WHERE expense_id=?) ORDER BY d.created_at DESC",
        id, id, mapper = ::mapDocView
    )

    fun documentsFor(column: String, id: Long): List<DocumentView> {
        require(column in setOf("payment_id", "task_id", "event_id", "transport_id"))
        return db.list("$DOC_SELECT WHERE d.$column=? ORDER BY d.created_at DESC", id, mapper = ::mapDocView)
    }

    private fun checkDocLinks(i: DocumentInput) {
        requireExists("supplier", i.supplierId, "הספק")
        requireExists("expense", i.expenseId, "ההוצאה")
        requireExists("payment", i.paymentId, "התשלום")
        requireExists("task", i.taskId, "המשימה")
        requireExists("event", i.eventId, "האירוע")
        requireExists("transport", i.transportId, "ההסעה")
    }

    fun addDocument(i: DocumentInput): Long = mutate {
        val title = requireText(i.title, "שם למסמך")
        if (i.fileName.isBlank() || i.fileName.contains('/') || i.fileName.contains("..")) throw ValidationException("שם קובץ לא תקין")
        checkDocLinks(i)
        db.insert(
            """INSERT INTO document (wedding_id, title, doc_type, file_name, mime_type, size_bytes, original_name, doc_date, supplier_id, expense_id,
               payment_id, task_id, event_id, transport_id, notes, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            weddingId, title, i.type.name, i.fileName, i.mimeType, i.sizeBytes, i.originalName.clean(), checkDate(i.docDate, "תאריך המסמך"),
            i.supplierId, i.expenseId, i.paymentId, i.taskId, i.eventId, i.transportId, i.notes.clean(), now()
        )
    }

    fun updateDocument(id: Long, i: DocumentInput) = mutate {
        val title = requireText(i.title, "שם למסמך")
        checkDocLinks(i)
        db.update(
            """UPDATE document SET title=?, doc_type=?, doc_date=?, supplier_id=?, expense_id=?, payment_id=?, task_id=?, event_id=?, transport_id=?, notes=? WHERE id=?""",
            title, i.type.name, checkDate(i.docDate, "תאריך המסמך"), i.supplierId, i.expenseId, i.paymentId, i.taskId, i.eventId,
            i.transportId, i.notes.clean(), id
        )
    }

    /** Deletes the record; returns the stored file name so the caller can delete the file. */
    fun deleteDocument(id: Long): String? = mutate {
        val f = db.first("SELECT file_name FROM document WHERE id=?", id) { it.str("file_name") }
        db.update("DELETE FROM document WHERE id=?", id)
        f
    }

    fun allDocumentFileNames(): List<String> = db.list("SELECT file_name FROM document") { it.str("file_name") }

    // ------------------------------------------------------------------ reminders

    private fun mapReminder(r: Row) = Reminder(
        r.long("id"), TargetType.valueOf(r.str("target_type")), r.long("target_id"), ReminderMode.valueOf(r.str("mode")),
        r.intOrNull("offset_minutes"), r.longOrNull("absolute_at"), r.bool("muted"), r.longOrNull("snoozed_until"), r.longOrNull("fired_for")
    )

    fun reminderFor(type: TargetType, targetId: Long): Reminder? =
        db.first("SELECT * FROM reminder WHERE target_type=? AND target_id=?", type.name, targetId, mapper = ::mapReminder)

    fun reminder(id: Long): Reminder? = db.first("SELECT * FROM reminder WHERE id=?", id, mapper = ::mapReminder)

    fun reminders(): List<Reminder> = db.list("SELECT * FROM reminder ORDER BY id", mapper = ::mapReminder)

    /** Sets (or with null removes) the reminder of a target. Changing the spec un-mutes and clears snooze. */
    fun setReminder(type: TargetType, targetId: Long, spec: ReminderSpec?) = mutate {
        if (spec == null) { deleteReminderFor(type, targetId); return@mutate }
        when (spec.mode) {
            ReminderMode.OFFSET -> if (spec.offsetMinutes == null || spec.offsetMinutes < 0) throw ValidationException("זמן התזכורת אינו תקין")
            ReminderMode.ABSOLUTE -> if (spec.absoluteAt == null) throw ValidationException("יש לבחור מועד לתזכורת")
        }
        if (!targetExists(type, targetId)) throw ValidationException("הפריט לתזכורת אינו קיים")
        val existing = reminderFor(type, targetId)
        val t = now()
        val offset = if (spec.mode == ReminderMode.OFFSET) spec.offsetMinutes else null
        val abs = if (spec.mode == ReminderMode.ABSOLUTE) spec.absoluteAt else null
        if (existing == null) {
            db.insert(
                "INSERT INTO reminder (target_type, target_id, mode, offset_minutes, absolute_at, muted, created_at, updated_at) VALUES (?,?,?,?,?,0,?,?)",
                type.name, targetId, spec.mode.name, offset, abs, t, t
            )
        } else {
            val same = existing.mode == spec.mode && existing.offsetMinutes == offset && existing.absoluteAt == abs
            db.update(
                "UPDATE reminder SET mode=?, offset_minutes=?, absolute_at=?, muted=?, snoozed_until=?, updated_at=? WHERE id=?",
                spec.mode.name, offset, abs, if (same) existing.muted else false, if (same) existing.snoozedUntil else null, t, existing.id
            )
        }
    }

    fun setReminderMuted(reminderId: Long, muted: Boolean) = mutate {
        db.update("UPDATE reminder SET muted=?, updated_at=? WHERE id=?", muted, now(), reminderId)
    }

    fun snoozeReminder(reminderId: Long, until: Long) = mutate {
        db.update("UPDATE reminder SET snoozed_until=?, muted=0, updated_at=? WHERE id=?", until, now(), reminderId)
    }

    /** Records that a notification was shown for [triggerAt], so the same trigger never notifies twice. */
    fun markReminderFired(reminderId: Long, triggerAt: Long) = mutate {
        db.update("UPDATE reminder SET fired_for=? WHERE id=?", triggerAt, reminderId)
    }

    private fun clearSnooze(type: TargetType, id: Long) {
        db.update("UPDATE reminder SET snoozed_until=NULL WHERE target_type=? AND target_id=?", type.name, id)
    }

    private fun deleteReminderFor(type: TargetType, id: Long) {
        db.update("DELETE FROM reminder WHERE target_type=? AND target_id=?", type.name, id)
    }

    private fun targetExists(type: TargetType, id: Long) = when (type) {
        TargetType.TASK -> exists("task", id)
        TargetType.EVENT -> exists("event", id)
        TargetType.TRANSPORT -> exists("transport", id)
        TargetType.EXPENSE_PAYMENT -> exists("expense", id)
    }

    /** Facts about a reminder's target, used for scheduling and notification text. */
    data class TargetInfo(
        val type: TargetType, val id: Long, val title: String, val body: String,
        val at: LocalDateTime?, val active: Boolean, val inactiveReason: String?, val phone: String?
    )

    fun targetInfo(type: TargetType, id: Long): TargetInfo? {
        val defTime = defaultReminderTime()
        fun dt(date: String?, time: String?): LocalDateTime? {
            val d = Dates.parseDate(date) ?: return null
            return LocalDateTime.of(d, Dates.parseTime(time) ?: defTime)
        }
        return when (type) {
            TargetType.TASK -> task(id)?.let { tv ->
                val t = tv.task
                val body = listOfNotNull(
                    if (t.dueDate != null) "יעד: ${Dates.display(t.dueDate)}${t.dueTime?.let { " " + Dates.displayTime(it) } ?: ""}" else null,
                    tv.supplierName?.let { "ספק: $it" }
                ).joinToString(" · ")
                TargetInfo(type, id, t.title, body, dt(t.dueDate, t.dueTime), t.status.isActive,
                    if (!t.status.isActive) "המשימה ${t.status.label}" else null, null)
            }
            TargetType.EVENT -> event(id)?.let { ev ->
                val e = ev.event
                val body = listOfNotNull(
                    "${Dates.display(e.date)}${e.time?.let { " " + Dates.displayTime(it) } ?: ""}", e.location, ev.supplierName?.let { "ספק: $it" }
                ).joinToString(" · ")
                TargetInfo(type, id, e.title, body, dt(e.date, e.time), e.status == EventStatus.ACTIVE,
                    if (e.status != EventStatus.ACTIVE) "האירוע ${e.status.label}" else null,
                    ev.event.supplierId?.let { supplier(it)?.phone })
            }
            TargetType.TRANSPORT -> transport(id)?.let { tr ->
                val body = listOfNotNull(
                    "יציאה ${Dates.display(tr.date)}${tr.departTime?.let { " " + Dates.displayTime(it) } ?: ""}",
                    tr.departPlace?.let { "מ: $it" }, tr.destination?.let { "אל: $it" }, tr.driverName?.let { "נהג: $it" }
                ).joinToString(" · ")
                TargetInfo(type, id, "הסעה: ${tr.displayTitle}", body, dt(tr.date, tr.departTime), true, null, tr.driverPhone)
            }
            TargetType.EXPENSE_PAYMENT -> expense(id)?.let { e ->
                val amount = e.expense.nextPaymentAgorot ?: maxOf(e.remainingAgorot, 0)
                val body = "תשלום מתוכנן ${Dates.display(e.expense.nextPaymentDate)} · ${Money.format(amount)} · יתרה ${Money.format(maxOf(e.remainingAgorot, 0))}"
                val active = !e.cancelled && e.remainingAgorot > 0
                TargetInfo(type, id, "תשלום: ${e.expense.name}", body, dt(e.expense.nextPaymentDate, null), active,
                    when { e.cancelled -> "ההתחייבות בוטלה"; e.remainingAgorot <= 0 -> "שולם במלואו"; else -> null },
                    e.supplierPhone ?: e.expense.phone)
            }
        }
    }

    /** The moment a reminder should fire, ignoring mute/fired state; null when it cannot be computed. */
    fun baseTrigger(r: Reminder, info: TargetInfo?): Long? = when (r.mode) {
        ReminderMode.ABSOLUTE -> r.absoluteAt
        ReminderMode.OFFSET -> info?.at?.let { Dates.toMillis(it.toLocalDate(), it.toLocalTime(), clock.zone) - (r.offsetMinutes ?: 0) * 60_000L }
    }

    enum class ReminderState(val label: String) {
        PLANNED("מתוזמנת"), MUTED("מושתקת"), NO_DATE("אין מועד לפריט"), PASSED("המועד עבר"),
        FIRED("ההתראה הוצגה"), INACTIVE("הפריט אינו פעיל"), DISABLED("התזכורות כבויות בהגדרות")
    }

    data class ReminderStatus(val reminder: Reminder, val info: TargetInfo?, val triggerAt: Long?, val state: ReminderState, val reason: String?)

    /** How far in the past a missed trigger still fires (e.g. phone was off at the time). */
    val missedGraceMillis = 6 * 60 * 60 * 1000L

    fun reminderStatus(r: Reminder, nowMs: Long = now(), enabled: Boolean = remindersEnabled()): ReminderStatus {
        val info = targetInfo(r.targetType, r.targetId)
        val base = baseTrigger(r, info)
        val trigger = r.snoozedUntil ?: base
        val state = when {
            info == null -> ReminderState.INACTIVE
            !enabled -> ReminderState.DISABLED
            !info.active -> ReminderState.INACTIVE
            r.muted -> ReminderState.MUTED
            trigger == null -> ReminderState.NO_DATE
            r.firedFor == trigger -> ReminderState.FIRED
            trigger < nowMs - missedGraceMillis -> ReminderState.PASSED
            else -> ReminderState.PLANNED
        }
        val reason = when (state) {
            ReminderState.INACTIVE -> info?.inactiveReason ?: "הפריט נמחק"
            else -> null
        }
        return ReminderStatus(r, info, trigger, state, reason)
    }

    fun remindersEnabled(): Boolean = getSetting(KEY_REMINDERS_ENABLED) != "0"

    fun reminderStatuses(enabled: Boolean = remindersEnabled()): List<ReminderStatus> {
        val n = now()
        return reminders().map { reminderStatus(it, n, enabled) }
    }

    /** Alarms that should be armed right now. A missed trigger within the grace window is fired as soon as possible. */
    fun plannedAlarms(nowMs: Long = now()): List<PlannedAlarm> {
        if (!remindersEnabled()) return emptyList()
        return plannedAlarmsIgnoringSwitch(nowMs)
    }

    private fun plannedAlarmsIgnoringSwitch(nowMs: Long): List<PlannedAlarm> = reminders().mapNotNull { r ->
        val st = reminderStatus(r, nowMs)
        if (st.state != ReminderState.PLANNED) null
        else PlannedAlarm(r.id, maxOf(st.triggerAt!!, nowMs + 2_000), r.targetType, r.targetId, st.info!!.title)
    }

    /** Removes reminders whose targets no longer exist (safety net; deletes normally remove them). */
    fun cleanupOrphanReminders() = mutate {
        reminders().filter { !targetExists(it.targetType, it.targetId) }.forEach { db.update("DELETE FROM reminder WHERE id=?", it.id) }
    }

    // ------------------------------------------------------------------ finance aggregates

    fun financeTotals(list: List<ExpenseSummary> = expenses()): FinanceTotals {
        val active = list.filter { !it.cancelled }
        return FinanceTotals(
            budgetAgorot = wedding().budgetAgorot,
            committedAgorot = list.sumOf { it.effectiveCommitmentAgorot },
            committedSuppliersAgorot = list.filter { it.expense.supplierId != null }.sumOf { it.effectiveCommitmentAgorot },
            committedOtherAgorot = list.filter { it.expense.supplierId == null }.sumOf { it.effectiveCommitmentAgorot },
            paidAgorot = list.sumOf { it.paidAgorot },
            remainingAgorot = active.sumOf { maxOf(it.remainingAgorot, 0) },
            creditAgorot = active.sumOf { maxOf(-it.remainingAgorot, 0) },
            activeExpenseCount = active.size,
            openCommitmentsCount = active.count { it.remainingAgorot > 0 }
        )
    }

    fun categoryTotals(list: List<ExpenseSummary> = expenses()): List<CategoryTotals> {
        val cats = categories(true)
        val byCat = list.groupBy { it.expense.categoryId }
        val out = mutableListOf<CategoryTotals>()
        cats.forEach { c ->
            val l = byCat[c.id] ?: emptyList()
            if (c.archived && l.isEmpty()) return@forEach
            out += CategoryTotals(c.id, c.name, c.budgetAgorot, l.sumOf { it.effectiveCommitmentAgorot }, l.sumOf { it.paidAgorot },
                l.filter { !it.cancelled }.sumOf { maxOf(it.remainingAgorot, 0) }, l.size)
        }
        byCat[null]?.let { l ->
            out += CategoryTotals(null, "ללא קטגוריה", null, l.sumOf { it.effectiveCommitmentAgorot }, l.sumOf { it.paidAgorot },
                l.filter { !it.cancelled }.sumOf { maxOf(it.remainingAgorot, 0) }, l.size)
        }
        return out
    }

    /** Planned payments of open commitments, overdue first. */
    fun upcomingPayments(list: List<ExpenseSummary> = expenses(), withinDays: Long = 30): List<UpcomingPayment> {
        val today = today()
        val limit = today.plusDays(withinDays)
        return list.filter { !it.cancelled && it.remainingAgorot > 0 && it.expense.nextPaymentDate != null }
            .mapNotNull { e ->
                val d = Dates.parseDate(e.expense.nextPaymentDate) ?: return@mapNotNull null
                if (d.isAfter(limit)) return@mapNotNull null
                val amount = minOf(e.expense.nextPaymentAgorot ?: e.remainingAgorot, e.remainingAgorot)
                UpcomingPayment(e, e.expense.nextPaymentDate!!, amount, d.isBefore(today))
            }.sortedBy { it.date }
    }

    // ------------------------------------------------------------------ calendar

    fun calendarItems(from: LocalDate, to: LocalDate): List<CalendarItem> {
        val f = Dates.iso(from)
        val t = Dates.iso(to)
        val out = mutableListOf<CalendarItem>()
        wedding().date?.let { wd -> if (wd in f..t) out += CalendarItem(CalendarItemType.WEDDING, 0, wd, null, "יום החתונה", wedding().venue, false) }
        events().filter { it.event.date in f..t }.forEach { ev ->
            out += CalendarItem(CalendarItemType.EVENT, ev.event.id, ev.event.date, ev.event.time, ev.event.title,
                listOfNotNull(ev.event.location, ev.supplierName).joinToString(" · ").ifEmpty { null },
                ev.event.status == EventStatus.DONE, ev.event.status == EventStatus.CANCELLED)
        }
        tasks().filter { it.task.dueDate != null && it.task.dueDate in f..t }.forEach { tv ->
            out += CalendarItem(CalendarItemType.TASK, tv.task.id, tv.task.dueDate!!, tv.task.dueTime, tv.task.title, tv.supplierName,
                tv.task.status == TaskStatus.DONE, tv.task.status == TaskStatus.CANCELLED)
        }
        transports().filter { it.date in f..t }.forEach { tr ->
            out += CalendarItem(CalendarItemType.TRANSPORT, tr.id, tr.date, tr.departTime, "הסעה: ${tr.displayTitle}",
                tr.driverName?.let { "נהג: $it" }, false)
        }
        expenses().filter { !it.cancelled }.forEach { e ->
            val x = e.expense
            if (x.keyDate != null && x.keyDate in f..t) {
                out += CalendarItem(CalendarItemType.KEY_DATE, x.id, x.keyDate, x.keyTime, x.keyDateLabel ?: x.name,
                    listOfNotNull(if (x.keyDateLabel != null) x.name else null, e.supplierName).joinToString(" · ").ifEmpty { null }, false)
            }
            if (x.nextPaymentDate != null && x.nextPaymentDate in f..t && e.remainingAgorot > 0) {
                out += CalendarItem(CalendarItemType.PAYMENT_DUE, x.id, x.nextPaymentDate, null, "תשלום: ${x.name}",
                    Money.format(minOf(x.nextPaymentAgorot ?: e.remainingAgorot, e.remainingAgorot)), false)
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.time ?: "99:99" }))
    }

    // ------------------------------------------------------------------ dashboard

    fun dashboard(): Dashboard {
        val list = expenses()
        val tasks = tasks()
        val today = today()
        return Dashboard(
            wedding = wedding(),
            totals = financeTotals(list),
            upcomingPayments = upcomingPayments(list, 30),
            buckets = TaskLogic.bucketize(tasks, nowDateTime()),
            agenda = calendarItems(today, today.plusDays(7)).filter {
                it.type != CalendarItemType.TASK && it.type != CalendarItemType.PAYMENT_DUE && !it.done && !it.cancelled
            },
            supplierCount = db.longValue("SELECT COUNT(*) FROM supplier WHERE wedding_id=?", weddingId).toInt(),
            expenseCount = list.size,
            taskCount = tasks.size,
            openTaskCount = tasks.count { it.task.status.isActive }
        )
    }

    /** Erases all data and recreates an empty wedding with default categories (explicit user action only). */
    fun wipeAll() = mutate {
        for (t in il.hamechutan.app.core.db.Schema.TABLES.reversed()) db.exec("DELETE FROM $t")
        il.hamechutan.app.core.db.Schema.seed(db, now())
        invalidateCaches()
    }

    // ------------------------------------------------------------------ search

    fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        fun cond(vararg cols: String) = cols.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" }
        fun args(n: Int) = Array<Any?>(n) { like }
        val w = weddingId
        val out = mutableListOf<SearchHit>()
        db.rows("SELECT id, name, service, phone FROM supplier WHERE wedding_id=$w AND (${cond("name", "service", "phone", "contact_name", "notes", "arrival_info")})", *args(6))
            .forEach { out += SearchHit("SUPPLIER", it.long("id"), it.str("name"), listOfNotNull(it.strOrNull("service"), it.strOrNull("phone")).joinToString(" · ").ifEmpty { null }) }
        db.rows("SELECT e.id, e.name, s.name AS sname FROM expense e LEFT JOIN supplier s ON s.id=e.supplier_id WHERE e.wedding_id=$w AND (${cond("e.name", "e.notes", "e.key_date_label", "e.phone")})", *args(4))
            .forEach { out += SearchHit("EXPENSE", it.long("id"), it.str("name"), it.strOrNull("sname")) }
        db.rows("SELECT p.id, p.amount_agorot, p.paid_date, e.name FROM payment p JOIN expense e ON e.id=p.expense_id WHERE e.wedding_id=$w AND (${cond("p.note", "p.method_note")})", *args(2))
            .forEach { out += SearchHit("PAYMENT", it.long("id"), "תשלום ${Money.format(it.long("amount_agorot"))} · ${it.str("name")}", Dates.display(it.str("paid_date"))) }
        db.rows("SELECT id, title, due_date, status FROM task WHERE wedding_id=$w AND (${cond("title", "description", "notes")})", *args(3))
            .forEach { out += SearchHit("TASK", it.long("id"), it.str("title"), listOfNotNull(TaskStatus.valueOf(it.str("status")).label, it.strOrNull("due_date")?.let { d -> Dates.display(d) }).joinToString(" · ")) }
        db.rows("SELECT id, title, event_date FROM event WHERE wedding_id=$w AND (${cond("title", "location", "notes")})", *args(3))
            .forEach { out += SearchHit("EVENT", it.long("id"), it.str("title"), Dates.display(it.str("event_date"))) }
        db.rows("SELECT * FROM transport WHERE wedding_id=$w AND (${cond("title", "depart_place", "destination", "driver_name", "driver_phone", "notes")})", *args(6))
            .forEach { val tr = mapTransport(it); out += SearchHit("TRANSPORT", tr.id, tr.displayTitle, Dates.display(tr.date)) }
        db.rows("SELECT id, title, doc_type FROM document WHERE wedding_id=$w AND (${cond("title", "notes", "original_name")})", *args(3))
            .forEach { out += SearchHit("DOCUMENT", it.long("id"), it.str("title"), DocType.of(it.strOrNull("doc_type")).label) }
        return out
    }

    companion object {
        const val KEY_DEFAULT_TIME = "default_reminder_time"
        const val KEY_THEME = "theme"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_HEBREW_DATES = "hebrew_dates"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"
    }
}
