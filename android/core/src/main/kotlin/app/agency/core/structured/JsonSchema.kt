package app.agency.core.structured

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A small builder for strict JSON schemas: every property is required, `additionalProperties` is false, a field that may be
 * empty is `anyOf [type, null]`. Length, range and item-count limits are left out on purpose (the providers' strict modes do
 * not enforce them; some reject them), so every limit is checked in Kotlin by the spec's decoder.
 */
object JsonSchema {
    fun obj(vararg props: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", buildJsonArray { props.forEach { add(JsonPrimitive(it.first)) } })
        put("additionalProperties", false)
    }

    fun str(): JsonObject = type("string")
    fun int(): JsonObject = type("integer")
    fun num(): JsonObject = type("number")
    fun bool(): JsonObject = type("boolean")

    fun enum(values: Collection<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

    fun arr(items: JsonElement): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    fun nullable(schema: JsonElement): JsonObject = buildJsonObject {
        put("anyOf", buildJsonArray { add(schema); add(type("null")) })
    }

    private fun type(t: String) = buildJsonObject { put("type", t) }
}
