package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.agency.core.model.Baseline
import app.agency.core.model.ConditionLog
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import app.agency.core.model.WeightLog

@Entity(tableName = "water_log", indices = [Index("day")])
data class WaterLogEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    @ColumnInfo(name = "half_bottles") val halfBottles: Int,
    @ColumnInfo(name = "photo_media_id") val photoMediaId: String?,
) {
    fun toModel() = WaterLog(id, day, at, halfBottles, photoMediaId)

    companion object {
        fun of(w: WaterLog) = WaterLogEntity(w.id, w.day, w.at, w.halfBottles, w.photoMediaId)
    }
}

@Entity(tableName = "training_log", indices = [Index("day")])
data class TrainingLogEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    val kind: TrainingKind,
    val rounds: Int?,
    val detail: String?,
    val reduced: Boolean,
    val verified: Boolean,
    val legacy: Boolean,
) {
    fun toModel() = TrainingLog(id, day, at, kind, rounds, detail, reduced, verified, legacy)

    companion object {
        fun of(t: TrainingLog) = TrainingLogEntity(t.id, t.day, t.at, t.kind, t.rounds, t.detailJson, t.reduced, t.verified, t.legacy)
    }
}

@Entity(tableName = "condition_log")
data class ConditionLogEntity(
    @PrimaryKey val day: String,
    val at: Long,
    val score: Int,
    val sleep: Int?,
    val energy: Int?,
    val ill: Boolean,
    val note: String?,
) {
    fun toModel() = ConditionLog(day, at, score, sleep, energy, ill, note)

    companion object {
        fun of(c: ConditionLog) = ConditionLogEntity(c.day, c.at, c.score, c.sleep, c.energy, c.ill, c.note)
    }
}

@Entity(tableName = "sign_log", indices = [Index("day")])
data class SignLogEntity(
    @PrimaryKey val id: String,
    val day: String,
    val kind: SignKind,
    val at: Long,
    @ColumnInfo(name = "on_time") val onTime: Boolean?,
    @ColumnInfo(name = "review_good") val reviewGood: String?,
    @ColumnInfo(name = "review_fix") val reviewFix: String?,
) {
    fun toModel() = SignLog(id, day, at, kind, onTime, reviewGood, reviewFix)

    companion object {
        fun of(s: SignLog) = SignLogEntity(s.id, s.day, s.kind, s.at, s.onTime, s.reviewGood, s.reviewFix)
    }
}

@Entity(tableName = "weight_log", indices = [Index("day")])
data class WeightLogEntity(
    @PrimaryKey val id: String,
    val day: String,
    val at: Long,
    val kg: Double,
    @ColumnInfo(name = "waist_cm") val waistCm: Double?,
) {
    fun toModel() = WeightLog(id, day, at, kg, waistCm)

    companion object {
        fun of(w: WeightLog) = WeightLogEntity(w.id, w.day, w.at, w.kg, w.waistCm)
    }
}

@Entity(tableName = "baseline")
data class BaselineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "height_cm") val heightCm: Double?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "photo_front") val photoFront: String?,
    @ColumnInfo(name = "photo_side") val photoSide: String?,
    @ColumnInfo(name = "photo_back") val photoBack: String?,
    val recording: String?,
    @ColumnInfo(name = "pushups_max") val pushupsMax: Int?,
    @ColumnInfo(name = "plank_max_sec") val plankMaxSec: Int?,
    val skipped: String,
    val step: String?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
) {
    fun toModel() = Baseline(
        id, heightCm, weightKg, photoFront, photoSide, photoBack, recording, pushupsMax, plankMaxSec, JsonCols.strings(skipped), step,
        completedAt,
    )

    companion object {
        fun of(b: Baseline) = BaselineEntity(
            b.id, b.heightCm, b.weightKg, b.photoFront, b.photoSide, b.photoBack, b.recording, b.pushupsMax, b.plankMaxSec,
            JsonCols.strings(b.skipped), b.step, b.completedAt,
        )
    }
}

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val id: String,
    val kind: MediaKind,
    val purpose: MediaPurpose,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    fun toModel() = Media(id, kind, purpose, mime, bytes, sha256, createdAt)

    companion object {
        fun of(m: Media) = MediaEntity(m.id, m.kind, m.purpose, m.mime, m.bytes, m.sha256, m.createdAt)
    }
}

/** 外送收入 of a coach day (v5): one row per day, a second report replaces it. */
@Entity(tableName = "delivery_income")
data class DeliveryIncomeEntity(
    @PrimaryKey val day: String,
    val amount: Int,
    val at: Long,
) {
    fun toModel() = app.agency.core.model.DeliveryIncome(day, amount, at)

    companion object {
        fun of(i: app.agency.core.model.DeliveryIncome) = DeliveryIncomeEntity(i.day, i.amount, i.at)
    }
}
