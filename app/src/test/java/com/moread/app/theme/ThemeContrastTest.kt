package com.moread.app.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private fun hex(hex: String): Int = hex.removePrefix("#").toLong(16).toInt()

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = channel((rgb shr 16) and 0xFF)
        val g = channel((rgb shr 8) and 0xFF)
        val b = channel(rgb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun `body text contrast at least 7 and accents at least 4_5`() {
        val dayBg = hex("#FAFAF7")
        val nightBg = hex("#16181D")
        val dayText = hex("#2B2B2B")
        val nightText = hex("#D8D8D6")
        assertTrue("day body ${contrast(dayText, dayBg)}", contrast(dayText, dayBg) >= 7.0)
        assertTrue("night body ${contrast(nightText, nightBg)}", contrast(nightText, nightBg) >= 7.0)
        // PRD §6.3 + F-12 预设色板（本地 JVM 测试不依赖 android.graphics.Color）。
        val accents = listOf(
            "墨阅蓝" to ("#3A6EA5" to "#7FA8D9"),
            "松绿" to ("#2F7D46" to "#86D9A4"),
            "暮紫" to ("#6A4C93" to "#C3A6F5"),
            "暖橙" to ("#B45309" to "#F2B36D"),
            "青碧" to ("#00796B" to "#66D9CD"),
        )
        accents.forEach { (label, pair) ->
            assertTrue("$label day", contrast(hex(pair.first), dayBg) >= 4.5)
            assertTrue("$label night", contrast(hex(pair.second), nightBg) >= 4.5)
        }
    }
}
