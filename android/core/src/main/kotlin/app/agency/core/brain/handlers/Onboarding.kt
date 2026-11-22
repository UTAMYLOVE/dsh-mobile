package app.agency.core.brain.handlers

import app.agency.core.guard.CoachNumbers
import app.agency.core.model.Baseline
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.Profile
import app.agency.core.model.WeightLog
import app.agency.core.persona.PromptInput
import app.agency.core.rules.CoachRules
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

/**
 * 入社建檔's items, in the order the manager asks them (plan v3 §14, arch T07). Stored by [name] in `baseline.step` and
 * `baseline.skipped`; the 5a94d3c build's single 「PHOTO」 reads as the front photo ([parse]).
 */
enum class OnboardingStep(val zh: String) {
    BODY("身高體重"),
    PHOTO_FRONT("正面全身照"),
    PHOTO_SIDE("側面全身照"),
    PHOTO_BACK("背面全身照"),
    RECORDING("清唱錄音"),
    PUSHUPS("伏地挺身"),
    PLANK("棒式"),
    ;

    /** Handed in through the screen (camera, recorder), never by text; text can only skip it. */
    val isMedia: Boolean get() = this in PHOTOS || this == RECORDING

    companion object {
        val PHOTOS = listOf(PHOTO_FRONT, PHOTO_SIDE, PHOTO_BACK)

        /** A stored step name; the legacy 「PHOTO」 (one photo, the front one) is [PHOTO_FRONT]. */
        fun parse(name: String?): OnboardingStep? = when (name) {
            null -> null
            LEGACY_PHOTO -> PHOTO_FRONT
            else -> entries.firstOrNull { it.name == name }
        }

        private const val LEGACY_PHOTO = "PHOTO"
    }
}

/** What 入社建檔 shows: the open question, what is still missing (skipped items included), and whether it was walked through. */
data class OnboardingStatus(val open: OnboardingStep?, val missing: List<OnboardingStep>, val completed: Boolean, val baseline: Baseline?)

/** Reads a [Baseline] by step (pure). */
object BaselineSteps {
    fun has(b: Baseline, step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.BODY -> b.heightCm != null && b.weightKg != null
        OnboardingStep.PHOTO_FRONT -> b.photoFront != null
        OnboardingStep.PHOTO_SIDE -> b.photoSide != null
        OnboardingStep.PHOTO_BACK -> b.photoBack != null
        OnboardingStep.RECORDING -> b.recording != null
        OnboardingStep.PUSHUPS -> b.pushupsMax != null
        OnboardingStep.PLANK -> b.plankMaxSec != null
    }

    fun skipped(b: Baseline): Set<OnboardingStep> = b.skipped.mapNotNull(OnboardingStep::parse).toSet()

    fun open(b: Baseline): OnboardingStep? = OnboardingStep.parse(b.step)

    /** Not handed in yet, skipped ones included (「入社資料缺 N 項」). */
    fun missing(b: Baseline?): List<OnboardingStep> = OnboardingStep.entries.filter { b == null || !has(b, it) }

    fun mediaOf(b: Baseline, step: OnboardingStep): String? = when (step) {
        OnboardingStep.PHOTO_FRONT -> b.photoFront
        OnboardingStep.PHOTO_SIDE -> b.photoSide
        OnboardingStep.PHOTO_BACK -> b.photoBack
        OnboardingStep.RECORDING -> b.recording
        else -> null
    }

    fun withMedia(b: Baseline, step: OnboardingStep, mediaId: String): Baseline = when (step) {
        OnboardingStep.PHOTO_FRONT -> b.copy(photoFront = mediaId)
        OnboardingStep.PHOTO_SIDE -> b.copy(photoSide = mediaId)
        OnboardingStep.PHOTO_BACK -> b.copy(photoBack = mediaId)
        OnboardingStep.RECORDING -> b.copy(recording = mediaId)
        else -> throw IllegalArgumentException("not a media step: $step")
    }
}

/**
 * Strict readers of his 入社建檔 answers (moved from the coach's `Agency.kt`, with the review fixes A1 and A5). Anything that is
 * not plainly an answer returns null, so the message goes through the normal flow and the question stays open.
 */
object BaselineAnswers {
    private const val N = "([0-9]+(?:\\.[0-9]+)?|[零〇一二兩三四五六七八九十百]+(?:點[零〇一二兩三四五六七八九]+)?)"
    private const val SEP = "[\\s,，、和]*"

    private fun num(s: String): Double? = s.toDoubleOrNull() ?: CoachNumbers.chinese(s)

