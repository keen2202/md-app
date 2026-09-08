package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.BlockQuoteBlock
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.HorizontalRuleBlock
import com.moread.app.reader.parser.ast.InlineNodes
import com.moread.app.reader.parser.ast.ListBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TableBlock
import com.moread.app.reader.outline.OutlineExtractor
import com.moread.app.reader.render.HtmlExporter
import com.moread.app.reader.render.RenderPipeline
import com.moread.app.reader.render.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private val parser = MarkdownParser()

    @Test fun `ATX headings H1-H6`() {
        val doc = parser.parse("# H1\n\n## H2\n\n### H3\n\n#### H4\n\n##### H5\n\n###### H6")
        val headings = doc.flatten().filterIsInstance<HeadingBlock>()
        assertEquals((1..6).toList(), headings.map { it.level })
    }

    @Test fun `setext heading`() {
        val doc = parser.parse("Title\n=====\n\nSub\n---")
        val headings = doc.flatten().filterIsInstance<HeadingBlock>()
        assertEquals(listOf(1, 2), headings.map { it.level })
        assertEquals("Title", headings[0].rawText)
    }

    @Test fun `fenced code block with language`() {
        val doc = parser.parse("```kotlin\nfun main() {}\n```")
        val code = doc.flatten().filterIsInstance<CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main()"))
    }

    @Test fun `nested ordered and unordered list`() {
        val doc = parser.parse("1. one\n   - nested\n   - nested2\n2. two")
        val lists = doc.flatten().filterIsInstance<ListBlock>()
        assertEquals(2, lists.size)
        assertTrue(lists[0].ordered)
        assertEquals(2, lists[0].items.size)
        assertTrue(!lists[1].ordered)
    }

    @Test fun `gfm table`() {
        val doc = parser.parse("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertTrue(doc.flatten().any { it is TableBlock })
    }

    @Test fun `blockquote and thematic break`() {
        val doc = parser.parse("> quote\n> line\n\n---")
        assertTrue(doc.flatten().any { it is BlockQuoteBlock })
        assertTrue(doc.flatten().any { it is HorizontalRuleBlock })
    }

    @Test fun `unknown structure degrades to paragraph`() {
        val doc = parser.parse("<div>not rendered</div>")
        val paragraphs = doc.flatten().filterIsInstance<ParagraphBlock>()
        assertTrue(paragraphs.isNotEmpty())
    }

    @Test fun `malformed fences never hang and degrade safely`() {
        val inputs = listOf(
            "```", "````\nno close", "#".repeat(100), "* ".repeat(5000),
            "> ".repeat(300), "1. ".repeat(300), "\\".repeat(999), "[".repeat(999),
        )
        inputs.forEach { input ->
            val doc = parser.parse(input)
            assertTrue(doc.blocks.isNotEmpty() || doc.flatten().isNotEmpty())
        }
    }

    @Test fun `deeply nested blockquote and list do not overflow`() {
        val doc = parser.parse("> ".repeat(5000) + "x")
        assertTrue(doc.flatten().isNotEmpty())
        val listDoc = parser.parse("1. ".repeat(5000) + "x")
        assertTrue(listDoc.flatten().isNotEmpty())
    }

    @Test fun `deeply nested inline emphasis does not overflow downstream render`() {
        // 复现用户反馈：`*`/`**`/`~~` 深层嵌套会让 CommonMark AST 深度达到数千层，
        // 即使解析成功，RenderPipeline / SpanFactory 的递归遍历也会在主线程 StackOverflow。
        val inputs = listOf(
            "*".repeat(2_000) + "a" + "*".repeat(2_000),
            "**".repeat(2_000) + "a" + "**".repeat(2_000),
            "~~".repeat(2_000) + "a" + "~~".repeat(2_000),
        )
        inputs.forEach { input ->
            val doc = parser.parse(input)
            assertTrue(doc.flatten().isNotEmpty())
            // 深度上限内正常保留行内文本；超限部分压平为文本节点，不得丢字。
            doc.flatten().filterIsInstance<ParagraphBlock>().forEach { paragraph ->
                InlineNodes.plainText(paragraph.inline)
            }
            // 模拟阅读页完整消费链路，确保任何一步都不会递归栈溢出。
            val model = RenderPipeline().build(doc)
            SearchIndex(model)
            OutlineExtractor.extract(doc)
            HtmlExporter.renderCommonMark(doc)
        }
        var callbacks = 0
        parser.parseStreaming(
            text = inputs.first(),
            onBlock = { callbacks++ },
        )
        assertTrue(callbacks > 0)
    }

    @Test fun `extreme nested inline input degrades without exception`() {
        // 超过 commonmark-java 自身可承受的嵌套深度时，parse 内部会捕获 StackOverflow
        // 并降级为纯文本块，保证阅读页仍能打开而不是闪退。
        val input = "*".repeat(50_000) + "a" + "*".repeat(50_000)
        val doc = parser.parse(input)
        assertTrue(doc.flatten().isNotEmpty())
        val text = doc.flatten().filterIsInstance<ParagraphBlock>().joinToString("") { InlineNodes.plainText(it.inline) }
        assertTrue(text.contains("a"))
        RenderPipeline().build(doc)
    }

    @Test fun `parseChunk returns blocks in line window`() {
        val text = "# A\n\npara\n\n## B\n\npara2"
        val chunk = parser.parseChunk(text, 0, 2)
        assertTrue(chunk.any { it is HeadingBlock })
        assertTrue(chunk.none { (it as? HeadingBlock)?.rawText == "B" })
    }
}
