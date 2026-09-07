package com.moread.app.reader.highlight.lang

val JavaScript = LanguageDef(
    name = "js",
    aliases = setOf("javascript", "jsx", "node"),
    keywords = setOf(
        "break","case","catch","class","const","continue","debugger","default","delete","do","else",
        "export","extends","finally","for","function","if","import","in","instanceof","let","new",
        "of","return","static","super","switch","this","throw","try","typeof","var","void","while",
        "with","yield","async","await","null","true","false",
    ),
    types = setOf("Array","Boolean","Date","Error","Function","JSON","Map","Math","Number","Object","Promise","RegExp","Set","String","Symbol","WeakMap","WeakSet","BigInt","Int8Array","Uint8Array","Uint8ClampedArray","Int16Array","Uint16Array","Int32Array","Uint32Array","Float32Array","Float64Array"),
    constants = setOf("NaN","Infinity","undefined","this","arguments"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\'', '`'),
)
