package app.agency.core.lessons

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Where a lesson is in its life. [WISH] is not a lesson yet: it is something he asked to learn, kept so the day can pick it
 * up (the one extra slot on request); it is removed when a video for it is proposed.
 */
enum class LessonState { PROPOSED, APPROVED, WATCHED, PASSED, NOTED, REVIEW_DUE, REVIEWED, DECLINED, WISH }

/** One multiple-choice question. [answer] is the 0-based index into [options]; [quote] is copied from the material. */
@Serializable
data class Question(val q: String, val options: List<String>, val answer: Int, val quote: String)

@Serializable
data class Quiz(val questions: List<Question>)

/**
 * The `Kind.LESSON` payload. Dates are ISO logical days; [openedAt] is an ISO instant. [notesOnly] marks the fallback when no
 * valid quiz could be made: he writes notes straight after watching. [extra] marks a lesson taken on request (a wish).
 */
@Serializable
data class Lesson(
    val subject: String,
    val title: String,
    val url: String,
    val minutes: Int? = null,
    val material: String = "",
    val state: LessonState = LessonState.PROPOSED,
    val level: String = SubjectLevels.NAMES.first(),
    val quiz: Quiz? = null,
    val answers: List<Int>? = null,
    /** Number right at the last graded attempt out of [total]. */
    val score: Int? = null,
    val total: Int = 0,
    val note: String = "",
    val attempts: Int = 0,
    val approvedOn: String? = null,
    val passedOn: String? = null,
    val notedOn: String? = null,
    val reviewOn: String? = null,
    val reviewedOn: String? = null,
    val reviewScore: Int? = null,
    val reviewQuiz: Quiz? = null,
    val openedAt: String? = null,
    val returned: Boolean = false,
    val notesOnly: Boolean = false,
    val extra: Boolean = false,
) {
    /** NOTED or later: the lesson itself is finished. */
    val finished: Boolean
        get() = state == LessonState.NOTED || state == LessonState.REVIEW_DUE || state == LessonState.REVIEWED

    /** Approved, being watched or waiting for its note. */
    val active: Boolean
        get() = state == LessonState.APPROVED || state == LessonState.WATCHED || state == LessonState.PASSED
}

/** Why an operation was refused; [text] is what he may be told. */
enum class Refusal(val text: String) {
    NOT_FOUND("找不到這堂課。"),
    WRONG_STATE("這堂課現在不能做這一步。"),
    BAD_HIT("影片資料不完整。"),
    DUPLICATE("這支影片已經在清單裡了。"),
    NOT_OPENED("還沒點開影片，不能說看完。"),
    BAD_QUIZ("這份題目不合格。"),
    NO_QUIZ("這堂課沒有題目。"),
    BAD_ANSWERS("答案的數量不對。"),
    NOTE_TOO_SHORT("筆記至少要十個字。"),
    NOT_DUE("還沒到複習的日子。"),
    EMPTY("內容是空的。"),
}

sealed interface LessonResult {
    data class Ok(val id: Long, val lesson: Lesson) : LessonResult
    data class Refused(val why: Refusal) : LessonResult
}

/** The grade of one attempt. [refusal] is set (and the rest is zero) when the answers could not be graded at all. */
data class QuizResult(val right: Int, val total: Int, val passed: Boolean, val refusal: Refusal? = null) {
    companion object {
        fun refused(why: Refusal) = QuizResult(0, 0, false, why)
    }
}

/** He opened [lessonId] in another app and has not come back; [minutes] is the video length when known. */
data class LessonAway(val lessonId: Long, val title: String, val openedAt: Instant, val minutes: Int? = null)
