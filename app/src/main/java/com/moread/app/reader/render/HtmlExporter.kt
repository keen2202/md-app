package com.moread.app.reader.render

import com.moread.app.reader.parser.ast.BlockQuoteBlock
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.ColumnAlign
import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.EmphasisInline
import com.moread.app.reader.parser.ast.HardBreakInline
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.HorizontalRuleBlock
import com.moread.app.reader.parser.ast.HtmlInline
import com.moread.app.reader.parser.ast.ImageBlock
import com.moread.app.reader.parser.ast.ImageInline
import com.moread.app.reader.parser.ast.LinkInline
import com.moread.app.reader.parser.ast.ListBlock
import com.moread.app.reader.parser.ast.ListItemBlock
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.MdInline
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.SoftBreakInline
import com.moread.app.reader.parser.ast.StrikeInline
import com.moread.app.reader.parser.ast.StrongInline
import com.moread.app.reader.parser.ast.TableBlock
import com.moread.app.reader.parser.ast.TextInline
import com.moread.app.theme.ColorPalette

/**
 * AST → HTML 导出（F-11/T17）。
 *
 * [renderStyled] 生成内联样式 HTML（当前主题色板），供系统分享后在浏览器查看；
 * [renderCommonMark] 生成无样式标准 HTML，同时作为 CommonMark spec 测试的比对输出。
 */
object HtmlExporter {

