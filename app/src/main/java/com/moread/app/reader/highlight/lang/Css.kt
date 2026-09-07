package com.moread.app.reader.highlight.lang

val Css = LanguageDef(
    name = "css",
    aliases = setOf("scss", "less", "postcss"),
    keywords = setOf(
        "align-content","align-items","align-self","animation","appearance","background","background-color",
        "background-image","background-position","background-repeat","background-size","border","border-radius",
        "bottom","box-shadow","box-sizing","color","content","cursor","display","filter","flex","flex-direction",
        "flex-grow","flex-shrink","flex-wrap","float","font","font-family","font-size","font-style","font-weight",
        "gap","grid","grid-template-columns","height","justify-content","left","letter-spacing","line-height",
        "list-style","margin","margin-bottom","margin-left","margin-right","margin-top","max-height","max-width",
        "min-height","min-width","opacity","overflow","padding","position","right","text-align","text-decoration",
        "text-transform","top","transform","transition","visibility","white-space","width","z-index",
        "important","media","supports","keyframes","from","to",
    ),
    types = setOf("em","rem","px","vw","vh","vmin","vmax","fr","deg","s","ms","pt","pc","cm","mm","in","ch","ex","lh","rlh"),
    constants = setOf("inherit","initial","unset","revert","none","auto","block","inline","inline-block","flex","grid","absolute","relative","fixed","sticky","solid","dashed","dotted","transparent"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
)
