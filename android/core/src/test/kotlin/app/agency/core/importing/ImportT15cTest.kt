package app.agency.core.importing

import app.agency.core.model.ImportTarget
import app.agency.core.model.LedgerBucket
import app.agency.core.model.LedgerEntry
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15c: CSV, XLSX, JSON, Markdown and pasted text read on the phone; the mapper, duplicates, nothing before 匯入, one 復原. Invented data. */
class ImportT15cTest {
    private val store = InMemoryAgencyStore()
    private var n = 0
    private val importer = Importer(store, AgencyClock.TAIPEI) { "id-%04d".format(++n) }
    private val today = LocalDate.parse("2026-12-10")

    companion object {
        fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> entries.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        }.toByteArray()

        /** An invented one-sheet workbook: 日期 as an Excel serial, 金額 a number, 項目 and 分類 shared strings. */
        fun xlsx(): ByteArray = zip(
            "xl/workbook.xml" to "<workbook/>",
            "xl/sharedStrings.xml" to """<sst><si><t>日期</t></si><si><t>金額</t></si><si><t>項目</t></si><si><t>分類</t></si><si><t>便當</t></si><si><t>餐費</t></si><si><t>髮蠟</t></si><si><t>形象用品</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
                <row r="2"><c r="A2"><v>46357</v></c><c r="B2"><v>120</v></c><c r="C2" t="s"><v>4</v></c><c r="D2" t="s"><v>5</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>2026/12/2</t></is></c><c r="B3"><v>250</v></c><c r="C3" t="s"><v>6</v></c><c r="D3" t="s"><v>7</v></c></row>
            </sheetData></worksheet>""",
        )
    }

    @Test fun everyFormatBecomesTheSameTable() {
        val csv = Tables.text("日期,金額,項目\n2026-12-01,120,便當\n2026-12-02,\"1,250\",\"耳機, 有線\"")
        assertEquals(listOf("日期", "金額", "項目"), csv.headers)
        assertEquals(listOf("2026-12-02", "1,250", "耳機, 有線"), csv.rows[1])
        val md = Tables.text("| 日期 | 金額 | 項目 |\n|---|---|---|\n| 12/1 | 120 | 便當 |")
        assertEquals(listOf(listOf("12/1", "120", "便當")), md.rows)
        val json = Tables.text("""[{"date":"2026-12-01","amount":120,"item":"便當"}]""")
        assertEquals(listOf("date", "amount", "item"), json.headers)
        assertEquals(listOf("2026-12-01", "120", "便當"), json.rows.single())
        val tsv = Tables.text("日期\t金額\t項目\n12/3\t60\t豆漿")
        assertEquals("豆漿", tsv.rows.single()[2])
        val x = Tables.xlsx(xlsx())!!
        assertEquals(listOf("日期", "金額", "項目", "分類"), x.headers)
        assertEquals(LocalDate.parse("2026-12-01"), Cells.date(x.rows[0][0], today))
        assertEquals("髮蠟", x.rows[1][2])
        assertNull(Tables.xlsx("not a zip".toByteArray()))
    }

    @Test fun cellsAreReadByCode() {
        assertEquals(LocalDate.parse("2026-12-01"), Cells.date("2026年12月1日", today))
        assertEquals(LocalDate.parse("2026-12-01"), Cells.date("民國115/12/1", today))
        assertEquals(LocalDate.parse("2025-12-25"), Cells.date("12/25", today)) // later than today: last year
        assertEquals(1250, Cells.amount("$1,250"))
        assertEquals(120, Cells.amount("120元"))
        assertEquals(120, Cells.amount("-120"))
        assertNull(Cells.amount("免費"))
        assertEquals(94.4, Cells.kg("94.4 公斤")!!, 1e-9)
    }

    @Test fun theMapperGuessesThePreviewSkipsDuplicatesAndNothingIsSavedBeforeImport() = runBlocking {
        store.ledger.insert(LedgerEntry("old", "2026-12-01", 1, 120, "便當"))
        val t = Tables.xlsx(xlsx())!!
        val m = Importer.guess(t)
        assertEquals(Mapping(0, 1, 2, 3, null, null, 2, setOf("形象用品")), m)
        val plan = importer.plan(ImportTarget.LEDGER, t, m, "記帳.xlsx", today)
        assertEquals(1, plan.duplicates) // the 便當 of 12/1 is already in
        assertEquals(listOf("髮蠟"), plan.fresh.map { it.item })
        assertEquals(1, store.ledger.count()) // the preview wrote nothing
        val batch = importer.commit(plan, 99)
        val rows = store.ledger.between("2026-12-01", "2026-12-31")
        assertEquals(listOf(120 to LedgerBucket.LIFE, 250 to LedgerBucket.FUN), rows.map { it.amount to it.bucket })
        assertEquals(batch.id, rows[1].batchId)
        // The same file again: everything is a duplicate.
        assertTrue(importer.plan(ImportTarget.LEDGER, t, m, "記帳.xlsx", today).fresh.isEmpty())
        // His mapping is remembered for that source.
        assertEquals(m, importer.remembered("記帳.xlsx", ImportTarget.LEDGER))
        // One 復原 takes the whole import back, once.
        assertTrue(importer.undo(batch.id, 100))
        assertEquals(listOf("old"), store.ledger.between("2026-12-01", "2026-12-31").map { it.id })
        assertFalse(importer.undo(batch.id, 101))
        assertEquals(100L, store.imports.get(batch.id)!!.undoneAt)
    }

    @Test fun mealsWeightsAndMemosToo() = runBlocking {
        val meals = Tables.text("日期,吃了,熱量\n12/8,雞腿便當,700\n12/8,雞腿便當,700\n12/9,滷味,")
        val pm = importer.plan(ImportTarget.MEALS, meals, Importer.guess(meals), Importer.PASTE, today)
        assertEquals(listOf("雞腿便當", "滷味"), pm.fresh.map { it.item })
        assertEquals(1, pm.duplicates)
        importer.commit(pm, 5)
        assertEquals(700, store.meals.onDay("2026-12-08").single().kcal)
        val weights = Tables.text("日期,體重\n12/1,95.6\n12/8,94.9 公斤\n12/9,很重")
        val pw = importer.plan(ImportTarget.WEIGHTS, weights, Importer.guess(weights), Importer.PASTE, today)
        assertEquals(listOf(95.6, 94.9), pw.fresh.map { it.kg })
        assertEquals("體重看不懂", pw.rows.single { it.status == Planned.Status.BAD }.reason)
        importer.commit(pw, 6)
        assertEquals(2, store.weights.count())
        val memos = Tables.text("買洗衣精\n繳電話費")
        val pn = importer.plan(ImportTarget.MEMOS, memos, Importer.guess(memos), Importer.PASTE, today)
        val b = importer.commit(pn, 7)
        assertEquals(listOf("買洗衣精", "繳電話費"), store.memos.all().map { it.text })
        importer.undo(b.id, 8)
        assertTrue(store.memos.all(deleted = true).isEmpty())
    }

    @Test fun aClaudeExportIsReadOnThePhoneAndOnlyUnreadLinesGoToTheModelAfterHisOk() = runBlocking {
        val conv = """[{"uuid":"c-1","name":"十二月記帳","chat_messages":[
            {"sender":"human","created_at":"2026-12-01T04:00:00Z","text":"12/1\n午餐 便當 120元\n飲料 $45\n今天心情不錯"},
            {"sender":"assistant","created_at":"2026-12-01T04:00:05Z","text":"好的，記下：便當 120 元"},
            {"sender":"human","created_at":"2026-12-02T04:00:00Z","content":[{"type":"text","text":"捷運 三十塊"}]}]},
            {"uuid":"c-2","name":"別的","chat_messages":[]}]"""
        val all = ClaudeExport.conversations(zip("data/conversations.json" to conv), AgencyClock.TAIPEI)!!
        assertEquals(listOf("十二月記帳", "別的"), all.map { it.name })
        val lines = ClaudeExport.lines(all.take(1), today)
        // His lines only (not the assistant's repeat); the chat line is left out; 「三十塊」 code cannot read.
        assertEquals(listOf("便當" to 120, "飲料" to 45), lines.filter { it.amount != null }.map { it.item.substringAfterLast(' ') to it.amount })
        assertEquals(LocalDate.parse("2026-12-01"), lines.first().day)
        val unread = lines.filter { it.amount == null }
        assertEquals(listOf("捷運 三十塊"), unread.map { it.raw })
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"rows":[{"line":1,"date":null,"amount":30,"item":"捷運"}]}"""))
        val read = LineReader.read(llm, unread, today)!!
        assertEquals(listOf(LocalDate.parse("2026-12-02") to 30), read.map { it.day to it.amount })
        // Only those lines were sent.
        val sent = llm.requests.single().turns.joinToString { it.toString() }
        assertTrue(sent.contains("捷運 三十塊"))
        assertFalse(sent.contains("便當"))
        val table = ClaudeExport.table(lines + read)
        val plan = importer.plan(ImportTarget.LEDGER, table, Importer.guess(table), Importer.CLAUDE, today)
        assertEquals(3, plan.fresh.size)
    }
}
