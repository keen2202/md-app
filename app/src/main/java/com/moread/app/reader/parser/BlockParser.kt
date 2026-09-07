package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.BlockQuoteBlock
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.ColumnAlign
import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.HorizontalRuleBlock
import com.moread.app.reader.parser.ast.ListBlock
import com.moread.app.reader.parser.ast.ListItemBlock
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TableBlock
import com.moread.app.reader.parser.ast.TableCell

/**
 * 块级解析器（SPEC §1.2）。
 *
 * 说明：HTML 块按 SPEC 既定取舍不单独识别，行首 `<tag>` 会降级为段落，
 * 行内 HTML 由 InlineParser 转义为纯文本显示。
 */
class BlockParser(private val context: ParseContext) {

    private val source: SourceLines = context.source

    // ------------------------------------------------------------------ 主循环

    /**
     * 解析 [from, to) 行区间，结果追加到 [out]。
     * [onBlock] 非空时每完成一个顶层块立即回调（流式渲染入口）。
     * 所有分支均保证 index 前进，异常统一降级为段落。
     */
    fun parseRange(
        from: Int,
        to: Int,
        out: MutableList<MdBlock>,
        onBlock: ((MdBlock) -> Unit)?,
    ) {
        // 先收集区间内全部链接引用定义，保证段落行内解析时可解析引用式链接。
        for (lineNo in from until to) {
            val line = context.toSrcLine(lineNo)
            if (!line.isBlank) {
                parseReferenceDefinition(line.content)?.let { definition ->
                    context.references.putIfAbsent(definition.label, definition)
                }
            }
        }

        var i = from
        var guard = 0
        while (i < to && context.shouldContinue()) {
            if (guard++ > to - from + 8) {
                // 防御性断点：理论上每轮都会前进，此分支仅用于死循环兜底。
                break
            }
            val line = context.toSrcLine(i)
            if (line.isBlank) {
                i++
                continue
            }
            val next = parseOneBlock(i, to, out)
            val block = out.lastOrNull()
            if (next == i) {
                // 未识别时降级为段落，parseParagraph 内部保证至少消费 1 行。
                val para = parseParagraph(i, to)
                out.add(para)
                onBlock?.invoke(para)
                i = para.endLine + 1
            } else {
                if (block != null && block.endLine >= i) {
                    onBlock?.invoke(block)
                }
                i = next
            }
        }
    }

    private fun parseOneBlock(start: Int, end: Int, out: MutableList<MdBlock>): Int {
        val line = context.toSrcLine(start)
        val raw = line.content
        val norm = line.normalized

        // 1. 缩进代码块：4 列及以上缩进优先于所有叶子块标记。
        if (indentColumns(norm) >= 4 && raw.isNotBlank()) {
            return parseIndentedCode(start, end, out)
        }
        // 2. 围栏代码块
        fenceOpen(raw, norm)?.let { return parseFencedCode(start, end, it, out) }
        // 3. ATX 标题
        atxHeading(raw, norm)?.let { return parseAtx(start, it, out) }
        // 4. 分割线
        if (isThematicBreak(norm)) {
            out.add(makeBlock(HorizontalRuleBlock(raw), start, start))
            return start + 1
        }
        // 5. 引用
        if (quoteMarker(raw) != null) return parseBlockQuote(start, end, out)
        // 6. 列表
        listMarker(raw, norm)?.let { return parseList(start, end, it, out) }
        // 7. GFM 表格（块起始位置才识别）
        if (isTableStart(start, end)) return parseTable(start, end, out)
        // 8. 链接引用定义（不产生可见块）
        parseReferenceDefinition(raw)?.let {
            context.references[it.label] = it
            return start + 1
        }
        // 其余交给段落
        return start
    }

    private fun makeBlock(block: MdBlock, startLine: Int, endLine: Int): MdBlock {
        block.startLine = startLine
        block.endLine = endLine
        block.startOffset = source.lineStart(startLine)
        block.endOffset = source.lineStart(endLine + 1).coerceAtMost(source.text.length)
        return block
    }

    // ------------------------------------------------------------------ 标题/分割线

    private data class AtxInfo(val level: Int, val content: String)

