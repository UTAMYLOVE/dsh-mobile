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
import app.agency.core.model.RoleKey
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.model.WeightLog
import app.agency.core.model.Who
import app.agency.core.rules.FoodTable
import java.security.MessageDigest

/** A file in `coach/baseline/`: its name and modification time. */
data class LegacyFile(val name: String, val lastModified: Long)

/** What the importer read from the legacy folder and prefs, untouched. [state] is null when no state file could be read. */
data class LegacyInput(
    val state: LegacyState?,
    val origin: StateOrigin,
    val messages: List<LegacyMessage>,
    val unreadableMessageLines: Int = 0,
    val baselineFiles: List<LegacyFile> = emptyList(),
    val prefs: LegacyPrefs = LegacyPrefs(emptyMap()),
)

/**
 * Legacy coach records → agency records (arch 1.14 step 2). Pure: the same input and [now] give the same plan. Legacy ids
 * are kept verbatim, so inserting the plan twice writes nothing the second time. History is carried as it was recorded: a
 * meal keeps the breach labels it got under the old rules (`OVER700` becomes [MEAL_CAP_700_LEGACY]); nothing is recomputed.
 */
object LegacyMapper {
    const val MEAL_CAP_700_LEGACY = "MEAL_CAP_700_LEGACY"
    const val AUDIT_SEED_CORRECTED = "SEED_FOOD_CORRECTED"

    /** The seeded entry plan v3 corrects (the old seed said 629 kcal / 51 g). */
    const val GELI_CHICKEN = "給力盒子 南加州烤雞腿"
    private const val GELI_OLD_KCAL = 629
    private const val GELI_OLD_PROTEIN = 51.0
    private const val GELI_KCAL = 714
    private const val GELI_PROTEIN = 48.0

    val DEFAULT_GOALS = CompanyRules().goalsKg
    const val MIN_GOAL_KG = 52.0
    const val DEFAULT_COMPANY = "小雨娛樂"
    const val DEFAULT_JOIN_DATE = "2026-11-21"
    const val TRAINEE_NO = "001"
    private const val OLD_PLANNED_MEAL = GELI_CHICKEN
    private const val OLD_EVIDENCE_MIN = 30

