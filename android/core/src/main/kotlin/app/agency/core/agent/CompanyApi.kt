package app.agency.core.agent

import app.agency.core.plan.DayPlan
import app.agency.core.plan.PlanItem
import app.agency.core.store.Message
import kotlinx.serialization.json.JsonObject

/** One of the three small stickers on the home screen. [key] is "eaten", "water" or "done" (stage 1: lessons-only variants). */
data class Sticker(val key: String, val label: String, val value: String)

/** Everything the home screen shows besides the conversation. */
data class Today(
    /** 「第 2 階段 · 第 3 天」. */
    val stageLabel: String,
    val stickers: List<Sticker>,
    val plan: DayPlan,
    /** Quick replies that fit this moment (at most four), each sent as his words when tapped. */
    val chips: List<String>,
    /** False until the rules are filled in and a key is saved: the app shows onboarding instead of home. */
    val ready: Boolean,
)

/** Something attached to what he says. Photos are already cleaned (downscaled, metadata stripped) by the app. */
sealed interface Attachment {
    /** [jpeg] is what may be sent to a model; [photoId] is the encrypted original in the media vault. */
    class Photo(val photoId: String, val jpeg: ByteArray, val fromGallery: Boolean) : Attachment

    /** A pasted or picked file, already read to text by the app (plain text, CSV, JSON, Markdown). */
    class File(val name: String, val text: String) : Attachment
}

/** What happened to one thing he said. */
sealed interface SayResult {
    /** 小葵 answered; the messages are in the store. */
    data object Ok : SayResult

    /** Not sent (offline, no key, spending cap…). His message is stored with `meta.failed = true` and can be resent. */
    data class NotSent(val reason: String) : SayResult
}

/**
 * The one door between the app and the company. The UI never touches the store, the rules or a model directly.
 * All functions are safe to call off the main thread; [say] suspends for the model.
 */
interface CompanyApi {
    fun today(): Today

    /** The newest [limit] messages before [beforeId], oldest first. */
    fun messages(limit: Int = 60, beforeId: Long? = null): List<Message>

    /** He said something (typed, spoken, a chip, a card's "say" tap). */
    suspend fun say(text: String, attachments: List<Attachment> = emptyList()): SayResult

    /** Sends a failed message again. */
    suspend fun resend(messageId: Long): SayResult

    /** A fallback tap: runs [tool] in code at once (no model call) and adds a NOTE line. Returns that line's text. */
    fun direct(tool: String, args: JsonObject): String

    /** A tap on a plan item, resolved to say / open / direct by the caller from [PlanItem.tap]. Kept here for symmetry. */
    fun item(id: String): PlanItem?

    /** He answered a card (a score dot, a proposal's yes/no, a video approved…). Code records it; 小葵 is told on the next turn. */
    suspend fun answerCard(messageId: Long, cardIndex: Int, answer: String): SayResult

    /**
     * Housekeeping to run when the app opens and on every alarm: closes finished days, judges a finished stage round, writes the
     * week's result, opens the next stage. Idempotent.
     */
    fun rollover()
}
