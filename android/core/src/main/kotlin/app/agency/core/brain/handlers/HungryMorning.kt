package app.agency.core.brain.handlers

import app.agency.core.model.DayOverride
import app.agency.core.store.AgencyStore
import java.time.LocalDate

/** 「早上很餓」 (plan v3 §5): today's 豆漿 moves to the morning; the chicken breast always stays at 21:00 (riding safety). */
class HungryMorning(private val store: AgencyStore) {
    /** The reply, and whether this message changed today (false when it was already swapped). */
    suspend fun declare(today: LocalDate, at: Long): Pair<String, Boolean> {
        val o = store.days.get(today.toString())
        if (o?.soyMorning == true) return "今天的豆漿已經挪到早上了。" to false
        store.days.put((o ?: DayOverride(today.toString(), declaredAt = at)).copy(soyMorning = true, declaredAt = at))
        return "好，今天的豆漿挪到早上喝，雞胸照樣留到 21:00 跑單途中吃。喝的時候拍照。" to true
    }
}
