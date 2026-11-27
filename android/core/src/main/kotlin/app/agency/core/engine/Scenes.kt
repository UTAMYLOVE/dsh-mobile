package app.agency.core.engine

import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/**
 * 新鮮感 (定稿 §2, §11): the manager plays one short scene of company life a day (社長巡視、剛開完會、練習室…), the kind turned by
 * code so it does not repeat for [KINDS] days; special days (入社第 30、100 天, holidays) have their own line instead. The lines
 * are code's: only the company's world, never a number or a fact about him, never a change of what is required, never
 * anything that makes him need her (each line is checked by the output guard in tests).
 */
object Scenes {
    data class Scene(val kind: String, val lines: List<String>)

    val ALL = listOf(
        Scene("社長巡視", listOf(
            "社長剛剛來練習室巡了一圈，只說了一句「基本功不能偷懶」就走了。照表來。",
            "社長今天突然下來看練習生名冊，在你的名字旁邊停了一下。今天的表照做。",
            "社長巡視完，交代經紀組把每個人這週的練習單貼在門口。你的那張我看過了。",
        )),
        Scene("剛開完會", listOf(
            "剛開完企劃會，大家在討論下一季的主題。練習生要做的事一樣：今天的表做滿。",
            "會議室的白板寫滿了，聲樂組和舞蹈組在搶練習室時段。你的時段沒變。",
            "早上的會開太久，我咖啡都涼了。好，今天的安排照表，有事找我。",
        )),
        Scene("練習室", listOf(
            "練習室的鏡子今天剛擦過，亮到可以看清楚每個小動作。等一下照著練。",
            "舞蹈組的老師在練習室放了新的節拍器，說誰的拍子掉了一聽就知道。",
            "練習室的冷氣又壞了，管理員說下午修好。你的練習照常。",
        )),
        Scene("錄音室", listOf(
            "錄音室今天在調新的麥克風，工程師說收音很細，氣不穩一聽就出來。",
            "聲樂老師在錄音室外面貼了一張紙：「先暖聲，再唱歌」。",
        )),
        Scene("造型室", listOf(
            "造型室剛進了一批新衣服，造型師說練習生先把基本的合身學好。",
            "化妝師在整理刷具，說保養做好的皮膚上妝最快。",
        )),
        Scene("宿舍", listOf(
            "宿舍公告：熄燈時間照規定，晚睡的會被記下來。",
            "宿舍的冰箱今天大掃除，過期的全部丟掉了。",
        )),
        Scene("公司走廊", listOf(
            "走廊上貼了上個月的出道組海報，大家經過都會看一眼。",
            "公司大廳換了新的花，櫃台說是社長挑的粉色。",
        )),
        Scene("經紀組", listOf(
            "經紀組今天在排下個月的行程表，我把你的練習時段放在最前面。",
            "經紀組的電話一直響，我先回你這邊：今天有什麼要問的，一次問清楚。",
        )),
    )

    val KINDS = ALL.size

    /** The scene of [day]: the kind turns daily (each kind once every [KINDS] days), the line by the week. */
    fun of(day: LocalDate): Pair<String, String> {
        val n = day.toEpochDay()
        val s = ALL[Math.floorMod(n, KINDS.toLong()).toInt()]
        val line = s.lines[Math.floorMod(n / KINDS, s.lines.size.toLong()).toInt()]
        return s.kind to line
    }

    private val HOLIDAYS = mapOf(
        MonthDay.of(1, 1) to "新年快樂。公司今天在大廳掛了新年的燈籠，新的一年，照表來，一天一天變強。",
        MonthDay.of(2, 14) to "今天是情人節，公司收到很多粉絲寄來的卡片。練習生的心意，放在練習上。",
        MonthDay.of(4, 4) to "今天兒童節，練習室放了一盒糖果，看看就好。今天照表。",
        MonthDay.of(10, 10) to "國慶日，公司放假半天，練習室還是開著。你的表照做。",
        MonthDay.of(12, 25) to "聖誕快樂。大廳放了一棵粉色的聖誕樹，社長說今年的練習生最認真。",
        MonthDay.of(12, 31) to "今天是今年最後一天。把今天的表做滿，好好跟這一年說再見。",
    )

    /** A special day's line (入社第 30、100 天 from [joinDate], or a holiday), or null. */
    fun special(day: LocalDate, joinDate: String?): String? {
        val joined = joinDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (joined != null) {
            when (ChronoUnit.DAYS.between(joined, day) + 1) {
                30L -> return "今天是你入社第 30 天。一個月了，公司替你準備了一張紀念小卡。繼續照表來。"
                100L -> return "今天是你入社第 100 天！經紀組全體跟你說辛苦了。紀念小卡貼上照片牆了。"
            }
        }
        return HOLIDAYS[MonthDay.from(day)]
    }

    /** The day's opening: the special line, else the scene; on a Monday the week's theme is announced with it. */
    fun opening(day: LocalDate, joinDate: String?, themeOpen: Boolean): String {
        val base = special(day, joinDate) ?: of(day).second
        if (!themeOpen || day.dayOfWeek != java.time.DayOfWeek.MONDAY) return base
        val t = Themes.of(day)
        return "$base\n這週是${t.name}：星期六前多一個小實作——${t.task}"
    }
}
