package com.moread.app.reader.highlight.lang

val Sql = LanguageDef(
    name = "sql",
    aliases = setOf("mysql","postgres","postgresql","sqlite","tsql","plsql"),
    keywords = setOf(
        "add","all","alter","and","any","as","asc","backup","between","by","case","check","column",
        "constraint","create","database","default","delete","desc","distinct","drop","else","end",
        "exec","exists","foreign","from","full","grant","group","having","in","index","inner","insert",
        "into","is","join","key","left","like","limit","not","null","on","or","order","outer","primary",
        "procedure","references","right","rollback","row","rownum","select","set","table","then","top",
        "truncate","union","unique","update","values","view","when","where","with",
    ),
    types = setOf("int","integer","bigint","smallint","tinyint","numeric","decimal","real","float","double","char","varchar","text","date","datetime","timestamp","time","boolean","bool","blob","clob","binary","varbinary","json","xml","uuid","serial","bigserial"),
    constants = setOf("null","true","false","count","sum","avg","min","max","coalesce","cast","convert"),
    lineComments = listOf("--"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\'', '`'),
)
