package com.moread.app.reader.highlight.lang

import java.util.Locale

/** Top 20 语言注册表；未知语言回落到 plaintext。 */
object LanguageRegistry {
    val all: List<LanguageDef> = listOf(
        JavaScript,
        TypeScript,
        Python,
        Java,
        Go,
        Rust,
        C,
        Cpp,
        Shell,
        Json,
        Yaml,
        Html,
        Css,
        Sql,
        Kotlin,
        Swift,
        Php,
        Ruby,
        Markdown,
        PlainText,
    )

    private val byName: Map<String, LanguageDef> = buildMap {
        all.forEach { def ->
            put(def.name, def)
            def.aliases.forEach { put(it, def) }
        }
    }

    fun defFor(language: String): LanguageDef =
        byName[language.trim().lowercase(Locale.ROOT)] ?: PlainText

    fun contains(name: String): Boolean = byName.containsKey(name.trim().lowercase(Locale.ROOT))
}
