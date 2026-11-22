package app.agency.core.model

import kotlinx.serialization.Serializable

/** What a lesson's optional 實作 is (T19c): a recording, a selfie, a photo. Only 「交了／沒交」 ever reaches a prompt. */
enum class PracticalKind(val zh: String, val media: MediaKind) {
    RECORDING("錄一段聲音", MediaKind.AUDIO),
    SELFIE("前鏡頭自拍一張", MediaKind.PHOTO),
    PHOTO("拍一張照片", MediaKind.PHOTO),
}

/**
 * 理論課 categories (agency-spec 「理論課 categories」: everything K-pop trainees study, plus our extras for him). [zh] is what he
 * reads; [query] the words code searches with (never his words alone); [practical] the 實作 where the category allows one
 * (plan v3 §8: language → read a sentence aloud, expression and camera → a selfie, posture → a photo, vocal → a recording).
 */
enum class LessonCategory(val zh: String, val query: String, val practical: PracticalKind?) {
    VOCAL("聲樂", "發聲練習 唱歌技巧 教學", PracticalKind.RECORDING),
    DANCE("舞蹈", "韓國偶像 舞蹈 基本功 教學", null),
    RAP("饒舌", "饒舌 咬字 節奏 教學", PracticalKind.RECORDING),
    LANGUAGE("外語", "初學 會話 教學", PracticalKind.RECORDING),
    EXPRESSION("表情管理", "表情管理 鏡頭前 笑容 教學", PracticalKind.SELFIE),
    CAMERA("鏡頭感", "鏡頭感 拍照 姿勢 教學", PracticalKind.SELFIE),
    ACTING("演技", "演技 台詞 情緒 練習 教學", PracticalKind.RECORDING),
    INTERVIEW("受訪和綜藝", "受訪 綜藝 說話技巧 教學", null),
    ETIQUETTE("禮儀", "禮儀 待人 基本 教學", null),
    BODY("體態管理", "體態 肩頸 站姿 改善 教學", PracticalKind.PHOTO),
    STYLING("造型和化妝", "男生 化妝 造型 入門 教學", PracticalKind.PHOTO),
    SONGWRITING("作詞作曲和樂器", "作詞 作曲 入門 教學", null),
    SOCIAL("社交和聊天", "聊天 技巧 社交 教學", null),
    SKIN("皮膚保養", "男生 皮膚保養 步驟 教學", PracticalKind.PHOTO),
    OUTFIT("穿搭和髮型", "男生 穿搭 髮型 入門 教學", PracticalKind.PHOTO),
    POSTURE("站姿", "站姿 駝背 改善 教學", PracticalKind.PHOTO),
    MONEY("理財", "理財 入門 記帳 教學", null),
    MIND("認識自己", "心理學 認識自己 情緒 教學", null),
    COOKING("簡單健康料理", "簡單 健康 料理 高蛋白 教學", null),
    ;

    companion object {
        fun parse(name: String?): LessonCategory? = entries.firstOrNull { it.name == name }
    }
}

/** The foreign languages, learned one at a time in this order (agency-spec: English → Korean → Japanese). */
enum class StudyLanguage(val zh: String) { ENGLISH("英文"), KOREAN("韓文"), JAPANESE("日文") }

/** CANDIDATE: a search result waiting for his 好; LISTED: in 「課程清單」; DROPPED: he said 不要. */
enum class LessonStatus { CANDIDATE, LISTED, DROPPED }

/**
 * One 理論課 video (T19c). Everything about the video comes from a stored search result ([resultId]): [title], [url] and
 * [source] (the text the search returned: title, snippet, page text) are copied by code, never from model text. [keyPoints]
 * are his three points in his own words when [source] is too thin to quiz on (or the quiz could not be made); they become the
 * 教材 then. [note] is his note (≥ 30 Han characters) that unlocks the next one. [score] is the first round of the quiz
 * (right answers out of three), [retries] the fresh questions he needed after it; [fallback] says the quiz was replaced by
 * 「寫下三個重點」. [reviewDay] is the coach day of the 兩天後複習考, [reviewScore] its result (recorded only).
 */
data class Lesson(
    val id: String,
    val resultId: String,
    val category: LessonCategory,
    val language: StudyLanguage? = null,
    val title: String,
    val url: String,
    val source: String,
    val status: LessonStatus = LessonStatus.CANDIDATE,
    val foundAt: Long,
    val listedAt: Long? = null,
    val openedAt: Long? = null,
    val watchedAt: Long? = null,
    val keyPoints: String? = null,
    val quizPassedAt: Long? = null,
    val note: String? = null,
    val passedAt: Long? = null,
    val passedDay: String? = null,
    val score: Int? = null,
    val retries: Int = 0,
    val fallback: Boolean = false,
    val practicalMediaId: String? = null,
    val reviewDay: String? = null,
    val reviewScore: Int? = null,
    val reviewedAt: Long? = null,
) {
    val passed: Boolean get() = passedAt != null
}

/** MAIN: the quiz after watching (it unlocks); REVIEW: the 兩天後複習考 (recorded only). */
enum class QuizKind { MAIN, REVIEW }

/** OPEN: questions waiting; PASSED / FAILED: the main quiz's end; DONE: a review answered. */
enum class QuizStatus { OPEN, PASSED, FAILED, DONE }

/**
 * One multiple-choice question, made by the model strictly from the 教材 and checked by code: four options, [answer] the index
 * of the right one, [quote] a span copied word for word from the 教材 (code checks it is a verbatim substring).
 */
@Serializable
data class QuizQuestion(val q: String, val options: List<String>, val answer: Int, val quote: String)

/** His pick for question [question] (an index into the quiz's questions); [correct] is code's grading by index. */
@Serializable
data class QuizAnswer(val question: Int, val chosen: Int, val correct: Boolean)

/**
 * A quiz of a lesson. [questions] holds the three asked first and the spare ones a retry draws from (never shown before);
 * [shown] is how many have been put in front of him so far; [answers] what he picked. [score] counts his right answers.
 */
data class Quiz(
    val id: String,
    val lessonId: String,
    val kind: QuizKind,
    val createdAt: Long,
    val questions: List<QuizQuestion>,
    val shown: Int,
    val answers: List<QuizAnswer> = emptyList(),
    val status: QuizStatus = QuizStatus.OPEN,
    val score: Int = 0,
)
