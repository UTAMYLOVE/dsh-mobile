package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自動 for the avatar square (the owner, seeing a community look's avatar show the chest of his full-body picture:
 * 「啊我的她勒」): the app's own guess of where the face is, worked out off the main thread once per picture version and kept
 * next to the picture, so a cold start reads it in a moment.
 *
 * First Android's own face finder on a small copy (it knows photos best; it rarely finds a drawn face); when it finds no face it
 * is sure enough of, a rule for portrait art: a tall picture is usually a full-body figure with the face near the top, anything
 * else a bust or a scene with the subject a little above the middle.
 */
object AvatarGuess {
    /** The face finder's least confidence (0..1) for its face to be used. */
    const val MIN_CONFIDENCE = .4f
    /** The face finder works on a copy at most this many pixels on its long side. */
    const val DETECT_SIDE = 512
    /** A face's avatar square: this many eye distances across. */
    const val FACE_SIDE = 5f
    /** The square's centre sits this many eye distances under the eyes, in the middle of the face. */
    const val FACE_DROP = .5f
    /** A picture at least this many times as tall as it is wide is a tall (full-body) picture. */
    const val TALL = 1.2f
    private const val MAX_FACES = 4

    private val lock = Mutex()
    @Volatile private var key: Pair<String, Long>? = null
    @Volatile private var cached: AvatarCrop? = null

    /**
     * The rule for when no face is found: a tall picture (h / w ≥ [TALL]) centred at (0.5, 0.21) with a side of 0.55 of its
     * width; any other at (0.5, 0.40) with 0.75 of its short side. Always inside the picture.
     */
    fun heuristic(width: Int, height: Int): AvatarCrop {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return if (h.toFloat() / w >= TALL) AvatarCrop(.5f, .21f, .55f).fitted(w, h) else AvatarCrop(.5f, .40f, .75f).fitted(w, h)
    }

    /**
     * The square for a face found on a [width] × [height] picture: centred [FACE_DROP] eye distances under the eyes' midpoint
     * ([eyesX], [eyesY], in that picture's pixels), [FACE_SIDE] eye distances ([eyes]) across, kept inside the picture.
     */
    fun fromFace(width: Int, height: Int, eyesX: Float, eyesY: Float, eyes: Float): AvatarCrop {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return AvatarCrop(eyesX / w, (eyesY + FACE_DROP * eyes) / h, FACE_SIDE * eyes / min(w, h)).fitted(w, h)
    }

    /**
     * The face finder on [picture]: an RGB_565 copy at most [DETECT_SIDE] on its long side and an even number of pixels wide
     * (what the finder needs; see-through parts on white). The face it is surest of, when at least [MIN_CONFIDENCE], else null.
     */
    fun detect(picture: Bitmap): AvatarCrop? = runCatching {
        val k = min(1f, DETECT_SIDE.toFloat() / max(picture.width, picture.height))
        val dw = (max(2, (picture.width * k).roundToInt()) / 2) * 2
        val dh = max(1, (picture.height * k).roundToInt())
        val small = Bitmap.createBitmap(dw, dh, Bitmap.Config.RGB_565)
        try {
            Canvas(small).apply {
                drawColor(Color.WHITE)
                drawBitmap(picture, null, Rect(0, 0, dw, dh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val n = FaceDetector(dw, dh, MAX_FACES).findFaces(small, faces)
            val best = faces.take(n.coerceIn(0, MAX_FACES)).filterNotNull()
                .filter { it.confidence() >= MIN_CONFIDENCE && it.eyesDistance() > 0f }
                .maxByOrNull { it.confidence() } ?: return@runCatching null
            val mid = PointF().also { best.getMidPoint(it) }
            fromFace(dw, dh, mid.x, mid.y, best.eyesDistance()).fitted(picture.width, picture.height)
        } finally {
            small.recycle()
        }
    }.getOrNull()

    /** [detect] on [picture], else [heuristic] for its shape. */
    fun guess(picture: Bitmap): AvatarCrop = detect(picture) ?: heuristic(picture.width, picture.height)

    /** The guess for [file] at [version] if it is already known in memory (no reading, no waiting), else null. */
    fun peek(file: File?, version: Long): AvatarCrop? = if (file != null && key == (file.path to version)) cached else null

    /**
     * The guess for the picture in [file] at [version]: from memory, from the copy kept next to the picture, or worked out now
     * (off the main thread) and kept. Null when there is no picture that can be read.
     */
    suspend fun auto(file: File, version: Long): AvatarCrop? = lock.withLock {
        val want = file.path to version
        if (key == want) cached?.let { return@withLock it }
        val made = withContext(Dispatchers.Default) {
            val kept = guessFile(file, version)
            read(kept) ?: compute(file)?.also { write(file, kept, it) }
        } ?: return@withLock null
        cached = made
        key = want
        made
    }

    /** The guesses kept next to [file] (one, for the current picture; they go with the picture). */
    fun files(file: File): List<File> = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.face-") }?.toList().orEmpty()

    private fun guessFile(file: File, version: Long) = File(file.parentFile, "${file.name}.face-$version.txt")

    private fun read(kept: File): AvatarCrop? = runCatching {
        if (!kept.isFile) return@runCatching null
        val v = kept.readText().trim().split(' ').map { it.toFloat() }
        if (v.size != 3 || v.any { !it.isFinite() }) null else AvatarCrop(v[0], v[1], v[2])
    }.getOrNull()

    private fun write(file: File, kept: File, crop: AvatarCrop) = runCatching {
        files(file).forEach { it.delete() }
        val tmp = File(kept.path + ".tmp")
        tmp.writeText(String.format(Locale.ROOT, "%.5f %.5f %.5f", crop.x, crop.y, crop.size))
        if (!tmp.renameTo(kept)) tmp.delete()
    }

    /** The guess for [file]'s picture, decoded only as large as the face finder needs; null when it cannot be read. */
    private fun compute(file: File): AvatarCrop? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= DETECT_SIDE) sample *= 2
        val small = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
            ?: return heuristic(w, h)
        return try {
            (detect(small) ?: heuristic(small.width, small.height)).fitted(w, h)
        } finally {
            small.recycle()
        }
    }
}

/**
 * The avatar square [look] uses: the one chosen in 調整頭像, else the guess for the picture in [file] (null while it is being
 * worked out, or with no picture).
 */
@Composable
fun rememberAvatarCrop(look: Look, file: File? = LocalLookPicture.current): AvatarCrop? {
    val guess = remember(file, look.picture) { mutableStateOf(AvatarGuess.peek(file, look.picture)) }
    val wanted = file != null && look.hasPicture && look.avatar == null
    LaunchedEffect(file, look.picture, wanted) {
        if (wanted && guess.value == null) guess.value = AvatarGuess.auto(file!!, look.picture)
    }
    return if (file == null || !look.hasPicture) look.avatar else look.avatar ?: guess.value
}

/**
 * The avatar square as a small picture of its own: what a community look gets as avatar.img. Cut from the picture exactly as
 * the app shows it, kept next to the picture under a name with the picture's version, the six-colour strengths and the square,
 * so a run streams a small file instead of decoding a big picture on the WebView's request thread.
 */
object AvatarCuts {
    /** The cut's side, in pixels. */
    const val SIDE = 512

