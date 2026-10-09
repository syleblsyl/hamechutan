package il.hamechutan.app.core

import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.db.Row
import il.hamechutan.app.core.db.Schema
import il.hamechutan.app.core.db.SqlDb
import il.hamechutan.app.core.db.bindValue
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** SqlDb on a real SQLite engine (xerial sqlite-jdbc) – same SQL semantics as Android's SQLite. */
class JdbcSqlDb(url: String = "jdbc:sqlite::memory:") : SqlDb {
    val conn: Connection = DriverManager.getConnection(url)
    private var depth = 0

    init { conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") } }

    override val inTransaction: Boolean get() = depth > 0

    private fun prep(sql: String, args: Array<out Any?>): PreparedStatement {
        val ps = conn.prepareStatement(sql)
        args.forEachIndexed { i, a ->
            when (val v = bindValue(a)) {
                null -> ps.setNull(i + 1, Types.NULL)
                is Long -> ps.setLong(i + 1, v)
                is Double -> ps.setDouble(i + 1, v)
                is String -> ps.setString(i + 1, v)
                is ByteArray -> ps.setBytes(i + 1, v)
                else -> ps.setString(i + 1, v.toString())
            }
        }
        return ps
    }

    override fun exec(sql: String, vararg args: Any?) { prep(sql, args).use { it.execute() } }

    override fun insert(sql: String, vararg args: Any?): Long {
        prep(sql, args).use { it.executeUpdate() }
        conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); return rs.getLong(1) } }
    }

    override fun update(sql: String, vararg args: Any?): Int = prep(sql, args).use { it.executeUpdate() }

    override fun rows(sql: String, vararg args: Any?): List<Row> = prep(sql, args).use { ps ->
        ps.executeQuery().use { rs ->
            val md = rs.metaData
            val out = mutableListOf<Row>()
            while (rs.next()) {
                val m = LinkedHashMap<String, Any?>()
                for (i in 1..md.columnCount) {
                    m[md.getColumnLabel(i)] = when (val o = rs.getObject(i)) {
                        is Int -> o.toLong(); is Short -> o.toLong(); is Float -> o.toDouble(); else -> o
                    }
                }
                out += Row(m)
            }
            out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        try {
            val r = block()
            depth--
            if (depth == 0) { conn.commit(); conn.autoCommit = true }
            return r
        } catch (e: Throwable) {
            depth--
            if (depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw e
        }
    }
}

object TestDb {
    /** 2026-10-09 10:00 Israel time. */
    val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-09T07:00:00Z"), ZoneId.of("Asia/Jerusalem"))

    fun fresh(clock: Clock = CLOCK, url: String = "jdbc:sqlite::memory:"): Repo {
        val db = JdbcSqlDb(url)
        db.transaction { Schema.create(db); Schema.seed(db, clock.millis()) }
        db.exec("PRAGMA user_version = ${Schema.VERSION}")
        return Repo(db, clock)
    }
}

class MutableClock(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("Asia/Jerusalem")) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    override fun instant(): Instant = instant
}
