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
import java.util.Locale

/** A file in `coach/baseline/`: its name, modification time and size in bytes. */
data class LegacyFile(val name: String, val lastModified: Long, val size: Long = 0)

/**
 * What the importer read from the legacy folder and prefs, untouched. [state] is null when no state file could be read.
 * [setAside] holds each `state.json.bad-*` file by name, with its records (null when nothing in it could be read);
 * [setAsideSalvaged] names those of them that did not parse whole (only their complete records were read). [salvagedFrom] names the cut file a [StateOrigin.SALVAGED] state came from; [tmpIgnored] says a `state.json.tmp` was left
 * unread next to a readable `state.json`.
 */
data class LegacyInput(
    val state: LegacyState?,
    val origin: StateOrigin,
    val messages: List<LegacyMessage>,
    val unreadableMessageLines: Int = 0,
    val baselineFiles: List<LegacyFile> = emptyList(),
    val prefs: LegacyPrefs = LegacyPrefs(emptyMap()),
    val setAside: List<Pair<String, LegacyState?>> = emptyList(),
    val salvagedFrom: String? = null,
    val tmpIgnored: Boolean = false,
    val setAsideSalvaged: Set<String> = emptySet(),
    /** The largest baseline file to bring ([LegacyMapper.mediaCap] of this phone's heap); a larger one is noted, not read. */
    val maxMediaBytes: Long = LegacyMapper.MAX_IMPORT_MEDIA_BYTES,
    /**
     * Baseline files the vault could not take on this run (its Keystore key failed): the records come without them, the file
     * stays in the legacy folder, and a later start seals it ([ImportNote.mediaPending]).
     */
    val mediaPending: Set<String> = emptySet(),
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

    /**
     * The largest baseline file the importer brings into the vault. Sealing holds the plaintext and its sealed copy in memory
     * during the put, and the sealed copy and its decryption during the check (the cipher may buffer one more copy); the
     * recording picker accepted any audio, so a long voice memo could otherwise run the app out of memory. A larger file is
     * listed in [ImportNote.mediaTooLarge] and stays in the legacy folder.
     */
    const val MAX_IMPORT_MEDIA_BYTES: Long = 32L * 1024 * 1024

    /**
     * The cap for a heap of [maxHeapBytes] (`Runtime.maxMemory()`): [MAX_IMPORT_MEDIA_BYTES], or a sixth of the heap when that
     * is less. Sealing one file peaks at about three copies of it (with the cipher's own), and an out-of-memory error on
     * another thread (the UI drawing 「正在搬資料」) is not caught, so it would crash every start. Heaps of 192 MiB and more keep
     * the full 32 MiB, so no file that fits there is left out.
     */
    fun mediaCap(maxHeapBytes: Long): Long = minOf(MAX_IMPORT_MEDIA_BYTES, maxHeapBytes / 6)

    /** The two foods plan v3 adds to his table. */
    fun newSeeds(now: Long): List<FoodEntry> = listOf(
        FoodEntry("即食雞胸", listOf("即食雞胸肉"), 117, null, null, null, fried = false, estimated = true, seeded = true, addedAt = now),
        FoodEntry("統一高纖豆漿", listOf("高纖豆漿"), 193, 15.0, null, null, fried = false, estimated = false, seeded = true, addedAt = now),
    )

    /** A vault id for the legacy file [name] (vault ids allow no dots; the same name always gives the same id). */
    fun mediaId(name: String): String = "legacy-" + sha256(name).take(24)

    /** How the baseline file [name] goes into the vault: its id, kind, purpose, type and the time it was handed in. */
    fun mediaCopy(name: String, lastModified: Long): MediaCopy {
        val kind = kindOf(name)
        return MediaCopy(mediaId(name), name, kind, MediaPurpose.BASELINE, mimeOf(name, kind), createdAtOf(name) ?: lastModified)
    }

    fun map(input: LegacyInput, now: Long): ImportPlan {
        val main = input.state ?: LegacyState()
        // His food table is known only when a state file was read whole (or there is none, and the coach would have seeded
        // one): a cut or unreadable table gets no seeds, which could bring back one he deleted or shadow one of his own.
        val tableKnown = input.origin == StateOrigin.STATE || input.origin == StateOrigin.TMP || input.origin == StateOrigin.NONE
        // The coach seeded its table on the first load of a state that was not seeded yet; he saw that table.
        val baseTable = if (main.seeded || !tableKnown) main.food else FoodTable.seed(now) + main.food
        val (state, setAsideNote) = withSetAside(main.copy(food = baseTable), input.setAside)
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

        val table = state.food
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
        val added = if (!tableKnown) emptyList() else newSeeds(now).filter { s -> food.none { it.name == s.name } && (listOf(s.name) + s.aliases).all { FoodTable.lookup(food, it) == null } }
        food += added

        val (tooLarge, fitting) = input.baselineFiles.sortedBy { it.name }.partition { it.size > input.maxMediaBytes }
        val (waiting, files) = fitting.partition { it.name in input.mediaPending }
        val present = files.map { it.name }.toSet()
        val skippedLarge = tooLarge.map { it.name }.toSet()
        val pendingNames = waiting.map { it.name }.toSet()
        val missing = mutableListOf<String>()
        val pendingLinks = linkedMapOf<String, String>()
        fun linked(name: String?, step: LegacyBaselineStep): String? = when {
            name == null -> null
            name in present -> mediaId(name)
            name in skippedLarge -> null // listed in mediaTooLarge
            name in pendingNames -> null.also { pendingLinks[name] = step.name } // linked when a later start seals it
            else -> null.also { missing += step.name }
        }
        val baseline = state.baseline?.let { b ->
            val out = Baseline(
                id = Baseline.JOIN, heightCm = b.heightCm, weightKg = b.weightKg,
                photoFront = linked(b.photo, LegacyBaselineStep.PHOTO), recording = linked(b.recording, LegacyBaselineStep.RECORDING),
                pushupsMax = b.pushups, plankMaxSec = b.plankSec,
                skipped = LegacyBaselineStep.entries.filter { it in b.skipped }.map { it.name }, step = b.step?.name,
            )
            // No question open and every step handed in or skipped: 入社建檔 was finished in the 5a94d3c build.
            if (b.step == null) completedIfDone(out, now) else out
        }
        // Every file in the folder is carried, also older hand-ins the baseline no longer points at.
        val media = files.map { f -> mediaCopy(f.name, f.lastModified) }

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
            salvagedFrom = input.salvagedFrom.takeIf { input.origin == StateOrigin.SALVAGED },
            salvagedRecords = if (input.origin == StateOrigin.SALVAGED) main.records else 0,
            tmpIgnored = input.tmpIgnored,
            setAsideFiles = setAsideNote,
            setAsideSalvaged = input.setAside.map { it.first }.filter { it in input.setAsideSalvaged }.sorted(),
            droppedRecords = (listOf(main) + input.setAside.mapNotNull { it.second }).fold(mapOf<String, Int>()) { acc, s ->
                (acc.keys + s.dropped.keys).associateWith { (acc[it] ?: 0) + (s.dropped[it] ?: 0) }
            },
            duplicates = duplicates,
            customisedSlots = LegacyPrefs.SLOT_DEFAULTS.mapNotNull { (key, def) ->
                prefs.slotMinute(key)?.takeIf { it != def }?.let { key to String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
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
            mediaTooLarge = tooLarge.map { it.name },
            mediaPending = waiting.map { it.name },
            mediaPendingLinks = pendingLinks,
            mealsWithEvidence = state.meals.count { it.evidence != null },
            correctedFood = corrected,
            addedSeeds = added.map { it.name },
        )

        val agencyBuild = state.agencyBuild || input.setAside.any { it.second?.agencyBuild == true } || input.messages.any { it.role != null } ||
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
            fromPrefs = buildSet {
                if (prefs.heightCm != null) add(PROFILE_HEIGHT)
                if (prefs.startWeightKg != null) add(PROFILE_START_WEIGHT)
                if (prefs.company != null) add(PROFILE_COMPANY)
                if (prefs.joinDate != null) add(PROFILE_JOIN_DATE)
                if (prefs.dayStartHour != null) add(PROFILE_DAY_START)
                if (prefs.fatG != null) add(RULES_FAT)
                if (prefs.carbsG != null) add(RULES_CARBS)
                if (prefs.marginPct != null) add(RULES_MARGIN)
                if (goals.kept != null) add(RULES_GOALS)
            },
        )
    }

    /**
     * [b] stamped [now] as finished when every step is handed in or skipped (no question was open in the 5a94d3c build); also
     * used when a pending file is sealed later and completes it.
     */
    fun completedIfDone(b: Baseline, now: Long): Baseline =
        if (b.completedAt == null && LegacyBaselineStep.entries.all { it.name in b.skipped || handedIn(b, it) }) b.copy(completedAt = now) else b

    private fun handedIn(b: Baseline, step: LegacyBaselineStep): Boolean = when (step) {
        LegacyBaselineStep.BODY -> b.heightCm != null && b.weightKg != null
        LegacyBaselineStep.PHOTO -> b.photoFront != null
        LegacyBaselineStep.RECORDING -> b.recording != null
        LegacyBaselineStep.PUSHUPS -> b.pushupsMax != null
        LegacyBaselineStep.PLANK -> b.plankMaxSec != null
    }

    /**
     * [main] (its food already the table he saw) with the records of each set-aside file it does not hold: meals, weights,
     * trainings and pending labels by id (or meal and item), food by normalised name, and the baseline when [main] has none.
     * One exception for food: his own entry (not a seed) replaces a name the main table holds only as an untouched seed, since
     * a state the coach seeded afresh after setting his file aside holds nothing of his. Files are taken newest first (the
     * name ends in the time the coach set it aside), so a newer file wins over an older one, and [main] wins over all of them.
     * Ids are random UUIDs, so this cannot join two different records. Also returns, per file in name order, how many records
     * it gave (-1 when unreadable).
     */
    private fun withSetAside(main: LegacyState, setAside: List<Pair<String, LegacyState?>>): Pair<LegacyState, Map<String, Int>> {
        if (setAside.isEmpty()) return main to emptyMap()
        val meals = main.meals.toMutableList()
        val weights = main.weights.toMutableList()
        val trainings = main.trainings.toMutableList()
        val pending = main.pending.toMutableList()
        val food = main.food.toMutableList()
        var baseline = main.baseline
        val mealIds = meals.mapTo(HashSet()) { it.id }
        val weightIds = weights.mapTo(HashSet()) { it.id }
        val trainingIds = trainings.mapTo(HashSet()) { it.id }
        val pendingKeys = pending.mapTo(HashSet()) { it.mealId to it.itemIndex }
        fun key(f: FoodEntry) = FoodTable.normalize(f.name)
        val note = linkedMapOf<String, Int>()
        for ((name, s) in setAside.sortedByDescending { it.first }) {
            if (s == null) {
                note[name] = -1
                continue
            }
            var n = 0
            s.meals.forEach { if (mealIds.add(it.id)) { meals += it; n++ } }
            s.weights.forEach { if (weightIds.add(it.id)) { weights += it; n++ } }
            s.trainings.forEach { if (trainingIds.add(it.id)) { trainings += it; n++ } }
            s.pending.forEach { if (pendingKeys.add(it.mealId to it.itemIndex)) { pending += it; n++ } }
            // Within one file the last entry of a name is current, as in the main table.
            s.food.associateBy { it.name }.values.forEach { e ->
                val same = food.filter { key(it) == key(e) }
                if (same.isEmpty() || (!e.seeded && same.all { it.seeded })) {
                    food.removeAll { key(it) == key(e) }
                    food += e
                    n++
                }
            }
            if (baseline == null && s.baseline != null) { baseline = s.baseline; n++ }
            note[name] = n
        }
        return main.copy(meals = meals, weights = weights, trainings = trainings, pending = pending, food = food, baseline = baseline) to note.toSortedMap()
    }

    const val PROFILE_HEIGHT = "profile.heightCm"
    const val PROFILE_START_WEIGHT = "profile.startWeightKg"
    const val PROFILE_COMPANY = "profile.companyName"
    const val PROFILE_JOIN_DATE = "profile.joinDate"
    const val PROFILE_DAY_START = "profile.dayStartHour"
    const val RULES_FAT = "rules.fatG"
    const val RULES_CARBS = "rules.carbsG"
    const val RULES_MARGIN = "rules.marginPct"
    const val RULES_GOALS = "rules.goalsKg"

    /**
     * The values he saved in the coach ([ImportPlan.fromPrefs]) that differ from a profile or 公司規章 row that already
     * existed, so the import kept that row: field → his legacy value, for [ImportNote.notApplied]. Pass null for a row the
     * import wrote itself.
     */
    fun notApplied(plan: ImportPlan, existingProfile: Profile?, existingRules: CompanyRules?): Map<String, String> = buildMap {
        fun check(key: String, legacy: Any?, stored: Any?) {
            if (key in plan.fromPrefs && legacy != stored) put(key, legacy.toString())
        }
        existingProfile?.let { p ->
            check(PROFILE_HEIGHT, plan.profile.heightCm, p.heightCm)
            check(PROFILE_START_WEIGHT, plan.profile.startWeightKg, p.startWeightKg)
            check(PROFILE_COMPANY, plan.profile.companyName, p.companyName)
            check(PROFILE_JOIN_DATE, plan.profile.joinDate, p.joinDate)
            check(PROFILE_DAY_START, plan.profile.dayStartHour, p.dayStartHour)
        }
        existingRules?.let { r ->
            check(RULES_FAT, plan.rules.fatG, r.fatG)
            check(RULES_CARBS, plan.rules.carbsG, r.carbsG)
            check(RULES_MARGIN, plan.rules.marginPct, r.marginPct)
            check(RULES_GOALS, plan.rules.goalsKg, r.goalsKg)
        }
    }

    private data class Goals(val goals: List<Double>, val kept: List<Double>?, val dropped: List<Double>)

    /** The old default (or nothing saved) becomes none (he sets his own in 設定); his own list is kept without values under 52 kg. */
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
