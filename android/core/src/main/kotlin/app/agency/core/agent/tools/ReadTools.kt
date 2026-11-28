package app.agency.core.agent.tools

import app.agency.core.agent.Moment
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.base.Days
import app.agency.core.base.J
import app.agency.core.extras.Evaluations
import app.agency.core.extras.RealTasks
import app.agency.core.extras.SongBook
import app.agency.core.extras.SpotChecks
import app.agency.core.extras.SpotStatus
import app.agency.core.extras.ThemeWeeks
import app.agency.core.stage.Feature
import app.agency.core.stage.Stages
import app.agency.core.store.DayClose
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.week.Income
import app.agency.core.week.WeekMath
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.serialization.json.put

internal class GetDayTool : BaseTool(
    "get_day",
    "查某一天的紀錄和完成度（date 是 YYYY-MM-DD；今天也可以）。重新算或核對數字時用這個，不要自己算。",
    S.obj("date" to S.str("日期 YYYY-MM-DD")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val raw = a.str("date", "日期")
        val day = runCatching { LocalDate.parse(raw) }.getOrNull() ?: throw BadArg("日期要寫成 YYYY-MM-DD，收到「$raw」。")
        val today = F.day(ctx)
        if (day.isAfter(today)) throw BadArg("$day 還沒到。")
        val m = if (day == today) F.moment(ctx) else {
            Moment.at(ctx.store, ctx.clock, ctx.rules, day, Days.at(day.plusDays(1), LocalTime.of(Days.START_HOUR, 0), ctx.clock.zone))
        }
        val close = ctx.store.entries(day, Kind.DAY_CLOSE).lastOrNull()?.let { runCatching { it.read<DayClose>() }.getOrNull() }
        return ToolOut(F.obj {
            put("date", day.toString())
            put("done", close?.done ?: m.plan.done)
            put("total", close?.total ?: m.plan.total)
            close?.let { put("perfect", it.perfect) }
            put("items", F.arr(m.plan.items.map(F::item)))
            put("meals", F.arr(m.state.meals.map { r ->
                F.obj {
                    put("entry_id", r.entryId); put("slot", Meals.slotWord(r.meal.slot)); put("kcal", r.kcal)
                    put("protein_g", F.num(r.proteinG)); put("items", F.strs(r.meal.items.map { "${it.name} ${it.kcal} 大卡" }))
                }
            }))
            put("kcal", m.state.kcal)
            put("protein_g", F.num(m.state.proteinG))
            put("water_ml", m.state.waterMl)
            if (m.state.income > 0) put("income", m.state.income)
            put("violations", F.strs(m.state.violations.map(F::violationWord)))
            if (day == today) put("today", F.today(m))
        })
    }
}

internal class GetWeekTool : BaseTool(
    "get_week",
    "這個日曆週（星期一到日）到現在的情況：每天完成度、跑單收入和目標、休息日用了沒、體能做了幾次，和這一輪階段的進度。",
    S.obj(),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val day = F.day(ctx)
        val w = WeekMath.soFar(ctx.store, day, ctx.rules)
        val closes = ctx.store.entries(w.weekStart, day, Kind.DAY_CLOSE).mapNotNull { e ->
            runCatching { e.read<DayClose>() }.getOrNull()?.let { e.day to it }
        }
        val roundFrom = runCatching { LocalDate.parse(ctx.stage.startedOn) }.getOrNull() ?: day
        val round = ctx.store.entries(roundFrom, day, Kind.DAY_CLOSE).mapNotNull { runCatching { it.read<DayClose>() }.getOrNull() }
        val rd = round.sumOf { it.done }
        val rt = round.sumOf { it.total }
        return ToolOut(F.obj {
            put("week_start", w.weekStart.toString())
            put("days", F.arr(closes.map { (d, c) ->
                F.obj { put("date", d.toString()); put("done", c.done); put("total", c.total); put("violations", c.violations) }
            }))
            if (Feature.RIDE.openAt(ctx.stage.stage)) put("income", IncomeFacts.json(Income.week(ctx.store, day, ctx.rules)))
            put("rest_day_used", w.restDayUsed)
            if (Feature.CARDIO.openAt(ctx.stage.stage)) put("cardio_done", w.cardioDone)
            put("stage", Stages.label(ctx.stage, day))
            put("round_done", rd)
            put("round_total", rt)
            put("round_pct", if (rt == 0) 100 else rd * 100 / rt)
            put("round_violations", round.sumOf { it.violations })
            put("pass_pct", ctx.rules.level.passPct)
        })
    }
}

