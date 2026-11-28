package app.dshmobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.dshmobile.secure.DataLockedException
import app.dshmobile.secure.TransientDataException
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the production database `xiaoyu.db` (SQLCipher, key wrapped by the Android Keystore). Thin on purpose: SQLCipher's
 * native library cannot load on the JVM, so this is exercised only on a device; the schema and the store above it
 * ([Schema], [SqlStore]) are tested through the framework helper. The 0.9 database file is never touched here.
 */
object Db {
    const val FILE_NAME = "xiaoyu.db"

    fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    /**
     * Opens (creating on first use) the encrypted database. Blocking: call it off the main thread. Nothing is ever deleted when
     * it fails. [DataLockedException]: the passphrase is lost or the file does not open with it (trying again does not help);
     * [TransientDataException]: a passing failure (disk full, busy), try again. An `UnsatisfiedLinkError` means SQLCipher's
     * library could not load.
     */
    fun open(context: Context, key: DbKey = DbKey.forApp(context), file: File = file(context)): SupportSQLiteDatabase {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        val existed = file.exists()
        return openWith(context, file, existed, SupportOpenHelperFactory(key.sqlcipherKey(dbExists = existed), null, true))
    }

    /** [open] after the key: opens through [factory] now, not at the first query, and maps SQLite failures to our two kinds. */
    internal fun openWith(context: Context, file: File, existed: Boolean, factory: SupportSQLiteOpenHelper.Factory): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
            .name(file.absolutePath)
            .callback(Schema.callback())
            .build()
        val helper = factory.create(config)
        try {
            return helper.writableDatabase
        } catch (e: SQLiteNotADatabaseException) {
            closeQuietly(helper)
            throw DataLockedException("the database key does not open the file", e)
        } catch (e: SQLiteDatabaseCorruptException) {
            closeQuietly(helper)
            if (!existed) throw TransientDataException("the database file could not be created", e)
            throw DataLockedException("the database file is damaged", e)
        } catch (e: SQLiteException) {
            closeQuietly(helper)
            if (!existed) throw TransientDataException("the database file could not be created", e)
            throw TransientDataException("the database file cannot be opened now", e)
        }
    }

    private fun closeQuietly(helper: SupportSQLiteOpenHelper) {
        try {
            helper.close()
        } catch (_: Exception) {
        }
    }
}
