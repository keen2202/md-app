package com.moread.app.editor

/** 一次格式操作后的文本与选区；纯数据，便于 JVM 单测。 */
data class EditCommandResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Markdown 源码格式命令（方案 §4.4 / §3.5）。
 *
 * 不依赖 Android 控件；EditorActivity 只负责把结果写回 EditText。
 * 约定：
 * - 行级命令作用于选区覆盖到的所有行；
 * - 再次执行相同行级命令可取消；
 * - 无选区时插入成对标记，并把光标放到中间或选中占位文字。
 */
object MarkdownEditCommands {

    fun bold(text: String, start: Int, end: Int): EditCommandResult =
        wrap(text, start, end, prefix = "**", suffix = "**")

    fun italic(text: String, start: Int, end: Int): EditCommandResult =
        wrap(text, start, end, prefix = "*", suffix = "*")

    fun inlineCode(text: String, start: Int, end: Int): EditCommandResult =
        wrap(text, start, end, prefix = "`", suffix = "`")

    fun heading(text: String, start: Int, end: Int, level: Int = 2): EditCommandResult {
        val safeLevel = level.coerceIn(1, 6)
        val targetPrefix = "#".repeat(safeLevel) + " "
        val range = lineRange(text, start, end)
        val lines = text.substring(range.first, range.second).split('\n')
        val headingRegex = Regex("""^#{1,6}\s+""")
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        val allAtTargetLevel = nonBlank.isNotEmpty() && nonBlank.all {
            lines[it].startsWith(targetPrefix)
        }
        val newLines = if (nonBlank.isEmpty()) {
            lines.mapIndexed { index, line -> if (index == 0) targetPrefix + line else line }
        } else {
            lines.map { line ->
                when {
                    line.isBlank() -> line
                    allAtTargetLevel -> line.replaceFirst(headingRegex, "")
                    else -> targetPrefix + line.replaceFirst(headingRegex, "")
                }
            }
        }
        return rebuildLines(text, range.first, range.second, newLines)
    }

    fun quote(text: String, start: Int, end: Int): EditCommandResult =
        toggleLinePrefix(text, start, end, "> ")

    fun unorderedList(text: String, start: Int, end: Int): EditCommandResult {
        val range = lineRange(text, start, end)
        val lines = text.substring(range.first, range.second).split('\n')
        val markerRegex = Regex("""^(?:[-*+]|\d+\.)\s+""")
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        val allPrefixed = nonBlank.isNotEmpty() && nonBlank.all { lines[it].startsWith("- ") }
        val newLines = if (nonBlank.isEmpty()) {
            lines.mapIndexed { index, line -> if (index == 0) "- $line" else line }
        } else {
            lines.map { line ->
                when {
                    line.isBlank() -> line
                    allPrefixed -> line.removePrefix("- ")
                    else -> "- " + markerRegex.replaceFirst(line, "")
                }
            }
        }
        return rebuildLines(text, range.first, range.second, newLines)
    }

    fun orderedList(text: String, start: Int, end: Int): EditCommandResult {
        val range = lineRange(text, start, end)
        val lines = text.substring(range.first, range.second).split('\n')
        val orderedRegex = Regex("""^\d+\.\s+""")
        val otherMarkerRegex = Regex("""^(?:[-*+]|\d+\.)\s+""")
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        val allOrdered = nonBlank.isNotEmpty() && nonBlank.all { orderedRegex.containsMatchIn(lines[it]) }
        var number = 1
        val newLines = if (nonBlank.isEmpty()) {
            lines.mapIndexed { index, line -> if (index == 0) "1. $line" else line }
        } else {
            lines.map { line ->
                when {
                    line.isBlank() -> line
                    allOrdered -> orderedRegex.replaceFirst(line, "")
                    else -> {
                        val stripped = otherMarkerRegex.replaceFirst(line, "")
                        "${number++}. $stripped"
                    }
                }
            }
        }
        return rebuildLines(text, range.first, range.second, newLines)
    }

