package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Baseline
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.WeightLog
import app.agency.core.model.WordedBy
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 入社建檔 (arch T07) through the brain on the in-memory store; every number and photo here is invented. */
class OnboardingTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z")) // 12:00 in Taipei
    private var n = 0
    /** Offline: anything that is not a 入社建檔 answer gets the template (and the test sees that a call was made). */
    private val llm = object : LlmClient {
        val requests = mutableListOf<ChatRequest>()
        override suspend fun chat(request: ChatRequest): ChatResult = ChatResult.Failed(Fail.OFFLINE).also { requests += request }
    }
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun join(): Baseline? = runBlocking { store.baselines.get(Baseline.JOIN) }
    private fun step(): OnboardingStep? = join()?.let(BaselineSteps::open)
    private fun say(text: String): Message = runBlocking { brain.onText(text) }.last()
    private fun media(id: String, kind: MediaKind = MediaKind.PHOTO) =
        Media(id, kind, MediaPurpose.BASELINE, if (kind == MediaKind.PHOTO) "image/jpeg" else "audio/mp4", 1234, "ab".repeat(32), clock.millis())

    private fun profile(p: Profile) = runBlocking { store.profile.put(p) }

    private fun said() = runBlocking { store.messages.count() }

    @Test fun theFirstOpenWelcomesOnceAndTheFormSavesEachItemSilently() {
        profile(Profile(joinDate = "2026-11-21", companyName = "小雨娛樂"))
        val first = runBlocking { brain.startOnboardingIfDue() }!!
        assertEquals(RoleKey.MANAGER, first.role)
        assertEquals(WordedBy.TEMPLATE, first.wordedBy)
        assertEquals(Brain.BASELINE_KIND, first.kind)
        assertTrue(first.text, first.text.contains("歡迎加入小雨娛樂") && first.text.contains("入社第 3 天"))
        // No question in the conversation: the items are a form of their own (03).
        assertFalse(first.text.contains("身高"))
        assertNull(step())
        assertTrue(runBlocking { brain.onboardingStatus() }.due)
        assertNull(runBlocking { brain.startOnboardingIfDue() })
        val before = said()

        assertTrue(runBlocking { brain.onboardingBody(184.0, 96.0) })
        assertNull(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_FRONT, media("m-front")) })
        assertNull(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_SIDE, media("m-side")) })
        assertNull(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_BACK, media("m-back")) })
        assertNull(runBlocking { brain.handInBaseline(OnboardingStep.RECORDING, media("m-rec", MediaKind.AUDIO)) })
        assertTrue(runBlocking { brain.onboardingPushups(30) })
        assertFalse(runBlocking { brain.onboardingPlank(2) }) // too short to be a plank
        assertTrue(runBlocking { brain.onboardingPlank(120) })
        assertEquals(before, said())
        assertFalse(runBlocking { brain.onboardingStatus() }.completed)

        val last = runBlocking { brain.finishOnboarding() }!!
        assertTrue(last.text, last.text.startsWith("資料收好了，入社建檔完成。"))
        // The T10 addendum: 藝名 and 代表色 are asked once, last.
        assertTrue(last.text, last.text.endsWith(IdolProfile.ASK))
        val b = join()!!
        assertEquals(listOf("m-front", "m-side", "m-back", "m-rec"), listOf(b.photoFront, b.photoSide, b.photoBack, b.recording))
        assertEquals(184.0, b.heightCm!!, 0.0)
        assertEquals(30, b.pushupsMax)
        assertEquals(120, b.plankMaxSec)
        assertEquals(clock.millis(), b.completedAt)
        assertTrue(runBlocking { store.profile.get() }!!.onboardingDone)
        assertEquals(184.0, runBlocking { store.profile.get() }!!.heightCm!!, 0.0)
        assertEquals(96.0, runBlocking { store.weights.between("0000-01-01", "9999-12-31") }.single().kg, 0.0)
        assertTrue(llm.requests.isEmpty()) // code-driven: no model call at all
        val status = runBlocking { brain.onboardingStatus() }
        assertTrue(status.missing.isEmpty())
        assertFalse(status.due)
    }

    @Test fun finishingNeedsEveryItemDoneOrSkippedAndSaysWhatIsMissingByName() {
        runBlocking { brain.startOnboardingIfDue() }
        runBlocking { brain.onboardingBody(186.0, 94.0) }
        assertNull(runBlocking { brain.finishOnboarding() })
        val status0 = runBlocking { brain.onboardingStatus() }
        assertEquals(OnboardingStep.entries.drop(1), status0.todo)
        assertFalse(status0.canFinish)
        OnboardingStep.entries.drop(1).forEach { runBlocking { brain.onboardingSkip(it) } }
        val status = runBlocking { brain.onboardingStatus() }
        assertTrue(status.canFinish)
        assertEquals(OnboardingStep.entries.drop(1).toSet(), status.skipped)
        val end = runBlocking { brain.finishOnboarding() }!!
        assertTrue(end.text, end.text.contains("還差：正面全身照、側面全身照、背面全身照、清唱錄音、伏地挺身、棒式。"))
        val after = runBlocking { brain.onboardingStatus() }
        assertTrue(after.completed)
        assertEquals(6, after.missing.size)
        // Handed in later (入社資料): saved, the skip mark goes, nothing is said.
        assertTrue(runBlocking { brain.onboardingPushups(12) })
        assertEquals(5, runBlocking { brain.onboardingStatus() }.missing.size)
        assertTrue(join()!!.skipped.none { it == OnboardingStep.PUSHUPS.name })
    }

    @Test fun theConversationNoLongerTakesTheBodyNumbers() {
        runBlocking { brain.startOnboardingIfDue() }
        say("身高 186 體重 94")
        assertNull(join()!!.heightCm)
        assertNull(step())
    }

    @Test fun theSameWeightAgainIsNoNewReading() {
        runBlocking { store.weights.insert(WeightLog("w1", "2026-11-22", clock.millis() - 3_600_000, 95.6)) }
        runBlocking { brain.startOnboardingIfDue() }
        runBlocking { brain.onboardingBody(184.0, 95.6) }
        assertEquals(1, runBlocking { store.weights.count() })
        runBlocking { brain.onboardingBody(184.0, 93.5) }
        assertEquals(2, runBlocking { store.weights.count() })
        assertFalse(runBlocking { brain.onboardingBody(400.0, 93.5) })
    }

    @Test fun aLegacyBaselinePrefillsAndIsNotAskedAgain() {
        // The 5a94d3c build: body and its one photo (the front one) handed in, recording skipped, the push-up question open.
        runBlocking {
            store.baselines.insert(Baseline(Baseline.JOIN, heightCm = 184.0, weightKg = 96.0, photoFront = "old-photo", skipped = listOf("RECORDING"), step = "PUSHUPS"))
        }
        assertNull(runBlocking { brain.startOnboardingIfDue() }) // its question is still open: nothing new is asked
        say("25")
        assertEquals(OnboardingStep.PLANK, step())
        val end = say("一分半")
        assertTrue(end.text, end.text.contains("還差：側面全身照、背面全身照、清唱錄音。"))

        // A legacy 「PHOTO」 step reads as the front photo.
        val s2 = InMemoryAgencyStore()
        runBlocking { s2.baselines.insert(Baseline(Baseline.JOIN, step = "PHOTO", skipped = listOf("PHOTO"))) }
        assertEquals(OnboardingStep.PHOTO_FRONT, runBlocking { Onboarding(s2, AgencyClock.of(clock)) { "y" }.status() }.open)
        assertEquals(setOf(OnboardingStep.PHOTO_FRONT), BaselineSteps.skipped(runBlocking { s2.baselines.get(Baseline.JOIN) }!!))
    }

    @Test fun aFinishedImportedBaselineDoesNotStartAgain() {
        runBlocking { store.baselines.insert(Baseline(Baseline.JOIN, heightCm = 184.0, weightKg = 96.0, completedAt = 5)) }
        assertNull(runBlocking { brain.startOnboardingIfDue() })
        assertEquals(6, runBlocking { brain.onboardingStatus() }.missing.size)
    }

    @Test fun aLegacyOpenQuestionStillAnswersInTheConversation() {
        runBlocking { store.baselines.insert(Baseline(Baseline.JOIN, heightCm = 184.0, weightKg = 96.0, step = "PHOTO_BACK")) }
        val r = runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_BACK, media("m-back")) }!!
        assertTrue(r.text, r.text.startsWith("記下：背面全身照收到了。"))
        assertNotNull(runBlocking { store.media.get("m-back") })
    }

    @Test fun aMediaOfTheWrongKindIsRefused() {
        runBlocking { brain.startOnboardingIfDue() }
        val r = runCatching { runBlocking { brain.handInBaseline(OnboardingStep.RECORDING, media("m-x")) } }
        assertTrue(r.isFailure)
        assertNull(join()!!.recording)
        assertNull(runBlocking { store.media.get("m-x") })
    }
}
