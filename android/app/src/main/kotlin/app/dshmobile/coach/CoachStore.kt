package app.dshmobile.coach

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
    private val messagesFile = File(dir, "messages.jsonl")

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<CoachState> = _state.asStateFlow()

    private val _messages = MutableStateFlow(loadMessages())
    val messages: StateFlow<List<CoachMessage>> = _messages.asStateFlow()

    private fun loadState(): CoachState {
        val read = try {
            if (stateFile.isFile) CoachJson.decode(Json.parseToJsonElement(stateFile.readText())) else CoachState()
        } catch (_: Exception) {
            CoachState()
        }
        if (read.seeded) return read
        val seeded = read.copy(seeded = true, food = FoodTable.seed(clock()) + read.food)
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

    private fun write(s: CoachState): Boolean = runCatching {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(CoachJson.encode(s).toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            check(tmp.renameTo(stateFile))
        }
    }.isSuccess

    @Synchronized fun update(f: (CoachState) -> CoachState): CoachState {
        val next = f(_state.value)
        if (next != _state.value) {
            _state.value = next
            write(next)
        }
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
