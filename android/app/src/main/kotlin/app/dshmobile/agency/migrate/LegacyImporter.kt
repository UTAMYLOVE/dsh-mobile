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
import app.agency.core.migrate.MediaCopy
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The result of one [LegacyImporter.run]. [counts] are the planned records per kind, as the legacy files hold them (what the
 * store holds afterwards when [ok], unless he deleted some after an earlier partial run); [inserted] what this run wrote (all
 * zero when the records were already there). [skipped] when an earlier run had finished, or was partial and the files did not
 * change since. [note] is the import note for 設定 › 舊資料.
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
    /**
     * The state file could not be read whole: what could be read is in, the folder stays where it is, and the import does not
     * run again until the legacy files change or he asks ([LegacyImporter.retry]). Also set on the skipped runs after it.
     */
    val partial: Boolean = false,
) {
    /**
     * True only on the run that made the import partial, so T06 shows its notice once (「有些舊紀錄讀不出來；讀得到的都搬好了，
     * 舊資料都還留著。」), not at every start; the skipped starts after it show nothing (and no 「正在搬資料」).
     */
    val partialNotice: Boolean get() = partial && !skipped
}

/** The phone ran out of memory sealing the baseline file [file]: that file is left out, everything else is imported. */
class MediaTooLargeException(val file: String, cause: Throwable) : IOException("media too large", cause)

/** The store did not hold what the plan wrote (only kind names and numbers, never content). */
class ImportMismatchException(val expected: Map<ImportKind, Int>, val found: Map<ImportKind, Int>) :
    IllegalStateException("import counts differ: " + ImportKind.entries.filter { expected[it] != found[it] }.joinToString { "${it.name} ${found[it]}/${expected[it]}" })

