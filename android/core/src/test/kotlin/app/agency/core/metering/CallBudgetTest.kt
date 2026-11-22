package app.agency.core.metering

import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBudgetTest {
    @Test fun thirtyCallsAMinuteByDefaultThenTheWindowSlides() {
        val clock = MutableClock()
        val budget = CallBudget(clock = clock)
        repeat(30) { assertTrue(budget.tryAcquire()) }
        assertFalse(budget.tryAcquire())
        clock.advance(Duration.ofSeconds(59))
        assertFalse(budget.tryAcquire())
        clock.advance(Duration.ofSeconds(1))
        repeat(30) { assertTrue(budget.tryAcquire()) } // all 30 of t0 left the window together
        assertFalse(budget.tryAcquire())
    }
}
