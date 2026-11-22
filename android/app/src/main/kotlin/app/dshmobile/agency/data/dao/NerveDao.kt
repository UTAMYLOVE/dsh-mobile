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
