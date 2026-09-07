package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.BlockQuoteBlock
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.ColumnAlign
import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.EmphasisInline
import com.moread.app.reader.parser.ast.HardBreakInline
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.HorizontalRuleBlock
import com.moread.app.reader.parser.ast.HtmlInline as AstHtmlInline
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
import com.moread.app.reader.parser.ast.TableCell
import com.moread.app.reader.parser.ast.TextInline
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock as GfmTableBlock
import org.commonmark.ext.gfm.tables.TableCell as GfmTableCell
import org.commonmark.ext.gfm.tables.TableHead as GfmTableHead
import org.commonmark.ext.gfm.tables.TableRow as GfmTableRow
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline as CmHtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * CommonMark 参考解析器 → 墨阅 AST 转换器（SPEC §1.2 备选路线）。
 * 保留源码行列信息用于 blockIndex / 搜索偏移 / 阅读进度。
 */
class CommonmarkAstConverter(private val source: SourceLines) {

    fun convert(document: Document): DocumentBlock {
        val root = DocumentBlock()
        document.firstChild?.let { first ->
            var node: Node? = first
            while (node != null) {
                val block = convertBlock(node)
                if (block != null) root.blocks.add(block)
                node = node.next
            }
        }
        return root
    }

    private fun convertBlock(node: Node): MdBlock? = when (node) {
        is Heading -> HeadingBlock(node.level, convertInlines(node.firstChild), plainTextOf(node)).also { applyBlockMeta(it, node) }
        is Paragraph -> ParagraphBlock(convertInlines(node.firstChild), rawText(node)).also { applyBlockMeta(it, node) }
        is FencedCodeBlock -> CodeBlock(
            code = node.literal.orEmpty(),
            language = firstInfoWord(node.info),
            fenced = true,
            fenceInfo = node.info.orEmpty(),
            rawText = rawText(node),
        ).also { applyBlockMeta(it, node) }
        is IndentedCodeBlock -> CodeBlock(
            code = node.literal.orEmpty(),
            language = "",
            fenced = false,
            rawText = rawText(node),
        ).also { applyBlockMeta(it, node) }
        is BlockQuote -> {
            val children = childBlocks(node)
            BlockQuoteBlock(children).also { applyBlockMeta(it, node) }
        }
        is BulletList -> convertList(node, ordered = false, start = 1)
        is OrderedList -> convertList(node, ordered = true, start = node.startNumber)
        is ThematicBreak -> HorizontalRuleBlock(rawText(node)).also { applyBlockMeta(it, node) }
        is HtmlBlock -> ParagraphBlock(listOf(AstHtmlInline(node.literal.orEmpty())), node.literal.orEmpty()).also { applyBlockMeta(it, node) }
        is GfmTableBlock -> convertTable(node)
        else -> null
    }

    private fun convertList(node: Node, ordered: Boolean, start: Int): ListBlock {
        val items = ArrayList<ListItemBlock>()
        var child = node.firstChild
        while (child != null) {
            if (child is ListItem) {
                val itemChildren = childBlocks(child)
                val item = ListItemBlock(itemChildren)
                applyBlockMeta(item, child)
                items.add(item)
            }
            child = child.next
        }
        return ListBlock(ordered, start, items).also { applyBlockMeta(it, node) }
    }

    private fun convertTable(node: GfmTableBlock): TableBlock {
        val headers = ArrayList<TableCell>()
        val rows = ArrayList<List<TableCell>>()
        var section = node.firstChild
        while (section != null) {
            var rowNode = section.firstChild
            while (rowNode != null) {
                if (rowNode is GfmTableRow) {
                    val cells = rowNode.firstChild.let { first ->
                        buildList {
                            var cell = first
                            while (cell != null) {
                                if (cell is GfmTableCell) add(convertCell(cell))
                                cell = cell.next
                            }
                        }
                    }
                    if (section is GfmTableHead) headers.addAll(cells) else rows.add(cells)
                }
                rowNode = rowNode.next
            }
            section = section.next
        }
        return TableBlock(headers, rows, rawText(node)).also { applyBlockMeta(it, node) }
    }

