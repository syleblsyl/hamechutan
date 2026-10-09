package il.hamechutan.app.platform

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import il.hamechutan.app.core.db.Row
import il.hamechutan.app.core.db.Schema
import il.hamechutan.app.core.db.SqlDb
import il.hamechutan.app.core.db.bindValue

/** SqlDb implementation over the platform SQLite. Values are bound with their real types. */
class AndroidSqlDb(private val db: SQLiteDatabase) : SqlDb {

    override val inTransaction: Boolean get() = db.inTransaction()

    private fun bindArgs(args: Array<out Any?>): Array<Any?> = Array(args.size) { i ->
        when (val v = bindValue(args[i])) {
            is Long, is Double, is String, is ByteArray, null -> v
            else -> v.toString()
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindArgs(args))
    }

    override fun insert(sql: String, vararg args: Any?): Long {
        db.compileStatement(sql).use { st ->
            bindAll(st, args)
            return st.executeInsert()
        }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        db.compileStatement(sql).use { st ->
            bindAll(st, args)
            return st.executeUpdateDelete()
        }
    }

    private fun bindAll(st: android.database.sqlite.SQLiteProgram, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (val v = bindValue(a)) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, v)
                is Double -> st.bindDouble(i + 1, v)
                is String -> st.bindString(i + 1, v)
                is ByteArray -> st.bindBlob(i + 1, v)
                else -> st.bindString(i + 1, v.toString())
            }
        }
    }

    override fun rows(sql: String, vararg args: Any?): List<Row> {
        // A cursor factory lets us bind typed values (rawQuery only binds strings).
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bindAll(query, args)
            SQLiteCursor(driver, editTable, query)
        }
        db.rawQueryWithFactory(factory, sql, null, null).use { c ->
            val out = ArrayList<Row>(c.count)
            val names = c.columnNames
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, Any?>(names.size)
                for (i in names.indices) {
                    m[names[i]] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                out += Row(m)
            }
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }
}

class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, Schema.VERSION) {
    companion object { const val NAME = "hamechutan.db" }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val s = AndroidSqlDb(db)
        Schema.create(s)
        Schema.seed(s, System.currentTimeMillis())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Schema.migrate(AndroidSqlDb(db), oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("מסד הנתונים נוצר בגרסה חדשה יותר של האפליקציה")
    }
}
