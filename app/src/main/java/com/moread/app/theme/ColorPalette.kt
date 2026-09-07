package com.moread.app.theme

import android.graphics.Color

/** 主题模式（PRD F-04）。 */
enum class ThemeMode { DAY, NIGHT, FOLLOW_SYSTEM }

/** 阅读字体（PRD F-13，使用系统字体族，不内置字体文件）。 */
enum class ReaderFont(val label: String, val family: String) {
    SANS("系统默认", "sans-serif"),
    SERIF("衬线", "serif"),
    MONO("等宽", "monospace"),
}

/**
 * 强调色预设（PRD F-12，3–5 色）。
 * 每组日/夜两色均已按 WCAG AA 对比度 ≥4.5:1 选取。
 */
enum class AccentPreset(
    val label: String,
    val dayColor: Int,
    val nightColor: Int,
) {
    BLUE("墨阅蓝", Color.parseColor("#3A6EA5"), Color.parseColor("#7FA8D9")),
    GREEN("松绿", Color.parseColor("#2F7D46"), Color.parseColor("#86D9A4")),
    PURPLE("暮紫", Color.parseColor("#6A4C93"), Color.parseColor("#C3A6F5")),
    ORANGE("暖橙", Color.parseColor("#B45309"), Color.parseColor("#F2B36D")),
    TEAL("青碧", Color.parseColor("#00796B"), Color.parseColor("#66D9CD")),
}

/**
 * 主题色板（PRD §6.3 全部色值 + 代码高亮 One Light/One Dark）。
 */
data class ColorPalette(
    val background: Int,
    val card: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val codeText: Int,
    val codeKeyword: Int,
    val codeString: Int,
    val codeComment: Int,
    val codeNumber: Int,
    val codeType: Int,
    val codeFunction: Int,
    val codePlain: Int,
    val divider: Int,
) {
    companion object {
        fun day(accent: Int): ColorPalette = ColorPalette(
            background = Color.parseColor("#FAFAF7"),
            card = Color.parseColor("#F1F0EB"),
            textPrimary = Color.parseColor("#2B2B2B"),
            textSecondary = Color.parseColor("#6E6E6E"),
            accent = accent,
            codeText = Color.parseColor("#383A42"),
            codeKeyword = Color.parseColor("#A626A4"),
            codeString = Color.parseColor("#50A14F"),
            codeComment = Color.parseColor("#A0A1A7"),
            codeNumber = Color.parseColor("#986801"),
            codeType = Color.parseColor("#C18401"),
            codeFunction = Color.parseColor("#4078F2"),
            codePlain = Color.parseColor("#383A42"),
            divider = Color.parseColor("#E2E1DC"),
        )

        fun night(accent: Int): ColorPalette = ColorPalette(
            background = Color.parseColor("#16181D"),
            card = Color.parseColor("#1F2229"),
            textPrimary = Color.parseColor("#D8D8D6"),
            textSecondary = Color.parseColor("#8B8D94"),
            accent = accent,
            codeText = Color.parseColor("#D6DAE2"),
            codeKeyword = Color.parseColor("#C678DD"),
            codeString = Color.parseColor("#98C379"),
            codeComment = Color.parseColor("#7F848E"),
            codeNumber = Color.parseColor("#D19A66"),
            codeType = Color.parseColor("#E5C07B"),
            codeFunction = Color.parseColor("#61AFEF"),
            codePlain = Color.parseColor("#D6DAE2"),
            divider = Color.parseColor("#2C2F37"),
        )
    }
}
