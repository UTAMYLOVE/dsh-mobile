package app.dshmobile.ui.fog

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 外觀主題 (the owner, 2026-11-15: 「加進去二次元版粉粉嫩嫩的 顏色一樣這六種」, and 「所有功能都要可調」). [COSY] is the 0.8.0
 * look and stays the default; [KAWAII] adds soft floating petals, sparkles, hearts and clouds in the six colours.
 */
enum class BackdropStyle(val id: String) { COSY("cosy"), KAWAII("kawaii") }

/** Which part of a tall or wide picture stays on screen when it is cropped to the screen. */
enum class PictureAlign(val id: String, val bias: Float) { TOP("top", -1f), CENTER("center", 0f), BOTTOM("bottom", 1f) }

/**
 * The startup animation (off by default): 需要訪問許可 → 身份確認 → 核驗通過 → the welcome line. Blank texts show the app's
 * default wording; [seconds] is the whole animation; with [skippable] a tap ends it.
 */
data class Intro(
    val enabled: Boolean = false,
    val name: String = "",
    val number: String = "",
    val level: String = "",
    val welcome: String = "",
    val seconds: Float = 3f,
    val skippable: Boolean = true,
    /** The background picture (when there is one) as the round portrait on the welcome card. */
    val portrait: Boolean = true,
) {
    companion object {
        const val MIN_SECONDS = 1.5f
        const val MAX_SECONDS = 8f
        const val MAX_TEXT = 24
    }
}

/** Everything the 外觀主題 settings choose. Amounts are 0..100. */
data class Look(
    val style: BackdropStyle = BackdropStyle.COSY,
    val petals: Boolean = true,
    val sparkles: Boolean = true,
    val hearts: Boolean = true,
    val clouds: Boolean = true,
    val amount: Int = 50,
    val speed: Int = 40,
    /** Bumped whenever the picture changes (0 = none), so what shows it reloads. */
    val picture: Long = 0L,
    /** 0 = the picture's own colours, 100 = fully moved to the six colours (skin, white and shading always stay). */
    val six: Int = 100,
    /** How much of the deep reds turns light aqua instead of pink (水藍點綴). */
    val aqua: Int = 55,
    /** How strongly the picture shows over the pink ground. */
    val opacity: Int = 80,
    val align: PictureAlign = PictureAlign.CENTER,
    val intro: Intro = Intro(),
) {
    val hasPicture: Boolean get() = picture != 0L
}

val LocalLook: ProvidableCompositionLocal<Look> = staticCompositionLocalOf { Look() }

/** Where the background picture is kept ([LookController.pictureFile]); null shows none (tests, previews). */
val LocalLookPicture: ProvidableCompositionLocal<File?> = staticCompositionLocalOf { null }

/**
 * The background picture recoloured at [Look.six], one at a time: the background and the startup animation share it. The
 * recoloured copy is also kept next to the picture (one file, for the current picture and strengths), so a cold start decodes
 * it in a moment instead of recolouring for up to a second: the startup animation waits only 400 ms for the picture.
 */
object LookPictures {
    private val lock = Mutex()
    private var key: List<Any>? = null
    private var cached: ImageBitmap? = null

    suspend fun load(file: File, version: Long, six: Int, aqua: Int): ImageBitmap? = lock.withLock {
        val want = listOf(file.path, version, six, aqua)
        if (key == want) return@withLock cached
        val made = withContext(Dispatchers.Default) {
            if (six > 0) {
                val copy = recolouredFile(file, version, six, aqua)
                runCatching { BitmapFactory.decodeFile(copy.path) }.getOrNull()?.let { return@withContext it.asImageBitmap() }
            }
            val source = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return@withContext null
            if (six <= 0) return@withContext source.asImageBitmap()
            SixColour.recolour(source, six / 100f, aqua / 100f).also { source.recycle(); keep(file, it, recolouredFile(file, version, six, aqua)) }.asImageBitmap()
        }
        key = want
        cached = made
        made
    }

    private fun recolouredFile(file: File, version: Long, six: Int, aqua: Int) = File(file.parentFile, "${file.name}.six-$version-$six-$aqua.jpg")

    /** Every recoloured copy of [file] (removed with the picture, and replaced when the picture or its strengths change). */
    fun copies(file: File): List<File> = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.six-") }?.toList().orEmpty()

