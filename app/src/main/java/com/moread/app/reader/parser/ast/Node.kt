package com.moread.app.reader.parser.ast

/**
 * Markdown AST 定义。
 *
 * 设计约束（SPEC §1.2）：
 * - reader 包零 Android UI 依赖，本文件仅使用 Kotlin 标准库；
 * - 每个块节点携带原文偏移（startOffset/endOffset）、起止行与 blockIndex，
 *   blockIndex 按深度优先遍历顺序编号，供大纲跳转、搜索定位与进度记忆复用。
 */
sealed class MdBlock {
    /** 原文中的起始字符偏移（含块起始空白，inclusive）。 */
    var startOffset: Int = -1

    /** 原文中的结束字符偏移（exclusive）。 */
    var endOffset: Int = -1

    /** 起始行号（0-based，含）。 */
    var startLine: Int = -1

    /** 结束行号（0-based，含）。 */
    var endLine: Int = -1

    /** 深度优先遍历序号；-1 表示尚未编号。 */
    var blockIndex: Int = -1

    /** 块内原始文本（用于搜索与导出）。 */
    abstract val rawText: String

    /** 深度优先遍历所有块节点（含自身）。 */
    fun flatten(): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        walk { out.add(it) }
        return out
    }

    open fun walk(visitor: (MdBlock) -> Unit) {
        visitor(this)
        children().forEach { it.walk(visitor) }
    }

    open fun children(): List<MdBlock> = emptyList()

    fun assignBlockIndices() {
        var next = 0
        walk { it.blockIndex = next++ }
    }
}

class DocumentBlock(val blocks: MutableList<MdBlock> = mutableListOf()) : MdBlock() {
    override val rawText: String
        get() = blocks.joinToString("") { it.rawText }

    override fun walk(visitor: (MdBlock) -> Unit) {
        visitor(this)
        blocks.forEach { it.walk(visitor) }
    }

    override fun children(): List<MdBlock> = blocks
}

/** ATX / Setext 标题。 */
class HeadingBlock(
    val level: Int,
    val inline: List<MdInline>,
    override val rawText: String,
) : MdBlock()

/** 段落（行内元素已解析）。 */
class ParagraphBlock(
    val inline: List<MdInline>,
    override val rawText: String,
) : MdBlock() {
    /** 段落仅由一张本地图片构成时，渲染层可将其提升为独立图片块。 */
    val soleImage: ImageInline?
        get() = if (inline.size == 1 && inline[0] is ImageInline) inline[0] as ImageInline else null
}

/** 围栏/缩进代码块。 */
class CodeBlock(
    val code: String,
    val language: String,
    val fenced: Boolean,
    val fenceInfo: String = "",
    override val rawText: String,
) : MdBlock()

class BlockQuoteBlock(val blocks: MutableList<MdBlock> = mutableListOf()) : MdBlock() {
    override val rawText: String
        get() = blocks.joinToString("") { it.rawText }

    override fun walk(visitor: (MdBlock) -> Unit) {
        visitor(this)
        blocks.forEach { it.walk(visitor) }
    }

    override fun children(): List<MdBlock> = blocks
}

class ListBlock(
    val ordered: Boolean,
    val start: Int,
    val items: MutableList<ListItemBlock> = mutableListOf(),
) : MdBlock() {
    override val rawText: String
        get() = items.joinToString("") { it.rawText }

    override fun walk(visitor: (MdBlock) -> Unit) {
        visitor(this)
        items.forEach { it.walk(visitor) }
    }

    override fun children(): List<MdBlock> = items
}

class ListItemBlock(val children: MutableList<MdBlock> = mutableListOf()) : MdBlock() {
    override val rawText: String
        get() = children.joinToString("") { it.rawText }

    override fun walk(visitor: (MdBlock) -> Unit) {
        visitor(this)
        children.forEach { it.walk(visitor) }
    }

    override fun children(): List<MdBlock> = children
}

class TableCell(val inline: List<MdInline>, val rawText: String, val align: ColumnAlign)

class TableBlock(
    val headers: List<TableCell>,
    val rows: List<List<TableCell>>,
    override val rawText: String,
) : MdBlock()

class HorizontalRuleBlock(override val rawText: String = "---") : MdBlock()

/** 块级图片（由仅含单张图片的段落提升而来）。 */
class ImageBlock(val image: ImageInline, override val rawText: String) : MdBlock()

enum class ColumnAlign { LEFT, CENTER, RIGHT }

/** 行内节点，offset 为相对原文的全局偏移。 */
sealed class MdInline {
    var startOffset: Int = -1
    var endOffset: Int = -1
}

class TextInline(val text: String) : MdInline()
class StrongInline(val children: List<MdInline>) : MdInline()
class EmphasisInline(val children: List<MdInline>) : MdInline()
class StrikeInline(val children: List<MdInline>) : MdInline()
class CodeInline(val code: String) : MdInline()
class LinkInline(val url: String, val title: String?, val children: List<MdInline>) : MdInline()
class ImageInline(val url: String, val alt: String) : MdInline()
class SoftBreakInline : MdInline()
class HardBreakInline : MdInline()

/** HTML 内联块：按纯文本转义显示，不渲染为富文本（SPEC §1.2）。 */
class HtmlInline(val html: String) : MdInline()

/** 解析异常兜底类型：任何无法识别的输入最终降级为段落或文本，不向外抛出。 */
object InlineNodes {
    /**
     * 迭代式提取纯文本，显式栈避免畸形 Markdown 生成超深行内树时递归栈溢出。
     * 输出顺序与递归前序遍历一致。
     */
    fun plainText(list: List<MdInline>): String = buildString {
        val stack = ArrayDeque<MdInline>()
        for (i in list.indices.reversed()) stack.addLast(list[i])
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is TextInline -> append(node.text)
                is StrongInline -> pushChildren(stack, node.children)
                is EmphasisInline -> pushChildren(stack, node.children)
                is StrikeInline -> pushChildren(stack, node.children)
                is CodeInline -> append(node.code)
                is LinkInline -> pushChildren(stack, node.children)
                is ImageInline -> append(node.alt)
                is SoftBreakInline -> append('\n')
                is HardBreakInline -> append('\n')
                is HtmlInline -> append(node.html)
            }
        }
    }

    private fun pushChildren(stack: ArrayDeque<MdInline>, children: List<MdInline>) {
        for (i in children.indices.reversed()) stack.addLast(children[i])
    }

    fun MdInline.plainText(): String = InlineNodes.plainText(listOf(this))
}
