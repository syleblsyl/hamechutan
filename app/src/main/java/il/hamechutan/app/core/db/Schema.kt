package il.hamechutan.app.core.db

/**
 * Database schema. Money is stored as integer agorot. Dates are ISO text (yyyy-MM-dd),
 * times are text (HH:mm), timestamps are epoch millis.
 *
 * All records are scoped to a wedding (wedding_id) so that a future version can open a
 * new wedding while keeping the previous one intact.
 */
object Schema {
    const val VERSION = 1

    /** Tables in parent-before-child order (used by backup/restore). */
    val TABLES = listOf(
        "wedding", "category", "payment_method", "supplier", "expense", "payment",
        "task", "event", "transport", "document", "reminder", "audit_log", "app_setting"
    )

    private val CREATE = listOf(
        """CREATE TABLE wedding (
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            wedding_date TEXT,
            venue TEXT,
            budget_agorot INTEGER CHECK (budget_agorot IS NULL OR budget_agorot >= 0),
            notes TEXT,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL
        )""",
        """CREATE TABLE category (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            name TEXT NOT NULL CHECK (length(trim(name)) > 0),
            budget_agorot INTEGER CHECK (budget_agorot IS NULL OR budget_agorot >= 0),
            sort_order INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE payment_method (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL CHECK (length(trim(name)) > 0),
            code TEXT,
            sort_order INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE supplier (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            name TEXT NOT NULL CHECK (length(trim(name)) > 0),
            service TEXT,
            category_id INTEGER REFERENCES category(id) ON DELETE SET NULL,
            phone TEXT,
            contact_name TEXT,
            arrival_info TEXT,
            notes TEXT,
            cancelled INTEGER NOT NULL DEFAULT 0,
            cancelled_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE expense (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            name TEXT NOT NULL CHECK (length(trim(name)) > 0),
            category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
            supplier_id INTEGER REFERENCES supplier(id) ON DELETE RESTRICT,
            agreed_agorot INTEGER NOT NULL CHECK (agreed_agorot >= 0),
            next_payment_date TEXT,
            next_payment_agorot INTEGER CHECK (next_payment_agorot IS NULL OR next_payment_agorot > 0),
            key_date TEXT,
            key_time TEXT,
            key_date_label TEXT,
            phone TEXT,
            notes TEXT,
            cancelled INTEGER NOT NULL DEFAULT 0,
            cancelled_at INTEGER,
            cancel_note TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE payment (
            id INTEGER PRIMARY KEY,
            expense_id INTEGER NOT NULL REFERENCES expense(id) ON DELETE RESTRICT,
            amount_agorot INTEGER NOT NULL CHECK (amount_agorot > 0),
            kind TEXT NOT NULL DEFAULT 'PAYMENT' CHECK (kind IN ('PAYMENT','REFUND')),
            paid_date TEXT NOT NULL,
            method_id INTEGER REFERENCES payment_method(id) ON DELETE RESTRICT,
            method_note TEXT,
            note TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE task (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            title TEXT NOT NULL CHECK (length(trim(title)) > 0),
            description TEXT,
            due_date TEXT,
            due_time TEXT,
            priority INTEGER NOT NULL DEFAULT 1 CHECK (priority BETWEEN 1 AND 3),
            status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','DONE','CANCELLED')),
            supplier_id INTEGER REFERENCES supplier(id) ON DELETE SET NULL,
            expense_id INTEGER REFERENCES expense(id) ON DELETE SET NULL,
            payment_id INTEGER REFERENCES payment(id) ON DELETE SET NULL,
            notes TEXT,
            completed_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE event (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            title TEXT NOT NULL CHECK (length(trim(title)) > 0),
            event_date TEXT NOT NULL,
            event_time TEXT,
            location TEXT,
            supplier_id INTEGER REFERENCES supplier(id) ON DELETE SET NULL,
            expense_id INTEGER REFERENCES expense(id) ON DELETE SET NULL,
            notes TEXT,
            status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DONE','CANCELLED')),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE transport (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            title TEXT,
            transport_date TEXT NOT NULL,
            depart_time TEXT,
            depart_place TEXT,
            destination TEXT,
            driver_name TEXT,
            driver_phone TEXT,
            supplier_id INTEGER REFERENCES supplier(id) ON DELETE SET NULL,
            notes TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE document (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            title TEXT NOT NULL CHECK (length(trim(title)) > 0),
            doc_type TEXT NOT NULL DEFAULT 'OTHER',
            file_name TEXT NOT NULL UNIQUE,
            mime_type TEXT,
            size_bytes INTEGER,
            original_name TEXT,
            doc_date TEXT,
            supplier_id INTEGER REFERENCES supplier(id) ON DELETE SET NULL,
            expense_id INTEGER REFERENCES expense(id) ON DELETE SET NULL,
            payment_id INTEGER REFERENCES payment(id) ON DELETE SET NULL,
            task_id INTEGER REFERENCES task(id) ON DELETE SET NULL,
            event_id INTEGER REFERENCES event(id) ON DELETE SET NULL,
            transport_id INTEGER REFERENCES transport(id) ON DELETE SET NULL,
            notes TEXT,
            created_at INTEGER NOT NULL
        )""",
        """CREATE TABLE reminder (
            id INTEGER PRIMARY KEY,
            target_type TEXT NOT NULL CHECK (target_type IN ('TASK','EVENT','TRANSPORT','EXPENSE_PAYMENT')),
            target_id INTEGER NOT NULL,
            mode TEXT NOT NULL CHECK (mode IN ('OFFSET','ABSOLUTE')),
            offset_minutes INTEGER,
            absolute_at INTEGER,
            muted INTEGER NOT NULL DEFAULT 0,
            snoozed_until INTEGER,
            fired_for INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE (target_type, target_id)
        )""",
        """CREATE TABLE audit_log (
            id INTEGER PRIMARY KEY,
            wedding_id INTEGER NOT NULL REFERENCES wedding(id) ON DELETE CASCADE,
            entity_type TEXT NOT NULL,
            entity_id INTEGER NOT NULL,
            expense_id INTEGER,
            action TEXT NOT NULL,
            details TEXT,
            created_at INTEGER NOT NULL
        )""",
        """CREATE TABLE app_setting (
            key TEXT PRIMARY KEY,
            value TEXT
        )""",
        "CREATE INDEX idx_expense_supplier ON expense(supplier_id)",
        "CREATE INDEX idx_expense_category ON expense(category_id)",
        "CREATE INDEX idx_payment_expense ON payment(expense_id)",
        "CREATE INDEX idx_task_due ON task(due_date)",
        "CREATE INDEX idx_task_supplier ON task(supplier_id)",
        "CREATE INDEX idx_task_expense ON task(expense_id)",
        "CREATE INDEX idx_event_date ON event(event_date)",
        "CREATE INDEX idx_transport_date ON transport(transport_date)",
        "CREATE INDEX idx_document_supplier ON document(supplier_id)",
        "CREATE INDEX idx_document_expense ON document(expense_id)",
        "CREATE INDEX idx_document_payment ON document(payment_id)",
        "CREATE INDEX idx_audit_expense ON audit_log(expense_id)"
    )

