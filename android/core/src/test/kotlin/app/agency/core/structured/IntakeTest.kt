package app.agency.core.structured

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeTest {
    private fun decode(json: String, owner: String): Decoded<Intake> =
        Intake.decode(LocalRepair.parse(json, Intake.DEFAULTS)!!, owner)

    @Test fun aMealKeepsTheOldValidationRanges() {
        val d = decode(
            """{"kind":"meal","items":[
                {"name":"雞腿便當","portion":20,"fried":true,"processed":false,"zero_cal":false,"kcal_low":900,"kcal_high":700,"protein_low":40,"protein_high":30},
                {"name":"紅茶","portion":1,"fried":false,"processed":false,"zero_cal":true,"kcal_low":9000,"kcal_high":null,"protein_low":null,"protein_high":null}]}""",
            "吃了雞腿便當和紅茶",
        )
        val i = d.value!!
        assertEquals("meal", i.kind)
        assertEquals(IntakeItem("雞腿便當", 1.0, fried = true, kcalLow = 700, kcalHigh = 900, proteinLow = 30.0, proteinHigh = 40.0), i.items[0])
        assertEquals(IntakeItem("紅茶", zeroCal = true), i.items[1])
        assertNull(i.weightKg)
    }

    @Test fun figuresThatMustBeHisAreKeptOnlyWhenHeWroteThem() {
        val json = """{"kind":"weight","weight_kg":79.2,"waist_cm":88,"label":{"name":"雞胸","kcal":117,"protein_g":24},
            "training":{"kind":"strength","rounds":3,"last_round_extra":2},"condition_score":4,"illness":false}"""
        val said = decode(json, "早上七十九點二，腰圍 88，雞胸標示 117 卡 24 克，肌力三輪做完最後一輪還能多兩下，狀態 4").value!!
        assertEquals(79.2, said.weightKg!!, 1e-9)
        assertEquals(88.0, said.waistCm!!, 1e-9)
        assertEquals(IntakeLabel("雞胸", 117, 24.0), said.label)
        assertEquals(IntakeTraining("strength", 3, 2), said.training)
        assertEquals(4, said.conditionScore)
        val invented = decode(json, "量好了").value!!
        assertNull(invented.weightKg)
        assertNull(invented.waistCm)
        assertEquals(IntakeLabel("雞胸", null, null), invented.label)
        assertEquals(IntakeTraining("strength", null, null), invented.training)
        assertNull(invented.conditionScore)
    }

    @Test fun theNewKindsAreAccepted() {
        for (k in listOf("water", "sign_in", "sign_out", "condition", "rest_day", "cheat_declare", "hungry_morning", "unwell", "mood", "discuss")) {
            assertEquals(k, decode("""{"kind":"$k"}""", "").value!!.kind)
        }
        assertEquals(2, decode("""{"kind":"water","water_half":2}""", "喝完一壺").value!!.waterHalf)
        assertNull(decode("""{"kind":"water","water_half":9}""", "").value!!.waterHalf)
    }

    @Test fun anUnknownKindOrANamelessItemIsAViolation() {
        val d = decode("""{"kind":"dinner","items":[{"portion":1}]}""", "")
        assertNull(d.value)
        assertEquals(listOf("kind", "items[0].name"), d.violations.map { it.field })
        assertTrue(decode("""{"kind":"training","training":{"kind":"yoga","rounds":null,"last_round_extra":null}}""", "").violations.any { it.field == "training.kind" })
    }

    @Test fun atMostTwelveItems() {
        val items = (1..15).joinToString(",") { """{"name":"菜$it"}""" }
        assertEquals(Intake.MAX_ITEMS, decode("""{"kind":"meal","items":[$items]}""", "").value!!.items.size)
    }

    @Test fun theSchemaIsStrict() {
        assertStrict(Intake.SCHEMA)
        assertStrict(Utterance.SCHEMA)
        assertStrict(NudgeLine.SCHEMA)
        // No limit the strict modes ignore or reject is sent: lengths and ranges are checked in Kotlin.
        val all = Intake.SCHEMA.toString() + Utterance.SCHEMA + NudgeLine.SCHEMA
        for (k in listOf("maxLength", "minLength", "maxItems", "minItems", "minimum", "maximum")) assertFalse(k, all.contains(k))
        assertTrue(Intake.SYSTEM.contains("json"))
    }

    /** Every object: all properties required, no additional properties (nested ones too). */
    private fun assertStrict(s: JsonObject) {
        if (s["type"]?.toString() == "\"object\"") {
            val props = s["properties"]!!.jsonObject
            val required = (s["required"] as JsonArray).map { it.toString().trim('"') }.toSet()
            assertEquals(props.keys, required)
            assertEquals("false", s["additionalProperties"].toString())
            props.values.forEach { assertStrict(it.jsonObject) }
        }
        (s["items"] as? JsonObject)?.let { assertStrict(it) }
        (s["anyOf"] as? JsonArray)?.forEach { assertStrict(it.jsonObject) }
    }
}
