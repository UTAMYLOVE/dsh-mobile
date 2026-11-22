package app.agency.core.engine

import java.time.Duration
import java.time.Instant

/**
 * Sign-in and sign-out (arch 1.5 `SignPolicy`; the skeleton of T11, the rest with T19). On time = at most [ON_TIME_MIN]
 * minutes after the wake slot (earlier is always fine). Early sleep is never a gap; a sign-out after the latest bedtime is late.
 */
object SignPolicy {
    const val ON_TIME_MIN = 10L

    /** 「提早 10 分鐘起床」 asks to move the class-day wake this much earlier. */
    const val EARLIER_MIN = 10

    /**
     * How many on-time class-day sign-ins in a row ask to wake earlier: two weeks of class days (plan v3 「連續兩週 8:30 都準時
     * 簽到」; with classes on 一二四五 that is 8).
     */
    fun earlierRun(classDaysPerWeek: Int): Int = 2 * classDaysPerWeek

    /**
     * Whether the class days from today back ([onTime], newest first, only those after the last 「提早起床」 proposal; null: no
     * sign-in that day) make exactly [run] on time in a row: asked once per run, never again for the same run.
     */
    fun earlierDue(onTime: List<Boolean?>, run: Int): Boolean = onTime.takeWhile { it == true }.size == run

    fun onTime(signIn: Instant, wakeDue: Instant): Boolean = !signIn.isAfter(wakeDue.plus(Duration.ofMinutes(ON_TIME_MIN)))

    fun lateSignOut(signOut: Instant, latest: Instant): Boolean = signOut.isAfter(latest)

    /** Minutes he signed in after the wake slot (0 when on time or early). */
    fun minutesLate(signIn: Instant, wakeDue: Instant): Long = Duration.between(wakeDue, signIn).toMinutes().coerceAtLeast(0)
}
