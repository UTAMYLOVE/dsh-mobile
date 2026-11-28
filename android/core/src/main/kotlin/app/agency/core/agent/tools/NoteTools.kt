package app.agency.core.agent.tools

import app.agency.core.agent.Memory
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.stage.Feature
import app.agency.core.store.Kind
import app.agency.core.store.Memo
import app.agency.core.store.Store
import app.agency.core.store.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.put

/** One memo as lists show it. [dueAt] is an instant or null. */
data class MemoRow(val id: Long, val text: String, val dueAt: Instant?, val done: Boolean)

/** Memos are plain `Kind.MEMO` entries; these are the few operations everyone needs. */
object Memos {
    private const val WINDOW = 400L

    fun all(store: Store, today: LocalDate): List<MemoRow> =
        store.entries(today.minusDays(WINDOW), today.plusDays(1), Kind.MEMO).mapNotNull { e ->
            runCatching { e.read<Memo>() }.getOrNull()?.let { m ->
                MemoRow(e.id, m.text, m.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() }, m.done)
            }
        }

    fun open(store: Store, today: LocalDate): List<MemoRow> = all(store, today).filter { !it.done }

    /** `(id, memo)` pairs for the reminders. */
    fun forReminders(store: Store, today: LocalDate): List<Pair<Long, Memo>> =
        store.entries(today.minusDays(WINDOW), today.plusDays(1), Kind.MEMO).mapNotNull { e ->
            runCatching { e.read<Memo>() }.getOrNull()?.let { e.id to it }
        }

    /** Open memos due on logical [day]. */
    fun dueOn(store: Store, day: LocalDate, zone: ZoneId): List<MemoRow> =
        open(store, day).filter { it.dueAt != null && Days.of(it.dueAt, zone) == day }

    fun setDone(store: Store, id: Long, done: Boolean = true): Boolean {
        val e = store.entry(id)?.takeIf { it.kind == Kind.MEMO } ?: return false
        val m = runCatching { e.read<Memo>() }.getOrNull() ?: return false
        return store.update(id, m.copy(done = done))
    }

    fun remove(store: Store, id: Long): Boolean {
        val e = store.entry(id)?.takeIf { it.kind == Kind.MEMO } ?: return false
        return store.remove(e.id)
    }

    /**
     * "HH:mm" (today, or tomorrow when already past), "YYYY-MM-DD", "YYYY-MM-DD HH:mm" or an ISO instant → an instant; null when
     * unreadable.
     */
    fun parseDue(raw: String, clock: Clock): Instant? {
        val s = raw.trim().replace('T', ' ')
        val zone = clock.zone
        val now = clock.now()
        runCatching { return Instant.parse(raw.trim()) }
        Days.parse(s)?.let { t ->
            val today = Days.today(clock)
            val at = Days.at(today, t, zone)
            return if (at.isAfter(now)) at else Days.at(today.plusDays(1), t, zone)
        }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant() }
        runCatching { return LocalDate.parse(s).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant() }
        return null
    }
}

internal class MemoAddTool : BaseTool(
    "memo_add",
    "記一則備忘（他要你記得提醒的事）。due_at 是提醒時間：「HH:mm」、「YYYY-MM-DD」或「YYYY-MM-DD HH:mm」，沒有時間就 null。" +
        "時間到了程式會發通知。回傳備忘編號。",
    S.obj("text" to S.str("備忘內容"), "due_at" to S.str("提醒時間；沒有就 null", nullable = true)),
    Feature.MEMO,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val text = a.str("text", "內容")
        val dueRaw = a.strOrNull("due_at")
        val due = dueRaw?.let { Memos.parseDue(it, ctx.clock) ?: throw BadArg("看不懂提醒時間「$it」，請用 HH:mm 或 YYYY-MM-DD HH:mm。") }
        val e = F.write(ctx, Kind.MEMO, Memo(text, due?.toString()))
        return ToolOut(F.obj {
            put("memo_id", e.id)
            put("text", text)
            due?.let {
                put("due_date", Days.of(it, ctx.clock.zone).toString())
                put("due_time", F.hm(it, ctx.clock.zone))
            }
            put("note", "已記備忘 #${e.id}：$text")
        })
    }
}

internal class MemoListTool : BaseTool(
    "memo_list", "列出還沒完成的備忘（編號、內容、提醒時間）。", S.obj(), Feature.MEMO,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val list = Memos.open(ctx.store, F.day(ctx))
        return ToolOut(F.obj {
            put("count", list.size)
            put("memos", F.arr(list.map { r ->
                F.obj {
                    put("memo_id", r.id); put("text", r.text)
                    r.dueAt?.let { put("due", Days.of(it, ctx.clock.zone).toString() + " " + F.hm(it, ctx.clock.zone)) }
                }
            }))
        })
    }
}

internal class MemoDoneTool : BaseTool(
    "memo_done", "把一則備忘標成完成。memo_id 是備忘編號。", S.obj("memo_id" to S.int("備忘編號")), Feature.MEMO,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("memo_id", "備忘編號")
        if (!Memos.setDone(ctx.store, id)) throw BadArg("找不到編號 #$id 的備忘。")
        return ToolOut(F.obj { put("memo_id", id); put("note", "備忘 #$id 完成") })
    }
}

internal class MemoRemoveTool : BaseTool(
    "memo_remove", "刪掉一則備忘。memo_id 是備忘編號。", S.obj("memo_id" to S.int("備忘編號")), Feature.MEMO,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val id = a.long("memo_id", "備忘編號")
        if (!Memos.remove(ctx.store, id)) throw BadArg("找不到編號 #$id 的備忘。")
        return ToolOut(F.obj { put("memo_id", id); put("note", "已刪除備忘 #$id") })
    }
}

internal class RememberTool : BaseTool(
    "remember",
    "把一件長期有用的事記進你的長期記憶（他的喜好、習慣、在意的事、對你的要求、說好的約定）。一次一件，短短一句。今天的小事用 note_today。",
    S.obj("text" to S.str("要記住的一句話")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val r = Memory.remember(ctx.store, a.str("text", "內容"))
        return ToolOut(F.obj {
            put("added", r.added)
            put("lines", r.lines.size)
            r.dropped?.let { put("dropped", it) }
            put("note", if (r.added) "記住了" else "這件事已經記過了")
        })
    }
}

internal class ForgetTool : BaseTool(
    "forget", "從長期記憶刪掉含有這段文字的每一行（他說不要記、記錯了）。回傳刪掉了哪幾行。",
    S.obj("text" to S.str("要刪的那幾行裡的一段文字")), null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val gone = Memory.forget(ctx.store, a.str("text", "內容"))
        return ToolOut(F.obj { put("removed", F.strs(gone)); put("note", if (gone.isEmpty()) "沒有找到要刪的" else "刪掉了 ${gone.size} 行") })
    }
}

internal class NoteTodayTool : BaseTool(
    "note_today", "替自己記一句今天值得留意的事（只在最近三天的情境裡看得到）。", S.obj("text" to S.str("一句話")), null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val e = Memory.noteToday(ctx.store, ctx.clock, a.str("text", "內容")) ?: throw BadArg("內容是空的。")
        return ToolOut(F.obj { put("entry_id", e.id); put("note", "記下了") })
    }
}
