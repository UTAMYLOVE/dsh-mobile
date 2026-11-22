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
import app.agency.core.model.Media
import app.agency.core.model.MigrationLog
import app.agency.core.store.AgencyStore
import app.dshmobile.agency.media.MediaVault
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
)

/** The store did not hold what the plan wrote (only kind names and numbers, never content). */
class ImportMismatchException(val expected: Map<ImportKind, Int>, val found: Map<ImportKind, Int>) :
    IllegalStateException("import counts differ: " + ImportKind.entries.filter { expected[it] != found[it] }.joinToString { "${it.name} ${found[it]}/${expected[it]}" })

/**
 * Imports the coach's records (v0.8.3 or the 5a94d3c build) into the agency store, arch 1.14 steps 1-5.
 *
 * 1. Reads `filesDir/coach` (state.json, else the temp file of a cut save; every line of messages.jsonl; every file in `baseline/`) and
 *    prefs `dshm_coach`, read-only. Nothing legacy is written or deleted while importing.
 * 2. [LegacyMapper] builds the plan.
 * 3. Seals the baseline files into the [vault], then inserts everything in one store transaction, INSERT OR IGNORE on the
 *    legacy ids; the profile, rules and audit rows only when they are not there yet. A second run writes nothing.
 * 4. Inside the transaction, checks every planned record is in the store as planned (and every media file decrypts to its
 *    bytes); any difference rolls everything back, removes the media it sealed, logs the counts, and leaves the legacy files as
 *    they were, for the next start to try again. On success it logs the import and sets `agency_prefs.migrated = 1`.
 * 5. Then renames `filesDir/coach` to `filesDir/coach.migrated-<ts>` (kept until he deletes it in 設定 › 舊資料).
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

    suspend fun run(): ImportReport = withContext(Dispatchers.IO) {
        if (agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0) == 1) return@withContext ImportReport(emptyMap(), ok = true, skipped = true)
        val prefValues = legacyPrefs.all.toMap()
        if (!dir.isDirectory && prefValues.isEmpty()) {
            // A fresh install: nothing to bring over.
            agencyPrefs.edit().putInt(AgencyPrefKeys.MIGRATED, 1).commit()
            return@withContext ImportReport(emptyMap(), ok = true)
        }
        val now = clock()
        val plan = try {
            LegacyMapper.map(read(prefValues), now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A legacy file could not be read: nothing was written, the next start tries again.
            return@withContext ImportReport(emptyMap(), ok = false)
        }
        val sealedNow = mutableListOf<String>()
        try {
            val media = seal(plan, sealedNow)
            val inserted = store.transaction {
                val inserted = insert(plan, media, now)
                val found = found(plan, media, inserted)
                if (found != plan.expected) throw ImportMismatchException(plan.expected, found)
                val logId = "import-" + plan.source.name
                if (store.journal.migrations().none { it.id == logId }) {
                    store.journal.logMigration(MigrationLog(logId, now, plan.source, ImportPlan.countsJson(plan.expected), ok = true, note = plan.note.toJson()))
                }
                inserted.counts(plan)
            }
            val edit = agencyPrefs.edit()
            plan.prefs.forEach { (k, v) ->
                if (!agencyPrefs.contains(k)) when (v) {
                    is PrefValue.Str -> edit.putString(k, v.value)
                    is PrefValue.Num -> edit.putInt(k, v.value)
                }
            }
            check(edit.putInt(AgencyPrefKeys.MIGRATED, 1).commit()) { "agency prefs not saved" }
            val kept = File(filesDir, "$LEGACY_DIR.migrated-$now").takeIf { dir.isDirectory && dir.renameTo(it) }
            ImportReport(plan.expected, ok = true, inserted = inserted, note = plan.note, keptAt = kept)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { unseal(sealedNow) }
            throw e
        } catch (e: Exception) {
            unseal(sealedNow)
            val why = (e as? ImportMismatchException)?.message ?: e.javaClass.simpleName
            runCatching {
                store.journal.logMigration(MigrationLog(UUID.randomUUID().toString(), now, plan.source, ImportPlan.countsJson(plan.expected), ok = false, note = why))
            }
            ImportReport(plan.expected, ok = false, note = plan.note)
        }
    }

    /** Step 1, in the coach's own order: state.json, else state.json.tmp; every message line; the baseline folder. */
    private fun read(prefValues: Map<String, Any?>): LegacyInput {
        val (state, origin) = readState()
        val messagesFile = File(dir, "messages.jsonl")
        val (messages, bad) = if (messagesFile.isFile) LegacyCoachJson.parseMessages(messagesFile.readText()) else emptyList<Nothing>() to 0
        val files = File(dir, BASELINE_DIR).listFiles()?.filter { it.isFile }?.map { LegacyFile(it.name, it.lastModified()) }.orEmpty()
        return LegacyInput(state, origin, messages, bad, files, LegacyPrefs(prefValues))
    }

    private fun readState(): Pair<LegacyState?, StateOrigin> {
        val main = File(dir, "state.json")
        val tmp = File(dir, "state.json.tmp")
        fun parse(f: File): LegacyState? = if (f.isFile) LegacyCoachJson.parseState(f.readText()) else null
        parse(main)?.let { return it to StateOrigin.STATE }
        parse(tmp)?.let { return it to StateOrigin.TMP }
        return null to if (main.isFile || tmp.isFile) StateOrigin.UNREADABLE else StateOrigin.NONE
    }

    /** Seals each baseline file (a file already sealed with the same bytes is kept) and checks it decrypts to those bytes. */
    private fun seal(plan: ImportPlan, sealedNow: MutableList<String>): List<Media> = plan.media.map { c ->
        val plain = File(File(dir, BASELINE_DIR), c.file).readBytes()
        val sha = sha256(plain)
        val already = vault.has(c.id) && runCatching { sha256(vault.get(c.id)) == sha }.getOrDefault(false)
        if (!already) {
            vault.put(c.id, plain)
            sealedNow += c.id
        }
        check(sha256(vault.get(c.id)) == sha) { "sealed media differs" }
        Media(c.id, c.kind, c.purpose, c.mime, plain.size.toLong(), sha, c.createdAt)
    }

    /** Removes the files this run sealed that no committed row points at (the transaction rolled back: normally all). */
    private suspend fun unseal(ids: List<String>) = ids.forEach { id ->
        val referenced = try {
            store.media.get(id) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!referenced) vault.delete(id)
    }

    /** What this run wrote, by kind, and the ids of the rows it wrote (to compare with the plan). */
    private class Inserted {
        val ids = mutableMapOf<ImportKind, MutableSet<Any>>()
        fun add(kind: ImportKind, key: Any, wrote: Boolean) { if (wrote) ids.getOrPut(kind) { mutableSetOf() } += key }
        fun has(kind: ImportKind, key: Any) = ids[kind]?.contains(key) == true
        fun counts(plan: ImportPlan): Map<ImportKind, Int> = ImportKind.entries.associateWith { k ->
            if (k == ImportKind.MEAL_ITEMS) plan.meals.filter { has(ImportKind.MEALS, it.id) }.sumOf { it.items.size } else ids[k]?.size ?: 0
        }
    }

    private suspend fun insert(plan: ImportPlan, media: List<Media>, now: Long): Inserted {
        val w = Inserted()
        if (store.profile.get() == null) {
            store.profile.put(plan.profile)
            w.add(ImportKind.PROFILE, Unit, true)
        }
        if (store.rules.current() == null) {
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
     * as planned; a row an earlier, committed run wrote counts by its id. A media row counts only when its file decrypts to
     * the bytes it names.
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
                ok(ImportKind.MEDIA, m.id, row, m) && runCatching { sha256(vault.get(m.id)) == row!!.sha256 }.getOrDefault(false)
            },
            ImportKind.MESSAGES to plan.messages.count { ok(ImportKind.MESSAGES, it.id, store.messages.get(it.id), it) },
        )
    }

    companion object {
        const val LEGACY_DIR = "coach"
        const val BASELINE_DIR = "baseline"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
