package app.agency.core.importing

import app.agency.core.agent.Card
import app.agency.core.agent.Company
import app.agency.core.agent.Providers
import app.agency.core.agent.SayResult
import app.agency.core.agent.tools.ToolRig
import app.agency.core.agent.tools.Tools
import app.agency.core.base.TestClock
import app.agency.core.day.Violations
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.calls
import app.agency.core.llm.ScriptedLlm.Companion.says
import app.agency.core.plan.ItemStatus
import app.agency.core.rules.Fixtures
import app.agency.core.stage.StageState
import app.agency.core.store.Body
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MemStore
import app.agency.core.store.Money
import app.agency.core.store.ProposalRec
import app.agency.core.store.Role
import app.agency.core.store.writeDoc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing old records from a file: preview, his yes, entries on their own days, undo. All data invented. */
class ImportTest {
    private val store = MemStore()
    private val clock = TestClock()
    private val llm = ScriptedLlm()
    private val company = Company(store, clock, Providers(llm, ScriptedLlm(fallback = says("""{"ok":true,"reason":""}""")), ToolRig.FakeSearch()) { true })
    private val monday = TestClock.MONDAY

    private fun onboard(stage: Int = 2) {
        company.saveRules(Fixtures.rules(), "", "原文")
        company.rollover()
        val joined = monday.minusDays(((stage - 1) * 7).toLong())
        store.writeDoc(Doc.STAGE, StageState(joinedOn = joined.toString(), stage = stage, startedOn = monday.toString()))
    }

