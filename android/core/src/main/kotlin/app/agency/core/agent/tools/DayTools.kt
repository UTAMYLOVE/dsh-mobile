package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.base.Days
import app.agency.core.plan.ItemStatus
import app.agency.core.stage.Feature
import app.agency.core.store.Body
import app.agency.core.store.Bucket
import app.agency.core.store.CheckIn
import app.agency.core.store.CheckOut
import app.agency.core.store.Drink
import app.agency.core.store.DrinkType
import app.agency.core.store.Kind
import app.agency.core.store.Money
import app.agency.core.store.Mood
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Water
import app.agency.core.store.add
import app.agency.core.week.Income
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CheckInTool : BaseTool(
    "check_in",
    "早上簽到。他說早安、起床了、到了，就用這個。score 是他今天的狀態 1～5 分（5 最好）；他沒說分數就傳 null，" +
        "畫面會出五個點讓他點，點了才算簽到。回傳簽到時間、跟規定時間比晚了幾分鐘、今天改了什麼、下一件事。",
    S.obj("score" to S.int("今天的狀態 1～5；不知道就 null", nullable = true)),
    Feature.CHECK_IN, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val score = a.intOrNull("score", "分數", 1..5)
        val early = earlyDay(ctx)
        if (early != null) {
            if (ctx.store.entries(early, Kind.CHECK_IN).isNotEmpty()) {
                return ToolOut(F.obj { put("already", true); put("note", "今天（早上 6:00 起）已經簽到過了") })
            }
            if (score == null) {
                return ToolOut(F.obj {
                    put("recorded", false)
                    put("note", "還沒簽到：畫面上出了五個點，他點一個分數就完成簽到。")
                }, listOf(Card.Score("check_in")))
            }
            return record(ctx, score)
        }
        val before = F.moment(ctx)
        if (before.state.checkIn != null) {
            return ToolOut(F.obj {
                put("already", true)
                put("checked_in_at", F.hm(before.state.checkInAt!!, ctx.clock.zone))
                put("note", "今天已經簽到過了")
                put("today", F.today(before))
            })
        }
        if (score == null) {
            return ToolOut(F.obj {
                put("recorded", false)
                put("note", "還沒簽到：畫面上出了五個點，他點一個分數就完成簽到。")
            }, listOf(Card.Score("check_in")))
        }
        return record(ctx, score)
    }

    fun record(ctx: ToolCtx, score: Int): ToolOut {
        val early = earlyDay(ctx)
        if (early != null) {
            // Signed in between 03:00 and 06:00: it is the morning of the logical day that starts at 06:00, not the night before.
            val e = ctx.store.add(early, ctx.clock.now(), Kind.CHECK_IN, CheckIn(score))
            if (!isOpen(ctx)) return ToolOut(F.closed(Feature.CHECK_IN, e) { put("score", score) })
            return ToolOut(F.obj {
                put("entry_id", e.id)
                put("score", score)
                put("checked_in_at", F.hm(e.at, ctx.clock.zone))
                put("for_day", early.toString())
                put("late_minutes", 0)
                put("note", "已簽到，狀態 $score 分（算今天早上 6:00 開始的這一天）")
            })
        }
        val e = F.write(ctx, Kind.CHECK_IN, CheckIn(score))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.CHECK_IN, e) { put("score", score) })
        F.violations(ctx)
        val m = F.moment(ctx)
        val wake = m.item("check_in")?.at
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("score", score)
            put("checked_in_at", F.hm(e.at, ctx.clock.zone))
            if (wake != null) {
                put("wake_time", F.hm(wake))
                val late = Duration.between(Days.at(m.day, wake, ctx.clock.zone), e.at).toMinutes()
                put("late_minutes", late.coerceAtLeast(0))
            }
            put("adjustments", F.strs(m.plan.adjustments.map { "${it.what}（${it.why}）" }))
            put("note", "已簽到，狀態 $score 分")
            put("today", F.today(m))
        })
    }

    companion object {
        /** Between 03:00 and 06:00 a check-in opens the logical day starting at 06:00; null at any other time. */
        fun earlyDay(ctx: ToolCtx): java.time.LocalDate? {
            val now = ctx.clock.now()
            val t = now.atZone(ctx.clock.zone).toLocalTime()
            if (t.hour < EARLY_FROM || t.hour >= Days.START_HOUR) return null
            return Days.of(now, ctx.clock.zone).plusDays(1)
        }

        private const val EARLY_FROM = 3
    }
}

