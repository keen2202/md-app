package com.moread.app.reader.highlight.lang

val Ruby = LanguageDef(
    name = "ruby",
    aliases = setOf("rb", "rake", "gemfile"),
    keywords = setOf(
        "alias","and","begin","break","case","class","def","defined","do","else","elsif","end","ensure",
        "false","for","if","in","module","next","nil","not","or","redo","rescue","retry","return","self",
        "super","then","true","undef","unless","until","when","while","yield","require","require_relative",
    ),
    types = setOf("Array","Hash","String","Symbol","Integer","Float","Numeric","Object","Class","Module","Proc","Lambda","Range","Regexp","Time","Date","Exception","StandardError"),
    constants = setOf("nil","true","false","STDIN","STDOUT","STDERR","ENV","ARGV"),
    lineComments = listOf("#"),
    blockComments = listOf("=begin" to "=end"),
    stringDelimiters = setOf('"', '\''),
    rawStringDelimiters = setOf('\''),
)
