package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.model.CompanyRules
import app.agency.core.model.Profile
import app.agency.core.persona.PromptInput
import app.agency.core.rules.CoachRules
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What the 練習生證 (plan v3 §17, arch T10) shows, all computed by code: the company and his number, 藝名 and 代表色, the
 * level, 入社第 N 天 (the join day is day 1), today's stickers, only the next weight goal, what is left today, today's
 * breaches and how many 入社資料 items are missing. [photoMediaId] is the 入社建檔 front photo (the card decrypts it into
 * memory only).
 */
data class IdolCardFacts(
    val company: String,
    val stageName: String?,
    val memberColor: String?,
    val traineeNo: String,
    val level: String,
    val joinDay: Long?,
    val stickers: List<Sticker>,
    val nextGoalKg: Double?,
    val kcalLeft: Int,
    val proteinLeftG: Int,
    val breachesToday: Int,
    val baselineMissing: Int,
    val photoMediaId: String?,
    /** Today's plan with status (the card's schedule reads the day planner, arch T11). */
    val slots: List<SlotState> = emptyList(),
    /** Today's meals without a photo (「未證實」, T15), by what they were: 午餐, 晚餐, 加餐. */
    val unverified: List<String> = emptyList(),
    /** Today's water and its target, in half bottles of his 1 L bottle (T17). */
    val waterHalves: Int = 0,
    val waterTargetHalves: Int = 0,
    /** Polaroids on the photocard wall (L11, one a day for the first core or strength done). */
    val photocards: Int = 0,
    /** 分週上線: the rollout week and what is open (the screens hide the rest). */
    val rolloutWeek: Int = app.agency.core.schedule.RolloutSpec.LAST,
    val open: Set<app.agency.core.schedule.Feature> = app.agency.core.schedule.Feature.entries.toSet(),
    /** 外送 this Mon–Sun week so far and his weekly target (null: not open, or no target set). */
    val incomeWeek: Int? = null,
    val incomeTarget: Int? = null,
) {
    /** What comes next: the first slot still open or later, in time order. */
    val next: SlotState? get() = slots.firstOrNull { it.status == SlotStatus.OPEN || it.status == SlotStatus.LATER }

    /** Required slots due and missing. */
    val gaps: List<SlotState> get() = slots.filter { it.isGap }
}

object IdolCard {
    /** A meal that belongs to no slot. */
    const val EXTRA = "加餐"

    /** His number on the card: 「001」 until the profile says otherwise (one trainee). */
    const val DEFAULT_NO = "001"

    /** No goal below this is ever shown or pushed (BMI 18.5 for him; plan v3 §5). */
    const val GOAL_FLOOR_KG = 52.0

    fun joinDay(p: Profile, today: LocalDate): Long? {
        val join = p.joinDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return (ChronoUnit.DAYS.between(join, today) + 1).takeIf { it >= 1 }
    }

    fun traineeNo(p: Profile): String = p.traineeNo?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.padStart(3, '0') ?: DEFAULT_NO

    /** Only the next step goal below his current weight, never one under [GOAL_FLOOR_KG]; null when none is left. */
    fun nextGoal(rules: CompanyRules, currentKg: Double?): Double? {
        val goals = rules.goalsKg.filter { it >= GOAL_FLOOR_KG }
        return if (currentKg == null) goals.maxOrNull() else CoachRules.nextGoal(goals, currentKg)
    }

    fun build(
        profile: Profile,
        rules: CompanyRules,
        today: TodayRecords,
        todayDay: LocalDate,
        currentKg: Double?,
        baselineMissing: Int,
        photoMediaId: String?,
        stickers: List<Sticker> = Stickers.fromRecords(today),
        slots: List<SlotState> = emptyList(),
        waterTargetHalves: Int = 0,
    ): IdolCardFacts {
        val labels = slots.associate { it.key.name to it.slot.label }
        val eaten = today.meals.sumOf { it.kcal }
        val protein = today.meals.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        return IdolCardFacts(
            company = profile.companyName ?: PromptInput.DEFAULT_COMPANY,
            stageName = profile.stageName?.takeIf { it.isNotBlank() },
            memberColor = profile.memberColor,
            traineeNo = traineeNo(profile),
            level = profile.level,
            joinDay = joinDay(profile, todayDay),
            stickers = stickers,
            nextGoalKg = nextGoal(rules, currentKg),
            kcalLeft = CoachRules.remainingKcal(rules.quotaKcal, eaten),
            proteinLeftG = CoachRules.remainingProtein(rules.proteinG, protein),
            breachesToday = today.meals.sumOf { it.breaches.size },
            baselineMissing = baselineMissing,
            photoMediaId = photoMediaId,
            slots = slots,
            waterHalves = today.water.sumOf { it.halfBottles },
            waterTargetHalves = waterTargetHalves,
            unverified = today.meals.filter { !it.verified && it.items.isNotEmpty() }.map { m -> m.slotKey?.let { labels[it] ?: app.agency.core.schedule.SlotKey.parse(it)?.zh } ?: EXTRA },
        )
    }
}
