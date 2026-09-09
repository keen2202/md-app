package com.moread.app.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归测试：表格必须按「共享列宽」布局，禁止回退到权重自适应。
 *
 * 事故背景（表格列不对齐）：`bindTable` 曾用
 * `LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)` 布局单元格。LinearLayout 按权重分配的
 * 「剩余空间」是 `行宽 − 本行内容宽度之和`，而每行内容宽度不同（表头加粗、表体长短不一），
 * 于是同一列在表头行与表体行、以及不同表体行之间宽度都不一样，列线歪斜。
 *
 * 修复后所有行共用 `TableColumnSizer` 算出的一组列宽，本测试把这一约定固化下来。
 */
class TableLayoutContractTest {

    private val adapterFile = File("src/main/java/com/moread/app/ui/reader/ReaderAdapter.kt")

    @Test
    fun `表格单元格使用共享列宽而非权重自适应`() {
        val body = functionBody(adapterFile.readText(), "bindTable")
        assertTrue("未能从 ReaderAdapter.kt 解析出 bindTable 方法体", body != null)

        assertTrue(
            "bindTable 必须通过 TableColumnSizer 计算各列共用宽度，否则表头与表体无法对齐",
            body!!.contains("TableColumnSizer.columnWidths("),
        )

        val weightLayout = Regex("""LayoutParams\(\s*0\s*,.*1f\s*\)""")
        assertFalse(
            "表格单元格不得使用 width=0 + weight=1：权重分配的剩余空间取决于每行自身内容宽度，" +
                "行与行不同 → 同一列宽度不一致，列线无法对齐",
            weightLayout.containsMatchIn(body),
        )

        assertTrue(
            "每一行必须显式指定与列宽之和相同的宽度，否则各行宽度不一致、列线无法对齐",
            body.contains("tableWidth"),
        )
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
}
