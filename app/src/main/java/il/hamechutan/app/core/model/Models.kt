package il.hamechutan.app.core.model

// ---------- Enums ----------

enum class ExpenseStatus(val label: String) {
    UNPAID("טרם שולם"), PARTIAL("שולם חלקית"), PAID("שולם במלואו"), CANCELLED("בוטל")
}

enum class SupplierStatus(val label: String) {
    NO_COMMITMENT("ללא התחייבות"), COMMITTED("התחייבות פתוחה"), PARTIAL("שולם חלקית"),
    PAID("שולם במלואו"), CANCELLED("ההתקשרות בוטלה")
}

enum class TaskStatus(val label: String) {
    OPEN("פתוחה"), IN_PROGRESS("בתהליך"), DONE("הושלמה"), CANCELLED("בוטלה");
    val isActive get() = this == OPEN || this == IN_PROGRESS
}

enum class Priority(val value: Int, val label: String) {
    NORMAL(1, "רגילה"), HIGH(2, "גבוהה"), URGENT(3, "דחופה");
    companion object { fun of(v: Int) = values().firstOrNull { it.value == v } ?: NORMAL }
}

enum class EventStatus(val label: String) { ACTIVE("פעיל"), DONE("הושלם"), CANCELLED("בוטל") }

enum class PaymentKind(val label: String) { PAYMENT("תשלום"), REFUND("החזר") }

enum class DocType(val label: String) {
    RECEIPT("קבלה"), INVOICE("חשבונית"), CONTRACT("חוזה / הסכם"), QUOTE("הצעת מחיר"),
    PHOTO("תמונה"), OTHER("אחר");
    companion object { fun of(s: String?) = values().firstOrNull { it.name == s } ?: OTHER }
}

enum class TargetType(val label: String) {
    TASK("משימה"), EVENT("אירוע"), TRANSPORT("הסעה"), EXPENSE_PAYMENT("תשלום עתידי")
}

enum class ReminderMode { OFFSET, ABSOLUTE }

enum class TaskBucket(val label: String) {
    OVERDUE("באיחור"), URGENT("דחוף"), TODAY("היום"), WEEK("השבוע"), LATER("בהמשך")
}

// ---------- Records ----------

data class Wedding(
    val id: Long, val title: String, val date: String?, val venue: String?,
    val budgetAgorot: Long?, val notes: String?
)

data class Category(val id: Long, val name: String, val budgetAgorot: Long?, val sortOrder: Int, val archived: Boolean)

data class PaymentMethod(val id: Long, val name: String, val code: String?, val sortOrder: Int, val archived: Boolean) {
    val isBuiltIn get() = code != null
    val isOther get() = code == "OTHER"
}

data class Supplier(
    val id: Long, val name: String, val service: String?, val categoryId: Long?, val phone: String?,
    val contactName: String?, val arrivalInfo: String?, val notes: String?, val cancelled: Boolean,
    val createdAt: Long, val updatedAt: Long
)

data class SupplierInput(
    val name: String, val service: String? = null, val categoryId: Long? = null, val phone: String? = null,
    val contactName: String? = null, val arrivalInfo: String? = null, val notes: String? = null,
    /** When creating a supplier, optionally create its commitment in the same transaction. */
    val initialAgreedAgorot: Long? = null
)

data class SupplierSummary(
    val supplier: Supplier,
    val categoryName: String?,
    val status: SupplierStatus,
    val committedAgorot: Long,
    val paidAgorot: Long,
    val remainingAgorot: Long,
    val activeExpenseCount: Int,
    val expenseCount: Int
)

data class Expense(
    val id: Long, val name: String, val categoryId: Long?, val supplierId: Long?, val agreedAgorot: Long,
    val nextPaymentDate: String?, val nextPaymentAgorot: Long?, val keyDate: String?, val keyTime: String?,
    val keyDateLabel: String?, val phone: String?, val notes: String?, val cancelled: Boolean,
    val cancelledAt: Long?, val cancelNote: String?, val createdAt: Long, val updatedAt: Long
)

data class ExpenseInput(
    val name: String, val categoryId: Long?, val supplierId: Long?, val agreedAgorot: Long,
    val nextPaymentDate: String? = null, val nextPaymentAgorot: Long? = null,
    val keyDate: String? = null, val keyTime: String? = null, val keyDateLabel: String? = null,
    val phone: String? = null, val notes: String? = null
)