    private fun atxHeading(raw: String, norm: String): AtxInfo? {
        var k = 0
        while (k < raw.length && k <= 3 && raw[k] == ' ') k++
        if (k > 3 || k >= raw.length || raw[k] != '#') return null
        val levelStart = k
        while (k < raw.length && raw[k] == '#') k++
        val level = k - levelStart
        if (level !in 1..6) return null
        if (k < raw.length && raw[k] != ' ' && raw[k] != '\t') return null
        while (k < raw.length && (raw[k] == ' ' || raw[k] == '\t')) k++
        var content = raw.substring(k)
        content = content.replace(Regex("""[ \t]+#+[ \t]*$"""), "")
        return AtxInfo(level, content)
    }

    private fun parseAtx(start: Int, info: AtxInfo, out: MutableList<MdBlock>): Int {
        val base = context.toSrcLine(start)
        val inline = InlineParser(context).parseInline(info.content, base.contentStartOffset() + (base.content.length - info.content.length))
        out.add(
            makeBlock(
                HeadingBlock(info.level, inline, info.content),
                start,
                start,
            ),
        )
        return start + 1
    }

    private fun isThematicBreak(norm: String): Boolean {
        val s = norm.trim()
        if (s.length < 3) return false
        val ch = s[0]
        if (ch != '*' && ch != '-' && ch != '_') return false
        var count = 0
        for (c in s) {
            when {
                c == ch -> count++
                c == ' ' || c == '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    // ------------------------------------------------------------------ 围栏/缩进代码

    private data class FenceInfo(val char: Char, val length: Int, val info: String)

    private fun fenceOpen(raw: String, norm: String): FenceInfo? {
        var k = 0
        while (k < norm.length && k <= 3 && norm[k] == ' ') k++
        if (k > 3 || k >= norm.length) return null
        val ch = norm[k]
        if (ch != '`' && ch != '~') return null
        val start = k
        while (k < norm.length && norm[k] == ch) k++
        val len = k - start
        if (len < 3) return null
        val info = norm.substring(k).trim()
        if (ch == '`' && info.contains('`')) return null
        return FenceInfo(ch, len, info)
    }

    private fun parseFencedCode(start: Int, end: Int, fence: FenceInfo, out: MutableList<MdBlock>): Int {
        val codeLines = ArrayList<String>()
        var j = start + 1
        while (j < end) {
            val norm = context.toSrcLine(j).normalized
            if (isClosingFence(norm, fence.char, fence.length)) {
                j++
                break
            }
            codeLines.add(context.toSrcLine(j).content)
            j++
        }
        val code = codeLines.joinToString("\n")
        val language = fenceLanguage(InlineParser.decodeEscapesAndEntities(fence.info))
        val block = makeBlock(
            CodeBlock(code = code, language = language, fenced = true, fenceInfo = fence.info, rawText = source.rawBetween(start, j - 1)),
            start,
            j - 1,
        )
        out.add(block)
        return j
    }

    private fun isClosingFence(norm: String, ch: Char, openLen: Int): Boolean {
        val s = norm.trimStart(' ')
        if (s.length < openLen) return false
        var k = 0
        while (k < s.length && s[k] == ch) k++
        if (k < openLen) return false
        return s.substring(k).isBlank()
    }

    private fun fenceLanguage(info: String): String {
        val s = info.trim()
        if (s.isEmpty()) return ""
        var end = 0
        while (end < s.length && !s[end].isWhitespace() && s[end] != '{') end++
        return s.substring(0, end).lowercase()
    }

    private fun parseIndentedCode(start: Int, end: Int, out: MutableList<MdBlock>): Int {
        val codeLines = ArrayList<String>()
        var j = start
        while (j < end) {
            val line = context.toSrcLine(j)
            if (line.isBlank) {
                codeLines.add("")
                j++
                continue
            }
            if (indentColumns(line.normalized) < 4) break
            codeLines.add(stripColumns(line.content, 4))
            j++
        }
        while (codeLines.isNotEmpty() && codeLines.last().isBlank()) codeLines.removeAt(codeLines.size - 1)
        val code = codeLines.joinToString("\n")
        val block = makeBlock(CodeBlock(code = code, language = "", fenced = false, rawText = code), start, j - 1)
        out.add(block)
        return j
    }

    // ------------------------------------------------------------------ 引用

    private fun quoteMarker(raw: String): Int? {
        var i = 0
        var cols = 0
        while (i < raw.length && cols <= 3 && (raw[i] == ' ' || raw[i] == '\t')) {
            cols += if (raw[i] == '\t') 4 - (cols % 4) else 1
            i++
        }
        if (cols > 3 || i >= raw.length || raw[i] != '>') return null
        var contentStart = i + 1
        if (contentStart < raw.length && (raw[contentStart] == ' ' || raw[contentStart] == '\t')) contentStart++
        return contentStart
    }

    private fun parseBlockQuote(start: Int, end: Int, out: MutableList<MdBlock>): Int {
        val inner = ArrayList<SrcLine>()
        var j = start
        while (j < end) {
            val line = context.toSrcLine(j)
            val marker = quoteMarker(line.content)
            when {
                marker != null -> {
                    inner.add(SrcLine(line.content.substring(marker), line.globalLine, marker))
                    j++
                }
                line.isBlank -> {
                    // 空白行仅在其后仍存在引用标记时属于引用，否则结束引用。
                    var k = j
                    while (k < end && context.toSrcLine(k).isBlank) k++
                    if (k < end && quoteMarker(context.toSrcLine(k).content) != null) {
                        while (j <= k) {
                            inner.add(SrcLine("", context.toSrcLine(j).globalLine, 0))
                            j++
                        }
                    } else {
                        break
                    }
                }
                j == start -> break
                else -> {
                    // 惰性续行：段落延续允许不带 > 标记。
                    inner.add(SrcLine(line.content, line.globalLine, 0))
                    j++
                }
            }
        }
        val children = ArrayList<MdBlock>()
        parseRangeOnLines(inner, children, null)
        val block = makeBlock(BlockQuoteBlock(children), start, j - 1)
        out.add(block)
        return j
    }

    private fun parseRangeOnLines(
        lines: List<SrcLine>,
        out: MutableList<MdBlock>,
        onBlock: ((MdBlock) -> Unit)?,
    ) {
        if (lines.isEmpty()) return
        val savedLines = context.activeLines
        context.activeLines = lines
        try {
            parseRange(0, lines.size, out, onBlock)
        } finally {
            context.activeLines = savedLines
        }
    }

    // ------------------------------------------------------------------ 列表

    private data class ListMarkerInfo(
        val ordered: Boolean,
        val startNumber: Int,
        val indentColumns: Int,
        val markerEnd: Int,
        val contentStart: Int,
        val contentIndentColumns: Int,
        val raw: String,
    )

    private fun listMarker(raw: String, norm: String): ListMarkerInfo? {
        var i = 0
        var cols = 0
        while (i < raw.length && cols <= 3 && (raw[i] == ' ' || raw[i] == '\t')) {
            cols += if (raw[i] == '\t') 4 - (cols % 4) else 1
            i++
        }
        if (cols > 3 || i >= raw.length) return null
        val markerStart = i
        val ordered: Boolean
        var startNumber = 1
        when {
            raw[i] == '*' || raw[i] == '+' || raw[i] == '-' -> {
                ordered = false
                i++
            }
            raw[i].isDigit() -> {
                ordered = true
                var num = 0
                var digits = 0
                while (i < raw.length && raw[i].isDigit() && digits < 9) {
                    num = num * 10 + (raw[i] - '0')
                    i++
                    digits++
                }
                if (i >= raw.length || (raw[i] != '.' && raw[i] != ')')) return null
                startNumber = if (num <= 0) 1 else num
                i++
            }
            else -> return null
        }
        val markerEnd = i
        // CommonMark：列表标记后必须跟随空白或行尾，否则不是列表。
        if (markerEnd < raw.length && raw[markerEnd] != ' ' && raw[markerEnd] != '\t') return null
        var contentStart = markerEnd
        var ws = 0
        while (contentStart < raw.length && (raw[contentStart] == ' ' || raw[contentStart] == '\t') && ws < 5) {
            ws++
            contentStart++
        }
        if (ws == 0) contentStart = markerEnd
        if (ws > 4) {
            contentStart = markerEnd + 1
            ws = 1
        }
        val markerWidth = markerEnd - markerStart
        val contentIndent = cols + markerWidth + if (ws == 0) 1 else ws
        return ListMarkerInfo(
            ordered = ordered,
            startNumber = startNumber,
            indentColumns = cols,
            markerEnd = markerEnd,
            contentStart = contentStart,
            contentIndentColumns = contentIndent,
            raw = raw,
        )
    }

    private fun parseList(start: Int, end: Int, first: ListMarkerInfo, out: MutableList<MdBlock>): Int {
        val ordered = first.ordered
        val startNumber = first.startNumber
        val items = ArrayList<ListItemBlock>()
        var i = start

        while (i < end) {
            val line = context.toSrcLine(i)
            val marker = listMarker(line.content, line.normalized) ?: break
            if (marker.ordered != ordered || marker.indentColumns != first.indentColumns) break

            val itemLines = ArrayList<SrcLine>()
            val firstContent = if (marker.contentStart <= line.content.length) line.content.substring(marker.contentStart) else ""
            itemLines.add(SrcLine(firstContent, line.globalLine, marker.contentStart))
            var j = i + 1
            var loose = false

            while (j < end) {
                val next = context.toSrcLine(j)
                val nextNorm = next.normalized
                if (next.isBlank) {
                    // 判断空行后是否仍属于当前列表项。
                    var k = j
                    while (k < end && context.toSrcLine(k).isBlank) k++
                    if (k >= end) {
                        j = k
                        break
                    }
                    val nextMarker = listMarker(context.toSrcLine(k).content, context.toSrcLine(k).normalized)
                    val sameLevelMarker = nextMarker != null && nextMarker.ordered == ordered && nextMarker.indentColumns == first.indentColumns
                    if (sameLevelMarker) {
                        j = k
                        break
                    }
                    val continuation = indentColumns(context.toSrcLine(k).normalized) >= marker.contentIndentColumns ||
                        !startsNewBlock(context.toSrcLine(k).content, context.toSrcLine(k).normalized)
                    if (!continuation) {
                        j = k
                        break
                    }
                    // 宽松列表：空行纳入当前 item（对渲染无阻断）。
                    loose = true
                    while (j <= k) {
                        itemLines.add(SrcLine("", next.globalLine, 0))
                        j++
                    }
                    continue
                }

                val nextMarker = listMarker(next.content, nextNorm)
                if (nextMarker != null && nextMarker.ordered == ordered && nextMarker.indentColumns == first.indentColumns) break

                val indent = indentColumns(nextNorm)
                if (indent >= marker.contentIndentColumns) {
                    val stripped = stripColumnsWithOffset(next.content, marker.contentIndentColumns)
                    itemLines.add(SrcLine(stripped.first, next.globalLine, stripped.second))
                    j++
                } else if (startsNewBlock(next.content, nextNorm)) {
                    break
                } else {
                    // 惰性续行
                    itemLines.add(SrcLine(next.content, next.globalLine, 0))
                    j++
                }
            }

            val children = ArrayList<MdBlock>()
            parseRangeOnLines(itemLines, children, null)
            if (children.isEmpty()) {
                children.add(ParagraphBlock(emptyList(), "").also { p ->
                    p.startOffset = source.lineStart(line.globalLine) + marker.contentStart
                    p.endOffset = p.startOffset
                    p.startLine = line.globalLine
                    p.endLine = line.globalLine
                })
            }
            val item = ListItemBlock(children)
            item.startLine = line.globalLine
            item.endLine = (j - 1).coerceAtLeast(line.globalLine)
            item.startOffset = source.lineStart(line.globalLine)
            item.endOffset = source.lineStart(item.endLine + 1).coerceAtMost(source.text.length)
            items.add(item)
            i = j
            if (!loose) Unit
        }

        if (items.isEmpty()) return start + 1
        val block = ListBlock(ordered, startNumber, items)
        block.startLine = start
        block.endLine = items.last().endLine
        block.startOffset = source.lineStart(start)
        block.endOffset = source.lineStart(block.endLine + 1).coerceAtMost(source.text.length)
        out.add(block)
        return items.last().endLine + 1
    }

    // ------------------------------------------------------------------ 表格

    private fun isTableStart(line: Int, end: Int): Boolean {
        if (line + 1 >= end) return false
        val header = context.toSrcLine(line).content
        val delim = context.toSrcLine(line + 1).normalized
        if (!header.contains('|')) return false
        if (!delim.contains('-')) return false
        val cells = splitTableRow(header)
        val delimCells = splitTableRow(context.toSrcLine(line + 1).content)
        if (cells.isEmpty() || delimCells.isEmpty()) return false
        if (delimCells.size !in 1..cells.size) return false
        return delimCells.all { isDelimiterCell(it) } && delimCells.any { it.contains('-') }
    }

    private fun isDelimiterCell(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        val noColon = t.trim(':')
        if (noColon.length < 1) return false
        return noColon.all { it == '-' }
    }

    private fun parseTable(start: Int, end: Int, out: MutableList<MdBlock>): Int {
        val headerLine = context.toSrcLine(start)
        val delimiterLine = context.toSrcLine(start + 1)
        val headerRaw = splitTableRow(headerLine.content)
        val delimRaw = splitTableRow(delimiterLine.content)
        val aligns = delimRaw.map { raw ->
            val t = raw.trim()
            val left = t.startsWith(':')
            val right = t.endsWith(':')
            when {
                left && right -> ColumnAlign.CENTER
                right -> ColumnAlign.RIGHT
                else -> ColumnAlign.LEFT
            }
        }
        fun cell(text: String, align: ColumnAlign): TableCell {
            val inline = InlineParser(context).parseInline(text.trim(), offsetOf(headerLine, text.trimStart().let { headerLine.content.length - text.trim().length }))
            return TableCell(inline, text.trim(), align)
        }
        val headers = headerRaw.mapIndexed { idx, s ->
            cell(s, aligns.getOrElse(idx) { ColumnAlign.LEFT })
        }
        val rows = ArrayList<List<TableCell>>()
        var j = start + 2
        while (j < end) {
            val line = context.toSrcLine(j)
            if (line.isBlank) break
            val cells = splitTableRow(line.content)
            if (cells.isEmpty()) break
            if (!line.content.contains('|')) break
            rows.add(cells.mapIndexed { idx, s -> cell(s, aligns.getOrElse(idx) { ColumnAlign.LEFT }) })
            j++
        }
        val block = makeBlock(
            TableBlock(headers, rows, source.rawBetween(start, j - 1)),
            start,
            j - 1,
        )
        out.add(block)
        return j
    }

    private fun splitTableRow(raw: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var backtickRun = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length && raw[i + 1] == '|' -> {
                    sb.append('|')
                    i += 2
                }
                c == '`' -> {
                    var run = 0
                    while (i < raw.length && raw[i] == '`') { run++; i++ }
                    if (backtickRun == 0) backtickRun = run
                    else if (backtickRun == run) backtickRun = 0
                    repeat(run) { sb.append('`') }
                }
                c == '|' && backtickRun == 0 -> {
                    result.add(sb.toString())
                    sb.clear()
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        result.add(sb.toString())
        val trimmed = result.map { it.trim() }
        // 去掉首尾由外层管道产生的空单元。
        val startIdx = if (trimmed.firstOrNull().isNullOrEmpty()) 1 else 0
        val endIdx = if (trimmed.size > 1 && trimmed.lastOrNull().isNullOrEmpty()) trimmed.size - 1 else trimmed.size
        if (startIdx >= endIdx) return emptyList()
        return trimmed.subList(startIdx, endIdx)
    }

    // ------------------------------------------------------------------ 引用定义

    private fun parseReferenceDefinition(raw: String): ReferenceDefinition? {
        val m = REFERENCE_REGEX.matchEntire(raw) ?: return null
        val label = m.groupValues[1].trim().lowercase()
        if (label.length > 999) return null
        var url = m.groupValues[2].trim()
        if (url.startsWith('<') && url.endsWith('>')) url = url.substring(1, url.length - 1)
        url = InlineParser.decodeEscapesAndEntities(url)
        val titlePart = m.groupValues[3].trim()
        val title = when {
            titlePart.isEmpty() -> null
            titlePart.length >= 2 && titlePart.first() == '"' && titlePart.last() == '"' -> titlePart.substring(1, titlePart.length - 1)
            titlePart.length >= 2 && titlePart.first() == '\'' && titlePart.last() == '\'' -> titlePart.substring(1, titlePart.length - 1)
            titlePart.length >= 2 && titlePart.first() == '(' && titlePart.last() == ')' -> titlePart.substring(1, titlePart.length - 1)
            else -> titlePart
        }
        return ReferenceDefinition(label, url, title?.let { InlineParser.decodeEscapesAndEntities(it) })
    }

    // ------------------------------------------------------------------ 段落

    private fun parseParagraph(start: Int, end: Int): MdBlock {
        val pLines = ArrayList<SrcLine>()
        var j = start
        var consumed = false
        while (j < end) {
            val line = context.toSrcLine(j)
            if (j > start && line.isBlank) break
            if (j > start && isSetextUnderline(line.normalized)) {
                return buildSetextHeading(pLines, if (line.normalized.trimStart(' ').firstOrNull() == '=') 1 else 2)
            }
            if (j > start && startsNewBlock(line.content, line.normalized)) break
            pLines.add(line)
            consumed = true
            j++
        }
        if (!consumed && j < end) {
            pLines.add(context.toSrcLine(j))
            j++
        }
        val text = pLines.joinToString("\n") { it.content }
        val baseLine = pLines.firstOrNull() ?: context.toSrcLine(start)
        val baseOffset = offsetOf(baseLine, 0)
        val inline = try {
            InlineParser(context).parseInline(text, baseOffset)
        } catch (_: Exception) {
            emptyList()
        }
        val block = ParagraphBlock(inline, text)
        val last = pLines.lastOrNull() ?: context.toSrcLine(start)
        block.startLine = baseLine.globalLine
        block.endLine = last.globalLine
        block.startOffset = baseOffset
        block.endOffset = endOffsetOf(last)
        return block
    }

    private fun buildSetextHeading(pLines: List<SrcLine>, level: Int): HeadingBlock {
        val text = pLines.joinToString("\n") { it.content }
        val base = pLines.first()
        val inline = try {
            InlineParser(context).parseInline(text, offsetOf(base, 0))
        } catch (_: Exception) {
            emptyList()
        }
        val last = pLines.last()
        val block = HeadingBlock(level, inline, text)
        block.startLine = base.globalLine
        block.endLine = last.globalLine + 1 // 包含下划线行
        block.startOffset = offsetOf(base, 0)
        block.endOffset = endOffsetOf(last)
        return block
    }

    private fun isSetextUnderline(norm: String): Boolean {
        val s = norm.trimStart(' ')
        if (s.isEmpty()) return false
        val ch = s[0]
        if (ch != '=' && ch != '-') return false
        return s.all { it == ch || it == ' ' || it == '\t' }
    }

    private fun startsNewBlock(raw: String, norm: String): Boolean =
        fenceOpen(raw, norm) != null ||
            atxHeading(raw, norm) != null ||
            isThematicBreak(norm) ||
            quoteMarker(raw) != null ||
            listMarker(raw, norm) != null ||
            parseReferenceDefinition(raw) != null

    // ------------------------------------------------------------------ 偏移工具

    private fun SrcLine.contentStartOffset(): Int = offsetOf(this, 0)

    private fun offsetOf(line: SrcLine, localCol: Int): Int =
        source.lineStart(line.globalLine) + line.columnOffset + localCol

    private fun endOffsetOf(line: SrcLine): Int =
        source.lineStart(line.globalLine) + line.columnOffset + line.content.length

    private fun indentColumns(norm: String): Int {
        var count = 0
        for (c in norm) {
            if (c == ' ') count++ else break
        }
        return count
    }

    private fun stripColumns(raw: String, columns: Int): String =
        stripColumnsWithOffset(raw, columns).first

    /** 按显示列剥离缩进，返回剩余文本与其在原始行中的字符偏移。 */
    private fun stripColumnsWithOffset(raw: String, columns: Int): Pair<String, Int> {
        var col = 0
        var i = 0
        while (i < raw.length && col < columns) {
            if (raw[i] == '\t') {
                col += 4 - (col % 4)
            } else {
                col++
            }
            if (col <= columns) i++ else {
                // tab 跨越边界时保留剩余部分难度较大，按整 tab 剥离后补偿一个空格。
                return Pair(raw.substring(i + 1), i + 1)
            }
        }
        return Pair(raw.substring(i), i)
    }

    companion object {
        private val REFERENCE_REGEX = Regex("""^ {0,3}\[([^\]\n]+)]:\s*(\S+)(?:[ \t]+(.+))?$""")
    }
}