internal class CheckOutTool : BaseTool(
    "check_out",
    "晚上簽退（他說要睡了、晚安）。good 是今天做得好的一件事，improve 是明天想改進的一件事（他沒說就傳空字串，" +
        "不要替他編），mood 是心情 1～5（沒問到就 null）。回傳今天完成幾項、沒做完的項目、今天的違規。",
    S.obj(
        "good" to S.str("今天做得好的事，他的原話；沒有就空字串"),
        "improve" to S.str("想改進的事，他的原話；沒有就空字串"),
        "mood" to S.int("心情 1～5；沒有就 null", nullable = true),
    ),
    Feature.CHECK_IN, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val mood = a.intOrNull("mood", "心情", 1..5)
        val value = CheckOut(a.text("good"), a.text("improve"), mood)
        val yesterday = lateFor(ctx)
        if (yesterday != null) {
            // Past 06:00 but he never went to bed: this is last night's (late) check-out, so today stays open for reminders.
            val e = ctx.store.add(yesterday, ctx.clock.now(), Kind.CHECK_OUT, value)
            if (!isOpen(ctx)) return ToolOut(F.closed(Feature.CHECK_IN, e))
            val fresh = F.violationsOn(ctx, yesterday)
            return ToolOut(F.obj {
                put("entry_id", e.id)
                put("checked_out_at", F.hm(e.at, ctx.clock.zone))
                put("for_day", yesterday.toString())
                put("new_violations", F.strs(fresh))
                put("note", "已簽退（算昨天 ${yesterday.monthValue}/${yesterday.dayOfMonth} 的簽退，已經很晚了）")
            })
        }
        val e = F.write(ctx, Kind.CHECK_OUT, value)
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.CHECK_IN, e))
        val fresh = F.violations(ctx)
        val m = F.moment(ctx)
        val left = m.plan.items.filter { it.counts && (it.status == ItemStatus.PENDING || it.status == ItemStatus.LATE) }
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("checked_out_at", F.hm(e.at, ctx.clock.zone))
            m.bedtime?.let { put("bedtime", F.hm(it)) }
            put("not_done", F.strs(left.map { it.title }))
            put("violations_today", F.strs(m.state.violations.map(F::violationWord)))
            put("new_violations", F.strs(fresh))
            put("note", "已簽退，今天完成 ${m.plan.done}／${m.plan.total} 項")
            put("today", F.today(m))
        })
    }

    companion object {
        /**
         * The logical day a check-out now belongs to when it is not today: before noon, no check-in today yet, and yesterday
         * has a check-in without a check-out. Null otherwise.
         */
        fun lateFor(ctx: ToolCtx): java.time.LocalDate? {
            val now = ctx.clock.now()
            if (now.atZone(ctx.clock.zone).toLocalTime().hour >= LATE_UNTIL) return null
            val today = Days.of(now, ctx.clock.zone)
            if (ctx.store.entries(today, Kind.CHECK_IN).isNotEmpty() || ctx.store.entries(today, Kind.CHECK_OUT).isNotEmpty()) return null
            val y = today.minusDays(1)
            val ins = ctx.store.entries(y, Kind.CHECK_IN)
            return if (ins.isNotEmpty() && ctx.store.entries(y, Kind.CHECK_OUT).isEmpty()) y else null
        }

        private const val LATE_UNTIL = 12
    }
}

internal class LogWaterTool : BaseTool(
    "log_water",
    "記喝水。他說喝完一壺、喝了半壺、喝了 500 毫升就用這個。bottles 是壺數（0.5、1…），ml 是毫升；兩個給一個就好，" +
        "另一個傳 null。回傳今天喝了多少、目標多少、還差多少。",
    S.obj(
        "bottles" to S.num("喝了幾壺，例如 1 或 0.5；用毫升時傳 null", nullable = true),
        "ml" to S.int("喝了幾毫升；用壺數時傳 null", nullable = true),
    ),
    Feature.WATER, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val bottles = a.numOrNull("bottles", "壺數", min = 0.0, max = 10.0)
        val mlArg = a.intOrNull("ml", "毫升", 1..10_000)
        val bottleMl = ctx.rules.water.bottleMl
        val ml = when {
            mlArg != null -> mlArg
            bottles != null && bottles > 0 && bottleMl > 0 -> Math.round(bottles * bottleMl).toInt()
            bottles != null && bottleMl <= 0 -> throw BadArg("水壺容量還沒設定，請改用毫升（ml），或請他到 設定 → 公司規章 填水壺容量。")
            else -> throw BadArg("要給 bottles（壺數）或 ml（毫升）其中一個。")
        }
        val e = F.write(ctx, Kind.WATER, Water(ml))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.WATER, e) { put("ml", ml) })
        val m = F.moment(ctx)
        val target = m.state.waterTargetMl(m.state.dayNumber)
        val words = if (bottleMl > 0) "${fmt(F.bottles(ml, bottleMl))} 壺" else "$ml 毫升"
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("ml", ml)
            if (bottleMl > 0) put("bottles", F.num(F.bottles(ml, bottleMl)))
            put("reached", target in 1..m.state.waterMl)
            put("note", "已記：喝水 $words")
            put("today", F.today(m))
        }, listOf(Card.Summary("喝水", listOfNotNull(
            "今天 ${m.state.waterMl}／$target 毫升",
            if (bottleMl > 0) "${fmt(F.bottles(m.state.waterMl, bottleMl))}／${fmt(F.bottles(target, bottleMl))} 壺" else null,
        ))))
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

