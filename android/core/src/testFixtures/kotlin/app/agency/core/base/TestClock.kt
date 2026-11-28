package app.agency.core.base

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A clock tests move by hand. Starts on a Monday morning in a fixed zone so weekdays are predictable. */
class TestClock(
    var instant: Instant = LocalDate.of(2030, 1, 7).atTime(7, 0).atZone(ZONE).toInstant(),
    override val zone: ZoneId = ZONE,
) : Clock {
    override fun now(): Instant = instant

    fun plus(d: Duration) { instant = instant.plus(d) }

    /** Jumps to [time] on logical [day] (a time before 06:00 lands on the next calendar date, see [Days.at]). */
    fun set(day: LocalDate, time: LocalTime) { instant = Days.at(day, time, zone) }

    fun set(day: LocalDate, hhmm: String) = set(day, LocalTime.parse(hhmm))

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Taipei")

        /** The Monday the default clock starts on. */
        val MONDAY: LocalDate = LocalDate.of(2030, 1, 7)
    }
}
