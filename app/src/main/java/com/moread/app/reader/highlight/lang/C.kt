package com.moread.app.reader.highlight.lang

val C = LanguageDef(
    name = "c",
    aliases = setOf("clang", "gnu-c"),
    keywords = setOf(
        "auto","break","case","char","const","continue","default","do","double","else","enum","extern",
        "float","for","goto","if","inline","int","long","register","restrict","return","short","signed",
        "sizeof","static","struct","switch","typedef","union","unsigned","void","volatile","while",
        "_Alignas","_Alignof","_Atomic","_Bool","_Complex","_Generic","_Imaginary","_Noreturn","_Static_assert","_Thread_local",
    ),
    types = setOf("FILE","size_t","ssize_t","ptrdiff_t","int8_t","uint8_t","int16_t","uint16_t","int32_t","uint32_t","int64_t","uint64_t","bool","NULL"),
    constants = setOf("NULL","EOF","true","false"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
)