internal class LogDrinkTool : BaseTool(
    "log_drink",
    "記一杯有限制的飲料：caffeine 是含咖啡因的（咖啡、茶、能量飲），zero_cal 是無熱量飲料（零卡汽水等）。" +
        "含糖飲料不是這個，要用 log_meal 記熱量。回傳本週用了幾次、今天有沒有新的違規。",
    S.obj("type" to S.enum("飲料種類", listOf("caffeine", "zero_cal"))),
    Feature.MEALS, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val type = a.enum("type", "飲料種類", DrinkType.values())
        val e = F.write(ctx, Kind.DRINK, Drink(type))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.MEALS, e))
        val fresh = F.violations(ctx)
        val day = F.day(ctx)
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val week = ctx.store.entries(monday, day, Kind.DRINK).count {
            runCatching { it.read<Drink>().type }.getOrNull() == type
        }
        val word = if (type == DrinkType.CAFFEINE) "咖啡因飲料" else "無熱量飲料"
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("type", word)
            put("this_week", week)
            if (type == DrinkType.ZERO_CAL && ctx.rules.diet.zeroCalDrinksPerWeek > 0) {
                put("allowed_per_week", ctx.rules.diet.zeroCalDrinksPerWeek)
            }
            if (type == DrinkType.CAFFEINE && ctx.rules.diet.caffeineCutoff.isNotBlank()) {
                Days.parse(ctx.rules.diet.caffeineCutoff)?.let { put("caffeine_cutoff", F.hm(it)) }
            }
            put("new_violations", F.strs(fresh))
            put("note", "已記：$word")
        })
    }
}

internal class LogBodyTool : BaseTool(
    "log_body",
    "記體重（公斤）和腰圍（公分），他說了哪個就給哪個，另一個傳 null。回傳這次的數字和上一次的比較。",
    S.obj(
        "kg" to S.num("體重公斤；沒說就 null", nullable = true),
        "waist_cm" to S.num("腰圍公分；沒說就 null", nullable = true),
    ),
    Feature.WEIGHT, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val kg = a.numOrNull("kg", "體重", min = 20.0, max = 300.0)
        val waist = a.numOrNull("waist_cm", "腰圍", min = 30.0, max = 250.0)
        if (kg == null && waist == null) throw BadArg("體重（kg）和腰圍（waist_cm）至少要給一個。")
        val day = F.day(ctx)
        val previous = ctx.store.entries(day.minusDays(120), day, Kind.BODY)
            .mapNotNull { runCatching { it.read<Body>() }.getOrNull() }
        val lastKg = previous.mapNotNull { it.kg }.lastOrNull()
        val lastWaist = previous.mapNotNull { it.waistCm }.lastOrNull()
        val e = F.write(ctx, Kind.BODY, Body(kg = kg, waistCm = waist))
        val facts = F.obj {
            put("entry_id", e.id)
            kg?.let { put("kg", F.num(it)) }
            waist?.let { put("waist_cm", F.num(it)) }
            if (kg != null && lastKg != null) { put("previous_kg", F.num(lastKg)); put("change_kg", F.num(kg - lastKg)) }
            if (waist != null && lastWaist != null) { put("previous_waist_cm", F.num(lastWaist)); put("change_waist_cm", F.num(waist - lastWaist)) }
            if (ctx.rules.body.neverBelowKg > 0) put("never_below_kg", F.num(ctx.rules.body.neverBelowKg))
            if (!isOpen(ctx)) { put("open", false); put("note_open", F.closedNote(Feature.WEIGHT)) }
            put("note", "已記：" + listOfNotNull(kg?.let { "體重 ${F.num(it)} 公斤" }, waist?.let { "腰圍 ${F.num(it)} 公分" }).joinToString("、"))
        }
        return ToolOut(facts)
    }
}

