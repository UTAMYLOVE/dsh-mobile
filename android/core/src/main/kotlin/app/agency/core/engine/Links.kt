package app.agency.core.engine

import app.agency.core.model.RoleKey
import app.agency.core.model.TrainingKind
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayFlag
import app.agency.core.schedule.DayType
import app.agency.core.schedule.Reduction
import app.agency.core.schedule.RuleEngineTime
import app.agency.core.schedule.SlotKey
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * The links of agency-links §3 that have a data source in batch 2, and the ones waiting for theirs ([Pending]). Each is pure:
 * signal + day state + context → effects. Numbers are code's; lines are code templates (the manager's voice may word them
 * later, never decide them).
 */
object Links {
    fun all(): List<Link> = listOf(
        L1WakeLate, L2Illness, L3Gentle, NoReduction, L4HungryMorning, L5NextMeal, L6Floor, L7CheatMeal, L8Riding, L9RideEnd, L10Water,
        L11Completion, L12Chase, L13Violation, L14Discuss, L15Progression, L16Spend, L17Grooming, L18NoClassDay, L19Review, L20Week,
        L21WeightStop, L22TiredRiding, L23WeightTrend, L24Lessons, L25Rollout, L26Interview, L27Attendance, L28Weather, L29Caffeine,
        L30MoodCare, L31MonthInterview, L32LevelUp, L33Habit,
    ) + Pending.all

    /** Minutes of [at] from 00:00 of the coach day's date (after midnight is past 24 × 60, like a slot's minute). */
    fun minuteOf(day: java.time.LocalDate, at: Long, zone: java.time.ZoneId): Int =
        Duration.between(day.atStartOfDay(zone).toInstant(), Instant.ofEpochMilli(at)).toMinutes().toInt()

    private fun hhmm(minute: Int) = DayClock.hhmm(minute)

