package app.agency.core.sim

import app.agency.core.agent.Card
import app.agency.core.plan.ItemStatus
import app.agency.core.stage.Stages
import app.agency.core.store.Kind
import app.agency.core.store.MealSlot
import app.agency.core.store.Role
import app.agency.core.store.Violation
import java.time.LocalTime

/**
 * The reusable fourteen-day scenario (runs in :core's tests on a MemStore and on the phone against the encrypted database).
 * Fourteen logical days with a scripted owner and a scripted model (SPEC J). Days 1–7 are stage 1 (lessons only), day 8 opens
 * stage 2, days 8–14 are full class and free days with a sick day and its recovery, a banned food, a late check-in on a free
 * day and a rest day. At every step the invariants of [Sim.check] hold; the test adds the day labels, the stage opening and the
 * round judgement on day 15.
 */
class Sim14(private val sim: Sim = Sim()) {

    private fun morning(expected: String) {
        sim.at("06:30")
        sim.company.rollover()
        val label = sim.today().stageLabel
        sim.require(label == expected) { "day ${sim.dayN}: label is $label, expected $expected" }
        sim.t("—— ${sim.day}（${sim.day.dayOfWeek}）$label ——")
        sim.check("morning")
    }

    /** Does what `next` says until it says nothing (walking the clock to timed items), and checks it never sticks. */
    private fun followNext(chat: Boolean, cap: Int = 80) {
        var steps = 0
        while (true) {
            val n = sim.plan().next ?: break
            sim.require(++steps < cap) { "next never ran out" }
            n.at?.let { at ->
                val due = app.agency.core.base.Days.at(sim.day, at, sim.clock.zone)
                if (sim.clock.now().isBefore(due)) sim.walkTo("%02d:%02d".format(at.hour, at.minute))
            }
            sim.doItem(n.id, chat)
            val after = sim.plan().next
            sim.require(after?.id != n.id) { "next is still ${n.id} after it was done (${after?.status})" }
        }
    }

    private fun stageOneDay(n: Int, extra: () -> Unit = {}) {
        sim.dayN = n
        morning("第 1 階段 · 第 $n 天")
        sim.walkTo("08:00")
        sim.say("今天上什麼課")
        extra()
        followNext(chat = n % 2 == 1)
        sim.walkTo("23:00", 30)
        val p = sim.plan()
        sim.require(p.total > 0 && p.done == p.total) { "day $n: ${p.done}/${p.total} done" }
    }

    /** A class or free day of stage 2, done at realistic times. */
    private fun stageTwoDay(n: Int, chat: Boolean, wake: String, hooks: Map<String, () -> Unit> = emptyMap()) {
        sim.dayN = n
        morning("第 2 階段 · 第 ${n - 7} 天")
        fun step(time: String, vararg ids: String) {
            hooks["before $time"]?.invoke()
            sim.walkTo(time)
            for (id in ids) if (sim.item(id) != null && sim.item(id)!!.status != ItemStatus.OFF) sim.doItem(id, chat)
            hooks[time]?.invoke()
        }
        step(wake, "check_in", "body")
        hooks["after check-in"]?.invoke()
        val lessons = sim.plan().items.filter { it.id.startsWith("lesson:") || it.id.startsWith("habit:") }.map { it.id }
        val later = LocalTime.parse(wake).plusMinutes(25).toString()
        step(later, *lessons.toTypedArray())
        // review slots can appear only after the lesson list was loaded; finish whatever is left
        sim.plan().items.filter { (it.id.startsWith("lesson:") || it.id.startsWith("habit:")) && it.status != ItemStatus.DONE }
            .forEach { sim.doItem(it.id, chat) }
        val first = sim.item("meal:FIRST")?.at?.let { it.plusMinutes(5).toString() } ?: "12:05"
        step(first, "meal:FIRST")
        step("16:30", *(if (hooks.containsKey("skip water")) emptyArray() else arrayOf("water", "task:SUNSCREEN")))
        step("18:25", "meal:DINNER")
        step("19:25", "ride:out")
        hooks["riding"]?.invoke()
        step("21:45", "meal:NIGHT")
        step("22:30", "ride:back")
        val training = sim.plan().items.filter { it.id.startsWith("task:") && it.status != ItemStatus.OFF && it.status != ItemStatus.DONE }
        step("22:40", *training.map { it.id }.toTypedArray())
        step("23:50", "check_out")
        sim.walkTo("01:40", 20)
        val p = sim.plan()
        sim.require(p.done == p.total) { "day $n: ${p.done}/${p.total} done: ${p.items.filter { it.counts && it.status != ItemStatus.DONE && it.status != ItemStatus.OFF }.map { it.id }}" }
    }