internal class LogMoodTool : BaseTool(
    "log_mood",
    "記心情 1～5（1 很低落，5 很好）和一句他的原話。回傳最近連續低落幾天。",
    S.obj("score" to S.int("心情 1～5"), "note" to S.str("他的原話或一句摘要；沒有就空字串")),
    Feature.MOOD, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val score = a.int("score", "心情", 1..5)
        val e = F.write(ctx, Kind.MOOD, Mood(score, a.text("note")))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.MOOD, e) { put("score", score) })
        val low = app.agency.core.week.Streaks.lowMood(ctx.store, F.day(ctx))
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("score", score)
            put("low_days_in_a_row", low)
            put("note", "已記：心情 $score 分")
        })
    }
}

internal class LogMoneyTool : BaseTool(
    "log_money",
    "記帳。amount 是金額（整數元）；bucket：living 生活必要（吃飯、交通…），fun 娛樂，income 收入（跑單以外的）。" +
        "跑單收入不要用這個，用 ride_back。回傳本週和本月各類合計。",
    S.obj(
        "amount" to S.int("金額，整數元"),
        "bucket" to S.enum("類別", listOf("living", "fun", "income")),
        "note" to S.str("買了什麼，他的話；沒有就空字串"),
    ),
    Feature.MONEY, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val amount = a.int("amount", "金額", 1..10_000_000)
        val bucket = a.enum("bucket", "類別", Bucket.values())
        val e = F.write(ctx, Kind.MONEY, Money(amount, bucket, a.text("note")))
        val v = MoneyMath.of(ctx.store, F.day(ctx))
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("amount", amount)
            put("bucket", MoneyMath.word(bucket))
            put("week", MoneyMath.json(v.week))
            put("month", MoneyMath.json(v.month))
            put("note", "已記：${MoneyMath.word(bucket)} $amount 元")
        })
    }
}

internal class RideOutTool : BaseTool(
    "ride_out",
    "他出門跑單了（「我出門了」「去跑單」）。回傳出門時間、出門前的水喝完沒、本週收入進度。想睡的時候不要叫他出門。",
    S.obj(),
    Feature.RIDE, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val before = F.moment(ctx)
        if (before.state.riding) throw BadArg("他已經在跑單中了（還沒收工）。收工時用 ride_back。")
        val e = F.write(ctx, Kind.RIDE, Ride(RidePhase.OUT))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.RIDE, e))
        val m = F.moment(ctx)
        val water = m.item("water")
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("out_at", F.hm(e.at, ctx.clock.zone))
            if (water != null) put("water_done", water.status == ItemStatus.DONE)
            put("week_income", IncomeFacts.json(Income.week(ctx.store, m.day, ctx.rules)))
            put("note", "已記：出門跑單 ${F.hm(e.at, ctx.clock.zone)}")
            put("today", F.today(m))
        })
    }
}

internal class RideBackTool : BaseTool(
    "ride_back",
    "他跑單收工回來了（「收工了」「回到家」）。income 是今天賺的錢（整數元），他沒說就 null，之後他說了再記一次 ride_back 也可以。" +
        "回傳本週收入、離目標還差多少、剩下每天平均要多少，和收工後要做的訓練。",
    S.obj("income" to S.int("今天跑單收入，整數元；沒說就 null", nullable = true)),
    Feature.RIDE, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val income = a.intOrNull("income", "收入", 0..1_000_000)
        val before = F.moment(ctx)
        var backfilled = false
        if (before.state.rideOutAt == null) {
            F.write(ctx, Kind.RIDE, Ride(RidePhase.OUT))
            backfilled = true
        }
        val e = F.write(ctx, Kind.RIDE, Ride(RidePhase.BACK, income))
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.RIDE, e))
        val m = F.moment(ctx)
        val training = m.plan.items.filter {
            it.id.startsWith("task:") && it.counts && (it.status == ItemStatus.PENDING || it.status == ItemStatus.LATE)
        }
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("back_at", F.hm(e.at, ctx.clock.zone))
            income?.let { put("income_today", m.state.income) }
            if (backfilled) put("out_backfilled", "出門時沒記到，這次一起補記")
            put("week_income", IncomeFacts.json(Income.week(ctx.store, m.day, ctx.rules)))
            put("training_now", app.agency.core.agent.tools.F.arr(training.map(F::item)))
            put("note", "已記：收工 ${F.hm(e.at, ctx.clock.zone)}" + (income?.let { "，收入 $it 元" } ?: ""))
            put("today", F.today(m))
        })
    }
}

