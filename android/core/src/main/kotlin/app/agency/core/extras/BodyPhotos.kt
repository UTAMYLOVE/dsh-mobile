package app.agency.core.extras

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.store.Body
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.add
import java.time.DayOfWeek
import java.time.LocalDate

/** One point of the photo timeline: [photoId] is a media-vault id. */
data class BodyShot(val day: LocalDate, val photoId: String, val weightKg: Double?, val waistCm: Double?)

/** The Sunday same-angle full-body photo and the timeline of every body photo taken so far. The photos never leave the phone. */
class BodyPhotos(private val store: Store, private val clock: Clock) {

    fun isPhotoDay(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SUNDAY

    fun doneOn(day: LocalDate): Boolean =
        store.entries(day, Kind.BODY).any { runCatching { it.read<Body>() }.getOrNull()?.photoId != null }

    /** Keeps today's photo. */
    fun record(photoId: String): Long {
        val now = clock.now()
        return store.add(Days.of(now, clock.zone), now, Kind.BODY, Body(photoId = photoId)).id
    }

    /** Every body photo (the Sunday ones and the monthly ones) oldest first, from [since]. */
    fun timeline(since: LocalDate = LocalDate.of(2000, 1, 1)): List<BodyShot> =
        store.entries(since, LocalDate.of(2200, 1, 1), Kind.BODY).mapNotNull { e ->
            val b = runCatching { e.read<Body>() }.getOrNull() ?: return@mapNotNull null
            b.photoId?.let { BodyShot(e.day, it, b.kg, b.waistCm) }
        }
}
