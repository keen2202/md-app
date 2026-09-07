package com.moread.app.reader.parser

/**
 * 行视图与解析上下文。CommonMark 参考引擎与自研后备解析器共用。
 */

data class SrcLine(
    val content: String,
    val globalLine: Int,
    val columnOffset: Int = 0,
) {
    val normalized: String by lazy { SourceLines.expandTabsForIndent(content) }
    val isBlank: Boolean get() = content.isBlank()
}

class SourceLines(val text: String) {
    val lines: List<String>
    val lineStarts: IntArray

    init {
        val list = ArrayList<String>()
        val starts = ArrayList<Int>()
        val n = text.length
        if (n == 0) {
            list.add("")
            starts.add(0)
        } else {
            var start = 0
            var index = 0
            while (index < n) {
                when {
                    text[index] == '\n' -> {
                        list.add(text.substring(start, index))
                        starts.add(start)
                        index++
                        start = index
                    }
                    index == n - 1 -> {
                        list.add(text.substring(start, index + 1))
                        starts.add(start)
                        index++
                    }
                    else -> index++
                }
            }
        }
        lines = list
        lineStarts = starts.toIntArray()
    }

    fun lineStart(line: Int): Int =
        if (line < lineStarts.size) lineStarts[line] else text.length

    fun rawBetween(startLine: Int, endLineInclusive: Int): String {
        if (startLine >= lines.size) return ""
        val from = lineStart(startLine)
        val to = if (endLineInclusive + 1 < lineStarts.size) {
            lineStarts[endLineInclusive + 1]
        } else {
            text.length
        }
        return text.substring(from.coerceAtMost(text.length), to.coerceAtMost(text.length))
    }

    companion object {
        /** 仅用于缩进判断的 tab 展开，不改动原始内容。 */
        fun expandTabsForIndent(s: String): String {
            if (!s.contains('\t')) return s
            val sb = StringBuilder(s.length + 8)
            var column = 0
            for (ch in s) {
                if (ch == '\t') {
                    val step = 4 - (column % 4)
                    repeat(step) { sb.append(' ') }
                    column += step
                } else {
                    sb.append(ch)
                    column++
                }
            }
            return sb.toString()
        }
    }
}

class ParseContext(val source: SourceLines) {
    val references: LinkedHashMap<String, ReferenceDefinition> = LinkedHashMap()

    /** 递归解析容器（引用/列表）时设置的局部行视图；为空表示正在解析原始文档行。 */
    var activeLines: List<SrcLine>? = null

    /** 自研流式解析器的中断开关（T16「解析进度可中断」）。 */
    var shouldContinue: () -> Boolean = { true }

    fun toSrcLine(globalLine: Int): SrcLine {
        activeLines?.let { lines ->
            if (globalLine >= 0 && globalLine < lines.size) return lines[globalLine]
        }
        val content = source.lines.getOrElse(globalLine) { "" }
        return SrcLine(content, globalLine, 0)
    }
}

data class ReferenceDefinition(
    val label: String,
    val url: String,
    val title: String?,
)
