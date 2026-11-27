package app.agency.core.engine

import app.agency.core.model.TrainingKind
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.PracticeSpec
import app.agency.core.schedule.ScheduleSpec
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import app.agency.core.schedule.Timetable
import app.agency.core.schedule.DayType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** How a practice item is handed in: a guided timer, a tap (or his words), a photo, or the evaluation screen. */
enum class HandIn { TIMER, TAP, PHOTO, EVAL }

/** One practice item of rollout weeks 3 and 4: its slot, its record kind (same name) and how it is handed in. */
data class PracticeItem(val key: SlotKey, val kind: TrainingKind, val handIn: HandIn)

/**
 * The practice items beyond the training block (定稿 §3, §6, §10, §11), in code: 起床伸展 5 分鐘, 聲樂基本功 10 分鐘（彈唇、滑音、
 * 音階、咬字）, 靠牆站姿 3 分鐘, 防曬 before the run, on no-class days 舞蹈 40 + 聲樂 20 before the first meal and 表情鏡頭 10 twice a
 * week, the month's 個人任務 10 minutes, the 學了就用 habits, the 主題週 practical, 突擊檢查, the Sunday 全身對比照, 每週評鑑 and 月評.
 * Each is a [SlotKey] and a [TrainingKind] of the same name, so the rule engine, the stickers and the week's count treat them
 * like any other item.
 */
object Practice {
    val ITEMS: List<PracticeItem> = listOf(
        PracticeItem(SlotKey.MORNING_STRETCH, TrainingKind.MORNING_STRETCH, HandIn.TIMER),
        PracticeItem(SlotKey.VOCAL_BASICS, TrainingKind.VOCAL_BASICS, HandIn.TIMER),
        PracticeItem(SlotKey.POSTURE, TrainingKind.POSTURE, HandIn.TIMER),
        PracticeItem(SlotKey.SUNSCREEN, TrainingKind.SUNSCREEN, HandIn.TAP),
        PracticeItem(SlotKey.DANCE, TrainingKind.DANCE, HandIn.TIMER),
        PracticeItem(SlotKey.VOCAL_PRACTICE, TrainingKind.VOCAL_PRACTICE, HandIn.TIMER),
        PracticeItem(SlotKey.EXPRESSION, TrainingKind.EXPRESSION, HandIn.TIMER),
        PracticeItem(SlotKey.PERSONAL_TASK, TrainingKind.PERSONAL_TASK, HandIn.TIMER),
        PracticeItem(SlotKey.HABIT, TrainingKind.HABIT, HandIn.TAP),
        PracticeItem(SlotKey.THEME_TASK, TrainingKind.THEME_TASK, HandIn.TAP),
        PracticeItem(SlotKey.SURPRISE_CHECK, TrainingKind.SURPRISE_CHECK, HandIn.PHOTO),
        PracticeItem(SlotKey.BODY_PHOTO, TrainingKind.BODY_PHOTO, HandIn.PHOTO),
        PracticeItem(SlotKey.WEEKLY_EVAL, TrainingKind.WEEKLY_EVAL, HandIn.EVAL),
        PracticeItem(SlotKey.MONTHLY_EVAL, TrainingKind.MONTHLY_EVAL, HandIn.EVAL),
    )

    fun of(key: SlotKey): PracticeItem? = ITEMS.firstOrNull { it.key == key }
    fun of(kind: TrainingKind): PracticeItem? = ITEMS.firstOrNull { it.kind == kind }

    /** The timed items, in the order the home screen shows their buttons. */
    val TIMED: List<TrainingKind> = ITEMS.filter { it.handIn == HandIn.TIMER }.map { it.kind }

    /** His words for a tap item (「防曬擦好了」), by kind. */
    val TAP_WORDS: Map<String, TrainingKind> = buildMap {
        listOf("防曬擦好了", "擦好防曬了", "擦防曬了", "防曬擦了").forEach { put(it, TrainingKind.SUNSCREEN) }
        listOf("主題實作完成", "主題週實作完成", "主題實作做完了").forEach { put(it, TrainingKind.THEME_TASK) }
        listOf("小習慣完成", "小習慣做完了", "小習慣做好了").forEach { put(it, TrainingKind.HABIT) }
    }

