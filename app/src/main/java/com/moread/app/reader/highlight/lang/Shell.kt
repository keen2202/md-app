package com.moread.app.reader.highlight.lang

val Shell = LanguageDef(
    name = "shell",
    aliases = setOf("sh", "bash", "zsh", "shellscript", "console"),
    keywords = setOf(
        "if","then","else","elif","fi","for","while","until","do","done","case","esac","function",
        "select","time","coproc","in","local","export","readonly","return","exit","break","continue",
        "source","alias","unalias","set","unset","shift","trap","ulimit","umask","cd","pushd","popd",
        "echo","printf","read","test","exec","eval","typeset","declare","let","getopts","true","false",
    ),
    types = setOf("array","declare","export","local","readonly"),
    constants = setOf("$0","$1","$2","$3","$4","$5","$6","$7","$8","$9","$?","$#","$@","$*","$$","$!"),
    lineComments = listOf("#"),
    stringDelimiters = setOf('"', '\'', '`'),
    rawStringDelimiters = setOf('\''),
)
