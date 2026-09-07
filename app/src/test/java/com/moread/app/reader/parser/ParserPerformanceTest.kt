package com.moread.app.reader.parser

import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §4.1 解析性能 JVM 回归基线；真机 300ms 目标由 benchmark/ 宏基准覆盖。 */
class ParserPerformanceTest {
    @Test fun `parse 500KB document under JVM regression budget`() {
        val block = "# Section\n\nParagraph with **bold** and `code` and [link](https://example.com).\n\n" +
            "- item one\n- item two\n\n```kotlin\nfun main() { println(\"hello\") }\n```\n\n"
        val text = buildString {
            repeat(500_000 / block.length + 1) { append(block) }
        }
        val parser = MarkdownParser()
        parser.parse("# warmup")
        val start = System.nanoTime()
        val doc = parser.parse(text)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("parse 500KB took ${elapsedMs}ms", doc.blocks.isNotEmpty() && elapsedMs < 600.0)
    }
}
