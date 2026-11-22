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
import java.security.MessageDigest

/** The kinds the importer counts; the store must hold exactly the planned number of each after the import. */
enum class ImportKind { PROFILE, RULES, MEALS, MEAL_ITEMS, PENDING, WEIGHTS, TRAININGS, FOOD, AUDIT, BASELINES, MEDIA, MESSAGES }

/**
 * Which state file the records came from, in the coach's own order (state.json, then the temp file of a cut save).
 * [SALVAGED]: neither file parses, but the complete records before the cut of one of them were recovered
 * ([LegacyCoachJson.salvageState]; of two cut files, the one that still holds more records). [UNREADABLE]: a state file
 * exists and nothing in it could be read. Both make the import partial: what could be read is committed, the folder stays
 * where it is, and the import runs again only when the legacy files change or he asks for it (設定 › 舊資料 「再試一次」).
 */
enum class StateOrigin { STATE, TMP, NONE, SALVAGED, UNREADABLE }

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
    /** With [StateOrigin.SALVAGED]: the file the records were recovered from and how many complete records it still held. */
    val salvagedFrom: String? = null,
    val salvagedRecords: Int = 0,
    /** A `state.json.tmp` next to a readable `state.json`: a save that never landed. The coach's order reads state.json. */
    val tmpIgnored: Boolean = false,
    /**
     * Each `state.json.bad-*` file the coach set aside, by name: how many of its records were not in the state file and were
     * imported from it (0 when it held nothing new), or -1 when nothing in it could be read.
     */
    val setAsideFiles: Map<String, Int> = emptyMap(),
    /** The [setAsideFiles] that did not parse whole, so only their complete records before the cut were read. */
    val setAsideSalvaged: List<String> = emptyList(),
    /** Records the decoders left out because a required field was missing (no id, day, kg, name or kcal), per kind. */
    val droppedRecords: Map<String, Int> = emptyMap(),
    /**
     * Records whose id appeared twice in a legacy file, per kind. The first one is kept, except for food, where the last one
     * is kept (the coach replaced an entry by appending its new version).
     */
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
    /**
     * Files in `coach/baseline/` too big to move: larger than [LegacyInput.maxMediaBytes] ([LegacyMapper.MAX_IMPORT_MEDIA_BYTES],
     * less on a small heap), or the phone ran out of memory sealing one. Not imported (a step that names one reads as not handed in); the original stays in the legacy folder.
     */
    val mediaTooLarge: List<String> = emptyList(),
    /** Meals that carried an evidence text (no version ever wrote one; the agency has no field for it). */
    val mealsWithEvidence: Int = 0,
    val correctedFood: List<String> = emptyList(),
    val addedSeeds: List<String> = emptyList(),
    /**
     * Values he saved in the coach that were not applied because a profile or 公司規章 row already existed
     * (`profile.heightCm` → 「172.0」), so 設定 › 舊資料 can show them.
     */
    val notApplied: Map<String, String> = emptyMap(),
    /** Records an earlier, partial import already brought and this run left alone ([ImportPlan.except]). */
    val carriedBefore: Int = 0,
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
    /** The [profile] and [rules] fields whose value he saved in the coach (`profile.heightCm`, `rules.fatG`, …). */
    val fromPrefs: Set<String> = emptySet(),
) {
    /** False when the state file could not be read whole: the import is then partial after committing what it could read. */
    val stateRead: Boolean get() = StateOrigin.valueOf(note.stateOrigin) !in setOf(StateOrigin.SALVAGED, StateOrigin.UNREADABLE)

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

    /**
     * One short key per record of the plan: its kind and the first 16 hex digits of a hash of its id (a meal and item index for
     * a pending label, the name for a food). Nothing of the record itself. Kept after a partial import, so a later run can
     * leave those records alone ([except]).
     */
    fun recordKeys(): Set<String> = buildSet {
        meals.forEach { add(recordKey(ImportKind.MEALS, it.id)) }
        pending.forEach { add(recordKey(ImportKind.PENDING, pendingId(it))) }
        weights.forEach { add(recordKey(ImportKind.WEIGHTS, it.id)) }
        trainings.forEach { add(recordKey(ImportKind.TRAININGS, it.id)) }
        food.forEach { add(recordKey(ImportKind.FOOD, it.name)) }
        audits.forEach { add(recordKey(ImportKind.AUDIT, it.id)) }
        baseline?.let { add(recordKey(ImportKind.BASELINES, it.id)) }
        media.forEach { add(recordKey(ImportKind.MEDIA, it.id)) }
        messages.forEach { add(recordKey(ImportKind.MESSAGES, it.id)) }
    }

    /**
     * This plan without the records whose [recordKeys] are in [carried]: an earlier, partial import brought them, so he may
     * have deleted or edited them since, and a later run must neither bring them back nor count them. The profile and rules
     * are written only when missing anyway. The note says how many were left out ([ImportNote.carriedBefore]).
     */
    fun except(carried: Set<String>): ImportPlan {
        if (carried.isEmpty()) return this
        fun gone(kind: ImportKind, id: String) = recordKey(kind, id) in carried
        return copy(
            meals = meals.filterNot { gone(ImportKind.MEALS, it.id) },
            pending = pending.filterNot { gone(ImportKind.PENDING, pendingId(it)) },
            weights = weights.filterNot { gone(ImportKind.WEIGHTS, it.id) },
            trainings = trainings.filterNot { gone(ImportKind.TRAININGS, it.id) },
            food = food.filterNot { gone(ImportKind.FOOD, it.name) },
            audits = audits.filterNot { gone(ImportKind.AUDIT, it.id) },
            baseline = baseline?.takeUnless { gone(ImportKind.BASELINES, it.id) },
            media = media.filterNot { gone(ImportKind.MEDIA, it.id) },
            messages = messages.filterNot { gone(ImportKind.MESSAGES, it.id) },
            note = note.copy(carriedBefore = recordKeys().count { it in carried }),
        )
    }

    companion object {
        /** Counts as stored in `migration_log.counts`: kind names to numbers, nothing else. */
        fun countsJson(counts: Map<ImportKind, Int>): String =
            Json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), counts.mapKeys { it.key.name })

        /** See [recordKeys]. */
        fun recordKey(kind: ImportKind, id: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest("${kind.name}\n$id".toByteArray(Charsets.UTF_8))
            return kind.name + ":" + hash.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun pendingId(p: PendingLabel) = "${p.mealId}#${p.itemIdx}"
    }
}