/**
 * Imports the coach's records (v0.8.3 or the 5a94d3c build) into the agency store, arch 1.14 steps 1-5.
 *
 * 1. Reads `filesDir/coach` (state.json, else the temp file of a cut save, else the complete records of the cut file that holds
 *    the most; every
 *    `state.json.bad-*` file the coach set aside; every line of messages.jsonl; every file in `baseline/` up to
 *    [LegacyMapper.MAX_IMPORT_MEDIA_BYTES]) and prefs `dshm_coach`, read-only. Nothing legacy is written or deleted while
 *    importing.
 * 2. [LegacyMapper] builds the plan.
 * 3. Seals the baseline files into the [vault], then inserts everything in one store transaction, INSERT OR IGNORE on the
 *    legacy ids; the profile, rules and audit rows only when they are not there yet. A second run writes nothing.
 *    A baseline file the phone runs out of memory sealing is left out ([ImportNote.mediaTooLarge]); the rest still imports.
 * 4. Inside the transaction, checks every planned record is in the store as planned (and every media file decrypts to its
 *    bytes); any difference rolls everything back, removes the media no committed row points at, logs the counts (one row,
 *    brought up to date by each failed run), and leaves the legacy files as they were, for the next start to try again. On
 *    success it logs the import and sets `agency_prefs.migrated = 1`.
 *    When no state file could be read whole, the import is partial: what could be read is committed and logged, and
 *    `migrated = 2` is saved with a fingerprint of the legacy files and the keys of the records it brought. Later starts do
 *    nothing while the files match the fingerprint; a changed file or [retry] runs the import again, leaving out the records
 *    a partial run already brought (one he deleted stays deleted, one he edited keeps his edit).
 * 5. Then renames `filesDir/coach` to `filesDir/coach.migrated-<ts>` (kept until he deletes it in 設定 › 舊資料); a rename
 *    that failed is tried again at the next start. A partial import renames nothing.
 *
 * One row in `migration_log` holds the outcome ([LOG_ID]: partial, then done), one more the last rolled-back run
 * ([FAILED_LOG_ID]); no run adds a row beside them. One run at a time in the process: a second call waits and then finds the
 * import finished.
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

    suspend fun run(): ImportReport = withContext(Dispatchers.IO) { LOCK.withLock { runLocked(force = false) } }

    /**
     * 設定 › 舊資料 「再試一次」 after a partial import: runs the import again now, also when the legacy files did not change.
     * Records a partial run already brought are left alone. Does nothing after a finished import, or when the legacy folder
     * is gone.
     */
    suspend fun retry(): ImportReport = withContext(Dispatchers.IO) { LOCK.withLock { runLocked(force = true) } }

    private suspend fun runLocked(force: Boolean): ImportReport {
        val migrated = agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0)
        if (migrated == AgencyPrefKeys.MIGRATED_DONE) {
            // Done before; a rename that failed then is tried again.
            if (!dir.isDirectory) return ImportReport(emptyMap(), ok = true, skipped = true)
            val kept = keepAside(clock())
            return ImportReport(emptyMap(), ok = true, skipped = true, keptAt = kept, renameFailed = kept == null)
        }
        val partialBefore = migrated == AgencyPrefKeys.MIGRATED_PARTIAL
        if (partialBefore && (!dir.isDirectory || (!force && sameStamp()))) {
            // Partial before, and nothing changed since (or he removed the folder): nothing is read, written or shown.
            return ImportReport(emptyMap(), ok = false, skipped = true, partial = true)
        }
        val prefValues = legacyPrefs.all.toMap()
        if (!dir.isDirectory) {
            // The folder was already moved aside by a finished import (its flag is gone): nothing to plan, no seeds to add.
            // Or a fresh install: nothing to bring over.
            val moved = filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("$LEGACY_DIR.migrated-") }?.isNotEmpty() == true
            if (moved || prefValues.isEmpty()) {
                agencyPrefs.edit().putInt(AgencyPrefKeys.MIGRATED, AgencyPrefKeys.MIGRATED_DONE).commit()
                return ImportReport(emptyMap(), ok = true, skipped = moved)
            }
        }
        val now = clock()
        val carried = if (partialBefore) carried() else emptySet()
        val (read, stamp, mapped) = try {
            val read = read(prefValues)
            Triple(read, if (dir.isDirectory) stamp() else null, LegacyMapper.map(read, now))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A legacy file could not be read: nothing was written, the next start tries again.
            return ImportReport(emptyMap(), ok = false)
        }
        var input = read
        var full = mapped
        // Without what a partial run already brought; the largest set this run may seal, so a failure removes from it.
        var plan = full.except(carried)
        val firstPlan = plan
        var committed = false
        try {
            var sealed: List<Media>? = null
            while (sealed == null) {
                try {
                    sealed = seal(plan)
                } catch (e: MediaTooLargeException) {
                    // That one file is left out (noted as too large); every other file and record still comes.
                    check(input.baselineFiles.any { it.name == e.file && it.size != Long.MAX_VALUE }) { "media too large again" }
                    unseal(plan.media.filter { it.file == e.file })
                    input = input.copy(baselineFiles = input.baselineFiles.map { if (it.name == e.file) it.copy(size = Long.MAX_VALUE) else it })
                    full = LegacyMapper.map(input, now)
                    plan = full.except(carried)
                }
            }
            val media: List<Media> = sealed
            val (inserted, note) = store.transaction {
                val w = insert(plan, media, now)
                val found = found(plan, media, w)
                if (found != plan.expected) throw ImportMismatchException(plan.expected, found)
                val note = plan.note.copy(notApplied = LegacyMapper.notApplied(plan, w.existingProfile, w.existingRules))
                val counts = ImportPlan.countsJson(full.expected)
                if (plan.stateRead) {
                    // Written once: a second run (after a crash before the flag) keeps the first row.
                    if (store.journal.migrations().none { it.id == LOG_ID && it.ok }) {
                        store.journal.logMigration(MigrationLog(LOG_ID, now, plan.source, counts, ok = true, note = note.toJson()))
                    }
                } else {
                    store.journal.logMigration(MigrationLog(LOG_ID, now, plan.source, counts, ok = false, note = note.toJson()))
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
                // What could be read is in; the state is not. No rename: the folder stays. Final until the files change.
                val keys = (carried + plan.recordKeys()).sorted().joinToString(",")
                edit.putInt(AgencyPrefKeys.MIGRATED, AgencyPrefKeys.MIGRATED_PARTIAL)
                    .putString(AgencyPrefKeys.LEGACY_STAMP, stamp)
                    .putString(AgencyPrefKeys.LEGACY_CARRIED, keys)
                check(edit.commit()) { "agency prefs not saved" }
                return ImportReport(full.expected, ok = false, inserted = inserted, note = note, partial = true)
            }
            edit.putInt(AgencyPrefKeys.MIGRATED, AgencyPrefKeys.MIGRATED_DONE).remove(AgencyPrefKeys.LEGACY_STAMP).remove(AgencyPrefKeys.LEGACY_CARRIED)
            check(edit.commit()) { "agency prefs not saved" }
            val hadDir = dir.isDirectory
            val kept = keepAside(now)
            return ImportReport(full.expected, ok = true, inserted = inserted, note = note, keptAt = kept, renameFailed = hadDir && kept == null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { unseal(firstPlan.media) }
            throw e
        } catch (e: Exception) {
            unseal(firstPlan.media)
            // After the commit the records and their row are in: only the flag is missing, the next start sets it.
            if (!committed) {
                val why = (e as? ImportMismatchException)?.message ?: e.javaClass.simpleName
                try {
                    store.journal.logMigration(MigrationLog(FAILED_LOG_ID, now, plan.source, ImportPlan.countsJson(plan.expected), ok = false, note = why))
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // The log is best effort; the report says the import failed.
                }
            }
            return ImportReport(full.expected, ok = false, note = plan.note)
        }
    }

    /** The legacy files still match the fingerprint the partial import saved (an unreadable file counts as changed). */
    private fun sameStamp(): Boolean = try {
        stamp() == agencyPrefs.getString(AgencyPrefKeys.LEGACY_STAMP, null)
    } catch (_: IOException) {
        false
    }

    /** What partial runs committed ([ImportPlan.recordKeys]). */
    private fun carried(): Set<String> =
        agencyPrefs.getString(AgencyPrefKeys.LEGACY_CARRIED, null).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    /**
     * A fingerprint of what the import reads in `filesDir/coach`: the bytes of every state file (state.json, its temp file,
     * every set-aside file) and of messages.jsonl, and the name, size and time of every baseline file. A hash only.
     */
    private fun stamp(): String {
        val md = MessageDigest.getInstance("SHA-256")
        dir.listFiles { f -> f.isFile && (f.name.startsWith(STATE) || f.name == MESSAGES) }.orEmpty().sortedBy { it.name }.forEach { f ->
            val (sha, size) = digest(f)
            md.update("${f.name}:$size:$sha\n".toByteArray(Charsets.UTF_8))
        }
        File(dir, BASELINE_DIR).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
            md.update("$BASELINE_DIR/${f.name}:${f.length()}:${f.lastModified()}\n".toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Step 5: `coach` → `coach.migrated-<at>`; null when the rename failed (or there is no folder). */
    private fun keepAside(at: Long): File? = File(filesDir, "$LEGACY_DIR.migrated-$at").takeIf { dir.isDirectory && dir.renameTo(it) }

    /**
     * Step 1, in the coach's own order: state.json, else state.json.tmp, else the complete records of whichever of them was
     * cut; every set-aside state file; every message line; the baseline folder.
     */
    private fun read(prefValues: Map<String, Any?>): LegacyInput {
        val state = readState()
        val messagesFile = File(dir, MESSAGES)
        val (messages, bad) = if (messagesFile.isFile) LegacyCoachJson.parseMessages(messagesFile.readText()) else emptyList<Nothing>() to 0
        val files = File(dir, BASELINE_DIR).listFiles()?.filter { it.isFile }?.map { LegacyFile(it.name, it.lastModified(), it.length()) }.orEmpty()
        // The coach moved an unreadable state.json aside as state.json.bad-<ms> (or copied it as …-copy) before seeding a new one.
        val salvaged = mutableSetOf<String>()
        val setAside = dir.listFiles { f -> f.isFile && f.name.startsWith(SET_ASIDE_PREFIX) }.orEmpty().sortedBy { it.name }.map { f ->
            val text = f.readText()
            f.name to (LegacyCoachJson.parseState(text) ?: LegacyCoachJson.salvageState(text)?.also { salvaged += f.name })
        }
        return LegacyInput(
            state.state, state.origin, messages, bad, files, LegacyPrefs(prefValues),
            setAside = setAside, salvagedFrom = state.salvagedFrom, tmpIgnored = state.tmpIgnored, setAsideSalvaged = salvaged,
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
        // Both cut: the one that still holds more complete records (the main file on a tie, as in the coach's order).
        val salvaged = listOfNotNull(
            mainText?.let(LegacyCoachJson::salvageState)?.let { it to main.name },
            tmpText?.let(LegacyCoachJson::salvageState)?.let { it to tmp.name },
        ).maxByOrNull { it.first.records }
        salvaged?.let { (state, from) -> return StateRead(state, StateOrigin.SALVAGED, salvagedFrom = from) }
        return StateRead(null, if (mainText != null || tmpText != null) StateOrigin.UNREADABLE else StateOrigin.NONE)
    }

    /**
     * Seals each baseline file (a file already sealed with the same bytes is kept) and checks it decrypts to those bytes.
     * The plaintext is hashed as a stream; [MediaVault.put] holds the plaintext and its sealed copy, and the check afterwards
     * the sealed copy and its decryption (the cipher may buffer one more copy).
     * @throws MediaTooLargeException when the phone runs out of memory on one file.
     */
    private fun seal(plan: ImportPlan): List<Media> = plan.media.map { c ->
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
                check(sealed.sha256 == sha && sealed.bytes == size) { "media changed while importing" }
                check(sha256(vault.get(c.id)) == sha) { "sealed media differs" }
            }
            Media(c.id, c.kind, c.purpose, c.mime, size, sha, c.createdAt)
        } catch (e: OutOfMemoryError) {
            // Not a crash at every start: this file is left out and the import goes on without it.
            throw MediaTooLargeException(c.file, e)
        }
    }

    /**
     * After a failure: removes every vault file of [media] that no committed row points at, also those an earlier run sealed
     * and never committed. A file whose row cannot be looked up is kept (an orphan costs space, a lost photo cannot be undone).
     */
    private suspend fun unseal(media: List<MediaCopy>) = media.forEach { c ->
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

        /** The `migration_log` row of the import's outcome: partial (ok false), then done (ok true). */
        const val LOG_ID = "import-legacy"

        /** The `migration_log` row of the last run that was rolled back. */
        const val FAILED_LOG_ID = "import-legacy-failed"
        private const val STATE = "state.json"
        private const val MESSAGES = "messages.jsonl"
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
