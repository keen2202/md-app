package com.moread.app.reader.highlight.lang

val Kotlin = LanguageDef(
    name = "kotlin",
    aliases = setOf("kt", "kts"),
    keywords = setOf(
        "as","break","class","continue","do","else","false","for","fun","if","in","interface","is",
        "null","object","package","return","super","this","throw","true","try","typealias","typeof",
        "val","var","when","while","by","catch","constructor","delegate","dynamic","field","file",
        "finally","get","import","init","param","property","receiver","set","setparam","where","actual",
        "abstract","annotation","companion","const","crossinline","data","enum","expect","external",
        "final","infix","inline","inner","internal","lateinit","noinline","open","operator","out",
        "override","private","protected","public","reified","sealed","suspend","tailrec","vararg",
    ),
    types = setOf("Any","Array","Boolean","Byte","Char","Comparable","Double","Enum","Float","Int","Long","Nothing","Number","Short","String","Throwable","Unit","List","Map","Set","MutableList","MutableMap","MutableSet","Sequence","Pair","Triple","Result","Regex","IntArray","LongArray"),
    constants = setOf("true","false","null","this","super"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
    tripleQuotedStrings = listOf("\"\"\""),
)
