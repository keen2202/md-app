package com.moread.app.reader.render

/**
 * GFM 表格列宽计算（纯函数、无 Android 依赖 → 可在 JVM 单测里覆盖）。
 *
 * 事故背景（表格列不对齐）：
 * 旧实现把每个单元格设成 `width=0, weight=1`，每行是 `HorizontalScrollView` 内
 * `wrap_content` 的横向 `LinearLayout`。LinearLayout 会先按内容宽度测量这些单元格，
 * 再按权重分配「剩余空间 = 行可用宽度 − 本行内容宽度之和」。行可用宽度各行相同，
 * 但每行内容宽度不同（表头加粗字形更宽、表体长短不一），于是每行分到的空间也不同——
 * 同一列在表头行与表体行、以及不同表体行之间宽度都不一样，列线因此歪斜。
 *
 * 修复思路：先量出每个单元格的「内容自然宽度」，按列取所有行（含表头）的最大值，
 * 得到**一组列宽供所有行共用**。列宽固定后，同一列的任意两行必然对齐。
 *
 * 列宽策略（对齐 SPEC §1.4「列宽按内容自适应，超宽可滑动」）：
 * - 内容比可视区窄 → 按比例铺满，表格与正文同宽；
 * - 内容比可视区宽 → 压缩列宽、让单元格换行，尽量不横向滚动（浏览器表格的默认行为）；
 * - 各列都压到最小宽度仍放不下（列很多 / 存在超长不可断内容）→ 保持最小宽度，
 *   由外层 HorizontalScrollView 横向滚动承接。
 */
object TableColumnSizer {

    /** 单列最小宽度（dp）：空单元格、极短内容也要有可点面积和可见边界。 */
    const val MIN_COLUMN_WIDTH_DP = 56

    /**
     * 计算各列宽度（px）。
     *
     * @param naturalWidths 每行每列的**内容自然宽度**（px，已含单元格内边距）；
     *                      允许各行长度不一致，缺失的单元格按 0 处理。
     * @param availableWidth 可视区可用宽度（px）；≤0 表示未知，此时不压缩也不铺满。
     * @param minColumnWidth 单列最小宽度（px），压缩时的下限。
     * @return 列宽数组，长度 = 各行中的最大单元格数。
     *         可视宽度已知时：能放下则总和 == availableWidth（内容窄则铺满、内容宽则压缩换行）；
     *         放下则各列保持 minColumnWidth，总和 > availableWidth，交给横向滚动。
     */
    fun columnWidths(
        naturalWidths: List<List<Int>>,
        availableWidth: Int,
        minColumnWidth: Int,
    ): IntArray {
        val columnCount = naturalWidths.maxOfOrNull { it.size } ?: 0
        if (columnCount == 0) return IntArray(0)

        val floor = minColumnWidth.coerceAtLeast(1)

        // 每列宽度 = 该列所有行自然宽度的最大值，且不低于最小宽度。
        val widths = IntArray(columnCount) { column ->
            val natural = naturalWidths.maxOfOrNull { row -> row.getOrNull(column) ?: 0 } ?: 0
            natural.coerceAtLeast(floor)
        }

        if (availableWidth <= 0) return widths

        val total = widths.sum()
        return when {
            total < availableWidth -> expandTo(widths, availableWidth)
            total > availableWidth -> shrinkTo(widths, availableWidth, floor)
            else -> widths
        }
    }

    /** 内容比可视区窄：按自然宽度比例拉伸铺满，避免表格缩在左侧留一大片空白。 */
    private fun expandTo(widths: IntArray, availableWidth: Int): IntArray {
        val total = widths.sum()
        val extra = availableWidth - total
        var distributed = 0
        for (index in widths.indices) {
            val add = (extra.toLong() * widths[index] / total).toInt()
            widths[index] += add
            distributed += add
        }
        // 整除的余数补给最左侧几列，保证总和精确等于可用宽度。
        var remainder = extra - distributed
        var index = 0
        while (remainder > 0 && widths.isNotEmpty()) {
            widths[index % widths.size]++
            remainder--
            index++
        }
        return widths
    }

    /**
     * 内容比可视区宽：把超出最小宽度的部分按比例削掉，让单元格换行而不是横向滚动。
     * 若所有列都压到最小宽度仍放不下，则保持最小宽度（外层横向滚动承接）。
     */
    private fun shrinkTo(widths: IntArray, availableWidth: Int, floor: Int): IntArray {
        val shrinkable = widths.indices.filter { widths[it] > floor }
        val excessTotal = shrinkable.sumOf { widths[it] - floor }
        val need = widths.sum() - availableWidth
        if (excessTotal == 0 || need >= excessTotal) {
            // 压无可压：全部落到最小宽度，总和仍可能大于可用宽度 → 横向滚动。
            for (index in shrinkable) widths[index] = floor
            return widths
        }

        var applied = 0
        for (index in shrinkable) {
            val excess = widths[index] - floor
            val cut = (need.toLong() * excess / excessTotal).toInt()
            widths[index] -= cut
            applied += cut
        }
        // 整除余数从「可压缩量最大」的列依次扣除，保证总和精确等于可用宽度。
        var remainder = need - applied
        while (remainder > 0) {
            var target = -1
            var maxExcess = 0
            for (index in widths.indices) {
                val excess = widths[index] - floor
                if (excess > maxExcess) {
                    maxExcess = excess
                    target = index
                }
            }
            if (target < 0) break
            widths[target]--
            remainder--
        }
        return widths
    }
}
