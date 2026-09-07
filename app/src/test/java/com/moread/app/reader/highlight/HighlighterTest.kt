package com.moread.app.reader.highlight

import com.moread.app.reader.highlight.lang.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterTest {
    private val highlighter = Highlighter()

    @Test fun `registry contains top 20 languages`() {
        val required = listOf(
            "js","ts","python","java","go","rust","c","cpp","shell","json","yaml",
            "html","css","sql","kotlin","swift","php","ruby","markdown","plaintext",
        )
        required.forEach { assertTrue("missing $it", LanguageRegistry.defFor(it).name != "plaintext" || it == "plaintext" || it == "markdown") }
        assertEquals(20, LanguageRegistry.all.size)
    }

    @Test fun `unknown language falls back to plaintext`() {
        val tokens = highlighter.highlight("fun main() {}", "unknown-lang")
        assertTrue(tokens.isEmpty())
    }

    @Test fun `kotlin tokens classified`() {
        val code = "fun main() {\n    // hi\n    val s = \"x\"\n    val n = 42\n}"
        val tokens = highlighter.highlight(code, "kotlin")
        assertTrue(tokens.any { it.type == TokenType.KEYWORD })
        assertTrue(tokens.any { it.type == TokenType.COMMENT })
        assertTrue(tokens.any { it.type == TokenType.STRING })
        assertTrue(tokens.any { it.type == TokenType.NUMBER })
    }

    @Test fun `every language tokenizes without exception`() {
        LanguageRegistry.all.forEach { def ->
            if (!def.plaintext) {
                val code = "fun main() {\n  // comment\n  val s = \"str\"\n  val n = 123\n}"
                highlighter.highlight(code, def.name)
            }
        }
    }

    @Test fun `single block highlight under 16ms`() {
        val code = (1..20).joinToString("\n") { "fun test$it() { println(\"hello $it\"); return ${it * 2}; } // line" }
        val start = System.nanoTime()
        repeat(10) { highlighter.highlight(code, "kotlin") }
        val perMs = (System.nanoTime() - start) / 1_000_000.0 / 10.0
        assertTrue("highlight took ${perMs}ms", perMs < 16.0)
    }
}
