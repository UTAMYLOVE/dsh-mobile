package app.agency.core.migrate

import app.agency.core.model.AuditLog
import app.agency.core.model.Baseline
import app.agency.core.model.CompanyRules
import app.agency.core.model.FoodEntry
import app.agency.core.model.Meal
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Message
import app.agency.core.model.MigrationSource
import app.agency.core.model.PendingLabel
import app.agency.core.model.Profile
import app.agency.core.model.TrainingLog
import app.agency.core.model.WeightLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The kinds the importer counts; the store must hold exactly the planned number of each after the import. */
enum class ImportKind { PROFILE, RULES, MEALS, MEAL_ITEMS, PENDING, WEIGHTS, TRAININGS, FOOD, AUDIT, BASELINES, MEDIA, MESSAGES }

/** Which state file the records came from, in the coach's own order (state.json, then the temp file of a cut save). */
enum class StateOrigin { STATE, TMP, NONE, UNREADABLE }

/** A legacy file to seal into the media vault as [id]; [file] is its name in `coach/baseline/`. */
data class MediaCopy(
    val id: String,
    val file: String,
    val kind: MediaKind,
    val purpose: MediaPurpose,
    val mime: String,
    val createdAt: Long,
)

/** A value for prefs `agency_prefs` (provider choice, model ids, the compatible service). */
sealed interface PrefValue {
    data class Str(val value: String) : PrefValue
    data class Num(val value: Int) : PrefValue
}

/**
 * What the import note lists for him to review in 設定 › 舊資料: settings the agency no longer uses or replaced, values it
 * dropped by rule, records it could not carry. Counts and setting values only, never his messages or meals.
 */
@Serializable
data class ImportNote(
    val stateOrigin: String = StateOrigin.NONE.name,
    val unreadableMessageLines: Int = 0,
    /** Records whose id appeared twice in a legacy file (the first one is kept), per kind. */
    val duplicates: Map<String, Int> = emptyMap(),
    /** Slot times he changed in the 5a94d3c build (`t_lunch` → 「12:30」); plan v3's timetable replaces them. */
    val customisedSlots: Map<String, String> = emptyMap(),
    /** His own goal list, kept (only values of at least 52 kg). */
    val goalsKept: List<Double>? = null,
    /** Goals under 52 kg, dropped by rule. */
    val goalsDropped: List<Double> = emptyList(),
    /** v0.8.3 quota / protein / 休假餐 day he saved; 公司規章 sets them now. */
    val ignoredQuotaKcal: Int? = null,
    val ignoredProteinG: Int? = null,
    val ignoredCheatDay: Int? = null,
    /** Old settings with no place in the agency (bedtime, planned meal, evidence minutes). */
    val oldSettings: Map<String, String> = emptyMap(),
    /** 入社建檔 files the state names that are not in `coach/baseline/` (the step reads as not handed in). */
    val missingMedia: List<String> = emptyList(),
    /** Meals that carried an evidence text (no version ever wrote one; the agency has no field for it). */
    val mealsWithEvidence: Int = 0,
    val correctedFood: List<String> = emptyList(),
    val addedSeeds: List<String> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(ImportNote.serializer(), this)

    companion object {
        private val json = Json { encodeDefaults = false }
        fun fromJson(s: String): ImportNote = json.decodeFromString(ImportNote.serializer(), s)
    }
}

/**
 * Everything one import writes, built by [LegacyMapper] from the legacy files alone (pure: no I/O, the clock passed in).
 * [expected] is what the store must contain afterwards for each kind.
 */
data class ImportPlan(
    val source: MigrationSource,
    val profile: Profile,
    val rules: CompanyRules,
    val meals: List<Meal>,
    val pending: List<PendingLabel>,
    val weights: List<WeightLog>,
    val trainings: List<TrainingLog>,
    val food: List<FoodEntry>,
    val audits: List<AuditLog>,
    val baseline: Baseline?,
    val media: List<MediaCopy>,
    val messages: List<Message>,
    /** Values for `agency_prefs`, each written only when that key is not there yet. */
    val prefs: Map<String, PrefValue>,
    val note: ImportNote,
) {
    val expected: Map<ImportKind, Int>
        get() = linkedMapOf(
            ImportKind.PROFILE to 1,
            ImportKind.RULES to 1,
            ImportKind.MEALS to meals.size,
            ImportKind.MEAL_ITEMS to meals.sumOf { it.items.size },
            ImportKind.PENDING to pending.size,
            ImportKind.WEIGHTS to weights.size,
            ImportKind.TRAININGS to trainings.size,
            ImportKind.FOOD to food.size,
            ImportKind.AUDIT to audits.size,
            ImportKind.BASELINES to if (baseline == null) 0 else 1,
            ImportKind.MEDIA to media.size,
            ImportKind.MESSAGES to messages.size,
        )

    companion object {
        /** Counts as stored in `migration_log.counts`: kind names to numbers, nothing else. */
        fun countsJson(counts: Map<ImportKind, Int>): String =
            Json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), counts.mapKeys { it.key.name })
    }
}

/** The keys of prefs `agency_prefs` the importer writes. */
object AgencyPrefKeys {
    const val NAME = "agency_prefs"
    const val MIGRATED = "migrated"
    const val PROVIDER = "provider"
    const val MODEL_DEEPSEEK = "model_deepseek"
    const val MODEL_CLAUDE = "model_claude"
    const val COMPAT_URL = "compat_url"
    const val COMPAT_MODEL = "compat_model"
    const val COMPAT_DAY = "compat_day"
    const val COMPAT_COUNT = "compat_count"
}
