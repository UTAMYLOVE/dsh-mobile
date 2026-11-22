package app.agency.core.lessons

import app.agency.core.llm.ChatResult
import app.agency.core.llm.SearchClient
import app.agency.core.llm.SearchHit
import app.agency.core.llm.SearchResult
import app.agency.core.structured.ScriptedLlm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Invented 理論課 material, search hits and quiz replies for the tests (T19c). */
object LessonKit {
    /** A vocal lesson's page text (more than 200 Han characters). */
    const val VOCAL = "腹式呼吸是唱歌的基礎。吸氣的時候肩膀不要抬起來，讓肚子慢慢鼓起來，像氣球一樣。吐氣的時候用嘴巴發出嘶的聲音，盡量維持十五秒以上，聲音要平穩不要忽大忽小。" +
        "每天早上練習五分鐘，連續兩週就會感覺氣變長。唱高音的時候不要用喉嚨擠，要把氣往下沉，下巴放鬆，嘴巴張開成直的橢圓形。練習時可以把手放在肚子上，感覺吸氣時往外推、吐氣時往內收。" +
        "如果練到頭暈就先休息，不要硬撐。錄下自己的聲音回來聽，比較每天的差別，是進步最快的方法。唱歌前先喝一點溫水，不要喝冰的，喉嚨才不會緊。練習的地方要安靜，旁邊不要有人一直講話。"

    /** An expression lesson's page text (more than 200 Han characters). */
    const val SMILE = "對著鏡頭笑的時候，先想一件開心的事，眼睛才會跟著笑。只動嘴巴的笑看起來很假。練習時拿手機前鏡頭，從一數到三再笑，每次停三秒，看眼角有沒有細紋。眼神要看鏡頭中間偏上的位置，不要飄走。" +
        "下巴微微往下收，臉看起來會比較小。每天練十次，錄下來比較第一天和第七天。緊張的時候先深呼吸兩次，肩膀放下來，再重新開始。拍照時身體斜四十五度，臉轉回來正對鏡頭，線條會比較好看。" +
        "不要一直眨眼，拍之前先閉眼兩秒再張開。練完記得讓臉部肌肉休息一下，用手輕輕按摩兩頰。自信比長相更重要，鏡頭會看得出來。"

    const val VOCAL_URL = "https://www.youtube.com/watch?v=vocal-one"
    const val SMILE_URL = "https://www.youtube.com/watch?v=smile-one"
    const val THIN_URL = "https://www.youtube.com/watch?v=thin-one"

    fun hit(url: String, title: String, content: String, summary: String = "") = SearchHit(url, title, summary, null, 2, 2.0, content)

    /** A search that answers every video query with [hits] and remembers the queries. */
    class FakeSearch(var hits: List<SearchHit> = listOf(hit(VOCAL_URL, "腹式呼吸發聲練習", VOCAL), hit(SMILE_URL, "鏡頭前的笑容練習", SMILE))) : SearchClient {
        val queries = mutableListOf<String>()
        override suspend fun searchPlaces(area: String, dish: String): SearchResult = SearchResult.Ok("", emptyList())
        override suspend fun searchVideos(query: String): SearchResult {
            queries += query
            return SearchResult.Ok(query, hits)
        }
    }

    data class Q(val q: String, val options: List<String>, val answer: Int, val quote: String)

    /** Three questions and three spares on [VOCAL], every quote copied from it. */
    val VOCAL_QS = listOf(
        Q("吐氣練習時嘶的聲音要維持多久以上？", listOf("五秒", "十秒", "十五秒", "三十秒"), 2, "盡量維持十五秒以上"),
        Q("唱高音時氣要怎麼處理？", listOf("往上提", "往下沉", "憋住", "用喉嚨擠"), 1, "要把氣往下沉"),
        Q("吸氣時肩膀要怎樣？", listOf("抬起來", "不要抬起來", "往前縮", "往後夾"), 1, "吸氣的時候肩膀不要抬起來"),
        Q("每天早上要練多久？", listOf("一分鐘", "五分鐘", "二十分鐘", "一小時"), 1, "每天早上練習五分鐘"),
        Q("練到頭暈要怎麼做？", listOf("繼續練", "先休息", "喝咖啡", "唱更大聲"), 1, "如果練到頭暈就先休息"),
        Q("進步最快的方法是什麼？", listOf("多吃", "錄下自己的聲音回來聽", "睡覺", "看別人唱"), 1, "錄下自己的聲音回來聽"),
    )

    /** Three new questions on [VOCAL] for the 複習考. */
    val VOCAL_REVIEW = listOf(
        Q("嘴巴要張成什麼形狀？", listOf("圓形", "直的橢圓形", "扁的", "閉起來"), 1, "嘴巴張開成直的橢圓形"),
        Q("練習時手可以放在哪裡？", listOf("頭上", "肚子上", "背後", "口袋"), 1, "可以把手放在肚子上"),
        Q("連續練多久會感覺氣變長？", listOf("兩天", "兩週", "兩個月", "兩年"), 1, "連續兩週就會感覺氣變長"),
    )

    val SMILE_QS = listOf(
        Q("眼睛要怎樣才會跟著笑？", listOf("先想一件開心的事", "用力睜大", "一直眨眼", "看地上"), 0, "先想一件開心的事，眼睛才會跟著笑"),
        Q("眼神要看哪裡？", listOf("鏡頭下面", "鏡頭中間偏上的位置", "旁邊的人", "天花板"), 1, "眼神要看鏡頭中間偏上的位置"),
        Q("拍照時身體要斜幾度？", listOf("十五度", "三十度", "四十五度", "九十度"), 2, "身體斜四十五度"),
        Q("拍之前眼睛要先怎樣？", listOf("閉眼兩秒再張開", "一直看燈", "揉眼睛", "眨十次"), 0, "拍之前先閉眼兩秒再張開"),
        Q("每天要練幾次？", listOf("一次", "五次", "十次", "二十次"), 2, "每天練十次"),
        Q("緊張時先做什麼？", listOf("喝水", "深呼吸兩次", "跑步", "吃東西"), 1, "先深呼吸兩次"),
    )

    /** A question whose quote is not in any material (made up). */
    val INVENTED = Q("每天要喝多少水？", listOf("一公升", "兩公升", "三公升", "四公升"), 2, "每天要喝三公升的水")

    fun quizJson(qs: List<Q>): String = buildJsonObject {
        put("questions", buildJsonArray {
            qs.forEach { q ->
                add(buildJsonObject {
                    put("q", q.q); put("options", JsonArray(q.options.map { JsonPrimitive(it) })); put("answer", q.answer); put("quote", q.quote)
                })
            }
        })
    }.toString()

    fun quiz(qs: List<Q>): ChatResult = ScriptedLlm.ok(quizJson(qs))
}
