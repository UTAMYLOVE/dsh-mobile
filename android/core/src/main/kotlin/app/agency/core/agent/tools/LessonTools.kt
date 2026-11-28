package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.Hit
import app.agency.core.agent.SearchUnavailable
import app.agency.core.agent.ToolCtx
import app.agency.core.agent.ToolOut
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonDay
import app.agency.core.lessons.LessonResult
import app.agency.core.lessons.Habits
import app.agency.core.lessons.SubjectLevels
import app.agency.core.lessons.Subjects
import app.agency.core.stage.Feature
import app.agency.core.stage.Today
import app.agency.core.store.Kind
import app.agency.core.store.ProposalRec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.put

internal class FindVideosTool : BaseTool(
    "find_videos",
    "用真的網路搜尋找教學影片，給他挑。subject 是科目（例如「語言」「聲樂」），query 是他想學的具體內容（沒有就空字串）。" +
        "程式會依他這科的程度加上搜尋字，把最多四個結果放進清單等他按「加入清單」或「不要」。影片優先；影片不夠時會補上真的教學文章（回傳裡標「文章」），" +
        "照實告訴他哪些是影片、哪些是文章。只能說回傳裡的標題，不要自己寫網址。找不到時不要自己一直重試，問他要不要換個方向或貼連結。",
    S.obj("subject" to S.str("科目"), "query" to S.str("他想學的具體內容；沒有就空字串")),
    Feature.LESSONS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val subject = a.str("subject", "科目")
        val search = ctx.ports.search ?: return ToolOut.error(NO_SEARCH)
        val q = listOf(subject, SubjectLevels(ctx.store).searchWords(subject), a.text("query"), "教學影片").filter { it.isNotBlank() }.joinToString(" ")
        val hits: List<Hit> = try {
            search.videos(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchUnavailable) {
            return ToolOut.error(SEARCH_DOWN)
        } catch (e: Exception) {
            return ToolOut.error(SEARCH_DOWN)
        }
        val book = LessonBook(ctx.store, ctx.clock, ctx.rules)
        val videos = ArrayList<Card.Video>()
        for (h in hits) {
            if (videos.size >= MAX) break
            val r = book.propose(h, subject)
            if (r is LessonResult.Ok) videos += Card.Video(r.id, subject, r.lesson.title, r.lesson.url, r.lesson.minutes, page = !h.video)
        }
        if (videos.isEmpty()) {
            return ToolOut(F.obj {
                put("found", 0)
                put("query", q)
                put("note", "這次沒有找到合用的新影片，換個說法再找一次，或請他自己貼影片連結。")
            })
        }
        return ToolOut(F.obj {
            put("found", videos.size)
            put("subject", subject)
            put("videos", F.arr(videos.map { v -> F.obj { put("lesson_id", v.lessonId); put("title", v.title); put("kind", if (v.page) "文章" else "影片") } }))
            put("note", "找到 ${videos.size} 個結果（影片 ${videos.count { !it.page }}、文章 ${videos.count { it.page }}），清單在卡片上，請他按「加入清單」或「不要」。")
        }, listOf(Card.Videos(videos)))
    }

    companion object {
        const val MAX = 4

        /** No search port at all: the two ways on, so stage 1 never dead-ends. */
        const val NO_SEARCH = "搜尋現在不能用（還沒有搜尋用的金鑰）。告訴他兩個辦法：到 設定 → 小葵的模型 加上搜尋用的金鑰；" +
            "或者現在直接把影片連結貼給你，你用 add_video 加進清單。不要自己編影片，也不要自己寫網址。"

        const val SEARCH_DOWN = "搜尋現在不能用，等一下再找；或請他直接把影片連結貼給你，你用 add_video 加進清單。不要自己編影片。"
    }
}

/**
 * A video link he pasted himself. Only a link that literally appears in his current message is accepted (so a model cannot
 * slip in one of its own); the lesson starts PROPOSED with no material, so it ends with notes instead of a quiz.
 */
