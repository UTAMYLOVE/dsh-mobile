package app.agency.core.stage

import app.agency.core.store.Doc
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the staged rollout looks like from outside: the label, what is open, what opens next, and joining on day 1. */
object Stages {
    /** 「第 2 階段 · 第 3 天」: the day inside the current round (1..7), never a calendar week. */
    fun label(state: StageState, today: LocalDate): String =
        "第 ${state.stage} 階段 · 第 ${dayInRound(state, today)} 天"

    /** Day inside the current round, 1-based, at most [StageState.ROUND_DAYS] (day 8 is judged on its first activity). */
    fun dayInRound(state: StageState, today: LocalDate): Int {
        val start = runCatching { LocalDate.parse(state.startedOn) }.getOrNull() ?: return 1
        return (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, StageState.ROUND_DAYS)
    }

    /** Every feature that is open at this stage (those that open at stage 0 are always in). */
    fun open(state: StageState): Set<Feature> = Feature.values().filter { it.openAt(state.stage) }.toSet()

    /** What the next stage will open, in declaration order; empty at the last stage. */
    fun opensNext(state: StageState): List<Feature> =
        if (state.stage >= StageState.LAST_STAGE) emptyList() else Feature.values().filter { it.stage == state.stage + 1 }

    /** What stage [stage] newly opens. */
    fun opensAt(stage: Int): List<Feature> = Feature.values().filter { it.stage == stage }

    /** The stored stage document, or null before he has joined. */
    fun get(store: Store): StageState? = store.readDoc<StageState>(Doc.STAGE)

    /** Creates the stage document on day 1 (stage 1, round starting [today]); an existing one is returned untouched. */
    fun join(store: Store, today: LocalDate): StageState = store.tx {
        get(store) ?: StageState(joinedOn = today.toString()).also { store.writeDoc(Doc.STAGE, it) }
    }
}
