package app.agency.core.brain.handlers

import app.agency.core.engine.Signal
import app.agency.core.lessons.AskedQuestion
import app.agency.core.lessons.GradedQuestion
import app.agency.core.lessons.LessonPlan
import app.agency.core.lessons.LessonStage
import app.agency.core.lessons.QuizCheck
import app.agency.core.lessons.QuizDraft
import app.agency.core.lessons.QuizGrader
import app.agency.core.lessons.QuizSpec
import app.agency.core.llm.Fail
import app.agency.core.llm.Purpose
import app.agency.core.llm.SearchClient
import app.agency.core.llm.Turn
import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.Media
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Quiz
import app.agency.core.model.QuizKind
import app.agency.core.model.QuizStatus
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.store.AgencyStore
import app.agency.core.structured.Structured
import app.agency.core.structured.StructuredCaller
import app.agency.core.time.AgencyClock
import java.time.LocalDate

/** What a lesson action said ([lines], one bubble of the manager), the signals it emits, and the lessons its cards show. */
data class LessonOutcome(val lines: List<String>, val signals: List<Signal> = emptyList(), val cards: List<String> = emptyList())

/** One lesson as the 理論課 tab shows it (T19c): never the link (去看 asks the brain for it) and never the answers. */
data class LessonView(
    val id: String,
    val title: String,
    val category: LessonCategory,
    val stage: LessonStage,
    /** Its turn has not come: the one before must pass first. */
    val locked: Boolean = false,
    val score: Int? = null,
    val retries: Int = 0,
    val fallback: Boolean = false,
    val reviewScore: Int? = null,
    val reviewDue: Boolean = false,
    val practicalDone: Boolean = false,
    val passedDay: String? = null,
    val summary: String? = null,
)

/** The 理論課 tab: today's lessons, the open questions, 「課程清單」, the candidates, the 複習考 due, and the history. */
data class LessonBoard(
    val started: Boolean,
    val today: List<LessonView>,
    val passedToday: Int,
    val questions: List<AskedQuestion>,
    val reviewQuestions: Map<String, List<AskedQuestion>>,
    val list: List<LessonView>,
    val candidates: List<LessonView>,
    val reviews: List<LessonView>,
    val history: List<LessonView>,
)

/**
 * 理論課 (T19c), code first. Videos come only from DeepSeek Search through the network gate ([search]): each hit is stored as a
 * search result and a CANDIDATE lesson, with its title, link and text copied by code; nothing is in 「課程清單」 until his 好. The
 * app never fetches a video page; 去看 hands the stored link to his YouTube app or browser. 看完了 → three questions written
 * by the model strictly from the 教材 (each quote checked verbatim by code), graded by option index; two right pass, a miss
 * shows the right answer and its quote and one fresh question; a note of 30 Han characters unlocks the next. Too thin a
 * 教材 asks for three key points first; a quiz that cannot be made becomes 「寫下三個重點」. Day +2: the 複習考, recorded only.
 */
