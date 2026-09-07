package com.moread.app.reader.highlight.lang

val Cpp = LanguageDef(
    name = "cpp",
    aliases = setOf("c++", "cc", "cxx", "hpp"),
    keywords = setOf(
        "alignas","alignof","and","and_eq","asm","atomic_cancel","atomic_commit","atomic_noexcept","auto",
        "bitand","bitor","bool","break","case","catch","char","char8_t","char16_t","char32_t","class",
        "compl","concept","const","consteval","constexpr","constinit","const_cast","continue","co_await",
        "co_return","co_yield","decltype","default","delete","do","double","dynamic_cast","else","enum",
        "explicit","export","extern","false","float","for","friend","goto","if","inline","int","long",
        "mutable","namespace","new","noexcept","not","not_eq","nullptr","operator","or","or_eq","private",
        "protected","public","reflexpr","register","reinterpret_cast","requires","return","short","signed",
        "sizeof","static","static_assert","static_cast","struct","switch","synchronized","template","this",
        "thread_local","throw","true","try","typedef","typeid","typename","union","unsigned","using",
        "virtual","void","volatile","wchar_t","while","xor","xor_eq",
    ),
    types = setOf("string","wstring","u16string","u32string","vector","array","map","unordered_map","set","unordered_set","list","deque","forward_list","pair","tuple","optional","variant","any","shared_ptr","unique_ptr","weak_ptr","function","move_only_function","span","string_view","iostream","ostream","istream","fstream","sstream","exception","stdexcept"),
    constants = setOf("nullptr","true","false","NULL","EOF","stdin","stdout","stderr"),
    lineComments = listOf("//"),
    blockComments = listOf("/*" to "*/"),
    stringDelimiters = setOf('"', '\''),
)
