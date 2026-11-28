package app.agency.core.remind

import java.time.LocalDate

/**
 * The words of a reminder when code writes them (offline, or before 小葵 words them herself). Short, dry, a little teasing:
 * a cool older sister who is also the manager. Several variants per kind; the pick depends only on the day, the key and how
 * many times it was already sent, so the same inputs always give the same line and a chase never repeats the previous one.
 */
internal object Lines {
    fun first(day: LocalDate, key: String, title: String): String = pick(
        listOf(
            "${title}的時間到了。",
            "該做${title}了，現在。",
            "${title}，輪到你了。",
            "喂，${title}別忘了。",
            "時間到。${title}，開始吧。",
        ),
        day, key, 0,
    )

    fun chase(day: LocalDate, key: String, title: String, timesSent: Int): String = pick(
        listOf(
            "${title}還沒做。我在看著。",
            "${title}，再拖下去就太晚了。",
            "半小時了，${title}呢？",
            "${title}，我話不說第二遍。快。",
            "${title}還在等你，別讓它排到明天。",
            "你不動，${title}就不會自己完成。",
        ),
        day, key, timesSent,
    )

    fun memo(day: LocalDate, key: String, text: String): String = pick(
        listOf(
            "你交代過的：${text}。",
            "備忘到了：${text}。",
            "記得這件事嗎？${text}。",
        ),
        day, key, 0,
    )

    fun lessonAway(day: LocalDate, key: String, title: String): String = pick(
        listOf(
            "《${title}》看完了嗎？回來報到。",
            "影片應該播完了，《${title}》，回來吧。",
            "人跑去哪了？《${title}》看完就回來答題。",
        ),
        day, key, 0,
    )

    private fun pick(variants: List<String>, day: LocalDate, key: String, n: Int): String =
        variants[Math.floorMod(day.toString().hashCode() * 31 + key.hashCode() + n, variants.size)]
}
