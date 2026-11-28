package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.Moment
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.extras.ThemeWeeks
import app.agency.core.lessons.Habits
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.TaskId
import app.agency.core.stage.Feature
import app.agency.core.store.Feel
import app.agency.core.store.Kind
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.TaskDone
import app.agency.core.week.WeekMath
import kotlinx.serialization.json.put

/** The task names a tool accepts: every [TaskId] plus the theme-week practice. */
internal object Tasks {
    val names: List<String> = TaskId.values().map { it.name } + ThemeWeeks.TASK_NAME

    fun feature(name: String): Feature? =
        TaskId.values().firstOrNull { it.name == name }?.feature ?: if (name == ThemeWeeks.TASK_NAME) Feature.THEME_WEEK else null

    fun title(name: String): String = TaskId.values().firstOrNull { it.name == name }?.title ?: "主題週小練習"

    fun parse(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it in names }
}

internal class LogTaskTool : BaseTool(
    "log_task",
    "記一項做完的例行項目或訓練：CORE 核心、STRENGTH 肌力、STRETCH 收操伸展、MORNING_STRETCH 起床伸展、VOCAL_BASICS 聲樂基本功、" +
        "CARDIO 體能、POSTURE 靠牆站姿、SUNSCREEN 擦防曬、WALK 快走、DANCE 舞蹈、VOCAL 聲樂練習、EXPRESSION 表情鏡頭、GROOMING 儀容" +
        "（note 寫做了哪幾項，例如「指甲」）、PERSONAL_TASK 個人任務、THEME_PRACTICE 主題週小練習。rounds 是做了幾輪（肌力用），" +
        "minutes 是幾分鐘，feel 是感覺 easy／ok／hard；沒說就 null。他說「訓練做完了」就把今天計畫裡還沒做的訓練各記一次。" +
        "減量只有程式能決定（生病、受傷、剛康復）；他累或痠不算。回傳今天完成幾項和下一件事。",
    S.obj(
        "task" to S.enum("哪一項", Tasks.names),
        "rounds" to S.int("做了幾輪；沒說就 null", nullable = true),
        "minutes" to S.int("做了幾分鐘；沒說就 null", nullable = true),
        "feel" to S.enum("感覺；沒說就 null", listOf("easy", "ok", "hard"), nullable = true),
        "note" to S.str("補充，例如儀容做了哪幾項；沒有就空字串"),
    ),
    null, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val task = Tasks.parse(a.strOrNull("task")) ?: throw BadArg("task 只能是：${Tasks.names.joinToString("、")}。")
        val rounds = a.intOrNull("rounds", "輪數", 0..50)
        val minutes = a.intOrNull("minutes", "分鐘", 0..600)
        val feel = a.enumOrNull("feel", "感覺", Feel.values())
        return record(ctx, task, rounds, minutes, feel, a.text("note"), 0)
    }

    companion object {
        /** Writes one task record; the timer screen calls this too (with the seconds he spent in other apps). */
        fun record(
            ctx: ToolCtx,
            task: String,
            said: Int?,
            minutes: Int?,
            feel: Feel?,
            note: String,
            awaySeconds: Int,
        ): ToolOut {
            val feature = Tasks.feature(task) ?: return ToolOut.error("沒有這一項：$task。")
            val title = Tasks.title(task)
            val open = feature.openAt(ctx.stage.stage)
            val before = Moment.of(ctx.store, ctx.clock, ctx.rules)
            val full = before.training.strengthRounds
            val unwell = before.state.sick || before.state.firstDayBack
            // Strength said without a number of rounds is the plan's own number: full, or the code's reduced one when unwell.
            val rounds = said ?: if (task == TaskId.STRENGTH.name && open && full > 0) {
                if (unwell) (full - 1).coerceAtLeast(1) else full
            } else null
            val reduced = task == TaskId.STRENGTH.name && open && unwell && rounds != null && rounds < full
            val rec = TaskDone(task, rounds, minutes, feel, reduced = reduced, awaySeconds = awaySeconds.coerceAtLeast(0), note = note)
            val e = F.write(ctx, Kind.TASK, rec)
            if (!open) return ToolOut(F.closed(feature, e) { put("task", title) })
            val m = Moment.of(ctx.store, ctx.clock, ctx.rules)
            val item = m.item("task:$task") ?: if (task == ThemeWeeks.TASK_NAME) m.item("theme") else null
            return ToolOut(F.obj {
                put("entry_id", e.id)
                put("task", title)
                rounds?.let { put("rounds", it) }
                minutes?.let { put("minutes", it) }
                if (task == TaskId.STRENGTH.name) {
                    put("full_rounds", full)
                    put("push_ups", m.training.pushUps)
                    put("squats", m.training.squats)
                    put("plank_seconds", m.training.plankSeconds)
                    if (reduced) put("reduced_by_code", "生病或剛康復，程式允許少一輪")
                    if (rounds != null && rounds < full && !reduced) put("short_rounds", "沒有做滿 $full 輪")
                }
                if (before.state.injured) put("injured", "受傷中，訓練由程式暫停；做了也照記")
                if (item == null) put("in_plan", false)
                if (awaySeconds > 0) put("away_seconds", awaySeconds)
                put("note", "已記：$title" + (rounds?.let { " $it 輪" } ?: "") + (minutes?.let { " $it 分鐘" } ?: ""))
                put("today", F.today(m))
            })
        }
    }
}