internal class ReadRulesTool : BaseTool(
    "read_rules",
    "在他貼的原始規章文件裡找有某個關鍵字的段落（例如「宵夜」「炸」），回傳原文摘錄。不確定規定怎麼寫時用這個。",
    S.obj("keyword" to S.str("關鍵字")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val kw = a.str("keyword", "關鍵字")
        val text = RulesDoc.text(ctx.store)
        if (text.isBlank()) return ToolOut.error("還沒有他貼的原始規章文件。")
        val lines = text.lines()
        val hits = lines.indices.filter { lines[it].contains(kw) }
        if (hits.isEmpty()) return ToolOut(F.obj { put("found", 0); put("note", "原始文件裡沒有「$kw」") })
        val out = StringBuilder()
        val used = HashSet<Int>()
        for (i in hits) {
            for (j in (i - 1).coerceAtLeast(0)..(i + 1).coerceAtMost(lines.lastIndex)) {
                if (used.add(j)) out.append(lines[j].trim()).append('\n')
            }
            if (out.length > MAX) break
        }
        return ToolOut(F.obj { put("found", hits.size); put("excerpt", out.toString().take(MAX)) })
    }

    companion object {
        const val MAX = 1500
    }
}

/** Doc `rules_text` (his pasted document): stored as a JSON string, read leniently. */
object RulesDoc {
    fun text(store: app.agency.core.store.Store): String {
        val raw = store.doc(Doc.RULES_TEXT) ?: return ""
        return runCatching { J.decodeFromString<String>(raw) }.getOrElse { raw }
    }

    fun handbook(store: app.agency.core.store.Store): String {
        val raw = store.doc(Doc.HANDBOOK) ?: return ""
        return runCatching { J.decodeFromString<String>(raw) }.getOrElse { raw }
    }
}

internal class RealTaskTool : BaseTool(
    "real_task",
    "這週的真人任務（只跟他已經認識的人）。done=false：查這週的任務是什麼；done=true：他說做完了（text 是任務內容，空字串＝這週的那個）。",
    S.obj("text" to S.str("任務內容；查詢或這週的任務就空字串"), "done" to S.bool("他做完了")),
    Feature.REAL_TASK,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.REAL_TASK, null))
        val tasks = RealTasks(ctx.store, ctx.clock)
        val day = F.day(ctx)
        return if (a.bool("done")) {
            val t = tasks.done(a.text("text")) ?: throw BadArg("找不到這個還沒完成的任務。")
            ToolOut(F.obj { put("task", t.text); put("done", true); put("note", "真人任務完成：${t.text}") })
        } else {
            val t = tasks.current(day)
            ToolOut(F.obj { put("task", t.text); put("done", t.done); put("note", "這週的真人任務：${t.text}") })
        }
    }
}

internal class EvalStatusTool : BaseTool(
    "eval_status",
    "第四階段的事今天有哪些要做：每週評鑑、月評、突擊檢查、全身對比照、主題週。回傳哪些今天到期、哪些做了。",
    S.obj(),
    Feature.WEEKLY_EVAL,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        if (!isOpen(ctx)) return ToolOut(F.closed(Feature.WEEKLY_EVAL, null))
        val day = F.day(ctx)
        val evals = Evaluations(ctx.store, ctx.clock, ctx.rules)
        val spot = SpotChecks(ctx.store, ctx.clock, ctx.rules).todays(day).lastOrNull()?.second
        return ToolOut(F.obj {
            put("weekly_due", evals.weeklyDue(day, ctx.stage))
            put("weekly_done", evals.weekly(day) != null)
            put("monthly_due", evals.monthlyDue(day, ctx.stage))
            put("monthly_done", evals.monthly(YearMonth.from(day)) != null)
            spot?.let { put("spot_check", if (it.status == SpotStatus.ASKED) "等他傳${it.ask.zh}照片" else if (it.status == SpotStatus.DONE) "完成" else "錯過") }
            ThemeWeeks.current(day, ctx.stage)?.let { put("theme", "${it.name}：${it.practice}"); put("theme_done", ThemeWeeks.doneOn(ctx.store, day)) }
        })
    }
}

internal class SongSummaryTool : BaseTool(
    "song_summary",
    "讀一次錄好的唱歌分析數字（entry_id 是那首歌的編號）。評論只能用這些數字。",
    S.obj("entry_id" to S.int("那首歌的編號")),
    Feature.SINGING,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("entry_id", "編號")
        val book = SongBook(ctx.store, ctx.clock)
        val rec = book.song(id) ?: throw BadArg("找不到編號 #$id 的唱歌紀錄。")
        return ToolOut(F.obj {
            put("entry_id", id)
            put("title", rec.title)
            put("duration_sec", F.num(rec.durationSec))
            put("in_tune_pct", rec.inTunePct)
            rec.lowestName?.let { put("lowest", it) }
            rec.highestName?.let { put("highest", it) }
            put("range_semitones", rec.rangeSemitones)
            put("longest_phrase_sec", F.num(rec.longestPhraseSec))
            put("loudness_db", rec.loudnessDb)
            put("volume_steady_pct", rec.volumeSteadyPct)
            put("vocal_score", book.vocalScore(rec))
        })
    }
}
