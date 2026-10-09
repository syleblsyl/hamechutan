package il.hamechutan.app.core.db

/**
 * Minimal SQL abstraction so that all business SQL lives in pure Kotlin and can be
 * tested on the JVM against a real SQLite engine (sqlite-jdbc), while the app uses
 * android.database.sqlite. Values: Long, Double, String, ByteArray or null.
 */
interface SqlDb {
    fun exec(sql: String, vararg args: Any?)
    fun insert(sql: String, vararg args: Any?): Long
    fun update(sql: String, vararg args: Any?): Int
    fun rows(sql: String, vararg args: Any?): List<Row>
    fun <T> transaction(block: () -> T): T
    val inTransaction: Boolean
}

class Row(val values: Map<String, Any?>) {
    private fun v(c: String): Any? {
        require(values.containsKey(c)) { "Missing column '$c' in ${values.keys}" }
        return values[c]
    }
    fun long(c: String): Long = (v(c) as? Number)?.toLong() ?: error("Column '$c' is null")
    fun longOrNull(c: String): Long? = (v(c) as? Number)?.toLong()
    fun int(c: String): Int = long(c).toInt()
    fun intOrNull(c: String): Int? = longOrNull(c)?.toInt()
    fun bool(c: String): Boolean = long(c) != 0L
    fun str(c: String): String = v(c)?.toString() ?: error("Column '$c' is null")
    fun strOrNull(c: String): String? = v(c)?.toString()
    fun has(c: String) = values.containsKey(c)
}

fun <T> SqlDb.list(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = rows(sql, *args).map(mapper)
fun <T> SqlDb.first(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = rows(sql, *args).firstOrNull()?.let(mapper)
fun SqlDb.longValue(sql: String, vararg args: Any?): Long =
    rows(sql, *args).firstOrNull()?.values?.values?.firstOrNull()?.let { (it as Number).toLong() } ?: 0L

/** Normalizes Kotlin values into SQL bind values. */
fun bindValue(a: Any?): Any? = when (a) {
    null -> null
    is Boolean -> if (a) 1L else 0L
    is Int -> a.toLong()
    is Long -> a
    is Short -> a.toLong()
    is Double -> a
    is Float -> a.toDouble()
    is String -> a
    is ByteArray -> a
    is Enum<*> -> a.name
    else -> a.toString()
}