    private val BOTH = Regex("^身高\\s*$N\\s*(?:公分)?${SEP}體重\\s*$N\\s*(?:公斤)?$")
    private val BOTH_UNITS = Regex("^$N\\s*公分${SEP}$N\\s*公斤$")
    private val BARE_TWO = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$")
    private val HEIGHT_ONLY = Regex("^(?:身高\\s*$N\\s*(?:公分)?|$N\\s*公分)$")
    private val WEIGHT_ONLY = Regex("^(?:體重\\s*$N\\s*(?:公斤)?|$N\\s*公斤)$")

    private fun h(v: Double?) = v?.takeIf { it in 120.0..230.0 }
    private fun w(v: Double?) = v?.takeIf { it in 30.0..250.0 }

    /** (height, weight), either may be null when only the other was given; null when the message is not a body answer. */
    fun body(raw: String): Pair<Double?, Double?>? {
        // A1: 「我身高 170 體重 78」「我的體重 78」: a leading 我 / 我的 is part of a plain answer.
        val t = raw.trim().removePrefix("我的").removePrefix("我").trim()
        BOTH.find(t)?.let { m -> return (h(num(m.groupValues[1])) ?: return null) to (w(num(m.groupValues[2])) ?: return null) }
        BOTH_UNITS.find(t)?.let { m -> return (h(num(m.groupValues[1])) ?: return null) to (w(num(m.groupValues[2])) ?: return null) }
        BARE_TWO.find(t)?.let { m -> return (h(num(m.groupValues[1])) ?: return null) to (w(num(m.groupValues[2])) ?: return null) }
        HEIGHT_ONLY.find(t)?.let { m -> return (h(num(m.groupValues[1].ifEmpty { m.groupValues[2] })) ?: return null) to null }
        WEIGHT_ONLY.find(t)?.let { m -> return null to (w(num(m.groupValues[1].ifEmpty { m.groupValues[2] })) ?: return null) }
        return null
    }

    /** A5: digits, 十, or at least two Chinese numerals: a bare 「一下」 is not a count. */
    private const val COUNT = "([0-9]+|十|[零〇一二兩三四五六七八九十百]{2,})"
    private val PUSHUPS = Regex("^(?:伏地挺身)?\\s*$COUNT\\s*下?$")

    fun pushups(raw: String): Int? {
        val m = PUSHUPS.find(raw.trim()) ?: return null
        val v = num(m.groupValues[1]) ?: return null
        return v.takeIf { it == floor(it) && it in 0.0..500.0 }?.toInt()
    }

    private val PLANK_SEC = Regex("^(?:棒式)?\\s*$N\\s*秒(?:鐘)?$")
    private val PLANK_MIN = Regex("^(?:棒式)?\\s*$N\\s*分(?:鐘)?\\s*(?:(半)|$N\\s*秒(?:鐘)?)?$")
    private val PLANK_BARE = Regex("^(?:棒式)?\\s*$N$")

    fun plank(raw: String): Int? {
        val t = raw.trim()
        val sec = PLANK_SEC.find(t)?.let { num(it.groupValues[1]) }
            ?: PLANK_MIN.find(t)?.let { m ->
                val min = num(m.groupValues[1]) ?: return null
                val rest = when {
                    m.groupValues[2] == "半" -> 30.0
                    m.groupValues[3].isNotEmpty() -> num(m.groupValues[3]) ?: return null
                    else -> 0.0
                }
                min * 60 + rest
            }
            ?: PLANK_BARE.find(t)?.let { num(it.groupValues[1]) }
            ?: return null
        return sec.toInt().takeIf { it in 5..1800 }
    }

    /** Words that skip the open item (exact, after trimming punctuation). */
    val SKIP = setOf("跳過", "先跳過", "之後補", "之後再補", "晚點補", "下次補", "跳過這個", "先不要")

    /** Words that confirm the numbers the manager read out (only for 身高體重). */
    val CONFIRM = setOf("對", "對的", "沒錯", "確認", "是", "是的", "正確")

    private val TRIM = Regex("^[\\s　]+|[\\s　。！!？?，,、～~.]+$")

    fun clean(text: String): String = TRIM.replace(text, "")
}

/**
 * 入社建檔, code-driven (no model): the manager asks one item at a time ([OnboardingStep] order), reads his answers strictly
 * ([BaselineAnswers]), takes photos and the recording from the screen ([handIn]), and lets him skip any item (「跳過」) and hand
 * it in later (「補交」, [reopen]). The baseline row is `baseline.id = 'join'`; a legacy (imported) one pre-fills its items.
 * Photos and the recording are only media ids here; nothing but the numbers ever reaches a prompt.
 *
 * Every function returns the manager's line (the caller writes it as a message), or null when there is nothing to say.
 */
