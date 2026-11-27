package app.agency.core.lessons

import app.agency.core.brain.Brain
import app.agency.core.brain.FactsBuilder
import app.agency.core.engine.Links
import app.agency.core.engine.Nudger
import app.agency.core.engine.Signal
import app.agency.core.engine.SlotStatus
import app.agency.core.lessons.LessonKit.FakeSearch
import app.agency.core.lessons.LessonKit.quiz
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectKind
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Profile
import app.agency.core.model.Quiz
import app.agency.core.model.QuizKind
import app.agency.core.model.QuizQuestion
import app.agency.core.model.QuizStatus
import app.agency.core.model.SearchKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.Checked
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 理論課 (T19c): videos from real search results only, the quiz checked and graded by code, the unlock, the 複習考. */
class LessonT19cTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private val monday = LocalDate.parse("2026-12-07")
    private val search = FakeSearch()
    private var n = 0

    init {
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
    }

    private fun brain(llm: LlmClient, s: FakeSearch? = search) =
        Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), search = s) { "id-%04d".format(++n) }

    private fun t(hhmm: String, day: LocalDate = monday): Long = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()
    private fun at(hhmm: String, day: LocalDate = monday) { clock.now = Instant.ofEpochMilli(t(hhmm, day)) }
    private fun lines(m: List<app.agency.core.model.Message>) = m.filter { it.who == Who.COMPANY }.joinToString("\n") { it.text }

    /** 找影片 at 09:00, both candidates said 好 to; the lesson ids in list order. */
    private fun listTwo(b: Brain): List<String> = runBlocking {
        at("09:00")
        val found = b.findVideos(LessonCategory.VOCAL).single { it.kind == Brain.LESSON_FOUND_KIND }
        found.refs.records.forEach { b.approveLesson(it) }
        found.refs.records
    }

    private val note = "腹式呼吸要讓肚子鼓起來，肩膀不能抬，吐氣維持十五秒，高音時氣往下沉不要擠喉嚨。"

    // ---- search and the list ----

    @Test fun videosComeOnlyFromStoredSearchResultsAndJoinTheListOnlyOnHisYes() = runBlocking {
        val b = brain(ScriptedLlm())
        at("09:00")
        val found = b.findVideos(LessonCategory.VOCAL)
        val card = found.single { it.kind == Brain.LESSON_FOUND_KIND }
        assertTrue(card.text.startsWith("找到 2 支「聲樂」的影片，都是真的搜尋結果。"))
        assertEquals(listOf("發聲練習 唱歌技巧 教學 入門 初學 影片"), search.queries)
        // Stored: the search results (the cards' evidence) and the candidates, each with the hit's own link and text.
        val results = store.places.results(SearchKind.VIDEO)
        assertEquals(setOf(LessonKit.VOCAL_URL, LessonKit.SMILE_URL), results.map { it.url }.toSet())
        val candidates = store.lessons.withStatus(LessonStatus.CANDIDATE)
        assertEquals(card.refs.records, candidates.map { it.id })
        candidates.forEach { l -> assertEquals(results.single { it.id == l.resultId }.url, l.url) }
        assertTrue(candidates.first().source.contains("腹式呼吸是唱歌的基礎"))
        // Nothing is in the list (nor in the plan) before his 好; his 不要 keeps one out, and the search never brings it back.
        assertNull(b.nerve.plan(monday).slot(SlotKey.LESSONS))
        assertTrue(b.lessonBoard().list.isEmpty())
        b.approveLesson(candidates[0].id)
        b.dropLesson(candidates[1].id)
        assertEquals(listOf(candidates[0].id), b.lessonBoard().list.map { it.id })
        assertEquals(2, b.nerve.plan(monday).slot(SlotKey.LESSONS)!!.count)
        val again = b.findVideos(LessonCategory.VOCAL)
        assertTrue(lines(again), lines(again).startsWith("沒有找到新的「聲樂」影片"))
        // No search client (no DeepSeek key): a fixed line, nothing stored.
        assertEquals(listOf(app.agency.core.brain.handlers.LessonDesk.NO_SEARCH), brain(ScriptedLlm(), s = null).findVideos().filter { it.who == Who.COMPANY }.map { it.text })
    }

    @Test fun theCategoryRotatesAndTheLanguageIsEnglishFirstOneAtATime() = runBlocking {
        val b = brain(ScriptedLlm())
        at("09:00")
        search.hits = emptyList()
        b.findVideos() // VOCAL has the fewest (none): the first category
        search.hits = listOf(LessonKit.hit("https://www.youtube.com/watch?v=v2", "發聲", LessonKit.VOCAL))
        b.findVideos(LessonCategory.VOCAL)
        b.findVideos() // VOCAL has one now: DANCE is next
        assertEquals("韓國偶像 舞蹈 基本功 教學 入門 初學 影片", search.queries[2])
        assertEquals("英文 初學 會話 教學 入門 初學 影片", LessonPlan.query(LessonCategory.LANGUAGE, LessonPlan.language()))
        assertEquals(app.agency.core.model.StudyLanguage.ENGLISH, LessonPlan.language())
        // Typed: 「找表情影片」 names the category; 「找影片」 picks the next one.
        search.hits = emptyList()
        b.onText("找表情管理影片")
        assertEquals("表情管理 鏡頭前 笑容 教學 入門 初學 影片", search.queries.last())
    }

    // ---- the quiz: code checks every quote, code grades ----

    @Test fun quoteVerificationRejectsInventedQuestions() {
        val good = LessonKit.VOCAL_QS[0].let { QuizQuestion(it.q, it.options, it.answer, it.quote) }
        assertTrue(QuizCheck.problems(good, LessonKit.VOCAL).isEmpty())
        val invented = LessonKit.INVENTED.let { QuizQuestion(it.q, it.options, it.answer, it.quote) }
        assertTrue(QuizCheck.problems(invented, LessonKit.VOCAL).contains("quote 不是教材裡一字不差的原文"))
        // Changed by one character is not verbatim; folded white space is.
        assertFalse(QuizCheck.problems(good.copy(quote = "盡量維持十六秒以上"), LessonKit.VOCAL).isEmpty())
        assertTrue(QuizCheck.problems(good.copy(quote = "盡量維持 十五秒以上"), "盡量維持\n十五秒以上，聲音要平穩").isEmpty())
        // Three options, a repeated option, an answer out of range, a link-like option, a question asked before.
        assertFalse(QuizCheck.problems(good.copy(options = good.options.take(3)), LessonKit.VOCAL).isEmpty())
        assertFalse(QuizCheck.problems(good.copy(options = listOf("五秒", "五秒", "十五秒", "三十秒")), LessonKit.VOCAL).isEmpty())
        assertFalse(QuizCheck.problems(good.copy(answer = 4), LessonKit.VOCAL).isEmpty())
        assertTrue(QuizCheck.problems(good.copy(options = listOf("看 https 網站", "十秒", "十五秒", "三十秒")), LessonKit.VOCAL).contains("外文字只能用教材裡有的"))
        assertTrue(QuizCheck.problems(good, LessonKit.VOCAL, setOf(good.q)).contains("這題之前考過了"))
        // Two good ones are not enough: the reasons go back.
        val two = QuizDraft(listOf(good, invented, good.copy(q = "另一題：嘶的聲音要多久？")))
        val r = QuizCheck.check(two, LessonKit.VOCAL, need = 3, spares = 3)
        assertTrue(r is Checked.Reject && r.violations.any { it.field == "questions[1]" })
        // A page that teaches skipping meals never becomes a question (REVIEW-safety M4).
        val fasting = good.copy(q = "想瘦要怎麼做？", options = listOf("晚餐跳過", "照常吃", "多喝水", "早睡"))
        val r2 = QuizCheck.check(QuizDraft(listOf(fasting)), LessonKit.VOCAL, need = 1, spares = 0)
        assertTrue(r2 is Checked.Reject && r2.violations.any { it.field == "questions[0]" && it.reason.contains("不能教") })
    }

    @Test fun inventedQuizzesAreSentBackThenTheLessonFallsBackToThreeKeyPoints() = runBlocking {
        val bad = LessonKit.VOCAL_QS.take(2) + LessonKit.INVENTED
        val llm = ScriptedLlm(quiz(bad), quiz(bad), quiz(bad))
        val b = brain(llm)
        val (first, _) = listTwo(b)
        at("10:00")
        assertEquals(LessonKit.VOCAL_URL, b.openLesson(first))
        val said = lines(b.lessonWatched(first))
        assertEquals(app.agency.core.brain.handlers.LessonDesk.ASK_POINTS_FALLBACK, said)
        assertEquals(3, llm.requests.size)
        assertTrue(llm.requests.all { it.purpose == Purpose.QUIZ })
        assertTrue((llm.requests[1].turns.last() as Turn.User).text.contains("quote 不是教材裡一字不差的原文"))
        // The 教材 went as data; no link, no title of another video, nothing of his.
        assertTrue(llm.requests.none { r -> r.turns.any { it.toString().contains("https://") } })
        assertTrue(store.lessons.quizzes(first).isEmpty())
        // His three points stand for the quiz; then the note unlocks the next.
        assertEquals("要寫三個重點，每個一行。", lines(b.lessonKeyPoints(first, listOf("呼吸用肚子不要用肩膀", "吐氣"))))
        assertEquals(app.agency.core.brain.handlers.LessonDesk.POINTS_PASS, lines(b.lessonKeyPoints(first, listOf("呼吸要用肚子不要聳起肩膀", "吐氣要維持十五秒以上而且平穩", "唱高音要讓氣往下沉別擠喉嚨"))))
        assertTrue(lines(b.lessonNote(first, note)).startsWith("通過：今天第 1 支。"))
        assertTrue(store.lessons.get(first)!!.let { it.fallback && it.score == null && it.passed })
    }

    @Test fun gradingIsByOptionIndexAndTwoOfThreePass() {
        val qs = LessonKit.VOCAL_QS.map { QuizQuestion(it.q, it.options, it.answer, it.quote) }
        val quiz = Quiz("q", "l", QuizKind.MAIN, 0, qs, shown = 3)
        // The model's reply is never read for a score: only the stored answer index decides.
        val (passed, graded) = QuizGrader.submit(quiz, listOf(2, 1, 0), review = false)!!
        assertEquals(listOf(true, true, false), graded.map { it.correct })
        assertEquals(QuizStatus.PASSED, passed.status)
        assertEquals(2, passed.score)
        // One right: one fresh question (the fourth), then right → passed with a retry.
        val (retry, _) = QuizGrader.submit(quiz, listOf(2, 0, 0), review = false)!!
        assertEquals(QuizStatus.OPEN, retry.status)
        assertEquals(listOf(3), QuizGrader.open(retry).map { it.index })
        val (after, g2) = QuizGrader.submit(retry, listOf(1), review = false)!!
        assertEquals(QuizStatus.PASSED, after.status)
        assertEquals("每天早上練習五分鐘", g2.single().quote)
        assertEquals(1, QuizGrader.firstRound(after))
        // A wrong count or an option out of range changes nothing.
        assertNull(QuizGrader.submit(quiz, listOf(1, 1), review = false))
        assertNull(QuizGrader.submit(quiz, listOf(1, 1, 4), review = false))
        // All wrong and every spare wrong: failed.
        var q = QuizGrader.submit(quiz, listOf(0, 0, 0), review = false)!!.first
        repeat(3) { q = QuizGrader.submit(q, listOf(3), review = false)!!.first }
        assertEquals(QuizStatus.FAILED, q.status)
    }

    @Test fun passingUnlocksTheNextOnlyWithTheNote() = runBlocking {
        val llm = ScriptedLlm(quiz(LessonKit.VOCAL_QS))
        val b = brain(llm)
        val (first, second) = listTwo(b)
        at("10:00")
        // The second is locked: no link, no 看完了.
        assertNull(b.openLesson(second))
        assertTrue(b.lessonBoard().list.single { it.id == second }.locked)
        b.openLesson(first)
        assertEquals(app.agency.core.brain.handlers.LessonDesk.QUIZ_READY, lines(b.lessonWatched(first)))
        // The board shows the questions and options, never the answers.
        val asked = b.lessonBoard().questions
        assertEquals(3, asked.size)
        assertEquals(LessonKit.VOCAL_QS[0].options, asked[0].options)
        val said = lines(b.answerLesson(first, listOf(2, 1, 0)))
        assertTrue(said, said.contains("「吸氣時肩膀要怎樣？」答錯了。正確答案：不要抬起來。教材原文：「吸氣的時候肩膀不要抬起來」"))
        assertTrue(said.endsWith("答對 2 題，考過了。${app.agency.core.brain.handlers.LessonDesk.ASK_NOTE}"))
        assertNull(b.openLesson(second))
        assertEquals("筆記至少 30 個字，現在 6 個。寫你記住了什麼、要怎麼用。", lines(b.lessonNote(first, "呼吸要用肚子")))
        val passed = lines(b.lessonNote(first, note))
        assertTrue(passed, passed.startsWith("通過：今天第 1 支。兩天後有複習考。\n下一支開了"))
        assertTrue(passed.contains("這一課有實作：錄一段聲音。"))
        assertEquals(LessonKit.SMILE_URL, b.openLesson(second))
        val l = store.lessons.get(first)!!
        assertEquals(2, l.score)
        assertEquals("2026-12-09", l.reviewDay)
        // Recorded for the month-end evaluation (L24).
        assertEquals(listOf("lesson"), store.nerve.effects(monday.toString()).filter { it.link == Links.L24Lessons.id }.map { it.because })
    }

    @Test fun aMissShowsTheRightAnswerAndOneFreshQuestionAndAllWrongMeansWatchAgain() = runBlocking {
        val llm = ScriptedLlm(quiz(LessonKit.VOCAL_QS), quiz(LessonKit.VOCAL_QS.drop(3) + LessonKit.VOCAL_REVIEW))
        val b = brain(llm)
        val (first, _) = listTwo(b)
        at("10:00")
        b.lessonWatched(first)
        val one = lines(b.answerLesson(first, listOf(2, 0, 0)))
        assertTrue(one, one.endsWith("答對 1 題，要答對 2 題才過。下面多一題新的，再答一次。"))
        assertEquals(listOf("每天早上要練多久？"), b.lessonBoard().questions.map { it.q })
        // Wrong again, then wrong on the last spare: failed, back to 待看, a new quiz next time (it avoids the old questions).
        b.answerLesson(first, listOf(0))
        assertTrue(lines(b.answerLesson(first, listOf(0))).contains("答對 1 題，要答對 2 題才過。"))
        val failed = lines(b.answerLesson(first, listOf(0)))
        assertTrue(failed, failed.endsWith("這次沒過。回去再看一次影片，看完按「看完了」再考，會出新的題目。"))
        assertEquals(LessonStage.TO_WATCH, LessonPlan.stage(store.lessons.get(first)!!))
        b.lessonWatched(first)
        assertTrue((llm.requests[1].turns.single() as Turn.User).text.contains("【之前考過的題目，不要再出一樣的】\n吐氣練習時嘶的聲音要維持多久以上？"))
        b.answerLesson(first, listOf(1, 1, 1))
        val l = store.lessons.get(first)!!
        assertEquals(1, l.score) // the first round of the first quiz is what is kept
        assertEquals(3, l.retries)
        assertEquals(LessonStage.NOTE, LessonPlan.stage(l))
    }

    @Test fun aThinTextAsksForThreeKeyPointsFirstAndTheQuizIsMadeFromThem() = runBlocking {
        search.hits = listOf(LessonKit.hit(LessonKit.THIN_URL, "英文打招呼五種說法", "", "影片介紹"))
        val points = listOf("見面可以說嗨比哈囉更輕鬆隨意", "早安要在中午以前說才算對", "很高興認識你要看著對方的眼睛說")
        val fromPoints = listOf(
                LessonKit.Q("什麼時候說早安才對？", listOf("中午以前", "晚上", "半夜", "隨時"), 0, "早安要在中午以前說才算對"),
                LessonKit.Q("說很高興認識你要看哪裡？", listOf("地上", "對方的眼睛", "手機", "天空"), 1, "很高興認識你要看著對方的眼睛說"),
                LessonKit.Q("比哈囉更輕鬆的是？", listOf("嗨", "您好", "再見", "謝謝"), 0, "見面可以說嗨比哈囉更輕鬆隨意"),
            )
        val llm = ScriptedLlm(quiz(fromPoints))
        val b = brain(llm)
        at("09:00")
        val id = b.findVideos(LessonCategory.LANGUAGE).single { it.kind == Brain.LESSON_FOUND_KIND }.refs.records.single()
        b.approveLesson(id)
        assertTrue(LessonPlan.thin(store.lessons.get(id)!!.source))
        assertEquals(app.agency.core.brain.handlers.LessonDesk.ASK_POINTS_THIN, lines(b.lessonWatched(id)))
        assertTrue(llm.requests.isEmpty())
        assertEquals("第 2 個重點太短，每個至少 10 個字，用自己的話寫。", lines(b.lessonKeyPoints(id, listOf(points[0], "早安", points[2]))))
        assertEquals(app.agency.core.brain.handlers.LessonDesk.QUIZ_READY, lines(b.lessonKeyPoints(id, points)))
        val sent = (llm.requests.single().turns.single() as Turn.User).text
        assertTrue(sent, sent.startsWith("【教材】\n${points.joinToString("\n")}"))
        assertFalse(sent.contains("影片介紹"))
        assertTrue(lines(b.answerLesson(id, listOf(0, 1, 0))).contains("答對 3 題，考過了"))
    }

    // ---- the day: the slot, the chase, the 複習考 ----

    @Test fun theLessonSlotIsInThePlanFromTheFirstYesDoneAtTwoAndChased() = runBlocking {
        val b = brain(ScriptedLlm(quiz(LessonKit.VOCAL_QS), quiz(LessonKit.SMILE_QS)))
        val ids = listTwo(b)
        val slot = b.nerve.plan(monday).slot(SlotKey.LESSONS)!!
        assertEquals("16:00", slot.time) // a class day: in class
        assertTrue(slot.required)
        assertEquals("15:30", b.nerve.plan(monday.plusDays(4)).slot(SlotKey.LESSONS)!!.time) // Friday's classes end at 16:00
        // One passed by 16:00 is not enough: the chase names it (L12).
        at("10:00")
        b.lessonWatched(ids[0]); b.answerLesson(ids[0], listOf(2, 1, 1)); b.lessonNote(ids[0], note)
        at("16:00")
        val push = b.tick().posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("理論課時間到了，做完回報。", push.text)
        at("16:20")
        b.lessonWatched(ids[1]); b.answerLesson(ids[1], listOf(0, 1, 2))
        val done = lines(b.lessonNote(ids[1], "笑的時候先想開心的事眼睛才會笑，眼神看鏡頭中間偏上，身體斜四十五度臉轉回來。"))
        assertTrue(done, done.contains("今天的理論課做到了。想多看就繼續下一支。"))
        val st = app.agency.core.engine.RuleEngine.status(b.nerve.plan(monday), app.agency.core.brain.SnapshotReader(store, AgencyClock.of(clock)).records(monday), clock.now, AgencyClock.TAIPEI)
        assertEquals(SlotStatus.DONE, st.single { it.key == SlotKey.LESSONS }.status)
        assertEquals(2, b.nerve.state(monday).lessonsPassed)
        assertTrue(b.tick().posts.none { it.key == Nudger.GAP_KEY })
    }

    @Test fun aNoClassDayPutsTheLessonsAfterTheFirstMealBeforeTheWalk() = runBlocking {
        val b = brain(ScriptedLlm())
        listTwo(b)
        val wed = monday.plusDays(2)
        at("08:00", wed)
        b.signal(Signal.DayStarted("day:$wed", t("08:00", wed), wed.toString()))
        val l18 = store.nerve.effects(wed.toString()).filter { it.link == Links.L18NoClassDay.id }.map { it.because }
        assertEquals(listOf("因為今天沒課，所以 16:20 快走 30 分鐘。", "因為今天沒課，所以理論課 2 支排在第一餐之後，16:00 前看完、考完、寫好筆記。"), l18)
        assertEquals("16:00", b.nerve.plan(wed).slot(SlotKey.LESSONS)!!.time)
    }

    @Test fun twoDaysLaterTheReviewSlotRemindsAndTheReviewIsOnlyRecorded() = runBlocking {
        val llm = ScriptedLlm(quiz(LessonKit.VOCAL_QS), quiz(LessonKit.VOCAL_REVIEW))
        val b = brain(llm)
        val (first, second) = listTwo(b)
        at("10:00")
        b.lessonWatched(first); b.answerLesson(first, listOf(2, 1, 1)); b.lessonNote(first, note)
        // Tuesday: no review yet. Wednesday (+2): a review slot (not required) and its reminder at 14:00 (no-class day).
        assertNull(b.nerve.plan(monday.plusDays(1)).slot(SlotKey.LESSON_REVIEW))
        val wed = monday.plusDays(2)
        val review = b.nerve.plan(wed).slot(SlotKey.LESSON_REVIEW)!!
        assertFalse(review.required)
        assertEquals("14:00", review.time)
        at("09:00", wed)
        b.tick()
        val remind = store.nerve.effects(wed.toString()).single { it.link == Links.L24Lessons.id && it.kind == EffectKind.NOTIFY }
        assertEquals(Links.L24Lessons.reviewLine(1), remind.because)
        assertEquals(t("14:00", wed), remind.dueAt)
        at("14:00", wed)
        assertEquals(listOf(Links.L24Lessons.reviewLine(1)), b.tick().posts.filter { it.key != Nudger.GAP_KEY }.map { it.text })
        // Before it: the second lesson is open anyway (the review locks nothing).
        assertEquals(LessonKit.SMILE_URL, b.openLesson(second))
        assertEquals(app.agency.core.brain.handlers.LessonDesk.REVIEW_READY, lines(b.startReview(first)))
        // Three new questions, from the 教材 and his note, the old ones to avoid.
        val sent = (llm.requests[1].turns.single() as Turn.User).text
        assertTrue(sent.contains(note) && sent.contains("【之前考過的題目，不要再出一樣的】"))
        assertEquals(3, b.lessonBoard().reviewQuestions.getValue(first).size)
        val said = lines(b.answerReview(first, listOf(1, 0, 1)))
        assertTrue(said, said.endsWith("複習考答對 2 題，記下了。"))
        assertEquals(2, store.lessons.get(first)!!.reviewScore)
        assertEquals(listOf("lesson_review"), store.nerve.effects(wed.toString()).filter { it.link == Links.L24Lessons.id && it.kind == EffectKind.RECORD }.map { it.because })
        assertTrue(b.lessonBoard().reviews.isEmpty())
    }

    // ---- what reaches a prompt ----

    @Test fun aPracticalIsOnlyHandedInOrNotAndNoLessonTextReachesThePrompt() = runBlocking {
        val b = brain(ScriptedLlm(quiz(LessonKit.VOCAL_QS)))
        val (first, _) = listTwo(b)
        at("10:00")
        b.lessonWatched(first); b.answerLesson(first, listOf(2, 1, 1)); b.lessonNote(first, note)
        val snap = app.agency.core.brain.SnapshotReader(store, AgencyClock.of(clock))
        val before = FactsBuilder.facts(snap.facts(emptyList())).toString()
        assertTrue(before, before.contains("\"practical\":\"沒交\""))
        // A photo is not a recording: refused. The recording is kept in the vault's row only.
        val photo = Media("m-photo", MediaKind.PHOTO, MediaPurpose.LESSON, "image/jpeg", 10, "00", 1)
        assertTrue(b.handInPractical(first, photo).none { it.who == Who.COMPANY })
        val rec = Media("m-rec", MediaKind.AUDIO, MediaPurpose.LESSON, "audio/mp4", 10, "00", 1)
        assertEquals("實作收到了，記下：交了。", lines(b.handInPractical(first, rec)))
        val after = FactsBuilder.facts(snap.facts(emptyList())).toString()
        assertTrue(after.contains("\"practical\":\"交了\""))
        listOf("m-rec", "https://", "腹式呼吸發聲練習", "腹式呼吸是唱歌的基礎", note).forEach { assertFalse(it, after.contains(it)) }
        assertEquals(listOf("lesson", "practical"), store.nerve.effects(monday.toString()).filter { it.link == Links.L24Lessons.id }.map { it.because })
    }

    @Test fun theListRunningLowIsRemindedAtSignIn() = runBlocking {
        val b = brain(ScriptedLlm())
        listTwo(b)
        at("08:30", monday.plusDays(1))
        b.onText("簽到")
        assertTrue(store.nerve.effects(monday.plusDays(1).toString()).any { it.link == Links.L24Lessons.id && it.because == Links.L24Lessons.lowLine(2) })
    }
}
