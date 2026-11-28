package app.agency.core.sim

import app.agency.core.agent.Card
import app.agency.core.plan.ItemStatus
import app.agency.core.stage.StageRound
import app.agency.core.stage.StageState
import app.agency.core.stage.Stages
import app.agency.core.store.Doc
import app.agency.core.store.Role
import app.agency.core.store.writeDoc
import org.junit.Test

/**
 * Stages 3 and 4 with the same harness: a week of stage 3 that passes, the opening of stage 4, and a week of stage 4 that
 * ends on the last Sunday of the month (weekly and monthly evaluation, body photo, real-person task, theme week, spot checks,
 * grooming). Every day is carried by `next` alone, so anything open without a doable item shows up as a dead end.
 */
class SimLaterStagesTest {
    private val sim = Sim()

    private fun followNext(chat: Boolean) {
        var steps = 0
        while (true) {
            val n = sim.plan().next ?: break
            sim.require(++steps < 120) { "next never ran out" }
            n.at?.let { at ->
                val due = app.agency.core.base.Days.at(sim.day, at, sim.clock.zone)
                if (sim.clock.now().isBefore(due)) sim.walkTo("%02d:%02d".format(at.hour, at.minute))
            }
            if (sim.item(n.id)?.status != ItemStatus.PENDING && sim.item(n.id)?.status != ItemStatus.LATE) continue
            sim.doItem(n.id, chat)
            val after = sim.plan().next
            sim.require(after?.id != n.id) { "next is still ${n.id} after it was done (${after?.status})" }
        }
    }

    private fun day(n: Int, stage: Int, dayInRound: Int) {
        sim.dayN = n
        sim.at("06:30")
        sim.company.rollover()
        val label = sim.today().stageLabel
        sim.require(label == "第 $stage 階段 · 第 $dayInRound 天") { "day $n label $label" }
        sim.t("—— ${sim.day}（${sim.day.dayOfWeek}）$label ——")
        sim.check("morning")
        followNext(chat = n % 2 == 0)
        sim.walkTo("02:40", 20)
        val p = sim.plan()
        val left = p.items.filter { it.counts && it.status != ItemStatus.DONE && it.status != ItemStatus.OFF }
        sim.require(left.isEmpty()) { "day $n left undone: ${left.map { "${it.id} ${it.status}" }}" }
    }

    @Test fun stagesThreeAndFourHaveNoDeadEnd() {
        sim.dayN = 1
        sim.at("06:10")
        sim.company.saveRules(sim.rules, "", "")
        // He joined three weeks before the first simulated day and passed stages 1 and 2.
        sim.store.writeDoc(
            Doc.STAGE,
            StageState(
                joinedOn = "2029-12-31", stage = 3, startedOn = "2030-01-14",
                rounds = listOf(
                    StageRound(1, "2029-12-31", "2030-01-06", 100, 0, true),
                    StageRound(2, "2030-01-07", "2030-01-13", 100, 0, true),
                ),
                closedThrough = "2030-01-13",
            ),
        )
        for (n in 8..14) day(n, 3, n - 7)
        for (n in 15..21) day(n, 4, n - 14)
        val st = Stages.get(sim.store)!!
        sim.require(st.stage == 4) { "stage 4 did not open: ${st.rounds.last()}" }
        val stages = sim.store.messages(3000).filter { it.role == Role.NOTE }.flatMap { it.cards }
            .filterIsInstance<Card.Ceremony>().filter { it.kind == "stage" }
        sim.require(stages.size == 1) { "stage ceremonies ${stages.size}" }
        sim.require(sim.model.problems.isEmpty()) { "model problems: ${sim.model.problems}" }
        runCatching { java.io.File("build/sim-later-trace.txt").writeText(sim.trace.toString()) }
    }
}