/** Expense with amounts derived from its payment records (single source of truth). */
data class ExpenseSummary(
    val expense: Expense,
    val categoryName: String?,
    val supplierName: String?,
    val supplierPhone: String?,
    val paidAgorot: Long,          // net: payments minus refunds
    val lastPaymentDate: String?,
    val paymentCount: Int
) {
    val id get() = expense.id
    val cancelled get() = expense.cancelled
    /** Remaining to pay. Zero for cancelled expenses; negative means overpaid (credit with supplier). */
    val remainingAgorot: Long get() = if (expense.cancelled) 0 else expense.agreedAgorot - paidAgorot
    /** What this expense actually costs: the agreed price, or for a cancelled one, what was paid and not refunded. */
    val effectiveCommitmentAgorot: Long get() = if (expense.cancelled) maxOf(paidAgorot, 0) else expense.agreedAgorot
    val status: ExpenseStatus get() = when {
        expense.cancelled -> ExpenseStatus.CANCELLED
        paidAgorot <= 0 -> ExpenseStatus.UNPAID
        paidAgorot < expense.agreedAgorot -> ExpenseStatus.PARTIAL
        else -> ExpenseStatus.PAID
    }
    val isOverpaid get() = !expense.cancelled && paidAgorot > expense.agreedAgorot
}

data class Payment(
    val id: Long, val expenseId: Long, val amountAgorot: Long, val kind: PaymentKind, val date: String,
    val methodId: Long?, val methodNote: String?, val note: String?, val createdAt: Long, val updatedAt: Long
) {
    val signedAgorot get() = if (kind == PaymentKind.REFUND) -amountAgorot else amountAgorot
}

data class PaymentView(
    val payment: Payment, val expenseName: String, val supplierId: Long?, val supplierName: String?,
    val methodName: String?
) {
    /** "כרטיס אשראי" or "אחר: צ׳ק בנקאי" */
    val methodLabel: String get() {
        val n = payment.methodNote?.takeIf { it.isNotBlank() }
        return when {
            methodName == null && n == null -> "לא צוין"
            methodName == null -> n!!
            n == null -> methodName
            else -> "$methodName: $n"
        }
    }
}

data class PaymentInput(
    val expenseId: Long, val amountAgorot: Long, val date: String, val methodId: Long?,
    val methodNote: String? = null, val note: String? = null, val kind: PaymentKind = PaymentKind.PAYMENT
)

enum class OverpayPolicy { REJECT, ALLOW_CREDIT, RAISE_AGREED }

sealed class PaymentCheck {
    object Ok : PaymentCheck()
    data class Error(val message: String) : PaymentCheck()
    /** Payment exceeds the remaining balance; user must confirm how to handle it. */
    data class Overpay(val remainingAgorot: Long, val excessAgorot: Long, val newPaidAgorot: Long) : PaymentCheck()
}

data class PaymentResult(
    val paymentId: Long,
    val remainingAfter: Long,
    /** Open tasks linked to this expense; when fully paid the UI offers (does not force) completing them. */
    val relatedOpenTasks: List<TaskView>
)

data class Task(
    val id: Long, val title: String, val description: String?, val dueDate: String?, val dueTime: String?,
    val priority: Priority, val status: TaskStatus, val supplierId: Long?, val expenseId: Long?, val paymentId: Long?,
    val notes: String?, val completedAt: Long?, val createdAt: Long, val updatedAt: Long
)

data class TaskInput(
    val title: String, val description: String? = null, val dueDate: String? = null, val dueTime: String? = null,
    val priority: Priority = Priority.NORMAL, val status: TaskStatus = TaskStatus.OPEN,
    val supplierId: Long? = null, val expenseId: Long? = null, val paymentId: Long? = null, val notes: String? = null
)

data class TaskView(val task: Task, val supplierName: String?, val expenseName: String?) {
    val id get() = task.id
}

data class EventRec(
    val id: Long, val title: String, val date: String, val time: String?, val location: String?,
    val supplierId: Long?, val expenseId: Long?, val notes: String?, val status: EventStatus,
    val createdAt: Long, val updatedAt: Long
)

data class EventInput(
    val title: String, val date: String, val time: String? = null, val location: String? = null,
    val supplierId: Long? = null, val expenseId: Long? = null, val notes: String? = null,
    val status: EventStatus = EventStatus.ACTIVE
)

data class EventView(val event: EventRec, val supplierName: String?, val expenseName: String?)

data class Transport(
    val id: Long, val title: String?, val date: String, val departTime: String?, val departPlace: String?,
    val destination: String?, val driverName: String?, val driverPhone: String?, val supplierId: Long?,
    val notes: String?, val createdAt: Long, val updatedAt: Long
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(departPlace?.takeIf { it.isNotBlank() }, destination?.takeIf { it.isNotBlank() })
            .joinToString(" ← ").ifEmpty { "הסעה" }
}

data class TransportInput(
    val title: String? = null, val date: String, val departTime: String? = null, val departPlace: String? = null,
    val destination: String? = null, val driverName: String? = null, val driverPhone: String? = null,
    val supplierId: Long? = null, val notes: String? = null
)

