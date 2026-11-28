package app.dshmobile.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.sqlite.db.SupportSQLiteDatabase
import app.agency.core.backup.BackupCipher
import app.agency.core.backup.BackupException
import app.dshmobile.R
import app.dshmobile.data.Schema
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.platform.media.MediaVaultException
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * What a backup holds, for the preview before a restore: when it was made, its schema version, the rows per table, how many
 * photos and recordings, and the first and last logical day of its entries (ISO dates, null when it has none).
 */
data class BackupSummary(
    val createdAt: Long,
    val schema: Int,
    val rows: Map<String, Int>,
    val media: Int,
    val firstDay: String?,
    val lastDay: String?,
)

/**
 * 加密備份: every row of the three tables and every file of the media vault, written into one zip that [BackupCipher] seals
 * with a passphrase he types (PBKDF2, AES-256-GCM, every segment authenticated). Inside: `manifest.json`,
 * `tables/<table>.jsonl` (a column line, then one JSON array per row) and `media/<id>.bin` (the plaintext of a vault file; the
 * backup's own encryption is what protects it).
 *
 * Never in it: the API keys (they live in the Keystore-wrapped vault; he types them again on a new phone) and the Keystore keys.
 * [restore] replaces every row inside one transaction (a bad file or a wrong passphrase rolls back and changes nothing) and then
 * the vault's files. Use [preview] first: it reads and checks the whole file and changes nothing.
 */
