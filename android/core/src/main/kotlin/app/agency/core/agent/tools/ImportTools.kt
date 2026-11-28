package app.agency.core.agent.tools

import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.importing.ImportKind
import app.agency.core.importing.Importer
import kotlinx.serialization.json.put

/** `import_preview(kind, rows[])`: checks rows read from a file he gave and shows them as a proposal; writes nothing else. */
internal class ImportPreviewTool : BaseTool(
    "import_preview",
    "他附上或貼上一份舊紀錄（記帳、飲食、體重、備忘的表格、清單或文字）想匯入時用。把檔案的每一列整理成 rows 交給程式檢查；" +
        "程式只做預覽卡，他按「好」才寫入，匯入的只當歷史紀錄（不算完成、不算違規）。kind：money 記帳（amount、bucket、note）、" +
        "meal 飲食（slot、name、kcal、protein_g，一樣東西一列，同一天同一餐會合成一餐）、body 體重（kg、waist_cm）、memo 備忘（text、done）。" +
        "每列一定要有 date（西元日期，像 2030-01-07）；用不到的欄位傳 null。一次最多 ${Importer.MAX_ROWS} 列。",
    S.obj(
        "kind" to S.enum("匯入哪一種", ImportKind.values().map { it.wire }),
        "rows" to S.arr(
            "檔案裡的每一列",
            S.obj(
                "date" to S.str("日期，西元年-月-日"),
                "amount" to S.int("記帳：金額（元）", nullable = true),
                "bucket" to S.enum("記帳：living 生活、fun 娛樂、income 收入", listOf("living", "fun", "income"), nullable = true),
                "note" to S.str("記帳：備註", nullable = true),
                "slot" to S.enum("飲食：first 第一餐、dinner 晚餐、night 宵夜、extra 點心", listOf("first", "dinner", "night", "extra"), nullable = true),
                "name" to S.str("飲食：食物名稱", nullable = true),
                "kcal" to S.int("飲食：熱量（大卡），檔案裡沒有就 null", nullable = true),
                "protein_g" to S.num("飲食：蛋白質（克）", nullable = true),
                "kg" to S.num("體重（公斤）", nullable = true),
                "waist_cm" to S.num("腰圍（公分）", nullable = true),
                "text" to S.str("備忘：內容", nullable = true),
                "done" to S.bool("備忘：是否已完成", nullable = true),
            ),
        ),
    ),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val kind = ImportKind.of(a.str("kind", "種類")) ?: throw BadArg("kind 只能是 money、meal、body 或 memo。")
        val rows = a.objects("rows")
        if (rows.isEmpty()) throw BadArg("rows 是空的：把檔案的每一列整理成一筆再給我。")
        val p = Importer.preview(ctx.store, ctx.clock, ctx.rules, kind, rows)
        return ToolOut(
            F.obj {
                put("proposal_id", p.entryId)
                put("batch_id", p.entryId)
                put("kind", kind.word)
                put("rows", p.rows)
                put("accepted", p.accepted)
                p.from?.let { put("from", it.toString()) }
                p.to?.let { put("to", it.toString()) }
                put("rejected", F.strs(p.rejected.map { "第 ${it.row} 列：${it.why}" }))
                put(
                    "note",
                    if (p.accepted == 0) "沒有可以匯入的列，什麼都沒寫入；照沒收的原因跟他說" else
                        "預覽卡已送出，還沒寫入；等他按好才匯入（共 ${p.accepted} 筆${kind.word}）",
                )
            },
            listOf(p.card),
        )
    }
}

/** `undo_import(batch)`: removes exactly the entries one confirmed import wrote. */
internal class UndoImportTool : BaseTool(
    "undo_import",
    "他要撤回一次已經匯入的資料時用。batch 是那次匯入的編號（import_preview 回傳的 batch_id）。只刪那一次匯入寫進去的紀錄。",
    S.obj("batch" to S.int("匯入的編號")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val batch = a.long("batch", "匯入的編號")
        val n = Importer.undo(ctx.store, batch) ?: throw BadArg("找不到這次匯入，或它還沒被接受、已經復原過了。")
        return ToolOut(F.obj { put("batch_id", batch); put("removed_count", n); put("note", "已復原：刪掉 $n 筆匯入的紀錄") })
    }
}
