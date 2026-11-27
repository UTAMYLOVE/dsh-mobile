package app.agency.core.engine

/** The simple built-in picture of a move (the screen draws it; no picture is ever fetched). */
enum class MoveIcon { HALF_BOAT, HALF_SITUP, KNEE_HUG, BICYCLE, LEG_RAISE, SIDE_PLANK_LEFT, SIDE_PLANK_RIGHT, PUSH_UP, SQUAT, PLANK, ROW, BRIDGE, REST, HIP, LEG, CHEST, BACK, JUMPING_JACK, BURPEE, SQUAT_JUMP }

/**
 * One step of a guided routine: a move held for [seconds] (a timed step moves on by itself) or done [reps] times (he taps
 * 下一個 when the reps are done), or a rest. [how] is the one-line text of how to do it; [round] is the strength round.
 */
data class RoutineStep(
    val name: String,
    val icon: MoveIcon,
    val how: String,
    val seconds: Int? = null,
    val reps: Int? = null,
    val round: Int? = null,
    val rest: Boolean = false,
) {
    /** What the screen shows as the amount: 「60 秒」, 「15 下」. */
    val amount: String get() = reps?.let { "$it 下" } ?: seconds?.let { "$it 秒" }.orEmpty()
}

/** A strength move of the rounds, with its amount: [reps] or, for the plank, [seconds]. [key] names it in the report. */
data class StrengthMove(val key: String, val name: String, val icon: MoveIcon, val how: String, val reps: Int? = null, val seconds: Int? = null)

/**
 * The guided routines of plan v3 §7, in code (pure): the core 7 minutes exactly as Ahyeon showed it (half boat, HALF sit-ups
 * only, knee hug, bicycle crunch, advanced leg raise, side plank left and right, a minute each), the strength rounds of the
 * current progression step (20 seconds of rest between moves), and the 10-minute stretch (hips, legs, chest, back).
 */
object Routine {
    const val STEP_SECONDS = 60
    const val REST_SECONDS = 20

    val CORE: List<RoutineStep> = listOf(
        RoutineStep("半船式", MoveIcon.HALF_BOAT, "坐著，膝蓋彎起來，上身往後靠、腳離地，手往前伸，撐住不動。", STEP_SECONDS),
        RoutineStep("半仰臥起坐", MoveIcon.HALF_SITUP, "躺著屈膝，只把肩膀抬離地板再慢慢放下；不要整個坐起來，保護腰。", STEP_SECONDS),
        RoutineStep("抱膝", MoveIcon.KNEE_HUG, "坐著上身微微後仰，雙腳往胸口收、手抱膝，再把腳伸出去，來回做。", STEP_SECONDS),
        RoutineStep("單車捲腹", MoveIcon.BICYCLE, "躺著手扶頭，右手肘碰左膝、左手肘碰右膝，像踩腳踏車，慢慢換邊。", STEP_SECONDS),
        RoutineStep("進階抬腿", MoveIcon.LEG_RAISE, "躺著雙腳併攏伸直，慢慢抬高再慢慢放下，下背貼地，腳不碰地。", STEP_SECONDS),
        RoutineStep("側棒式（左）", MoveIcon.SIDE_PLANK_LEFT, "左手肘撐地，身體側過來成一直線，屁股不要掉下去。", STEP_SECONDS),
        RoutineStep("側棒式（右）", MoveIcon.SIDE_PLANK_RIGHT, "換右手肘撐地，身體成一直線，撐住。", STEP_SECONDS),
    )

    val PUSH_UP = StrengthMove("push_up", "伏地挺身", MoveIcon.PUSH_UP, "雙手比肩寬，身體成一直線，胸口降到快碰地再推起來。", reps = 15)
    val PUSH_UP_FEET_UP = StrengthMove("push_up", "腳墊高伏地挺身", MoveIcon.PUSH_UP, "腳放在床邊或椅子上，身體成一直線，胸口降下去再推起來。", reps = 15)
    val SPLIT_SQUAT = StrengthMove("squat", "分腿蹲", MoveIcon.SQUAT, "一腳在前一腳在後，三秒慢慢蹲下、後膝快碰地停一秒再站起來；每邊 10 下。", reps = 20)
    /** Owner 2026-11-26: 20 slow squats (3 seconds down, a second at the bottom) and a 60-second plank. */
    val SQUAT = StrengthMove("squat", "慢速深蹲", MoveIcon.SQUAT, "雙腳與肩同寬，三秒慢慢蹲下、最低點停一秒，再站起來；膝蓋對腳尖。", reps = 20)
    val PLANK = StrengthMove("plank", "棒式", MoveIcon.PLANK, "手肘撐地，身體成一直線，肚子收緊，不要塌腰。", seconds = 60)
    val ROW = StrengthMove("row", "背包划船", MoveIcon.ROW, "背包裝書或水，彎腰背打直，把背包往肚子拉，再慢慢放下。", reps = 12)
    val BRIDGE = StrengthMove("bridge", "臀橋", MoveIcon.BRIDGE, "躺著屈膝，腳踩地，屁股往上推到身體成一直線，夾緊再放下。", reps = 15)

