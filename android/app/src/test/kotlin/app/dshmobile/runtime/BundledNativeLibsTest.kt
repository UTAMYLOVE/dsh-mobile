package app.dshmobile.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The proot that `fetchProot` puts into `src/main/jniLibs` must be loadable on a device: every shared library it (or a library
 * we bundle) names in DT_NEEDED has to be either part of Android itself or bundled next to it under that exact name. The
 * emulator run found `libandroid-shmem.so` missing (Termux's proot links it), so dsh never started.
 */
class BundledNativeLibsTest {
    private val jniLibs = File("src/main/jniLibs")

    @Test fun everyNeededLibraryOfTheBundledBinariesIsBundledOrPartOfAndroid() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val dir = File(jniLibs, abi)
            val bundled = dir.list().orEmpty().filter { it.endsWith(".so") }.toSet()
            assertTrue("$abi: proot is not bundled (fetchProot did not run?)", "libproot.so" in bundled)
            for (lib in bundled) {
                for (needed in neededLibraries(File(dir, lib).readBytes())) {
                    assertTrue("$abi/$lib needs $needed, which is neither bundled nor an Android system library", needed in ANDROID_LIBS || needed in bundled)
                }
            }
        }
    }

    @Test fun prootNeedsShmemAndTallocUnderTheirBundledNames() {
        val needed = neededLibraries(File(jniLibs, "x86_64/libproot.so").readBytes())
        assertTrue(needed.toString(), "libtalloc.so" in needed && "libandroid-shmem.so" in needed)
    }

    /** DT_NEEDED entries of a little-endian ELF64 shared object or executable, read through its section headers. */
    private fun neededLibraries(elf: ByteArray): List<String> {
        val b = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        check(b.getInt(0) == 0x464c457f && elf[4].toInt() == 2 && elf[5].toInt() == 1) { "not a little-endian ELF64 file" }
        val shoff = b.getLong(0x28).toInt()
        val shentsize = b.getShort(0x3A).toInt()
        val shnum = b.getShort(0x3C).toInt()
        fun section(i: Int) = shoff + i * shentsize
        val dynamic = (0 until shnum).map(::section).firstOrNull { b.getInt(it + 4) == SHT_DYNAMIC } ?: return emptyList()
        val strtab = section(b.getInt(dynamic + 0x28))
        val strOff = b.getLong(strtab + 0x18).toInt()
        val dynOff = b.getLong(dynamic + 0x18).toInt()
        val dynSize = b.getLong(dynamic + 0x20).toInt()
        val out = ArrayList<String>()
        for (e in dynOff until dynOff + dynSize step 16) {
            val tag = b.getLong(e)
            if (tag == DT_NULL) break
            if (tag == DT_NEEDED) {
                val start = strOff + b.getLong(e + 8).toInt()
                var end = start
                while (elf[end].toInt() != 0) end++
                out += String(elf, start, end - start, Charsets.US_ASCII)
            }
        }
        return out
    }

    private companion object {
        const val SHT_DYNAMIC = 6
        const val DT_NULL = 0L
        const val DT_NEEDED = 1L
        /** NDK stable system libraries an app's native code may link against. */
        val ANDROID_LIBS = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so")
    }
}
