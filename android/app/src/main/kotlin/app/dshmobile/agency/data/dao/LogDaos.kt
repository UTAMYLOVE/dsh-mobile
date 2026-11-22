package app.dshmobile.agency.data.dao

import androidx.room.Dao
import androidx.room.Query
import app.dshmobile.agency.data.entities.SignLogEntity
import app.dshmobile.agency.data.entities.TrainingLogEntity
import app.dshmobile.agency.data.entities.WaterLogEntity
import app.dshmobile.agency.data.entities.WeightLogEntity

@Dao interface WaterDao : DayLogDao<WaterLogEntity> {
    @Query("SELECT * FROM water_log WHERE id = :id") override suspend fun get(id: String): WaterLogEntity?
    @Query("SELECT * FROM water_log WHERE day = :day ORDER BY at, id") override suspend fun onDay(day: String): List<WaterLogEntity>
    @Query("SELECT * FROM water_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    override suspend fun between(fromDay: String, toDay: String): List<WaterLogEntity>
    @Query("DELETE FROM water_log WHERE id = :id") override suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM water_log") override suspend fun count(): Int
}

@Dao interface TrainingDao : DayLogDao<TrainingLogEntity> {
    @Query("SELECT * FROM training_log WHERE id = :id") override suspend fun get(id: String): TrainingLogEntity?
    @Query("SELECT * FROM training_log WHERE day = :day ORDER BY at, id") override suspend fun onDay(day: String): List<TrainingLogEntity>
    @Query("SELECT * FROM training_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    override suspend fun between(fromDay: String, toDay: String): List<TrainingLogEntity>
    @Query("DELETE FROM training_log WHERE id = :id") override suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM training_log") override suspend fun count(): Int
}

@Dao interface SignDao : DayLogDao<SignLogEntity> {
    @Query("SELECT * FROM sign_log WHERE id = :id") override suspend fun get(id: String): SignLogEntity?
    @Query("SELECT * FROM sign_log WHERE day = :day ORDER BY at, id") override suspend fun onDay(day: String): List<SignLogEntity>
    @Query("SELECT * FROM sign_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    override suspend fun between(fromDay: String, toDay: String): List<SignLogEntity>
    @Query("DELETE FROM sign_log WHERE id = :id") override suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM sign_log") override suspend fun count(): Int
}

@Dao interface WeightDao : DayLogDao<WeightLogEntity> {
    @Query("SELECT * FROM weight_log WHERE id = :id") override suspend fun get(id: String): WeightLogEntity?
    @Query("SELECT * FROM weight_log WHERE day = :day ORDER BY at, id") override suspend fun onDay(day: String): List<WeightLogEntity>
    @Query("SELECT * FROM weight_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY at, id")
    override suspend fun between(fromDay: String, toDay: String): List<WeightLogEntity>
    @Query("DELETE FROM weight_log WHERE id = :id") override suspend fun delete(id: String): Int
    @Query("SELECT COUNT(*) FROM weight_log") override suspend fun count(): Int
}
