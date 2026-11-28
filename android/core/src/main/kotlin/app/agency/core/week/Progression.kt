package app.agency.core.week

import app.agency.core.base.Clock
import app.agency.core.lessons.Levels
import app.agency.core.plan.Adjustment
import app.agency.core.plan.TaskId
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.rules.TrainingRule
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Doc
import app.agency.core.store.Feel
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.Store
import app.agency.core.store.TaskDone
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Training goes up by itself and never down. Offsets live in doc `levels` ([Levels.trainingOffsets]), never in `Rules`; the
 * numbers actually used are `rules.training + offsets` ([effective]).
 */
object Progression {
    const val PUSH_UPS = "pushUps"
    const val SQUATS = "squats"
    const val PLANK = "plankSeconds"
    const val WINDOW_DAYS = 14
    const val HOLD_DAYS = 14L
    const val EASY_NEEDED = 2
    const val PROTEIN_PCT = 90
    private const val UNWELL_LOOKBACK = 60L

    fun effective(rules: Rules, levels: Levels): TrainingRule {
        val o = levels.trainingOffsets
        val t = rules.training
        return t.copy(
            pushUps = t.pushUps + (o[PUSH_UPS] ?: 0),
            squats = t.squats + (o[SQUATS] ?: 0),
            plankSeconds = t.plankSeconds + (o[PLANK] ?: 0),
        )
    }

    /**
     * Raises one load by a step when the last 14 closed days (up to yesterday) earned it, and returns what changed for [day]'s
     * "what changed today"; null when nothing was raised. Earned means all of:
     *  - strength was open for the whole window and was done on every one of the 14 days, at full rounds (a record without a
     *    round count does not prove it) and not reduced;
     *  - he said EASY on at least two of those strength records;
     *  - he was never sick or injured in the window (nor at its start);
     *  - his average daily protein is at least 90 % of the target.
     * Then the next lever in rotation (push-ups +2, squats +2, plank +10 s) goes up and nothing may rise for 14 days.
     */
    fun maybeRaise(store: Store, clock: Clock, rules: Rules, day: LocalDate): Adjustment? {
        val stage = store.readDoc<StageState>(Doc.STAGE) ?: return null
        val t = rules.training
        if (!Feature.STRENGTH.openAt(stage.stage) || !RulesCheck.ready(rules, Feature.STRENGTH)) return null
        if (rules.diet.proteinG <= 0) return null
        runCatching { LocalDate.parse(stage.lastRaiseOn) }.getOrNull()?.let {
            if (ChronoUnit.DAYS.between(it, day) < HOLD_DAYS) return null
        }
        val from = day.minusDays(WINDOW_DAYS.toLong())
        val to = day.minusDays(1)
        val opened = app.agency.core.day.StageDays.openedOn(stage, Feature.STRENGTH.stage) ?: return null
        if (opened.isAfter(from)) return null

        val entries = store.entries(from, to)
        val strength = entries.filter { it.kind == Kind.TASK }.mapNotNull { e ->
            runCatching { e.read<TaskDone>() }.getOrNull()?.takeIf { it.task == TaskId.STRENGTH.name }?.let { e.day to it }
        }
        for (i in 0 until WINDOW_DAYS) {
            val d = from.plusDays(i.toLong())
            val ok = strength.any { (day2, r) -> day2 == d && (r.rounds ?: 0) >= t.strengthRounds && !r.reduced }
            if (!ok) return null
        }
        if (strength.count { it.second.feel == Feel.EASY } < EASY_NEEDED) return null
        if (wasUnwell(store, from, to)) return null
        val protein = entries.filter { it.kind == Kind.MEAL }.sumOf { e ->
            runCatching { e.read<Meal>() }.getOrNull()?.items?.sumOf { it.proteinG } ?: 0.0
        }
        if (protein / WINDOW_DAYS * 100 < rules.diet.proteinG * PROTEIN_PCT) return null

        val levels = store.readDoc<Levels>(Doc.LEVELS) ?: Levels()
        val o = levels.trainingOffsets
        val raises = (o[PUSH_UPS] ?: 0) / 2 + (o[SQUATS] ?: 0) / 2 + (o[PLANK] ?: 0) / 10
        val (key, step, name, unit) = when (raises % 3) {
            0 -> Lever(PUSH_UPS, 2, "伏地挺身", "下")
            1 -> Lever(SQUATS, 2, "深蹲", "下")
            else -> Lever(PLANK, 10, "棒式", "秒")
        }
        val raised = (o[key] ?: 0) + step
        store.writeDoc(Doc.LEVELS, levels.copy(trainingOffsets = o + (key to raised)))
        store.writeDoc(Doc.STAGE, stage.copy(lastRaiseOn = day.toString()))
        val total = effective(rules, levels.copy(trainingOffsets = o + (key to raised)))
        val number = when (key) { PUSH_UPS -> total.pushUps; SQUATS -> total.squats; else -> total.plankSeconds }
        return Adjustment(
            "$name 加 $step $unit，現在是 $number $unit",
            "最近兩週肌力都做滿輪、有輕鬆的感覺、沒有生病，蛋白質也吃夠，所以往上加一點",
        )
    }

    private data class Lever(val key: String, val step: Int, val name: String, val unit: String)

    /** True when he was sick or injured on any day of [from]..[to], or was already unwell when the window began. */
    private fun wasUnwell(store: Store, from: LocalDate, to: LocalDate): Boolean {
        var unwell = false
        for (e in store.entries(from.minusDays(UNWELL_LOOKBACK), to, Kind.STATUS)) {
            val type = runCatching { e.read<Status>().type }.getOrNull() ?: continue
            val inside = !e.day.isBefore(from)
            when (type) {
                StatusType.SICK, StatusType.INJURED -> if (inside) return true else unwell = true
                StatusType.RECOVERED -> if (!inside) unwell = false
                else -> Unit
            }
        }
        return unwell
    }
}
