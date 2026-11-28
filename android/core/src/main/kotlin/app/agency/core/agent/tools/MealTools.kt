package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.Moment
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.day.Nutrition
import app.agency.core.stage.Feature
import app.agency.core.store.Entry
import app.agency.core.store.FoodSource
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealItem
import app.agency.core.store.MealSlot
import app.agency.core.store.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object Meals {
    val itemSchema: JsonObject = S.obj(
        "name" to S.str("食物名稱，照他說的或包裝上的寫"),
        "kcal" to S.int("這一項的熱量（大卡）：標籤上的數字，或你嚴格的估計；他的食物表裡有的，程式會用表上的數字"),
        "protein_g" to S.num("這一項的蛋白質（克）"),
    )

    fun slotWord(s: MealSlot) = when (s) {
        MealSlot.FIRST -> "第一餐"
        MealSlot.DINNER -> "晚餐"
        MealSlot.NIGHT -> "宵夜"
        MealSlot.EXTRA -> "點心"
    }

    /** Reads and resolves the items; an empty or unreadable list is a [BadArg]. */
    fun items(a: Args, ctx: ToolCtx, source: FoodSource): List<MealItem> {
        val raw = a.objects("items")
        if (raw.isEmpty()) throw BadArg("items 不能是空的：每樣吃的東西一項，各有 name、kcal、protein_g。")
        return raw.mapIndexed { i, o ->
            val x = Args(o)
            val name = x.strOrNull("name") ?: throw BadArg("第 ${i + 1} 項沒有名稱。")
            val kcal = x.intOrNull("kcal", "熱量", 0..5000) ?: throw BadArg("「$name」沒有熱量（kcal）。不確定就嚴格估一個。")
            val protein = x.numOrNull("protein_g", "蛋白質", 0.0, 300.0) ?: 0.0
            Nutrition.resolve(ctx.rules, name, kcal, protein, source)
        }
    }

    fun read(e: Entry?): Meal? = if (e == null || e.kind != Kind.MEAL) null else runCatching { e.read<Meal>() }.getOrNull()

    /** The card and the facts of one stored meal, with the day's fresh numbers. */
    fun result(ctx: ToolCtx, e: Entry, meal: Meal, fresh: List<String>, verb: String): ToolOut {
        val m = Moment.of(ctx.store, ctx.clock, ctx.rules)
        val d = ctx.rules.diet
        val kcal = meal.items.sumOf { it.kcal }
        val protein = meal.items.sumOf { it.proteinG }
        val warnings = ArrayList<String>()
        meal.items.filter { it.banned != null }.forEach { warnings += "${it.name} 是禁止的食物（${it.banned}）" }
        if (d.mealCapKcal > 0 && kcal > d.mealCapKcal) warnings += "這餐 $kcal 大卡，超過單餐上限 ${d.mealCapKcal} 大卡"
        if (d.kcal > 0 && m.state.kcal > d.kcal) warnings += "今天 ${m.state.kcal} 大卡，超過每日 ${d.kcal} 大卡"
        val estimated = meal.items.any { it.source == FoodSource.ESTIMATE }
        val needsPhoto = meal.photoId == null
        val title = slotWord(meal.slot) + "：" + meal.items.joinToString("、") { it.name }
        val card = Card.MealCard(
            entryId = e.id, title = title, kcal = kcal, proteinG = protein, dayKcal = m.state.kcal, dayTargetKcal = d.kcal,
            dayProteinG = m.state.proteinG, dayTargetProteinG = d.proteinG, estimated = estimated, needsPhoto = needsPhoto,
            warnings = warnings,
        )
        val facts = F.obj {
            put("entry_id", e.id)
            put("slot", slotWord(meal.slot))
            put("items", F.arr(meal.items.map { i ->
                F.obj {
                    put("name", i.name)
                    put("kcal", i.kcal)
                    put("protein_g", F.num(i.proteinG))
                    put("source", when (i.source) { FoodSource.TABLE -> "他的食物表"; FoodSource.LABEL -> "標籤"; FoodSource.ESTIMATE -> "嚴格估算" })
                    i.banned?.let { put("banned", it) }
                }
            }))
            put("meal_kcal", kcal)
            put("meal_protein_g", F.num(protein))
            put("photo", !needsPhoto)
            if (meal.photoLate) put("photo_late", true)
            if (needsPhoto) put("needs_photo", "還缺這餐的照片，計畫裡這餐要有照片才算完成；可以等一下用相簿補交")
            put("warnings", F.strs(warnings))
            put("new_violations", F.strs(fresh))
            put("note", "$verb：$title $kcal 大卡")
            put("today", F.today(m))
        }
        return ToolOut(facts, listOf(card))
    }

    /** A photo of this message not yet used by another meal today. */
    fun freePhoto(ctx: ToolCtx): Pair<String, Boolean>? {
        val day = F.day(ctx)
        val used = ctx.store.entries(day, Kind.MEAL).mapNotNull { read(it)?.photoId }.toSet()
        val i = ctx.photoIds.indexOfFirst { it !in used }
        if (i < 0) return null
        return ctx.photoIds[i] to (ctx.photoFromGallery.getOrNull(i) == true)
    }
}

