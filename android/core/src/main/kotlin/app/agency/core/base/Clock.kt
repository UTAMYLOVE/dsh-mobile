package app.agency.core.base

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json

/** The one source of time. Tests and the fourteen-day simulation move it. */
interface Clock {
    fun now(): Instant
    val zone: ZoneId
}

class SystemClock(override val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun now(): Instant = Instant.now()
}

/**
 * The logical day. He goes to bed after midnight, so a day runs from 06:00 to 06:00: 01:30 on the 5th still belongs to the 4th.
 */
object Days {
    const val START_HOUR = 6

    fun of(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).minusHours(START_HOUR.toLong()).toLocalDate()

    fun today(clock: Clock): LocalDate = of(clock.now(), clock.zone)

    /** The instant a wall time of logical [day] happens: a time before 06:00 is on the next calendar date. */
    fun at(day: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        (if (time.hour < START_HOUR) day.plusDays(1) else day).atTime(time).atZone(zone).toInstant()

    /** Minutes since the logical day began (06:00 = 0, 05:59 next morning = 1439). Orders times within a day. */
    fun minuteOfDay(time: LocalTime): Int = ((time.hour - START_HOUR + 24) % 24) * 60 + time.minute

    fun minuteOfDay(at: Instant, zone: ZoneId): Int = minuteOfDay(at.atZone(zone).toLocalTime())

    /** "HH:mm" → time, or null when blank or malformed (a rule that is not filled in yet). */
    fun parse(hhmm: String): LocalTime? = runCatching { LocalTime.parse(hhmm.trim()) }.getOrNull()
}

/** The one JSON configuration for stored payloads: unknown keys are ignored so an older backup still reads. */
val J: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