    /** His words for a timed item done without the timer (typed, so 「未證實」). */
    val DONE_WORDS: Map<String, TrainingKind> = buildMap {
        listOf("起床伸展完成", "起床伸展做完了").forEach { put(it, TrainingKind.MORNING_STRETCH) }
        listOf("聲樂基本功完成", "基本功完成", "聲樂基本功做完了").forEach { put(it, TrainingKind.VOCAL_BASICS) }
        listOf("站姿完成", "站姿做完了", "靠牆站完了").forEach { put(it, TrainingKind.POSTURE) }
        listOf("舞蹈完成", "舞蹈練完了", "跳舞練完了").forEach { put(it, TrainingKind.DANCE) }
        listOf("聲樂練習完成", "聲樂練完了").forEach { put(it, TrainingKind.VOCAL_PRACTICE) }
        listOf("表情練習完成", "表情鏡頭完成", "表情練完了").forEach { put(it, TrainingKind.EXPRESSION) }
        listOf("個人任務完成", "個人任務做完了").forEach { put(it, TrainingKind.PERSONAL_TASK) }
    }

    /** Minutes of a timed item by the plan. */
    fun minutes(kind: TrainingKind, p: PracticeSpec): Int = when (kind) {
        TrainingKind.MORNING_STRETCH -> p.morningStretchMin
        TrainingKind.VOCAL_BASICS -> p.vocalBasicsMin
        TrainingKind.POSTURE -> p.postureMin
        TrainingKind.DANCE -> p.danceMin
        TrainingKind.VOCAL_PRACTICE -> p.vocalPracticeMin
        TrainingKind.EXPRESSION -> p.expressionMin
        TrainingKind.PERSONAL_TASK -> p.personalTaskMin
        else -> 0
    }

    private fun step(name: String, icon: MoveIcon, how: String, share: Int) = RoutineStep(name, icon, how, seconds = share)

    /** Parts sharing [minutes] by weight (whole seconds, the remainder on the first ones). */
    private fun shared(minutes: Int, parts: List<Pair<RoutineStep, Int>>): List<RoutineStep> {
        val total = minutes.coerceAtLeast(1) * 60
        val weights = parts.sumOf { it.second }
        val secs = parts.map { total * it.second / weights }.toMutableList()
        var left = total - secs.sum()
        var i = 0
        while (left > 0) { secs[i % secs.size]++; left--; i++ }
        return parts.mapIndexed { k, (s, _) -> s.copy(seconds = secs[k]) }
    }

    /** The guided steps of a timed item ([task] is the month's weak point for the 個人任務). */
    fun steps(kind: TrainingKind, p: PracticeSpec): List<RoutineStep> {
        val m = minutes(kind, p)
        return when (kind) {
            TrainingKind.MORNING_STRETCH -> shared(m, listOf(
                step("脖子", MoveIcon.REST, "頭慢慢往左右倒，再慢慢轉一圈，肩膀放鬆。", 0) to 1,
                step("肩膀繞圈", MoveIcon.CHEST, "手放肩上，手肘往前畫大圈，再往後畫。", 0) to 1,
                step("側彎", MoveIcon.REST, "一手舉高往另一邊彎，腰側拉開；一半時間換邊。", 0) to 1,
                step("髖部", MoveIcon.HIP, "弓箭步，屁股往前推；一半時間換邊。", 0) to 1,
                step("腿後", MoveIcon.LEG, "站著一腳往前伸直，上身從髖部往前彎；一半時間換邊。", 0) to 1,
            ))
            TrainingKind.VOCAL_BASICS -> shared(m, listOf(
                step("彈唇", MoveIcon.REST, "嘴唇放鬆，吹氣讓嘴唇抖動，帶一點聲音，從低滑到高再回來。", 0) to 1,
                step("警報滑音", MoveIcon.REST, "用「嗚」從最低滑到最高再滑下來，像警報聲，不要卡住。", 0) to 1,
                step("音階", MoveIcon.REST, "用「啊」唱五個音上去再下來，一次升半音；不要漏氣，肚子撐住。", 0) to 1,
                step("咬字", MoveIcon.REST, "短促清楚地念「八、怕、媽、發、搭、他」，嘴型做滿，再快一點念一次。", 0) to 1,
            ))
            TrainingKind.POSTURE -> listOf(step("靠牆站", MoveIcon.REST, "後腦、肩胛骨、屁股、腳跟貼牆，下巴微收，肩膀往下放，肚子收，呼吸不要停。", m * 60))
            TrainingKind.DANCE -> shared(m, listOf(
                step("暖身", MoveIcon.HIP, "原地小跳、轉肩、轉髖，把身體叫醒。", 0) to 1,
                step("律動彈跳", MoveIcon.JUMPING_JACK, "膝蓋放鬆，跟著拍子上下彈，手跟著擺，穩定不要停。", 0) to 2,
                step("身體分離", MoveIcon.REST, "只動一個地方：頭、肩膀、胸口、髖部，各自前後左右，其他地方不動。", 0) to 2,
                step("波浪", MoveIcon.REST, "從頭到胸口到髖部到膝蓋，一節一節往下波，再往上。", 0) to 1,
                step("這週的舞段", MoveIcon.JUMPING_JACK, "練這週要交的那一段，一次一小節，最後完整跳兩次。", 0) to 2,
            ))
            TrainingKind.VOCAL_PRACTICE -> shared(m, listOf(
                step("暖聲", MoveIcon.REST, "彈唇、滑音各一分鐘，喉嚨放鬆。", 0) to 1,
                step("這週的歌（分段）", MoveIcon.REST, "一句一句唱，唱不穩的那句重複三次。", 0) to 2,
                step("整首唱一次", MoveIcon.REST, "從頭到尾不停唱一次，像在台上。", 0) to 1,
            ))
            TrainingKind.EXPRESSION -> shared(m, listOf(
                step("三種笑", MoveIcon.REST, "對鏡頭：抿嘴笑、露齒笑、眼睛笑，各停五秒，來回做。", 0) to 1,
                step("眼神", MoveIcon.REST, "看鏡頭、往旁邊看、再看回鏡頭；眼神有焦點，不要飄。", 0) to 1,
                step("表情切換", MoveIcon.REST, "跟著一首歌的副歌，冷酷、可愛、自信三種表情切換。", 0) to 1,
            ))
            TrainingKind.PERSONAL_TASK -> listOf(step("這個月的個人任務", MoveIcon.REST, MonthlyEval.taskText(p.personalTask), m * 60))
            else -> emptyList()
        }
    }

