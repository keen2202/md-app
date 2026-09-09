package com.moread.app.ui.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归测试：ViewHolder 只允许查找其布局中真实存在的控件 id。
 *
 * 事故背景（打开含引用块的 Markdown 立即闪退）：
 * `QuoteHolder` 继承 `TextHolder`，而 `TextHolder` 的属性初始化写在父类构造器里并固定
 * 查找 `R.id.md_text`；`item_md_quote.xml` 没有该控件，`findViewById` 返回 null，
 * Kotlin 的平台类型空检查随即抛 NPE。异常发生在 `onCreateViewHolder`
 * （`bind` 的 try/catch 只覆盖 `onBindViewHolder`），最终导致进程闪退。
 *
 * 本测试不依赖设备：直接比对 `ReaderAdapter.kt` 的控件查找与 `res/layout` 的 id 声明，
 * 把「Holder 只能找自己布局里的控件」这一约定固化为可回归的断言。
 */
class ViewHolderLayoutContractTest {

    private val adapterFile = File("src/main/java/com/moread/app/ui/reader/ReaderAdapter.kt")
    private val layoutDir = File("src/main/res/layout")

    @Test
    fun `每个 ViewHolder 只查找自身布局中存在的控件 id`() {
        val source = adapterFile.readText()

        val branches = parseBranches(source)
        assertTrue("未能从 ReaderAdapter.kt 解析出 onCreateViewHolder 的 viewType 分支：$branches", branches.size >= 6)

        val holders = parseHolderDeclarations(source)
        branches.map { it.holder }.distinct().forEach { name ->
            assertTrue("未能解析出 ViewHolder 内部类 $name 的声明", holders.containsKey(name))
        }

        branches.forEach { branch ->
            val layoutIds = layoutIds(branch.layout)
            val required = branch.expressionIds + inheritedIds(branch.holder, holders)
            val missing = required - layoutIds
            assertTrue(
                "${branch.holder} 在 ${branch.layout}.xml 中查找了不存在的控件 $missing；" +
                    "findViewById 会返回 null 并在 ViewHolder 构造器里抛 NPE（历史闪退原因）",
                missing.isEmpty(),
            )
        }
    }

    /** viewType 分支：Holder 类名 + 布局名 + 该分支表达式里直接出现的控件 id。 */
    private data class Branch(
        val holder: String,
        val layout: String,
        val expressionIds: Set<String>,
    )

    /** Holder 声明：自身引用的控件 id + 直接父类（用于沿继承链汇总）。 */
    private data class HolderDecl(
        val ids: Set<String>,
        val superName: String?,
    )

    private fun parseBranches(source: String): List<Branch> {
        val body = functionBody(source, "onCreateViewHolder") ?: return emptyList()
        val markers = Regex("""(?:TYPE_[A-Z_]+|else)\s*->""").findAll(body).toList()
        return markers.mapIndexedNotNull { index, marker ->
            val end = markers.getOrNull(index + 1)?.range?.first ?: body.length
            val expression = body.substring(marker.range.last + 1, end)
            val head = Regex("""^\s*(\w+)\s*\(\s*inflater\.inflate\(R\.layout\.(\w+)""").find(expression)
                ?: return@mapIndexedNotNull null
            Branch(
                holder = head.groupValues[1],
                layout = head.groupValues[2],
                expressionIds = idRefs(expression),
            )
        }
    }

    private fun parseHolderDeclarations(source: String): Map<String, HolderDecl> {
        val result = LinkedHashMap<String, HolderDecl>()
        Regex("""(?:open\s+)?inner\s+class\s+(\w+)""").findAll(source).forEach { match ->
            val bodyStart = source.indexOf('{', match.range.last)
            if (bodyStart < 0) return@forEach
            val bodyEnd = matchingBrace(source, bodyStart)
            if (bodyEnd < 0) return@forEach
            val header = source.substring(match.range.first, bodyStart)
            val body = source.substring(bodyStart, bodyEnd + 1)
            result[match.groupValues[1]] = HolderDecl(
                ids = idRefs(header + body),
                superName = superClassName(header),
            )
        }
        return result
    }

    /** 沿继承链汇总 Holder 自身及所有父类声明的控件 id（不包含父类未声明的外部类）。 */
    private fun inheritedIds(name: String, holders: Map<String, HolderDecl>): Set<String> {
        val ids = LinkedHashSet<String>()
        var current: String? = name
        val visited = HashSet<String>()
        while (current != null && visited.add(current)) {
            val decl = holders[current] ?: break
            ids += decl.ids
            current = decl.superName
        }
        return ids
    }

    private fun idRefs(text: String): Set<String> =
        Regex("""R\.id\.(\w+)""").findAll(text).map { it.groupValues[1] }.toSet()

    private fun layoutIds(layout: String): Set<String> {
        val file = File(layoutDir, "$layout.xml")
        assertTrue("布局文件不存在：${file.path}", file.isFile)
        return Regex("""@\+id/(\w+)""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun functionBody(source: String, functionName: String): String? {
        val signature = Regex("""fun\s+$functionName\s*\(""").find(source) ?: return null
        val bodyStart = source.indexOf('{', signature.range.last)
        if (bodyStart < 0) return null
        val bodyEnd = matchingBrace(source, bodyStart)
        if (bodyEnd < 0) return null
        return source.substring(bodyStart, bodyEnd + 1)
    }

    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 跳过类自身的构造参数列表后，取 `:` 之后的父类简单名。 */
    private fun superClassName(header: String): String? {
        val open = header.indexOf('(')
        if (open < 0) return null
        var depth = 0
        var index = open
        while (index < header.length) {
            when (header[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            index++
        }
        val rest = header.substring((index + 1).coerceAtMost(header.length))
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        return Regex("""[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""")
            .find(rest.substring(colon + 1))
            ?.value
            ?.substringAfterLast('.')
    }
}
