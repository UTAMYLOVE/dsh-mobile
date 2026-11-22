package app.dshmobile.agency.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.agency.core.model.EffectStatus
import app.dshmobile.agency.data.entities.NerveEffectEntity
import app.dshmobile.agency.data.entities.SignalEntity

@Dao interface NerveDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSignal(row: SignalEntity): Long
    @Query("SELECT * FROM signal WHERE id = :id") suspend fun signal(id: String): SignalEntity?
    @Query("SELECT * FROM signal WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    suspend fun signals(fromDay: String, toDay: String): List<SignalEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEffect(row: NerveEffectEntity): Long
    @Query("SELECT * FROM nerve_effect WHERE id = :id") suspend fun effect(id: String): NerveEffectEntity?
    @Query("SELECT * FROM nerve_effect WHERE day = :day ORDER BY at, id") suspend fun effects(day: String): List<NerveEffectEntity>
    @Query("SELECT * FROM nerve_effect WHERE status = :status ORDER BY at, id") suspend fun effectsWith(status: EffectStatus): List<NerveEffectEntity>
    @Query("UPDATE nerve_effect SET status = :status, decided_at = :decidedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: EffectStatus, decidedAt: Long?): Int
}

/** The manager's stored thinking (schema v3, T12). */
@Dao interface ThinkingDao {
    @androidx.room.Upsert suspend fun put(row: app.dshmobile.agency.data.entities.ThinkingEntity)
    @Query("SELECT * FROM thinking WHERE message_id IN (:ids)") suspend fun of(ids: List<String>): List<app.dshmobile.agency.data.entities.ThinkingEntity>
    @Query("DELETE FROM thinking WHERE at < :before") suspend fun prune(before: Long): Int
    @Query("SELECT COUNT(*) FROM thinking") suspend fun count(): Int
}

/** 理論課 (schema v4, T19c). */
@Dao interface LessonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: app.dshmobile.agency.data.entities.LessonEntity): Long
    @androidx.room.Upsert suspend fun upsert(row: app.dshmobile.agency.data.entities.LessonEntity)
    @Query("SELECT * FROM lesson WHERE id = :id") suspend fun get(id: String): app.dshmobile.agency.data.entities.LessonEntity?
    @Query("SELECT * FROM lesson ORDER BY found_at, id") suspend fun all(): List<app.dshmobile.agency.data.entities.LessonEntity>
    @Query("SELECT * FROM lesson WHERE status = :status ORDER BY found_at, id")
    suspend fun withStatus(status: app.agency.core.model.LessonStatus): List<app.dshmobile.agency.data.entities.LessonEntity>
    @androidx.room.Upsert suspend fun putQuiz(row: app.dshmobile.agency.data.entities.QuizEntity)
    @Query("SELECT * FROM quiz WHERE id = :id") suspend fun quiz(id: String): app.dshmobile.agency.data.entities.QuizEntity?
    @Query("SELECT * FROM quiz WHERE lesson_id = :lessonId ORDER BY created_at, id")
    suspend fun quizzes(lessonId: String): List<app.dshmobile.agency.data.entities.QuizEntity>
}
