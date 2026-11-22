package app.agency.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The one source of "now" for the brain: tests move it by hand, the app gives it the system clock. */
interface AgencyClock {
    fun now(): Instant

    /** The zone days and slot times are read in (Asia/Taipei for him). */
    val zone: ZoneId

    fun millis(): Long = now().toEpochMilli()

    companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

        /** A clock that reads [clock]'s time, in [zone] (not [clock]'s own zone). */
        fun of(clock: Clock, zone: ZoneId = TAIPEI): AgencyClock = JavaAgencyClock(clock, zone)

        fun system(zone: ZoneId = TAIPEI): AgencyClock = of(Clock.systemUTC(), zone)
    }
}

private class JavaAgencyClock(private val clock: Clock, override val zone: ZoneId) : AgencyClock {
    override fun now(): Instant = clock.instant()
}