    /** L1: woke ≥ 60 minutes late → the first meal follows (at most to 3 h before the next meal); sleep time stays. */
    object L1WakeLate : Link {
        override val feature get() = app.agency.core.schedule.Feature.SIGN
        override val id = "L1"
        override val zh = "起床晚了，第一餐跟著順延"
        override val dailyCap = 1
        const val LATE_MIN = 60

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.WokeUp) return emptyList()
            val plan = state.plan
            val wake = plan.slot(SlotKey.WAKE) ?: return emptyList()
            val late = Duration.between(RuleEngineTime.at(plan.day, wake.minute, ctx.zone), Instant.ofEpochMilli(signal.at)).toMinutes().toInt()
            if (late < LATE_MIN) return emptyList()
            val first = plan.slot(plan.firstMeal) ?: return emptyList()
            val next = plan.slot(SlotKey.DINNER) ?: return emptyList()
            val to = minOf(first.minute + late, next.minute - ctx.specs.meals.firstMealGapMin)
            if (to <= first.minute) return emptyList()
            return listOf(
                Effect.AdjustToday(
                    DayAdjust.Move(first.key, to),
                    "因為你晚起 $late 分鐘，所以${first.label}延到 ${hhmm(to)}；睡覺時間不變。",
                ),
            )
        }
    }

    /**
     * L2 (agency-links §3b): ill or hurt is the only way training gets lighter, decided by code. Unclear 「不舒服」 → one
     * question; 生病/受傷 → a today-only proposal (no core, no strength; stretch stays, avoiding an injured part) that needs
     * his yes; no answer → the plan stands. Three days in a row → see a doctor (recorded).
     */
    object L2Illness : Link {
        override val id = "L2"
        override val zh = "生病或受傷：問一題，提今天的調整"
        override val dailyCap = 3
        const val ASK = "哪裡不舒服？是發燒、感冒、腸胃不舒服，還是受傷？"
        const val PAIN_STOP = "刺痛或關節痛算受傷：現在馬上停下來，不要硬做。痠不算，刺痛才算。"
        val RED_FLAGS = setOf("頭暈", "暈")

        /** The first day after an illness: the rounds today (owner 2026-11-26 「病好第一天肌力 2 輪，隔天全量」). */
        const val RECOVERY_ROUNDS = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal is Signal.DayStarted) {
                val yesterday = state.day.minusDays(1)
                if (yesterday !in state.illDays || state.day in state.illDays || state.plan.slot(SlotKey.STRENGTH) == null) return emptyList()
                if (state.plan.strengthRounds <= RECOVERY_ROUNDS) return emptyList()
                return listOf(
                    Effect.AdjustToday(DayAdjust.Recovery(RECOVERY_ROUNDS), "因為昨天生病或受傷，所以今天是病好第一天：肌力先做 $RECOVERY_ROUNDS 輪，明天全量。"),
                )
            }
            if (signal !is Signal.Unwell) return emptyList()
            if (signal.kind == IllKind.UNKNOWN) return if (state.ill == null) listOf(Effect.Say(ASK)) else emptyList()
            val out = mutableListOf<Effect>()
            // What is still owed today (T18: the training records): a core or rounds already done need no change.
            val owed = listOfNotNull(
                "核心".takeIf { state.plan.slot(SlotKey.CORE) != null && TrainingKind.CORE7 !in state.training },
                "肌力".takeIf { state.plan.slot(SlotKey.STRENGTH) != null && TrainingKind.STRENGTH !in state.training },
                "體能".takeIf { state.plan.slot(SlotKey.CARDIO) != null && TrainingKind.CARDIO !in state.training },
            )
            // A sharp pain or joint pain (owner 2026-11-26) is an injury: stop the move now, before anything else.
            if (app.agency.core.brain.handlers.BodyWords.isPain(signal.word)) out += Effect.Say(PAIN_STOP)
            if (!state.reductionProposed && owed.isNotEmpty()) {
                val reduction = if (signal.kind == IllKind.INJURY) Reduction.INJURY else Reduction.SICK
                val what = if (owed.size > 2) owed.dropLast(1).joinToString("、") + "和" + owed.last() else owed.joinToString("和")
                val change = if (reduction == Reduction.INJURY) "今天${what}先停，伸展照做，避開受傷的地方。" else "今天${what}先停，伸展照做十分鐘。"
                out += Effect.Say("先照顧身體。${signal.word}的話，今天的訓練我提一個調整：${change}你說「好」才改；不回就照原本的表。")
                out += Effect.Proposal(todayOnly = true, change = change, because = "因為你說${signal.word}，所以只改今天：$change", reduction = reduction)
            }
            if (signal.word in RED_FLAGS) out += Effect.Say("頭暈就先坐下來，正常吃東西、喝水；一直暈或更嚴重就去看醫生。")
            if (state.illDaysInRow >= 3) {
                out += Effect.Say("已經連三天不舒服了，去看醫生。")
                out += Effect.Record("see_doctor", "days=${state.illDaysInRow}")
            }
            return out
        }
    }

    /** L3: ill or low → the manager speaks gently today (只提醒); training stays as planned unless L2 applies. */
    object L3Gentle : Link {
        override val id = "L3"
        override val zh = "生病或心情低落：小葵姐放軟口氣"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.Unwell -> if (signal.kind != IllKind.UNKNOWN) listOf(Effect.Record("gentle", signal.kind.name)) else emptyList()
            is Signal.MoodLow -> listOf(Effect.Record("gentle", "MOOD"))
            // A low morning score (T19): gentle today, the plan stays.
            is Signal.ConditionScored -> if (signal.ill == null && signal.score <= DayState.LOW_SCORE) listOf(Effect.Record("gentle", "SCORE")) else emptyList()
            else -> emptyList()
        }
    }

    /** agency-links §3b: tired, sore, low or unwilling is no reason: 「今天照表」, however often he asks. */
    object NoReduction : Link {
        override val id = "L3b"
        override val zh = "沒生病就照表"
        override val dailyCap = 50
        const val LINE = "今天照表。只有生病或受傷才調整訓練。"

        /** A sick day already has its change: the stretch is the minimum (§3b.3), no more is taken off. */
        const val SICK_LINE = "今天已經調整過了：伸展照做，這是最少的。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.ReduceAsked) return emptyList()
            val line = if (state.ill != null && state.reductionProposed) SICK_LINE else LINE
            return listOf(Effect.Say(line), Effect.Record("no_reduction", "times=${state.reduceAskedTimes + 1}"))
        }
    }

    /** L4: 「早上很餓」 → the 豆漿 to the morning; the chicken breast stays at 21:00. */
    object L4HungryMorning : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L4"
        override val zh = "早上很餓：豆漿換到早上"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.HungryMorning) {
                listOf(Effect.AdjustToday(DayAdjust.SoyMorning, "因為你說早上很餓，所以豆漿挪到早上喝；雞胸照樣 21:00 跑單途中吃。"))
            } else {
                emptyList()
            }
    }

    /**
     * L5: a meal is in → when what is left today is less than the next meal usually is, that meal gets a smaller budget
     * (今天微調, [DayAdjust.MealBudget]): the suggestion and the nearby search follow it. Never below the floor of the day, never a
     * skipped meal, and nothing when the day is already over (then it is 「明天回到 1700」, not less food).
     */
    object L5NextMeal : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L5"
        override val zh = "每餐熱量：下一餐跟著調整（不低於 1200）"
        override val dailyCap = 6

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.MealLogged) return emptyList()
            val minute = minuteOf(state.plan.day, signal.at, ctx.zone)
            val b = app.agency.core.rules.MealBudget.next(state.plan, ctx.rules, state.kcal, state.proteinG, state.mealSlots, minute) ?: return emptyList()
            if (!b.smaller || state.plan.budgets[b.slot]?.let { it <= b.kcal } == true) return emptyList()
            val label = state.plan.slot(b.slot)?.label ?: b.slot.zh
            return listOf(
                Effect.AdjustToday(
                    DayAdjust.MealBudget(b.slot, b.kcal),
                    "因為今天到現在吃了 ${state.kcal} 大卡，所以${label}建議 ${b.kcal} 大卡以內、蛋白質至少 ${b.proteinG} 克；照樣要吃，不能跳過。",
                ),
            )
        }
    }

    /** L6: by dinner (or after L9) the day is under the floor → eat enough (never less). */
    object L6Floor : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L6"
        override val zh = "到晚餐還不到最低熱量：提醒吃夠"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            val check = signal is Signal.FloorCheck || (signal is Signal.MealLogged && signal.slot == SlotKey.DINNER)
            if (!check || state.kcal >= ctx.rules.floorKcal) return emptyList()
            return listOf(
                Effect.Notify(
                    "floor", "今天才吃 ${state.kcal} 大卡，不到 ${ctx.rules.floorKcal}。要吃夠，不准少吃；吃了拍照回報。", NotifyKind.REMINDER,
                ),
            )
        }
    }

    /**
     * L7: the declared 休假餐 (closed until 2026-12-31, back from 2027-01-01: [app.agency.core.rules.RulesBook.cheatOpen]; the
     * declaration itself refuses before that). Banned food in that meal is no breach (the meal handler reads the declaration);
     * it still counts in the day and under the single-meal cap, the other meals of the day are as always, and the second-pass
     * judge still reads the day's replies. Recorded here.
     */
    object L7CheatMeal : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L7"
        override val zh = "休假餐：那一餐的禁止食物不算違規，熱量照算"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when {
            signal is Signal.CheatDeclared -> listOf(Effect.Record("cheat_declared", "${signal.day} ${signal.slot.name}"))
            signal is Signal.MealLogged && signal.cheat -> listOf(Effect.Record("cheat_meal", signal.slot?.name.orEmpty()))
            else -> emptyList()
        }
    }

    /**
     * L8: out on the run → reminders sound once (no pop-ups while riding). On a delivery day's start, a reminder to drink
     * before leaving (little while riding).
     */
    object L8Riding : Link {
        override val feature get() = app.agency.core.schedule.Feature.RIDE
        override val id = "L8"
        override val zh = "跑單：提醒只響一聲，出門前喝水，收工時間到了提醒"
        override val dailyCap = 3
        const val BEFORE_MIN = 20

        /** The 收工 reminder comes this long before the block's latest start (it follows the bedtime, 睡覺時間往前調). */
        const val HOME_BEFORE_MIN = 15

        /**
         * 收工時間 (owner 2026-11-26): on a delivery day, before the training block must start, a warning (it reaches him on the
         * run) to head home: the block's latest start and the latest sign-out, from today's plan. Not posted once he is home.
         */
        fun homeReminder(state: DayState, ctx: LinkContext): Effect.Notify? {
            val core = state.plan.slot(SlotKey.CORE) ?: state.plan.slot(SlotKey.STRETCH) ?: return null
            val sleep = state.plan.slot(SlotKey.SIGN_OUT) ?: return null
            val at = core.minute - HOME_BEFORE_MIN
            return Effect.Notify(
                "ride-home", "收工時間到了：${hhmm(core.minute)} 前回家開始訓練，最晚 ${sleep.time} 簽退。單少也不要拖，睡眠不能少。", NotifyKind.WARNING,
                at = RuleEngineTime.at(state.plan.day, at, ctx.zone).toEpochMilli(), unlessHome = true,
            )
        }

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.RideStart -> if (DayFlag.RIDING_ONE_SOUND in state.flags) emptyList() else listOf(
                Effect.AdjustToday(DayAdjust.Flag(DayFlag.RIDING_ONE_SOUND), "因為你出門跑單了，所以提醒改成只響一聲，不會一直跳出來。"),
            )
            // Skipped when the day's water is in by then (T17: nothing left to drink before the run).
            is Signal.DayStarted -> state.plan.slot(SlotKey.RIDE_OUT)?.let { out ->
                listOfNotNull(
                    Effect.Notify(
                        "preride", "快出門了：出門前先喝水，路上只能小口喝。", NotifyKind.REMINDER,
                        at = RuleEngineTime.at(state.plan.day, out.minute - BEFORE_MIN, ctx.zone).toEpochMilli(),
                        unlessWaterHalves = WaterPlan.target(state.day, ctx.specs.water, ctx.joinDate),
                    ),
                    homeReminder(state, ctx),
                )
            } ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * L10: water behind → one more reminder in the afternoon gap: at 15:00 less than half of the day's target (only if he is
     * not out on the run by then); on a day without a run, the bottle is due by 20:00. Never 「灌」: in sips, before the run
     * (owner: he cannot drink much while riding). The reminder is not posted when the water is in by then. Only reminds.
     */
    object L10Water : Link {
        override val feature get() = app.agency.core.schedule.Feature.WATER
        override val id = "L10"
        override val zh = "喝水落後：下午加一次提醒，跑單前不灌"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.DayStarted) return emptyList()
            val plan = state.plan
            val target = WaterPlan.target(state.day, ctx.specs.water, ctx.joinDate)
            val deadline = WaterPlan.deadline(plan)
            val out = mutableListOf<Effect>()
            val wake = plan.slot(SlotKey.WAKE)?.minute ?: 0
            if (WaterPlan.HALF_CHECK_MINUTE in (wake + 60) until deadline) {
                out += Effect.Notify(
                    "water-half",
                    "下午三點了，水還不到一半（今天要喝 ${WaterPlan.bottles(target)}）。現在分幾口喝，出門前喝完，不要一次灌。",
                    NotifyKind.REMINDER, at = RuleEngineTime.at(plan.day, WaterPlan.HALF_CHECK_MINUTE, ctx.zone).toEpochMilli(),
                    unlessWaterHalves = WaterPlan.half(target),
                )
            }
            if (plan.slot(SlotKey.RIDE_OUT) == null) {
                out += Effect.Notify(
                    "water-goal", "今天的水（${WaterPlan.bottles(target)}）還沒喝完，睡前不要猛喝，現在分幾次喝。", NotifyKind.REMINDER,
                    at = RuleEngineTime.at(plan.day, deadline, ctx.zone).toEpochMilli(), unlessWaterHalves = target,
                )
            }
            return out
        }
    }

    /** L9: home from the run with the 21:00 portion eaten → no 宵夜 tonight, then L6 checks the floor. */
    object L9RideEnd : Link {
        override val feature get() = app.agency.core.schedule.Feature.RIDE
        override val id = "L9"
        override val zh = "收工且吃了 21:00 那份：宵夜取消，再看最低熱量"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.RideEnd || !state.rideMealEaten) return emptyList()
            return listOf(
                Effect.AdjustToday(DayAdjust.Flag(DayFlag.LATE_SNACK_OFF), "因為 21:00 那份吃了，所以今天不吃宵夜。"),
                Effect.Chain(Signal.FloorCheck("floor:${signal.id}", signal.at)),
            )
        }
    }

    /**
     * L11: the first big completion of the day (the core 7 minutes or the strength rounds) → one photocard on the wall (拍立得照片牆)
     * and, on a day without a breach, the one 撒嬌 of the day (a code line of the manager; never a second one: the daily cap and
     * [DayState.sweetToday]). Only records: nothing changes in the plan.
     */
    object L11Completion : Link {
        override val feature get() = app.agency.core.schedule.Feature.CORE
        override val id = "L11"
        override val zh = "核心或肌力當天第一次完成：撒嬌一次、照片牆加一張"
        override val dailyCap = 1
        const val SWEET = "sweet"
        const val PHOTOCARD = "photocard"
        val BIG = setOf(TrainingKind.CORE7, TrainingKind.STRENGTH)

        fun sweetLine(kind: TrainingKind, rounds: Int?): String = when (kind) {
            TrainingKind.CORE7 -> "核心七分鐘做完了，好棒，姐姐都看到了喔。今天的拍立得貼上照片牆了。"
            else -> "${zhNumber(rounds ?: 3)}輪全部做完了，好厲害，姐姐都看到了喔。今天的拍立得貼上照片牆了。去伸展，做完跟我說。"
        }

        const val PLAIN = "今天的拍立得貼上照片牆了。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.TrainingDone || signal.kind !in BIG || state.photocardsToday > 0) return emptyList()
            val out = mutableListOf<Effect>(Effect.Record(PHOTOCARD, signal.kind.name))
            if (state.breaches == 0 && state.sweetToday < ToneDecider.MAX_SWEET_PER_DAY) {
                out += Effect.Record(SWEET, signal.kind.name)
                out += Effect.Say(sweetLine(signal.kind, signal.rounds))
            } else {
                out += Effect.Say(PLAIN)
            }
            return out
        }
    }

    /** 3 → 三 (the rounds, at most a few). */
    fun zhNumber(n: Int): String = listOf("零", "一", "兩", "三", "四", "五", "六", "七", "八", "九", "十").getOrElse(n) { n.toString() }

    /** L12: something due is not reported → a reminder every 30 minutes until he reports or goes to bed; once while riding. */
    object L12Chase : Link {
        override val id = "L12"
        override val zh = "沒回報：每 30 分鐘催一次，跑單中只響一聲"
        override val dailyCap = 500
        const val SHOWN = 3

        /** The latest departure passed without 「出門跑單」 (owner 2026-11-27): the chase says what to do before leaving. */
        /** 突擊檢查 (定稿 §11): ten minutes, a photo, no warning before. */
        const val SURPRISE = "突擊檢查！十分鐘內拍照交上來，按首頁的「突擊檢查」。"

        const val RIDE_LATE = "最晚出門的時間到了：擦防曬、水喝完，出門前按「出門跑單」。想睡就不准騎車，先跟我說。"

        fun text(keys: List<SlotKey>, index: Int): String {
            val names = keys.map { it.zh }
            val head = when {
                keys == listOf(SlotKey.RIDE_OUT) -> RIDE_LATE
                SlotKey.SURPRISE_CHECK in keys -> SURPRISE
                index == 0 && keys.size == 1 -> "${names[0]}時間到了，做完回報。"
                else -> "還缺：${names.take(SHOWN).joinToString("、")}。"
            }
            val more = if (names.size > SHOWN) "還有 ${names.size - SHOWN} 項。" else ""
            return head + more
        }

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.GapDue && signal.keys.isNotEmpty()) {
                listOf(Effect.Notify("gaps", text(signal.keys, signal.index), NotifyKind.GAP, soundOnce = state.riding))
            } else {
                emptyList()
            }
    }

    /**
     * L13: a meal broke 公司規章 (the banned list of the rules, fried food included, outside a declared 休假餐; the single-meal
     * cap; the weekly zero-calorie limit; 即食雞胸, 統一高纖豆漿, 給力盒子, 全家烤雞三明治, 麥當勞嫩煎雞腿堡／藜麥烤雞沙拉 never are)
     * → recorded for the evaluation, and the manager says so coolly: what he did and that the next meal goes to plan. Never
     * 「明天少吃補回來」. A breach on the day turns the second-pass judge on for every reply of it (the day's `breachToday`).
     */
    object L13Violation : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L13"
        override val zh = "違規：記進評鑑，小葵姐指出"
        override val dailyCap = 10

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.MealLogged || signal.breaches.isEmpty()) return emptyList()
            val what = signal.breaches.map { app.agency.core.rules.Breach.zh(it) }.distinct().joinToString("、")
            return listOf(
                Effect.Record("violation", signal.breaches.joinToString(",")),
                Effect.Say("記一次違規：$what。今天第 ${state.breaches} 次，會算進評鑑。下一餐照計畫。"),
            )
        }
    }

    /**
     * L14: one required item ended undone three coach days in a row → the manager opens 討論 with one question to find the
     * cause (code-written, with chips); a change to the plan comes only as a proposal he says yes to. Once a day.
     */
    object L14Discuss : Link {
        override val id = "L14"
        override val zh = "同一項連三天沒做：開討論"
        override val dailyCap = 1
        const val DAYS = 3
        val OPTIONS = listOf("時間排不進去", "忘記了", "身體不舒服", "其他原因")

        fun question(key: SlotKey) = "${key.zh}已經連三天沒做到了。我要先弄清楚原因：是哪裡卡住了？"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.DayMissed || signal.day != state.day.minusDays(1).toString()) return emptyList()
            val key = signal.keys.sortedBy { it.ordinal }.firstOrNull { (state.missedInRow[it] ?: 0) >= DAYS } ?: return emptyList()
            return listOf(Effect.Say(question(key), discuss = true, options = OPTIONS), Effect.Record("discuss", key.name))
        }
    }

    /**
     * L15 訓練升級 (owner 2026-11-22 甲, 2026-11-26): AUTOMATIC once truly stable ([Progression.stable]: two weeks of full rounds
     * with room to spare, 「輕鬆」 twice, no illness, protein on five of seven days, two weeks after the last step) → one item
     * up one step, the ladders in turn (strength, core, cardio), with its 「因為…所以…」; protein is to be eaten in full on the
     * days after. Two sessions in a row not in full after a step: it stays at this step (never back down). Answers a strength
     * report.
     */
    object L15Progression : Link {
        override val feature get() = app.agency.core.schedule.Feature.STRENGTH
        override val id = "L15"
        override val zh = "訓練真的穩定了：自動加一級（一次一項，加完兩週不再加）"
        override val dailyCap = 1
        const val HOLD = "加量之後連兩次沒做滿：停在這一級，不退回，先把這一級做穩。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.TrainingDone || signal.kind != TrainingKind.STRENGTH) return emptyList()
            val t = ctx.specs.training
            val last = t.lastStepDay?.let { java.time.LocalDate.parse(it) }
            if (!signal.full) {
                val after = state.strengthSessions.filterKeys { d -> last != null && d.isAfter(last.minusDays(1)) }.toSortedMap()
                val tail = after.values.toList().takeLast(2)
                return if (last != null && tail.size == 2 && tail.none { it.full }) listOf(Effect.Say(HOLD, role = RoleKey.BODY), Effect.Record("hold", t.progressionStep.toString())) else emptyList()
            }
            if (!signal.easy || state.pendingProgression) return emptyList()
            val stable = Progression.stable(
                state.day,
                Progression.Stability(state.strengthSessions, state.missedStrengthDays, state.illDays, state.easySaidDays, state.proteinByDay, ctx.rules.proteinG),
                last,
            )
            if (!stable) return emptyList()
            val ladder = Progression.nextLadder(t, ctx.specs.open(app.agency.core.schedule.Feature.CARDIO)) ?: return emptyList()
            val next = Progression.up(t, ladder, state.day)
            val change = Progression.change(ladder, next)
            val because = "因為這兩週的肌力每次都做滿、最後一輪還能多做 ${Progression.EASY_EXTRA} 下以上，你也說了輕鬆、沒有生病、蛋白質有吃夠，" +
                "所以自動加一級（一次一項，加完兩週內不再加）：$change"
            return listOf(
                Effect.Progress(ladder.name, because),
                Effect.Say("$because 接下來兩週蛋白質一定要吃夠 ${ctx.rules.proteinG} 克；做不完就停在這一級，不退回。", role = RoleKey.BODY),
            )
        }
    }

    /**
     * L16: money he spent on food (a price he wrote with a meal) comes out of 生活費; 形象用品 out of 娛樂費. Recorded only:
     * 記帳 and its buckets are stage 6, so there is no balance to warn about yet.
     */
    object L16Spend : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L16"
        override val zh = "花費：外食從生活費扣，形象用品從娛樂費扣"
        override val dailyCap = 30

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.SpendLogged && signal.amount > 0) {
                listOf(Effect.Record("spend", (if (signal.image) "娛樂費 " else "生活費 ") + signal.amount))
            } else {
                emptyList()
            }
    }

    /** L17: grooming cycles → reminders in today's free time (shave at sign-in). */
    object L17Grooming : Link {
        override val feature get() = app.agency.core.schedule.Feature.GROOMING
        override val id = "L17"
        override val zh = "儀容到期：排進空檔提醒"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            val day = state.day
            fun since(i: GroomingItem): Long? = state.groomingLast[i]?.let { ChronoUnit.DAYS.between(it, day) }
            return when (signal) {
                is Signal.WokeUp -> {
                    val s = since(GroomingItem.SHAVE)
                    if (s == null || s >= 2) listOf(Effect.Notify("groom-shave", "刮鬍子（每兩天一次），刮好說「刮好了」。", NotifyKind.REMINDER)) else emptyList()
                }
                is Signal.DayStarted -> {
                    val lines = mutableListOf<String>()
                    if (day.dayOfWeek == DayOfWeek.SUNDAY) {
                        if ((since(GroomingItem.NAILS) ?: 99) >= 1) lines += "剪指甲（手和腳）"
                        if ((since(GroomingItem.BROWS) ?: 99) >= 13) lines += "修眉毛"
                    }
                    val hair = since(GroomingItem.HAIR)
                    val hairLine = when {
                        hair == null -> null
                        hair >= 28 -> "該剪頭髮了（每四到六週）"
                        hair >= 25 -> "三天後該剪頭髮了，先預約"
                        else -> null
                    }
                    hairLine?.let { lines += it }
                    if (lines.isEmpty()) return emptyList()
                    // The free time: an hour after the first meal.
                    val at = state.plan.slot(state.plan.firstMeal)?.let { RuleEngineTime.at(day, it.minute + 60, ctx.zone).toEpochMilli() }
                    listOf(Effect.Notify("groom-day", "儀容：${lines.joinToString("、")}。做好拍照回報。", NotifyKind.REMINDER, at = at))
                }
                else -> emptyList()
            }
        }
    }

    /**
     * L18: a no-class day (a rest day too: it only has no run) → the 30-minute walk placed in the gap before the core, and (once the course has
     * started, T19c) the 理論課 in the gap after the first meal, done before the walk.
     */
    object L18NoClassDay : Link {
        override val id = "L18"
        override val zh = "沒課日：理論課和快走排進空檔"
        override val dailyCap = 1

        /** How long before the walk the day's lessons are to be done. */
        const val LESSONS_BEFORE_WALK_MIN = 20

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.DayStarted || state.plan.type != DayType.NO_CLASS) return emptyList()
            val walk = state.plan.slot(SlotKey.WALK) ?: return emptyList()
            val first = state.plan.slot(SlotKey.FIRST_MEAL)?.minute ?: return emptyList()
            val core = state.plan.slot(SlotKey.CORE)?.minute ?: return emptyList()
            val at = maxOf(walk.minute, first + 90).coerceAtMost(core - ctx.specs.training.walkMin - 10)
            val out = mutableListOf<Effect>(Effect.AdjustToday(DayAdjust.Move(SlotKey.WALK, at), "因為今天沒課，所以 ${hhmm(at)} 快走 ${ctx.specs.training.walkMin} 分鐘。"))
            state.plan.slot(SlotKey.LESSONS)?.let { lessons ->
                val due = (at - LESSONS_BEFORE_WALK_MIN).coerceAtLeast(first + 60)
                out += Effect.AdjustToday(
                    DayAdjust.Move(SlotKey.LESSONS, due),
                    "因為今天沒課，所以理論課 ${lessons.count ?: 1} 支排在第一餐之後，${hhmm(due)} 前看完、考完、寫好筆記。",
                )
            }
            return out
        }
    }

    /**
     * L19: the bedtime review (T19) → the 檢討卡 (what code counted: done, not done, breaches, the illness change, and his two
     * answers) and his 「明天要改」 as a proposal: on his yes the manager reminds him of it at tomorrow's sign-in ([Signal.FixPlanned],
     * then [Signal.WokeUp] here). Nothing not done is to be made up tomorrow.
     */
    object L19Review : Link {
        override val feature get() = app.agency.core.schedule.Feature.SIGN
        override val id = "L19"
        override val zh = "睡前簽退：檢討卡，明天要改的變提案；超過最晚時間簽退記違規"
        override val dailyCap = 3

        fun card(r: Signal.ReviewDone): String = buildString {
            append("今天的檢討卡\n")
            append("做到：").append(r.done.joinToString("、") { it.zh }.ifEmpty { "還沒有" }).append("\n")
            append("沒做到：").append(r.missed.joinToString("、") { it.zh }.ifEmpty { "沒有，全部做到" }).append("\n")
            if (r.breaches > 0) append("違規：${r.breaches} 次\n")
            r.reduction?.let { append("調整：今天${it}，只改了今天，不用補做\n") }
            if (r.missed.isNotEmpty()) append("沒做到的不用補，明天照表。\n")
            append("你說做得好：").append(r.good).append("\n")
            append("明天要改：").append(r.fix)
        }

        fun change(fix: String) = "明天起床簽到時，我提醒你：$fix"

        /** A sign-out after the latest time (owner 2026-11-26 「超過時間才簽退算違規」). */
        const val LATE = "late_sign_out"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.SignedOut -> if (signal.late) {
                listOf(
                    Effect.Record("violation", LATE),
                    Effect.Say("記一次違規：超過最晚簽退的時間（${state.plan.slot(SlotKey.SIGN_OUT)?.time ?: ""}）。今天第 ${state.breaches} 次，會算進評鑑。"),
                )
            } else {
                emptyList()
            }
            is Signal.ReviewDone -> listOf(
                Effect.Say(card(signal)),
                Effect.Record("review", "done=${signal.done.size} missed=${signal.missed.size}"),
                Effect.Proposal(
                    todayOnly = false, change = change(signal.fix),
                    because = "因為你在睡前回顧說明天要改「${signal.fix}」，所以提案：明天簽到時提醒你一次。你說好才記。", fixTomorrow = signal.fix,
                ),
            )
            is Signal.WokeUp -> state.fixToday?.let { listOf(Effect.Say("昨晚你說今天要改：$it。今天就做到。")) } ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * L20: a Mon–Sun week is over (the brain counts it at the next week's start) → the week's numbers as the manager's
     * announcement (完成率, 訓練, 違規, 照片牆, 真人任務, on target or not, the weeks on target and the level), recorded for the
     * evaluation, and the next week's real-person task. The first one is given when the reminders start.
     */
    object L20Week : Link {
        override val id = "L20"
        override val zh = "一週結束：評鑑分數、等級進度、照片牆、真人任務"
        override val dailyCap = 4

        fun summary(w: Signal.WeekEnded): String {
            val monday = java.time.LocalDate.parse(w.monday)
            fun md(d: java.time.LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
            return buildString {
                append("一週結果（${md(monday)}–${md(monday.plusDays(6))}）\n")
                append("完成率 ${WeekReport.pct(w.done, w.due)}%（該做 ${w.due} 項，做到 ${w.done} 項）\n")
                append("訓練 ${w.trainingDone}/${w.trainingDue}\n")
                append("違規 ${w.breaches} 次\n")
                append("照片牆加了 ${w.photocards} 張\n")
                if (w.realTaskRequired) append("真人任務：").append(if (w.realTask) "做到了" else "沒做到").append("\n")
                val need = if (w.realTaskRequired) "、真人任務做到" else ""
                append(if (w.onTarget) "這週達標。" else "這週沒達標（要該做的 ${WeekReport.TARGET_PCT}% 以上、沒有違規$need）。")
                append("等級 ${w.level}，連續達標 ${w.weeksOnTarget} 週；連續達標才累積，升級看月末評鑑。")
            }
        }

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.WeekEnded -> {
                val next = java.time.LocalDate.parse(signal.monday).plusDays(7)
                listOfNotNull(
                    Effect.Say(summary(signal)),
                    Effect.Record("week", "${signal.monday} ${signal.done}/${signal.due} breaches=${signal.breaches} real=${signal.realTask} target=${signal.onTarget}"),
                    // The real-person task opens with its week (分週上線).
                    Effect.Say(RealTasks.line(next)).takeIf { ctx.specs.open(app.agency.core.schedule.Feature.REAL_TASK) },
                )
            }
            is Signal.RemindersOn -> if (ctx.specs.open(app.agency.core.schedule.Feature.REAL_TASK)) {
                listOf(Effect.Say(RealTasks.line(state.day.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))))
            } else {
                emptyList()
            }
            is Signal.RealTaskDone -> listOf(Effect.Record("real_task", state.day.toString()))
            else -> emptyList()
        }
    }

    /** L21: under 65 kg → no lower target until the photos and a health check; never below 52. */
    object L21WeightStop : Link {
        override val id = "L21"
        override val zh = "體重低於 65：先停在這裡"
        override val dailyCap = 1
        const val STOP_KG = 65.0

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.WeightLogged && signal.kg < STOP_KG) {
                listOf(
                    Effect.Notify("weight-stop", "體重到 65 公斤以下了。目標先停在這裡，看照片和健康檢查之後再決定下一步。", NotifyKind.WARNING),
                    Effect.Record("goal_stop", "kg=${signal.kg}"),
                )
            } else {
                emptyList()
            }
    }

    /**
     * L22: out to ride after < 6 h sleep or > 16 h awake → warn (fewer orders, finish early); training stays as planned. And
     * (owner 2026-11-27 「想睡就不准騎車跑單」) when he says he is sleepy on a delivery day before he is home: no riding, a warning
     * at once.
     */
    object L22TiredRiding : Link {
        override val feature get() = app.agency.core.schedule.Feature.RIDE
        override val id = "L22"
        override val zh = "疲勞騎車：提醒少跑、早收工"
        override val dailyCap = 4
        const val MIN_SLEEP_H = 6.0
        const val MAX_AWAKE_H = 16.0

        const val SLEEPY = "想睡就不准騎車跑單。現在停到安全的地方休息，清醒了再騎；還是想睡就收工回家。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal is Signal.Sleepy) {
                val home = state.rideEndAt != null && (state.rideStartAt == null || state.rideEndAt >= state.rideStartAt)
                if (!state.plan.delivery || home || state.signedOutAt != null) return emptyList()
                return listOf(Effect.Notify("sleepy-ride", SLEEPY, NotifyKind.WARNING), Effect.Say(SLEEPY))
            }
            if (signal !is Signal.RideStart) return emptyList()
            val sleep = state.sleepHours
            val awake = state.awakeHours(Instant.ofEpochMilli(signal.at))
            val why = when {
                sleep != null && sleep < MIN_SLEEP_H -> "昨晚只睡 ${oneDecimal(sleep)} 小時"
                awake != null && awake > MAX_AWAKE_H -> "已經醒了 ${oneDecimal(awake)} 小時"
                else -> return emptyList()
            }
            val text = "疲勞騎車很危險：$why。今天少跑幾單、早點收工；騎到想睡就不准再騎，停下來休息或收工。訓練照表。"
            return listOf(Effect.Notify("tired-ride", text, NotifyKind.WARNING), Effect.Say(text))
        }

        private fun oneDecimal(v: Double) = (Math.round(v * 10) / 10.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
    }

    /**
     * L23: two weeks in a row each losing more than 1 kg → propose a higher daily kcal, calibrated from what he ate:
     * TDEE = average intake + loss × 7700 ÷ days; the suggestion keeps a 500 deficit, rounded up to 50. Accepting only says to
     * change it in 設定 (rules are never changed by a link).
     */
    object L23WeightTrend : Link {
        override val feature get() = app.agency.core.schedule.Feature.MEALS
        override val id = "L23"
        override val zh = "體重掉太快：提案調高熱量"
        override val dailyCap = 1
        const val KCAL_PER_KG = 7700

        fun suggestion(state: DayState, quota: Int): Int? {
            val w = state.weights.sortedBy { it.first }
            if (w.size < 3) return null
            val last = w.last()
            val start = last.first.minusDays(14)
            val first = w.firstOrNull { !it.first.isBefore(start) && it.first.isBefore(last.first.minusDays(10)) } ?: return null
            val mid = w.lastOrNull { !it.first.isAfter(last.first.minusDays(7)) && it.first.isAfter(first.first) } ?: return null
            val days1 = ChronoUnit.DAYS.between(first.first, mid.first).coerceAtLeast(1)
            val days2 = ChronoUnit.DAYS.between(mid.first, last.first).coerceAtLeast(1)
            val week1 = (first.second - mid.second) * 7 / days1
            val week2 = (mid.second - last.second) * 7 / days2
            if (week1 <= 1.0 || week2 <= 1.0) return null
            val days = ChronoUnit.DAYS.between(first.first, last.first)
            val eaten = state.kcalByDay.filterKeys { !it.isBefore(first.first) && !it.isAfter(last.first) }.values
            val avg = if (eaten.size >= days / 2) eaten.average() else quota.toDouble()
            val tdee = avg + (first.second - last.second) * KCAL_PER_KG / days
            val next = (ceil((tdee - 500) / 50.0) * 50).toInt()
            return next.takeIf { it > quota }
        }

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.WeightLogged) return emptyList()
            val next = suggestion(state, ctx.rules.quotaKcal) ?: return emptyList()
            val change = "每天的熱量從 ${ctx.rules.quotaKcal} 調到 $next（到設定的公司規章改，要確認兩次）。"
            return listOf(
                Effect.Proposal(todayOnly = false, change = change, because = "因為連兩週每週掉超過 1 公斤，用你實際吃的量算，所以建議：$change", suggestQuota = next),
            )
        }
    }

    /**
     * The links whose data comes in a later batch: their signal types exist and they are registered, so the hub is ready; they
     * answer nothing until their batch fills them in.
     */
    /**
     * L24 (T19c 理論課): a 複習考 due today → a reminder at its slot (recorded only, it locks nothing); 「課程清單」 running low → at
     * the sign-in, a reminder to find more (real search results only, nothing listed without his 好); a lesson passed, a review
     * taken, a 實作 handed in → recorded for the month-end evaluation (stage 4).
     */
    object L24Lessons : Link {
        override val feature get() = app.agency.core.schedule.Feature.LESSONS
        override val id = "L24"
        override val zh = "理論課：複習考提醒、清單快沒了提醒、分數和實作記進評鑑"
        override val dailyCap = 40
        const val LESSON = "lesson"
        const val REVIEW = "lesson_review"
        const val PRACTICAL = "practical"

        fun reviewLine(n: Int) = "複習考：兩天前的課有 $n 支，到理論課各考三題。只記錄，不擋下一支。"
        fun lowLine(n: Int) = if (n == 0) "課程清單沒有影片了。說「找影片」，我只列真的搜尋結果，你按好才放進清單。"
        else "課程清單只剩 $n 支。說「找影片」補，我只列真的搜尋結果，你按好才放進清單。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.DayStarted -> if (state.reviewsDue > 0) {
                val at = state.plan.slot(SlotKey.LESSON_REVIEW)?.let { RuleEngineTime.at(state.day, it.minute, ctx.zone).toEpochMilli() }
                listOf(Effect.Notify("lesson-review", reviewLine(state.reviewsDue), NotifyKind.REMINDER, at = at))
            } else {
                emptyList()
            }
            is Signal.WokeUp -> state.lessonsLeft?.takeIf { it < app.agency.core.lessons.LessonPlan.LOW_LIST }?.let {
                listOf(Effect.Notify("lesson-list", lowLine(it), NotifyKind.REMINDER))
            }.orEmpty()
            is Signal.LessonPassed -> listOf(
                Effect.Record(LESSON, "${signal.category} 第一輪 ${signal.score?.let { "$it/3" } ?: "寫重點代替"} 重考 ${signal.retries} 題"),
            )
            is Signal.LessonReviewed -> listOf(Effect.Record(REVIEW, "${signal.category} ${signal.score}/3"))
            is Signal.PracticalHandedIn -> listOf(Effect.Record(PRACTICAL, "${signal.category} 交了"))
            else -> emptyList()
        }
    }

    /**
     * L25 (分週上線, owner 2026-11-27): a week on target → the card for the next rollout week (what it opens); his 好 opens it
     * ([Nerve.decide]), 不要 keeps this week. Never past the last week; he can also set the week in 設定.
     */
    object L25Rollout : Link {
        override val id = "L25"
        override val zh = "一週達標：開下一週（你說好才開）"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.WeekEnded || !signal.onTarget) return emptyList()
            val r = ctx.specs.rollout
            if (r.week >= app.agency.core.schedule.RolloutSpec.LAST) return emptyList()
            val next = r.week + 1
            val opens = r.opensIn(next).joinToString("、") { it.zh }
            val change = "第 $next 週開始，新開放：$opens。"
            return listOf(
                Effect.Say("這週達標了。第 $next 週要開放：$opens。你說「好」就開始；還沒準備好就說「不要」，這週再穩一週。"),
                Effect.Proposal(todayOnly = false, change = change, because = "因為上一週達標（該做的 ${WeekReport.TARGET_PCT}% 以上、沒有違規），所以提案開下一週：$change", rolloutWeek = next),
            )
        }
    }

    /**
     * L26 (owner 2026-11-26): the third breach of a Mon–Sun week → 小葵 opens a 面談 (one question, 討論), and that week's level
     * progress does not count (the week is off target anyway: [WeekReport.onTarget] needs no breach). Once a week: when the
     * count crosses three.
     */
    object L26Interview : Link {
        override val id = "L26"
        override val zh = "一週違規三次：面談，這週等級進度不算"
        override val dailyCap = 1
        const val TIMES = 3
        val OPTIONS = listOf("時間排不過來", "忍不住", "忘記了", "其他原因")

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            val added = when (signal) {
                is Signal.MealLogged -> signal.breaches.size
                is Signal.SignedOut -> if (signal.late) 1 else 0
                else -> 0
            }
            if (added == 0 || state.weekBreaches < TIMES || state.weekBreaches - added >= TIMES) return emptyList()
            return listOf(
                Effect.Say(
                    "這週已經違規 ${state.weekBreaches} 次了。照公司規章要面談，這週的等級進度不算。先說原因：是哪裡卡住了？",
                    discuss = true, options = OPTIONS,
                ),
                Effect.Record("interview", "breaches=${state.weekBreaches}"),
            )
        }
    }

    /** L27 全勤小卡 (owner 2026-11-26): 7 and 30 days in a row with everything that was due done → a card on the wall. */
    object L27Attendance : Link {
        override val feature get() = app.agency.core.schedule.Feature.ATTENDANCE
        override val id = "L27"
        override val zh = "全勤小卡：連續 7 天、30 天全部做到"
        override val dailyCap = 1
        const val CARD = "attendance"
        val DAYS = listOf(7, 30)

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.Attendance && signal.days in DAYS) {
                listOf(
                    Effect.Record(CARD, signal.days.toString()),
                    Effect.Say("全勤小卡：連續 ${signal.days} 天，該做的全部做到。小卡貼上照片牆了。"),
                )
            } else {
                emptyList()
            }
    }

    /**
     * L30 心情 (定稿 §2): three days in a row of 低落 at sign-out → the manager asks first, gently, without taking any of the plan
     * away (and without making him need her: she points at sleep and someone he trusts too).
     */
    object L30MoodCare : Link {
        override val feature get() = app.agency.core.schedule.Feature.MOOD
        override val id = "L30"
        override val zh = "連三天心情低落：小葵姐主動關心"
        override val dailyCap = 1
        const val DAYS = 3
        const val LINE = "小雨，這是第三天心情低落了。辛苦了。想說的話我在聽；也可以找一個信任的朋友或家人聊聊。今天先早點睡，明天的事明天再說。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.Mood && signal.level == 1 && signal.lowDays > 0 && signal.lowDays % DAYS == 0) listOf(Effect.Say(LINE), Effect.Record("gentle", "MOOD")) else emptyList()
    }

    /** L31 月評面談 (定稿 §10): two months in a row without progress → 討論, one question, his choice (feedback in private). */
    object L31MonthInterview : Link {
        override val feature get() = app.agency.core.schedule.Feature.MONTHLY_EVAL
        override val id = "L31"
        override val zh = "月評連兩個月沒進步：小葵姐面談"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.MonthInterview) {
                listOf(Effect.Say(app.agency.core.brain.handlers.PracticeDesk.INTERVIEW, discuss = true, options = app.agency.core.brain.handlers.PracticeDesk.INTERVIEW_OPTIONS))
            } else {
                emptyList()
            }
    }

    /** L32 升級典禮 (T19e): a subject's level-up → the ceremony line and a level card on the photocard wall. */
    object L32LevelUp : Link {
        override val feature get() = app.agency.core.schedule.Feature.LESSONS
        override val id = "L32"
        override val zh = "每科升級：升級典禮和小卡"
        override val dailyCap = 20
        const val CARD = "level_card"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.LevelUp) return emptyList()
            val cat = app.agency.core.model.LessonCategory.parse(signal.subject) ?: return emptyList()
            return listOf(Effect.Say(Levels.ceremony(cat, signal.level)), Effect.Record(CARD, "${signal.subject}:${signal.level}"))
        }
    }

    /** L33 學了就用 (owner 2026-11-23): the manager proposes the passed lesson's small daily habit; added only on his yes. */
    object L33Habit : Link {
        override val feature get() = app.agency.core.schedule.Feature.HABITS
        override val id = "L33"
        override val zh = "學了就用：一課通過，提議變成每天的小習慣"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.HabitOffer) return emptyList()
            val cat = app.agency.core.model.LessonCategory.parse(signal.subject) ?: return emptyList()
            val o = Habits.offer(cat) ?: return emptyList()
            return listOf(Effect.Proposal(todayOnly = false, change = "明天起每天一個小習慣：${o.text}（${o.at}前，做完說「小習慣完成」）", because = "${cat.zh}這課通過了，生活用得上。一次只加一項，穩定兩週才加下一項。", habit = cat.name))
        }
    }

    /**
     * L28 天氣 (owner 2026-11-26; a manual switch, no weather service): 「今天下雨」 → 30 minutes before the latest departure,
     * the raincoat and riding slow; 「今天很熱」 → drink half a bottle more before leaving. Its off word takes it back.
     */
    object L28Weather : Link {
        override val feature get() = app.agency.core.schedule.Feature.WEATHER
        override val id = "L28"
        override val zh = "天氣：下雨穿雨衣騎慢，大熱天出門前多喝半壺"
        override val dailyCap = 6
        const val RAIN = "今天下雨：出門穿雨衣、騎慢一點，煞車提早，路口多看一眼。"
        const val HOT = "今天很熱：出門前多喝半壺水，路上小口喝；頭暈就停下來休息。"
        const val BEFORE_MIN = 30

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.Weather || !signal.on) return emptyList()
            val out = state.plan.slot(SlotKey.RIDE_OUT) ?: return emptyList()
            val text = if (signal.kind == "RAIN") RAIN else HOT
            val at = maxOf(signal.at, RuleEngineTime.at(state.plan.day, (out.untilMinute ?: out.minute) - BEFORE_MIN, ctx.zone).toEpochMilli())
            return listOf(Effect.Notify("weather-${signal.kind}", text, NotifyKind.WARNING, at = at, unlessHome = true))
        }
    }

    /** L29 (owner 2026-11-26): no coffee and no tea after 20:30 → a reminder a quarter before. */
    object L29Caffeine : Link {
        override val feature get() = app.agency.core.schedule.Feature.CAFFEINE
        override val id = "L29"
        override val zh = "晚上 8 點半後不喝咖啡和茶"
        override val dailyCap = 1
        val CUTOFF = DayClock.minute("20:30")
        const val LINE = "20:30 之後不喝咖啡和茶，會睡不好。要喝就喝水。"

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> =
            if (signal is Signal.DayStarted) {
                listOf(
                    Effect.Notify(
                        "caffeine", LINE, NotifyKind.REMINDER, at = RuleEngineTime.at(state.plan.day, CUTOFF - 15, ctx.zone).toEpochMilli(),
                        expiresAt = RuleEngineTime.at(state.plan.day, CUTOFF, ctx.zone).toEpochMilli(),
                    ),
                )
            } else {
                emptyList()
            }
    }

    class Pending(override val id: String, override val zh: String, val batch: String) : Link {
        override val dailyCap = 10
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = emptyList()

        companion object {
            val all = emptyList<Pending>()
        }
    }

    /** Who says a [Effect.Say] line by default. */
    val SPEAKER = RoleKey.MANAGER
}
