package com.moread.app.reader.outline

import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.InlineNodes
import com.moread.app.reader.parser.ast.MdBlock

/** 大纲条目：H1–H6 标题，携带 AST blockIndex 与显示标题。 */
data class OutlineItem(
    val level: Int,
    val title: String,
    val blockIndex: Int,
)

/**
 * 大纲抽取器（SPEC §1.5）。
 * 在 AST 上收集全部 H1–H6 节点，标题文本为行内纯文本（重复标题按各自 blockIndex 区分）。
 */
object OutlineExtractor {

    fun extract(document: DocumentBlock): List<OutlineItem> {
        val result = ArrayList<OutlineItem>()
        document.flatten().forEach { block ->
            if (block is HeadingBlock) {
                val title = InlineNodes.plainText(block.inline).trim()
                result.add(OutlineItem(block.level.coerceIn(1, 6), title.ifEmpty { "(无标题)" }, block.blockIndex))
            }
        }
        return result
    }

    /** 与 [OutlineExtractor.extract] 等价，便于渲染层直接使用任意块列表。 */
    fun extractFromBlocks(blocks: List<MdBlock>): List<OutlineItem> {
        val result = ArrayList<OutlineItem>()
        blocks.forEach { block ->
            if (block is HeadingBlock) {
                val title = InlineNodes.plainText(block.inline).trim()
                result.add(OutlineItem(block.level.coerceIn(1, 6), title.ifEmpty { "(无标题)" }, block.blockIndex))
            }
        }
        return result
    }
}
