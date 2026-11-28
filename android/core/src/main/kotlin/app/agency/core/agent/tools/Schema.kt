package app.agency.core.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The strict JSON schemas the providers enforce (`strict: true`): every property listed in `required`, optional ones made
 * nullable with a `["type", "null"]` type, `additionalProperties: false` on every object. No numeric or length constraints,
 * because strict mode does not accept them: ranges are checked in code ([Args]) where a bad value gets a helpful message.
 */
internal object S {
    fun obj(vararg props: Pair<String, JsonObject>): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(props.toMap()),
            "required" to JsonArray(props.map { JsonPrimitive(it.first) }),
            "additionalProperties" to JsonPrimitive(false),
        ),
    )

    private fun typed(type: String, desc: String, nullable: Boolean, extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        val t: JsonElement = if (nullable) JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null"))) else JsonPrimitive(type)
        return JsonObject(mapOf("type" to t, "description" to JsonPrimitive(desc)) + extra)
    }

    fun str(desc: String, nullable: Boolean = false) = typed("string", desc, nullable)
    fun int(desc: String, nullable: Boolean = false) = typed("integer", desc, nullable)
    fun num(desc: String, nullable: Boolean = false) = typed("number", desc, nullable)
    fun bool(desc: String, nullable: Boolean = false) = typed("boolean", desc, nullable)

    fun enum(desc: String, values: List<String>, nullable: Boolean = false): JsonObject {
        val vs = values.map { JsonPrimitive(it) as JsonElement } + if (nullable) listOf(JsonNull) else emptyList()
        return typed("string", desc, nullable, mapOf("enum" to JsonArray(vs)))
    }

    fun arr(desc: String, items: JsonObject) = typed("array", desc, false, mapOf("items" to items))
}

/** A bad argument: its message (Traditional Chinese, written to 小葵) goes back as the tool's error. */
internal class BadArg(message: String) : Exception(message)

/**
 * Reads tool arguments leniently (a number sent as a string still counts, a missing optional is null) but strictly about
 * meaning: a required value that is missing or of the wrong kind throws [BadArg] with a message she can act on.
 */
internal class Args(private val o: JsonObject) {
    private fun prim(k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun has(k: String): Boolean = prim(k) != null || o[k] is JsonArray || o[k] is JsonObject

    fun strOrNull(k: String): String? = prim(k)?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun str(k: String, what: String): String = strOrNull(k) ?: throw BadArg("缺少「$what」（$k）。")

    fun text(k: String): String = strOrNull(k).orEmpty()

    fun longOrNull(k: String, what: String): Long? {
        val p = prim(k) ?: return null
        val v = p.longOrNull ?: p.content.trim().removePrefix("#").toLongOrNull()
            ?: p.doubleOrNull?.takeIf { it == Math.floor(it) }?.toLong()
        return v ?: throw BadArg("「$what」（$k）要是整數，收到的是「${p.content}」。")
    }

    fun long(k: String, what: String): Long = longOrNull(k, what) ?: throw BadArg("缺少「$what」（$k）。")

    fun intOrNull(k: String, what: String, range: IntRange? = null): Int? {
        val v = longOrNull(k, what)?.toInt() ?: return null
        if (range != null && v !in range) throw BadArg("「$what」（$k）要在 ${range.first} 到 ${range.last} 之間，收到 $v。")
        return v
    }

    fun int(k: String, what: String, range: IntRange? = null): Int = intOrNull(k, what, range) ?: throw BadArg("缺少「$what」（$k）。")

    fun numOrNull(k: String, what: String, min: Double? = null, max: Double? = null): Double? {
        val p = prim(k) ?: return null
        val v = p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
            ?: throw BadArg("「$what」（$k）要是數字，收到的是「${p.content}」。")
        if (v.isNaN() || (min != null && v < min) || (max != null && v > max)) {
            throw BadArg("「$what」（$k）不合理：$v。")
        }
        return v
    }

    fun boolOrNull(k: String): Boolean? {
        val p = prim(k) ?: return null
        return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "true", "1", "yes", "是", "對" -> true
            "false", "0", "no", "否", "不" -> false
            else -> throw BadArg("「$k」要是 true 或 false。")
        }
    }

    fun bool(k: String, default: Boolean = false): Boolean = boolOrNull(k) ?: default

    fun <E : Enum<E>> enumOrNull(k: String, what: String, values: Array<E>): E? {
        val s = strOrNull(k) ?: return null
        return values.firstOrNull { it.name.equals(s, ignoreCase = true) }
            ?: throw BadArg("「$what」（$k）只能是 ${values.joinToString("、") { it.name.lowercase() }}，收到「$s」。")
    }

    fun <E : Enum<E>> enum(k: String, what: String, values: Array<E>): E =
        enumOrNull(k, what, values) ?: throw BadArg("缺少「$what」（$k）。")

    fun objects(k: String): List<JsonObject> = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun strings(k: String): List<String> =
        (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

    fun ints(k: String): List<Int> =
        (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }.orEmpty()
}
