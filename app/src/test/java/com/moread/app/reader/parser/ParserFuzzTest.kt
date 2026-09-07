package com.moread.app.reader.parser

import com.moread.app.reader.render.HtmlExporter
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** SPEC §7.4：随机/截断 Markdown 输入 1000 例零崩溃、零死循环。 */
class ParserFuzzTest {
    @Test fun `fuzz 1000 random inputs no exception no hang`() {
        val parser = MarkdownParser()
        val chars = "abc #*_`[]()!<>\\\n\t.-+|:~\u4E2D\u6587"
        repeat(1000) { round ->
            val length = Random(round).nextInt(0, 300)
            val input = buildString {
                repeat(length) {
                    append(chars[Random(round * 31 + it).nextInt(chars.length)])
                }
            }
            val doc = parser.parse(input)
            assertTrue(doc.flatten().isNotEmpty() || input.isEmpty())
            HtmlExporter.renderCommonMark(doc)

            // 自研后备解析器同样必须零崩溃、零死循环（SPEC §1.2 容错约束）。
            val legacyDoc = parser.parseLegacy(input)
            assertTrue(legacyDoc.flatten().isNotEmpty() || input.isEmpty())
        }
    }
}
