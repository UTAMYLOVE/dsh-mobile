package app.agency.core.engine

import app.agency.core.schedule.SlotKind

/**
 * 指揮室 role cards (T20): what each person of the company is doing right now, computed by code from the card — the manager
 * waits for the next open item, the body teacher counts what is left of today's protein (or waits for the next meal or
 * training); while the brain works 「正在回你」; when the model could not be reached 「連不上模型」; nothing due 「今天交代的都收到了」.
 */
object RoleActivity {
    const val BUSY = "正在回你"
    const val FAILED = "連不上模型，先照表做"
    const val IDLE = "今天交代的都收到了"

    fun manager(card: IdolCardFacts, busy: Boolean, failed: Boolean): String = when {
        busy -> BUSY
        failed -> FAILED
        else -> card.slots.firstOrNull { it.status == SlotStatus.OPEN && it.slot.key.kind != SlotKind.MEAL && it.slot.key.kind != SlotKind.TRAINING }
            ?.let { "在等你 ${it.slot.time} 的${it.slot.label}" }
            ?: card.next?.let { "下一個是 ${it.slot.time} 的${it.slot.label}" }
            ?: IDLE
    }

    fun body(card: IdolCardFacts, busy: Boolean, failed: Boolean): String = when {
        busy -> BUSY
        failed -> FAILED
        else -> {
            val open = card.slots.firstOrNull { (it.status == SlotStatus.OPEN || it.status == SlotStatus.LATER) && (it.slot.key.kind == SlotKind.MEAL || it.slot.key.kind == SlotKind.TRAINING) }
            when {
                open != null && open.status == SlotStatus.OPEN -> "在等你 ${open.slot.time} 的${open.slot.label}"
                card.proteinLeftG > 0 && open != null -> "在算你今天剩下的蛋白質：還差 ${card.proteinLeftG} 克"
                open != null -> "下一個是 ${open.slot.time} 的${open.slot.label}"
                else -> IDLE
            }
        }
    }
}
