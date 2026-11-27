package app.dshmobile.agency.media

import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * One photo through the system camera (arch 1.12, T07; a meal's with T15): [begin] (a 入社建檔 step) or [beginMeal] makes an empty `cache/camera/<uuid>.jpg`, saves which step and
 * file are waiting in [prefs] (so a result that arrives after the process died still finds them, and the start-up sweep keeps
 * that file), and returns the `ACTION_IMAGE_CAPTURE` intent that writes into it through the FileProvider ([uriFor]). [finish]
 * takes the camera's answer: on RESULT_OK the shot is downsized (≤ [MAX_PX] px, JPEG q[QUALITY], turned upright, which also
 * drops its metadata), sealed into the vault, and the plaintext deleted; on anything else the plaintext is deleted and the step
 * stays open. The plaintext never outlives [finish]. No CAMERA permission: the camera app takes the picture.
 */
class CameraCapture(
    private val cameraDir: File,
    private val prefs: SharedPreferences,
    private val uriFor: (File) -> Uri,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** A waiting capture: for a 入社建檔 [step], a 理論課 實作 ([lessonId], T19c), or (both null) for a meal (拍這餐). */
    data class Pending(val step: OnboardingStep?, val file: File, val lessonId: String? = null, val purpose: MediaPurpose? = null) {
        val meal: Boolean get() = step == null && lessonId == null && purpose == null
    }

    /**
     * The step (null: a meal), the lesson of a 實作, and the [Media] row of a sealed shot (not yet in the store: the caller
     * writes it with its link).
     */
    data class Captured(val step: OnboardingStep?, val media: Media, val lessonId: String? = null)

    /** 菜單, 收據 or 身體照 (T15b) through the system camera, when the in-app camera cannot run. */
    fun beginFor(purpose: MediaPurpose): Intent {
        require(purpose in READ_PURPOSES) { "not a photo the model reads" }
        return start(OTHER + purpose.name)
    }

    /** Prepares the file and returns the camera intent for a 入社建檔 photo; the pending capture is saved first. */
    fun begin(step: OnboardingStep): Intent {
        require(step in OnboardingStep.PHOTOS) { "not a photo step" }
        return start(step.name)
    }

    /** 拍這餐 (T15): the same round trip, for the meal being reported. */
    fun beginMeal(): Intent = start(MEAL)

    /** A 理論課 實作 photo (T19c): the same round trip, for lesson [lessonId]. */
    fun beginLesson(lessonId: String): Intent {
        require(ID.matches(lessonId)) { "not a lesson id" }
        return start(LESSON + lessonId)
    }

    private fun start(what: String): Intent {
        cameraDir.mkdirs()
        pending()?.file?.delete() // an older capture that never came back
        val file = File(cameraDir, newId() + ".jpg")
        file.createNewFile()
        check(prefs.edit().putString(KEY, what + SEP + file.name).commit()) { "could not save the pending capture" }
        val uri = uriFor(file)
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri("", uri) }
    }

    /** The capture waiting for the camera's answer, if any. */
    fun pending(): Pending? {
        val v = prefs.getString(KEY, null) ?: return null
        val what = v.substringBefore(SEP)
        val name = v.substringAfter(SEP, "")
        if (!NAME.matches(name)) return null
        if (what.startsWith(OTHER)) return MediaPurpose.entries.firstOrNull { it.name == what.removePrefix(OTHER) && it in READ_PURPOSES }?.let { Pending(null, File(cameraDir, name), purpose = it) }
        if (what.startsWith(LESSON)) return what.removePrefix(LESSON).takeIf { ID.matches(it) }?.let { Pending(null, File(cameraDir, name), it) }
        val step = if (what == MEAL) null else OnboardingStep.parse(what)?.takeIf { it in OnboardingStep.PHOTOS } ?: return null
        return Pending(step, File(cameraDir, name))
    }

    /** The file name the start-up sweep must keep (a result may still come for it). */
    fun pendingName(): String? = pending()?.file?.name

    /**
     * The camera's answer. Returns the sealed shot, or null (cancelled, nothing written, no pending capture). The plaintext and
     * the pending entry are gone afterwards, also when sealing throws (e.g. [app.dshmobile.agency.secure.DataLockedException]).
     */
    fun finish(ok: Boolean, vault: MediaVault): Captured? {
        val p = pending() ?: return null
        try {
            if (!ok || !p.file.isFile || p.file.length() == 0L) return null
            val id = newId()
            val bytes = downsize(p.file) ?: p.file.readBytes()
            val sealed = vault.put(id, bytes)
            bytes.fill(0)
            val purpose = when {
                p.purpose != null -> p.purpose
                p.lessonId != null -> MediaPurpose.LESSON
                p.meal -> MediaPurpose.MEAL
                else -> MediaPurpose.BASELINE
            }
            return Captured(p.step, Media(id, MediaKind.PHOTO, purpose, MIME, sealed.bytes, sealed.sha256, now()), p.lessonId)
        } finally {
            p.file.delete()
            prefs.edit().remove(KEY).commit()
        }
    }

    /** Forgets a capture that could not start (no camera app): its file goes too. */
    fun cancel() {
        pending()?.file?.delete()
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        const val KEY = "pending_capture"
        const val MEAL = "MEAL"
        const val LESSON = "LESSON~"
        const val OTHER = "PHOTO~"
        val READ_PURPOSES = setOf(MediaPurpose.MENU, MediaPurpose.RECEIPT, MediaPurpose.BODY)
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        const val MIME = "image/jpeg"
        const val MAX_PX = 1600
        const val QUALITY = 85
        private const val SEP = "|"
        private val NAME = Regex("[A-Za-z0-9-]{1,64}\\.jpg")

        /**
         * [file] decoded at most [MAX_PX] px on its long side, turned upright from its EXIF orientation, as JPEG q[QUALITY];
         * null when it cannot be decoded (the original bytes are sealed then).
         */
        fun downsize(file: File): ByteArray? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (longSide <= 0) return null
            var sample = 1
            while (longSide / (sample * 2) >= MAX_PX) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val scale = minOf(1f, MAX_PX.toFloat() / maxOf(decoded.width, decoded.height))
            val m = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            val upright = if (scale < 1f || rotation != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) else decoded
            val out = ByteArrayOutputStream()
            check(upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) { "not compressed" }
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
