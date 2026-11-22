package app.dshmobile.agency.data.entities

import app.agency.core.model.MessageRefs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The JSON columns of 1.2 (lists and message refs), encoded with kotlinx.serialization. */
internal object JsonCols {
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())
    private val doubleList = ListSerializer(Double.serializer())

    fun strings(list: List<String>): String = json.encodeToString(stringList, list)
    fun strings(text: String): List<String> = json.decodeFromString(stringList, text)
    fun doubles(list: List<Double>): String = json.encodeToString(doubleList, list)
    fun doubles(text: String): List<Double> = json.decodeFromString(doubleList, text)
    fun refs(refs: MessageRefs): String = json.encodeToString(MessageRefs.serializer(), refs)
    fun refs(text: String): MessageRefs = json.decodeFromString(MessageRefs.serializer(), text)
}
