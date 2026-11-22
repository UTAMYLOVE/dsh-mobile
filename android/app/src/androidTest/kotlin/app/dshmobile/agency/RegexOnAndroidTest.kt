package app.dshmobile.agency

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.brain.Router
import app.agency.core.llm.InlineToolCallParser
import app.agency.core.memory.DueDay
import app.agency.core.memory.MemoryApplier
import app.agency.core.memory.PrivacyFilter
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's java.util.regex is ICU, not the JVM's engine: a pattern the JVM accepts can fail here (an unescaped `}` in
 * InlineToolCallParser did, CI run 44993645132), and `\b`, `\d`, `\w` mean other things. This compiles every Regex of `:core`
 * and of the app's agency code on the phone: each class is initialised (its patterns built), and every Regex or Pattern held
 * in a static field, a list or an object in a list is run against samples. Patterns built inside a function are run through
 * the function that builds them.
 */
@RunWith(AndroidJUnit4::class)
class RegexOnAndroidTest {
    private val samples = listOf(
        "今天 12:30 吃了 2 個雞腿便當，還剩 1071 大卡。",
        "<tool_call>{\"name\":\"get_today\",\"arguments\":{}}</tool_call> 看 https://example.test/a 或 photo-1.jpg",
        "下週末 11/20 晚上九點 sk-abc 0912345678 台北市信義路五段 7 號",
        "",
    )

    private val prefixes = listOf("app.agency.core.", "app.dshmobile.agency.", "app.dshmobile.ui.agency.", "app.dshmobile.ui.settings.", "app.dshmobile.ui.kit.")

    @Suppress("DEPRECATION")
    private fun classNames(): List<String> {
        val path = InstrumentationRegistry.getInstrumentation().targetContext.packageCodePath
        return dalvik.system.DexFile(path).entries().toList().filter { n -> prefixes.any { n.startsWith(it) } }
    }

    @Test fun everyPatternCompilesAndRunsOnAndroid() {
        val loader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        val names = classNames()
        assertTrue("classes found: ${names.size}", names.size > 100)
        val broken = mutableListOf<String>()
        var patterns = 0
        for (name in names) {
            val cls = try {
                Class.forName(name, true, loader)
            } catch (e: ExceptionInInitializerError) {
                if (generateSequence(e as Throwable) { it.cause }.any { it is PatternSyntaxException }) broken += "$name: ${e.cause}"
                continue
            } catch (_: Throwable) {
                continue // not a class this test can load (a synthetic or an Android-only one): its patterns, if any, fail elsewhere
            }
            val fields = try { cls.declaredFields.filter { Modifier.isStatic(it.modifiers) } } catch (_: Throwable) { emptyList() }
            for (f in fields) {
                val v = try { f.isAccessible = true; f.get(null) } catch (_: Throwable) { null } ?: continue
                for (p in held(v)) {
                    patterns++
                    try {
                        samples.forEach { s -> p.matcher(s).find() }
                    } catch (e: Throwable) {
                        broken += "$name.${f.name}: $e"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), broken)
        assertTrue("patterns found: $patterns", patterns > 60)
    }

    /** The Patterns in [v]: itself, the items of a list or map, and the Regex fields of an object held in a list (one level). */
    private fun held(v: Any, depth: Int = 0): List<Pattern> = when (v) {
        is Regex -> listOf(v.toPattern())
        is Pattern -> listOf(v)
        is Collection<*> -> v.filterNotNull().flatMap { held(it, depth + 1) }
        is Map<*, *> -> v.values.filterNotNull().flatMap { held(it, depth + 1) }
        is Array<*> -> v.filterNotNull().flatMap { held(it, depth + 1) }
        else -> if (depth == 0 || !v.javaClass.name.startsWith("app.")) emptyList() else v.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(v) }.getOrNull() }
            .flatMap { if (it is Regex || it is Pattern) held(it, depth + 1) else emptyList() }
    }

    @Test fun thePatternsBuiltInsideFunctionsWorkOnAndroid() {
        // InlineToolCallParser: the pattern that failed on the CI emulator.
        val parsed = InlineToolCallParser.parse("""<tool_call>{"name":"get_today","arguments":{}}</tool_call>""", setOf("get_today"), "t")
        assertNotNull(parsed)
        assertEquals("get_today", parsed!!.calls.single().name)
        assertEquals(LocalDate.parse("2026-11-28"), DueDay.resolve("下週末要去", LocalDate.parse("2026-11-19")))
        assertEquals("記1", MemoryApplier.ref("【記1】"))
        assertEquals(Router.route("喝完一壺！"), Router.route("喝完一壺"))
        // \b next to Han: a key glued to Chinese is still caught, the same as on the JVM.
        assertEquals("不能記金鑰、密碼、帳號、證件或卡號", PrivacyFilter.reason("我的sk-abcdef"))
    }
}
