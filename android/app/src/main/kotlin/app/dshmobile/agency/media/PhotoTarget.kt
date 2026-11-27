package app.dshmobile.agency.media

import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.MediaPurpose
import app.agency.core.model.PhotoReadKind

/** What a photo is for (T15b/T15c): where the sealed shot goes, and what the image model reads in it. */
sealed interface PhotoTarget {
    val purpose: MediaPurpose
    val read: PhotoReadKind? get() = null

    /** 拍這餐: the meal being reported (read when it has no words yet). */
    data object Meal : PhotoTarget {
        override val purpose = MediaPurpose.MEAL
        override val read = PhotoReadKind.MEAL
    }

    data object Menu : PhotoTarget {
        override val purpose = MediaPurpose.MENU
        override val read = PhotoReadKind.MENU
    }

    /** A receipt or an order screenshot. */
    data object Receipt : PhotoTarget {
        override val purpose = MediaPurpose.RECEIPT
        override val read = PhotoReadKind.RECEIPT
    }

    /** 身體照: the teacher's words (same spot as the 入社建檔 photos). */
    data object Body : PhotoTarget {
        override val purpose = MediaPurpose.BODY
        override val read = PhotoReadKind.BODY
    }

    data class Lesson(val lessonId: String) : PhotoTarget {
        override val purpose = MediaPurpose.LESSON
    }

    data class Baseline(val step: OnboardingStep) : PhotoTarget {
        override val purpose = MediaPurpose.BASELINE
    }
}
