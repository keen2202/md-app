package com.moread.app.reader.render

import com.moread.app.reader.parser.ast.BlockQuoteBlock
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.HorizontalRuleBlock
import com.moread.app.reader.parser.ast.ImageBlock
import com.moread.app.reader.parser.ast.InlineNodes
import com.moread.app.reader.parser.ast.ListBlock
import com.moread.app.reader.parser.ast.ListItemBlock
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TableBlock

enum class ItemKind { HEADING, PARAGRAPH, CODE, TABLE, IMAGE, HR }

/**
 * 渲染 item：RecyclerView 中一个 item 对应一个或多个 AST 块。
 * - 普通块 1:1；
 * - 相邻短段落按 T16 策略合并为 1 个 item，`blocks` 持有合并前的块序列。
 */
data class RenderItem(
    val kind: ItemKind,
    val blocks: List<MdBlock>,
    val indent: Int,
    val marker: String? = null,
    val quoteDepth: Int = 0,
) {
    val primaryBlock: MdBlock get() = blocks.first()
    val startLine: Int get() = primaryBlock.startLine
    val endLine: Int get() = blocks.last().endLine

    /** 每个组成块在合并后 item 文本中的偏移区间（供 T15 搜索高亮）。 */
    fun blockTextRanges(): List<BlockTextRange> {
        if (blocks.size == 1) {
            return listOf(BlockTextRange(blocks[0], 0, itemTextLength(blocks[0])))
        }
        val ranges = ArrayList<BlockTextRange>(blocks.size)
        var cursor = 0
        blocks.forEach { block ->
            val length = itemTextLength(block)
            ranges.add(BlockTextRange(block, cursor, cursor + length))
            cursor += length + SEPARATOR.length
        }
        return ranges
    }

    private fun itemTextLength(block: MdBlock): Int = when (block) {
        is ParagraphBlock -> InlineNodes.plainText(block.inline).length
        is HeadingBlock -> InlineNodes.plainText(block.inline).length
        is CodeBlock -> block.code.length
        is TableBlock -> block.rawText.length
        is ImageBlock -> block.rawText.length
        is HorizontalRuleBlock -> 0
        else -> block.rawText.length
    }

    companion object {
        const val SEPARATOR = "\n\n"
    }
}

data class BlockTextRange(
    val block: MdBlock,
    val start: Int,
    val end: Int,
)

/**
 * 渲染管线（SPEC §1.3）。
 * AST → 扁平 RenderItem 列表，建立 blockIndex → item 映射，供大纲跳转与进度恢复。
 */
class RenderPipeline {

    fun build(document: DocumentBlock): RenderModel {
        val rawItems = ArrayList<RenderItem>()
        document.blocks.forEach { flatten(it, indent = 0, quoteDepth = 0, marker = null, rawItems) }
        val merged = mergeShortParagraphs(rawItems)
        val map = HashMap<Int, Int>()
        merged.forEachIndexed { index, item ->
            item.blocks.forEach { block ->
                if (block.blockIndex >= 0) map[block.blockIndex] = index
            }
        }
        return RenderModel(merged, map)
    }

    /** 供流式解析器对单个顶层块构建首屏 item（T16 边解析边渲染）。 */
    fun buildBlocks(blocks: List<MdBlock>): List<RenderItem> {
        val raw = ArrayList<RenderItem>()
        blocks.forEach { flatten(it, 0, 0, null, raw) }
        return mergeShortParagraphs(raw)
    }

    private fun flatten(
        block: MdBlock,
        indent: Int,
        quoteDepth: Int,
        marker: String?,
        out: MutableList<RenderItem>,
    ) {
        when (block) {
            is ParagraphBlock -> {
                val image = block.soleImage
                if (image != null) {
                    out.add(RenderItem(ItemKind.IMAGE, listOf(block), indent, marker, quoteDepth))
                } else {
                    out.add(RenderItem(ItemKind.PARAGRAPH, listOf(block), indent, marker, quoteDepth))
                }
            }
            is HeadingBlock -> out.add(RenderItem(ItemKind.HEADING, listOf(block), indent, marker, quoteDepth))
            is CodeBlock -> out.add(RenderItem(ItemKind.CODE, listOf(block), indent, marker, quoteDepth))
            is TableBlock -> out.add(RenderItem(ItemKind.TABLE, listOf(block), indent, marker, quoteDepth))
            is ImageBlock -> out.add(RenderItem(ItemKind.IMAGE, listOf(block), indent, marker, quoteDepth))
            is HorizontalRuleBlock -> out.add(RenderItem(ItemKind.HR, listOf(block), indent, marker, quoteDepth))
            is BlockQuoteBlock -> block.blocks.forEach { flatten(it, indent, quoteDepth + 1, null, out) }
            is ListBlock -> flattenList(block, indent, quoteDepth, out)
            is ListItemBlock -> flattenListItem(block, indent, quoteDepth, marker, out)
            is DocumentBlock -> block.blocks.forEach { flatten(it, indent, quoteDepth, marker, out) }
            else -> out.add(RenderItem(ItemKind.PARAGRAPH, listOf(block), indent, marker, quoteDepth))
        }
    }

