package app.dshmobile.agency.migrate

import android.content.SharedPreferences
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.ImportKind
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.ImportPlan
import app.agency.core.migrate.LegacyCoachJson
import app.agency.core.migrate.LegacyFile
import app.agency.core.migrate.LegacyInput
import app.agency.core.migrate.LegacyMapper
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.migrate.LegacyState
import app.agency.core.migrate.PrefValue
import app.agency.core.migrate.StateOrigin
import app.agency.core.model.CompanyRules
import app.agency.core.model.Media
import app.agency.core.model.MigrationLog
import app.agency.core.model.Profile
import app.agency.core.store.AgencyStore
import app.dshmobile.agency.media.MediaVault
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The result of one [LegacyImporter.run]. [counts] are the planned records per kind (what the store holds afterwards when
 * [ok]); [inserted] what this run wrote (all zero when the records were already there). [skipped] when an earlier run had
 * finished. [note] is the import note for 設定 › 舊資料.
 */
data class ImportReport(
    val counts: Map<ImportKind, Int>,
    val ok: Boolean,
    val inserted: Map<ImportKind, Int> = emptyMap(),
    val skipped: Boolean = false,
    val note: ImportNote? = null,
    /** Where `filesDir/coach` went after a successful import (null when there was no folder or the rename failed). */
    val keptAt: File? = null,
    /** The import is done but `filesDir/coach` could not be renamed; the next start tries again. */
    val renameFailed: Boolean = false,
)

/** The store did not hold what the plan wrote (only kind names and numbers, never content). */
class ImportMismatchException(val expected: Map<ImportKind, Int>, val found: Map<ImportKind, Int>) :
    IllegalStateException("import counts differ: " + ImportKind.entries.filter { expected[it] != found[it] }.joinToString { "${it.name} ${found[it]}/${expected[it]}" })

/**
 * Imports the coach's records (v0.8.3 or the 5a94d3c build) into the agency store, arch 1.14 steps 1-5.
 *
 * 1. Reads `filesDir/coach` (state.json, else the temp file of a cut save, else the complete records of a cut file; every
 *    `state.json.bad-*` file the coach set aside; every line of messages.jsonl; every file in `baseline/` up to
 *    [LegacyMapper.MAX_IMPORT_MEDIA_BYTES]) and prefs `dshm_coach`, read-only. Nothing legacy is written or deleted while
 *    importing.
 * 2. [LegacyMapper] builds the plan.
 * 3. Seals the baseline files into the [vault], then inserts everything in one store transaction, INSERT OR IGNORE on the
 *    legacy ids; the profile, rules and audit rows only when they are not there yet. A second run writes nothing.
 * 4. Inside the transaction, checks every planned record is in the store as planned (and every media file decrypts to its
 *    bytes); any difference rolls everything back, removes the media no committed row points at, logs the counts, and leaves
 *    the legacy files as they were, for the next start to try again. On success it logs the import and sets
 *    `agency_prefs.migrated = 1`. When no state file could be read whole, what could be read is committed, but the run logs a
 *    failure, sets no flag and renames nothing, so the next start tries again.
 * 5. Then renames `filesDir/coach` to `filesDir/coach.migrated-<ts>` (kept until he deletes it in 設定 › 舊資料); a rename
 *    that failed is tried again at the next start.
 *
 * One run at a time in the process: a second call waits and then finds the import finished.
 */
