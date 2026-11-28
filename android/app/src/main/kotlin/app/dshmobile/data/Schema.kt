package app.dshmobile.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * The three tables of [SqlStore] and the one place that creates and migrates them. Production ([Db]) and the Robolectric tests
 * (plain framework SQLite) open the same schema through [callback], so the tests exercise exactly what the phone creates.
 *
 * `entry.at` and `message.at` are epoch milliseconds; `entry.day` is the ISO date of the logical day (so a range of days is a
 * plain string comparison); `data`, `cards` and `meta` are JSON text.
 */
object Schema {
    /** The database `user_version`. Raise it with a branch in [upgrade]; a backup records it and refuses a newer one. */
    const val VERSION = 1

    const val ENTRY = "entry"
    const val DOC = "doc"
    const val MESSAGE = "message"

    /** Every table a backup carries. */
    val TABLES = listOf(ENTRY, DOC, MESSAGE)

    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS entry (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, at INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, data TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS entry_day ON entry (day)")
        db.execSQL("CREATE INDEX IF NOT EXISTS entry_kind_day ON entry (kind, day)")
        db.execSQL("CREATE TABLE IF NOT EXISTS doc (key TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS message (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, role TEXT NOT NULL, " +
                "text TEXT NOT NULL, cards TEXT NOT NULL, meta TEXT NOT NULL)",
        )
    }

    /**
     * The migration hook: called with the stored version [from] up to [to]. Version 1 is the first schema, so there is nothing
     * to do yet; the next change adds a `if (from < 2) { ... }` step here and raises [VERSION]. Never drop a user table.
     */
    @Suppress("UNUSED_PARAMETER")
    fun upgrade(db: SupportSQLiteDatabase, from: Int, to: Int) {
        // no migrations yet
    }

    /** Create on first open, migrate on a newer app, refuse (the default) a database from a newer app. */
    fun callback(): SupportSQLiteOpenHelper.Callback = object : SupportSQLiteOpenHelper.Callback(VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) = create(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion, newVersion)
    }
}