class Backup(
    private val db: () -> SupportSQLiteDatabase,
    private val vault: MediaVault,
    /** Called after a successful [restore], so cached values (the settings) are read again. */
    private val onRestored: () -> Unit = {},
) {
    companion object {
        const val FORMAT = "xiaoyu-backup"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val TABLES = "tables/"
        const val MEDIA = "media/"
        private val MEDIA_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** `xiaoyu-20301231.xybk`, the name offered to the file picker. */
        fun suggestedName(at: Instant): String =
            "xiaoyu-" + DateTimeFormatter.BASIC_ISO_DATE.format(at.atZone(ZoneOffset.UTC)) + ".xybk"

        /** SAF: where to write the backup (launch with [suggestedName]). */
        fun createContract(): ActivityResultContract<String, android.net.Uri?> = ActivityResultContracts.CreateDocument("application/octet-stream")

        /** SAF: which backup to read (launch with an array holding the any-type pattern, star slash star). */
        fun openContract(): ActivityResultContract<Array<String>, android.net.Uri?> = ActivityResultContracts.OpenDocument()

        /** The reason a backup could not be opened, in his language. */
        fun reasonText(context: Context, e: BackupException): String = context.getString(
            when (e.reason) {
                BackupException.Reason.WEAK_PASSWORD -> R.string.backup_weak_password
                BackupException.Reason.NOT_A_BACKUP -> R.string.backup_not_a_backup
                BackupException.Reason.NEWER_VERSION -> R.string.backup_newer_version
                BackupException.Reason.OLDER_VERSION -> R.string.backup_older_version
                BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED -> R.string.backup_wrong_password
                BackupException.Reason.TRUNCATED -> R.string.backup_truncated
            },
        )
    }

    private fun count(d: SupportSQLiteDatabase, table: String): Int =
        d.query("SELECT COUNT(*) FROM $table").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun dayRange(d: SupportSQLiteDatabase): Pair<String?, String?> =
        d.query("SELECT MIN(day), MAX(day) FROM entry").use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getString(1)) else null to null
        }

    /** Writes the backup of everything to [out] (closed afterwards) and returns what went in. */
    fun export(out: OutputStream, password: CharArray, at: Instant, iterations: Int = BackupCipher.ITERATIONS): BackupSummary {
        val d = db()
        val sealed = BackupCipher.encrypt(out, password, iterations) // a weak passphrase fails here, before anything is read
        var mediaCount = 0
        val rows = Schema.TABLES.associateWith { count(d, it) }
        val (first, last) = dayRange(d)
        ZipOutputStream(sealed).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(BackupSummary(at.toEpochMilli(), d.version, rows, 0, first, last)).toByteArray())
            zip.closeEntry()
            Schema.TABLES.forEach { t ->
                zip.putNextEntry(ZipEntry("$TABLES$t.jsonl"))
                d.query("SELECT * FROM $t").use { c ->
                    zip.write((JsonArray(c.columnNames.map { JsonPrimitive(it) }).toString() + "\n").toByteArray())
                    while (c.moveToNext()) zip.write((row(c).toString() + "\n").toByteArray())
                }
                zip.closeEntry()
            }
            vault.ids().forEach { id ->
                val input = try {
                    vault.open(id)
                } catch (_: MediaVaultException) {
                    return@forEach // a damaged file is left out; it must not stop the backup of everything else
                }
                input.use {
                    zip.putNextEntry(ZipEntry("$MEDIA$id.bin"))
                    it.copyTo(zip)
                    zip.closeEntry()
                    mediaCount++
                }
            }
        }
        return BackupSummary(at.toEpochMilli(), d.version, rows, mediaCount, first, last)
    }

    private fun manifest(s: BackupSummary): String = buildJsonObject {
        put("format", FORMAT)
        put("version", FORMAT_VERSION)
        put("schema", s.schema)
        put("created_at", s.createdAt)
        put("rows", buildJsonObject { s.rows.forEach { (t, n) -> put(t, n) } })
        s.firstDay?.let { put("first_day", it) }
        s.lastDay?.let { put("last_day", it) }
    }.toString()

    private fun row(c: Cursor) = buildJsonArray {
        for (i in 0 until c.columnCount) {
            add(
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> buildJsonObject { put("r", c.getDouble(i)) }
                    Cursor.FIELD_TYPE_BLOB -> buildJsonObject { put("b", Base64.getEncoder().encodeToString(c.getBlob(i))) }
                    else -> JsonPrimitive(c.getString(i))
                },
            )
        }
    }

    private fun readManifest(text: String, schema: Int): BackupSummary {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP, "no manifest")
        if (o["format"]?.jsonPrimitive?.content != FORMAT) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "not ours")
        if ((o["version"]?.jsonPrimitive?.int ?: 0) > FORMAT_VERSION) {
            throw BackupException(BackupException.Reason.NEWER_VERSION, "newer content")
        }
        val s = BackupSummary(
            createdAt = o["created_at"]?.jsonPrimitive?.long ?: 0,
            schema = o["schema"]?.jsonPrimitive?.int ?: 0,
            rows = (o["rows"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.int },
            media = 0,
            firstDay = o["first_day"]?.jsonPrimitive?.content,
            lastDay = o["last_day"]?.jsonPrimitive?.content,
        )
        if (s.schema > schema) throw BackupException(BackupException.Reason.NEWER_VERSION, "schema ${s.schema} is newer than $schema")
        return s
    }

    /** Reads the whole backup (every segment checked) and returns its summary; nothing is changed. */
    fun preview(input: InputStream, password: CharArray): BackupSummary = read(input, password, restore = false)

    /** Replaces every row and every vault file with the backup's. Call [preview] first. */
    fun restore(input: InputStream, password: CharArray): BackupSummary = read(input, password, restore = true).also { onRestored() }

    private class Keep(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun read(input: InputStream, password: CharArray, restore: Boolean): BackupSummary {
        val d = db()
        val plain = BackupCipher.decrypt(input, password)
        var summary: BackupSummary? = null
        var media = 0
        val kept = mutableSetOf<String>()
        if (restore) d.beginTransaction()
        try {
            ZipInputStream(plain).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        name == MANIFEST -> {
                            summary = readManifest(zip.readBytes().toString(Charsets.UTF_8), d.version)
                            if (restore) wipe(d)
                        }
                        summary == null -> throw BackupException(BackupException.Reason.NOT_A_BACKUP, "manifest first")
                        name.startsWith(TABLES) && name.endsWith(".jsonl") -> {
                            val table = name.removePrefix(TABLES).removeSuffix(".jsonl")
                            readTable(Keep(zip), d, table.takeIf { it in Schema.TABLES }, restore)
                        }
                        name.startsWith(MEDIA) && name.endsWith(".bin") -> {
                            val id = name.removePrefix(MEDIA).removeSuffix(".bin")
                            if (!MEDIA_ID.matches(id)) {
                                zip.readBytes()
                            } else {
                                if (restore) vault.putStream(id, Keep(zip)) else Keep(zip).readBytes()
                                kept += id
                                media++
                            }
                        }
                    }
                }
            }
            val s = summary ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP, "no manifest")
            if (restore) d.setTransactionSuccessful()
            val result = s.copy(media = media)
            if (restore) {
                d.endTransaction()
                // The vault now holds what the restored rows name.
                vault.ids().filter { it !in kept }.forEach { vault.delete(it) }
            }
            return result
        } catch (e: Throwable) {
            if (restore && d.inTransaction()) d.endTransaction() // not marked successful: rolled back
            throw e
        }
    }

    private fun wipe(d: SupportSQLiteDatabase) {
        Schema.TABLES.forEach { d.execSQL("DELETE FROM $it") }
        d.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('entry', 'message')")
    }

    private fun readTable(input: InputStream, d: SupportSQLiteDatabase, table: String?, restore: Boolean) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine() ?: return
        if (table == null || !restore) {
            while (reader.readLine() != null) Unit
            return
        }
        val cols = (Json.parseToJsonElement(header) as JsonArray).map { it.jsonPrimitive.content }
        val known = d.query("PRAGMA table_info($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        while (true) {
            val line = reader.readLine() ?: break
            val arr = Json.parseToJsonElement(line) as JsonArray
            val cv = ContentValues()
            cols.forEachIndexed { i, col ->
                if (col !in known) return@forEachIndexed
                when (val v = arr.getOrNull(i)) {
                    null, JsonNull -> cv.putNull(col)
                    is JsonObject -> v["r"]?.let { cv.put(col, it.jsonPrimitive.content.toDouble()) }
                        ?: v["b"]?.let { cv.put(col, Base64.getDecoder().decode(it.jsonPrimitive.content)) }
                    is JsonPrimitive -> if (v.isString) cv.put(col, v.content) else cv.put(col, v.content.toLong())
                    else -> cv.putNull(col)
                }
            }
            d.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
        }
    }
}
