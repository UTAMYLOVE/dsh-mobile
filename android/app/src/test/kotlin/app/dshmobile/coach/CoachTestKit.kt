package app.dshmobile.coach

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.agency.core.metering.Provider
import app.dshmobile.security.KeyVault
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A model that answers the parse call with [parse] and every reply call with [chat]; it records what was sent. */
class FakeCoachModel(
    var parse: (String) -> ModelReply = { ModelReply.Ok("""{"kind":"chat"}""") },
    var chat: (String) -> ModelReply = { ModelReply.Ok("這是藉口，第二種。10 分鐘，現在練，練完傳錄音。") },
) : CoachModel {
    val calls = mutableListOf<Pair<String, List<Turn>>>()
    override suspend fun complete(system: String, turns: List<Turn>, maxTokens: Int): ModelReply {
        calls += system to turns
        val last = (turns.last().parts.first() as Part.Text).text
        return if (system == MealParse.SYSTEM) parse(last) else chat(last)
    }
}

class FakeVault(private val keys: MutableMap<String, String> = mutableMapOf()) : KeyVault {
    override fun get(p: Provider): String? = keys[p.name]
    override fun put(p: Provider, key: String) { keys[p.name] = key }
    override fun clear(p: Provider) { keys.remove(p.name) }
    override fun getNamed(name: String): String? = keys[name]
    override fun putNamed(name: String, key: String) { keys[name] = key }
    override fun clearNamed(name: String) { keys.remove(name) }
}

/** Saturday 2026-11-21 19:00 in Taipei (not the default 放縱餐 day). */
val SATURDAY_EVENING: Instant = Instant.parse("2026-11-21T11:00:00Z")
val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

class CoachRig(
    val dir: File = Files.createTempDirectory("coach").toFile(),
    val clock: MutableClock = MutableClock(SATURDAY_EVENING),
    val model: FakeCoachModel = FakeCoachModel(),
    val prefs: InMemorySharedPreferences = InMemorySharedPreferences(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val settings = CoachSettings(prefs)
    val store = CoachStore(dir) { clock.millis() }
    val engine = CoachEngine(store, settings, model, clock, TAIPEI, scope)

    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** The parse JSON for one meal item. */
    companion object {
        fun item(name: String, portion: Double = 1.0, fried: Boolean = false, low: Int? = null, high: Int? = null, pLow: Int? = null) =
            """{"name":"$name","portion":$portion,"fried":$fried,"kcal_low":${low ?: "null"},"kcal_high":${high ?: "null"},"protein_low":${pLow ?: "null"},"protein_high":null}"""
        fun parsed(kind: String, vararg items: String, weight: Double? = null, foodInfo: String? = null) =
            """{"kind":"$kind","items":[${items.joinToString(",")}],"weight_kg":${weight ?: "null"},"waist_cm":null,"food_info":${foodInfo ?: "null"},"illness":false}"""
    }
}
