package com.moread.app.theme

import android.content.Context
import android.content.res.Configuration
import com.moread.app.prefs.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 主题系统（SPEC §1.6）。
 *
 * 模式（日/夜/跟随系统）与排版参数统一持久化；跟随系统通过 Activity 的
 * `onConfigurationChanged` 回调同步 uiMode 变化，无需 recreate。
 */
object ThemeEngine {

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<ThemeListener>()
    private var _systemNight = false

    @Volatile
    var mode: ThemeMode = ThemeMode.FOLLOW_SYSTEM
        private set
    @Volatile
    var accentPreset: AccentPreset = AccentPreset.BLUE
        private set
    @Volatile
    var fontScaleIndex: Int = 2
        private set
    @Volatile
    var lineSpacingIndex: Int = 1
        private set
    @Volatile
    var readerFont: ReaderFont = ReaderFont.SANS
        private set

    val systemNight: Boolean get() = _systemNight
    val isNight: Boolean
        get() = when (mode) {
            ThemeMode.DAY -> false
            ThemeMode.NIGHT -> true
            ThemeMode.FOLLOW_SYSTEM -> _systemNight
        }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        Prefs.init(app)
        _systemNight = isSystemNight(app)
        mode = when (Prefs.themeMode()) {
            "day" -> ThemeMode.DAY
            "night" -> ThemeMode.NIGHT
            else -> ThemeMode.FOLLOW_SYSTEM
        }
        accentPreset = AccentPreset.entries.getOrElse(Prefs.accentIndex()) { AccentPreset.BLUE }
        fontScaleIndex = Prefs.fontScaleIndex()
        lineSpacingIndex = Prefs.lineSpacingIndex()
        readerFont = ReaderFont.entries.getOrElse(Prefs.fontFamilyIndex()) { ReaderFont.SANS }
    }

    fun palette(): ColorPalette {
        val accent = if (isNight) accentPreset.nightColor else accentPreset.dayColor
        return if (isNight) ColorPalette.night(accent) else ColorPalette.day(accent)
    }

    val fontSizeSp: Float get() = FONT_SIZES[fontScaleIndex]
    val lineSpacing: Float get() = LINE_SPACINGS[lineSpacingIndex]

    fun onSystemConfigurationChanged(newConfig: Configuration) {
        val night = isSystemNightConfig(newConfig)
        if (night != _systemNight) {
            val old = palette()
            _systemNight = night
            notifyChanged(old, animated = true)
        }
    }

    /** 阅读页日/夜一键切换：同时把模式从 FOLLOW_SYSTEM 转为手动。 */
    fun toggleDayNight() {
        val target = when {
            mode == ThemeMode.FOLLOW_SYSTEM -> if (isNight) ThemeMode.DAY else ThemeMode.NIGHT
            mode == ThemeMode.DAY -> ThemeMode.NIGHT
            else -> ThemeMode.DAY
        }
        setMode(target)
    }

    fun setMode(newMode: ThemeMode) {
        if (mode == newMode) return
        val old = palette()
        mode = newMode
        Prefs.setThemeMode(
            when (newMode) {
                ThemeMode.DAY -> "day"
                ThemeMode.NIGHT -> "night"
                ThemeMode.FOLLOW_SYSTEM -> "follow_system"
            },
        )
        notifyChanged(old, animated = true)
    }

    fun setFontScaleIndex(index: Int) {
        val next = index.coerceIn(FONT_SIZES.indices)
        if (next != fontScaleIndex) {
            val old = palette()
            fontScaleIndex = next
            Prefs.setFontScaleIndex(next)
            notifyChanged(old, animated = false)
        }
    }

    fun setLineSpacingIndex(index: Int) {
        val next = index.coerceIn(LINE_SPACINGS.indices)
        if (next != lineSpacingIndex) {
            val old = palette()
            lineSpacingIndex = next
            Prefs.setLineSpacingIndex(next)
            notifyChanged(old, animated = false)
        }
    }

    fun setReaderFont(font: ReaderFont) {
        if (font != readerFont) {
            val old = palette()
            readerFont = font
            Prefs.setFontFamilyIndex(font.ordinal)
            notifyChanged(old, animated = false)
        }
    }

    fun setAccent(index: Int) {
        val next = index.coerceIn(AccentPreset.entries.indices)
        if (next != accentPreset.ordinal) {
            val old = palette()
            accentPreset = AccentPreset.entries[next]
            Prefs.setAccentIndex(next)
            notifyChanged(old, animated = true)
        }
    }

    fun addListener(listener: ThemeListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: ThemeListener) {
        listeners.remove(listener)
    }

    private fun notifyChanged(oldPalette: ColorPalette, animated: Boolean) {
        val event = ThemeChangeEvent(oldPalette, palette(), animated)
        listeners.forEach { it.onThemeChanged(event) }
    }

    private fun isSystemNight(context: Context): Boolean = isSystemNightConfig(context.resources.configuration)

    private fun isSystemNightConfig(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    interface ThemeListener {
        fun onThemeChanged(event: ThemeChangeEvent)
    }

    data class ThemeChangeEvent(
        val oldPalette: ColorPalette,
        val newPalette: ColorPalette,
        val animated: Boolean,
    )

    val FONT_SIZES = floatArrayOf(14f, 15.5f, 17f, 19f, 21f)
    val LINE_SPACINGS = floatArrayOf(1.5f, 1.7f, 1.9f)
}