internal class OpenTimerTool : BaseTool(
    "open_timer",
    "放一個「開始計時」按鈕，讓他用計時器做一項訓練或例行項目（task 同 log_task 的名稱）。計時結束時程式會自己記錄，不用再 log_task。",
    S.obj("task" to S.enum("哪一項", Tasks.names)),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val task = Tasks.parse(a.strOrNull("task")) ?: throw BadArg("task 只能是：${Tasks.names.joinToString("、")}。")
        val feature = Tasks.feature(task)!!
        if (!feature.openAt(ctx.stage.stage)) throw BadArg("「${feature.label}」還沒開放，不能排進今天。")
        val m = F.moment(ctx)
        val item = m.item("task:$task")
        if (item?.status == ItemStatus.OFF) throw BadArg("「${item.title}」今天由程式取消了（${m.plan.adjustments.joinToString("；") { it.what }}）。")
        val title = Tasks.title(task)
        return ToolOut(
            F.obj { put("task", title); item?.let { put("item", F.item(it)) }; put("note", "放了計時按鈕：$title") },
            listOf(Card.Open("TIMER", task, "開始計時：$title")),
        )
    }
}

internal class HabitDoneTool : BaseTool(
    "habit_done",
    "他完成了今天的一個小習慣（計畫裡 habit: 開頭的項目；habit_id 是冒號後面的數字）。回傳今天完成幾項。",
    S.obj("habit_id" to S.int("習慣的編號")),
    Feature.LESSONS, direct = true,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("habit_id", "習慣編號")
        val day = F.day(ctx)
        val habit = Habits(ctx.store, ctx.clock).today(day).firstOrNull { it.id == id }
            ?: throw BadArg("今天沒有編號 $id 的習慣。今天的習慣：${Habits(ctx.store, ctx.clock).today(day).joinToString("、") { "#${it.id} ${it.name}" }.ifEmpty { "沒有" }}。")
        Habits(ctx.store, ctx.clock).markDone(id, day)
        val m = F.moment(ctx)
        return ToolOut(F.obj {
            put("habit", habit.name)
            put("note", "已記：${habit.name}")
            put("today", F.today(m))
        })
    }
}

