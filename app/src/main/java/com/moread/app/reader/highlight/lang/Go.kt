package com.moread.app.reader.highlight.lang

val Go = LanguageDef(
    name = "go",
    aliases = setOf("golang"),
    keywords = setOf(
        "break","case","chan","const","continue","default","defer","else","fallthrough","for","func",
        "go","goto","if","import","interface","map","package","range","return","select","struct","switch",
        "type","var","nil","true","false","iota",
    ),
    types = setOf("bool","byte","complex64","complex128","error","float32","float64","int","int8","int16","int32","int64","rune","string","uint","uint8","uint16","uint32","uint64","uintptr","any","comparable"),
    constants = setOf("nil","true","false","iota","len","cap","make","new","append","copy","delete","panic","recover","print","println"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\'', '`'),
    rawStringDelimiters = setOf('`'),
)
