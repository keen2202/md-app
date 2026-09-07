package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.CodeInline
import com.moread.app.reader.parser.ast.EmphasisInline
import com.moread.app.reader.parser.ast.ImageInline
import com.moread.app.reader.parser.ast.LinkInline
import com.moread.app.reader.parser.ast.StrikeInline
import com.moread.app.reader.parser.ast.StrongInline
import com.moread.app.reader.parser.ast.TextInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineParserTest {
    private val parser = MarkdownParser()

    private fun inline(md: String) = parser.parse(md).flatten().first { it is com.moread.app.reader.parser.ast.ParagraphBlock }.let {
        (it as com.moread.app.reader.parser.ast.ParagraphBlock).inline
    }

    @Test fun `bold italic strike inline code`() {
        val nodes = inline("**bold** *italic* ~~strike~~ `code`")
        assertTrue(nodes.any { it is StrongInline })
        assertTrue(nodes.any { it is EmphasisInline })
        assertTrue(nodes.any { it is StrikeInline })
        assertTrue(nodes.any { it is CodeInline })
    }

    @Test fun `inline link and image`() {
        val nodes = inline("[site](https://example.com \"t\") and ![alt](img/a.png)")
        val link = nodes.filterIsInstance<LinkInline>().single()
        val image = nodes.filterIsInstance<ImageInline>().single()
        assertEquals("https://example.com", link.url)
        assertEquals("img/a.png", image.url)
    }

    @Test fun `reference link`() {
        val doc = parser.parse("[foo][id]\n\n[id]: https://example.com")
        val paragraph = doc.flatten().filterIsInstance<com.moread.app.reader.parser.ast.ParagraphBlock>().single()
        val link = paragraph.inline.filterIsInstance<LinkInline>().single()
        assertEquals("https://example.com", link.url)
    }

    @Test fun `html inline stays as escaped text`() {
        val nodes = inline("a <b>bold</b> c")
        assertTrue(nodes.any { it is com.moread.app.reader.parser.ast.HtmlInline })
        assertTrue(nodes.filterIsInstance<TextInline>().none { it.text == "<b>" })
    }

    @Test fun `unclosed structures become text`() {
        val nodes = inline("a *foo [link](url `code")
        assertTrue(nodes.isNotEmpty())
    }
}