    /** True when [file] holds a PNG: the picture was kept as PNG because it has transparent parts (LookController.setPicture). */
    private fun isPng(file: File) = runCatching { file.inputStream().use { it.read() == 0x89 } }.getOrDefault(false)

    private fun keep(file: File, bitmap: Bitmap, to: File) = runCatching {
        copies(file).forEach { it.delete() }
        val tmp = File(to.path + ".tmp")
        // A cut-out picture's recoloured copy stays PNG too, or its transparent parts come back as a dark block.
        tmp.outputStream().use { bitmap.compress(if (isPng(file)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        if (!tmp.renameTo(to)) tmp.delete()
    }
}

/** The picture [look] asks for, or null; the last one stays on screen while a new strength is being worked out. */
@Composable
fun rememberLookPicture(look: Look): ImageBitmap? {
    val file = LocalLookPicture.current
    val shown = remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, look.picture, look.six, look.aqua) {
        shown.value = if (file == null || !look.hasPicture) null else LookPictures.load(file, look.picture, look.six, look.aqua)
    }
    return if (file != null && look.hasPicture) shown.value else null
}

/** The [Look] in the app's UI preferences ("look_*" keys); unknown or out-of-range values read as the defaults. */
class LookStore(private val prefs: SharedPreferences) {
    fun load(): Look {
        val d = Look()
        val di = Intro()
        fun bool(k: String, def: Boolean) = prefs.getBoolean(k, def)
        fun pct(k: String, def: Int) = prefs.getInt(k, def).coerceIn(0, 100)
        fun text(k: String) = prefs.getString(k, "").orEmpty().take(Intro.MAX_TEXT)
        return Look(
            style = BackdropStyle.entries.firstOrNull { it.id == prefs.getString(STYLE, null) } ?: d.style,
            petals = bool(PETALS, d.petals),
            sparkles = bool(SPARKLES, d.sparkles),
            hearts = bool(HEARTS, d.hearts),
            clouds = bool(CLOUDS, d.clouds),
            amount = pct(AMOUNT, d.amount),
            speed = pct(SPEED, d.speed),
            picture = prefs.getLong(PICTURE, 0L),
            six = pct(SIX, d.six),
            aqua = pct(AQUA, d.aqua),
            opacity = pct(OPACITY, d.opacity).coerceAtLeast(MIN_OPACITY),
            align = PictureAlign.entries.firstOrNull { it.id == prefs.getString(ALIGN, null) } ?: d.align,
            intro = Intro(
                enabled = bool(INTRO, di.enabled),
                name = text(INTRO_NAME),
                number = text(INTRO_NUMBER),
                level = text(INTRO_LEVEL),
                welcome = text(INTRO_WELCOME),
                seconds = prefs.getFloat(INTRO_SECONDS, di.seconds).let { if (it.isFinite()) it else di.seconds }.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS),
                skippable = bool(INTRO_SKIP, di.skippable),
                portrait = bool(INTRO_PORTRAIT, di.portrait),
            ),
        )
    }

    fun save(look: Look) {
        prefs.edit()
            .putString(STYLE, look.style.id)
            .putBoolean(PETALS, look.petals).putBoolean(SPARKLES, look.sparkles).putBoolean(HEARTS, look.hearts).putBoolean(CLOUDS, look.clouds)
            .putInt(AMOUNT, look.amount.coerceIn(0, 100)).putInt(SPEED, look.speed.coerceIn(0, 100))
            .putLong(PICTURE, look.picture)
            .putInt(SIX, look.six.coerceIn(0, 100)).putInt(AQUA, look.aqua.coerceIn(0, 100)).putInt(OPACITY, look.opacity.coerceIn(MIN_OPACITY, 100)).putString(ALIGN, look.align.id)
            .putBoolean(INTRO, look.intro.enabled)
            .putString(INTRO_NAME, look.intro.name.take(Intro.MAX_TEXT)).putString(INTRO_NUMBER, look.intro.number.take(Intro.MAX_TEXT))
            .putString(INTRO_LEVEL, look.intro.level.take(Intro.MAX_TEXT)).putString(INTRO_WELCOME, look.intro.welcome.take(Intro.MAX_TEXT))
            .putFloat(INTRO_SECONDS, look.intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS))
            .putBoolean(INTRO_SKIP, look.intro.skippable).putBoolean(INTRO_PORTRAIT, look.intro.portrait)
            .apply()
    }

