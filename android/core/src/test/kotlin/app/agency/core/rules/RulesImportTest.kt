package app.agency.core.rules

import app.agency.core.llm.Effort
import app.agency.core.llm.Fail
import app.agency.core.llm.Output
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.fails
import app.agency.core.llm.ScriptedLlm.Companion.says
import app.agency.core.llm.Turn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesImportTest {
    /** Invented values only. */
    private val answer = """
        {"rules":{"callName":"小測","stageName":"","memberColor":"#123456","classWeekdays":[1,3,5,9],
          "classDay":{"wake":"7:30","firstMeal":"12:00","classEnd":"","walk":"","dinner":"18:30","leaveBy":"19:10","nightMeal":"22:00","bedtime":"02:20","bedtimeGoal":"25:00"},
          "freeDay":{"wake":"09:00","firstMeal":"","classEnd":"","walk":"","dinner":"","leaveBy":"","nightMeal":"","bedtime":"","bedtimeGoal":""},
          "diet":{"kcal":2100,"proteinG":110,"minKcal":9000,"mealCapKcal":850,"firstMealKcal":0,"dinnerKcal":0,"nightMealKcal":0,
            "banned":[{"category":"甜點","words":["蛋糕","布丁"]}],"allowed":["無糖豆漿"],"zeroCalDrinksPerWeek":2,"caffeineCutoff":"14:00",
            "freeMealFrom":"","freeMealsPerWeek":0,"estimateMarginPct":10},
          "foods":[{"name":"測試飯糰","kcal":250,"proteinG":6,"aliases":["飯糰"],"note":""},{"name":"壞資料","kcal":-5,"proteinG":1,"aliases":[],"note":""}],
          "water":{"bottleMl":750,"bottlesAtStart":1,"bottlesGoal":2,"rampAfterDays":14},
          "training":{"coreMinutes":5,"strengthRounds":3,"pushUps":12,"squats":-3,"plankSeconds":30,"stretchMinutes":0,"morningStretchMinutes":0,
            "vocalBasicsMinutes":0,"cardioMinutes":0,"cardioPerWeek":0,"postureMinutes":0,"walkMinutes":0,"danceMinutes":0,"vocalMinutes":0,
            "expressionMinutes":0,"expressionPerWeek":0},
          "ride":{"daysPerWeek":5,"dailyIncome":0,"weeklyIncome":5000},
          "body":{"startKg":0,"goalKg":0,"pauseBelowKg":0,"neverBelowKg":0,"weighWeekday":2,"fastLossKgPerWeek":0},
          "sleep":{"shiftMinutesPerWeek":0},
          "study":{"daily":"語言","twiceWeekly":[],"rotation":[],"maxPerDay":2,"extraPerDay":0,"quizQuestions":3,"quizPass":2,"reviewAfterDays":2,"examWeekPerDay":1,"examWeeksPerTerm":2},
          "money":{"livingPct":0,"funPct":0,"foodPerDayMin":0,"foodPerDayMax":0},
          "grooming":[],"level":{"passPct":90,"weeksToB":4,"weeksToA":12,"talkAtViolations":3}},
         "handbook":"他喜歡被直接提醒，不喜歡長篇大論。"}
    """.trimIndent()

    @Test fun oneStructuredCallCopiesTheValuesAndChecksThem() = runBlocking {
        val llm = ScriptedLlm(says(answer))
        val r = RulesImport(llm).extract("（測試文件）每天 2100 大卡……")
        assertTrue(r.ok)
        val req = llm.requests.single()
        assertTrue(req.output is Output.Json)
        assertEquals(Effort.MEDIUM, req.effort)
        assertEquals("小測", r.rules.callName)
        assertEquals(2100, r.rules.diet.kcal)
        assertEquals("07:30", r.rules.classDay.wake)
        assertEquals("02:20", r.rules.classDay.bedtime)
        assertEquals("", r.rules.classDay.bedtimeGoal)
        assertEquals(listOf(1, 3, 5), r.rules.classWeekdays)
        assertEquals(0, r.rules.diet.minKcal)
        assertEquals(0, r.rules.training.squats)
        assertEquals(listOf("測試飯糰"), r.rules.foods.map { it.name })
        assertEquals("甜點", r.rules.diet.banned.single().category)
        assertEquals("他喜歡被直接提醒，不喜歡長篇大論。", r.handbook)
        listOf("最少熱量", "目標睡覺", "上課日裡", "壞資料", "深蹲").forEach { w -> assertTrue(w, r.problems.any { it.contains(w) }) }
        assertTrue(r.problems.all { p -> p.none { it in 'A'..'Z' || it in 'a'..'z' } })
    }

    @Test fun malformedAnswersAndFailedCallsAreNotRules() = runBlocking {
        val bad = RulesImport(ScriptedLlm(says("這不是 JSON"))).extract("文件")
        assertFalse(bad.ok)
        assertEquals(Rules(), bad.rules)
        val failed = RulesImport(ScriptedLlm(fails(Fail.OFFLINE))).extract("文件")
        assertFalse(failed.ok)
        assertTrue(failed.problems.isNotEmpty())
        assertFalse(RulesImport(ScriptedLlm()).extract("   ").ok)
    }

    @Test fun aSectionOfTheWrongShapeIsDroppedAloneAndAFencedAnswerStillReads() {
        val r = RulesImport.parse("```json\n{\"rules\":{\"callName\":\"小測\",\"diet\":\"很多\",\"water\":{\"bottleMl\":600}},\"handbook\":\"\"}\n```")
        assertTrue(r.ok)
        assertEquals("小測", r.rules.callName)
        assertEquals(600, r.rules.water.bottleMl)
        assertTrue(r.problems.any { it.contains("飲食") })
    }

    @Test fun aLongDocumentIsCutAndSaysSo() = runBlocking {
        val llm = ScriptedLlm(says("""{"rules":{},"handbook":""}"""))
        val r = RulesImport(llm).extract("字".repeat(30_000))
        assertTrue(r.problems.any { it.contains("太長") })
        val sent = (llm.requests.single().turns.single() as Turn.User).text
        // The document part is cut to 24 000 characters; the rest of the turn is the instruction and the shape.
        assertTrue(sent.length < 24_000 + 12_000)
        assertTrue(sent.contains("字".repeat(24_000)))
        assertFalse(sent.contains("字".repeat(24_001)))
    }

    @Test fun aFailureSaysWhySoHeKnowsWhatToFix() = runBlocking {
        val rejected = RulesImport(ScriptedLlm(fails(Fail.KEY_REJECTED))).extract("文件")
        assertFalse(rejected.ok)
        assertTrue(rejected.problems.single().contains("金鑰被拒絕"))
        val noKey = RulesImport(ScriptedLlm(fails(Fail.NO_KEY))).extract("文件")
        assertTrue(noKey.problems.single().contains("金鑰"))
        val offline = RulesImport(ScriptedLlm(fails(Fail.OFFLINE))).extract("文件")
        assertTrue(offline.problems.single().contains("連不上"))
    }

    @Test fun aRequestTheProviderWouldNotTakeIsTriedOnceMoreAsPlainText() = runBlocking {
        val llm = ScriptedLlm(fails(Fail.BAD_RESPONSE), says("""{"rules":{"callName":"小測"},"handbook":""}"""))
        val r = RulesImport(llm).extract("文件")
        assertTrue(r.ok)
        assertEquals("小測", r.rules.callName)
        assertEquals(2, llm.requests.size)
        assertTrue(llm.requests[0].output is Output.Json)
        assertTrue(llm.requests[1].output is Output.Text)
        // The shape is in the prompt itself, so the plain-text try (and a provider without schemas) still knows the fields.
        val sent = (llm.requests[1].turns.single() as Turn.User).text
        listOf("callName", "classWeekdays", "foods", "handbook").forEach { assertTrue(it, sent.contains(it)) }
    }

    @Test fun theSchemaIsStrictAllTheWayDown() {
        fun walk(o: JsonObject, path: String) {
            if (o["type"]?.jsonPrimitive?.content == "object") {
                val props = (o["properties"] as JsonObject)
                assertEquals(path, props.keys, (o["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
                assertEquals(path, JsonPrimitive(false), o["additionalProperties"])
                props.forEach { (k, v) -> walk(v as JsonObject, "$path.$k") }
            }
            (o["items"] as? JsonObject)?.let { walk(it, "$path[]") }
        }
        walk(RulesImport.SCHEMA, "root")
        // Every Rules field has a place in the schema.
        val rulesProps = ((RulesImport.SCHEMA["properties"] as JsonObject)["rules"] as JsonObject)["properties"] as JsonObject
        val fields = app.agency.core.base.J.encodeToJsonElement(Rules.serializer(), Rules()) as JsonObject
        assertEquals(fields.keys, rulesProps.keys)
    }
}

class RulesTextTest {
    @Test fun rendersOnlyWhatIsSetGroupedWithUnitsNextToTheDigits() {
        val t = RulesText.render(Fixtures.rules())
        listOf("【作息】", "【飲食】", "【常吃的食物】", "【喝水】", "【訓練】", "【跑單】", "【體重】", "【學習】", "【記帳】", "【儀容】")
            .forEach { assertTrue(it, t.contains(it)) }
        assertTrue(t.contains("每日 2100 大卡"))
        assertTrue(t.contains("睡覺 0:50"))
        assertTrue(t.contains("肌力 4 輪：伏地挺身 12 下、深蹲 18 下、棒式 50 秒"))
        assertTrue(t.contains("上課日：星期一、星期二"))
        // Blank rules show only the app's own defaults (estimate margin, quiz and review settings), nothing of his.
        val blank = RulesText.render(Rules())
        assertFalse(blank.contains("大卡") || blank.contains(":") || blank.contains("作息") || blank.contains("訓練"))
        assertTrue(t.none { it in 'A'..'Z' && it != 'S' && it != 'T' && it != 'A' && it != 'R' })
    }

    @Test fun theEffectiveTrainingIsWhatIsShown() {
        val r = Fixtures.rules()
        val t = RulesText.render(r, r.training.copy(pushUps = 16))
        assertTrue(t.contains("伏地挺身 16 下"))
    }
}

class RulesEditTest {
    private fun field(r: Rules, key: String) = RulesEdit.groups(r).flatMap { it.fields }.first { it.key == key }

    @Test fun everyFieldReadsAndWritesItsValue() {
        val r = Fixtures.rules()
        val fields = RulesEdit.groups(r).flatMap { it.fields }
        assertTrue(fields.size > 60)
        assertEquals(fields.size, fields.map { it.key }.toSet().size)
        // Writing each field's own text back changes nothing.
        for (f in fields) {
            val res = f.set(r, f.value)
            assertTrue("${f.key}: ${(res as? EditResult.Bad)?.message}", res is EditResult.Ok)
            assertEquals(f.key, r, (res as EditResult.Ok).rules)
        }
        assertTrue(fields.all { it.label.none { c -> c in 'A'..'Z' || c in 'a'..'z' } })
    }

    @Test fun badTextIsRefusedWithAReason() {
        val r = Fixtures.rules()
        assertTrue(RulesEdit.set(r, "diet.kcal", "abc") is EditResult.Bad)
        assertTrue(RulesEdit.set(r, "diet.kcal", "100") is EditResult.Bad)
        assertEquals(2250, (RulesEdit.set(r, "diet.kcal", "2250") as EditResult.Ok).rules.diet.kcal)
        assertEquals(0, (RulesEdit.set(r, "diet.kcal", "") as EditResult.Ok).rules.diet.kcal)
        assertEquals("08:05", (RulesEdit.set(r, "classDay.wake", "8:05") as EditResult.Ok).rules.classDay.wake)
        assertTrue(RulesEdit.set(r, "classDay.wake", "八點") is EditResult.Bad)
        assertEquals(listOf(1, 3), (RulesEdit.set(r, "classWeekdays", "一、三") as EditResult.Ok).rules.classWeekdays)
        assertTrue(RulesEdit.set(r, "classWeekdays", "八") is EditResult.Bad)
        val foods = (RulesEdit.set(r, "foods", "測試麵 500 20 麵、湯麵\n測試蛋 70 6") as EditResult.Ok).rules.foods
        assertEquals(listOf("麵", "湯麵"), foods.first().aliases)
        assertTrue(RulesEdit.set(r, "foods", "沒有數字") is EditResult.Bad)
        assertEquals(2, (RulesEdit.set(r, "diet.banned", "甜點：蛋糕、布丁\n油炸：炸") as EditResult.Ok).rules.diet.banned.size)
        assertTrue(RulesEdit.set(r, "nope", "1") is EditResult.Bad)
        assertEquals("星期三", field(r, "body.weighWeekday").value)
    }
}
