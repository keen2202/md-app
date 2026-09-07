package com.moread.app.reader.highlight.lang

val Java = LanguageDef(
    name = "java",
    aliases = setOf("jdk"),
    keywords = setOf(
        "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
        "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
        "implements","import","instanceof","int","interface","long","native","new","package","private",
        "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","var","record","sealed","permits",
        "true","false","null",
    ),
    types = setOf("String","Object","Integer","Long","Double","Float","Boolean","Byte","Short","Character","Math","System","Runtime","Thread","Exception","RuntimeException","List","ArrayList","Map","HashMap","Set","HashSet","Optional","Stream","StringBuilder"),
    constants = setOf("null","true","false"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
)