    /**
     * The moves of one round at progression [step] (0 the three original moves; 1 + backpack row; 2 + glute bridge; 3 a fourth
     * round; 4–5 push-ups 18, 20; 6–7 plank 75, 90 seconds; 8 push-ups with the feet up; 9 split squats).
     */
    fun moves(step: Int): List<StrengthMove> {
        val push = when {
            step >= 8 -> PUSH_UP_FEET_UP
            step >= 5 -> PUSH_UP.copy(reps = 20)
            step >= 4 -> PUSH_UP.copy(reps = 18)
            else -> PUSH_UP
        }
        val plank = when {
            step >= 7 -> PLANK.copy(seconds = 90)
            step >= 6 -> PLANK.copy(seconds = 75)
            else -> PLANK
        }
        val squat = if (step >= 9) SPLIT_SQUAT else SQUAT
        return listOfNotNull(push, squat, plank, ROW.takeIf { step >= 1 }, BRIDGE.takeIf { step >= 2 })
    }

    /** The core at its ladder [step]: 60, 75, 86 seconds a move; from step 3 the advanced moves. */
    fun core(step: Int): List<RoutineStep> {
        val seconds = when {
            step >= 2 -> 86
            step >= 1 -> 75
            else -> STEP_SECONDS
        }
        val moves = if (step >= 3) CORE.mapIndexed { i, m ->
            when (i) {
                0 -> m.copy(name = "船式", how = "坐著雙腳伸直抬起，上身往後靠，身體和腿折成一個尖角，手往前伸，撐住不動。")
                1 -> m.copy(name = "完整捲腹", how = "躺著屈膝，肩膀和上背慢慢捲起來再放下，腰貼地，不要用脖子出力。")
                4 -> m.copy(name = "懸空抬腿", how = "躺著雙腳併攏，腳離地十公分撐住，再慢慢抬高放下，下背貼地。")
                else -> m
            }
        } else CORE
        return moves.map { it.copy(seconds = seconds) }
    }

    /** The cardio at its ladder [step]: work seconds a minute 45, 50, 55; from step 3 twelve minutes. */
    fun cardioWork(step: Int): Int = when {
        step >= 2 -> 55
        step >= 1 -> 50
        else -> CARDIO_WORK_SECONDS
    }

    fun cardioMinutes(step: Int, base: Int): Int = if (step >= 3) base + 2 else base

    /** Every step of the strength rounds: each move of each round, a rest between two moves. */
    fun strength(step: Int, rounds: Int): List<RoutineStep> = buildList {
        val moves = moves(step)
        for (r in 1..rounds) {
            moves.forEachIndexed { i, m ->
                add(RoutineStep(m.name, m.icon, m.how, seconds = m.seconds, reps = m.reps, round = r))
                val last = r == rounds && i == moves.lastIndex
                if (!last) add(RoutineStep("休息", MoveIcon.REST, "喘口氣，喝一小口水。", REST_SECONDS, round = r, rest = true))
            }
        }
    }

    /** The stretch of [minutes] (10 by plan): hips, legs, chest, back, the time shared out (whole seconds). */
    fun stretch(minutes: Int): List<RoutineStep> {
        val parts = listOf(
            RoutineStep("髖部伸展", MoveIcon.HIP, "弓箭步，後腳膝蓋著地，屁股往前推，感覺大腿前側和髖部拉開；一半時間換邊。"),
            RoutineStep("腿後伸展", MoveIcon.LEG, "坐著一腳伸直，上身從髖部往前彎，手往腳尖伸；一半時間換邊。"),
            RoutineStep("胸口伸展", MoveIcon.CHEST, "手扶牆或門框，身體慢慢轉開，胸口打開，肩膀放鬆；一半時間換邊。"),
            RoutineStep("背部伸展", MoveIcon.BACK, "跪坐，手往前伸、額頭往地板放，背慢慢拉長，深呼吸。"),
        )
        val total = minutes.coerceAtLeast(1) * 60
        return parts.mapIndexed { i, p -> p.copy(seconds = total / parts.size + if (i < total % parts.size) 1 else 0) }
    }