data class DocumentRec(
    val id: Long, val title: String, val type: DocType, val fileName: String, val mimeType: String?,
    val sizeBytes: Long?, val originalName: String?, val docDate: String?, val supplierId: Long?,
    val expenseId: Long?, val paymentId: Long?, val taskId: Long?, val eventId: Long?, val transportId: Long?,
    val notes: String?, val createdAt: Long
) {
    val isImage get() = mimeType?.startsWith("image/") == true
    val isPdf get() = mimeType == "application/pdf"
}

data class DocumentInput(
    val title: String, val type: DocType, val fileName: String, val mimeType: String?, val sizeBytes: Long?,
    val originalName: String? = null, val docDate: String? = null, val supplierId: Long? = null,
    val expenseId: Long? = null, val paymentId: Long? = null, val taskId: Long? = null, val eventId: Long? = null,
    val transportId: Long? = null, val notes: String? = null
)

data class DocumentView(
    val doc: DocumentRec, val supplierName: String?, val expenseName: String?, val paymentLabel: String?,
    val taskTitle: String?, val eventTitle: String?, val transportTitle: String?
) {
    /** Short "linked to" description. */
    val linkLabel: String get() = listOfNotNull(
        supplierName?.let { "ספק: $it" }, expenseName?.let { "הוצאה: $it" }, paymentLabel?.let { "תשלום: $it" },
        taskTitle?.let { "משימה: $it" }, eventTitle?.let { "אירוע: $it" }, transportTitle?.let { "הסעה: $it" }
    ).joinToString(" · ")
}

data class Reminder(
    val id: Long, val targetType: TargetType, val targetId: Long, val mode: ReminderMode, val offsetMinutes: Int?,
    val absoluteAt: Long?, val muted: Boolean, val snoozedUntil: Long?, val firedFor: Long?
)

data class ReminderSpec(val mode: ReminderMode, val offsetMinutes: Int? = null, val absoluteAt: Long? = null) {
    companion object {
        val OFFSET_CHOICES = listOf(
            0 to "בזמן היעד", 15 to "רבע שעה לפני", 60 to "שעה לפני", 180 to "3 שעות לפני",
            1440 to "יום לפני", 2880 to "יומיים לפני", 10080 to "שבוע לפני"
        )
        fun offsetLabel(m: Int): String = OFFSET_CHOICES.firstOrNull { it.first == m }?.second ?: "$m דקות לפני"
    }
}

/** An alarm that should currently be armed. */
data class PlannedAlarm(val reminderId: Long, val triggerAt: Long, val targetType: TargetType, val targetId: Long, val title: String)

data class AuditEntry(val id: Long, val entityType: String, val entityId: Long, val action: String, val details: String?, val createdAt: Long)

// ---------- Aggregates ----------

data class FinanceTotals(
    val budgetAgorot: Long?,
    val committedAgorot: Long,
    val committedSuppliersAgorot: Long,
    val committedOtherAgorot: Long,
    val paidAgorot: Long,
    val remainingAgorot: Long,
    val creditAgorot: Long,
    val activeExpenseCount: Int,
    val openCommitmentsCount: Int
) {
    val overBudget get() = budgetAgorot != null && committedAgorot > budgetAgorot
    val budgetLeftAgorot get() = budgetAgorot?.let { it - committedAgorot }
}

data class CategoryTotals(
    val categoryId: Long?, val name: String, val budgetAgorot: Long?, val committedAgorot: Long,
    val paidAgorot: Long, val remainingAgorot: Long, val count: Int
)

data class UpcomingPayment(val expense: ExpenseSummary, val date: String, val amountAgorot: Long, val overdue: Boolean)

data class CalendarItemType(val key: String, val label: String) {
    companion object {
        val EVENT = CalendarItemType("EVENT", "אירוע")
        val TASK = CalendarItemType("TASK", "משימה")
        val TRANSPORT = CalendarItemType("TRANSPORT", "הסעה")
        val PAYMENT_DUE = CalendarItemType("PAYMENT_DUE", "תשלום מתוכנן")
        val KEY_DATE = CalendarItemType("KEY_DATE", "מועד הוצאה")
        val WEDDING = CalendarItemType("WEDDING", "החתונה")
    }
}

data class CalendarItem(
    val type: CalendarItemType, val id: Long, val date: String, val time: String?, val title: String,
    val subtitle: String?, val done: Boolean, val cancelled: Boolean = false
)

data class Dashboard(
    val wedding: Wedding,
    val totals: FinanceTotals,
    val upcomingPayments: List<UpcomingPayment>,
    val buckets: Map<TaskBucket, List<TaskView>>,
    val agenda: List<CalendarItem>,
    val supplierCount: Int,
    val expenseCount: Int,
    val taskCount: Int,
    val openTaskCount: Int
) {
    val isEmpty get() = supplierCount == 0 && expenseCount == 0 && taskCount == 0 && totals.budgetAgorot == null
}

data class SearchHit(val kind: String, val id: Long, val title: String, val subtitle: String?)

class ValidationException(message: String) : Exception(message)
