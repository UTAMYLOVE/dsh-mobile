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

    @Test fun theFirstOpenWelcomesAndAsksOneItemAtATimeInOrder() {
        profile(Profile(heightCm = 184.0, startWeightKg = 96.0, joinDate = "2026-11-21", companyName = "小雨娛樂"))
        val first = runBlocking { brain.startOnboardingIfDue() }!!
        assertEquals(RoleKey.MANAGER, first.role)
        assertEquals(WordedBy.TEMPLATE, first.wordedBy)
        assertEquals(Brain.BASELINE_KIND, first.kind)
        assertTrue(first.text, first.text.contains("歡迎加入小雨娛樂") && first.text.contains("入社第 3 天"))
        assertTrue(first.text.contains("身高 184 公分、體重 96 公斤"))
        assertEquals(OnboardingStep.BODY, step())
        // Only once: a second open asks nothing.
        assertNull(runBlocking { brain.startOnboardingIfDue() })

        assertTrue(say("對").text.contains("正面"))
        assertEquals(OnboardingStep.PHOTO_FRONT, step())
        assertEquals(184.0, join()!!.heightCm!!, 0.0)
        assertEquals(96.0, join()!!.weightKg!!, 0.0)
        assertTrue(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_FRONT, media("m-front")) }.text.contains("側面"))
        assertTrue(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_SIDE, media("m-side")) }.text.contains("背面"))
        assertTrue(runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_BACK, media("m-back")) }.text.contains("清唱錄音"))
        assertTrue(runBlocking { brain.handInBaseline(OnboardingStep.RECORDING, media("m-rec", MediaKind.AUDIO)) }.text.contains("伏地挺身"))
        assertTrue(say("伏地挺身 30 下").text.contains("棒式"))
        val last = say("2 分 00 秒")
        assertTrue(last.text, last.text.contains("記下：棒式 120 秒。") && last.text.contains("入社建檔完成"))
        // The T10 addendum: 藝名 and 代表色 are asked once, last; typing one is code too.
        assertTrue(last.text, last.text.endsWith(IdolProfile.ASK))
        assertTrue(say("藝名 小雨").text.startsWith("記下：藝名「小雨」。"))
        assertEquals("小雨", runBlocking { store.profile.get() }!!.stageName)
        val b = join()!!
        assertEquals(listOf("m-front", "m-side", "m-back", "m-rec"), listOf(b.photoFront, b.photoSide, b.photoBack, b.recording))
        assertEquals(30, b.pushupsMax)
        assertEquals(120, b.plankMaxSec)
        assertNull(b.step)
        assertEquals(clock.millis(), b.completedAt)
        assertTrue(runBlocking { store.profile.get() }!!.onboardingDone)
        assertEquals(4, runBlocking { store.media.count() })
        assertTrue(llm.requests.isEmpty()) // code-driven: no model call at all
        assertTrue(runBlocking { brain.onboardingStatus() }.missing.isEmpty())
    }

    @Test fun aSkipMovesOnAndTheCardCountsWhatIsMissing() {
        runBlocking { brain.startOnboardingIfDue() }
        assertTrue(say("身高 186 體重 94").text.contains("記下：身高 186 公分、體重 94 公斤。"))
        repeat(3) { assertTrue(say("跳過").text.contains("之後補交")) }
        assertEquals(OnboardingStep.RECORDING, step())
        say("先跳過。")
        say("跳過")
        val end = say("跳過")
        assertTrue(end.text, end.text.contains("入社建檔先到這裡，還缺：正面全身照、側面全身照、背面全身照、清唱錄音、伏地挺身、棒式。"))
        val status = runBlocking { brain.onboardingStatus() }
        assertNull(status.open)
        assertTrue(status.completed)
        assertEquals(6, status.missing.size)

        // 補交 asks the first missing item again, the skipped ones included.
        val again = runBlocking { brain.reopenOnboarding() }!!
        assertTrue(again.text.contains("正面"))
        assertEquals(OnboardingStep.PHOTO_FRONT, step())
        assertTrue(join()!!.skipped.isEmpty())
    }

    @Test fun aTypedWeightIsANewReadingAndAConfirmKeepsHisLatest() {
        profile(Profile(heightCm = 184.0, startWeightKg = 96.0))
        runBlocking { store.weights.insert(WeightLog("w1", "2026-11-22", clock.millis() - 3_600_000, 95.6)) }
        val q = runBlocking { brain.startOnboardingIfDue() }!!
        assertTrue(q.text.contains("體重 95.6 公斤"))
        say("對")
        assertEquals(95.6, join()!!.weightKg!!, 0.0)
        assertEquals(1, runBlocking { store.weights.count() })
        assertEquals(96.0, runBlocking { store.profile.get() }!!.startWeightKg!!, 0.0)

        val other = InMemoryAgencyStore()
        runBlocking { other.profile.put(Profile(heightCm = 184.0, startWeightKg = 96.0)) }
        val b2 = Brain(other, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "x-${++n}" }
        runBlocking { b2.startOnboardingIfDue() }
        runBlocking { b2.onText("我身高 187 體重 93.5") }
        assertEquals(93.5, runBlocking { other.weights.between("2026-01-01", "2026-12-31") }.single().kg, 0.0)
        assertEquals(187.0, runBlocking { other.profile.get() }!!.heightCm!!, 0.0)
    }

    @Test fun anAnswerOnlyHalfThereKeepsTheQuestionOpen() {
        runBlocking { brain.startOnboardingIfDue() } // fresh install: nothing to read out
        assertFalse(join()!!.let { BaselineSteps.has(it, OnboardingStep.BODY) })
        val r = say("體重 94")
        assertTrue(r.text, r.text.contains("再打身高"))
        assertEquals(OnboardingStep.BODY, step())
        assertNull(say("對").text.takeIf { it.contains("記下") }) // nothing read out, so 對 is no answer
        say("身高 186")
        assertEquals(OnboardingStep.PHOTO_FRONT, step())
    }

    @Test fun somethingElseDuringAQuestionGoesTheNormalWayAndTheQuestionStays() {
        runBlocking { brain.startOnboardingIfDue() }
        say("身高 186 體重 94")
        say("跳過"); say("跳過"); say("跳過"); say("跳過")
        assertEquals(OnboardingStep.PUSHUPS, step())
        // 「等一下 10 分鐘」 is not a count; 「晚點再吃宵夜」 is not a skip; a command word is a command.
        val wait = say("喝完一壺")
        assertFalse(wait.kind == Brain.BASELINE_KIND)
        assertEquals(OnboardingStep.PUSHUPS, step())
        assertNull(join()!!.pushupsMax)
        assertTrue(join()!!.skipped.none { it == OnboardingStep.PUSHUPS.name })
    }

    @Test fun textCannotHandInAPhoto() {
        runBlocking { brain.startOnboardingIfDue() }
        say("身高 186 體重 94")
        assertEquals(OnboardingStep.PHOTO_FRONT, step())
        assertFalse(say("拍好了").kind == Brain.BASELINE_KIND)
        assertEquals(OnboardingStep.PHOTO_FRONT, step())
        assertNull(join()!!.photoFront)
    }

    @Test fun aPhotoHandedInOutsideItsQuestionIsSavedAndTheQuestionStays() {
        runBlocking { brain.startOnboardingIfDue() }
        val r = runBlocking { brain.handInBaseline(OnboardingStep.PHOTO_BACK, media("m-back")) }
        assertEquals("記下：背面全身照收到了。", r.text)
        assertEquals(OnboardingStep.BODY, step())
        assertEquals("m-back", join()!!.photoBack)
        assertNotNull(runBlocking { store.media.get("m-back") })
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
        assertTrue(end.text, end.text.contains("還缺：側面全身照、背面全身照、清唱錄音。"))

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

    @Test fun aMediaOfTheWrongKindIsRefused() {
        runBlocking { brain.startOnboardingIfDue() }
        val r = runCatching { runBlocking { brain.handInBaseline(OnboardingStep.RECORDING, media("m-x")) } }
        assertTrue(r.isFailure)
        assertNull(join()!!.recording)
        assertNull(runBlocking { store.media.get("m-x") })
    }
}
