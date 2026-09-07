package com.moread.app.reader.highlight

import com.moread.app.reader.highlight.lang.LanguageDef
import com.moread.app.reader.highlight.lang.LanguageRegistry
import java.util.Locale

enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, PLAIN }

data class HighlightToken(
    val type: TokenType,
    val start: Int,
    val end: Int,
)

/**
 * 规则表驱动的轻量语法高亮器（SPEC §1.4）。
 *
 * - 6 类 token：关键字/字符串/注释/数字/类型/函数；
 * - 每语言一个 [LanguageDef] 静态语法表，无运行时下载；
 * - 未知语言按 plaintext 降级；
 * - 单块高亮结果按语言 + 文本 hash 缓存（代码块内容只读）。
 */
class Highlighter {

    private val cache = LinkedHashMap<CacheKey, List<HighlightToken>>(64, 0.75f, true)

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()
        val def = LanguageRegistry.defFor(language)
        if (def.plaintext) return emptyList()
        val key = CacheKey(def.name, code)
        cache[key]?.let { return it }
        val tokens = tokenize(code, def)
        if (cache.size >= 64) {
            val iterator = cache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        cache[key] = tokens
        return tokens
    }

    private fun tokenize(code: String, def: LanguageDef): List<HighlightToken> {
        val tokens = ArrayList<HighlightToken>()
        var i = 0
        var guard = 0
        val n = code.length
        while (i < n) {
            if (guard++ > n + 64) break
            val c = code[i]

            // 1. 行注释
            val lineComment = def.lineComments.firstOrNull { code.startsWith(it, i) }
            if (lineComment != null) {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                tokens.add(HighlightToken(TokenType.COMMENT, i, end))
                i = end
                continue
            }
            // 2. 块注释
            val blockComment = def.blockComments.firstOrNull { code.startsWith(it.first, i) }
            if (blockComment != null) {
                val close = code.indexOf(blockComment.second, i + blockComment.first.length)
                val end = if (close < 0) n else close + blockComment.second.length
                tokens.add(HighlightToken(TokenType.COMMENT, i, end))
                i = end
                continue
            }
            // 3. 字符串（先处理 Python 三引号）
            val triple = def.tripleQuotedStrings.firstOrNull { code.startsWith(it, i) }
            if (triple != null) {
                val close = code.indexOf(triple, i + triple.length)
                val end = if (close < 0) n else close + triple.length
                tokens.add(HighlightToken(TokenType.STRING, i, end))
                i = end
                continue
            }
            if (def.stringDelimiters.contains(c)) {
                val end = scanString(code, i, c, def.rawStringDelimiters.contains(c))
                tokens.add(HighlightToken(TokenType.STRING, i, end))
                i = end
                continue
            }
            // 4. 数字
            if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
                val end = scanNumber(code, i)
                tokens.add(HighlightToken(TokenType.NUMBER, i, end))
                i = end
                continue
            }
            // 5. 标识符/关键字/类型/函数
            if (c.isLetter() || c == '_' || c == '$' || c == '@') {
                val start = i
                i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) i++
                val word = code.substring(start, i)
                val lower = word.lowercase(Locale.ROOT)
                val type = when {
                    lower in def.keywords -> TokenType.KEYWORD
                    word in def.types || lower in def.typesLower -> TokenType.TYPE
                    lower in def.constants -> TokenType.KEYWORD
                    isFunctionCall(code, i) -> TokenType.FUNCTION
                    else -> TokenType.PLAIN
                }
                if (type != TokenType.PLAIN) tokens.add(HighlightToken(type, start, i))
                i = if (i == start) start + 1 else i
                continue
            }
            i++
        }
        // 合并相邻的 PLAIN 段，减少 Span 数量。
        val merged = ArrayList<HighlightToken>(tokens.size)
        for (token in tokens) {
            if (token.type == TokenType.PLAIN) continue
            val last = merged.lastOrNull()
            if (last != null && last.type == token.type && last.end == token.start) {
                merged[merged.size - 1] = last.copy(end = token.end)
            } else {
                merged.add(token)
            }
        }
        return merged
    }

    private fun scanString(code: String, start: Int, quote: Char, raw: Boolean): Int {
        var i = start + 1
        var escaped = false
        while (i < code.length) {
            val c = code[i]
            if (!raw && c == '\\') {
                escaped = !escaped
                if (escaped) {
                    i += 2
                    escaped = false
                    continue
                }
            }
            if (c == quote) return i + 1
            if (c == '\n' && quote != '`') return i.coerceAtMost(code.length)
            i++
        }
        return code.length
    }

    private fun scanNumber(code: String, start: Int): Int {
        var i = start
        if (i + 1 < code.length && code[i] == '0' && (code[i + 1] == 'x' || code[i + 1] == 'X')) {
            i += 2
            while (i < code.length && (code[i].isDigit() || code[i] in 'a'..'f' || code[i] in 'A'..'F')) i++
            return i
        }
        var dot = false
        var exp = false
        while (i < code.length) {
            val c = code[i]
            when {
                c.isDigit() -> i++
                c == '.' && !dot && !exp -> {
                    dot = true
                    i++
                }
                (c == 'e' || c == 'E') && !exp -> {
                    exp = true
                    i++
                    if (i < code.length && (code[i] == '+' || code[i] == '-')) i++
                }
                else -> break
            }
        }
        return i
    }

    private fun isFunctionCall(code: String, identifierEnd: Int): Boolean {
        var i = identifierEnd
        while (i < code.length && code[i].isWhitespace()) i++
        return i < code.length && code[i] == '('
    }

    private data class CacheKey(val language: String, val code: String)
}
