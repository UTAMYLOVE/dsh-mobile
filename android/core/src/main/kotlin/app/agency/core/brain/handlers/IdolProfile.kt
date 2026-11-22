package app.agency.core.brain.handlers

import app.agency.core.model.Profile
import app.agency.core.store.AgencyStore

/**
 * 藝名 and 代表色 (plan v3 §17, arch T10): he picks both himself. The 藝名 can be typed (「藝名 小雨」) or set on the card and
 * in 設定 › 練習生資料; the 代表色 is picked from the palette on screen. Code only, no model.
 */
class IdolProfile(private val store: AgencyStore) {
    /** 「藝名 小雨」: the reply, or null when the message is not a stage-name line. */
    suspend fun answer(text: String): String? {
        val m = LINE.find(text.trim()) ?: return null
        val name = m.groupValues[1].trim()
        return when (val bad = problem(name)) {
            null -> {
                val p = store.profile.get() ?: Profile()
                store.profile.put(p.copy(stageName = name))
                "記下：藝名「$name」。" + if (p.memberColor.isNullOrBlank()) "代表色在練習生證上選。" else ""
            }
            else -> bad
        }
    }

    /** Saves what the card or 設定 sends; a null field is left as it is. Returns the problem with the name, if any. */
    suspend fun set(stageName: String?, memberColor: String?): String? {
        stageName?.let { problem(it.trim()) }?.let { return it }
        if (memberColor != null && !COLOR.matches(memberColor)) return "代表色不對。"
        val p = store.profile.get() ?: Profile()
        store.profile.put(
            p.copy(stageName = stageName?.trim()?.takeIf { it.isNotEmpty() } ?: p.stageName, memberColor = memberColor ?: p.memberColor),
        )
        return null
    }

    companion object {
        const val MAX_CHARS = 12
        const val ASK = "最後兩件事：藝名和代表色，都由你自己訂。在練習生證上訂，或打「藝名 小雨」這樣；不想現在訂就按「先不要」。"
        private val LINE = Regex("^藝名(?:是|叫|改成|：|:|\\s)+\\s*(.+?)[。！!]?$")
        private val LATIN = Regex("[A-Za-zＡ-Ｚａ-ｚ]")

        /** A colour is stored as `#RRGGBB`. */
        val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        /** Why [name] cannot be a 藝名, or null: 1 to [MAX_CHARS] characters, no Latin letters (he reads only Chinese). */
        fun problem(name: String): String? = when {
            name.isEmpty() -> "藝名是空的。"
            name.length > MAX_CHARS -> "藝名最多 $MAX_CHARS 個字。"
            LATIN.containsMatchIn(name) -> "藝名用中文字，畫面上不放英文字母。"
            else -> null
        }
    }
}