internal class LogMealTool : BaseTool(
    "log_meal",
    "記一餐。slot：first 第一餐、dinner 晚餐、night 宵夜、extra 點心或額外的。items 每樣東西一項（name、kcal、protein_g）。" +
        "source：label 是數字從他確認過的標籤、收據、菜單讀來的；estimate 是你估的（程式會嚴格往上調）。他的食物表有的東西程式自動用表上的數字。" +
        "attach_photo：他這則訊息附了這餐的照片就 true。從照片讀出來的內容要先跟他確認才記。" +
        "回傳這餐和今天的熱量、蛋白質、剩多少、下一餐預算、是否缺照片、警告和新違規；餐點卡會自動顯示。",
    S.obj(
        "slot" to S.enum("哪一餐", listOf("first", "dinner", "night", "extra")),
        "items" to S.arr("吃了哪些東西", Meals.itemSchema),
        "source" to S.enum("數字的來源", listOf("label", "estimate")),
        "attach_photo" to S.bool("這則訊息附的照片是這一餐的"),
    ),
    Feature.MEALS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val slot = a.enum("slot", "哪一餐", MealSlot.values())
        val source = if (a.strOrNull("source")?.lowercase() == "label") FoodSource.LABEL else FoodSource.ESTIMATE
        val items = Meals.items(a, ctx, source)
        val photo = if (a.bool("attach_photo")) Meals.freePhoto(ctx) else null
        if (a.bool("attach_photo") && photo == null && ctx.photoIds.isEmpty()) {
            throw BadArg("這則訊息沒有附照片，attach_photo 要是 false。")
        }
        val meal = Meal(slot, items, photoId = photo?.first)
        val e = F.write(ctx, Kind.MEAL, meal)
        if (!isOpen(ctx)) {
            return ToolOut(F.closed(Feature.MEALS, e) {
                put("meal_kcal", items.sumOf { it.kcal })
                put("meal_protein_g", F.num(items.sumOf { it.proteinG }))
            })
        }
        return Meals.result(ctx, e, meal, F.violations(ctx), "已記")
    }
}

internal class FixMealTool : BaseTool(
    "fix_meal",
    "改正今天已記的一餐（他說記錯了、份量不對）。entry_id 是那餐的編號（情境裡的 #數字），items 是整餐正確的內容（全部重給）。" +
        "已經產生的違規不會因此消失。",
    S.obj(
        "entry_id" to S.int("那一餐的編號"),
        "items" to S.arr("整餐正確的內容", Meals.itemSchema),
        "source" to S.enum("數字的來源", listOf("label", "estimate")),
    ),
    Feature.MEALS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("entry_id", "餐點編號")
        val e = ctx.store.entry(id)
        val old = Meals.read(e) ?: throw BadArg("找不到編號 #$id 的餐點。")
        if (e!!.day != F.day(ctx)) throw BadArg("#$id 不是今天的餐點，只能改今天的。")
        val source = if (a.strOrNull("source")?.lowercase() == "label") FoodSource.LABEL else FoodSource.ESTIMATE
        val meal = old.copy(items = Meals.items(a, ctx, source))
        ctx.store.update(id, meal)
        val fresh = if (isOpen(ctx)) F.violations(ctx) else emptyList()
        return Meals.result(ctx, ctx.store.entry(id)!!, meal, fresh, "已改")
    }
}

internal class AttachPhotoTool : BaseTool(
    "attach_photo_to_meal",
    "把這則訊息附的照片補到今天一餐上（那餐之前記的時候沒有照片）。entry_id 是那餐的編號。從相簿選的照片會標成補交。",
    S.obj("entry_id" to S.int("那一餐的編號")),
    Feature.MEALS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("entry_id", "餐點編號")
        val e = ctx.store.entry(id)
        val old = Meals.read(e) ?: throw BadArg("找不到編號 #$id 的餐點。")
        if (e!!.day != F.day(ctx)) throw BadArg("#$id 不是今天的餐點。")
        val (photo, gallery) = Meals.freePhoto(ctx) ?: throw BadArg("這則訊息沒有附新的照片。")
        val meal = attach(ctx, id, old, photo, gallery)
        return Meals.result(ctx, ctx.store.entry(id)!!, meal, emptyList(), "已補照片")
    }

    companion object {
        fun attach(ctx: ToolCtx, id: Long, old: Meal, photoId: String, fromGallery: Boolean): Meal {
            val meal = old.copy(photoId = photoId, photoLate = fromGallery)
            ctx.store.update(id, meal)
            return meal
        }
    }
}

internal class RemoveEntryTool : BaseTool(
    "remove_entry",
    "刪掉今天記錯的一筆（餐點、喝水、記帳、飲料、體重、心情）。entry_id 是情境裡的 #數字。簽到、訓練、違規都不能刪。",
    S.obj("entry_id" to S.int("要刪的那一筆的編號")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("entry_id", "編號")
        val e = ctx.store.entry(id) ?: throw BadArg("找不到編號 #$id。")
        if (e.kind !in F.TODAY_ONLY) throw BadArg("#$id 這種紀錄不能刪（只能刪餐點、喝水、記帳、飲料、體重、心情）。")
        if (e.day != F.day(ctx)) throw BadArg("#$id 不是今天的紀錄，只能刪今天的。")
        ctx.store.remove(id)
        val m = F.moment(ctx)
        return ToolOut(F.obj {
            put("removed", id)
            put("note", "已刪除 #$id")
            put("today", F.today(m))
        })
    }
}