    fun run() {
        // Day 1: onboarding, then the first lessons.
        sim.dayN = 1
        sim.at("06:10")
        sim.require(!sim.today().ready) { "ready before onboarding" }
        sim.company.saveRules(sim.rules, "他喜歡簡單直接的說法。", "（測試用的規章原文）")
        sim.company.rollover()
        sim.require(sim.today().ready) { "not ready after onboarding" }
        sim.require(Stages.get(sim.store)?.joinedOn == sim.day.toString()) { "not joined on day 1" }

        stageOneDay(1)
        stageOneDay(2) {
            sim.say("記帳 午餐 120")
            sim.say("幫我想一個每天的小習慣")
            sim.answer<Card.Proposal>("好")
            sim.require(sim.plan().items.none { it.id.startsWith("habit:") }) { "a habit accepted today changed today's plan" }
        }
        stageOneDay(3) {
            sim.require(sim.plan().items.any { it.id.startsWith("habit:") }) { "yesterday's habit has no plan item" }
            // A voluntary report for a closed feature: recorded, not counted, not scheduled, no violation.
            sim.say("喝完一壺了")
            sim.require(sim.item("water") == null) { "closed water got a plan item" }
            sim.require(sim.store.entries(sim.day, Kind.VIOLATION).isEmpty()) { "closed report made a violation" }
            sim.say("第一餐吃了炸雞排")
            sim.require(sim.store.entries(sim.day, Kind.VIOLATION).isEmpty()) { "closed meals made a violation" }
            sim.say("提醒我明天交作業")
        }
        stageOneDay(4) {
            sim.walkTo("09:10")
            sim.require(sim.notices.any { it.second.key.startsWith("memo:") }) { "the memo was never announced" }
        }
        for (n in 5..7) stageOneDay(n)

        // Day 8: the round is judged, stage 2 opens exactly once, and `next` alone carries the day.
        sim.dayN = 8
        morning("第 2 階段 · 第 1 天")
        val ceremonies = { sim.store.messages(2000).filter { it.role == Role.NOTE }.flatMap { it.cards }.filterIsInstance<Card.Ceremony>().filter { it.kind == "stage" } }
        sim.require(ceremonies().size == 1) { "stage ceremonies: ${ceremonies().size}" }
        sim.require(sim.plan().adjustments.any { it.what.startsWith("新開放") }) { "day 8 does not say what opened" }
        followNext(chat = false)
        sim.walkTo("01:40", 20)
        sim.require(sim.plan().done == sim.plan().total) { "day 8 not finished by following next" }

        // Day 9: words for everything; the water is left late while riding to see the chase stay quiet.
        stageTwoDay(9, chat = true, wake = "07:05", hooks = mapOf(
            "skip water" to {},
            "before 11:45" to {
                sim.walkTo("11:35")
                sim.require("我吃完了" in sim.today().chips) { "no meal chip near lunch: ${sim.today().chips}" }
                sim.say("我吃完了")
            },
            "riding" to {
                sim.walkTo("20:55")
                val water = sim.notices.filter { it.second.key == "water" && app.agency.core.base.Days.of(it.first, sim.clock.zone) == sim.day }
                sim.require(water.size >= 3) { "water was noticed ${water.size} times while late" }
                sim.require(water.drop(1).none { it.second.loud }) { "a water chase was loud while riding" }
                val gaps = water.zipWithNext { a, b -> java.time.Duration.between(a.first, b.first).toMinutes() }
                sim.require(gaps.all { it in 30..40 }) { "water chase gaps $gaps" }
                sim.say("喝完一壺了")
            },
        ))
        // Day 10: sick. Code reduces strength by one round, says so, and the reduced session completes the item.
        stageTwoDay(10, chat = false, wake = "07:05", hooks = mapOf(
            "after check-in" to {
                sim.say("我感冒了")
                sim.require(sim.plan().unwell) { "sick day is not unwell" }
                sim.require(sim.plan().adjustments.any { it.what.contains("肌力少一輪") }) { "no reduction shown" }
            },
            "22:30" to {
                val s = sim.item("task:STRENGTH")!!
                sim.require(s.detail.startsWith("3 輪")) { "sick day strength after the ride: ${s.detail}" }
            },
        ))
        stageTwoDay(11, chat = true, wake = "07:05", hooks = mapOf(
            "after check-in" to {
                sim.say("我好了")
                sim.require(sim.plan().adjustments.any { it.what.contains("肌力今天少一輪") }) { "first day back not reduced" }
            },
        ))
        // Day 12: a banned food at dinner.
        sim.foods = mapOf(MealSlot.DINNER to "炸雞排")
        stageTwoDay(12, chat = true, wake = "07:05")
        sim.foods = emptyMap()
        val banned = sim.store.entries(sim.day, Kind.VIOLATION).map { it.read<Violation>().code }
        sim.require("BANNED_FOOD" in banned) { "banned food not recorded: $banned" }
        // Day 13: a free day with a late check-in.
        sim.dayN = 13
        stageTwoDayLate()
        // Day 14: a rest day.
        stageTwoDay(14, chat = false, wake = "09:25", hooks = mapOf(
            "after check-in" to {
                sim.say("今天休息")
                sim.require(sim.item("ride:out")?.status == ItemStatus.OFF) { "rest day still schedules the ride" }
                sim.require(sim.item("water")?.detail?.contains("跑單") == false) { "rest-day water still talks of the ride" }
            },
        ))

        // Day 15 morning: the stage-2 round is judged and repeats because of the banned food.
        sim.dayN = 15
        morning("第 2 階段 · 第 1 天")
        val round = Stages.get(sim.store)!!.rounds.last()
        sim.require(round.stage == 2 && !round.passed && round.short.any { it.contains("違規") }) { "stage-2 round: $round" }
        sim.require(Stages.get(sim.store)!!.stage == 2) { "stage moved on despite the violation" }
        sim.require(ceremonies().size == 1) { "a second stage ceremony" }
        sim.require(sim.model.problems.isEmpty()) { "model problems: ${sim.model.problems}" }
    }

    private fun stageTwoDayLate() {
        // Free-day wake is 09:20; he signs in at 10:40. Before that only the wake notice may come, every 30 minutes.
        val hooks = mapOf(
            "before 10:40" to {
                sim.walkTo("10:35")
                val wake = sim.notices.filter { app.agency.core.base.Days.of(it.first, sim.clock.zone) == sim.day }
                sim.require(wake.isNotEmpty() && wake.all { it.second.key == "check_in" }) { "before check-in: ${wake.map { it.second.key }}" }
                sim.require(wake.size >= 3) { "wake notice not chased: ${wake.size}" }
            },
            "10:40" to {
                val adj = sim.plan().adjustments.map { it.what }
                sim.require(adj.any { it.contains("第一餐延後 80 分鐘") && it.contains("13:40") }) { "late check-in adjustment: $adj" }
                sim.require(sim.item("meal:FIRST")?.at == LocalTime.of(13, 40)) { "first meal at ${sim.item("meal:FIRST")?.at}" }
            },
        )
        sim.model.checkInScore = null // she asks with the five dots; his tap on the card signs him in
        stageTwoDay(13, chat = true, wake = "10:40", hooks = hooks)
        sim.model.checkInScore = 4
    }
}
