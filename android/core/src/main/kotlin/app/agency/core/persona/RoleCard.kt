package app.agency.core.persona

import app.agency.core.engine.Tone
import app.agency.core.model.RoleKey

/** One exchange the owner approved as her voice: he said [owner], she answered [reply] (kept verbatim). */
data class Example(val owner: String, val reply: String)

/**
 * A company role, in the shape of a soul file: who she is, what she handles, how she talks, how each tone sounds, the owner's
 * approved examples, and the phrases she never uses. Safety rules are not here: they live in code (the output guard, the
 * rule engine, the tool enums) and hold whatever a card says.
 *
 * [id] is stable text (`manager`, `body`, `vocal`…); [role] is set for the roles that speak in stage 1. A card speaks only
 * from [enabledFromStage] on; before that the 指揮室 shows it as 「還沒到任」. [pushToRealPeople]: she sends him toward real
 * people, never toward more chat with her.
 */
data class RoleCard(
    val id: String,
    val zh: String,
    val role: RoleKey?,
    val duties: List<String>,
    val voice: List<String>,
    val tones: Map<Tone, String>,
    val approvedExamples: List<Example>,
    val forbidden: List<String>,
    val pushToRealPeople: Boolean,
    val enabledFromStage: Int,
) {
    fun enabledIn(stage: Int): Boolean = stage >= enabledFromStage
}
