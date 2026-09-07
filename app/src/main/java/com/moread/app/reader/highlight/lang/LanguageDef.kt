package com.moread.app.reader.highlight.lang

/**
 * 语言静态语法表（SPEC §1.4）。
 * 关键字/类型/常量集合在编译期内置，规则按 [Highlighter] 中的固定顺序短路匹配。
 */
data class LanguageDef(
    val name: String,
    val aliases: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val constants: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelimiters: Set<Char> = setOf('"', '\''),
    val rawStringDelimiters: Set<Char> = emptySet(),
    val tripleQuotedStrings: List<String> = emptyList(),
) {
    val typesLower: Set<String> by lazy { types.map { it.lowercase() }.toSet() }
    val plaintext: Boolean get() = keywords.isEmpty() && types.isEmpty() && constants.isEmpty() && lineComments.isEmpty()
    val rules: Int get() = keywords.size + types.size + constants.size + lineComments.size + blockComments.size
}
