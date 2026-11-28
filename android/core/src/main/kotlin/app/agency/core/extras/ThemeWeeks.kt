package app.agency.core.extras

import app.agency.core.day.StageDays
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One weekly theme and the small practice it adds every day of that week. */
data class Theme(val id: String, val name: String, val practice: String, val minutes: Int = 5)

/**
 * A built-in rotating list of weekly themes. The rotation is by calendar week (Monday to Sunday) and starts with the week
 * [Feature.THEME_WEEK] opens; before it opens there is no theme.
 */
object ThemeWeeks {
    /** The [TaskDone.task] name that records the day's theme practice. Not a [app.agency.core.plan.TaskId], so the day state ignores it. */
    const val TASK_NAME = "THEME_PRACTICE"

    val THEMES: List<Theme> = listOf(
        Theme("expression", "表情週", "對著鏡子練三種表情，每種停三秒"),
        Theme("posture", "體態週", "靠牆站好，肩膀放鬆，數三十秒"),
        Theme("english", "英文週", "大聲念一段英文自我介紹"),
        Theme("vocal", "聲樂週", "用氣音和長音各練一分鐘"),
        Theme("dance", "舞蹈週", "把一段舞的八拍練到很乾淨"),
        Theme("etiquette", "禮儀週", "練習鞠躬、問候和得體的說話方式"),
        Theme("grooming", "儀容週", "檢查頭髮、指甲、衣服，整理成上鏡的樣子"),
    )

    private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** This week's theme, or null while the feature is closed. */
    fun current(day: LocalDate, stage: StageState): Theme? {
        if (!Feature.THEME_WEEK.openAt(stage.stage)) return null
        val opened = StageDays.openedOn(stage, Feature.THEME_WEEK.stage) ?: return null
        val weeks = ChronoUnit.WEEKS.between(monday(opened), monday(day)).toInt().coerceAtLeast(0)
        return THEMES[weeks % THEMES.size]
    }

    fun doneOn(store: Store, day: LocalDate): Boolean = store.entries(day, Kind.TASK).any {
        runCatching { it.read<TaskDone>() }.getOrNull()?.task == TASK_NAME
    }

    /** Records today's theme practice. */
    fun markDone(store: Store, day: LocalDate, at: Instant, minutes: Int? = null) {
        store.add(day, at, Kind.TASK, TaskDone(task = TASK_NAME, minutes = minutes))
    }
}