    /** Whether [day] is the last Sunday of its month (the 月評 day; that Sunday has no separate 每週評鑑). */
    fun monthEnd(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SUNDAY && day.plusDays(7).month != day.month
}

/**
 * 突擊檢查 (定稿 §11): two or three a week at times he cannot know, a photo within ten minutes. Only after class until the
 * latest departure on a class day (17:05–17:45, before the run) and in the daytime of a no-class day (13:30–15:50); never in
 * class, never on the run. Picked by code from the week's Monday (the same week always gives the same checks).
 */
object SurpriseChecks {
    val WHAT = listOf("站姿：靠牆站好，全身入鏡", "水壺：拍今天的水壺，看喝到哪裡", "穿搭：今天的全身穿搭")

    data class Check(val minute: Int, val what: String)

    fun week(monday: LocalDate, schedule: ScheduleSpec): Map<LocalDate, Check> {
        val rnd = java.util.Random(monday.toEpochDay() * 7919L + 17L)
        val n = 2 + rnd.nextInt(2)
        val days = (0L..6L).map { monday.plusDays(it) }.shuffled(rnd).take(n).sorted()
        return days.mapIndexed { i, d ->
            val (from, to) = if (Timetable.type(d, schedule) == DayType.CLASS) "17:05" to "17:45" else "13:30" to "15:50"
            val a = DayClock.minute(from)
            val span = (DayClock.minute(to) - a) / 5
            d to Check(a + 5 * rnd.nextInt(span + 1), WHAT[(i + rnd.nextInt(WHAT.size)) % WHAT.size])
        }.toMap()
    }

    fun on(day: LocalDate, schedule: ScheduleSpec): Check? = week(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), schedule)[day]
}

/** 主題週 (定稿 §11): one theme a week in turn, and the one extra small practical of that subject (due Saturday). */
object Themes {
    data class Theme(val name: String, val subject: String, val task: String)

    val ALL = listOf(
        Theme("表情週", "表情鏡頭", "對手機鏡頭練三種表情各一分鐘，拍一張最好的。"),
        Theme("體態週", "體態管理", "拍一張側面站姿照，跟上週比肩膀和骨盆。"),
        Theme("英文週", "語言", "用英文自我介紹三十秒，錄下來聽一次。"),
        Theme("聲樂週", "聲樂", "同一句歌錄三次，挑最穩的一次留下。"),
        Theme("舞蹈週", "舞蹈", "這週的舞段完整跳一次錄下來，自己看一次。"),
        Theme("保養週", "保養", "照步驟做一次完整保養：洗臉、化妝水、乳液、防曬。"),
    )

    private val EPOCH = LocalDate.of(2026, 1, 5)

    fun of(day: LocalDate): Theme {
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val w = ChronoUnit.WEEKS.between(EPOCH, monday)
        return ALL[Math.floorMod(w, ALL.size.toLong()).toInt()]
    }
}

/** A practice item that is a slot today ([SlotKind.PRACTICE]). */
fun SlotKey.isPractice(): Boolean = kind == SlotKind.PRACTICE
