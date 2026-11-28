package app.agency.core.rules

import app.agency.core.base.J
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Effort
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * What the import read from his document. [rules] is never saved by the import itself: he sees it on the confirmation screen
 * first. [problems] are in his language (values that were dropped, a document that was cut). [ok] is false when nothing could
 * be read at all (the call failed or the answer was not JSON); [rules] is then blank.
 */
data class ImportResult(val rules: Rules, val handbook: String, val problems: List<String>, val ok: Boolean = true)

/**
 * Turns his pasted requirements document into [Rules] plus a short handbook of soft knowledge, with one structured-output call.
 * The model copies; code checks: every value is range-checked here, an implausible one is dropped (left blank) and named in
 * [ImportResult.problems], so a misread number never becomes a rule silently.
 */
class RulesImport(private val llm: LlmClient) {
    suspend fun extract(text: String): ImportResult {
        val problems = ArrayList<String>()
        var doc = text.trim()
        if (doc.isEmpty()) return ImportResult(Rules(), "", listOf("文件是空的。"), ok = false)
        if (doc.length > MAX_CHARS) {
            doc = doc.take(MAX_CHARS)
            problems += "文件太長，只讀了前 $MAX_CHARS 個字；後面的部分請之後到 設定 → 公司規章 補。"
        }
        // The shape goes into the prompt as well as into the provider's schema field: one provider enforces the schema, the
        // other only promises "a JSON object", and a provider may refuse a schema it does not like (then the second try, without
        // the schema field, still knows the shape).
        val ask = "下面是他的文件，請照說明抽出來。\n\n輸出的 JSON 結構（每個欄位都要有）：\n$SCHEMA\n\n他的文件：\n```\n$doc\n```"
        fun request(output: Output) = ChatRequest(
            system = SYSTEM,
            turns = listOf(Turn.User(ask)),
            purpose = Purpose.INTAKE,
            maxTokens = 8000,
            output = output,
            effort = Effort.MEDIUM,
        )
        suspend fun call(output: Output): ChatResult? = try {
            llm.chat(request(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        var r = call(Output.Json(SCHEMA))
        // A request the provider would not take (for example a schema it rejects) is tried once more as plain text.
        if (r is ChatResult.Failed && r.fail == Fail.BAD_RESPONSE) r = call(Output.Text)
        val body = (r as? ChatResult.Ok)?.text?.trim()
            ?: return ImportResult(
                Rules(), "",
                listOf(why((r as? ChatResult.Failed)?.fail) + ((r as? ChatResult.Failed)?.detail?.let { "（$it）" } ?: "")),
                ok = false,
            )
        return parse(body, problems)
    }

    companion object {
        const val MAX_CHARS = 24_000
        const val HANDBOOK_MAX = 1500

        /** Why nothing was read, in his language, so he knows what to fix instead of only "try again". */
        fun why(fail: Fail?): String = "這次沒有讀到。" + when (fail) {
            Fail.OFFLINE -> "連不上，或等太久沒有回應。確認網路後再試一次。"
            Fail.NO_KEY -> "還沒有可以用的金鑰，請回上一步貼上金鑰。"
            Fail.KEY_REJECTED -> "金鑰被拒絕了：可能貼錯、貼到另一家的金鑰，或帳戶沒有餘額。請回上一步檢查。"
            Fail.CAP -> "已經到花費上限了。"
            Fail.RATE -> "對方說太頻繁了，等一分鐘再試。"
            Fail.SERVER -> "模型那邊暫時有問題，等一下再試。"
            Fail.REFUSED -> "模型拒絕讀這份文件，換個寫法再試。"
            Fail.BLOCKED_HOST -> "這個連線不在允許的清單裡。"
            Fail.NO_SETUP, Fail.NO_VISION, Fail.BAD_RESPONSE, null -> "模型的回應讀不懂，請再試一次。"
        }

        /** Reads a model answer leniently; exposed for tests. */
        fun parse(raw: String, problems: MutableList<String> = ArrayList()): ImportResult {
            val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val root = runCatching { J.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return ImportResult(Rules(), "", problems + "讀到的結果格式不對，請再試一次。", ok = false)
            val rulesJson = root["rules"] as? JsonObject ?: root
            val rules = decode(rulesJson, problems)
            val handbook = ((root["handbook"] as? JsonPrimitive)?.content ?: "").trim().let {
                if (it.length > HANDBOOK_MAX) { problems += "手冊太長，只留前 $HANDBOOK_MAX 個字。"; it.take(HANDBOOK_MAX) } else it
            }
            return ImportResult(RulesValidate.clean(rules, problems), handbook, problems)
        }

        /** Whole object first; if one section does not fit, sections one by one so the rest survives. */
        private fun decode(o: JsonObject, problems: MutableList<String>): Rules {
            runCatching { return J.decodeFromJsonElement<Rules>(o) }
            var acc = J.encodeToJsonElement(Rules()).jsonObject
            for ((k, v) in o) {
                val next = JsonObject(acc + (k to v))
                if (runCatching { J.decodeFromJsonElement<Rules>(next) }.isSuccess) acc = next
                else problems += "「${SECTION_NAMES[k] ?: k}」讀不懂，先留空。"
            }
            return J.decodeFromJsonElement(acc)
        }

        private val SECTION_NAMES = mapOf(
            "classDay" to "上課日時間", "freeDay" to "假日時間", "diet" to "飲食", "foods" to "常吃的食物", "water" to "喝水",
            "training" to "訓練", "ride" to "跑單", "body" to "體重", "sleep" to "睡眠", "study" to "學習", "money" to "記帳",
            "grooming" to "儀容", "classWeekdays" to "上課日",
        )

        // ---- schema (hand-written to mirror the Rules data classes; strict: every property required, no extras) ----

        private fun t(type: String, desc: String): JsonObject =
            JsonObject(mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(desc)))

        private fun s(desc: String) = t("string", desc)
        private fun i(desc: String) = t("integer", desc)
        private fun n(desc: String) = t("number", desc)
        private fun arr(desc: String, items: JsonObject) =
            JsonObject(mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive(desc), "items" to items))

        private fun obj(vararg p: Pair<String, JsonObject>) = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(p.toMap()),
                "required" to JsonArray(p.map { JsonPrimitive(it.first) }),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )

        private fun time(desc: String) = s("$desc，HH:mm；沒寫就空字串")

        private val dayTimes = obj(
            "wake" to time("最晚起床／簽到時間"), "firstMeal" to time("第一餐時間"), "classEnd" to time("下課時間（假日空字串）"),
            "walk" to time("快走時間"), "dinner" to time("晚餐時間"), "leaveBy" to time("最晚出門跑單時間"),
            "nightMeal" to time("宵夜時間"), "bedtime" to time("簽退／睡覺時間（過了午夜照寫，例如 02:30）"),
            "bedtimeGoal" to time("睡覺時間最後要提早到的目標"),
        )

        val SCHEMA: JsonObject = obj(
            "rules" to obj(
                "callName" to s("小葵怎麼叫他"),
                "stageName" to s("藝名"),
                "memberColor" to s("代表色 #RRGGBB；沒有就空字串"),
                "classWeekdays" to arr("有課的星期（1＝星期一 … 7＝星期日）", i("星期")),
                "classDay" to dayTimes,
                "freeDay" to dayTimes,
                "diet" to obj(
                    "kcal" to i("每日熱量（大卡）"), "proteinG" to i("每日蛋白質（克）"), "minKcal" to i("每日最少熱量"),
                    "mealCapKcal" to i("單餐上限"), "firstMealKcal" to i("第一餐預定熱量"), "dinnerKcal" to i("晚餐預定熱量"),
                    "nightMealKcal" to i("宵夜預定熱量"),
                    "banned" to arr("禁止的食物類別", obj("category" to s("類別名"), "words" to arr("這類的例子字詞", s("字詞")))),
                    "allowed" to arr("明確允許的食物名稱", s("名稱")),
                    "zeroCalDrinksPerWeek" to i("無熱量飲料每週次數上限"),
                    "caffeineCutoff" to time("咖啡因最晚時間"),
                    "freeMealFrom" to s("自由餐從哪天開始 YYYY-MM-DD；沒有就空字串"),
                    "freeMealsPerWeek" to i("每週自由餐次數"),
                    "estimateMarginPct" to i("估算熱量要加幾 %（沒寫就 10）"),
                ),
                "foods" to arr("他的食物表", obj(
                    "name" to s("名稱"), "kcal" to i("熱量"), "proteinG" to n("蛋白質克"), "aliases" to arr("別名", s("別名")),
                    "note" to s("備註；沒有就空字串"),
                )),
                "water" to obj(
                    "bottleMl" to i("水壺容量毫升"), "bottlesAtStart" to i("一開始每天幾壺"), "bottlesGoal" to i("最後每天幾壺"),
                    "rampAfterDays" to i("幾天後開始加量（沒寫就 14）"),
                ),
                "training" to obj(
                    "coreMinutes" to i("核心分鐘"), "strengthRounds" to i("肌力輪數"), "pushUps" to i("每輪伏地挺身下數"),
                    "squats" to i("每輪深蹲下數"), "plankSeconds" to i("每輪棒式秒數"), "stretchMinutes" to i("收操伸展分鐘"),
                    "morningStretchMinutes" to i("起床伸展分鐘"), "vocalBasicsMinutes" to i("聲樂基本功分鐘"),
                    "cardioMinutes" to i("體能分鐘"), "cardioPerWeek" to i("體能每週次數"), "postureMinutes" to i("靠牆站姿分鐘"),
                    "walkMinutes" to i("快走分鐘"), "danceMinutes" to i("舞蹈分鐘"), "vocalMinutes" to i("聲樂練習分鐘"),
                    "expressionMinutes" to i("表情鏡頭分鐘"), "expressionPerWeek" to i("表情鏡頭每週次數"),
                ),
                "ride" to obj("daysPerWeek" to i("每週跑單天數"), "dailyIncome" to i("每天收入目標元"), "weeklyIncome" to i("每週收入目標元")),
                "body" to obj(
                    "startKg" to n("起始體重"), "goalKg" to n("目標體重"), "pauseBelowKg" to n("低於多少先暫停檢討"),
                    "neverBelowKg" to n("絕不能低於"), "weighWeekday" to i("量體重的星期 1–7；沒寫就 0"),
                    "fastLossKgPerWeek" to n("每週瘦超過多少算太快"),
                ),
                "sleep" to obj("shiftMinutesPerWeek" to i("睡覺時間每週提早幾分鐘")),
                "study" to obj(
                    "daily" to s("每天上的科目"), "twiceWeekly" to arr("每週兩次的科目", s("科目")),
                    "rotation" to arr("輪流的科目", s("科目")), "maxPerDay" to i("每天最多幾堂"), "extraPerDay" to i("加課每天最多幾堂"),
                    "quizQuestions" to i("測驗題數（沒寫就 3）"), "quizPass" to i("對幾題算通過（沒寫就 2）"),
                    "reviewAfterDays" to i("幾天後複習（沒寫就 2）"), "examWeekPerDay" to i("考試週每天幾堂（沒寫就 1）"),
                    "examWeeksPerTerm" to i("考試週每學期幾次（沒寫就 2）"),
                ),
                "money" to obj(
                    "livingPct" to i("生活費占幾 %"), "funPct" to i("娛樂占幾 %"), "foodPerDayMin" to i("每天伙食最少元"),
                    "foodPerDayMax" to i("每天伙食最多元"),
                ),
                "grooming" to arr("儀容項目", obj("name" to s("項目"), "everyDays" to i("每幾天一次"))),
                "level" to obj(
                    "passPct" to i("過關完成率（沒寫就 90）"), "weeksToB" to i("升 B 的週數（沒寫就 4）"),
                    "weeksToA" to i("升 A 的週數（沒寫就 12）"), "talkAtViolations" to i("違規幾次要談（沒寫就 3）"),
                ),
            ),
            "handbook" to s("軟知識手冊，最多 1500 字"),
        )

        private const val SYSTEM = "你替一間經紀公司把練習生自己寫的要求文件整理成固定格式的 JSON。只回 JSON。\n" +
            "規則：\n" +
            "1. 數字照文件原樣抄，不要換算、不要四捨五入、不要自己估。文件沒寫的欄位留空白預設值：字串空字串、數字 0、清單空清單" +
            "（欄位說明寫了「沒寫就 X」的，用那個 X）。絕對不要編造文件裡沒有的值。\n" +
            "2. 時間一律寫成 HH:mm（24 小時制，例如 07:00、19:30）。過了午夜的時間照文件寫，例如半夜兩點半寫 02:30，不要寫 26:30。\n" +
            "3. 星期用 1～7：1 是星期一，7 是星期日。\n" +
            "4. 他的食物表（食物名稱、熱量、蛋白質、別名）放 foods。禁止的食物分類放 diet.banned，每類附上文件裡的例子字詞；" +
            "文件明確說可以吃的放 diet.allowed。\n" +
            "5. handbook：最多 1500 字的軟知識——口味、點餐的小技巧、他在意的事、偏好、他希望經紀人怎麼跟他說話。" +
            "屬於上面欄位的數字和時間不要寫進 handbook。用繁體中文。\n" +
            "6. 稱呼（callName）用文件裡他希望被叫的名字；沒寫就空字串。"
    }
}

/** Range checks shared by the import and the settings page: implausible values are blanked and named. */
object RulesValidate {
    private val HM = Regex("""^(\d{1,2})[:：](\d{2})$""")

