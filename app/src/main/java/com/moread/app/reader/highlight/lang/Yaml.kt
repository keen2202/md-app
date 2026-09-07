package com.moread.app.reader.highlight.lang

val Yaml = LanguageDef(
    name = "yaml",
    aliases = setOf("yml"),
    keywords = setOf("true","false","null","yes","no","on","off","---","...","-"),
    constants = setOf("true","false","null","~"),
    lineComments = listOf("#"),
    stringDelimiters = setOf('"', '\''),
)