    /** The two foods plan v3 adds to his table. */
    fun newSeeds(now: Long): List<FoodEntry> = listOf(
        FoodEntry("即食雞胸", listOf("即食雞胸肉"), 117, null, null, null, fried = false, estimated = true, seeded = true, addedAt = now),
        FoodEntry("統一高纖豆漿", listOf("高纖豆漿"), 193, 15.0, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
    )

    /** A vault id for the legacy file [name] (vault ids allow no dots; the same name always gives the same id). */
    fun mediaId(name: String): String = "legacy-" + sha256(name).take(24)

    fun map(input: LegacyInput, now: Long): ImportPlan {
        val state = input.state ?: LegacyState()
        val prefs = input.prefs
        val duplicates = linkedMapOf<String, Int>()
        fun <T> firstById(kind: ImportKind, list: List<T>, key: (T) -> Any): List<T> {
            val out = list.distinctBy(key)
            if (out.size < list.size) duplicates[kind.name] = list.size - out.size
            return out
        }

        val meals = firstById(ImportKind.MEALS, state.meals) { it.id }.map { m ->
            Meal(
                id = m.id, day = m.day, at = m.at, text = m.text, items = m.items, cheat = m.cheat,
                breaches = m.breaches.map { if (it == LegacyBreach.OVER700) MEAL_CAP_700_LEGACY else it },
                verified = m.verified, legacy = true,
            )
        }
        val pending = firstById(ImportKind.PENDING, state.pending) { it.mealId to it.itemIndex }
            .map { PendingLabel(it.mealId, it.itemIndex, it.name, it.countedKcal, it.askedAt) }
        val weights = firstById(ImportKind.WEIGHTS, state.weights) { it.id }.map { WeightLog(it.id, it.day, it.at, it.kg, it.waistCm) }
        val trainings = firstById(ImportKind.TRAININGS, state.trainings) { it.id }
            .map { TrainingLog(it.id, it.day, it.at, TrainingKind.STRENGTH, rounds = it.rounds, legacy = true) }

        // The coach seeded its table on the first load of a state that was not seeded yet; he saw that table.
        val table = if (state.seeded) state.food else FoodTable.seed(now) + state.food
        // The coach replaced an entry by appending its new version: for a repeated name the last one is current.
        val lastByName = table.associateBy { it.name }
        if (lastByName.size < table.size) duplicates[ImportKind.FOOD.name] = table.size - lastByName.size
        val corrected = mutableListOf<String>()
        val audits = mutableListOf<AuditLog>()
        val food = lastByName.values.map { f ->
            if (f.name == GELI_CHICKEN && f.seeded && f.kcal == GELI_OLD_KCAL && f.proteinG == GELI_OLD_PROTEIN) {
                corrected += f.name
                audits += AuditLog(
                    id = "legacy-food-" + sha256(f.name).take(16), at = now, what = AUDIT_SEED_CORRECTED,
                    detail = "${f.name}: $GELI_OLD_KCAL kcal ${GELI_OLD_PROTEIN.toInt()} g → $GELI_KCAL kcal ${GELI_PROTEIN.toInt()} g",
                )
                f.copy(kcal = GELI_KCAL, proteinG = GELI_PROTEIN, updatedAt = now)
            } else {
                f.copy(updatedAt = f.addedAt)
            }
        }.toMutableList()
        val added = newSeeds(now).filter { s -> food.none { it.name == s.name } && (listOf(s.name) + s.aliases).all { FoodTable.lookup(food, it) == null } }
        food += added

        val files = input.baselineFiles.sortedBy { it.name }
        val present = files.map { it.name }.toSet()
        val missing = mutableListOf<String>()
        fun linked(name: String?, step: LegacyBaselineStep): String? = when {
            name == null -> null
            name in present -> mediaId(name)
            else -> null.also { missing += step.name }
        }
        val baseline = state.baseline?.let { b ->
            Baseline(
                id = Baseline.JOIN, heightCm = b.heightCm, weightKg = b.weightKg,
                photoFront = linked(b.photo, LegacyBaselineStep.PHOTO), recording = linked(b.recording, LegacyBaselineStep.RECORDING),
                pushupsMax = b.pushups, plankMaxSec = b.plankSec,
                skipped = LegacyBaselineStep.entries.filter { it in b.skipped }.map { it.name }, step = b.step?.name,
            )
        }
        // Every file in the folder is carried, also older hand-ins the baseline no longer points at.
        val media = files.map { f ->
            val kind = kindOf(f.name)
            MediaCopy(mediaId(f.name), f.name, kind, MediaPurpose.BASELINE, mimeOf(f.name, kind), createdAtOf(f.name) ?: f.lastModified)
        }

        val messages = firstById(ImportKind.MESSAGES, input.messages) { it.id }.map { m ->
            val company = m.who == LegacySpeaker.COACH
            Message(
                id = m.id, at = m.at, who = if (company) Who.COMPANY else Who.OWNER,
                role = if (!company) null else when (m.role ?: LegacyRole.forKind(m.kind)) {
                    LegacyRole.AGENT -> RoleKey.MANAGER
                    LegacyRole.BODY -> RoleKey.BODY
                },
                kind = m.kind, text = m.text, legacy = true,
            )
        }

        val goals = goals(prefs)
        val note = ImportNote(
            stateOrigin = input.origin.name,
            unreadableMessageLines = input.unreadableMessageLines,
            duplicates = duplicates,
            customisedSlots = LegacyPrefs.SLOT_DEFAULTS.mapNotNull { (key, def) ->
                prefs.slotMinute(key)?.takeIf { it != def }?.let { key to "%d:%02d".format(it / 60, it % 60) }
            }.toMap(),
            goalsKept = goals.kept,
            goalsDropped = goals.dropped,
            ignoredQuotaKcal = prefs.quotaKcal?.takeIf { it != LegacyPrefs.OLD_DEFAULT_QUOTA },
            ignoredProteinG = prefs.proteinG?.takeIf { it != LegacyPrefs.OLD_DEFAULT_PROTEIN },
            ignoredCheatDay = prefs.cheatDay?.takeIf { it != LegacyPrefs.OLD_CHEAT_DAY },
            oldSettings = buildMap {
                prefs.bedtime?.let { put(LegacyPrefs.BEDTIME, it) }
                prefs.plannedMeal?.takeIf { it != OLD_PLANNED_MEAL }?.let { put(LegacyPrefs.PLANNED_MEAL, it) }
                prefs.evidenceMinutes?.takeIf { it != OLD_EVIDENCE_MIN }?.let { put(LegacyPrefs.EVIDENCE_MIN, it.toString()) }
            },
            missingMedia = missing,
            mealsWithEvidence = state.meals.count { it.evidence != null },
            correctedFood = corrected,
            addedSeeds = added.map { it.name },
        )

        val agencyBuild = state.agencyBuild || input.messages.any { it.role != null } ||
            listOf(LegacyPrefs.COMPANY, LegacyPrefs.JOIN_DATE, *LegacyPrefs.SLOT_DEFAULTS.keys.toTypedArray()).any(prefs::has)
        return ImportPlan(
            source = if (agencyBuild) MigrationSource.AGENCY_V2_JSON else MigrationSource.V083_JSON,
            profile = Profile(
                heightCm = prefs.heightCm, startWeightKg = prefs.startWeightKg,
                companyName = prefs.company ?: DEFAULT_COMPANY, traineeNo = TRAINEE_NO,
                joinDate = prefs.joinDate ?: DEFAULT_JOIN_DATE, dayStartHour = prefs.dayStartHour ?: 5,
            ),
            rules = CompanyRules(
                fatG = prefs.fatG ?: CompanyRules().fatG, carbsG = prefs.carbsG ?: CompanyRules().carbsG,
                marginPct = prefs.marginPct ?: CompanyRules().marginPct, goalsKg = goals.goals,
            ),
            meals = meals, pending = pending, weights = weights, trainings = trainings, food = food, audits = audits,
            baseline = baseline, media = media, messages = messages, prefs = agencyPrefs(prefs), note = note,
        )
    }

    private data class Goals(val goals: List<Double>, val kept: List<Double>?, val dropped: List<Double>)

    /** The old default 80/75/68 (or nothing saved) becomes plan v3's 75/68; his own list is kept without values under 52 kg. */
    private fun goals(prefs: LegacyPrefs): Goals {
        val saved = LegacyPrefs.parseGoals(prefs.goalsRaw)
        if (saved == null || saved == LegacyPrefs.OLD_DEFAULT_GOALS) return Goals(DEFAULT_GOALS, null, emptyList())
        val kept = saved.filter { it >= MIN_GOAL_KG }
        val dropped = saved.filter { it < MIN_GOAL_KG }
        return if (kept.isEmpty()) Goals(DEFAULT_GOALS, null, dropped) else Goals(kept, kept, dropped)
    }

    private fun agencyPrefs(p: LegacyPrefs): Map<String, PrefValue> = buildMap {
        fun str(from: String, to: String) = p.string(from)?.takeIf { it.isNotEmpty() }?.let { put(to, PrefValue.Str(it)) }
        str(LegacyPrefs.PROVIDER, AgencyPrefKeys.PROVIDER)
        str(LegacyPrefs.MODEL_DEEPSEEK, AgencyPrefKeys.MODEL_DEEPSEEK)
        str(LegacyPrefs.MODEL_CLAUDE, AgencyPrefKeys.MODEL_CLAUDE)
        str(LegacyPrefs.COMPAT_URL, AgencyPrefKeys.COMPAT_URL)
        str(LegacyPrefs.COMPAT_MODEL, AgencyPrefKeys.COMPAT_MODEL)
        str(LegacyPrefs.COMPAT_DAY, AgencyPrefKeys.COMPAT_DAY)
        p.int(LegacyPrefs.COMPAT_COUNT)?.let { put(AgencyPrefKeys.COMPAT_COUNT, PrefValue.Num(it)) }
    }

    private val AUDIO = setOf("m4a", "mp3", "wav", "aac", "ogg", "oga", "opus", "amr", "3gp", "3gpp", "flac", "weba", "mp4")
    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "m4a" to "audio/mp4", "mp4" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "amr" to "audio/amr", "3gp" to "audio/3gpp",
        "3gpp" to "audio/3gpp", "flac" to "audio/flac", "weba" to "audio/webm",
    )

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** The coach named hand-ins `photo-<millis>.<ext>` and `recording-<millis>.<ext>`. */
    private fun kindOf(name: String): MediaKind = when {
        name.startsWith("photo-") -> MediaKind.PHOTO
        name.startsWith("recording-") -> MediaKind.AUDIO
        ext(name) in AUDIO -> MediaKind.AUDIO
        else -> MediaKind.PHOTO
    }

    private fun mimeOf(name: String, kind: MediaKind): String = MIME[ext(name)]?.takeIf { it.startsWith(if (kind == MediaKind.AUDIO) "audio/" else "image/") }
        ?: if (kind == MediaKind.AUDIO && ext(name) == "mp4") "audio/mp4" else "application/octet-stream"

    private val STAMP = Regex("^(?:photo|recording)-([0-9]{10,15})(?:\\.|$)")
    private fun createdAtOf(name: String): Long? = STAMP.find(name)?.groupValues?.get(1)?.toLongOrNull()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