    val DEFAULT_CATEGORIES = listOf(
        "אולם וקייטרינג", "מוזיקה ותזמורת", "צילום ווידאו", "בגדים", "שטריימל וכובעים",
        "מתנות", "הסעות", "עיצוב ופרחים", "הוצאות משפחתיות", "שונות"
    )

    /** Built-in payment methods: code to Hebrew name. */
    val DEFAULT_METHODS = listOf(
        "CASH" to "מזומן",
        "TRANSFER" to "העברה בנקאית",
        "CHECK" to "צ׳ק",
        "CREDIT" to "כרטיס אשראי",
        "BIT" to "ביט / אפליקציית תשלום",
        "OTHER" to "אחר"
    )

    fun create(db: SqlDb) {
        CREATE.forEach { db.exec(it.trimIndent()) }
    }

    fun seed(db: SqlDb, now: Long) {
        val weddingId = db.insert(
            "INSERT INTO wedding (title, is_active, created_at) VALUES (?, 1, ?)",
            "החתונה שלנו", now
        )
        DEFAULT_CATEGORIES.forEachIndexed { i, name ->
            db.insert("INSERT INTO category (wedding_id, name, sort_order) VALUES (?, ?, ?)", weddingId, name, i)
        }
        DEFAULT_METHODS.forEachIndexed { i, (code, name) ->
            db.insert("INSERT INTO payment_method (name, code, sort_order) VALUES (?, ?, ?)", name, code, i)
        }
    }

    /** Upgrade hook for future schema versions. Each step must be idempotent within its transaction. */
    fun migrate(db: SqlDb, from: Int, to: Int) {
        var v = from
        while (v < to) {
            when (v) {
                // 1 -> 2: add future migrations here.
                else -> {}
            }
            v++
        }
    }
}