    private fun convertCell(cell: GfmTableCell): TableCell {
        val align = when (cell.alignment) {
            GfmTableCell.Alignment.CENTER -> ColumnAlign.CENTER
            GfmTableCell.Alignment.RIGHT -> ColumnAlign.RIGHT
            else -> ColumnAlign.LEFT
        }
        return TableCell(convertInlines(cell.firstChild), rawText(cell), align)
    }

    private fun childBlocks(parent: Node): MutableList<MdBlock> {
        val out = ArrayList<MdBlock>()
        var child = parent.firstChild
        while (child != null) {
            convertBlock(child)?.let { out.add(it) }
            child = child.next
        }
        return out
    }

    private fun convertInlines(first: Node?): List<MdInline> {
        val out = ArrayList<MdInline>()
        var node = first
        while (node != null) {
            convertInline(node)?.let { out.add(it) }
            node = node.next
        }
        return out
    }

    private fun convertInline(node: Node): MdInline? = when (node) {
        is Text -> TextInline(node.literal.orEmpty()).also { applyInlineMeta(it, node) }
        is StrongEmphasis -> StrongInline(convertInlines(node.firstChild)).also { applyInlineMeta(it, node) }
        is Emphasis -> EmphasisInline(convertInlines(node.firstChild)).also { applyInlineMeta(it, node) }
        is Code -> com.moread.app.reader.parser.ast.CodeInline(node.literal.orEmpty()).also { applyInlineMeta(it, node) }
        is Link -> LinkInline(node.destination.orEmpty(), node.title, convertInlines(node.firstChild)).also { applyInlineMeta(it, node) }
        is Image -> ImageInline(node.destination.orEmpty(), node.title.orEmpty()).also { applyInlineMeta(it, node) }
        is SoftLineBreak -> SoftBreakInline().also { applyInlineMeta(it, node) }
        is HardLineBreak -> HardBreakInline().also { applyInlineMeta(it, node) }
        is CmHtmlInline -> AstHtmlInline(node.literal.orEmpty()).also { applyInlineMeta(it, node) }
        is Strikethrough -> StrikeInline(convertInlines(node.firstChild)).also { applyInlineMeta(it, node) }
        else -> null
    }

    private fun firstInfoWord(info: String?): String {
        val s = info.orEmpty().trim()
        if (s.isEmpty()) return ""
        var end = 0
        while (end < s.length && !s[end].isWhitespace() && s[end] != '{') end++
        return s.substring(0, end).lowercase()
    }

    // ------------------------------------------------------------------ 源码偏移

    private fun applyBlockMeta(block: MdBlock, node: Node) {
        val spans = node.sourceSpans
        if (spans.isNotEmpty()) {
            val first = spans.first()
            val last = spans.last()
            block.startLine = first.lineIndex
            block.endLine = last.lineIndex
            block.startOffset = source.lineStart(first.lineIndex) + first.columnIndex
            block.endOffset = source.lineStart(last.lineIndex) + last.columnIndex + last.length
        }
    }

    private fun applyInlineMeta(inline: MdInline, node: Node) {
        val spans = node.sourceSpans
        if (spans.isNotEmpty()) {
            val first = spans.first()
            val last = spans.last()
            inline.startOffset = source.lineStart(first.lineIndex) + first.columnIndex
            inline.endOffset = source.lineStart(last.lineIndex) + last.columnIndex + last.length
        }
    }

    private fun rawText(node: Node): String {
        val spans = node.sourceSpans
        if (spans.isNotEmpty()) {
            val first = spans.first()
            val last = spans.last()
            val start = source.lineStart(first.lineIndex) + first.columnIndex
            val end = source.lineStart(last.lineIndex) + last.columnIndex + last.length
            if (start in 0..end && end <= source.text.length) return source.text.substring(start, end)
        }
        // 容器节点无 source span 时，退回纯文本，保证搜索与导出可用。
        return plainTextOf(node)
    }

    private fun plainTextOf(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is Text -> sb.append(n.literal.orEmpty())
                is Code -> sb.append(n.literal.orEmpty())
                is CmHtmlInline -> sb.append(n.literal.orEmpty())
                is SoftLineBreak, is HardLineBreak -> sb.append('\n')
                else -> Unit
            }
            var child = n.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(node)
        return sb.toString()
    }
}
