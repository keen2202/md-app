package com.moread.app.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 统一封装（SPEC §1.1 唯一本地存储入口）。
 * 全部读写为同步小数据，冷启动 <1ms，不占启动预算。
 */
object Prefs {
    private const val FILE = "moread_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun themeMode(): String = sp.getString(KEY_THEME_MODE, "follow_system") ?: "follow_system"
    fun setThemeMode(mode: String) = sp.edit().putString(KEY_THEME_MODE, mode).apply()

    fun fontScaleIndex(): Int = sp.getInt(KEY_FONT_SCALE, 2)
    fun setFontScaleIndex(index: Int) = sp.edit().putInt(KEY_FONT_SCALE, index.coerceIn(0, 4)).apply()

    fun lineSpacingIndex(): Int = sp.getInt(KEY_LINE_SPACING, 1)
    fun setLineSpacingIndex(index: Int) = sp.edit().putInt(KEY_LINE_SPACING, index.coerceIn(0, 2)).apply()

    fun fontFamilyIndex(): Int = sp.getInt(KEY_FONT_FAMILY, 0)
    fun setFontFamilyIndex(index: Int) = sp.edit().putInt(KEY_FONT_FAMILY, index.coerceIn(0, 2)).apply()

    fun accentIndex(): Int = sp.getInt(KEY_ACCENT, 0)
    fun setAccentIndex(index: Int) = sp.edit().putInt(KEY_ACCENT, index.coerceIn(0, 4)).apply()

    fun immersiveReader(): Boolean = sp.getBoolean(KEY_IMMERSIVE, true)
    fun setImmersiveReader(immersive: Boolean) = sp.edit().putBoolean(KEY_IMMERSIVE, immersive).apply()

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_FONT_SCALE = "font_scale_index"
    private const val KEY_LINE_SPACING = "line_spacing_index"
    private const val KEY_FONT_FAMILY = "font_family_index"
    private const val KEY_ACCENT = "accent_index"
    private const val KEY_IMMERSIVE = "immersive_reader"
}