internal class AddVideoTool : BaseTool(
    "add_video",
    "他自己貼了影片連結時用（搜尋不能用時最常見）。url 必須是他這則訊息裡原樣出現的連結；title 是影片名稱（他沒說就空字串）；" +
        "subject 是科目。會放進清單等他按「加入清單」或「不要」。這種影片沒有教材文字，看完後寫三行筆記就完成，不考試。",
    S.obj("url" to S.str("他訊息裡的連結，原樣照抄"), "title" to S.str("影片名稱；沒有就空字串"), "subject" to S.str("科目")),
    Feature.LESSONS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val url = a.str("url", "連結")
        val subject = a.str("subject", "科目")
        if (!URL.matches(url)) throw BadArg("這不是一個網址，請照他訊息裡的連結原樣傳。")
        if (!ctx.ownerText.contains(url)) throw BadArg("這個連結不在他這則訊息裡。只能加他自己貼的連結，不要自己寫網址。")
        val title = a.text("title").ifBlank { "他貼的影片" }
        val r = LessonBook(ctx.store, ctx.clock, ctx.rules).propose(Hit(title, url), subject)
        if (r !is LessonResult.Ok) {
            val why = (r as LessonResult.Refused).why
            throw BadArg(if (why == app.agency.core.lessons.Refusal.DUPLICATE) "這支影片已經在清單裡了。" else why.text)
        }
        val v = Card.Video(r.id, subject, r.lesson.title, r.lesson.url, r.lesson.minutes)
        return ToolOut(F.obj {
            put("lesson_id", r.id)
            put("subject", subject)
            put("title", r.lesson.title)
            put("notes_only", true)
            put("note", "已放進清單等他決定：${r.lesson.title}（看完寫三行筆記，不考試）")
        }, listOf(Card.Videos(listOf(v))))
    }

    companion object {
        private val URL = Regex("""https?://\S+""")
    }
}

internal class FindPlacesTool : BaseTool(
    "find_places",
    "用真的網路搜尋找吃飯的地方或店家（例如「學校附近 雞胸便當」）。只能說回傳裡的店名，不要自己編店名或網址。",
    S.obj("query" to S.str("要找什麼、在哪附近")),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val query = a.str("query", "要找什麼")
        val search = ctx.ports.search ?: return ToolOut.error("搜尋現在不能用（沒有設定搜尋），不要自己編店名。")
        val hits = try {
            search.places(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolOut.error("搜尋現在不能用，等一下再找；不要自己編店名。")
        }
        val places = hits.filter { it.title.isNotBlank() && it.url.isNotBlank() }.take(MAX).map { Card.Place(it.title, it.url, it.summary) }
        return ToolOut(F.obj {
            put("found", places.size)
            put("places", F.arr(places.map { p -> F.obj { put("name", p.name); if (p.summary.isNotBlank()) put("summary", p.summary.take(120)) } }))
            put("note", if (places.isEmpty()) "沒有找到" else "找到 ${places.size} 個地方，在卡片上")
        }, if (places.isEmpty()) emptyList() else listOf(Card.Places(places)))
    }

    companion object {
        const val MAX = 5
    }
}

internal class LessonWishTool : BaseTool(
    "lesson_wish",
    "他說想學某個東西（不是今天排的科目）：記成願望，今天可以多上一堂（有額度時）。之後用 find_videos 找影片。",
    S.obj("topic" to S.str("想學的主題")),
    Feature.LESSONS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val r = LessonBook(ctx.store, ctx.clock, ctx.rules).wish(a.str("topic", "主題"))
        if (r !is LessonResult.Ok) return ToolOut.error((r as LessonResult.Refused).why.text)
        return ToolOut(F.obj { put("wish_id", r.id); put("topic", r.lesson.subject); put("note", "記下想學：${r.lesson.subject}") })
    }
}