internal class SetStatusTool : BaseTool(
    "set_status",
    "記錄他今天的狀態，程式會依規則自動調整今天的計畫：REST_DAY 休息日（不跑單，一個日曆週最多一次）、SICK 生病、" +
        "INJURED 受傷（刺痛、關節痛都算，先叫他停下）、RECOVERED 康復、EXAM_WEEK 考試週（理論課減量，一學期有次數上限）。" +
        "累、痠、想睡、不想做都不是生病，不要用這個。回傳程式改了今天哪些項目，照這個跟他說。",
    S.obj(
        "type" to S.enum("狀態", StatusType.values().map { it.name }),
        "note" to S.str("他怎麼說的，一句話"),
    ),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val type = a.enum("type", "狀態", StatusType.values())
        val day = F.day(ctx)
        val before = F.moment(ctx)
        when (type) {
            StatusType.REST_DAY -> {
                if (before.state.restDay) throw BadArg("今天已經是休息日了。")
                if (WeekMath.soFar(ctx.store, day, ctx.rules).restDayUsed) {
                    throw BadArg("這個日曆週已經休息過一天了，每週最多一天，所以今天不能再當休息日。今天的計畫照常。")
                }
            }
            StatusType.EXAM_WEEK -> {
                if (before.state.examWeek) throw BadArg("現在已經是考試週了（七天內記過）。")
                val limit = ctx.rules.study.examWeeksPerTerm
                val used = ctx.store.entries(day.minusDays(120), day, Kind.STATUS).count {
                    runCatching { it.read<Status>().type }.getOrNull() == StatusType.EXAM_WEEK
                }
                if (limit > 0 && used >= limit) throw BadArg("最近 120 天已經用了 $used 次考試週，上限 $limit 次，這次不能再減量。")
            }
            StatusType.RECOVERED -> if (!before.state.unwell) throw BadArg("他現在沒有記錄生病或受傷，不用記康復。")
            StatusType.SICK -> if (before.state.sick) throw BadArg("已經記錄生病中了（第 ${before.state.sickDays} 天）。")
            StatusType.INJURED -> if (before.state.injured) throw BadArg("已經記錄受傷中了。")
        }
        val e = F.write(ctx, Kind.STATUS, Status(type, a.text("note")))
        val after = F.moment(ctx)
        val changes = diff(before.plan.items, after.plan.items)
        val newAdj = after.plan.adjustments.filter { it !in before.plan.adjustments }
        val reduced = after.state.unwell || after.state.firstDayBack
        return ToolOut(F.obj {
            put("entry_id", e.id)
            put("status", word(type))
            put("changed_items", F.strs(changes))
            put("adjustments", F.strs(newAdj.map { "${it.what}（${it.why}）" }))
            put("training_reduced_by_code", reduced)
            if (after.state.sickDays >= 3) put("see_doctor", "已經連續 ${after.state.sickDays} 天不舒服，請他去看醫生")
            put("note", "已記：${word(type)}")
            put("today", F.today(after))
        })
    }

    private fun diff(a: List<PlanItem>, b: List<PlanItem>): List<String> {
        val out = ArrayList<String>()
        val byId = a.associateBy { it.id }
        for (i in b) {
            val old = byId[i.id]
            when {
                old == null -> out += "新增：${i.title}"
                old.status != ItemStatus.OFF && i.status == ItemStatus.OFF -> out += "取消：${i.title}"
                old.status == ItemStatus.OFF && i.status != ItemStatus.OFF -> out += "恢復：${i.title}"
                old.detail != i.detail -> out += "${i.title}：${old.detail} → ${i.detail}"
            }
        }
        val ids = b.map { it.id }.toSet()
        a.filter { it.id !in ids }.forEach { out += "拿掉：${it.title}" }
        return out
    }

    private fun word(t: StatusType) = when (t) {
        StatusType.REST_DAY -> "休息日"
        StatusType.SICK -> "生病"
        StatusType.INJURED -> "受傷"
        StatusType.RECOVERED -> "康復"
        StatusType.EXAM_WEEK -> "考試週"
    }
}