/** The weekly income line, the same everywhere. */
internal object IncomeFacts {
    fun json(w: app.agency.core.week.IncomeWeek): JsonObject = F.obj {
        put("income", w.income)
        put("days_ridden", w.daysRidden)
        if (w.target > 0) {
            put("target", w.target)
            put("left", w.left)
            put("remaining_days", w.remainingDays)
            put("per_remaining_day", w.perRemainingDay)
            put("reached", w.reached)
        }
    }

    fun line(w: app.agency.core.week.IncomeWeek): String =
        if (w.target > 0) {
            "本週收入 ${w.income} 元／目標 ${w.target} 元，還差 ${w.left} 元，剩 ${w.remainingDays} 天，平均每天 ${w.perRemainingDay} 元；跑了 ${w.daysRidden} 天"
        } else {
            "本週收入 ${w.income} 元，跑了 ${w.daysRidden} 天"
        }
}

internal class OpenScreenTool : BaseTool(
    "open_screen",
    "在對話裡放一個按鈕，讓他打開某個畫面：lessons 理論課清單、quiz 測驗（arg 放課的編號）、camera 拍照（arg 放 first／dinner／night）、" +
        "check_in 簽到、check_out 簽退、body 量體重、memo 備忘、money 記帳、singing 唱歌、eval 評鑑。只回傳按鈕。",
    S.obj(
        "screen" to S.enum("哪個畫面", app.agency.core.plan.Screen.values().map { it.name.lowercase() }),
        "arg" to S.str("補充參數，沒有就空字串"),
    ),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val screen = a.enum("screen", "畫面", app.agency.core.plan.Screen.values())
        val arg = a.text("arg").let { if (screen == app.agency.core.plan.Screen.CAMERA) it.uppercase() else it }
        val gate = Screens.feature(screen)
        if (gate != null && !gate.openAt(ctx.stage.stage)) {
            throw BadArg("「${gate.label}」還沒開放，不能打開這個畫面。")
        }
        val label = Screens.label(screen)
        return ToolOut(F.obj { put("button", label); put("note", "放了按鈕：$label") }, listOf(Card.Open(screen.name, arg, label)))
    }
}

/** What each screen is for, and which feature it belongs to (a screen of a closed feature is never offered). */
internal object Screens {
    fun feature(s: app.agency.core.plan.Screen): Feature? = when (s) {
        app.agency.core.plan.Screen.LESSONS, app.agency.core.plan.Screen.QUIZ -> Feature.LESSONS
        app.agency.core.plan.Screen.CAMERA -> Feature.MEALS
        app.agency.core.plan.Screen.CHECK_IN, app.agency.core.plan.Screen.CHECK_OUT -> Feature.CHECK_IN
        app.agency.core.plan.Screen.BODY -> Feature.WEIGHT
        app.agency.core.plan.Screen.EVAL -> Feature.WEEKLY_EVAL
        app.agency.core.plan.Screen.SINGING -> Feature.SINGING
        app.agency.core.plan.Screen.MEMO -> Feature.MEMO
        app.agency.core.plan.Screen.MONEY -> Feature.MONEY
        app.agency.core.plan.Screen.TIMER -> null
    }

    fun label(s: app.agency.core.plan.Screen): String = when (s) {
        app.agency.core.plan.Screen.LESSONS -> "打開理論課"
        app.agency.core.plan.Screen.QUIZ -> "開始測驗"
        app.agency.core.plan.Screen.TIMER -> "開始計時"
        app.agency.core.plan.Screen.CAMERA -> "拍照"
        app.agency.core.plan.Screen.CHECK_IN -> "簽到"
        app.agency.core.plan.Screen.CHECK_OUT -> "簽退"
        app.agency.core.plan.Screen.BODY -> "量體重"
        app.agency.core.plan.Screen.EVAL -> "打開評鑑"
        app.agency.core.plan.Screen.SINGING -> "打開唱歌"
        app.agency.core.plan.Screen.MEMO -> "打開備忘"
        app.agency.core.plan.Screen.MONEY -> "打開記帳"
    }
}

internal fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.content
