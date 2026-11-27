package app.agency.core.singing

/**
 * 咬字 (T19d): the transcript of his take against the lyrics he typed, by code — the longest common subsequence of the Han
 * characters; what of the lyrics is not in the transcript is listed as unclear (at most [MAX] spans).
 */
object Diction {
    const val MAX = 8

    private fun han(s: String) = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    data class Result(val matchedPct: Int, val unclear: List<String>)

    fun check(lyrics: String, transcript: String): Result {
        val a = han(lyrics)
        val b = han(transcript)
        if (a.isEmpty()) return Result(0, emptyList())
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val hit = BooleanArray(a.length)
        var i = 0; var j = 0
        while (i < a.length && j < b.length) when {
            a[i] == b[j] -> { hit[i] = true; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
        val spans = mutableListOf<String>()
        var k = 0
        while (k < a.length) {
            if (hit[k]) { k++; continue }
            val s = k
            while (k < a.length && !hit[k]) k++
            spans += a.substring(s, k)
        }
        return Result(hit.count { it } * 100 / a.length, spans.take(MAX))
    }

    fun lines(r: Result): List<String> = buildList {
        add("咬字：歌詞有 ${r.matchedPct}% 聽得出來")
        if (r.unclear.isNotEmpty()) add("聽不清楚的地方：${r.unclear.joinToString("、") { "「$it」" }}")
    }
}
