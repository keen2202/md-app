package com.moread.app.reader.highlight.lang

val Python = LanguageDef(
    name = "python",
    aliases = setOf("py", "python3"),
    keywords = setOf(
        "and","as","assert","async","await","break","class","continue","def","del","elif","else",
        "except","finally","for","from","global","if","import","in","is","lambda","nonlocal","not",
        "or","pass","raise","return","try","while","with","yield","True","False","None","match","case",
    ),
    types = setOf("bool","bytearray","bytes","complex","dict","float","frozenset","int","list","object","range","set","slice","str","tuple","type","Exception","ValueError","TypeError","KeyError","IndexError","RuntimeError"),
    constants = setOf("True","False","None","self","cls","__name__","__main__"),
    lineComments = listOf("#"),
    stringDelimiters = setOf('"', '\''),
    rawStringDelimiters = setOf('"', '\''),
    tripleQuotedStrings = listOf("\"\"\"", "'''"),
)
