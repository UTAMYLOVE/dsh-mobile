package app.dshmobile.overview

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Clock

/** One memory reminder the heartbeat's "dream" produced. [at] is epoch ms. */
data class Reminder(val title: String, val body: String, val at: Long)

/**
 * The last ten reminders the heartbeat posted, kept in [prefs] (JSON), newest first when read. Only the heartbeat's own
 * reminders go in here: notifications dsh's plugins raise through the gateway do not.
 */
class ReminderLog(private val prefs: SharedPreferences, private val clock: Clock) {
    private val _revision = MutableStateFlow(0L)

    /** Goes up by one on every [add]. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun add(title: String, body: String) {
        synchronized(this) {
            val kept = (listOf(Reminder(title, body, clock.millis())) + read()).take(MAX)
            val array = JsonArray(
                kept.map { JsonObject(mapOf("t" to JsonPrimitive(it.title), "b" to JsonPrimitive(it.body), "at" to JsonPrimitive(it.at))) },
            )
            prefs.edit().putString(KEY, array.toString()).apply()
        }
        _revision.value += 1
    }

    /** The newest [limit] reminders, newest first. */
    fun recent(limit: Int = 3): List<Reminder> = synchronized(this) { read().take(limit) }

    private fun read(): List<Reminder> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.map {
                val o = it.jsonObject
                Reminder(o.getValue("t").jsonPrimitive.content, o.getValue("b").jsonPrimitive.content, o.getValue("at").jsonPrimitive.long)
            }
        } catch (e: RuntimeException) { // a damaged value: start over rather than crash the overview
            emptyList()
        }
    }

    companion object {
        const val PREFS = "dshm_reminders"
        private const val KEY = "list"
        private const val MAX = 10
    }
}