    /** One minute of the cardio: [work] seconds of the move, the rest of the minute to breathe. */
    const val CARDIO_WORK_SECONDS = 45

    /**
     * The 10-minute 體能 (owner 2026-11-26: 開合跳、波比跳、深蹲跳): [minutes] minutes, each one move of [work] seconds then a
     * rest to the full minute, the three moves in turn.
     */
    fun cardio(minutes: Int = 10, work: Int = CARDIO_WORK_SECONDS): List<RoutineStep> {
        val moves = listOf(
            RoutineStep("開合跳", MoveIcon.JUMPING_JACK, "雙腳跳開、手舉過頭，再跳回併攏、手放下；落地輕一點。"),
            RoutineStep("波比跳", MoveIcon.BURPEE, "蹲下手撐地，雙腳往後跳成伏地挺身姿勢，再跳回來站起、往上跳。"),
            RoutineStep("深蹲跳", MoveIcon.SQUAT_JUMP, "蹲下到大腿約平行地面，往上跳，落地時膝蓋微彎直接蹲下。"),
        )
        val w = work.coerceIn(10, 60)
        return buildList {
            for (i in 0 until minutes.coerceAtLeast(1)) {
                add(moves[i % moves.size].copy(seconds = w, round = i + 1))
                if (w < 60 && i < minutes - 1) add(RoutineStep("休息", MoveIcon.REST, "喘口氣，下一個馬上來。", 60 - w, round = i + 1, rest = true))
            }
        }
    }

    /** The timed seconds of [steps] (what a timer takes at least, reps not counted). */
    fun timedSeconds(steps: List<RoutineStep>): Int = steps.sumOf { it.seconds ?: 0 }
}

/** What the runner says happened: a new step began (the screen beeps and buzzes), or the routine is over. */
sealed interface RunnerEvent {
    data class StepStarted(val index: Int) : RunnerEvent
    data object Finished : RunnerEvent
}

/**
 * The guided timer's state machine (pure; the screen calls [tick] with the time that passed and plays the events): a timed
 * step counts down and moves on by itself, a rep step waits for [next]; [pause] stops the count. [elapsedMs] is the time it
 * really ran (paused time not counted), which the report carries.
 */
class RoutineRunner(val steps: List<RoutineStep>) {
    init {
        require(steps.isNotEmpty()) { "empty routine" }
    }

    var index: Int = 0
        private set
    var remainingMs: Long = steps[0].seconds?.times(1000L) ?: 0L
        private set
    var started: Boolean = false
        private set
    var paused: Boolean = false
        private set
    var finished: Boolean = false
        private set
    var elapsedMs: Long = 0L
        private set

    val current: RoutineStep get() = steps[index]
    val upcoming: RoutineStep? get() = steps.getOrNull(index + 1)

    /** Whole seconds left on a timed step (rounded up, so 「1」 shows until it is over). */
    val remainingSeconds: Int get() = ((remainingMs + 999) / 1000).toInt()

    val running: Boolean get() = started && !paused && !finished

    fun start(): List<RunnerEvent> {
        if (started) return emptyList()
        started = true
        return listOf(RunnerEvent.StepStarted(0))
    }

    fun pause() {
        if (running) paused = true
    }

    fun resume() {
        if (started && !finished) paused = false
    }

    /** [ms] passed: count down a timed step, moving on (maybe more than one step) when its time is up. */
    fun tick(ms: Long): List<RunnerEvent> {
        if (!running || ms <= 0) return emptyList()
        elapsedMs += ms
        val out = mutableListOf<RunnerEvent>()
        var left = ms
        while (left > 0 && !finished && current.seconds != null) {
            if (left < remainingMs) {
                remainingMs -= left
                left = 0
            } else {
                left -= remainingMs
                out += advance()
            }
        }
        return out
    }

    /** 下一個: the rep step is done (or he moves on early). */
    fun next(): List<RunnerEvent> {
        if (!started || finished) return emptyList()
        paused = false
        return advance()
    }

    private fun advance(): List<RunnerEvent> {
        if (index == steps.lastIndex) {
            finished = true
            remainingMs = 0
            return listOf(RunnerEvent.Finished)
        }
        index++
        remainingMs = current.seconds?.times(1000L) ?: 0L
        return listOf(RunnerEvent.StepStarted(index))
    }
}
