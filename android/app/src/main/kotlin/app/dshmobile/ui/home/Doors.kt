package app.dshmobile.ui.home

import app.agency.core.agent.Attachment
import app.agency.core.stage.Feature
import app.dshmobile.R
import app.dshmobile.ui.PhotoUi

private val StageRegex = Regex("第[ 　]*([0-9]+)[ 　]*階段")

/** The stage number inside 「第 2 階段 · 第 3 天」; 1 when the label says nothing about it. */
fun stageOf(label: String): Int = StageRegex.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 1

/**
 * The pages that have no plan item of their own: they are reached from the 「其他」 row at the bottom of the unfolded handbook.
 * [feature] is the feature that must be open for the door to show (null = always).
 */
enum class Door(val label: Int, val feature: Feature?) {
    LESSONS(R.string.door_lessons, null),
    MEMO(R.string.door_memo, null),
    MONEY(R.string.door_money, null),
    TRAINEE(R.string.door_trainee, null),
    SINGING(R.string.door_singing, Feature.SINGING),
    EVAL(R.string.door_eval, Feature.WEEKLY_EVAL),
    BODY_PHOTOS(R.string.door_body_photos, Feature.BODY_PHOTO),
    ;

    companion object {
        /** The doors to show at [stage]. */
        fun at(stage: Int): List<Door> = entries.filter { it.feature?.openAt(stage) ?: true }
    }
}

/** What the camera's photo comes to, decided from what the camera was opened for. */
sealed interface PhotoRoute {
    /** Taken care of without the model; [line] is the NOTE line the company left (also shown quietly on screen). */
    data class Done(val line: String) : PhotoRoute

    /** Nothing was done and nothing goes to chat: tell him [text]. */
    data class Said(val text: String) : PhotoRoute

    /** The photo goes to 小葵 with a sentence that names [slot] ("FIRST", "DINNER", "NIGHT" or "" for no particular meal). */
    data class Chat(val slot: String) : PhotoRoute
}

object PhotoRouting {
    /**
     * [arg] is what `Tap.Open(CAMERA, arg)` or a card carried: "spot" (a spot check), "body" / "photo" (the Sunday body photo), a
     * meal slot name, or blank. Runs on an IO thread (it writes).
     *
     * - spot: the answer goes to the spot check only; with no check open he is told so; never to chat as a meal.
     * - body: the body photo is kept at once.
     * - a meal slot: a meal recorded without a photo takes it at once (no model call); a meal not recorded yet goes to chat.
     */
    fun decide(arg: String, photo: Attachment.Photo, photos: PhotoUi?, noSpot: String, noDoor: String): PhotoRoute {
        val key = arg.trim()
        return when {
            key.equals("spot", ignoreCase = true) -> {
                val line = photos?.spot(photo.photoId)
                if (line != null) PhotoRoute.Done(line) else PhotoRoute.Said(if (photos == null) noDoor else noSpot)
            }
            key.equals("body", ignoreCase = true) || key.equals("photo", ignoreCase = true) -> {
                val line = photos?.bodyPhoto(photo.photoId)
                if (line != null) PhotoRoute.Done(line) else PhotoRoute.Said(noDoor)
            }
            key.uppercase() in MealSlots -> {
                val line = photos?.mealPhoto(key.uppercase(), photo.photoId, photo.fromGallery)
                if (line != null) PhotoRoute.Done(line) else PhotoRoute.Chat(key.uppercase())
            }
            else -> PhotoRoute.Chat("")
        }
    }

    val MealSlots = setOf("FIRST", "DINNER", "NIGHT", "EXTRA")
}
