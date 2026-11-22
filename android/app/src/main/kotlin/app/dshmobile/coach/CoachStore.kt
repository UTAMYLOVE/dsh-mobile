package app.dshmobile.coach

import app.agency.core.rules.FoodTable
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The coach's records on the phone, in [dir] (the app's own files, outside dsh's workspace: neither dsh nor the model can read
 * or change them). `state.json` is rewritten atomically (temp file, then rename); `messages.jsonl` is append-only and a
 * damaged line is skipped on load.
 */
class CoachStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val stateFile = File(dir, "state.json")
    /** 入社建檔's photo and recording (never sent to the model). */
    val baselineDir: File get() = File(dir, "baseline").also { it.mkdirs() }
    private val messagesFile = File(dir, "messages.jsonl")

    /** True when an unreadable state file could not be kept aside: nothing is written over it. */
    // Declared before [_state]: loadState() (its initializer) may set it.
    var frozen = false
        private set

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<CoachState> = _state.asStateFlow()

    private val _messages = MutableStateFlow(loadMessages())
    val messages: StateFlow<List<CoachMessage>> = _messages.asStateFlow()

    private fun read(f: File): CoachState? = try {
        if (f.isFile) CoachJson.decode(Json.parseToJsonElement(f.readText())) else null
    } catch (_: Exception) {
        null
    }

    /**
     * The saved state; a damaged `state.json` is never written over: the temp file of an interrupted save is tried first, and a
     * file nothing can read is kept aside as `state.json.bad-<time>` before a fresh state is seeded.
     */
    private fun loadState(): CoachState {
        val tmp = File(dir, "state.json.tmp")
        var read = read(stateFile)
        if (read == null && stateFile.isFile) {
            read = read(tmp)
            if (read == null && !keepAside(stateFile)) {
                // The unreadable file could not be kept aside: never write over it; this run keeps its records in memory only.
                frozen = true
            }
        } else if (read == null && tmp.isFile) {
            // A crash between deleting the old file and renaming the new one leaves only the temp file.
            read = read(tmp)
        }
        val base = read ?: CoachState()
        if (base.seeded) {
            if (!stateFile.isFile) write(base)
            return base
        }
        val seeded = base.copy(seeded = true, food = FoodTable.seed(clock()) + base.food)
        write(seeded)
        return seeded
    }

    private fun loadMessages(): List<CoachMessage> {
        if (!messagesFile.isFile) return emptyList()
        return try {
            messagesFile.readLines().mapNotNull { line ->
                if (line.isBlank()) null else runCatching { CoachJson.decodeMessage(Json.parseToJsonElement(line)) }.getOrNull()
            }.takeLast(KEEP_IN_MEMORY)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves (or else copies) an unreadable state file aside; false when neither worked. */
    private fun keepAside(f: File): Boolean {
        val t = clock()
        if (f.renameTo(File(dir, "state.json.bad-$t"))) return true
        return runCatching { f.copyTo(File(dir, "state.json.bad-$t-copy")); true }.getOrDefault(false)
    }

    private fun write(s: CoachState): Boolean = !frozen && runCatching {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(CoachJson.encode(s).toString())
        // renameTo replaces the old file in one step on Android's file systems; the fallback keeps the temp file until it lands.
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            check(tmp.renameTo(stateFile))
        }
    }.isSuccess

    /** Applies [f] and saves it. Null when the save failed: nothing changed, in memory or on disk. */
    @Synchronized fun update(f: (CoachState) -> CoachState): CoachState? {
        val next = f(_state.value)
        if (next == _state.value) return next
        if (!write(next)) return null
        _state.value = next
        return next
    }

    @Synchronized fun append(m: CoachMessage) {
        runCatching {
            dir.mkdirs()
            // A line cut by a crash would glue onto the next one: start each record on its own line.
            val needsBreak = messagesFile.isFile && messagesFile.length() > 0 &&
                java.io.RandomAccessFile(messagesFile, "r").use { f -> f.seek(f.length() - 1); f.read() != '\n'.code }
            messagesFile.appendText((if (needsBreak) "\n" else "") + CoachJson.encodeMessage(m) + "\n")
        }
        _messages.value = (_messages.value + m).takeLast(KEEP_IN_MEMORY)
    }

    companion object {
        const val KEEP_IN_MEMORY = 300
    }
}
