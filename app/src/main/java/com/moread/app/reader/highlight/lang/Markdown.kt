package com.moread.app.reader.highlight.lang

val Markdown = LanguageDef(
    name = "markdown",
    aliases = setOf("md", "mdx"),
    // Markdown 代码块高亮使用 plaintext 语义：标题/强调由渲染引擎负责。
)
