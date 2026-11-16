package app.dshmobile.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.fail
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares a render with a golden PNG in `src/test/resources/<folder>/` (eng E14). Robolectric's native canvas picks its SIMD
 * code at run time, so a laptop and a CI runner may differ by a level here and there: a pixel only counts as different when one
 * of its channels differs by more than [LEVEL]; a golden passes when at most [SHARE] of its pixels differ and no channel of any
 * pixel differs by more than [WORST] (two palette roles differ by at least 27 in some channel, so a swapped role still fails).
 *
 * A missing golden is written to `app/build/classic-golden/` and fails (copy it into `src/test/resources/classic`); a mismatch
 * writes `app/build/classic-diff/<name>-actual.png` and `-diff.png` and fails with the first ten differing pixels and the CPU.
 */
object PixelCompare {
    const val LEVEL = 2
    const val SHARE = 0.001
    const val WORST = 16

    /** What two bitmaps of the same size differ by. [first] lists up to ten differing pixels, `x,y expected→actual`. */
    class Diff(val total: Int, val differing: Int, val worst: Int, val first: List<String>, val mask: BooleanArray) {
        val passes: Boolean get() = differing <= total * SHARE && worst <= WORST
    }

    /** The golden [name] of [folder] in the test resources, or null when there is none yet. */
    fun golden(name: String, folder: String = "classic"): Bitmap? =
        PixelCompare::class.java.classLoader!!.getResourceAsStream("$folder/$name.png")?.use { s ->
            BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }

    /**
     * [expected] against the rows of [actual] from [actualTop] (F6 compares the flat settings below its new block this way);
     * both must be as wide, and [actual] must hold all of [expected]'s rows from [actualTop].
     */
    fun diff(expected: Bitmap, actual: Bitmap, actualTop: Int = 0): Diff {
        val w = expected.width
        val h = expected.height
        val e = IntArray(w * h).also { expected.getPixels(it, 0, w, 0, 0, w, h) }
        val a = IntArray(w * h).also { actual.getPixels(it, 0, w, 0, actualTop, w, h) }
        val mask = BooleanArray(w * h)
        var differing = 0
        var worst = 0
        val first = ArrayList<String>()
        for (i in e.indices) {
            val pe = e[i]
            val pa = a[i]
            if (pe == pa) continue
            var d = 0
            for (shift in intArrayOf(24, 16, 8, 0)) d = max(d, abs(((pe ushr shift) and 0xFF) - ((pa ushr shift) and 0xFF)))
            if (d > worst) worst = d
            if (d > LEVEL) {
                differing++
                mask[i] = true
                if (first.size < 10) first += "${i % w},${i / w} ${hex(pe)}→${hex(pa)}"
            }
        }
        return Diff(w * h, differing, worst, first, mask)
    }

    /** Fails unless [actual] matches the golden [name] (see the class comment); a missing golden is written out and fails. */
    fun assertMatches(name: String, actual: Bitmap, folder: String = "classic", actualTop: Int = 0) {
        val expected = golden(name, folder)
        if (expected == null) {
            val out = File(buildDir(), "$folder-golden/$name.png")
            write(actual, out)
            fail("no golden $folder/$name.png: the render was written to ${out.path}; copy it into src/test/resources/$folder")
            return
        }
        val sizeOk = expected.width == actual.width && actual.height - actualTop >= expected.height &&
            (actualTop != 0 || actual.height == expected.height)
        if (!sizeOk) {
            val out = File(buildDir(), "$folder-diff/$name-actual.png")
            write(actual, out)
            fail("$name: the render is ${actual.width}×${actual.height} (from row $actualTop), the golden ${expected.width}×${expected.height}; render at ${out.path}")
            return
        }
        val d = diff(expected, actual, actualTop)
        if (d.passes) return
        val dir = File(buildDir(), "$folder-diff")
        write(actual, File(dir, "$name-actual.png"))
        write(diffPicture(expected, d), File(dir, "$name-diff.png"))
        fail(
            "$name differs from $folder/$name.png: ${d.differing} of ${d.total} pixels (allowed ${(d.total * SHARE).toInt()}), " +
                "worst channel difference ${d.worst} (allowed $WORST); first: ${d.first.joinToString("; ")}; CPU: ${cpu()}; " +
                "pictures in ${dir.path}",
        )
    }

    /** The golden greyed out, with every differing pixel in red. */
    private fun diffPicture(expected: Bitmap, d: Diff): Bitmap {
        val w = expected.width
        val h = expected.height
        val px = IntArray(w * h).also { expected.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in px.indices) {
            px[i] = if (d.mask[i]) 0xFFFF0000.toInt() else {
                val p = px[i]
                val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                val g = 160 + l * 95 / 255
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun write(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** `app/build` (Gradle runs unit tests in the module's folder). */
    private fun buildDir(): File = File(System.getProperty("user.dir") ?: ".", "build")

    private fun cpu(): String = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
    }.getOrNull() ?: (System.getProperty("os.arch") ?: "unknown")

    private fun hex(p: Int) = String.format("#%08X", p)
}
