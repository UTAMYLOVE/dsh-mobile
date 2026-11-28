package app.dshmobile.wire.ui

import android.os.Handler
import android.os.Looper
import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyAdmin
import app.agency.core.agent.MealPick
import app.agency.core.store.MealSlot
import app.dshmobile.platform.Notifier
import app.dshmobile.ui.FoodRow
import app.dshmobile.ui.MealUi
import app.dshmobile.ui.PhotoUi
import app.dshmobile.ui.PracticeUi
import app.dshmobile.ui.RealTaskView

/** What a photo is for: each call goes to the company's own door for it (none of them asks the model). */
class PhotoAdapter(private val admin: CompanyAdmin) : PhotoUi {
    override fun spot(photoId: String): String? = admin.spotAnswer(photoId)

    override fun mealPhoto(slot: String, photoId: String, fromGallery: Boolean): String? {
        val s = MealSlot.entries.firstOrNull { it.name == slot.uppercase() } ?: return null
        return admin.mealPhoto(s, photoId, fromGallery)
    }

    override fun bodyPhoto(photoId: String): String? = admin.bodyPhoto(photoId)
}

/** 「記這一餐」: his own food table, and the company's direct meal path. */
class MealAdapter(private val admin: CompanyAdmin) : MealUi {
    override fun foods(): List<FoodRow> = admin.rules().foods.map { FoodRow(it.name, it.kcal, it.proteinG) }

    override fun log(slot: String, picks: List<FoodRow>, photo: Attachment.Photo?): String? =
        admin.logMeal(slot, picks.map { MealPick(it.name, it.kcal, it.proteinG) }, photo?.photoId, photo?.fromGallery == true)
}

/** The real-person task without the model. */
class PracticeAdapter(private val admin: CompanyAdmin) : PracticeUi {
    override fun realTask(): RealTaskView? = admin.realTaskNow()?.let { RealTaskView(it.text, it.done) }

    override fun realTaskDone(): String? = admin.realTaskDone()
}

/**
 * One quiet notice 「練習還在計時，回來繼續」 when he has been away from a running timer for [delayMs]; taken away when he is back.
 * Posting is only a local notification, nothing leaves the phone.
 */
class TimerAway(
    private val notifier: Notifier,
    private val title: String,
    private val text: String,
    private val delayMs: Long = 20_000L,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val post = Runnable { runCatching { notifier.post(KEY, title, text, loud = false) } }

    fun left() {
        handler.removeCallbacks(post)
        handler.postDelayed(post, delayMs)
    }

    fun back() {
        handler.removeCallbacks(post)
        runCatching { notifier.cancel(KEY) }
    }

    companion object {
        const val KEY = "timer_away"
    }
}
