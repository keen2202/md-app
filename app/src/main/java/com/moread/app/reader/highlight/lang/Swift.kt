package com.moread.app.reader.highlight.lang

val Swift = LanguageDef(
    name = "swift",
    aliases = setOf("swiftui"),
    keywords = setOf(
        "associatedtype","class","deinit","enum","extension","fileprivate","func","import","init","inout",
        "internal","let","open","operator","private","precedencegroup","protocol","public","rethrows",
        "static","struct","subscript","typealias","var","break","case","continue","default","defer","do",
        "else","fallthrough","for","guard","if","in","repeat","return","throw","switch","where","while",
        "as","Any","catch","false","is","nil","super","self","Self","throws","true","try","async","await","actor","some","any",
    ),
    types = setOf("Array","Bool","Character","ClosedRange","Dictionary","Double","Float","Int","Int8","Int16","Int32","Int64","Optional","Range","Set","String","UInt","UInt8","UInt16","UInt32","UInt64","URL","Data","Date","Error","Result"),
    constants = setOf("nil","true","false","self","Self","super","NSNotFound"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"'),
    tripleQuotedStrings = listOf("\"\"\""),
)
