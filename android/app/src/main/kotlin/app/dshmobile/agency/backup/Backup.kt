package app.dshmobile.agency.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.agency.core.backup.BackupCipher
import app.agency.core.backup.BackupException
import app.dshmobile.agency.media.MediaVault
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
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

/** What a backup holds: when it was made, its schema, the rows per table and the photos and recordings. */
data class BackupSummary(val createdAt: Long, val schema: Int, val rows: Map<String, Int>, val media: Int)

/**
 * 加密備份 (owner 2026-11-23): every record of the encrypted database and every photo and recording of the vault, decrypted
 * only into the backup stream, which [BackupCipher] seals with his password (PBKDF2, AES-GCM, a version mark). Inside is a
 * zip: `manifest.json`, `tables/<table>.jsonl` (a column line, then one JSON array per row) and `media/<id>.bin`.
 *
 * Never in it: the API keys (they live in the Keystore-wrapped key vault, not in the database; he types them again on the new
 * phone), the app's settings file, the manager's stored thinking ([EXCLUDED]), Room's own tables.
 * [restore] checks the whole file first ([summary] reads every segment), then replaces every table in one transaction and the
 * vault's files; a backup of an older schema is migrated first (REVIEW-logic I4), one of a newer schema is refused.
 */
class Backup(
    private val db: () -> SupportSQLiteDatabase,
    private val vault: MediaVault,
    /**
     * An older backup (REVIEW-logic I4): a fresh in-memory database at schema [Int] (its tables from Room's exported schema,
     * never on disk), or null when that schema is not known. Its rows are migrated with the app's own migrations.
     */
    private val older: ((Int) -> SupportSQLiteDatabase?)? = null,
    private val migrations: List<androidx.room.migration.Migration> = emptyList(),
) {
    companion object {
        const val FORMAT = "xiaoyu-backup"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val TABLES = "tables/"
        const val MEDIA = "media/"
        /** Room's own tables, and the thinking that is never exported (it goes back only to the provider that made it). */
        val EXCLUDED = setOf("room_master_table", "android_metadata", "sqlite_sequence", "thinking")
        private val NAME = Regex("[a-z_]{1,40}")
    }

    private fun tables(d: SupportSQLiteDatabase): List<String> = d.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }.filter { it !in EXCLUDED && !it.startsWith("sqlite_") && NAME.matches(it) }

    private fun count(d: SupportSQLiteDatabase, t: String): Int = d.query("SELECT COUNT(*) FROM `$t`").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Writes the backup of everything to [out] (closed afterwards); its summary. */
    fun export(out: OutputStream, password: CharArray, at: Long, iterations: Int = BackupCipher.ITERATIONS): BackupSummary {
        val d = db()
        val names = tables(d)
        val mediaIds = d.query("SELECT id FROM media").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.filter { vault.has(it) }
        val summary = BackupSummary(at, d.version, names.associateWith { count(d, it) }, mediaIds.size)
        val sealed = BackupCipher.encrypt(out, password, iterations)
        ZipOutputStream(sealed).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(summary).toByteArray())
            zip.closeEntry()
            names.forEach { t ->
                zip.putNextEntry(ZipEntry("$TABLES$t.jsonl"))
                d.query("SELECT * FROM `$t`").use { c ->
                    zip.write((JsonArray(c.columnNames.map { JsonPrimitive(it) }).toString() + "\n").toByteArray())
                    while (c.moveToNext()) zip.write((row(c).toString() + "\n").toByteArray())
                }
                zip.closeEntry()
            }
            mediaIds.forEach { id ->
                zip.putNextEntry(ZipEntry("$MEDIA$id.bin"))
                vault.open(id).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return summary
    }

    private fun manifest(s: BackupSummary): String = buildJsonObject {
        put("format", FORMAT); put("version", FORMAT_VERSION); put("schema", s.schema); put("created_at", s.createdAt)
        put("rows", buildJsonObject { s.rows.forEach { (t, n) -> put(t, n) } }); put("media", s.media)
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
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP, "no manifest")
        if (o["format"]?.jsonPrimitive?.content != FORMAT) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "not ours")
        if ((o["version"]?.jsonPrimitive?.int ?: 0) > FORMAT_VERSION) throw BackupException(BackupException.Reason.NEWER_VERSION, "newer content")
        val s = BackupSummary(
            o["created_at"]?.jsonPrimitive?.long ?: 0, o["schema"]?.jsonPrimitive?.int ?: 0,
            (o["rows"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.int }, o["media"]?.jsonPrimitive?.int ?: 0,
        )
        if (s.schema > schema) throw BackupException(BackupException.Reason.NEWER_VERSION, "schema ${s.schema} is newer than $schema")
        if (s.schema < schema && !canUpgrade(s.schema, schema)) throw BackupException(BackupException.Reason.OLDER_VERSION, "schema ${s.schema} cannot be upgraded")
        return s
    }

    /** The migrations from [from] to [to] in order, or null when the chain has a gap. */
    private fun chain(from: Int, to: Int): List<androidx.room.migration.Migration>? {
        val out = mutableListOf<androidx.room.migration.Migration>()
        var v = from
        while (v < to) {
            val m = migrations.firstOrNull { it.startVersion == v } ?: return null
            out += m
            v = m.endVersion
        }
        return out
    }

    private fun canUpgrade(from: Int, to: Int): Boolean = older != null && from >= 1 && chain(from, to) != null

    /** Writes [rows] into [d]'s tables of the same names (columns it does not have are left out). */
    private fun insertRows(d: SupportSQLiteDatabase, rows: Map<String, Pair<List<String>, List<JsonArray>>>, current: List<String>) {
        rows.forEach { (t, data) ->
            if (t !in current) return@forEach
            val (cols, list) = data
            val known = d.query("PRAGMA table_info(`$t`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
            list.forEach { arr ->
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
                d.insert(t, SQLiteDatabase.CONFLICT_ABORT, cv)
            }
        }
    }

    /**
     * An older backup's rows brought to the current schema: written into an in-memory database of their own schema, migrated
     * there by the app's migrations, and read back (the same column-line and row shape as the file).
     */
    private fun upgraded(rows: Map<String, Pair<List<String>, List<JsonArray>>>, from: Int, to: Int): Map<String, Pair<List<String>, List<JsonArray>>> {
        val steps = chain(from, to) ?: throw BackupException(BackupException.Reason.OLDER_VERSION, "no migration from $from")
        val tmp = older?.invoke(from) ?: throw BackupException(BackupException.Reason.OLDER_VERSION, "schema $from unknown")
        try {
            tmp.beginTransaction()
            try {
                insertRows(tmp, rows, tables(tmp))
                steps.forEach { it.migrate(tmp) }
                tmp.setTransactionSuccessful()
            } finally {
                tmp.endTransaction()
            }
            return tables(tmp).associateWith { t ->
                tmp.query("SELECT * FROM `$t`").use { c ->
                    c.columnNames.toList() to buildList { while (c.moveToNext()) add(row(c)) }
                }
            }
        } finally {
            tmp.close()
        }
    }

    /** Reads the whole backup (every segment checked) and returns its summary; nothing is changed. */
    fun summary(input: InputStream, password: CharArray): BackupSummary = read(input, password, restore = false)

    /** [summary], then replaces every record and every photo with the backup's. */
    fun restore(input: InputStream, password: CharArray): BackupSummary = read(input, password, restore = true)

    private class Keep(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun read(input: InputStream, password: CharArray, restore: Boolean): BackupSummary {
        val d = db()
        val plain = BackupCipher.decrypt(input, password)
        var summary: BackupSummary? = null
        val rows = linkedMapOf<String, Pair<List<String>, MutableList<JsonArray>>>()
        val media = mutableListOf<String>()
        ZipInputStream(plain).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == MANIFEST -> summary = readManifest(zip.readBytes().toString(Charsets.UTF_8), d.version)
                    summary == null -> throw BackupException(BackupException.Reason.NOT_A_BACKUP, "manifest first")
                    e.name.startsWith(TABLES) && e.name.endsWith(".jsonl") -> {
                        val t = e.name.removePrefix(TABLES).removeSuffix(".jsonl").takeIf { NAME.matches(it) && it !in EXCLUDED } ?: continue
                        val reader = BufferedReader(InputStreamReader(Keep(zip), Charsets.UTF_8))
                        val cols = (Json.parseToJsonElement(reader.readLine() ?: continue) as JsonArray).map { it.jsonPrimitive.content }
                        val list = mutableListOf<JsonArray>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (restore) list += Json.parseToJsonElement(line) as JsonArray
                        }
                        rows[t] = cols to list
                    }
                    e.name.startsWith(MEDIA) && e.name.endsWith(".bin") -> {
                        val id = e.name.removePrefix(MEDIA).removeSuffix(".bin")
                        if (restore) vault.putStream(id, Keep(zip)) else Keep(zip).readBytes()
                        media += id
                    }
                }
            }
        }
        val s = summary ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP, "no manifest")
        if (!restore) return s
        val current = tables(d)
        val data = if (s.schema < d.version) upgraded(rows, s.schema, d.version) else rows
        d.beginTransaction()
        try {
            d.execSQL("PRAGMA defer_foreign_keys = ON")
            (current + "thinking").forEach { d.execSQL("DELETE FROM `$it`") }
            insertRows(d, data, current)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        // The vault now holds what the restored records name.
        val keep = media.toSet()
        vault.ids().filter { it !in keep }.forEach { vault.delete(it) }
        return s
    }
}
