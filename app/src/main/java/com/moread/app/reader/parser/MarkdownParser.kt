package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TextInline
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Markdown 解析入口（SPEC §1.2）。
 *
 * 主引擎为 CommonMark 参考解析器（commonmark-java + GFM 表格/删除线扩展），
 * 转换结果仍为墨阅自有 AST，reader/render 层零解析器耦合，保证可替换性（PRD §8）。
 * 仓库内自研 BlockParser/InlineParser 保留为无第三方依赖的容错后备与流式解析参考实现，
 * 其单元测试覆盖畸形输入零崩溃约束。
 *
 * [parseStreaming] 在全量 AST 转换完成后按顶层块逐个回调；ReaderActivity 在后台线程
 * 调用并分批提交 RecyclerView，首屏不被完整解析阻塞。分段视图由 [parseChunk] 提供。
 */
class MarkdownParser {

    private val commonmark: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                ),
            )
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build()
    }

    /**
     * 全量解析（CommonMark 参考引擎，容错零异常）。
     *
     * [onRecovered] 在触发降级解析时回调（调用方可记录日志）；普通调用无需传入。
     */
    fun parse(
        text: String,
        onRecovered: ((Throwable) -> Unit)? = null,
    ): DocumentBlock {
        return try {
            parseCommonmark(text)
        } catch (t: StackOverflowError) {
            // commonmark-java 的深度嵌套行内结构可能递归溢出；超限输入降级为纯文本，
            // 保证阅读页不因畸形 Markdown 闪退。
            onRecovered?.invoke(t)
            parseDegraded(text)
        } catch (t: Exception) {
            onRecovered?.invoke(t)
            parseDegraded(text)
        }
    }

    private fun parseCommonmark(text: String): DocumentBlock {
        val source = SourceLines(text)
        val document = commonmark.parse(text) as org.commonmark.node.Document
        val root = CommonmarkAstConverter(source).convert(document)
        root.assignBlockIndices()
        return root
    }

    /**
     * 流式入口：转换完成后逐块回调，调用方可边提交首屏边完成后续排版。
     * [shouldContinue] 返回 false 时停止回调（解析本身已由库完成，不产生死循环）。
     */
    fun parseStreaming(
        text: String,
        shouldContinue: () -> Boolean = { true },
        onBlock: (MdBlock) -> Unit,
        onRecovered: ((Throwable) -> Unit)? = null,
    ): DocumentBlock {
        val root = parse(text, onRecovered)
        for (block in root.blocks) {
            if (!shouldContinue()) break
            onBlock(block)
        }
        return root
    }

    /**
     * 自研两阶段流式解析器（无第三方依赖的容错后备，SPEC §1.2 原始路线）。
     * 供大文档边解析边渲染路径与解析器回归测试使用。
     */
    fun parseLegacy(text: String): DocumentBlock {
        return try {
            val source = SourceLines(text)
            val context = ParseContext(source)
            val root = DocumentBlock()
            BlockParser(context).parseRange(0, source.lines.size, root.blocks, null)
            root.assignBlockIndices()
            root
        } catch (t: StackOverflowError) {
            parseDegraded(text)
        } catch (t: Exception) {
            parseDegraded(text)
        }
    }

    /** 分段解析接口：按起始行区间返回块视图，供渲染层分批提交。 */
    fun parseChunk(
        text: String,
        startLine: Int,
        endLine: Int,
    ): List<MdBlock> {
        val safeStart = startLine.coerceAtLeast(0)
        val safeEnd = if (endLine < 0) Int.MAX_VALUE else endLine.coerceAtLeast(safeStart)
        val root = parse(text)
        return root.flatten().filter { block ->
            block.startLine in safeStart until safeEnd
        }
    }

    /**
     * 极端畸形输入的安全降级：按字符上限切分为纯文本段落。
     *
     * 该路径只做线性扫描、不递归、不解析 Markdown，因此即使 commonmark 引擎
     * 因深层嵌套栈溢出，也能保证 App 继续打开文档而不是闪退。
     */
    private fun parseDegraded(text: String): DocumentBlock {
        val root = DocumentBlock()
        if (text.isEmpty()) return root

        val source = SourceLines(text)
        var startOffset = 0
        var startLine = 0
        while (startOffset < text.length) {
            var endOffset = (startOffset + MAX_DEGRADED_CHUNK_CHARS).coerceAtMost(text.length)
            if (endOffset < text.length) {
                // 尽量在换行处切分，避免破坏正常阅读节奏。
                val newline = text.lastIndexOf('\n', endOffset - 1)
                if (newline > startOffset) endOffset = newline + 1
            }
            val chunk = text.substring(startOffset, endOffset)
            val block = ParagraphBlock(listOf(TextInline(chunk)), chunk)
            block.startOffset = startOffset
            block.endOffset = endOffset
            while (startLine + 1 < source.lineStarts.size && source.lineStarts[startLine + 1] <= startOffset) {
                startLine++
            }
            var endLine = startLine
            while (endLine + 1 < source.lineStarts.size && source.lineStarts[endLine + 1] < endOffset) {
                endLine++
            }
            block.startLine = startLine
            block.endLine = endLine
            root.blocks.add(block)
            startOffset = endOffset
            startLine = endLine
        }
        root.assignBlockIndices()
        return root
    }

    private companion object {
        /** 降级纯文本块的最大字符数，避免单个 TextView 过大。 */
        const val MAX_DEGRADED_CHUNK_CHARS = 16 * 1024
    }
}
