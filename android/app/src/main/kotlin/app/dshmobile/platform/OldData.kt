package app.dshmobile.platform

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import app.dshmobile.data.DbKey
import app.dshmobile.secure.DataLockedException
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Who said a line of the 0.9 conversation. */
enum class OldWho { OWNER, COMPANY, SYSTEM }

/** One line of the 0.9 conversation: [id] is the old row id (a text), [at] its time. */
data class OldLine(val id: String, val at: Instant, val who: OldWho, val text: String)

/** A read-only handle on the old database (a seam: SQLCipher on the phone, plain SQLite in the tests). */
interface OldDb {
    fun query(sql: String, args: Array<String>): Cursor
    fun close()
}

/**
 * 舊版資料: the 0.9 conversation, read-only, from the 0.9 database file `noBackupFilesDir/agency/agency.db` with its own key
 * scheme (a random 32-byte secret named `db` in `agency_secrets.json`, wrapped by the Keystore key `agency-db`, handed to
 * SQLCipher as a raw key). Nothing here ever writes to that file or changes its version; the new database is another file with
 * another key. When the old file is absent everything reports "none" ([exists] false, [count] 0, [page] empty).
 *
 * The 0.9 `message` table (Room): `id TEXT, at INTEGER (epoch ms), who TEXT (OWNER, COMPANY, SYSTEM), role, kind, mode,
 * text TEXT, refs, worded_by, legacy, unsent`. The SQL is written by hand against exactly those names.
 *
 * Throws [DataLockedException] when the file exists but its key is lost or does not open it.
 */
class OldData internal constructor(
    private val root: File,
    private val open: (File) -> OldDb,
) {
    private val dir get() = File(root, OLD_DIR)
    private val file get() = File(dir, OLD_DB)
    private var db: OldDb? = null

    /** Whether the 0.9 database file is there. */
    fun exists(): Boolean = file.isFile

    private fun handle(): OldDb = synchronized(this) { db ?: open(file).also { db = it } }

    fun count(): Int {
        if (!exists()) return 0
        return synchronized(this) {
            handle().query("SELECT COUNT(*) FROM message", emptyArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }

    /**
     * The [limit] lines just older than [before] (the newest ones when null), returned oldest first. Page backwards by passing
     * the first line of the previous page; an empty list means the start of the conversation.
     */
    fun page(limit: Int, before: OldLine? = null): List<OldLine> {
        if (limit <= 0 || !exists()) return emptyList()
        val sql = "SELECT id, at, who, text FROM message " +
            (if (before != null) "WHERE (at < CAST(? AS INTEGER) OR (at = CAST(? AS INTEGER) AND id < ?)) " else "") +
            "ORDER BY at DESC, id DESC LIMIT ?"
        val args = (if (before != null) arrayOf(before.at.toEpochMilli().toString(), before.at.toEpochMilli().toString(), before.id) else emptyArray()) +
            limit.toString()
        val lines = synchronized(this) {
            handle().query(sql, args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            OldLine(
                                id = c.getString(0),
                                at = Instant.ofEpochMilli(c.getLong(1)),
                                who = OldWho.entries.firstOrNull { it.name == c.getString(2) } ?: OldWho.SYSTEM,
                                text = c.getString(3) ?: "",
                            ),
                        )
                    }
                }
            }
        }
        return lines.reversed()
    }

    fun close() = synchronized(this) {
        runCatching { db?.close() }
        db = null
    }

    /**
     * Deletes the old database (with its journal files), the old media folder and the old secrets file. Irreversible: the UI asks
     * twice first. True when nothing of it remains.
     */
    fun deleteAll(): Boolean {
        close()
        val a = !dir.exists() || dir.deleteRecursively()
        val b = listOf(OLD_SECRETS, "$OLD_SECRETS.new", "$OLD_SECRETS.bak").map { File(root, it) }.all { !it.exists() || it.delete() }
        return a && b
    }

    companion object {
        const val OLD_DIR = "agency"
        const val OLD_DB = "agency.db"
        const val OLD_MEDIA_DIR = "media"
        const val OLD_SECRETS = "agency_secrets.json"
        const val OLD_DB_ALIAS = "agency-db"

        fun forApp(context: Context): OldData {
            val root = context.noBackupFilesDir
            return OldData(root) { file ->
                System.loadLibrary("sqlcipher")
                val key = DbKey(SecretsFile(File(root, OLD_SECRETS)), KeystoreWrap(OLD_DB_ALIAS)).sqlcipherKey(dbExists = true)
                try {
                    SqlcipherOldDb(SQLiteDatabase.openDatabase(file.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY, null, null))
                } catch (e: SQLiteException) {
                    throw DataLockedException("the old database does not open", e)
                }
            }
        }
    }
}

private class SqlcipherOldDb(private val sqlite: SQLiteDatabase) : OldDb {
    override fun query(sql: String, args: Array<String>): Cursor = sqlite.rawQuery(sql, args)

    override fun close() = sqlite.close()
}