    private fun flattenList(block: ListBlock, indent: Int, quoteDepth: Int, out: MutableList<RenderItem>) {
        block.items.forEachIndexed { index, item ->
            val bullet = if (block.ordered) "${block.start + index}. " else "• "
            flattenListItem(item, indent + 1, quoteDepth, bullet, out)
        }
    }

    private fun flattenListItem(
        item: ListItemBlock,
        indent: Int,
        quoteDepth: Int,
        marker: String?,
        out: MutableList<RenderItem>,
    ) {
        if (item.children.isEmpty()) {
            val empty = ParagraphBlock(emptyList(), "")
            empty.startLine = item.startLine
            empty.endLine = item.endLine
            empty.blockIndex = item.blockIndex
            out.add(RenderItem(ItemKind.PARAGRAPH, listOf(empty), indent, marker, quoteDepth))
            return
        }
        var markerPending = marker
        item.children.forEach { child ->
            flatten(child, indent, quoteDepth, markerPending, out)
            // 列表前缀只应用于列表项的第一个直接子块。
            markerPending = null
        }
    }

    /**
     * T16：相邻短段落合并。合并条件：同缩进/引用层级、无列表前缀、无链接/图片、
     * 合并后纯文本长度 ≤ 240。可显著降低长文档 item 数量。
     */
    private fun mergeShortParagraphs(items: List<RenderItem>): List<RenderItem> {
        val merged = ArrayList<RenderItem>(items.size)
        var i = 0
        while (i < items.size) {
            val current = items[i]
            if (current.kind != ItemKind.PARAGRAPH || current.marker != null) {
                merged.add(current)
                i++
                continue
            }
            val group = ArrayList<MdBlock>()
            group.addAll(current.blocks)
            var j = i + 1
            var totalLength = current.blocks.sumOf { inlineLength(it) }
            while (j < items.size) {
                val next = items[j]
                if (next.kind != ItemKind.PARAGRAPH || next.marker != null ||
                    next.indent != current.indent || next.quoteDepth != current.quoteDepth
                ) break
                val nextLen = next.blocks.sumOf { inlineLength(it) }
                if (totalLength + nextLen + RenderItem.SEPARATOR.length > MAX_MERGED_TEXT) break
                group.addAll(next.blocks)
                totalLength += nextLen + RenderItem.SEPARATOR.length
                j++
            }
            if (group.size == 1) {
                merged.add(current)
            } else {
                merged.add(
                    RenderItem(
                        kind = ItemKind.PARAGRAPH,
                        blocks = group,
                        indent = current.indent,
                        marker = null,
                        quoteDepth = current.quoteDepth,
                    ),
                )
            }
            i = j
        }
        return merged
    }

    private fun inlineLength(block: MdBlock): Int = when (block) {
        is ParagraphBlock -> InlineNodes.plainText(block.inline).length
        is HeadingBlock -> InlineNodes.plainText(block.inline).length
        is CodeBlock -> block.code.length
        is TableBlock -> block.rawText.length
        else -> block.rawText.length
    }

    companion object {
        private const val MAX_MERGED_TEXT = 240
    }
}

class RenderModel(
    val items: List<RenderItem>,
    private val blockIndexToItem: Map<Int, Int>,
) {
    fun itemIndexForBlock(blockIndex: Int): Int = blockIndexToItem[blockIndex] ?: 0

    fun blockIndexAtItem(itemIndex: Int): Int =
        items.getOrNull(itemIndex)?.primaryBlock?.blockIndex ?: -1

    val itemCount: Int get() = items.size
}