    fun fencedCode(text: String, start: Int, end: Int): EditCommandResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)
        val fence = "```"
        return if (selected.isEmpty()) {
            val insert = "$fence\n\n$fence"
            EditCommandResult(
                text = text.substring(0, s) + insert + text.substring(e),
                selectionStart = s + fence.length + 1,
                selectionEnd = s + fence.length + 1,
            )
        } else {
            val block = "$fence\n$selected\n$fence"
            EditCommandResult(
                text = text.substring(0, s) + block + text.substring(e),
                selectionStart = s + fence.length + 1,
                selectionEnd = s + fence.length + 1 + selected.length,
            )
        }
    }

    fun link(text: String, start: Int, end: Int): EditCommandResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)
        val before = text.substring(0, s)
        val after = text.substring(e)
        return if (selected.isEmpty()) {
            val label = "链接文字"
            val insert = "[$label](url)"
            EditCommandResult(
                text = before + insert + after,
                selectionStart = s + 1,
                selectionEnd = s + 1 + label.length,
            )
        } else {
            val insert = "[$selected](url)"
            val urlStart = s + 1 + selected.length + 2
            EditCommandResult(
                text = before + insert + after,
                selectionStart = urlStart,
                selectionEnd = urlStart + 3,
            )
        }
    }

    fun horizontalRule(text: String, start: Int, end: Int): EditCommandResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val needLeadingBlank = s > 0 && text[s - 1] != '\n'
        val insert = if (needLeadingBlank) "\n\n---\n" else "---\n"
        return EditCommandResult(
            text = text.substring(0, s) + insert + text.substring(e),
            selectionStart = s + insert.length,
            selectionEnd = s + insert.length,
        )
    }

    // ------------------------------------------------------------------ 内部工具

    private fun wrap(
        text: String,
        start: Int,
        end: Int,
        prefix: String,
        suffix: String,
    ): EditCommandResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)
        val before = text.substring(0, s)
        val after = text.substring(e)

        // 已有成对标记时支持再次点击取消。
        if (before.endsWith(prefix) && after.startsWith(suffix)) {
            val newText = before.removeSuffix(prefix) + selected + after.removePrefix(suffix)
            val newStart = s - prefix.length
            return EditCommandResult(newText, newStart, newStart + selected.length)
        }

        return if (selected.isEmpty()) {
            val insert = prefix + suffix
            EditCommandResult(
                text = before + insert + after,
                selectionStart = s + prefix.length,
                selectionEnd = s + prefix.length,
            )
        } else {
            EditCommandResult(
                text = before + prefix + selected + suffix + after,
                selectionStart = s + prefix.length,
                selectionEnd = e + prefix.length,
            )
        }
    }

    private fun toggleLinePrefix(
        text: String,
        start: Int,
        end: Int,
        prefix: String,
    ): EditCommandResult {
        val range = lineRange(text, start, end)
        val lines = text.substring(range.first, range.second).split('\n')
        val nonBlank = lines.indices.filter { lines[it].isNotBlank() }
        val allPrefixed = nonBlank.isNotEmpty() && nonBlank.all { lines[it].startsWith(prefix) }
        val newLines = if (nonBlank.isEmpty()) {
            lines.mapIndexed { index, line -> if (index == 0) prefix + line else line }
        } else {
            lines.map { line ->
                when {
                    line.isBlank() -> line
                    allPrefixed -> if (line.startsWith(prefix)) line.removePrefix(prefix) else line
                    else -> prefix + line
                }
            }
        }
        return rebuildLines(text, range.first, range.second, newLines)
    }

    private fun rebuildLines(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        newLines: List<String>,
    ): EditCommandResult {
        val newSegment = newLines.joinToString("\n")
        val newText = text.substring(0, lineStart) + newSegment + text.substring(lineEnd)
        return EditCommandResult(
            text = newText,
            selectionStart = lineStart,
            selectionEnd = lineStart + newSegment.length,
        )
    }

    /** 将选区扩展到完整行边界；返回 [start, end) 半开区间。 */
    private fun lineRange(text: String, start: Int, end: Int): Pair<Int, Int> {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        var lineStart = s
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = e
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        return lineStart to lineEnd
    }
}
