package app.agency.core.brain

import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Profile
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photos and the recording of 入社建檔 never reach a model (plan v3 §19, arch T07): after every item is handed in, a chat turn's
 * requests carry the numbers of the conversation but no media id, file name, MIME type or checksum.
 */
class PromptNeverHasMediaTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z"))
    private var n = 0
    private val sha = "c0ffee".repeat(10) + "abcd"

    private fun media(id: String, kind: MediaKind) =
        Media(id, kind, MediaPurpose.BASELINE, if (kind == MediaKind.PHOTO) "image/jpeg" else "audio/mp4", 4321, sha, clock.millis())

    @Test fun noMediaOfTheBaselineIsInAnyRequest() {
        val llm = ScriptedLlm(
            ScriptedLlm.ok("""{"kind":"chat"}"""),
            ScriptedLlm.ok("""{"text":"收到，去休息。","ask":null,"options":[],"recommended":null,"memory":[]}"""),
            ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""),
        )
        val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-${++n}" }
        val ids = listOf("photo-front-7f3a", "photo-side-91bc", "photo-back-02de", "voice-55aa")
        runBlocking {
            store.profile.put(Profile(heightCm = 168.0, startWeightKg = 80.0))
            brain.startOnboardingIfDue()
            brain.onText("對")
            brain.handInBaseline(OnboardingStep.PHOTO_FRONT, media(ids[0], MediaKind.PHOTO))
            brain.handInBaseline(OnboardingStep.PHOTO_SIDE, media(ids[1], MediaKind.PHOTO))
            brain.handInBaseline(OnboardingStep.PHOTO_BACK, media(ids[2], MediaKind.PHOTO))
            brain.handInBaseline(OnboardingStep.RECORDING, media(ids[3], MediaKind.AUDIO))
            brain.onText("伏地挺身 18 下")
            brain.onText("棒式 65 秒")
            assertTrue(llm.requests.isEmpty())
            brain.onText("姐姐我建檔完了")
        }
        assertTrue(llm.requests.size >= 2)
        val sent = llm.requests.joinToString("\n") { it.toString() }
        // The numbers he gave are in the conversation the model reads …
        assertTrue(sent.contains("伏地挺身 18 下"))
        // … the photos and the recording are not, in any form.
        (ids + listOf(sha, "image/jpeg", "audio/mp4", ".bin", ".jpg", ".m4a", "4321")).forEach { assertFalse(it, sent.contains(it)) }
        assertEquals(4, runBlocking { store.media.count() })
    }
}
