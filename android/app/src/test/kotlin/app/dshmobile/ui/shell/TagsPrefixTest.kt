package app.dshmobile.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's tag rule: a prefix tag (one that ends in `-` and gets an id appended) is never the start of another tag, or a test
 * looking for "every node whose tag starts with it" would also find the other one (e.g. `effect-` and `effect-lowered`).
 */
class TagsPrefixTest {
    @Test fun noPrefixTagStartsAnotherTag() {
        val values = Tags::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.name to it.get(null) as String }
        val clashes = values.filter { (_, p) -> p.endsWith("-") }.flatMap { (n, p) ->
            values.filter { (m, v) -> m != n && v.startsWith(p) }.map { (m, v) -> "$n=\"$p\" starts $m=\"$v\"" }
        }
        assertTrue(clashes.joinToString("; "), clashes.isEmpty())
    }
}