    companion object {
        const val MIN_OPACITY = 10
        private const val STYLE = "look_style"
        private const val PETALS = "look_petals"
        private const val SPARKLES = "look_sparkles"
        private const val HEARTS = "look_hearts"
        private const val CLOUDS = "look_clouds"
        private const val AMOUNT = "look_amount"
        private const val SPEED = "look_speed"
        private const val PICTURE = "look_picture"
        private const val SIX = "look_six"
        private const val AQUA = "look_aqua"
        private const val OPACITY = "look_opacity"
        private const val ALIGN = "look_align"
        private const val INTRO = "look_intro"
        private const val INTRO_NAME = "look_intro_name"
        private const val INTRO_NUMBER = "look_intro_number"
        private const val INTRO_LEVEL = "look_intro_level"
        private const val INTRO_WELCOME = "look_intro_welcome"
        private const val INTRO_SECONDS = "look_intro_seconds"
        private const val INTRO_SKIP = "look_intro_skip"
        private const val INTRO_PORTRAIT = "look_intro_portrait"
    }
}

/**
 * The app's one [Look]: changes apply at once and are saved. The background picture is the user's own, picked on the phone and
 * kept only in the app's private files ([dir]), scaled down once ([MAX_SIDE]); it is never bundled or uploaded.
 */
class LookController(private val store: LookStore, private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val _look = MutableStateFlow(store.load().let { if (it.hasPicture && !File(dir, PICTURE_FILE).isFile) it.copy(picture = 0L) else it })
    val look: StateFlow<Look> = _look.asStateFlow()

    /** Bumped by 「預覽」 in Settings: the shell plays the startup animation once more. */
    private val _introTick = MutableStateFlow(0)
    val introTick: StateFlow<Int> = _introTick.asStateFlow()

    val pictureFile: File get() = File(dir, PICTURE_FILE)

    @Synchronized fun update(change: (Look) -> Look) {
        val next = change(_look.value)
        if (next == _look.value) return
        _look.value = next
        store.save(next)
    }

    /** Every choice back to its default; the picture (the user's own content, not a setting) stays. */
    fun restoreDefaults() = update { Look(picture = it.picture) }

    fun previewIntro() { _introTick.value++ }

    /**
     * Decodes [open]'s picture scaled down to at most [MAX_SIDE] pixels on its long side and keeps it; false when it is not a
     * picture Android can read (nothing changes then).
     */
    suspend fun setPicture(open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(open, MAX_SIDE) ?: return@withContext false
        dir.mkdirs()
        val tmp = File(dir, "$PICTURE_FILE.tmp")
        try {
            // JPEG has no alpha: a cut-out PNG (common for 二次元 art) kept its transparent parts as black, which the six colours
            // then turned into a dark block over the pink ground. The name stays picture.jpg; decodeFile reads the format itself.
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            tmp.outputStream().use { if (!bitmap.compress(format, 92, it)) return@withContext false }
            if (!tmp.renameTo(pictureFile)) return@withContext false
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
        update { it.copy(picture = now().coerceAtLeast(it.picture + 1)) }
        true
    }

    suspend fun removePicture() = withContext(Dispatchers.IO) {
        pictureFile.delete()
        LookPictures.copies(pictureFile).forEach { it.delete() }
        update { it.copy(picture = 0L) }
    }

    companion object {
        const val PICTURE_FILE = "picture.jpg"
        const val MAX_SIDE = 2048

        /**
         * [open] decoded with the largest power-of-two step that keeps the long side at or above [maxSide], then scaled to it and
         * turned upright. BitmapFactory ignores the EXIF orientation a phone camera writes instead of turning the pixels, so a
         * portrait photo came out on its side.
         */
        fun decodeScaled(open: () -> InputStream?, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { open()?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
            val decoded = runCatching { open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } }.getOrNull() ?: return null
            val matrix = upright(runCatching { open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull())
            val long = maxOf(decoded.width, decoded.height)
            if (long > maxSide) (maxSide.toFloat() / long).let { matrix.postScale(it, it) }
            if (matrix.isIdentity) return decoded
            // One pass for the scale and the turn (the same 2048 cap either way, since a turn keeps the long side).
            val out = runCatching { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) }.getOrNull()
            if (out == null) { decoded.recycle(); return null }
            if (out !== decoded) decoded.recycle()
            return out
        }

        /** The turn (and mirror) that shows a picture with EXIF [orientation] upright; identity for normal or unknown. */
        internal fun upright(orientation: Int?): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }
}
