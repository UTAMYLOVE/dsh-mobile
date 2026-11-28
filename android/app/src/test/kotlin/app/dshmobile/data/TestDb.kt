package app.dshmobile.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** The production schema on plain framework SQLite (SQLCipher's native library cannot load on the JVM). */
object TestDb {
    /** A fresh in-memory database, or the file [name] under the app's databases folder. */
    fun open(context: Context, name: String? = null): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(Schema.callback()).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}
