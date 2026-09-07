package com.moread.app.reader.highlight.lang

val Php = LanguageDef(
    name = "php",
    aliases = setOf("php7", "php8"),
    keywords = setOf(
        "abstract","and","array","as","break","callable","case","catch","class","clone","const","continue",
        "declare","default","die","do","echo","else","elseif","empty","enddeclare","endfor","endforeach",
        "endif","endswitch","endwhile","enum","eval","exit","extends","final","finally","fn","for","foreach",
        "function","global","goto","if","implements","include","include_once","instanceof","insteadof",
        "interface","isset","list","match","namespace","new","or","print","private","protected","public",
        "readonly","require","require_once","return","static","switch","throw","trait","try","unset","use",
        "var","while","xor","yield","true","false","null",
    ),
    types = setOf("int","float","string","bool","array","object","callable","iterable","mixed","void","never","self","parent","static","resource","null","false","true","DateTime","Exception","Closure","Generator","stdClass"),
    constants = setOf("true","false","null","PHP_EOL","PHP_INT_MAX","DIRECTORY_SEPARATOR"),
    lineComments = listOf("//", "#"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
)