/** The keys of prefs `agency_prefs` the importer writes, and of its own prefs file [IMPORT_NAME]. */
object AgencyPrefKeys {
    const val NAME = "agency_prefs"

    /**
     * The importer's own prefs file: [LEGACY_STAMP] and [LEGACY_CARRIED], only while the import is partial. Kept out of
     * `agency_prefs`, which is loaded at every start and rewritten by every setting change, because the carried keys can
     * come to a few hundred KB. Read only while [MIGRATED] is [MIGRATED_PARTIAL]; emptied when the import is done.
     */
    const val IMPORT_NAME = "agency_import"

    /** In `agency_prefs`: 0 (or absent): not imported yet; [MIGRATED_DONE]; [MIGRATED_PARTIAL]. */
    const val MIGRATED = "migrated"
    const val MIGRATED_DONE = 1

    /**
     * What could be read is in, the state file could not be read whole. The import runs again only when the legacy files no
     * longer match [LEGACY_STAMP], or when he asks (`LegacyImporter.retry`); records listed in [LEGACY_CARRIED] are never
     * brought again.
     */
    const val MIGRATED_PARTIAL = 2

    /** In [IMPORT_NAME]: a fingerprint of the legacy files a partial import read (a hash, nothing of their content). */
    const val LEGACY_STAMP = "legacy_stamp"

    /** In [IMPORT_NAME]: the [ImportPlan.recordKeys] partial imports committed, comma-separated. */
    const val LEGACY_CARRIED = "legacy_carried"
    const val PROVIDER = "provider"
    const val MODEL_DEEPSEEK = "model_deepseek"
    const val MODEL_CLAUDE = "model_claude"
    const val COMPAT_URL = "compat_url"
    const val COMPAT_MODEL = "compat_model"
    const val COMPAT_DAY = "compat_day"
    const val COMPAT_COUNT = "compat_count"
}
