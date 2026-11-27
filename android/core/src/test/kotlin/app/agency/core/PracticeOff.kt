package app.agency.core

import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.schedule.Feature
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.RolloutSpec
import app.agency.core.store.InMemoryAgencyStore
import kotlinx.coroutines.runBlocking

/**
 * The tests of the features before the practice items (batches 1–B) run with everything open except the practice items of
 * rollout weeks 3 and 4 (their table week is 5, past the last week), so their plans and chases stay what they test.
 */
object PracticeOff {
    val FEATURES = setOf(
        Feature.MORNING_STRETCH, Feature.VOCAL_BASICS, Feature.POSTURE, Feature.SUNSCREEN, Feature.DANCE, Feature.WEEKLY_EVAL,
        Feature.SURPRISE, Feature.THEME_WEEK, Feature.MONTHLY_EVAL, Feature.HABITS, Feature.SCENES,
    )
    val ROLLOUT = RolloutSpec(week = RolloutSpec.LAST, table = RolloutSpec().table + FEATURES.associate { it.name to RolloutSpec.LAST + 1 })
    val SPECS = PlanSpecs(rollout = ROLLOUT)
}

fun InMemoryAgencyStore.practiceOff(): InMemoryAgencyStore = also {
    runBlocking { rules.putSection(PlanSectionKey.ROLLOUT, PlanSpecs(rollout = PracticeOff.ROLLOUT).json(PlanSectionKey.ROLLOUT), PlanSection.BY_MIGRATION, 0) }
}