class Onboarding(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val newId: () -> String,
) {
    suspend fun join(): Baseline? = store.baselines.get(Baseline.JOIN)

    suspend fun status(): OnboardingStatus {
        val b = join()
        val open = b?.let(BaselineSteps::open)
        return OnboardingStatus(open, BaselineSteps.missing(b), b?.completedAt != null, b)
    }

    /**
     * On the first open: when 入社建檔 was never walked through and no question is open, the manager welcomes him and asks the
     * first item still missing. Imported items are not asked again; an imported question still open is left as it is.
     */
    suspend fun startIfDue(): String? {
        val b = join()
        if (b?.completedAt != null || b?.let(BaselineSteps::open) != null) return null
        val profile = store.profile.get() ?: Profile()
        if (profile.onboardingDone) return null
        val base = b ?: Baseline(Baseline.JOIN)
        val skipped = BaselineSteps.skipped(base)
        val next = OnboardingStep.entries.firstOrNull { !BaselineSteps.has(base, it) && it !in skipped }
        return (listOf(welcome(profile)) + advanceTo(base, next, profile)).joinToString("\n")
    }

    /** 補交: every missing item is asked again (the skipped ones included), from the first; null when nothing is missing. */
    suspend fun reopen(): String? {
        val b = join() ?: Baseline(Baseline.JOIN)
        if (BaselineSteps.open(b) != null) return question(BaselineSteps.open(b)!!, store.profile.get() ?: Profile())
        val next = BaselineSteps.missing(b).firstOrNull() ?: return null
        val again = b.copy(step = next.name, skipped = emptyList())
        store.baselines.upsert(again)
        return question(next, store.profile.get() ?: Profile())
    }

    /**
     * While a question is open, his message is first read as its answer (a confirm, the numbers, or a skip word). Null when it
     * is not an answer: the message then goes through the normal flow and the question stays open.
     */
    suspend fun answer(text: String): String? {
        val b = join() ?: return null
        val step = BaselineSteps.open(b) ?: return null
        val t = BaselineAnswers.clean(text)
        if (t in BaselineAnswers.SKIP) {
            return advance(b.copy(skipped = (b.skipped.filter { OnboardingStep.parse(it) != step } + step.name)), step, "好，${step.zh}之後補交。")
        }
        return when (step) {
            OnboardingStep.BODY -> body(b, t)
            OnboardingStep.PUSHUPS -> BaselineAnswers.pushups(t)?.let { advance(b.copy(pushupsMax = it), step, "記下：伏地挺身 $it 下。") }
            OnboardingStep.PLANK -> BaselineAnswers.plank(t)?.let { advance(b.copy(plankMaxSec = it), step, "記下：棒式 $it 秒。") }
            // Photos and the recording come through the screen; text can only skip them.
            else -> null
        }
    }

    /**
     * A photo or the recording, already sealed in the media vault ([media] is its row, written here with the link). Handed in
     * for the open question: saved and the next one asked. Handed in while another question is open (or none, a 補交 from the
     * card): saved, and the open question stays as it is.
     */
    suspend fun handIn(step: OnboardingStep, media: Media): String {
        require(step.isMedia) { "not a media step" }
        require((step == OnboardingStep.RECORDING) == (media.kind == MediaKind.AUDIO)) { "media kind does not fit the step" }
        val saved = "記下：${step.zh}收到了。"
        return store.transaction {
            store.media.insert(media)
            val b = join() ?: Baseline(Baseline.JOIN)
            val got = BaselineSteps.withMedia(b, step, media.id).copy(skipped = b.skipped.filter { OnboardingStep.parse(it) != step })
            if (BaselineSteps.open(b) == step) {
                advance(got, step, saved)
            } else {
                store.baselines.upsert(finishIfDone(got))
                saved
            }
        }
    }

    private suspend fun body(b: Baseline, t: String): String? {
        val profile = store.profile.get() ?: Profile()
        val latest = latestWeight()
        val shownWeight = latest ?: profile.startWeightKg
        val (h, w) = if (t in BaselineAnswers.CONFIRM) {
            // A confirm only means something when both numbers were read out to him.
            if (profile.heightCm == null || shownWeight == null) return null
            profile.heightCm to shownWeight
        } else {
            BaselineAnswers.body(t) ?: return null
        }
        val height = h ?: profile.heightCm
        val weight = w ?: shownWeight
        val day = coachDay(profile)
        return store.transaction {
            if (height != null && height != profile.heightCm) store.profile.put(profile.copy(heightCm = height))
            // A weight he typed is a new reading; a confirm (or the same number again) adds nothing.
            if (w != null && (latest == null || abs(latest - w) > 1e-9)) {
                store.weights.insert(WeightLog(newId(), day.toString(), clock.millis(), w))
            }
            val got = b.copy(heightCm = height ?: b.heightCm, weightKg = weight ?: b.weightKg)
            val saved = listOfNotNull(height?.let { "身高 ${CoachRules.kg(it)} 公分" }, weight?.let { "體重 ${CoachRules.kg(it)} 公斤" })
            if (BaselineSteps.has(got, OnboardingStep.BODY)) {
                advance(got, OnboardingStep.BODY, "記下：${saved.joinToString("、")}。")
            } else {
                // Only one of the two: saved, the question stays open for the other.
                store.baselines.upsert(got.copy(step = OnboardingStep.BODY.name))
                "記下：${saved.joinToString("、")}。" + if (got.heightCm == null) "再打身高，例如「身高 170」。" else "再打體重，例如「體重 78」。"
            }
        }
    }

    /** Saves [b], moves on to the next item after [from] that is neither handed in nor skipped, and returns the reply. */
    private suspend fun advance(b: Baseline, from: OnboardingStep, saved: String): String {
        val skipped = BaselineSteps.skipped(b)
        val next = OnboardingStep.entries.dropWhile { it != from }.drop(1).firstOrNull { !BaselineSteps.has(b, it) && it !in skipped }
        return (listOf(saved) + advanceTo(b, next, store.profile.get() ?: Profile())).joinToString("\n")
    }

    /** Stores [b] with [next] open (or finished when there is none) and returns the lines to say. */
    private suspend fun advanceTo(b: Baseline, next: OnboardingStep?, profile: Profile): List<String> {
        if (next != null) {
            store.baselines.upsert(b.copy(step = next.name))
            return listOf(question(next, profile))
        }
        val done = b.copy(step = null, completedAt = b.completedAt ?: clock.millis())
        store.baselines.upsert(done)
        if (!profile.onboardingDone) store.profile.put(profile.copy(onboardingDone = true))
        val missing = BaselineSteps.missing(done)
        return listOf(
            if (missing.isEmpty()) {
                "入社建檔完成。這是以後每次評鑑的比較基準，從今天開始照公司規章走。"
            } else {
                "入社建檔先到這裡，還缺：${missing.joinToString("、") { it.zh }}。之後按練習生證上的「補交」。"
            },
        )
    }

    /** A 補交 from the card that hands in the last missing item finishes 入社建檔 too. */
    private fun finishIfDone(b: Baseline): Baseline =
        if (b.completedAt == null && BaselineSteps.open(b) == null && BaselineSteps.missing(b).isEmpty()) b.copy(completedAt = clock.millis()) else b

    private suspend fun latestWeight(): Double? =
        store.weights.between(MIN_DAY, MAX_DAY).maxWithOrNull(compareBy({ it.at }, { it.id }))?.kg

    private fun coachDay(p: Profile) = CoachRules.coachDay(clock.now(), clock.zone, p.dayStartHour)

    private fun welcome(p: Profile): String {
        val company = p.companyName ?: PromptInput.DEFAULT_COMPANY
        val day = p.joinDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.let { ChronoUnit.DAYS.between(it, coachDay(p)) + 1 }?.takeIf { it >= 1 }
        val dayPart = day?.let { "今天是入社第 $it 天。" } ?: ""
        return "歡迎加入$company。你是公司唯一的練習生，等級練習生 ${p.level}。$dayPart" +
            "入社第一件事：建檔。這是以後每次評鑑的比較基準。一次問一項，現在不做就回「跳過」，之後再補交。"
    }

    private suspend fun question(step: OnboardingStep, p: Profile): String = when (step) {
        OnboardingStep.BODY -> {
            val w = latestWeight() ?: p.startWeightKg
            if (p.heightCm != null && w != null) {
                "先確認身高 ${CoachRules.kg(p.heightCm)} 公分、體重 ${CoachRules.kg(w)} 公斤。對就回「對」，不對就打「身高 170 體重 78」這樣。"
            } else {
                "先量身高體重，打「身高 170 體重 78」這樣。"
            }
        }
        OnboardingStep.PHOTO_FRONT ->
            "交三張全身照，先拍正面：站直，手機放在固定的位置，找一個以後每個月都能用的地方和一樣的光線。按下面的「拍照」。"
        OnboardingStep.PHOTO_SIDE -> "再拍側面：同一個位置、同樣的光線，身體轉九十度站直。按「拍照」。"
        OnboardingStep.PHOTO_BACK -> "最後拍背面：同一個位置、同樣的光線，背對手機站直。按「拍照」。"
        OnboardingStep.RECORDING -> "交一段清唱錄音，最長三分鐘。按「開始錄音」，唱完按「停」，可以先聽一次，不滿意就重錄，滿意就按「交出」。"
        OnboardingStep.PUSHUPS -> "體能測驗第一項：伏地挺身，做到做不動為止。做完打「伏地挺身 幾下」。"
        OnboardingStep.PLANK -> "體能測驗第二項：棒式，撐到撐不住為止。可以用下面的計時，或做完打「棒式 幾秒」。"
    }

    companion object {
        private const val MIN_DAY = "0000-01-01"
        private const val MAX_DAY = "9999-12-31"
    }
}