    /**
     * The [SIDE] × [SIDE] square [crop] picks on [shown] (the picture as the app shows it: [file]'s recoloured copy at [six] and
     * [aqua], or [file] itself; null [crop]: the rule for its shape), as JPEG, or PNG when [file] was kept as PNG for its
     * see-through parts. Kept under a name with [version], the strengths and the square; an older cut is deleted when a new one
     * is made. Null when the picture cannot be read. Off the main thread.
     */
    fun cut(file: File, shown: File, version: Long, six: Int, aqua: Int, crop: AvatarCrop?): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shown.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sq = avatarSquare(bounds.outWidth, bounds.outHeight, crop ?: AvatarGuess.heuristic(bounds.outWidth, bounds.outHeight))
        val png = LookPictures.isPng(file)
        val name = String.format(
            Locale.ROOT, "%s.avatar-%d-%d-%d-%d-%d-%d.%s", file.name, version, six, aqua, sq.left, sq.top, sq.side, if (png) "png" else "jpg",
        )
        val out = File(file.parentFile, name)
        if (out.isFile && out.length() > 0) return@runCatching out
        val part = decodeSquare(shown, sq) ?: return@runCatching null
        val square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(part, null, Rect(0, 0, SIDE, SIDE), Paint(Paint.FILTER_BITMAP_FLAG))
        part.recycle()
        files(file).forEach { it.delete() }
        val tmp = File(out.path + ".tmp")
        try {
            tmp.outputStream().use { square.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            if (!tmp.renameTo(out)) return@runCatching null
        } finally {
            square.recycle()
            tmp.delete()
        }
        out
    }.getOrNull()

    /** The cuts of [file] kept next to it (replaced when the picture, its strengths or the square change). */
    fun files(file: File): List<File> = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.avatar-") }?.toList().orEmpty()

    /**
     * [sq] of [shown], decoded only as large as the cut needs: just that region where Android can (JPEG, PNG), else the whole
     * picture, sampled down, and then cut.
     */
    private fun decodeSquare(shown: File, sq: AvatarSquare): Bitmap? {
        var sample = 1
        while (sq.side / (sample * 2) >= SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val rect = Rect(sq.left, sq.top, sq.left + sq.side, sq.top + sq.side)
        runCatching {
            val region = BitmapRegionDecoder.newInstance(shown.path)
            try { region.decodeRegion(rect, opts) } finally { region.recycle() }
        }.getOrNull()?.let { return it }
        val whole = runCatching { BitmapFactory.decodeFile(shown.path, opts) }.getOrNull() ?: return null
        val l = (rect.left / sample).coerceIn(0, whole.width - 1)
        val t = (rect.top / sample).coerceIn(0, whole.height - 1)
        val s = (sq.side / sample).coerceIn(1, min(whole.width - l, whole.height - t))
        val part = runCatching { Bitmap.createBitmap(whole, l, t, s, s) }.getOrNull()
        // createBitmap hands back the same bitmap when the square is the whole picture.
        if (part !== whole) whole.recycle()
        return part
    }
}
