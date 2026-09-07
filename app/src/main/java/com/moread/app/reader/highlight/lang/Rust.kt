package com.moread.app.reader.highlight.lang

val Rust = LanguageDef(
    name = "rust",
    aliases = setOf("rs"),
    keywords = setOf(
        "as","async","await","break","const","continue","crate","dyn","else","enum","extern","false",
        "fn","for","if","impl","in","let","loop","match","mod","move","mut","pub","ref","return",
        "self","Self","static","struct","super","trait","true","type","union","unsafe","use","where","while",
    ),
    types = setOf("bool","char","f32","f64","i8","i16","i32","i64","i128","isize","str","u8","u16","u32","u64","u128","usize","String","Vec","Option","Result","Box","Rc","Arc","HashMap","HashSet","Iterator"),
    constants = setOf("true","false","None","Some","Ok","Err"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"'),
)