    private fun row(vararg kv: Pair<String, Any?>): String {
        val all = linkedMapOf<String, Any?>(
            "date" to null, "amount" to null, "bucket" to null, "note" to null, "slot" to null, "name" to null, "kcal" to null,
            "protein_g" to null, "kg" to null, "waist_cm" to null, "text" to null, "done" to null,
        )
        kv.forEach { all[it.first] = it.second }
        return all.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + when (v) { null -> "null"; is String -> "\"$v\""; else -> v.toString() }
        }
    }

    private fun preview(kind: String, rows: List<String>): app.agency.core.store.Message {
        llm.then(calls("import_preview", """{"kind":"$kind","rows":[${rows.joinToString(",")}]}"""), says("預覽好了，你看一下卡片再決定。"))
        assertEquals(SayResult.Ok, runBlocking { company.say("幫我匯入這個檔案") })
        return store.messages(20).last { it.role == Role.MANAGER }
    }

    private fun entriesOf(kind: String) = store.entries(monday.minusDays(60), monday, kind)

    @Test fun previewThenYesPutsEachEntryOnItsDayThenUndoRemovesExactlyThose() = runBlocking {
        onboard()
        val mine = store.add(monday.minusDays(3), clock.now(), Kind.MONEY, Json.parseToJsonElement("""{"amount":77,"bucket":"LIVING","note":"自己記的"}""").jsonObject)
        val msg = preview(
            "money",
            listOf(
                row("date" to "${monday.minusDays(5)}", "amount" to 120, "bucket" to "living", "note" to "午餐"),
                row("date" to "${monday.minusDays(4)}", "amount" to 900, "bucket" to "income", "note" to "跑單"),
                row("date" to "${monday.minusDays(4)}", "amount" to 35, "bucket" to "fun", "note" to "飲料"),
            ),
        )
        val card = msg.cards.single() as Card.Proposal
        assertEquals("匯入 3 筆記帳", card.title)
        assertTrue(card.body, card.body.contains("共 3 筆記帳") && card.body.contains("${monday.minusDays(5)} 到 ${monday.minusDays(4)}"))
        assertTrue(card.body.contains("生活 120 元 午餐"))
        assertTrue(card.body.none { it in 'A'..'Z' || it in 'a'..'z' })
        // Nothing written before yes.
        assertEquals(listOf(mine.id), entriesOf(Kind.MONEY).map { it.id })

        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "好"))
        val money = entriesOf(Kind.MONEY).filter { it.id != mine.id }
        assertEquals(listOf(monday.minusDays(5), monday.minusDays(4), monday.minusDays(4)), money.map { it.day })
        assertEquals(listOf(120, 900, 35), money.map { it.read<Money>().amount })
        assertTrue(money.all { it.read<Money>().batch == card.entryId.toString() })
        val note = store.messages(5).last { it.role == Role.NOTE }
        assertTrue(note.text, note.text.startsWith("✓ 已匯入 3 筆記帳"))
        val undo = note.cards.single() as Card.Proposal
        assertEquals("復原", undo.yesLabel)
        // The 「復原」 card is not a pending proposal for her to chase.
        assertFalse(app.agency.core.agent.Context(store, clock, company.rules()).block().contains("復原這次匯入"))

        assertEquals(SayResult.Ok, company.answerCard(note.id, 0, "復原"))
        assertEquals(listOf(mine.id), entriesOf(Kind.MONEY).map { it.id })
        assertTrue(store.messages(5).last { it.role == Role.NOTE }.text.contains("已復原：刪掉 3 筆"))
        // A second undo does nothing.
        assertTrue(company.answerCard(note.id, 0, "復原") is SayResult.NotSent)
        assertNull(Importer.undo(store, card.entryId))
    }

    @Test fun badRowsAreReportedAndKeptOut() = runBlocking {
        onboard()
        val msg = preview(
            "body",
            listOf(
                row("date" to "${monday.minusDays(7)}", "kg" to 70.5, "waist_cm" to 75),
                row("date" to "${monday.plusDays(2)}", "kg" to 70.1),
                row("date" to "前天", "kg" to 70.1),
                row("date" to "${monday.minusDays(6)}", "kg" to 7000),
                row("date" to "${monday.minusDays(6)}"),
            ),
        )
        val card = msg.cards.single() as Card.Proposal
        assertEquals("匯入 1 筆體重", card.title)
        listOf("第 2 列：日期在未來", "第 3 列：日期看不懂", "第 4 列：體重不合理", "第 5 列：沒有體重也沒有腰圍").forEach {
            assertTrue("missing 「$it」 in\n${card.body}", card.body.contains(it))
        }
        assertTrue(entriesOf(Kind.BODY).isEmpty())
        val p = store.entry(card.entryId)!!.read<ProposalRec>()
        assertEquals(Importer.KIND, p.kind)
        assertEquals(1, Importer.read(p)!!.entries.size)
        company.answerCard(msg.id, 0, "好")
        val body = entriesOf(Kind.BODY).single()
        assertEquals(monday.minusDays(7), body.day)
        assertEquals(70.5, body.read<Body>().kg!!, 1e-9)
    }

    @Test fun importedMealsNeverMakeViolationsOrCountAsDone() = runBlocking {
        onboard()
        val msg = preview(
            "meal",
            listOf(
                // A banned food over the meal cap, on a past day and on today: history only.
                row("date" to "${monday.minusDays(2)}", "slot" to "dinner", "name" to "炸物拼盤", "kcal" to 1200, "protein_g" to 20),
                row("date" to "${monday.minusDays(2)}", "slot" to "dinner", "name" to "白飯", "kcal" to 280, "protein_g" to 5),
                row("date" to "$monday", "slot" to "first", "name" to "炸物拼盤", "kcal" to 1200, "protein_g" to 20),
                row("date" to "$monday", "slot" to "first", "name" to "沒有熱量的東西"),
            ),
        )
        val card = msg.cards.single() as Card.Proposal
        assertTrue(card.body, card.body.contains("共 2 筆飲食"))
        assertTrue(card.body.contains("炸物拼盤 1200 大卡、白飯 280 大卡，共 1480 大卡"))
        assertTrue(card.body.contains("第 4 列：沒有熱量"))
        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "好"))
        val meals = entriesOf(Kind.MEAL)
        assertEquals(listOf(monday.minusDays(2), monday), meals.map { it.day })
        assertEquals(2, meals.first().read<Meal>().items.size)
        for (d in listOf(monday.minusDays(2), monday)) {
            val stage = app.agency.core.day.StageDays.asOf(app.agency.core.stage.Stages.get(store)!!, d)
            Violations.resync(store, company.rules(), stage, d, clock.now(), clock.zone)
            assertTrue("violations on $d", store.entries(d, Kind.VIOLATION).isEmpty())
        }
        val today = company.today()
        assertTrue(today.plan.items.filter { it.id.startsWith("meal:") }.none { it.status == ItemStatus.DONE })
        assertEquals(0, app.agency.core.agent.Moment.of(store, clock, company.rules()).state.kcal)
    }

    @Test fun undoImportToolRemovesTheBatchWithoutAModelCallForTheCard() = runBlocking {
        onboard()
        val msg = preview("memo", listOf(row("date" to "${monday.minusDays(1)}", "text" to "繳費", "done" to true)))
        val card = msg.cards.single() as Card.Proposal
        // Before yes there is nothing to undo.
        assertNull(Importer.undo(store, card.entryId))
        company.answerCard(msg.id, 0, "好")
        assertEquals(1, entriesOf(Kind.MEMO).size)
        val ctx = app.agency.core.agent.ToolCtx(store, clock, company.rules(), app.agency.core.stage.Stages.get(store)!!)
        val out = Tools.named("undo_import")!!.run(Json.parseToJsonElement("""{"batch":${card.entryId}}""").jsonObject, ctx)
        assertTrue(out.facts.toString(), out.ok)
        assertTrue(entriesOf(Kind.MEMO).isEmpty())
        // The 「復原」 card is closed by the tool's undo.
        val note = store.messages(5).last { it.role == Role.NOTE && it.cards.isNotEmpty() }
        assertTrue(company.answerCard(note.id, 0, "復原") is SayResult.NotSent)
    }

    @Test fun aNoKeepsEverythingUnwritten() = runBlocking {
        onboard()
        val msg = preview("money", listOf(row("date" to "${monday.minusDays(1)}", "amount" to 50, "bucket" to "fun", "note" to "")))
        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "不要"))
        assertTrue(entriesOf(Kind.MONEY).isEmpty())
        assertEquals(Card.Answer.NO, store.entry((msg.cards.single() as Card.Proposal).entryId)!!.read<ProposalRec>().answer)
    }
}
