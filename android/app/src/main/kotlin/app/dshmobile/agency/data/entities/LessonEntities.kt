package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.Quiz
import app.agency.core.model.QuizAnswer
import app.agency.core.model.QuizKind
import app.agency.core.model.QuizQuestion
import app.agency.core.model.QuizStatus
import app.agency.core.model.StudyLanguage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 理論課 videos (schema v4, T19c): title, link and 教材 copied by code from the stored search result [resultId]. */
@Entity(tableName = "lesson", indices = [Index("status")])
data class LessonEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "result_id") val resultId: String,
    val category: LessonCategory,
    val language: StudyLanguage?,
    val title: String,
    val url: String,
    val source: String,
    val status: LessonStatus,
    @ColumnInfo(name = "found_at") val foundAt: Long,
    @ColumnInfo(name = "listed_at") val listedAt: Long?,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    @ColumnInfo(name = "watched_at") val watchedAt: Long?,
    @ColumnInfo(name = "key_points") val keyPoints: String?,
    @ColumnInfo(name = "quiz_passed_at") val quizPassedAt: Long?,
    val note: String?,
    @ColumnInfo(name = "passed_at") val passedAt: Long?,
    @ColumnInfo(name = "passed_day") val passedDay: String?,
    val score: Int?,
    val retries: Int,
    val fallback: Boolean,
    @ColumnInfo(name = "practical_media_id") val practicalMediaId: String?,
    @ColumnInfo(name = "review_day") val reviewDay: String?,
    @ColumnInfo(name = "review_score") val reviewScore: Int?,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long?,
) {
    fun toModel() = Lesson(
        id, resultId, category, language, title, url, source, status, foundAt, listedAt, openedAt, watchedAt, keyPoints, quizPassedAt,
        note, passedAt, passedDay, score, retries, fallback, practicalMediaId, reviewDay, reviewScore, reviewedAt,
    )

    companion object {
        fun of(l: Lesson) = LessonEntity(
            l.id, l.resultId, l.category, l.language, l.title, l.url, l.source, l.status, l.foundAt, l.listedAt, l.openedAt, l.watchedAt,
            l.keyPoints, l.quizPassedAt, l.note, l.passedAt, l.passedDay, l.score, l.retries, l.fallback, l.practicalMediaId, l.reviewDay,
            l.reviewScore, l.reviewedAt,
        )
    }
}

/** A lesson's quiz (schema v4): its checked questions and his answers as JSON; the score is code's. */
@Entity(tableName = "quiz", indices = [Index("lesson_id")])
data class QuizEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lesson_id") val lessonId: String,
    val kind: QuizKind,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val questions: String,
    val shown: Int,
    val answers: String,
    val status: QuizStatus,
    val score: Int,
) {
    fun toModel() = Quiz(
        id, lessonId, kind, createdAt, JSON.decodeFromString(QUESTIONS, questions), shown, JSON.decodeFromString(ANSWERS, answers), status, score,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val QUESTIONS = ListSerializer(QuizQuestion.serializer())
        private val ANSWERS = ListSerializer(QuizAnswer.serializer())

        fun of(q: Quiz) = QuizEntity(
            q.id, q.lessonId, q.kind, q.createdAt, JSON.encodeToString(QUESTIONS, q.questions), q.shown, JSON.encodeToString(ANSWERS, q.answers), q.status, q.score,
        )
    }
}
