package app.dshmobile.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class RateGuardTest {
    @Test fun windowSlides() {
        val clock = MutableClock()
        val guard = RateGuard(maxPerMinute = 2, clock = clock)
        assertTrue(guard.tryAcquire(Provider.DEEPSEEK))
        clock.advance(Duration.ofSeconds(30))
        assertTrue(guard.tryAcquire(Provider.DEEPSEEK))
        assertFalse(guard.tryAcquire(Provider.DEEPSEEK))
        clock.advance(Duration.ofSeconds(29)) // 59 s after the first
        assertFalse(guard.tryAcquire(Provider.DEEPSEEK))
        clock.advance(Duration.ofSeconds(1)) // 60 s after the first: it left the window, the second (30 s old) is still in
        assertTrue(guard.tryAcquire(Provider.DEEPSEEK))
        assertFalse(guard.tryAcquire(Provider.DEEPSEEK))
    }

    @Test fun rejectedAttemptsDoNotExtendTheWindow() {
        val clock = MutableClock()
        val guard = RateGuard(maxPerMinute = 1, clock = clock)
        assertTrue(guard.tryAcquire(Provider.ANTHROPIC))
        clock.advance(Duration.ofSeconds(59))
        assertFalse(guard.tryAcquire(Provider.ANTHROPIC))
        clock.advance(Duration.ofSeconds(1))
        assertTrue(guard.tryAcquire(Provider.ANTHROPIC))
    }

    @Test fun providersAreIndependent() {
        val guard = RateGuard(maxPerMinute = 1, clock = MutableClock())
        assertTrue(guard.tryAcquire(Provider.DEEPSEEK))
        assertFalse(guard.tryAcquire(Provider.DEEPSEEK))
        assertTrue(guard.tryAcquire(Provider.ANTHROPIC))
    }
}