class LegacyImporter(
    private val filesDir: File,
    private val legacyPrefs: SharedPreferences,
    private val agencyPrefs: SharedPreferences,
    private val store: AgencyStore,
    private val vault: MediaVault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dir get() = File(filesDir, LEGACY_DIR)

    suspend fun run(): ImportReport = withContext(Dispatchers.IO) { LOCK.withLock { runLocked() } }

    private suspend fun runLocked(): ImportReport {
        if (agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == 1) {
            // Done before; a rename that failed then is tried again.
            if (!dir.isDirectory) return ImportReport(emptyMap(), ok = true, skipped = true)
            val kept = keepAside(clock())
            return ImportReport(emptyMap(), ok = true, skipped = true, keptAt = kept, renameFailed = kept == null)
        }
        val prefValues = legacyPrefs.all.toMap()
        if (!dir.isDirectory) {
            // The folder was already moved aside by a finished import (its flag is gone): nothing to plan, no seeds to add.
            // Or a fresh install: nothing to bring over.
            val moved = filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("$LEGACY_DIR.migrated-") }?.isNotEmpty() == true
            if (moved || prefValues.isEmpty()) {
                agencyPrefs.edit().putInt(AgencyPrefKeys.MIGRATED, 1).commit()
                return ImportReport(emptyMap(), ok = true, skipped = moved)
            }
        }
        val now = clock()
        val plan = try {
            LegacyMapper.map(read(prefValues), now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A legacy file could not be read: nothing was written, the next start tries again.
            return ImportReport(emptyMap(), ok = false)
        }
        val sealedNow = mutableListOf<String>()
        var committed = false
        try {
            val media = seal(plan, sealedNow)
            val (inserted, note) = store.transaction {
                val w = insert(plan, media, now)
                val found = found(plan, media, w)
                if (found != plan.expected) throw ImportMismatchException(plan.expected, found)
                val note = plan.note.copy(notApplied = LegacyMapper.notApplied(plan, w.existingProfile, w.existingRules))
                if (plan.stateRead) {
                    val logId = "import-" + plan.source.name
                    if (store.journal.migrations().none { it.id == logId }) {
                        store.journal.logMigration(MigrationLog(logId, now, plan.source, ImportPlan.countsJson(plan.expected), ok = true, note = note.toJson()))
                    }
                } else {
                    // One row per source, brought up to date at every start that still cannot read the state.
                    val why = "STATE UNREADABLE" + if (note.salvagedRecords > 0) ", SALVAGED ${note.salvagedRecords}" else ""
                    store.journal.logMigration(MigrationLog("import-${plan.source.name}-state", now, plan.source, ImportPlan.countsJson(plan.expected), ok = false, note = why))
                }
                w.counts(plan) to note
            }
            committed = true
            val edit = agencyPrefs.edit()
            plan.prefs.forEach { (k, v) ->
                if (!agencyPrefs.contains(k)) when (v) {
                    is PrefValue.Str -> edit.putString(k, v.value)
                    is PrefValue.Num -> edit.putInt(k, v.value)
                }
            }
            if (!plan.stateRead) {
                // What could be read is in; the state is not. No flag, no rename: the folder stays for the next start.
                edit.commit()
                return ImportReport(plan.expected, ok = false, inserted = inserted, note = note)
            }
            check(edit.putInt(AgencyPrefKeys.MIGRATED, 1).commit()) { "agency prefs not saved" }
            val kept = keepAside(now)
            return ImportReport(plan.expected, ok = true, inserted = inserted, note = note, keptAt = kept, renameFailed = kept == null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { unseal(plan) }
            throw e
        } catch (e: Exception) {
            unseal(plan)
            // After the commit the records and their ok row are in: only the flag is missing, the next start sets it.
            if (!committed) {
                val why = (e as? ImportMismatchException)?.message ?: e.javaClass.simpleName
                try {
                    store.journal.logMigration(MigrationLog(UUID.randomUUID().toString(), now, plan.source, ImportPlan.countsJson(plan.expected), ok = false, note = why))
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // The log is best effort; the report says the import failed.
                }
            }
            return ImportReport(plan.expected, ok = false, note = plan.note)
        }
    }

    /** Step 5: `coach` → `coach.migrated-<at>`; null when the rename failed (or there is no folder). */
    private fun keepAside(at: Long): File? = File(filesDir, "$LEGACY_DIR.migrated-$at").takeIf { dir.isDirectory && dir.renameTo(it) }

    /**
     * Step 1, in the coach's own order: state.json, else state.json.tmp, else the complete records of whichever of them was
     * cut; every set-aside state file; every message line; the baseline folder.
     */
    private fun read(prefValues: Map<String, Any?>): LegacyInput {
        val state = readState()
        val messagesFile = File(dir, "messages.jsonl")
        val (messages, bad) = if (messagesFile.isFile) LegacyCoachJson.parseMessages(messagesFile.readText()) else emptyList<Nothing>() to 0
        val files = File(dir, BASELINE_DIR).listFiles()?.filter { it.isFile }?.map { LegacyFile(it.name, it.lastModified(), it.length()) }.orEmpty()
        // The coach moved an unreadable state.json aside as state.json.bad-<ms> (or copied it as …-copy) before seeding a new one.
        val setAside = dir.listFiles { f -> f.isFile && f.name.startsWith(SET_ASIDE_PREFIX) }.orEmpty().sortedBy { it.name }.map { f ->
            val text = f.readText()
            f.name to (LegacyCoachJson.parseState(text) ?: LegacyCoachJson.salvageState(text))
        }
        return LegacyInput(
            state.state, state.origin, messages, bad, files, LegacyPrefs(prefValues),
            setAside = setAside, salvagedFrom = state.salvagedFrom, tmpIgnored = state.tmpIgnored,
        )
    }

    private data class StateRead(val state: LegacyState?, val origin: StateOrigin, val salvagedFrom: String? = null, val tmpIgnored: Boolean = false)

    private fun readState(): StateRead {
        val main = File(dir, STATE)
        val tmp = File(dir, "$STATE.tmp")
        // A read error is thrown, not taken for a damaged file: the run then writes nothing and the next start tries again.
        val mainText = if (main.isFile) main.readText() else null
        val tmpText = if (tmp.isFile) tmp.readText() else null
        mainText?.let(LegacyCoachJson::parseState)?.let { return StateRead(it, StateOrigin.STATE, tmpIgnored = tmpText != null) }
        tmpText?.let(LegacyCoachJson::parseState)?.let { return StateRead(it, StateOrigin.TMP) }
        mainText?.let(LegacyCoachJson::salvageState)?.let { return StateRead(it, StateOrigin.SALVAGED, salvagedFrom = main.name) }
        tmpText?.let(LegacyCoachJson::salvageState)?.let { return StateRead(it, StateOrigin.SALVAGED, salvagedFrom = tmp.name) }
        return StateRead(null, if (mainText != null || tmpText != null) StateOrigin.UNREADABLE else StateOrigin.NONE)
    }

    /**
     * Seals each baseline file (a file already sealed with the same bytes is kept) and checks it decrypts to those bytes.
     * The plaintext is hashed as a stream; its bytes are held only while [MediaVault.put] seals them, and the check reads
     * the sealed file back once afterwards, so at most the plaintext and one encrypted or decrypted copy live at a time.
     */
    private fun seal(plan: ImportPlan, sealedNow: MutableList<String>): List<Media> = plan.media.map { c ->
        try {
            val file = File(File(dir, BASELINE_DIR), c.file)
            val (sha, size) = digest(file)
            val already = vault.has(c.id) && try {
                sha256(vault.get(c.id)) == sha
            } catch (_: Exception) {
                false
            }
            if (!already) {
                val sealed = vault.put(c.id, file.readBytes())
                sealedNow += c.id
                check(sealed.sha256 == sha && sealed.bytes == size) { "media changed while importing" }
                check(sha256(vault.get(c.id)) == sha) { "sealed media differs" }
            }
            Media(c.id, c.kind, c.purpose, c.mime, size, sha, c.createdAt)
        } catch (e: OutOfMemoryError) {
            // An ordinary failure: rolled back, logged, tried again at the next start, instead of a crash at every start.
            throw IOException("media too large", e)
        }
    }

    /**
     * After a failure: removes every planned vault file that no committed row points at, also those an earlier run sealed
     * and never committed. A file whose row cannot be looked up is kept (an orphan costs space, a lost photo cannot be undone).
     */
    private suspend fun unseal(plan: ImportPlan) = plan.media.forEach { c ->
        val referenced = try {
            store.media.get(c.id) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        if (!referenced) vault.delete(c.id)
    }

    /** What this run wrote, by kind, and the ids of the rows it wrote (to compare with the plan). */
    private class Inserted {
        val ids = mutableMapOf<ImportKind, MutableSet<Any>>()
        /** The profile and rules rows that were already there (so the plan's were not written), else null. */
        var existingProfile: Profile? = null
        var existingRules: CompanyRules? = null
        fun add(kind: ImportKind, key: Any, wrote: Boolean) { if (wrote) ids.getOrPut(kind) { mutableSetOf() } += key }
        fun has(kind: ImportKind, key: Any) = ids[kind]?.contains(key) == true
        fun counts(plan: ImportPlan): Map<ImportKind, Int> = ImportKind.entries.associateWith { k ->
            if (k == ImportKind.MEAL_ITEMS) plan.meals.filter { has(ImportKind.MEALS, it.id) }.sumOf { it.items.size } else ids[k]?.size ?: 0
        }
    }

    private suspend fun insert(plan: ImportPlan, media: List<Media>, now: Long): Inserted {
        val w = Inserted()
        w.existingProfile = store.profile.get()
        if (w.existingProfile == null) {
            store.profile.put(plan.profile)
            w.add(ImportKind.PROFILE, Unit, true)
        }
        w.existingRules = store.rules.current()
        if (w.existingRules == null) {
            store.rules.put(plan.rules, now)
            w.add(ImportKind.RULES, Unit, true)
        }
        plan.meals.forEach { w.add(ImportKind.MEALS, it.id, store.meals.insert(it)) }
        val pending = store.meals.pending().map { it.mealId to it.itemIdx }.toSet()
        plan.pending.forEach { p ->
            val key = p.mealId to p.itemIdx
            if (key !in pending) store.meals.putPending(p)
            w.add(ImportKind.PENDING, key, key !in pending)
        }
        plan.weights.forEach { w.add(ImportKind.WEIGHTS, it.id, store.weights.insert(it)) }
        plan.trainings.forEach { w.add(ImportKind.TRAININGS, it.id, store.training.insert(it)) }
        plan.food.forEach { w.add(ImportKind.FOOD, it.name, store.food.insert(it)) }
        val audits = store.journal.audits().map { it.id }.toSet()
        plan.audits.forEach { a ->
            if (a.id !in audits) store.journal.audit(a)
            w.add(ImportKind.AUDIT, a.id, a.id !in audits)
        }
        media.forEach { w.add(ImportKind.MEDIA, it.id, store.media.insert(it)) }
        plan.baseline?.let { w.add(ImportKind.BASELINES, it.id, store.baselines.insert(it)) }
        plan.messages.forEach { w.add(ImportKind.MESSAGES, it.id, store.messages.insert(it)) }
        return w
    }

    /**
     * Step 4: per kind, how many planned records the store holds. A row this run wrote counts only when it reads back exactly
     * as planned; a row an earlier, committed run wrote counts by its id. A media row counts only when it names the bytes
     * [seal] just checked its file decrypts to (so no file is decrypted again here).
     */
    private suspend fun found(plan: ImportPlan, media: List<Media>, w: Inserted): Map<ImportKind, Int> {
        fun <T> ok(kind: ImportKind, key: Any, stored: T?, planned: T): Boolean = stored != null && (!w.has(kind, key) || stored == planned)
        val storedMeals = plan.meals.associate { it.id to store.meals.get(it.id) }
        val pending = store.meals.pending().associateBy { it.mealId to it.itemIdx }
        val audits = store.journal.audits().associateBy { it.id }
        val rules = store.rules.current()
        val profile = store.profile.get()
        return linkedMapOf(
            ImportKind.PROFILE to if (ok(ImportKind.PROFILE, Unit, profile, plan.profile)) 1 else 0,
            ImportKind.RULES to if (ok(ImportKind.RULES, Unit, rules?.copy(version = 0, updatedAt = 0), plan.rules)) 1 else 0,
            ImportKind.MEALS to plan.meals.count { ok(ImportKind.MEALS, it.id, storedMeals[it.id], it) },
            ImportKind.MEAL_ITEMS to plan.meals.sumOf { storedMeals[it.id]?.items?.size ?: 0 },
            ImportKind.PENDING to plan.pending.count { p -> (p.mealId to p.itemIdx).let { ok(ImportKind.PENDING, it, pending[it], p) } },
            ImportKind.WEIGHTS to plan.weights.count { ok(ImportKind.WEIGHTS, it.id, store.weights.get(it.id), it) },
            ImportKind.TRAININGS to plan.trainings.count { ok(ImportKind.TRAININGS, it.id, store.training.get(it.id), it) },
            ImportKind.FOOD to plan.food.count { ok(ImportKind.FOOD, it.name, store.food.get(it.name), it) },
            ImportKind.AUDIT to plan.audits.count { ok(ImportKind.AUDIT, it.id, audits[it.id], it) },
            ImportKind.BASELINES to (plan.baseline?.let { if (ok(ImportKind.BASELINES, it.id, store.baselines.get(it.id), it)) 1 else 0 } ?: 0),
            ImportKind.MEDIA to media.count { m ->
                val row = store.media.get(m.id)
                ok(ImportKind.MEDIA, m.id, row, m) && row!!.sha256 == m.sha256 && row.bytes == m.bytes
            },
            ImportKind.MESSAGES to plan.messages.count { ok(ImportKind.MESSAGES, it.id, store.messages.get(it.id), it) },
        )
    }

    companion object {
        const val LEGACY_DIR = "coach"
        const val BASELINE_DIR = "baseline"
        private const val STATE = "state.json"
        private const val SET_ASIDE_PREFIX = "state.json.bad-"

        /** One import at a time in the process (a second run must not read the folder while the first moves it). */
        private val LOCK = Mutex()

        /** SHA-256 (hex) and size of [file], read as a stream. */
        private fun digest(file: File): Pair<String, Long> {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    size += n
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) } to size
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
