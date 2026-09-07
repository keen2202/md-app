package com.moread.app.reader.highlight.lang

val TypeScript = LanguageDef(
    name = "ts",
    aliases = setOf("typescript", "tsx"),
    keywords = setOf(
        "abstract","any","as","asserts","async","await","boolean","break","case","catch","class","const",
        "constructor","continue","debugger","declare","default","delete","do","else","enum","export","extends",
        "finally","for","from","function","get","if","implements","import","in","infer","instanceof","interface",
        "is","keyof","let","module","namespace","never","new","null","number","object","of","private","protected",
        "public","readonly","require","return","set","static","string","super","switch","symbol","this","throw",
        "try","type","typeof","undefined","unique","unknown","var","void","while","with","yield","true","false",
    ),
    types = setOf("Array","Boolean","Date","Error","Function","JSON","Map","Math","Number","Object","Promise","ReadonlyArray","Record","RegExp","Set","String","Partial","Required","Pick","Omit","Exclude","Extract","NonNullable","ReturnType","Parameters"),
    constants = setOf("NaN","Infinity","undefined","this","arguments"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\'', '`'),
)