    /** "7:5" stays bad; "7:05" → "07:05"; a blank stays blank; anything unreadable → null. */
    fun time(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val m = HM.find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return "%02d:%02d".format(h, mi)
    }

    fun clean(r: Rules, problems: MutableList<String>): Rules {
        fun t(v: String, name: String): String = time(v) ?: run { problems += "「$name」的時間「$v」看不懂，先留空。"; "" }
        fun times(d: DayTimes, p: String) = DayTimes(
            wake = t(d.wake, "${p}簽到"), firstMeal = t(d.firstMeal, "${p}第一餐"), classEnd = t(d.classEnd, "${p}下課"),
            walk = t(d.walk, "${p}快走"), dinner = t(d.dinner, "${p}晚餐"), leaveBy = t(d.leaveBy, "${p}出門"),
            nightMeal = t(d.nightMeal, "${p}宵夜"), bedtime = t(d.bedtime, "${p}睡覺"), bedtimeGoal = t(d.bedtimeGoal, "${p}目標睡覺"),
        )
        fun kcal(v: Int, name: String, range: IntRange): Int =
            if (v == 0 || v in range) v else { problems += "「$name」$v 大卡不合理，先留空。"; 0 }
        fun nonNeg(v: Int, name: String): Int = if (v >= 0) v else { problems += "「$name」不能是負的，先留空。"; 0 }
        fun kg(v: Double, name: String): Double =
            if (v == 0.0 || v in 30.0..250.0) v else { problems += "「$name」$v 公斤不合理，先留空。"; 0.0 }

        val badDays = r.classWeekdays.filter { it !in 1..7 }
        if (badDays.isNotEmpty()) problems += "上課日裡有不是 1～7 的星期（${badDays.joinToString("、")}），已拿掉。"
        val d = r.diet
        val diet = d.copy(
            kcal = kcal(d.kcal, "每日熱量", 800..5000),
            proteinG = if (d.proteinG in 0..400) d.proteinG else { problems += "「蛋白質」${d.proteinG} 克不合理，先留空。"; 0 },
            minKcal = kcal(d.minKcal, "最少熱量", 500..5000),
            mealCapKcal = kcal(d.mealCapKcal, "單餐上限", 100..5000),
            firstMealKcal = kcal(d.firstMealKcal, "第一餐熱量", 50..5000),
            dinnerKcal = kcal(d.dinnerKcal, "晚餐熱量", 50..5000),
            nightMealKcal = kcal(d.nightMealKcal, "宵夜熱量", 50..5000),
            caffeineCutoff = t(d.caffeineCutoff, "咖啡因"),
            zeroCalDrinksPerWeek = nonNeg(d.zeroCalDrinksPerWeek, "無熱量飲料次數"),
            freeMealsPerWeek = nonNeg(d.freeMealsPerWeek, "自由餐次數"),
            freeMealFrom = d.freeMealFrom.takeIf { it.isBlank() || runCatching { java.time.LocalDate.parse(it) }.isSuccess }
                ?: run { problems += "自由餐開始日「${d.freeMealFrom}」看不懂，先留空。"; "" },
            estimateMarginPct = if (d.estimateMarginPct in 0..100) d.estimateMarginPct else 10,
            banned = d.banned.filter { it.category.isNotBlank() },
            allowed = d.allowed.filter { it.isNotBlank() },
        )
        val foods = r.foods.filter { f ->
            val ok = f.name.isNotBlank() && f.kcal in 0..5000 && f.proteinG in 0.0..300.0
            if (!ok) problems += "食物「${f.name}」的數字不合理，沒有收進去。"
            ok
        }
        val tr = r.training
        val training = TrainingRule(
            coreMinutes = nonNeg(tr.coreMinutes, "核心"), strengthRounds = nonNeg(tr.strengthRounds, "肌力輪數"),
            pushUps = nonNeg(tr.pushUps, "伏地挺身"), squats = nonNeg(tr.squats, "深蹲"), plankSeconds = nonNeg(tr.plankSeconds, "棒式"),
            stretchMinutes = nonNeg(tr.stretchMinutes, "收操伸展"), morningStretchMinutes = nonNeg(tr.morningStretchMinutes, "起床伸展"),
            vocalBasicsMinutes = nonNeg(tr.vocalBasicsMinutes, "聲樂基本功"), cardioMinutes = nonNeg(tr.cardioMinutes, "體能"),
            cardioPerWeek = nonNeg(tr.cardioPerWeek, "體能次數").coerceAtMost(7), postureMinutes = nonNeg(tr.postureMinutes, "靠牆站姿"),
            walkMinutes = nonNeg(tr.walkMinutes, "快走"), danceMinutes = nonNeg(tr.danceMinutes, "舞蹈"),
            vocalMinutes = nonNeg(tr.vocalMinutes, "聲樂練習"), expressionMinutes = nonNeg(tr.expressionMinutes, "表情鏡頭"),
            expressionPerWeek = nonNeg(tr.expressionPerWeek, "表情鏡頭次數").coerceAtMost(7),
        )
        val w = r.water
        val water = w.copy(
            bottleMl = if (w.bottleMl == 0 || w.bottleMl in 100..5000) w.bottleMl else { problems += "水壺容量 ${w.bottleMl} 毫升不合理，先留空。"; 0 },
            bottlesAtStart = nonNeg(w.bottlesAtStart, "起始壺數"), bottlesGoal = nonNeg(w.bottlesGoal, "目標壺數"),
            rampAfterDays = if (w.rampAfterDays > 0) w.rampAfterDays else 14,
        )
        val b = r.body
        val body = b.copy(
            startKg = kg(b.startKg, "起始體重"), goalKg = kg(b.goalKg, "目標體重"), pauseBelowKg = kg(b.pauseBelowKg, "暫停體重"),
            neverBelowKg = kg(b.neverBelowKg, "最低體重"),
            weighWeekday = if (b.weighWeekday in 0..7) b.weighWeekday else { problems += "量體重的星期 ${b.weighWeekday} 不對，先留空。"; 0 },
            fastLossKgPerWeek = if (b.fastLossKgPerWeek in 0.0..5.0) b.fastLossKgPerWeek else 0.0,
        )
        val s = r.study
        val study = s.copy(
            maxPerDay = nonNeg(s.maxPerDay, "每天堂數"), extraPerDay = nonNeg(s.extraPerDay, "加課堂數"),
            quizQuestions = if (s.quizQuestions in 1..10) s.quizQuestions else 3,
            quizPass = if (s.quizPass in 1..10) s.quizPass else 2,
            reviewAfterDays = if (s.reviewAfterDays in 1..60) s.reviewAfterDays else 2,
            examWeekPerDay = if (s.examWeekPerDay >= 0) s.examWeekPerDay else 1,
            examWeeksPerTerm = if (s.examWeeksPerTerm >= 0) s.examWeeksPerTerm else 2,
            twiceWeekly = s.twiceWeekly.filter { it.isNotBlank() }, rotation = s.rotation.filter { it.isNotBlank() },
        )
        val color = r.memberColor.trim().takeIf { it.isEmpty() || Regex("^#[0-9A-Fa-f]{6}$").matches(it) }
            ?: run { problems += "代表色「${r.memberColor}」看不懂，先留空。"; "" }
        val l = r.level
        val level = LevelRule(
            passPct = if (l.passPct in 1..100) l.passPct else 90, weeksToB = if (l.weeksToB > 0) l.weeksToB else 4,
            weeksToA = if (l.weeksToA > 0) l.weeksToA else 12, talkAtViolations = if (l.talkAtViolations > 0) l.talkAtViolations else 3,
        )
        return r.copy(
            callName = r.callName.trim(), stageName = r.stageName.trim(), memberColor = color,
            classWeekdays = r.classWeekdays.filter { it in 1..7 }.distinct().sorted(),
            classDay = times(r.classDay, "上課日"), freeDay = times(r.freeDay, "假日"),
            diet = diet, foods = foods, water = water, training = training,
            ride = RideRule(nonNeg(r.ride.daysPerWeek, "跑單天數").coerceAtMost(7), nonNeg(r.ride.dailyIncome, "每天收入"), nonNeg(r.ride.weeklyIncome, "每週收入")),
            body = body, sleep = SleepRule(nonNeg(r.sleep.shiftMinutesPerWeek, "每週提早分鐘")), study = study,
            money = r.money.copy(
                livingPct = r.money.livingPct.coerceIn(0, 100), funPct = r.money.funPct.coerceIn(0, 100),
                foodPerDayMin = nonNeg(r.money.foodPerDayMin, "伙食最少"), foodPerDayMax = nonNeg(r.money.foodPerDayMax, "伙食最多"),
            ),
            grooming = r.grooming.filter { it.name.isNotBlank() && it.everyDays > 0 },
            level = level,
        )
    }
}
