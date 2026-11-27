package app.agency.core.importing

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A table read on the phone from a file or pasted text (T15c): a header row and the rows, every cell as text. */
data class Table(val headers: List<String>, val rows: List<List<String>>) {
    fun cell(row: List<String>, col: Int?): String = col?.let { row.getOrNull(it) }?.trim().orEmpty()

    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLS = 30
        val EMPTY = Table(emptyList(), emptyList())

        fun of(all: List<List<String>>): Table {
            val rows = all.map { r -> r.take(MAX_COLS).map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
            if (rows.isEmpty()) return EMPTY
            val width = rows.maxOf { it.size }
            val padded = rows.map { it + List(width - it.size) { "" } }
            return Table(padded.first(), padded.drop(1).take(MAX_ROWS))
        }
    }
}

/**
 * Reading what he gives (T15c), all on the phone and with no library: CSV (comma, semicolon or tab, quoted cells), a Markdown
 * table (what Claude writes when asked for a table), a JSON array of objects (or `{"rows": [...]}`), and the first sheet of an
 * XLSX workbook. Plain lines without columns become a one-column table.
 */
object Tables {
    private val MD_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

    fun text(raw: String): Table {
        val t = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isEmpty()) return Table.EMPTY
        if (t.startsWith("[") || t.startsWith("{")) json(t)?.let { return it }
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val markdown = lines.filter { it.startsWith("|") }
        if (markdown.size >= 2 && markdown.any { MD_RULE.matches(it) }) {
            return Table.of(markdown.filter { !MD_RULE.matches(it) }.map { l -> l.trim('|').split('|').map { it.trim() } })
        }
        val sep = listOf('\t', ',', ';', '，').maxByOrNull { c -> lines.take(10).count { it.contains(c) } }?.takeIf { c -> lines.take(10).count { it.contains(c) } >= minOf(2, lines.size) }
        if (sep == null) return Table.of(listOf(listOf("內容")) + lines.map { listOf(it) })
        return Table.of(csv(t, sep))
    }

    /** CSV with quotes ("a, b" and "" for a quote). */
    fun csv(t: String, sep: Char): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                quoted && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && c == '\n' -> { row += cell.toString(); cell.clear(); out += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        out += row
        return out
    }

    private fun json(t: String): Table? {
        val e = runCatching { Json.parseToJsonElement(t) }.getOrNull() ?: return null
        val arr = when (e) {
            is JsonArray -> e
            is JsonObject -> e.values.firstOrNull { it is JsonArray } as? JsonArray ?: return null
            else -> return null
        }
        val objs = arr.filterIsInstance<JsonObject>()
        if (objs.isEmpty()) return null
        val keys = objs.flatMap { it.keys }.distinct().take(Table.MAX_COLS)
        return Table.of(listOf(keys) + objs.map { o -> keys.map { k -> (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty() } })
    }

    /** The first sheet of an XLSX (zip of XML): shared strings, inline strings, numbers; Excel date serials as yyyy-MM-dd when the column looks like dates. */
    fun xlsx(bytes: ByteArray): Table? {
        val files = unzip(bytes, setOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/workbook.xml")) ?: return null
        val sheet = files["xl/worksheets/sheet1.xml"] ?: return null
        val shared = files["xl/sharedStrings.xml"]?.let { sx ->
            Regex("<si>([\\s\\S]*?)</si>").findAll(sx).map { si -> Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()
        }.orEmpty()
        val rows = mutableListOf<List<String>>()
        Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(sheet).forEach { r ->
            val cells = sortedMapOf<Int, String>()
            Regex("<c ([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(r.groupValues[1]).forEach { c ->
                val attrs = c.groupValues[1]
                val ref = Regex("r=\"([A-Z]+)[0-9]+\"").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val type = Regex("t=\"([a-zA-Z]+)\"").find(attrs)?.groupValues?.get(1)
                val inner = c.groupValues.getOrNull(2).orEmpty()
                val v = Regex("<v>([\\s\\S]*?)</v>").find(inner)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                    else -> v?.let(::unescape).orEmpty()
                }
                cells[column(ref)] = value
            }
            if (cells.isNotEmpty()) rows += (0..cells.lastKey()).map { cells[it].orEmpty() }
        }
        return Table.of(rows)
    }

    private fun column(ref: String): Int = ref.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#([0-9]+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }.replace("&amp;", "&")

    /** The named entries of a zip (at most [maxBytes] each), or null when it is not a zip. */
    fun unzip(bytes: ByteArray, names: Set<String>, maxBytes: Int = 64 * 1024 * 1024): Map<String, String>? = runCatching {
        val out = mutableMapOf<String, String>()
        var any = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                any = true
                val name = e.name.substringAfterLast('/').let { base -> names.firstOrNull { it == e.name || it.substringAfterLast('/') == base && !it.contains('/') } }
                    ?: names.firstOrNull { it == e.name }
                if (name != null && !e.isDirectory) {
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = z.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        check(buf.size() <= maxBytes) { "entry too large" }
                    }
                    out[name] = buf.toString(Charsets.UTF_8.name())
                }
            }
        }
        if (!any) null else out
    }.getOrNull()
}