class LessonDesk(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val newId: () -> String,
    private val structured: StructuredCaller,
    private val search: SearchClient?,
    private val today: suspend () -> LocalDate,
) {
    /** 找影片 (a category, or the one with the fewest videos): real search results become candidate cards. */
    suspend fun find(category: LessonCategory?): LessonOutcome {
        val client = search ?: return LessonOutcome(listOf(NO_SEARCH))
        val all = store.lessons.all()
        val cat = category ?: LessonPlan.nextCategory(all)
        val language = if (cat == LessonCategory.LANGUAGE) LessonPlan.language() else null
        val found = when (val r = client.searchVideos(LessonPlan.query(cat, language))) {
            is app.agency.core.llm.SearchResult.Failed -> return LessonOutcome(listOf(failLine(r.fail)))
            is app.agency.core.llm.SearchResult.Ok -> r
        }
        val known = all.map { it.url }.toSet()
        val now = clock.millis()
        val kept = found.hits.filter { it.url !in known }.take(MAX_CANDIDATES).map { h ->
            val rid = newId()
            store.places.putResult(SearchResult(rid, SearchKind.VIDEO, found.query, h.url, h.title, h.summary, h.date, h.authority.toDouble(), h.rerank, now))
            val lesson = Lesson(
                id = newId(), resultId = rid, category = cat, language = language, title = h.title, url = h.url,
                source = source(h.title, h.summary, h.content), foundAt = now,
            )
            store.lessons.insert(lesson)
            lesson.id
        }
        val what = cat.zh + (language?.let { "（${it.zh}）" } ?: "")
        if (kept.isEmpty()) return LessonOutcome(listOf("沒有找到新的「$what」影片。換一類試試，例如說「找舞蹈影片」。"))
        return LessonOutcome(listOf("找到 ${kept.size} 支「$what」的影片，都是真的搜尋結果。到理論課看，想上的按「好」放進課程清單，不要的按「不要」。"), cards = kept)
    }

    /** His 好 on a candidate: it joins 「課程清單」 at the end. */
    suspend fun approve(id: String): LessonOutcome? {
        val l = store.lessons.get(id)?.takeIf { it.status == LessonStatus.CANDIDATE } ?: return null
        store.lessons.upsert(l.copy(status = LessonStatus.LISTED, listedAt = clock.millis()))
        val n = LessonPlan.queue(store.lessons.all()).size
        return LessonOutcome(listOf("放進課程清單了，清單裡有 $n 支。一次一支，前一支考過、寫好筆記才開下一支。"))
    }

    suspend fun drop(id: String): LessonOutcome? {
        val l = store.lessons.get(id)?.takeIf { it.status == LessonStatus.CANDIDATE } ?: return null
        store.lessons.upsert(l.copy(status = LessonStatus.DROPPED))
        return LessonOutcome(listOf("好，這支不放。"))
    }

    /** 去看: the stored link of the open lesson (or one already passed, to watch again); null for a locked or unknown one. */
    suspend fun open(id: String): String? {
        val l = store.lessons.get(id) ?: return null
        val all = store.lessons.all()
        if (!l.passed && LessonPlan.current(all)?.id != l.id) return null
        if (!l.passed) store.lessons.upsert(l.copy(openedAt = l.openedAt ?: clock.millis()))
        return l.url
    }

    /** 看完了: the key points first (a thin 教材), or the quiz. */
    suspend fun watched(id: String): LessonOutcome? {
        val l = current(id)?.takeIf { LessonPlan.stage(it) == LessonStage.TO_WATCH } ?: return null
        val w = l.copy(watchedAt = clock.millis())
        store.lessons.upsert(w)
        if (LessonPlan.thin(w.source)) return LessonOutcome(listOf(ASK_POINTS_THIN))
        return quizFor(w)
    }

    /** His three key points: they become the 教材 (thin), or stand for the quiz (it could not be made). */
    suspend fun keyPoints(id: String, points: List<String>): LessonOutcome? {
        val l = current(id)?.takeIf { LessonPlan.stage(it) == LessonStage.KEY_POINTS } ?: return null
        LessonPlan.pointsProblem(points)?.let { return LessonOutcome(listOf(it)) }
        val text = points.map { it.trim() }.filter { it.isNotEmpty() }.take(LessonPlan.POINTS).joinToString("\n")
        val k = l.copy(keyPoints = text)
        if (k.fallback) {
            store.lessons.upsert(k.copy(quizPassedAt = clock.millis()))
            return LessonOutcome(listOf(POINTS_PASS))
        }
        store.lessons.upsert(k)
        return quizFor(k)
    }

    /** The quiz from the lesson's 教材, or the 「寫下三個重點」 fallback when no good questions came. */
    private suspend fun quizFor(l: Lesson): LessonOutcome {
        val material = LessonPlan.material(l)
        val avoid = store.lessons.quizzes(l.id).flatMap { q -> q.questions.map { it.q } }
        val draft = make(material, avoid, LessonPlan.QUESTIONS, LessonPlan.SPARES)
        if (draft == null) {
            val f = l.copy(fallback = true)
            if (f.keyPoints != null) {
                store.lessons.upsert(f.copy(quizPassedAt = clock.millis()))
                return LessonOutcome(listOf("題目出不來，你寫的三個重點就算考過。$ASK_NOTE"))
            }
            store.lessons.upsert(f)
            return LessonOutcome(listOf(ASK_POINTS_FALLBACK))
        }
        store.lessons.putQuiz(Quiz(newId(), l.id, QuizKind.MAIN, clock.millis(), draft.questions, shown = LessonPlan.QUESTIONS))
        return LessonOutcome(listOf(QUIZ_READY))
    }

    /** At least [need] checked questions from [material], or null (no answer, or nothing passed the checks after the retries). */
    private suspend fun make(material: String, avoid: List<String>, need: Int, spares: Int): QuizDraft? {
        val out = structured.call(
            QuizSpec.SPEC, QuizSpec.system(need + spares), listOf(Turn.User(QuizSpec.user(material, avoid))), Purpose.QUIZ, QUIZ_TOKENS,
            check = { QuizCheck.check(it, material, need, spares, avoid) }, fallback = { null },
        )
        return (out as? Structured.Model)?.value
    }

    /** The open quiz of the current lesson. */
    suspend fun openQuiz(l: Lesson): Quiz? = store.lessons.quizzes(l.id).lastOrNull { it.kind == QuizKind.MAIN && it.status == QuizStatus.OPEN }

    /** His answers to the open questions (in order): graded by index; pass, one fresh question, or watch again. */
    suspend fun answer(id: String, choices: List<Int>): LessonOutcome? {
        val l = current(id)?.takeIf { LessonPlan.stage(it) == LessonStage.TO_QUIZ } ?: return null
        val quiz = openQuiz(l) ?: return null
        val (next, graded) = QuizGrader.submit(quiz, choices, review = false) ?: return null
        store.lessons.putQuiz(next)
        val lines = misses(graded).toMutableList()
        val first = l.score ?: QuizGrader.firstRound(next).takeIf { next.answers.size >= LessonPlan.QUESTIONS }
        val retries = l.retries + (graded.size.takeIf { quiz.answers.isNotEmpty() } ?: 0)
        when (next.status) {
            QuizStatus.PASSED -> {
                store.lessons.upsert(l.copy(score = first, retries = retries, quizPassedAt = clock.millis()))
                lines += "答對 ${next.score} 題，考過了。$ASK_NOTE"
            }
            QuizStatus.FAILED -> {
                store.lessons.upsert(l.copy(score = first, retries = retries, watchedAt = null))
                lines += "這次沒過。回去再看一次影片，看完按「看完了」再考，會出新的題目。"
            }
            else -> {
                store.lessons.upsert(l.copy(score = first, retries = retries))
                lines += "答對 ${next.score} 題，要答對 ${LessonPlan.PASS} 題才過。下面多一題新的，再答一次。"
            }
        }
        return LessonOutcome(lines)
    }

    /** His note: the lesson passes, the next opens, the 複習考 is set two days on. */
    suspend fun note(id: String, text: String): LessonOutcome? {
        val l = current(id)?.takeIf { LessonPlan.stage(it) == LessonStage.NOTE } ?: return null
        LessonPlan.noteProblem(text)?.let { return LessonOutcome(listOf(it)) }
        val day = today()
        val now = clock.millis()
        val passed = l.copy(note = text.trim().take(NOTE_CHARS), passedAt = now, passedDay = day.toString(), reviewDay = day.plusDays(LessonPlan.REVIEW_AFTER_DAYS).toString())
        store.lessons.upsert(passed)
        val n = store.lessons.all().count { it.passedDay == day.toString() }
        val all = store.lessons.all()
        val lines = mutableListOf("通過：今天第 $n 支。兩天後有複習考。")
        lines += when {
            n == LessonPlan.DAILY -> "今天的理論課做到了。想多看就繼續下一支。"
            LessonPlan.current(all) != null -> "下一支開了，到理論課按「去看」。"
            else -> "課程清單看完了。說「找影片」補新的。"
        }
        l.category.practical?.let { lines += "這一課有實作：${it.zh}。想做就在理論課交，不做也可以。" }
        val signal = Signal.LessonPassed("lesson:${l.id}", now, l.id, l.category.name, l.score, l.retries, l.fallback)
        return LessonOutcome(lines, listOf(signal))
    }

    /** A 實作 sealed in the vault ([media] is written here with its link): only 「交了」 goes anywhere else. */
    suspend fun practical(id: String, media: Media): LessonOutcome? {
        val l = store.lessons.get(id)?.takeIf { it.status == LessonStatus.LISTED } ?: return null
        val kind = l.category.practical ?: return null
        if (media.kind != kind.media || media.purpose != MediaPurpose.LESSON) return null
        store.transaction {
            store.media.insert(media)
            store.lessons.upsert(l.copy(practicalMediaId = media.id))
        }
        return LessonOutcome(listOf("實作收到了，記下：交了。"), listOf(Signal.PracticalHandedIn("practical:${l.id}:${media.id}", clock.millis(), l.id, l.category.name)))
    }

    /** 開始複習考: three new questions from the 教材 and his note (reused while unanswered). */
    suspend fun startReview(id: String): LessonOutcome? {
        val l = reviewable(id) ?: return null
        if (openReview(l) != null) return LessonOutcome(listOf(REVIEW_READY))
        val material = LessonPlan.reviewMaterial(l)
        val avoid = store.lessons.quizzes(l.id).flatMap { q -> q.questions.map { it.q } }
        val draft = make(material, avoid, LessonPlan.QUESTIONS, 0) ?: return LessonOutcome(listOf("複習考的題目這次出不來，等一下再試。只記錄，不擋課。"))
        store.lessons.putQuiz(Quiz(newId(), l.id, QuizKind.REVIEW, clock.millis(), draft.questions, shown = LessonPlan.QUESTIONS))
        return LessonOutcome(listOf(REVIEW_READY))
    }

    suspend fun openReview(l: Lesson): Quiz? = store.lessons.quizzes(l.id).lastOrNull { it.kind == QuizKind.REVIEW && it.status == QuizStatus.OPEN }

    /** His answers to the 複習考: graded by index and recorded (nothing is locked by it). */
    suspend fun answerReview(id: String, choices: List<Int>): LessonOutcome? {
        val l = reviewable(id) ?: return null
        val quiz = openReview(l) ?: return null
        val (next, graded) = QuizGrader.submit(quiz, choices, review = true) ?: return null
        store.lessons.putQuiz(next)
        val now = clock.millis()
        store.lessons.upsert(l.copy(reviewScore = next.score, reviewedAt = now))
        val lines = misses(graded) + "複習考答對 ${next.score} 題，記下了。"
        return LessonOutcome(lines, listOf(Signal.LessonReviewed("review:${l.id}", now, l.id, l.category.name, next.score)))
    }

    private suspend fun reviewable(id: String): Lesson? {
        val l = store.lessons.get(id)?.takeIf { it.passed && it.reviewedAt == null } ?: return null
        val due = l.reviewDay?.let(LocalDate::parse) ?: return null
        return l.takeIf { !today().isBefore(due) }
    }

    private suspend fun current(id: String): Lesson? = LessonPlan.current(store.lessons.all())?.takeIf { it.id == id }

    /** The tab's whole picture now. */
    suspend fun board(): LessonBoard {
        val all = store.lessons.all()
        val day = today().toString()
        val queue = LessonPlan.queue(all)
        val cur = queue.firstOrNull()
        val passedToday = all.filter { it.passedDay == day }.sortedBy { it.passedAt }
        val upcoming = queue.drop(1).take(maxOf(0, LessonPlan.DAILY_SHOWN - passedToday.size - 1)).map { view(it, locked = true) }
        val reviews = all.filter { l -> l.passed && l.reviewedAt == null && l.reviewDay?.let { it <= day } == true }
        return LessonBoard(
            started = all.any { it.listedAt != null },
            today = passedToday.map { view(it) } + listOfNotNull(cur?.let { view(it) }) + upcoming,
            passedToday = passedToday.size,
            questions = cur?.let { c -> openQuiz(c)?.takeIf { LessonPlan.stage(c) == LessonStage.TO_QUIZ }?.let(QuizGrader::open) }.orEmpty(),
            reviewQuestions = reviews.mapNotNull { r -> openReview(r)?.let { r.id to QuizGrader.open(it) } }.toMap(),
            list = queue.map { view(it, locked = it.id != cur?.id) },
            candidates = all.filter { it.status == LessonStatus.CANDIDATE }.map { view(it, summary = true) },
            reviews = reviews.map { view(it).copy(reviewDue = true) },
            history = all.filter { it.passed }.sortedByDescending { it.passedAt }.map { view(it) },
        )
    }

    private suspend fun view(l: Lesson, locked: Boolean = false, summary: Boolean = false): LessonView = LessonView(
        l.id, l.title, l.category, LessonPlan.stage(l), locked, l.score, l.retries, l.fallback, l.reviewScore,
        practicalDone = l.practicalMediaId != null, passedDay = l.passedDay,
        summary = if (summary) store.places.result(l.resultId)?.summary else null,
    )

    /** Each miss: the right answer and the 教材's words that show it. */
    private fun misses(graded: List<GradedQuestion>): List<String> = graded.filter { !it.correct }.map { g ->
        "「${g.q}」答錯了。正確答案：${g.rightOption}。教材原文：「${g.quote}」"
    }

    companion object {
        const val MAX_CANDIDATES = 5
        const val QUIZ_TOKENS = 1600
        const val NOTE_CHARS = 1000
        const val NO_SEARCH = "現在不能找影片：要有 DeepSeek 的金鑰（設定的模型與金鑰）。"
        const val QUIZ_READY = "看完了就考：三題選擇題，到理論課作答。憑記憶答，不要回去看影片。"
        const val REVIEW_READY = "複習考三題，到理論課作答。只記錄，不擋課。"
        const val ASK_NOTE = "最後寫筆記，至少 ${LessonPlan.NOTE_HAN} 個字，寫完就開下一支。"
        const val POINTS_PASS = "三個重點收到，算考過。$ASK_NOTE"
        const val ASK_POINTS_THIN = "這支搜尋到的文字太少，出不了題。先用自己的話寫三個重點，每個至少 ${LessonPlan.POINT_HAN} 個字，寫完我照你的重點出題。"
        const val ASK_POINTS_FALLBACK = "這支的題目出不來。改成寫下三個重點，每個至少 ${LessonPlan.POINT_HAN} 個字。"

        /** The 教材 kept from a search hit: its title, its snippet, and the page text, as the search returned them. */
        fun source(title: String, summary: String, content: String): String =
            listOf(title, summary, content).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").take(LessonPlan.SOURCE_CHARS)

        fun failLine(fail: Fail): String = when (fail) {
            Fail.CAP -> "今天的花費到上限了，不能搜尋影片。"
            Fail.NO_KEY, Fail.NO_SETUP, Fail.KEY_REJECTED -> NO_SEARCH
            else -> "現在搜尋不到影片，等一下再試。"
        }
    }
}
