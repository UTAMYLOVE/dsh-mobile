package app.agency.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One conversation of a claude.ai data export: its name, and his lines with the day each was written. */
data class ExportConversation(val id: String, val name: String, val lines: List<Pair<LocalDate?, String>>)

/** A line code read as a 記帳 entry, or could not ([amount] null: it goes to the 「讀不出來」 list). */
data class LedgerLine(val day: LocalDate?, val amount: Int?, val item: String, val raw: String)

/**
 * His bookkeeping lives in Claude conversations (owner 2026-11-23). Route 2 of T15c: the data export (a zip with
 * `conversations.json`) is read on the phone; he picks conversations; [lines] reads each of his lines for a date, an amount and
 * an item. What code cannot read is listed, and only those lines go to the model, after his OK.
 */
object ClaudeExport {
    const val FILE = "conversations.json"
    const val MAX_CONVERSATIONS = 2000

    /** The conversations in [zip] (or a bare `conversations.json`), his own lines only; null when it is neither. */
    fun conversations(zip: ByteArray, zone: ZoneId): List<ExportConversation>? {
        val text = Tables.unzip(zip, setOf(FILE))?.get(FILE) ?: String(zip, Charsets.UTF_8).takeIf { it.trimStart().startsWith("[") } ?: return null
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return arr.take(MAX_CONVERSATIONS).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("uuid") ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: "（沒有名稱）"
            val msgs = (o["chat_messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("sender") == "human" }
            val lines = msgs.flatMap { m ->
                val day = m.str("created_at")?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
                val body = m.str("text")?.takeIf { it.isNotBlank() }
                    ?: (m["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { c -> c.str("type") == "text" }?.str("text") }.joinToString("\n")
                body.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { day to it }
            }
            ExportConversation(id, name, lines)
        }
    }

    private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val DATE_ANY = Regex("(20[0-9]{2})[-/.年]\\s*([0-9]{1,2})[-/.月]\\s*([0-9]{1,2})日?|(?<![0-9])([0-9]{1,2})[/月]([0-9]{1,2})[日號]?(?![0-9])")
    private val MONEY = Regex("(?:NT\\$|\\$|＄)\\s*([0-9][0-9,]*)|([0-9][0-9,]*)\\s*(?:元|塊|圓)")
    private val BARE = Regex("(?<![0-9.])([0-9]{2,6})(?![0-9.])")
    private val HAN = Regex("[\\u4e00-\\u9fff]")
    private val CN_NUM = Regex("[一二兩三四五六七八九十百千]+\\s*(?:元|塊)")
    private val BULLET = Regex("^[-*・•‧●○◦\\d]+[.、)）]?\\s+|^[-*・•‧●○◦]+")

    /**
     * His lines read for 記帳: a date in the line (else the day of the message, a date line before it carries over), an amount
     * (「120 元」「$120」「120 塊」, or the one bare number of a line), and the rest as the item. Lines with no number and no money
     * words are chat and left out; lines with money words code cannot read come back with [LedgerLine.amount] null.
     */
    fun lines(conversations: List<ExportConversation>, today: LocalDate): List<LedgerLine> = conversations.flatMap { c ->
        var carried: LocalDate? = null
        var carriedIn: LocalDate? = null
        c.lines.mapNotNull { (msgDay, raw) ->
            // A date line carries over to the lines after it in the same message only.
            if (carriedIn != msgDay) carried = null
            val cells = if (raw.startsWith("|")) raw.trim('|').split('|').map { it.trim() } else null
            if (cells != null && cells.all { it.matches(Regex(":?-{2,}:?")) || it.isEmpty() }) return@mapNotNull null
            val line = cells?.joinToString(" ") ?: raw
            val dm = DATE_ANY.find(line)
            val day = dm?.let { m ->
                if (m.groupValues[1].isNotEmpty()) runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
                else Cells.date("${m.groupValues[4]}/${m.groupValues[5]}", today)
            }
            var rest = dm?.let { line.removeRange(it.range) } ?: line
            if (day != null) { carried = day; carriedIn = msgDay }
            val mm = MONEY.find(rest)
            val amount = when {
                mm != null -> (mm.groupValues[1].ifEmpty { mm.groupValues[2] }).replace(",", "").toIntOrNull()?.also { rest = rest.removeRange(mm.range) }
                else -> BARE.findAll(rest).toList().singleOrNull()?.let { b -> b.groupValues[1].toIntOrNull()?.also { rest = rest.removeRange(b.range) } }
            }?.takeIf { it in 1..200_000 }
            val item = BULLET.replace(rest, "").replace(Regex("[：:，,|]+"), " ").trim().take(60)
            val moneyWords = CN_NUM.containsMatchIn(line) || line.contains("元") || line.contains("塊")
            when {
                amount != null && item.isNotEmpty() && HAN.containsMatchIn(item) -> LedgerLine(day ?: carried ?: msgDay, amount, item, raw)
                amount == null && moneyWords && HAN.containsMatchIn(line) -> LedgerLine(day ?: carried ?: msgDay, null, item.ifEmpty { line.take(60) }, raw)
                else -> null
            }
        }
    }

    /** The read lines as a table for the same mapper (日期、金額、項目). */
    fun table(lines: List<LedgerLine>): Table = Table(
        listOf("日期", "金額", "項目"),
        lines.filter { it.amount != null }.map { listOf(it.day?.toString().orEmpty(), it.amount.toString(), it.item) },
    )
}
