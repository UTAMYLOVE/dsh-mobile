package app.dshmobile.chat

import app.dshmobile.remote.Reminder
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** When a helper runs (Taiwan time). */
sealed interface HelperTiming {
    data class Daily(val time: String) : HelperTiming
    /** [day] 1 = Monday … 7 = Sunday. */
    data class Weekly(val day: Int, val time: String) : HelperTiming
    data class Every(val minutes: Int) : HelperTiming

    /** 「每天 08:00」「每週一 08:00」「每 60 分鐘」. */
    val zh: String get() = when (this) {
        is Daily -> "每天 $time"
        is Weekly -> "每週${DAYS[(day - 1).coerceIn(0, 6)]} $time"
        is Every -> if (minutes % 60 == 0) "每 ${minutes / 60} 小時" else "每 $minutes 分鐘"
    }

    companion object {
        val DAYS = listOf("一", "二", "三", "四", "五", "六", "日")
        /** The shortest repeat a helper may have: shorter would keep dsh (and the battery) busy all day. */
        const val MIN_EVERY = 30
        private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        fun validTime(t: String) = HHMM.matches(t)
    }
}

/** An always-on helper (0.5.0): its own conversation holds the dsh schedule; the spec stays here so pausing is reversible. */
data class Helper(
    val id: String,
    val sessionId: String,
    val name: String,
    val task: String,
    val timing: HelperTiming,
    val paused: Boolean = false,
    val createdAt: Long = 0,
)

/** What the helpers page shows for one helper, from dsh's schedule catalog. */
enum class HelperState { ACTIVE, PAUSED, LOST }

object Helpers {
    val ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    const val TITLE_PREFIX = "小幫手："

    /** The message that has the helper's conversation set its schedule up (dsh's `schedule_create`). */
    fun createPrompt(h: Helper): String {
        val timing = when (val t = h.timing) {
            is HelperTiming.Daily -> "daily：{ time: \"${t.time}\", time_zone: \"Asia/Taipei\" }"
            is HelperTiming.Weekly -> "weekly：{ time: \"${t.time}\", time_zone: \"Asia/Taipei\", weekdays: [${t.day}] }（1 = 週一）"
            is HelperTiming.Every -> "every_seconds：${t.minutes * 60}"
        }
        return """
            你是常駐小幫手「${h.name}」，這個對話專門給你用。
            請現在用 schedule_create 建立一個排程（只建立一個；這個對話裡有舊的排程就先刪掉）：
            - title：${h.name}
            - 時間：$timing（${h.timing.zh}）
            - prompt：${h.task.trim()}。做完後用 notify_user 把結果用 3 行以內通知主人，標題寫「${h.name}」。
            建立好之後回一句確認（寫出下次執行的時間），現在先不要執行任務本身。
        """.trimIndent()
    }

    /** The state of [h] given the schedules dsh has ([catalog]). */
    fun stateOf(h: Helper, catalog: List<Reminder>): HelperState = when {
        h.paused -> HelperState.PAUSED
        catalog.any { it.sessionId == h.sessionId && it.active } -> HelperState.ACTIVE
        else -> HelperState.LOST
    }

    /** dsh's schedules that belong to helpers. */
    fun ofHelpers(catalog: List<Reminder>, helpers: List<Helper>): List<Reminder> {
        val ids = helpers.mapTo(HashSet()) { it.sessionId }
        return catalog.filter { it.sessionId in ids }
    }

    /** When [h] next runs after [now] (ms), computed from its timing; null when paused. */
    fun nextRun(h: Helper, now: Long): Long? {
        if (h.paused) return null
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZONE)
        return when (val t = h.timing) {
            is HelperTiming.Daily -> {
                val time = LocalTime.parse(t.time)
                var next = at.with(time).withSecond(0).withNano(0)
                if (!next.isAfter(at)) next = next.plusDays(1)
                next.toInstant().toEpochMilli()
            }
            is HelperTiming.Weekly -> {
                val time = LocalTime.parse(t.time)
                var next = at.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(t.day.coerceIn(1, 7)))).with(time).withSecond(0).withNano(0)
                if (!next.isAfter(at)) next = next.plusWeeks(1)
                next.toInstant().toEpochMilli()
            }
            // dsh counts from when the schedule was made; the next whole period from creation is close enough to wake dsh for.
            is HelperTiming.Every -> {
                val period = t.minutes * 60_000L
                val start = if (h.createdAt > 0) h.createdAt else now
                start + ((now - start) / period + 1) * period
            }
        }
    }
}

/** The helpers, one JSON file in `filesDir` (app-private). */
class HelperStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val _helpers = MutableStateFlow(load())
    val helpers: StateFlow<List<Helper>> = _helpers.asStateFlow()

    fun add(sessionId: String, name: String, task: String, timing: HelperTiming): Helper {
        val h = Helper(UUID.randomUUID().toString(), sessionId, name.trim().ifEmpty { "小幫手" }.take(30), task.trim(), timing, false, clock())
        update { it + h }
        return h
    }

    fun setPaused(id: String, paused: Boolean) = update { l -> l.map { if (it.id == id) it.copy(paused = paused) else it } }
    fun remove(id: String) = update { l -> l.filter { it.id != id } }
    fun forSession(sessionId: String): Helper? = _helpers.value.firstOrNull { it.sessionId == sessionId }

    /** The soonest run of any active helper after [now]. */
    fun nextRun(now: Long = clock()): Long? = _helpers.value.mapNotNull { Helpers.nextRun(it, now) }.minOrNull()

    @Synchronized private fun update(f: (List<Helper>) -> List<Helper>) {
        val next = f(_helpers.value)
        if (next == _helpers.value) return
        _helpers.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(next).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun load(): List<Helper> = try {
        if (file.isFile) decode(Json.parseToJsonElement(file.readText())) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val FILE_NAME = "helpers.json"

        fun encode(l: List<Helper>) = buildJsonObject {
            put("version", 1)
            put("helpers", buildJsonArray {
                l.forEach { h ->
                    add(buildJsonObject {
                        put("id", h.id); put("sessionId", h.sessionId); put("name", h.name); put("task", h.task)
                        put("paused", h.paused); put("createdAt", h.createdAt)
                        when (val t = h.timing) {
                            is HelperTiming.Daily -> { put("kind", "daily"); put("time", t.time) }
                            is HelperTiming.Weekly -> { put("kind", "weekly"); put("time", t.time); put("day", t.day) }
                            is HelperTiming.Every -> { put("kind", "every"); put("minutes", t.minutes) }
                        }
                    })
                }
            })
        }

        fun decode(e: kotlinx.serialization.json.JsonElement): List<Helper> =
            ((e as? JsonObject)?.get("helpers") as? JsonArray).orEmpty().mapNotNull { x ->
                val o = x as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val time = s("time")?.takeIf(HelperTiming::validTime)
                val timing = when (s("kind")) {
                    "daily" -> time?.let { HelperTiming.Daily(it) }
                    "weekly" -> time?.let { t -> (o["day"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..7 }?.let { HelperTiming.Weekly(it, t) } }
                    "every" -> (o["minutes"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= HelperTiming.MIN_EVERY }?.let { HelperTiming.Every(it) }
                    else -> null
                } ?: return@mapNotNull null
                Helper(
                    s("id") ?: return@mapNotNull null, s("sessionId") ?: return@mapNotNull null, s("name").orEmpty(), s("task").orEmpty(), timing,
                    (o["paused"] as? JsonPrimitive)?.booleanOrNull ?: false, (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
            }
    }
}
