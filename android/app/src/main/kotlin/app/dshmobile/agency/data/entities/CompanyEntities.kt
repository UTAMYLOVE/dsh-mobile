package app.dshmobile.agency.data.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.agency.core.model.CompanyRules
import app.agency.core.model.DayOverride
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    @ColumnInfo(name = "height_cm") val heightCm: Double?,
    @ColumnInfo(name = "start_weight_kg") val startWeightKg: Double?,
    @ColumnInfo(name = "stage_name") val stageName: String?,
    @ColumnInfo(name = "member_color") val memberColor: String?,
    @ColumnInfo(name = "company_name") val companyName: String?,
    @ColumnInfo(name = "trainee_no") val traineeNo: String?,
    @ColumnInfo(name = "join_date") val joinDate: String?,
    val level: String,
    @ColumnInfo(name = "area_text") val areaText: String?,
    @ColumnInfo(name = "idol_names") val idolNames: String,
    @ColumnInfo(name = "day_start_hour") val dayStartHour: Int,
    @ColumnInfo(name = "onboarding_done") val onboardingDone: Boolean,
) {
    fun toModel() = Profile(
        heightCm, startWeightKg, stageName, memberColor, companyName, traineeNo, joinDate, level, areaText,
        JsonCols.strings(idolNames), dayStartHour, onboardingDone,
    )

    companion object {
        const val SINGLE_ROW = 1

        fun of(p: Profile) = ProfileEntity(
            SINGLE_ROW, p.heightCm, p.startWeightKg, p.stageName, p.memberColor, p.companyName, p.traineeNo, p.joinDate, p.level,
            p.areaText, JsonCols.strings(p.idolNames), p.dayStartHour, p.onboardingDone,
        )
    }
}

/** The columns of one version of 公司規章, shared by the current row and the history. */
data class RulesColumns(
    @ColumnInfo(name = "quota_kcal") val quotaKcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Int,
    @ColumnInfo(name = "fat_g") val fatG: Int,
    @ColumnInfo(name = "carbs_g") val carbsG: Int,
    @ColumnInfo(name = "floor_kcal") val floorKcal: Int,
    @ColumnInfo(name = "meal_cap_kcal") val mealCapKcal: Int,
    @ColumnInfo(name = "cheat_meals_per_week") val cheatMealsPerWeek: Int,
    @ColumnInfo(name = "zero_cal_per_week") val zeroCalPerWeek: Int,
    @ColumnInfo(name = "margin_pct") val marginPct: Int,
    val banned: String,
    @ColumnInfo(name = "goals_kg") val goalsKg: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toModel() = CompanyRules(
        quotaKcal, proteinG, fatG, carbsG, floorKcal, mealCapKcal, cheatMealsPerWeek, zeroCalPerWeek, marginPct,
        JsonCols.strings(banned), JsonCols.doubles(goalsKg), version, updatedAt,
    )

    companion object {
        fun of(r: CompanyRules) = RulesColumns(
            r.quotaKcal, r.proteinG, r.fatG, r.carbsG, r.floorKcal, r.mealCapKcal, r.cheatMealsPerWeek, r.zeroCalPerWeek,
            r.marginPct, JsonCols.strings(r.banned), JsonCols.doubles(r.goalsKg), r.version, r.updatedAt,
        )
    }
}

@Entity(tableName = "company_rules")
data class CompanyRulesEntity(@PrimaryKey val id: Int = ProfileEntity.SINGLE_ROW, @Embedded val rules: RulesColumns)

@Entity(tableName = "company_rules_history", primaryKeys = ["version"])
data class CompanyRulesHistoryEntity(@Embedded val rules: RulesColumns)

data class PlanSectionColumns(
    val section: PlanSectionKey,
    val json: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "changed_by") val changedBy: String,
) {
    fun toModel() = PlanSection(section, json, version, updatedAt, changedBy)

    companion object {
        fun of(s: PlanSection) = PlanSectionColumns(s.section, s.json, s.version, s.updatedAt, s.changedBy)
    }
}

@Entity(tableName = "plan_section", primaryKeys = ["section"])
data class PlanSectionEntity(@Embedded val s: PlanSectionColumns)

@Entity(tableName = "plan_section_history", primaryKeys = ["section", "version"])
data class PlanSectionHistoryEntity(@Embedded val s: PlanSectionColumns)

@Entity(tableName = "day_override")
data class DayOverrideEntity(
    @PrimaryKey val day: String,
    @ColumnInfo(name = "rest_day") val restDay: Boolean,
    @ColumnInfo(name = "cheat_slot") val cheatSlot: String?,
    @ColumnInfo(name = "soy_morning") val soyMorning: Boolean,
    @ColumnInfo(name = "declared_at") val declaredAt: Long,
) {
    fun toModel() = DayOverride(day, restDay, cheatSlot, soyMorning, declaredAt)

    companion object {
        fun of(d: DayOverride) = DayOverrideEntity(d.day, d.restDay, d.cheatSlot, d.soyMorning, d.declaredAt)
    }
}
