package app.agency.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a line of the conversation can carry besides words. Cards are made by code (tools), never by the model's free text, so
 * every number on a card is a computed one. The UI renders each kind; a tap on a card comes back through [CompanyApi].
 */
@Serializable
sealed class Card {
    /** Five dots, 1..5. [ask] says what for ("check_in", "mood"); [picked] once he chose. */
    @Serializable
    @SerialName("score")
    data class Score(val ask: String, val picked: Int? = null) : Card()

    /** A recorded meal with code-computed numbers. */
    @Serializable
    @SerialName("meal")
    data class MealCard(
        val entryId: Long,
        val title: String,
        val kcal: Int,
        val proteinG: Double,
        val dayKcal: Int,
        val dayTargetKcal: Int,
        val dayProteinG: Double,
        val dayTargetProteinG: Int,
        /** Some number is a strict estimate, not from his table or a label. */
        val estimated: Boolean,
        val needsPhoto: Boolean,
        /** Short warnings in his language (over the meal cap, a banned item…). */
        val warnings: List<String> = emptyList(),
    ) : Card()

    enum class Answer { OPEN, YES, NO }

    /** Something that needs his yes before it changes (a plan change from tomorrow on, a new habit, more kcal…). */
    @Serializable
    @SerialName("proposal")
    data class Proposal(
        val entryId: Long,
        val title: String,
        val body: String,
        val answer: Answer = Answer.OPEN,
        /** The word on the yes button when it is not 「好」 (an import's 「復原」 card); empty = the usual 好／不要. */
        val yesLabel: String = "",
    ) : Card()

    /** [page] marks a teaching page that is not a video (an article or a course page). */
    @Serializable
    data class Video(
        val lessonId: Long,
        val subject: String,
        val title: String,
        val url: String,
        val minutes: Int? = null,
        val page: Boolean = false,
    )

    /** Real search results for lessons; he approves each before it joins the list. */
    @Serializable
    @SerialName("videos")
    data class Videos(val items: List<Video>, val approved: List<Long> = emptyList(), val declined: List<Long> = emptyList()) : Card()

    @Serializable
    data class Place(val name: String, val url: String, val summary: String = "")

    /** Real search results for places to eat. */
    @Serializable
    @SerialName("places")
    data class Places(val items: List<Place>) : Card()

    /** Opens a screen: a timer, the quiz, the camera… [screen] is a [app.agency.core.plan.Screen] name. */
    @Serializable
    @SerialName("open")
    data class Open(val screen: String, val arg: String = "", val label: String) : Card()

    /** A titled block of computed lines (today so far, the week's result, what changed today). */
    @Serializable
    @SerialName("summary")
    data class Summary(val title: String, val lines: List<String>) : Card()

    /** A moment worth a full-width sticker: a stage opening, a level-up, a photocard earned, a special day. */
    @Serializable
    @SerialName("ceremony")
    data class Ceremony(val kind: String, val title: String, val text: String) : Card()

    /** A photo he sent, by media-vault id. */
    @Serializable
    @SerialName("photo")
    data class Photo(val photoId: String, val purpose: String = "") : Card()

    /** A file he sent: its name and what was read from it (shown folded). */
    @Serializable
    @SerialName("file")
    data class File(val name: String, val chars: Int) : Card()
}
