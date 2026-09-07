package com.moread.app.reader.parser

import com.moread.app.reader.parser.ast.CodeInline
import com.moread.app.reader.parser.ast.EmphasisInline
import com.moread.app.reader.parser.ast.HardBreakInline
import com.moread.app.reader.parser.ast.HtmlInline
import com.moread.app.reader.parser.ast.ImageInline
import com.moread.app.reader.parser.ast.LinkInline
import com.moread.app.reader.parser.ast.MdInline
import com.moread.app.reader.parser.ast.SoftBreakInline
import com.moread.app.reader.parser.ast.StrikeInline
import com.moread.app.reader.parser.ast.StrongInline
import com.moread.app.reader.parser.ast.TextInline

/**
 * 行内解析器（SPEC §1.2）。
 *
 * 支持：转义、行内代码、链接/图片（含引用式）、自动链接、HTML 行内块转义为纯文本、
 * 实体、粗体/斜体/删除线、硬/软换行。
 *
 * 容错约定：任何无法识别或无法闭合的结构均按普通文本返回，绝不抛异常。
 */
class InlineParser(private val context: ParseContext) {

    private val references: Map<String, ReferenceDefinition> = context.references

    fun parseInline(text: String, baseOffset: Int = 0): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val nodes = scan(text, baseOffset)
        assignOffsets(nodes)
        return nodes
    }

    // ------------------------------------------------------------------ 扫描

    private fun scan(text: String, base: Int): MutableList<MdInline> {
        val out = ArrayList<MdInline>()
        val n = text.length
        var i = 0
        var textStart = 0

        fun flushText(end: Int) {
            if (end <= textStart) return
            val run = text.substring(textStart, end)
            out.addAll(processEmphasisRun(run, base + textStart))
        }

        while (i < n) {
            val c = text[i]
            when {
                c == '\\' -> {
                    flushText(i)
                    when {
                        i + 1 < n && text[i + 1] == '\n' -> {
                            out.add(HardBreakInline().withOffset(base + i, base + i + 2))
                            i += 2
                        }
                        i + 1 < n && isEscapable(text[i + 1]) -> {
                            out.add(TextInline(text[i + 1].toString()).withOffset(base + i, base + i + 2))
                            i += 2
                        }
                        else -> {
                            out.add(TextInline("\\").withOffset(base + i, base + i + 1))
                            i++
                        }
                    }
                    textStart = i
                }
                c == '`' -> {
                    flushText(i)
                    val parsed = parseCodeSpan(text, i, base)
                    if (parsed == null) {
                        out.add(TextInline("`").withOffset(base + i, base + i + 1))
                        i++
                    } else {
                        out.add(parsed)
                        i = parsed.endOffset - base
                    }
                    textStart = i
                }
                c == '!' && i + 1 < n && text[i + 1] == '[' -> {
                    val parsed = parseLinkOrImage(text, i, image = true, base)
                    if (parsed == null) {
                        i++
                    } else {
                        flushText(i)
                        out.add(parsed)
                        i = parsed.endOffset - base
                        textStart = i
                    }
                }
                c == '[' -> {
                    val parsed = parseLinkOrImage(text, i, image = false, base)
                    if (parsed == null) {
                        i++
                    } else {
                        flushText(i)
                        out.add(parsed)
                        i = parsed.endOffset - base
                        textStart = i
                    }
                }
                c == '<' -> {
                    flushText(i)
                    val parsed = parseAngle(text, i, base)
                    if (parsed != null) {
                        out.add(parsed)
                        i = parsed.endOffset - base
                    } else {
                        out.add(TextInline("<").withOffset(base + i, base + i + 1))
                        i++
                    }
                    textStart = i
                }
                c == '&' -> {
                    flushText(i)
                    val entity = parseEntity(text, i, base)
                    if (entity == null) {
                        out.add(TextInline("&").withOffset(base + i, base + i + 1))
                        i++
                    } else {
                        out.add(entity)
                        i = entity.endOffset - base
                    }
                    textStart = i
                }
                c == '\n' -> {
                    var end = i
                    val hard = end - 1 >= textStart && text[end - 1] == ' ' && end - 2 >= textStart && text[end - 2] == ' '
                    if (hard) end -= 2
                    flushText(end)
                    if (hard) out.add(HardBreakInline().withOffset(base + end, base + i + 1)) else out.add(SoftBreakInline().withOffset(base + i, base + i + 1))
                    i++
                    textStart = i
                }
                else -> i++
            }
        }
        flushText(n)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : MdInline> T.withOffset(start: Int, end: Int): T {
        startOffset = start
        endOffset = end
        return this
    }

    private fun isEscapable(c: Char): Boolean =
        c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    // ------------------------------------------------------------------ 行内代码

    private fun parseCodeSpan(text: String, start: Int, base: Int): CodeInline? {
        var i = start
        var run = 0
        while (i < text.length && text[i] == '`') {
            run++
            i++
        }
        val close = text.indexOf("`".repeat(run), i)
        if (close < 0) return null
        var code = text.substring(i, close).replace('\n', ' ')
        // 首尾各一个空格且两侧不全是空格时剥离（CommonMark 规则）。
        if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.any { it != ' ' }) {
            code = code.substring(1, code.length - 1)
        }
        return CodeInline(code).withOffset(base + start, base + close + run)
    }

    // ------------------------------------------------------------------ 链接/图片

    private fun parseLinkOrImage(text: String, start: Int, image: Boolean, base: Int): MdInline? {
        val open = start + if (image) 1 else 0
        val close = findClosingBracket(text, open)
        if (close < 0) return null
        val label = text.substring(open + 1, close)

        // 行内式 [label](url "title")
        var after = close + 1
        if (after < text.length && text[after] == '(') {
            val parsed = parseInlineDestination(text, after, base)
            if (parsed != null) {
                val (url, title, end) = parsed
                return if (image) {
                    ImageInline(url, stripMarkup(label)).withOffset(base + start, base + end)
                } else {
                    LinkInline(url, title, parseInline(label, base + open + 1)).withOffset(base + start, base + end)
                }
            }
        }

        // 引用式 [label][ref] / [label][] / [label]
        val refLabel: String = when {
            after < text.length && text[after] == '[' -> {
                val refClose = findClosingBracket(text, after)
                if (refClose < 0) return null
                val raw = text.substring(after + 1, refClose)
                val key = if (raw.isEmpty()) label else raw
                text.substring(0, after) // no-op，保持局部变量类型
                after = refClose + 1
                key
            }
            else -> label
        }
        val def = references[refLabel.trim().lowercase()] ?: return null
        return if (image) {
            ImageInline(def.url, stripMarkup(label)).withOffset(base + start, base + after)
        } else {
            LinkInline(def.url, def.title, parseInline(label, base + open + 1)).withOffset(base + start, base + after)
        }
    }

    private fun findClosingBracket(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text[i] == '\\' -> i += 2
                text[i] == '[' -> {
                    depth++
                    i++
                }
                text[i] == ']' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return -1
    }

    private fun parseInlineDestination(text: String, openParen: Int, base: Int): Triple<String, String?, Int>? {
        var i = openParen + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val url: String
        if (text[i] == '<') {
            val end = text.indexOf('>', i + 1)
            if (end < 0) return null
            url = decodeEscapesAndEntities(text.substring(i + 1, end))
            i = end + 1
        } else {
            val start = i
            var depth = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '(') depth++
                if (c == ')') {
                    if (depth == 0) break
                    depth--
                }
                if (c.isWhitespace() && depth == 0) break
                i++
            }
            url = decodeEscapesAndEntities(text.substring(start, i).trimEnd())
            if (url.isEmpty()) return null
        }
        while (i < text.length && text[i].isWhitespace()) i++
        var title: String? = null
        if (i < text.length && text[i] != ')') {
            val quote = text[i]
            if (quote == '"' || quote == '\'' || quote == '(') {
                val closeChar = if (quote == '(') ')' else quote
                val end = text.indexOf(closeChar, i + 1)
                if (end >= 0) {
                    title = decodeEscapesAndEntities(text.substring(i + 1, end))
                    i = end + 1
                }
            } else {
                val start = i
                while (i < text.length && text[i] != ')') i++
                title = decodeEscapesAndEntities(text.substring(start, i).trim())
            }
        }
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ')') return null
        return Triple(url, title, i + 1)
    }

    private fun stripMarkup(label: String): String =
        label.replace(Regex("""[*_`]"""), "")

    // ------------------------------------------------------------------ 尖括号/HTML/实体

    private fun parseAngle(text: String, start: Int, base: Int): MdInline? {
        val end = text.indexOf('>', start + 1)
        if (end < 0) return null
        val body = text.substring(start + 1, end)
        if (body.isNotEmpty() && !body.any { it == '<' || it == '\n' }) {
            if (AUTOLINK_SCHEME.matches(body)) {
                val node = LinkInline(body, null, listOf(TextInline(body)))
                return node.withOffset(base + start, base + end + 1)
            }
            if (EMAIL_REGEX.matches(body)) {
                val url = "mailto:$body"
                val node = LinkInline(url, null, listOf(TextInline(body)))
                return node.withOffset(base + start, base + end + 1)
            }
        }
        // HTML 行内块：按纯文本转义显示（SPEC §1.2）。
        if (HTML_INLINE_REGEX.matches(text.substring(start, end + 1))) {
            return HtmlInline(text.substring(start, end + 1)).withOffset(base + start, base + end + 1)
        }
        return null
    }

    private fun parseEntity(text: String, start: Int, base: Int): TextInline? {
        val end = text.indexOf(';', start + 1)
        if (end < 0 || end - start > 32) return null
        val body = text.substring(start + 1, end)
        val decoded = decodeEntityBody(body) ?: return null
        return TextInline(decoded).withOffset(base + start, base + end + 1)
    }

    // ------------------------------------------------------------------ 强调/删除线

    private class DelimRun(
        val pos: Int,
        val char: Char,
        val count: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
    ) {
        val end: Int get() = pos + count
    }

    private class EmMatch(
        val openRun: Int,
        val closeRun: Int,
        val strong: Boolean,
        val order: Int,
    )

    private fun processEmphasisRun(text: String, base: Int): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        if (!text.any { it == '*' || it == '_' }) return listOf(TextInline(text).withOffset(base, base + text.length))

        val runs = ArrayList<DelimRun>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '*' || c == '_') {
                var j = i
                while (j < text.length && text[j] == c) j++
                val count = j - i
                val prev = text.getOrNull(i - 1)
                val next = text.getOrNull(j)
                runs.add(DelimRun(i, c, count, canOpen(prev, next, c), canClose(prev, next, c)))
                i = j
            } else {
                i++
            }
        }
        if (runs.isEmpty()) return listOf(TextInline(text).withOffset(base, base + text.length))

        val remain = IntArray(runs.size) { runs[it].count }
        val matches = ArrayList<EmMatch>()
        var order = 0
        var closer = 0
        var guard = 0
        while (closer < runs.size) {
            if (guard++ > runs.size * runs.size + 8) break
            val closeRun = runs[closer]
            if (remain[closer] == 0 || !closeRun.canClose) {
                closer++
                continue
            }
            var opener = closer - 1
            var found = -1
            while (opener >= 0) {
                val openRun = runs[opener]
                if (openRun.char == closeRun.char && remain[opener] > 0 &&
                    ((openRun.canOpen && closeRun.canClose) || (openRun.canClose && closeRun.canOpen))
                ) {
                    found = opener
                    break
                }
                opener--
            }
            if (found < 0) {
                closer++
                continue
            }
            val use = if (remain[found] >= 2 && remain[closer] >= 2) 2 else 1
            remain[found] -= use
            remain[closer] -= use
            matches.add(EmMatch(found, closer, strong = use == 2, order = order++))
            if (remain[closer] == 0) closer++
        }
        if (matches.isEmpty()) return listOf(TextInline(text).withOffset(base, base + text.length))

        val matchesByOpen = matches.groupBy { it.openRun }
        val out = ArrayList<MdInline>()
        var cursor = 0
        for (openIdx in 0 until runs.size) {
            val openRun = runs[openIdx]
            if (openRun.pos < cursor) continue
            val group = matchesByOpen[openIdx]
            if (group.isNullOrEmpty()) continue

            // 处理同一 opener 的嵌套配对；正常输入下 close 相同。
            val closeIdx = group.minOf { it.closeRun }
            val sameClose = group.filter { it.closeRun == closeIdx }
            if (sameClose.isEmpty()) continue
            val closeRun = runs[closeIdx]
            var usedOpen = 0
            var usedClose = 0
            sameClose.forEach {
                usedOpen += if (it.strong) 2 else 1
                usedClose += if (it.strong) 2 else 1
            }
            val contentStart = openRun.pos + usedOpen.coerceAtMost(openRun.count)
            val contentEnd = closeRun.pos

            if (openRun.pos > cursor) {
                out.add(TextInline(text.substring(cursor, openRun.pos)).withOffset(base + cursor, base + openRun.pos))
            }

            var content: List<MdInline> = processEmphasisRun(text.substring(contentStart, contentEnd), base + contentStart)
            for (match in sameClose.sortedBy { it.order }) {
                content = listOf(
                    if (match.strong) StrongInline(content) else EmphasisInline(content),
                )
            }
            out.addAll(content)
            cursor = closeRun.pos + usedClose.coerceAtMost(closeRun.count)
        }
        if (cursor < text.length) {
            out.add(TextInline(text.substring(cursor)).withOffset(base + cursor, base + text.length))
        }
        if (out.isEmpty()) {
            out.add(TextInline(text).withOffset(base, base + text.length))
        }
        return out
    }

    private fun canOpen(prev: Char?, next: Char?, marker: Char): Boolean {
        val left = prev == null || prev.isWhitespace() || isPunctuation(prev)
        val right = next == null || next.isWhitespace() || isPunctuation(next)
        val leftFlanking = next != null && !next.isWhitespace() && (!isPunctuation(next) || left)
        val rightFlanking = prev != null && !prev.isWhitespace() && (!isPunctuation(prev) || right)
        return if (marker == '_') leftFlanking && (!rightFlanking || prev != null && isPunctuation(prev))
        else leftFlanking
    }

    private fun canClose(prev: Char?, next: Char?, marker: Char): Boolean {
        val left = prev == null || prev.isWhitespace() || isPunctuation(prev)
        val right = next == null || next.isWhitespace() || isPunctuation(next)
        val leftFlanking = next != null && !next.isWhitespace() && (!isPunctuation(next) || left)
        val rightFlanking = prev != null && !prev.isWhitespace() && (!isPunctuation(prev) || right)
        return if (marker == '_') rightFlanking && (!leftFlanking || next != null && isPunctuation(next))
        else rightFlanking
    }

    private fun isPunctuation(c: Char): Boolean =
        c.isLetterOrDigit().not() && c != '_' && !c.isWhitespace()

    // ------------------------------------------------------------------ 偏移

    private fun assignOffsets(nodes: List<MdInline>) {
        nodes.forEach { assignOffsets(it) }
    }

    private fun assignOffsets(node: MdInline) {
        when (node) {
            is StrongInline -> {
                node.children.forEach { assignOffsets(it) }
                if (node.children.isNotEmpty()) {
                    node.startOffset = node.children.first().startOffset
                    node.endOffset = node.children.last().endOffset
                }
            }
            is EmphasisInline -> {
                node.children.forEach { assignOffsets(it) }
                if (node.children.isNotEmpty()) {
                    node.startOffset = node.children.first().startOffset
                    node.endOffset = node.children.last().endOffset
                }
            }
            is StrikeInline -> {
                node.children.forEach { assignOffsets(it) }
                if (node.children.isNotEmpty()) {
                    node.startOffset = node.children.first().startOffset
                    node.endOffset = node.children.last().endOffset
                }
            }
            is LinkInline -> {
                node.children.forEach { assignOffsets(it) }
                if (node.children.isNotEmpty()) {
                    node.startOffset = node.children.first().startOffset
                    node.endOffset = node.children.last().endOffset
                }
            }
            else -> Unit
        }
    }

    companion object {
        private val AUTOLINK_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]{1,31}:[^ <>]*$""")
        private val EMAIL_REGEX = Regex("""^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$""")
        private val HTML_INLINE_REGEX = Regex(
            """^(<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<![A-Z][\s\S]*?>|<!\[CDATA\[[\s\S]*?]]>|</?[A-Za-z][A-Za-z0-9-]*(?:\s+[A-Za-z_:][A-Za-z0-9_.:-]*(?:\s*=\s*(?:[^ "'=<>`]+|'[^']*'|"[^"]*"))?)*\s*/?>)""",
        )
        private val NAMED_ENTITIES = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to "\u00A0",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "ldquo" to "“",
            "rdquo" to "”",
            "lsquo" to "‘",
            "rsquo" to "’",
            "AElig" to "Æ",
            "Dcaron" to "Ď",
            "frac34" to "¾",
            "HilbertSpace" to "ℋ",
            "DifferentialD" to "ⅆ",
            "ClockwiseContourIntegral" to "∲",
            "ngE" to "≧̸",
            "ouml" to "ö",
            "eacute" to "é",
            "auml" to "ä",
            "uuml" to "ü",
            "szlig" to "ß",
            "Alpha" to "Α",
            "Beta" to "Β",
            "Gamma" to "Γ",
            "Delta" to "Δ",
            "lambda" to "λ",
            "mu" to "μ",
            "pi" to "π",
            "sigma" to "σ",
            "phi" to "φ",
            "larr" to "←",
            "uarr" to "↑",
            "rarr" to "→",
            "darr" to "↓",
            "harr" to "↔",
            "crarr" to "↵",
            "lArr" to "⇐",
            "uArr" to "⇑",
            "rArr" to "⇒",
            "dArr" to "⇓",
            "hArr" to "⇔",
            "minus" to "−",
            "times" to "×",
            "divide" to "÷",
            "ne" to "≠",
            "le" to "≤",
            "ge" to "≥",
            "infin" to "∞",
            "radic" to "√",
            "sum" to "∑",
            "prod" to "∏",
            "int" to "∫",
            "part" to "∂",
            "nabla" to "∇",
            "isin" to "∈",
            "notin" to "∉",
            "ni" to "∋",
            "empty" to "∅",
            "cap" to "∩",
            "cup" to "∪",
            "sub" to "⊂",
            "sup" to "⊃",
            "sube" to "⊆",
            "supe" to "⊇",
            "oplus" to "⊕",
            "otimes" to "⊗",
            "perp" to "⊥",
            "sdot" to "⋅",
            "loz" to "◊",
            "spades" to "♠",
            "clubs" to "♣",
            "hearts" to "♥",
            "diams" to "♦",
        )

        /** 反斜杠转义 + 实体解码（用于链接 URL/标题与围栏 info 字符串）。 */
        fun decodeEscapesAndEntities(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length && isEscapableChar(raw[i + 1]) -> {
                        sb.append(raw[i + 1])
                        i += 2
                    }
                    c == '&' -> {
                        val semi = raw.indexOf(';', i + 1)
                        if (semi in 1..i + 32) {
                            val body = raw.substring(i + 1, semi)
                            val decoded = decodeEntityBody(body)
                            if (decoded != null) {
                                sb.append(decoded)
                                i = semi + 1
                            } else {
                                sb.append(c)
                                i++
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun isEscapableChar(c: Char): Boolean =
            c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        private fun decodeEntityBody(body: String): String? {
            if (body.startsWith("#x") || body.startsWith("#X")) {
                val code = body.substring(2).toIntOrNull(16) ?: return null
                return codePointToChar(code)
            }
            if (body.startsWith("#")) {
                val code = body.substring(1).toIntOrNull() ?: return null
                return codePointToChar(code)
            }
            return NAMED_ENTITIES[body]
        }

        private fun codePointToChar(code: Int): String? {
            if (code == 0) return "\uFFFD"
            if (!Character.isValidCodePoint(code)) return null
            return String(Character.toChars(code))
        }
    }
}