internal class LessonsTodayTool : BaseTool(
    "lessons_today",
    "今天的理論課：每一堂的科目、影片、進度（等找影片／等他決定／待看／待測驗／待寫筆記／完成），和已通過幾堂。",
    S.obj(),
    Feature.LESSONS,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val day = F.day(ctx)
        val st = Today.state(ctx.store, day, ctx.rules, ctx.stage, ctx.clock.zone)
        val slots = LessonDay.of(day, ctx.store, ctx.rules, st.examWeek, Today.levels(ctx.store))
        val book = LessonBook(ctx.store, ctx.clock, ctx.rules)
        return ToolOut(F.obj {
            put("slots", F.arr(slots.map { s ->
                F.obj {
                    put("n", s.n)
                    put("subject", s.subject)
                    put("title", s.title)
                    s.lessonId?.let { id ->
                        put("lesson_id", id)
                        book.get(id)?.let { put("state", LessonWords.state(it)) }
                    }
                    put("done", s.done)
                    if (s.review) put("review", true)
                }
            }))
            put("done", slots.count { it.done })
            put("total", slots.size)
            put("note", "今天 ${slots.size} 堂課，完成 ${slots.count { it.done }} 堂")
        })
    }
}

/** Lesson states in his words. */
internal object LessonWords {
    fun state(l: app.agency.core.lessons.Lesson): String = when (l.state) {
        app.agency.core.lessons.LessonState.PROPOSED -> "等他決定要不要"
        app.agency.core.lessons.LessonState.APPROVED -> if (l.openedAt == null) "待看" else "看到一半"
        app.agency.core.lessons.LessonState.WATCHED -> if (l.notesOnly) "待寫筆記" else "待測驗"
        app.agency.core.lessons.LessonState.PASSED -> "測驗通過，待寫筆記"
        app.agency.core.lessons.LessonState.NOTED -> "完成"
        app.agency.core.lessons.LessonState.REVIEW_DUE -> "待複習"
        app.agency.core.lessons.LessonState.REVIEWED -> "複習完成"
        app.agency.core.lessons.LessonState.DECLINED -> "不要了"
        app.agency.core.lessons.LessonState.WISH -> "想學"
    }
}

internal class ProposeTool : BaseTool(
    "propose",
    "提一個要他按「好／不要」才生效的提案。kind：habit 是一個每天的小習慣（上完課後可以提，title 寫習慣名稱，例如「每天念五個單字」）；" +
        "note 是從明天起的計畫約定（他同意後你會記住）。公司規章和熱量目標不能用這個改。value 目前用不到，傳 null。",
    S.obj(
        "title" to S.str("提案標題，短短一句"),
        "body" to S.str("為什麼、怎麼做，一兩句"),
        "kind" to S.enum("提案種類", listOf("habit", "note")),
        "value" to S.int("用不到，傳 null", nullable = true),
    ),
    null,
) {
    override suspend fun exec(a: Args, ctx: ToolCtx): ToolOut {
        val title = a.str("title", "標題")
        val body = a.text("body")
        val kind = a.str("kind", "種類").lowercase()
        when (kind) {
            "habit" -> {
                if (!Feature.LESSONS.openAt(ctx.stage.stage)) throw BadArg("小習慣跟著理論課，還沒開放。")
                if (!Habits(ctx.store, ctx.clock).canAddNew(F.day(ctx))) {
                    throw BadArg("現在不能加新習慣：上一個習慣最近十四天要做到十二天以上，才能再加一個。")
                }
            }
            "note" -> Unit
            "kcal_up" -> throw BadArg("熱量的提案只能由程式依體重趨勢提出，你不能提。")
            else -> throw BadArg("kind 只能是 habit 或 note。")
        }
        val rec = ProposalRec(kind, title, body)
        val e = F.write(ctx, Kind.PROPOSAL, rec)
        return ToolOut(
            F.obj { put("proposal_id", e.id); put("title", title); put("note", "提案已送出，等他按好或不要") },
            listOf(Card.Proposal(e.id, title, body)),
        )
    }
}

/** The subjects she may search for: his study rules first, then the built-in list. */
internal object SubjectNames {
    fun all(ctx: ToolCtx): List<String> =
        (listOf(ctx.rules.study.daily) + ctx.rules.study.twiceWeekly + ctx.rules.study.rotation + Subjects.all)
            .filter { it.isNotBlank() }.distinct()
}
