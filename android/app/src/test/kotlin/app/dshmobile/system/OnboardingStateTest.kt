package app.dshmobile.system

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.system.OnboardingState.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    private val prefs = InMemorySharedPreferences()
    private val state = OnboardingState(prefs)

    @Test fun stepsComeInTheDocumentedOrder() {
        assertEquals(
            listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.PHANTOM, Step.DEEPSEEK_KEY, Step.ROOTFS),
            Step.values().toList(),
        )
    }

    @Test fun freshStateStartsAtNotifications() {
        assertEquals(Step.NOTIFICATIONS, state.nextStep())
        Step.values().forEach { assertFalse(state.isDone(it)) }
    }

    @Test fun nextStepFollowsTheOrderAsStepsAreDone() {
        val seen = mutableListOf<Step>()
        while (true) {
            val s = state.nextStep() ?: break
            seen += s
            state.markDone(s)
        }
        assertEquals(Step.values().toList(), seen)
        assertNull(state.nextStep())
    }

    @Test fun skippedStepsAreRememberedAcrossInstances() {
        state.markSkipped(Step.NOTIFICATIONS)
        state.markSkipped(Step.BATTERY)
        val reopened = OnboardingState(prefs)
        assertTrue(reopened.isDone(Step.NOTIFICATIONS))
        assertTrue(reopened.isDone(Step.BATTERY))
        assertEquals(Step.PHANTOM, reopened.nextStep())
    }

    @Test fun doneStepsAreRememberedAcrossInstances() {
        state.markDone(Step.DEEPSEEK_KEY)
        assertTrue(OnboardingState(prefs).isDone(Step.DEEPSEEK_KEY))
        assertFalse(OnboardingState(prefs).isDone(Step.ROOTFS))
    }

    @Test fun keyAndRootfsCannotBeSkipped() {
        assertFalse(Step.DEEPSEEK_KEY.skippable)
        assertFalse(Step.ROOTFS.skippable)
        assertThrows(IllegalArgumentException::class.java) { state.markSkipped(Step.DEEPSEEK_KEY) }
        assertThrows(IllegalArgumentException::class.java) { state.markSkipped(Step.ROOTFS) }
        assertFalse(state.isDone(Step.DEEPSEEK_KEY))
        assertFalse(state.isDone(Step.ROOTFS))
    }

    @Test fun theOptionalStepsCanBeSkipped() {
        listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.PHANTOM).forEach {
            assertTrue(it.skippable)
            state.markSkipped(it)
            assertTrue(state.isDone(it))
        }
    }

    @Test fun completeNeedsEveryStepAndTheCallersTwoFacts() {
        Step.values().forEach { state.markDone(it) }
        assertTrue(state.isComplete(rootfsInstalled = true, keyStored = true))
        assertFalse(state.isComplete(rootfsInstalled = false, keyStored = true))
        assertFalse(state.isComplete(rootfsInstalled = true, keyStored = false))
    }

    @Test fun incompleteStepsMeanNotComplete() {
        state.markDone(Step.NOTIFICATIONS)
        assertFalse(state.isComplete(rootfsInstalled = true, keyStored = true))
    }

    @Test fun clearForgetsAStep() {
        Step.values().forEach { state.markDone(it) }
        state.clear(Step.ROOTFS)
        assertFalse(state.isDone(Step.ROOTFS))
        assertEquals(Step.ROOTFS, state.nextStep())
        assertFalse(state.isComplete(rootfsInstalled = true, keyStored = true))
    }
}
