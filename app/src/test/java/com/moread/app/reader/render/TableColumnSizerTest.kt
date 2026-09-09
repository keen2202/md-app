package com.moread.app.reader.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格列宽算法回归测试。
 *
 * 事故背景：表头与表体、单元格之间列线不对齐。根因是单元格用 `width=0 + weight=1`，
 * 权重分配的剩余空间取决于每行自身内容宽度，行与行不同 → 同一列宽度不一致。
 * 修复后所有行共用同一组列宽，本测试锁定该契约：
 * 同一列取所有行的最大自然宽度、不低于最小宽度、能放下就精确铺满/压缩到可用宽度、
 * 放不下才交给横向滚动。
 */
class TableColumnSizerTest {

    private val min = 56

    @Test
    fun `同一列取所有行（含表头）的最大自然宽度`() {
        // 表头第 2 列只有 400，表体第 2 列有 900 → 列宽取 900，两行才会对齐。
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(
                listOf(120, 400, 200),
                listOf(80, 900, 150),
            ),
            availableWidth = 1220,
            minColumnWidth = 50,
        )
        assertArrayEquals(intArrayOf(120, 900, 200), widths)
    }

    @Test
    fun `内容比可用宽度窄时按比例铺满可用宽度`() {
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(100, 300)),
            availableWidth = 1000,
            minColumnWidth = min,
        )
        assertEquals("铺满后总和应精确等于可用宽度", 1000, widths.sum())
        assertTrue("拉伸应保持自然宽度比例，窄列仍窄", widths[0] < widths[1])
        assertTrue("拉伸后每列不小于自然宽度", widths[0] >= 100 && widths[1] >= 300)
    }

    @Test
    fun `整除余数分摊后总和精确等于可用宽度`() {
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(3, 7, 11)),
            availableWidth = 1000,
            minColumnWidth = 1,
        )
        assertEquals(1000, widths.sum())
    }

    @Test
    fun `内容比可用宽度宽时压缩换行而非横向滚动`() {
        // 三列自然宽度各 300，可视区只有 500：压缩到刚好放下，每列都不低于最小宽度。
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(300, 300, 300)),
            availableWidth = 500,
            minColumnWidth = min,
        )
        assertEquals("压缩后总和应精确等于可用宽度", 500, widths.sum())
        assertTrue("每列不得低于最小宽度", widths.all { it >= min })
        assertTrue("每列都应被压缩", widths.all { it < 300 })
    }

    @Test
    fun `长文本列被压缩时短列保留下限且宽列仍最宽`() {
        // 复刻「日 | 任务 | 产出」这类真实表格：第 2 列很长，第 3 列中等，第 1 列极短。
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(
                listOf(126, 2730, 984),
                listOf(126, 1400, 700),
            ),
            availableWidth = 984,
            minColumnWidth = 168,
        )
        assertEquals(984, widths.sum())
        assertEquals("极短列落到最小宽度", 168, widths[0])
        assertTrue("中等列仍应窄于长文本列", widths[2] < widths[1])
        assertTrue("压缩后各列不低于最小宽度", widths.all { it >= 168 })
    }

    @Test
    fun `所有列压到最小宽度仍放不下时保持最小宽度并横向滚动`() {
        // 3 列 × 下限 200 = 600 > 500：压无可压，交给 HorizontalScrollView。
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(900, 900, 900)),
            availableWidth = 500,
            minColumnWidth = 200,
        )
        assertArrayEquals(intArrayOf(200, 200, 200), widths)
        assertTrue("总和超过可用宽度 → 外层横向滚动", widths.sum() > 500)
    }

    @Test
    fun `单列宽度不低于最小宽度`() {
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(4)),
            availableWidth = 0,
            minColumnWidth = min,
        )
        assertEquals(min, widths[0])
    }

    @Test
    fun `可用宽度未知时不压缩也不铺满`() {
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(listOf(100, 200)),
            availableWidth = 0,
            minColumnWidth = min,
        )
        assertArrayEquals(intArrayOf(100, 200), widths)
    }

    @Test
    fun `各行单元格数量不一致时按最多的一行确定列数`() {
        val widths = TableColumnSizer.columnWidths(
            naturalWidths = listOf(
                listOf(100),
                listOf(100, 200, 300),
            ),
            availableWidth = 0,
            minColumnWidth = 50,
        )
        assertArrayEquals(intArrayOf(100, 200, 300), widths)
    }

    @Test
    fun `空表格返回空列宽`() {
        assertEquals(0, TableColumnSizer.columnWidths(emptyList(), availableWidth = 800, minColumnWidth = min).size)
        assertEquals(0, TableColumnSizer.columnWidths(listOf(emptyList()), availableWidth = 800, minColumnWidth = min).size)
    }
}
