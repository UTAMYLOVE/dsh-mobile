package app.agency.core.llm

import app.agency.core.metering.CallBudget
import app.agency.core.metering.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallChecksTest {
    @Test fun aRefusedBudgetIsRateAndTellsTheApp() {
        var told = 0
        val checks = CallChecks({ false }, CallBudget(maxPerMinute = 2, clock = MutableClock()), onBudgetRefused = { told++ })
        assertNull(checks.takeBudget())
        assertNull(checks.takeBudget())
        assertEquals(0, told)
        assertEquals(Fail.RATE, checks.takeBudget())
        assertEquals(1, told)
    }

    @Test fun aHookThatThrowsDoesNotBreakTheCheck() {
        val checks = CallChecks({ false }, CallBudget(maxPerMinute = 0, clock = MutableClock()), onBudgetRefused = { error("invented") })
        assertEquals(Fail.RATE, checks.takeBudget())
    }
}
