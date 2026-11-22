package app.agency.core.engine

import java.time.Duration
import java.time.Instant

/**
 * Sign-in and sign-out (arch 1.5 `SignPolicy`; the skeleton of T11, the rest with T19). On time = at most [ON_TIME_MIN]
 * minutes after the wake slot (earlier is always fine). Early sleep is never a gap; a sign-out after the latest bedtime is late.
 */
object SignPolicy {
    const val ON_TIME_MIN = 10L

    fun onTime(signIn: Instant, wakeDue: Instant): Boolean = !signIn.isAfter(wakeDue.plus(Duration.ofMinutes(ON_TIME_MIN)))

    fun lateSignOut(signOut: Instant, latest: Instant): Boolean = signOut.isAfter(latest)

    /** Minutes he signed in after the wake slot (0 when on time or early). */
    fun minutesLate(signIn: Instant, wakeDue: Instant): Long = Duration.between(wakeDue, signIn).toMinutes().coerceAtLeast(0)
}
