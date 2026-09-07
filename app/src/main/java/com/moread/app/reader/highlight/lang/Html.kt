package com.moread.app.reader.highlight.lang

val Html = LanguageDef(
    name = "html",
    aliases = setOf("htm", "xhtml", "xml", "svg"),
    keywords = setOf("DOCTYPE","a","abbr","address","article","aside","audio","b","base","blockquote","body","br","button","canvas","code","col","data","datalist","dd","del","details","dialog","div","dl","dt","em","fieldset","figcaption","figure","footer","form","h1","h2","h3","h4","h5","h6","head","header","hr","html","i","iframe","img","input","label","legend","li","link","main","map","mark","meta","nav","ol","optgroup","option","output","p","picture","pre","progress","q","s","script","section","select","small","source","span","strong","style","sub","summary","sup","table","tbody","td","template","textarea","tfoot","th","thead","time","title","tr","track","u","ul","var","video"),
    types = setOf("id","class","href","src","alt","type","name","value","style","width","height"),
    constants = setOf("true","false","null"),
    blockComments = listOf("<!--" to "-->"),
    stringDelimiters = setOf('"', '\''),
)
