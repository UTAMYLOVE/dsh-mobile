package app.agency.core.schedule

import java.time.LocalDate

/** A day's kind (plan v3 §4): class day (一、二、四、五), no-class delivery day (三、六、日). A rest day keeps its kind's times. */
enum class DayType(val zh: String) { CLASS("上課日"), NO_CLASS("沒課日") }

object Timetable {
    fun type(day: LocalDate, spec: ScheduleSpec): DayType = if (day.dayOfWeek.value in spec.classDays) DayType.CLASS else DayType.NO_CLASS
}
