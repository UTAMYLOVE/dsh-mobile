package app.agency.core.engine

import app.agency.core.model.RoleKey
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
        L1WakeLate, L2Illness, L3Gentle, NoReduction, L4HungryMorning, L6Floor, L8Riding, L9RideEnd, L12Chase, L14Discuss,
        L17Grooming, L18NoClassDay, L21WeightStop, L22TiredRiding, L23WeightTrend,
    ) + Pending.all

    private fun hhmm(minute: Int) = DayClock.hhmm(minute)

    /** L1: woke ≥ 60 minutes late → the first meal follows (at most to 3 h before the next meal); sleep time stays. */
    object L1WakeLate : Link {
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
        val RED_FLAGS = setOf("頭暈", "暈")

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.Unwell) return emptyList()
            if (signal.kind == IllKind.UNKNOWN) return if (state.ill == null) listOf(Effect.Say(ASK)) else emptyList()
            val out = mutableListOf<Effect>()
            if (!state.reductionProposed && state.plan.slots.any { it.key == SlotKey.STRENGTH || it.key == SlotKey.CORE }) {
                val reduction = if (signal.kind == IllKind.INJURY) Reduction.INJURY else Reduction.SICK
                val change = if (reduction == Reduction.INJURY) "今天核心和肌力先停，伸展照做，避開受傷的地方。" else "今天核心和肌力先停，伸展照做十分鐘。"
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
        override val zh = "生病或心情低落：經紀人放軟口氣"
        override val dailyCap = 2

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.Unwell -> if (signal.kind != IllKind.UNKNOWN) listOf(Effect.Record("gentle", signal.kind.name)) else emptyList()
            is Signal.MoodLow -> listOf(Effect.Record("gentle", "MOOD"))
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

    /** L6: by dinner (or after L9) the day is under the floor → eat enough (never less). */
    object L6Floor : Link {
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
     * L8: out on the run → reminders sound once (no pop-ups while riding). On a delivery day's start, a reminder to drink
     * before leaving (little while riding).
     */
    object L8Riding : Link {
        override val id = "L8"
        override val zh = "跑單：提醒只響一聲，出門前喝水"
        override val dailyCap = 3
        const val BEFORE_MIN = 20

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = when (signal) {
            is Signal.RideStart -> if (DayFlag.RIDING_ONE_SOUND in state.flags) emptyList() else listOf(
                Effect.AdjustToday(DayAdjust.Flag(DayFlag.RIDING_ONE_SOUND), "因為你出門跑單了，所以提醒改成只響一聲，不會一直跳出來。"),
            )
            is Signal.DayStarted -> state.plan.slot(SlotKey.RIDE_OUT)?.let { out ->
                listOf(
                    Effect.Notify(
                        "preride", "快出門了：出門前先喝水，路上只能小口喝。", NotifyKind.REMINDER,
                        at = RuleEngineTime.at(state.plan.day, out.minute - BEFORE_MIN, ctx.zone).toEpochMilli(),
                    ),
                )
            } ?: emptyList()
            else -> emptyList()
        }
    }

    /** L9: home from the run with the 21:00 portion eaten → no 宵夜 tonight, then L6 checks the floor. */
    object L9RideEnd : Link {
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

    /** L12: something due is not reported → a reminder every 30 minutes until he reports or goes to bed; once while riding. */
    object L12Chase : Link {
        override val id = "L12"
        override val zh = "沒回報：每 30 分鐘催一次，跑單中只響一聲"
        override val dailyCap = 500
        const val SHOWN = 3

        fun text(keys: List<SlotKey>, index: Int): String {
            val names = keys.map { it.zh }
            val head = if (index == 0 && keys.size == 1) "${names[0]}時間到了，做完回報。" else "還缺：${names.take(SHOWN).joinToString("、")}。"
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

    /** L17: grooming cycles → reminders in today's free time (shave at sign-in). */
    object L17Grooming : Link {
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

    /** L18: a no-class day (not a rest day) → the 30-minute walk placed in the gap before the core (videos with stage 3). */
    object L18NoClassDay : Link {
        override val id = "L18"
        override val zh = "沒課日：快走排進空檔"
        override val dailyCap = 1

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.DayStarted || state.plan.type != DayType.NO_CLASS || state.plan.rest) return emptyList()
            val walk = state.plan.slot(SlotKey.WALK) ?: return emptyList()
            val first = state.plan.slot(SlotKey.FIRST_MEAL)?.minute ?: return emptyList()
            val core = state.plan.slot(SlotKey.CORE)?.minute ?: return emptyList()
            val at = maxOf(walk.minute, first + 90).coerceAtMost(core - ctx.specs.training.walkMin - 10)
            return listOf(Effect.AdjustToday(DayAdjust.Move(SlotKey.WALK, at), "因為今天沒課，所以 ${hhmm(at)} 快走 ${ctx.specs.training.walkMin} 分鐘。"))
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

    /** L22: out to ride after < 6 h sleep or > 16 h awake → warn (fewer orders, finish early); training stays as planned. */
    object L22TiredRiding : Link {
        override val id = "L22"
        override val zh = "疲勞騎車：提醒少跑、早收工"
        override val dailyCap = 1
        const val MIN_SLEEP_H = 6.0
        const val MAX_AWAKE_H = 16.0

        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> {
            if (signal !is Signal.RideStart) return emptyList()
            val sleep = state.sleepHours
            val awake = state.awakeHours(Instant.ofEpochMilli(signal.at))
            val why = when {
                sleep != null && sleep < MIN_SLEEP_H -> "昨晚只睡 ${oneDecimal(sleep)} 小時"
                awake != null && awake > MAX_AWAKE_H -> "已經醒了 ${oneDecimal(awake)} 小時"
                else -> return emptyList()
            }
            val text = "疲勞騎車很危險：$why。今天少跑幾單、早點收工。訓練照表。"
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
    class Pending(override val id: String, override val zh: String, val batch: String) : Link {
        override val dailyCap = 10
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext): List<Effect> = emptyList()

        companion object {
            val all = listOf(
                Pending("L5", "每餐熱量：下一餐建議縮小（不低於 1200）", "第 4 批（吃飯 T14、討論與搜尋 T16）"),
                Pending("L7", "休假餐那一餐不算超量違規", "第 4 批（T14）"),
                Pending("L10", "喝水落後：下一個空檔加一次提醒", "第 4 批（T17）"),
                Pending("L11", "核心或肌力第一次完成：撒嬌一次、照片牆加一張", "第 5 批（T18）、第 6 批（T20）"),
                Pending("L13", "違規：記進評鑑、第二道審稿", "第 4 批（T14）"),
                Pending("L15", "肌力一週都輕鬆：提案升級", "第 5 批（T18）"),
                Pending("L16", "花費從生活費或娛樂金扣", "第六階段（記帳）"),
                Pending("L19", "睡前簽退：檢討卡、明天的提案", "第 5 批（T19）"),
                Pending("L20", "一週結束：評鑑、等級、照片牆", "第二階段"),
            )
        }
    }

    /** Who says a [Effect.Say] line by default. */
    val SPEAKER = RoleKey.MANAGER
}