    fun renderStyled(document: DocumentBlock, palette: ColorPalette): String = buildString {
        append(
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{margin:0;padding:24px;background:${palette.cssColor(palette.background)};" +
                "color:${palette.cssColor(palette.textPrimary)};font-family:sans-serif;line-height:1.7;max-width:820px;margin:0 auto;}" +
                "pre{background:${palette.cssColor(palette.card)};padding:14px;border-radius:8px;overflow-x:auto;}" +
                "code{font-family:monospace;background:${palette.cssColor(palette.card)};padding:2px 5px;border-radius:5px;}" +
                "pre code{background:none;padding:0;}blockquote{border-left:4px solid ${palette.cssColor(palette.accent)};" +
                "margin:0;padding:4px 16px;color:${palette.cssColor(palette.textSecondary)};}" +
                "table{border-collapse:collapse;display:block;overflow-x:auto;}th,td{border:1px solid ${palette.cssColor(palette.divider)};padding:8px 12px;}" +
                "img{max-width:100%;}hr{border:0;border-top:1px solid ${palette.cssColor(palette.divider)};}" +
                "a{color:${palette.cssColor(palette.accent)};}</style></head><body>",
        )
        document.blocks.forEach { appendBlock(it, styled = true, palette = palette) }
        append("</body></html>")
    }

    fun renderCommonMark(document: DocumentBlock): String = buildString {
        document.blocks.forEach { appendBlock(it, styled = false, palette = null) }
    }

    private fun StringBuilder.appendBlock(block: MdBlock, styled: Boolean, palette: ColorPalette?) {
        when (block) {
            is HeadingBlock -> {
                val tag = "h${block.level.coerceIn(1, 6)}"
                append("<$tag>")
                block.inline.forEach { appendInline(it, styled, palette) }
                append("</$tag>\n")
            }
            is ParagraphBlock -> {
                append("<p>")
                block.inline.forEach { appendInline(it, styled, palette) }
                append("</p>\n")
            }
            is CodeBlock -> {
                val cls = if (block.language.isBlank()) "" else " class=\"language-${escapeHtml(block.language)}\""
                append("<pre><code$cls>${escapeHtml(block.code)}\n</code></pre>\n")
            }
            is BlockQuoteBlock -> {
                append("<blockquote>\n")
                block.blocks.forEach { appendBlock(it, styled, palette) }
                append("</blockquote>\n")
            }
            is ListBlock -> {
                if (block.ordered) append("<ol start=\"${block.start}\">\n") else append("<ul>\n")
                block.items.forEach { item ->
                    append("<li>")
                    if (item.children.size == 1) {
                        appendBlockInline(item.children[0], styled, palette)
                    } else {
                        append("\n")
                        item.children.forEach { appendBlock(it, styled, palette) }
                    }
                    append("</li>\n")
                }
                if (block.ordered) append("</ol>\n") else append("</ul>\n")
            }
            is ListItemBlock -> block.children.forEach { appendBlock(it, styled, palette) }
            is TableBlock -> {
                append("<table>\n<thead>\n<tr>")
                block.headers.forEach { cell -> append("<th${alignAttr(cell.align)}>"); cell.inline.forEach { appendInline(it, styled, palette) }; append("</th>") }
                append("</tr>\n</thead>\n<tbody>\n")
                block.rows.forEach { row ->
                    append("<tr>")
                    row.forEachIndexed { idx, cell ->
                        val align = block.headers.getOrNull(idx)?.align ?: ColumnAlign.LEFT
                        append("<td${alignAttr(align)}>")
                        cell.inline.forEach { appendInline(it, styled, palette) }
                        append("</td>")
                    }
                    append("</tr>\n")
                }
                append("</tbody>\n</table>\n")
            }
            is HorizontalRuleBlock -> append("<hr />\n")
            is ImageBlock -> append("<p><img src=\"${escapeAttr(percentEncodeUrl(block.image.url))}\" alt=\"${escapeAttr(block.image.alt)}\" /></p>\n")
            is DocumentBlock -> block.blocks.forEach { appendBlock(it, styled, palette) }
            else -> append("<p>").append(escapeHtml(block.rawText)).append("</p>\n")
        }
    }

    /** 列表项仅有一个段落子节点时，去掉外层 <p>，输出 CommonMark 兼容的紧凑列表。 */
    private fun StringBuilder.appendBlockInline(block: MdBlock, styled: Boolean, palette: ColorPalette?) {
        if (block is ParagraphBlock) {
            block.inline.forEach { appendInline(it, styled, palette) }
        } else {
            append("\n")
            appendBlock(block, styled, palette)
        }
    }

    private fun alignAttr(align: ColumnAlign): String = when (align) {
        ColumnAlign.CENTER -> " style=\"text-align:center\" align=\"center\""
        ColumnAlign.RIGHT -> " style=\"text-align:right\" align=\"right\""
        ColumnAlign.LEFT -> ""
    }

    private fun StringBuilder.appendInline(node: MdInline, styled: Boolean, palette: ColorPalette?) {
        when (node) {
            is TextInline -> append(escapeHtml(node.text))
            is StrongInline -> {
                append("<strong>")
                node.children.forEach { appendInline(it, styled, palette) }
                append("</strong>")
            }
            is EmphasisInline -> {
                append("<em>")
                node.children.forEach { appendInline(it, styled, palette) }
                append("</em>")
            }
            is StrikeInline -> {
                append("<del>")
                node.children.forEach { appendInline(it, styled, palette) }
                append("</del>")
            }
            is com.moread.app.reader.parser.ast.CodeInline -> append("<code>${escapeHtml(node.code)}</code>")
            is LinkInline -> {
                val title = if (node.title.isNullOrBlank()) "" else " title=\"${escapeAttr(node.title)}\""
                append("<a href=\"${escapeAttr(percentEncodeUrl(node.url))}\"$title>")
                node.children.forEach { appendInline(it, styled, palette) }
                append("</a>")
            }
            is ImageInline -> append("<img src=\"${escapeAttr(percentEncodeUrl(node.url))}\" alt=\"${escapeAttr(node.alt)}\" />")
            is SoftBreakInline -> append("\n")
            is HardBreakInline -> append("<br />\n")
            is HtmlInline -> append(escapeHtml(node.html))
        }
    }

    fun escapeHtml(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    fun escapeAttr(text: String): String = escapeHtml(text).replace("'", "&#39;")

    /** CommonMark 对 URL 中的非 ASCII 字符按 UTF-8 字节做百分号编码。 */
    fun percentEncodeUrl(url: String): String {
        val sb = StringBuilder(url.length)
        for (ch in url) {
            when {
                ch.code < 0x80 && ch !in "\"<>`" -> sb.append(ch)
                ch.code < 0x80 -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    bytes.forEach { b -> sb.append('%').append(((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))) }
                }
            }
        }
        return sb.toString()
    }

    private fun ColorPalette.cssColor(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "#%02x%02x%02x".format(r, g, b)
    }
}
