package com.moread.app.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.moread.app.R
import com.moread.app.file.DraftStore
import com.moread.app.file.RecentStore
import com.moread.app.theme.AccentPreset
import com.moread.app.theme.ReaderFont
import com.moread.app.theme.ThemeApplier
import com.moread.app.theme.ThemeEngine
import com.moread.app.theme.ThemeMode

/**
 * 设置页（PRD §5.1）。
 * v1.0 条目 + T13 字号/行距 + T18 强调色 + T19 字体全部启用。
 */
class SettingsFragment : Fragment() {

    private lateinit var content: LinearLayout
    private val themeButtons = ArrayList<RadioButton>()
    private val chipGroups = HashMap<String, MutableList<TextView>>()
    private val accentViews = ArrayList<TextView>()

    private val themeListener = object : ThemeEngine.ThemeListener {
        override fun onThemeChanged(event: ThemeEngine.ThemeChangeEvent) {
            val view = view ?: return
            if (!isAdded) return
            if (event.animated) {
                ThemeApplier.animate(view, event.oldPalette, event.newPalette) {
                    if (isAdded) refreshAll()
                }
            } else {
                refreshAll()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(ThemeEngine.palette().background)
            isFillViewport = true
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildContent()
        refreshAll()
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeEngine.addListener(themeListener)
    }

    override fun onDestroyView() {
        ThemeEngine.removeListener(themeListener)
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildContent() {
        // 顶部返回（设置页由 MainActivity 管理，不单独设置 ActionBar）
        content.addView(TextView(requireContext()).apply {
            text = "← ${getString(R.string.settings)}"
            tag = "info"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(16))
            setOnClickListener { (activity as? com.moread.app.MainActivity)?.backToHome() }
        })

        // 主题模式
        content.addView(sectionTitle(getString(R.string.theme)))
        val radioGroup = RadioGroup(requireContext()).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            ThemeMode.DAY to R.string.theme_day,
            ThemeMode.NIGHT to R.string.theme_night,
            ThemeMode.FOLLOW_SYSTEM to R.string.theme_follow_system,
        ).forEach { (mode, labelRes) ->
            val button = RadioButton(requireContext()).apply {
                text = getString(labelRes)
                id = View.generateViewId()
                setPadding(dp(8), dp(4), dp(8), dp(4))
                tag = mode
            }
            themeButtons.add(button)
            radioGroup.addView(button)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = radioGroup.findViewById<View>(checkedId)?.tag as? ThemeMode ?: return@setOnCheckedChangeListener
            ThemeEngine.setMode(mode)
        }
        content.addView(radioGroup)

        // 阅读排版
        content.addView(sectionTitle(getString(R.string.typography)))
        val fontSizes = ThemeEngine.FONT_SIZES.toList()
        addChipRow(
            key = "fontSize",
            label = getString(R.string.font_size),
            options = fontSizes.map { "${it}sp" },
            selected = ThemeEngine.fontScaleIndex,
        ) { ThemeEngine.setFontScaleIndex(it) }

        addChipRow(
            key = "lineSpacing",
            label = getString(R.string.line_spacing),
            options = ThemeEngine.LINE_SPACINGS.map { "${it}x" },
            selected = ThemeEngine.lineSpacingIndex,
        ) { ThemeEngine.setLineSpacingIndex(it) }

        addChipRow(
            key = "readerFont",
            label = getString(R.string.reading_font),
            options = ReaderFont.entries.map { getString(fontLabel(it)) },
            selected = ThemeEngine.readerFont.ordinal,
        ) { ThemeEngine.setReaderFont(ReaderFont.entries[it]) }

        // 强调色
        content.addView(sectionTitle(getString(R.string.accent_color)))
        val accentRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        AccentPreset.entries.forEachIndexed { index, preset ->
            val circle = TextView(requireContext()).apply {
                text = "●"
                tag = "accent"
                textSize = 26f
                setTextColor(if (ThemeEngine.isNight) preset.nightColor else preset.dayColor)
                gravity = Gravity.CENTER
                width = dp(48)
                height = dp(48)
                contentDescription = preset.label
            }
            circle.setOnClickListener {
                ThemeEngine.setAccent(index)
                refreshAccentSelection()
            }
            accentViews.add(circle)
            accentRow.addView(circle)
        }
        content.addView(accentRow)

        // 代码高亮主题说明
        content.addView(sectionTitle(getString(R.string.code_highlight_theme)))
        content.addView(bodyText(getString(R.string.code_highlight_follow)))

        // 最近记录
        content.addView(sectionTitle(getString(R.string.recent_files)))
        content.addView(Button(requireContext()).apply {
            text = getString(R.string.clear_recent)
            setOnClickListener { confirmClearRecent() }
            tag = "clearRecent"
        })

        // 编辑
        content.addView(sectionTitle(getString(R.string.editing)))
        content.addView(Button(requireContext()).apply {
            text = getString(R.string.clear_drafts)
            setOnClickListener { confirmClearDrafts() }
            tag = "clearDrafts"
        })

        // 关于
        content.addView(sectionTitle(getString(R.string.about)))
        content.addView(infoRow(getString(R.string.version), versionName()))
        content.addView(bodyText(getString(R.string.license_text)))
        content.addView(sectionTitle(getString(R.string.privacy_promise)))
        content.addView(bodyText(getString(R.string.privacy_text)))
    }

    private fun addChipRow(
        key: String,
        label: String,
        options: List<String>,
        selected: Int,
        onClick: (Int) -> Unit,
    ) {
        content.addView(labelText("$label：${options.getOrElse(selected) { "" }}").also { it.tag = "label:$key" })
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val chips = ArrayList<TextView>()
        options.forEachIndexed { index, option ->
            val chip = TextView(requireContext()).apply {
                text = option
                tag = "chip:$key"
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    onClick(index)
                    refreshChipGroup(key)
                    refreshLabels()
                }
            }
            chips.add(chip)
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                },
            )
        }
        chipGroups[key] = chips
        content.addView(row)
    }

    private fun confirmClearDrafts() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_drafts)
            .setMessage(R.string.clear_drafts_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                DraftStore.clearAll(requireContext())
                Toast.makeText(requireContext(), R.string.drafts_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearRecent() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_recent)
            .setMessage(R.string.clear_recent_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                RecentStore.clear(requireContext())
                Toast.makeText(requireContext(), R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 刷新

    private fun refreshAll() {
        val palette = ThemeEngine.palette()
        ThemeApplier.applyWindow(requireActivity(), palette)
        view?.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        themeButtons.forEach { button ->
            button.setTextColor(palette.textPrimary)
            button.buttonTintList = ColorStateList.valueOf(palette.accent)
            button.isChecked = ThemeEngine.mode == button.tag
        }
        refreshChipGroup("fontSize")
        refreshChipGroup("lineSpacing")
        refreshChipGroup("readerFont")
        refreshAccentSelection()
        refreshLabels()
        colorTree(content, palette)
    }

    private fun refreshChipGroup(key: String) {
        val selected = when (key) {
            "fontSize" -> ThemeEngine.fontScaleIndex
            "lineSpacing" -> ThemeEngine.lineSpacingIndex
            "readerFont" -> ThemeEngine.readerFont.ordinal
            else -> -1
        }
        val palette = ThemeEngine.palette()
        chipGroups[key]?.forEachIndexed { index, chip ->
            val selectedNow = index == selected
            chip.setTextColor(if (selectedNow) palette.accent else palette.textPrimary)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(palette.card)
                setStroke(if (selectedNow) dp(2) else dp(1), if (selectedNow) palette.accent else palette.divider)
            }
        }
    }

    private fun refreshAccentSelection() {
        val palette = ThemeEngine.palette()
        accentViews.forEachIndexed { index, circle ->
            val selected = index == ThemeEngine.accentPreset.ordinal
            circle.setTextColor(
                if (ThemeEngine.isNight) AccentPreset.entries[index].nightColor
                else AccentPreset.entries[index].dayColor,
            )
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.card)
                setStroke(if (selected) dp(2) else dp(0), palette.accent)
            }
        }
    }

    private fun refreshLabels() {
        content.findViewsByTag<TextView>("label:fontSize") { it.text = "${getString(R.string.font_size)}：${ThemeEngine.fontSizeSp}sp" }
        content.findViewsByTag<TextView>("label:lineSpacing") { it.text = "${getString(R.string.line_spacing)}：${ThemeEngine.lineSpacing}x" }
        content.findViewsByTag<TextView>("label:readerFont") { it.text = "${getString(R.string.reading_font)}：${getString(fontLabel(ThemeEngine.readerFont))}" }
    }

    private fun <T : View> LinearLayout.findViewsByTag(tag: String, block: (TextView) -> Unit) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is TextView && child.tag == tag) block(child)
        }
    }

    private fun colorTree(view: View, palette: com.moread.app.theme.ColorPalette) {
        when (view) {
            is Button -> {
                view.backgroundTintList = ColorStateList.valueOf(palette.accent)
                view.setTextColor(Color.WHITE)
            }
            is TextView -> {
                when (view.tag) {
                    "section" -> view.setTextColor(palette.accent)
                    "body" -> view.setTextColor(palette.textSecondary)
                    "info", null -> view.setTextColor(palette.textPrimary)
                    "accent" -> {
                        val index = accentViews.indexOf(view)
                        if (index >= 0) {
                            view.setTextColor(
                                if (ThemeEngine.isNight) AccentPreset.entries[index].nightColor
                                else AccentPreset.entries[index].dayColor,
                            )
                        }
                    }
                    // label:* / chip:* 由 refreshLabels/refreshChipGroup 管理；RadioButton 保留 mode 标签。
                    else -> Unit
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) colorTree(view.getChildAt(i), palette)
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        tag = "section"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(8))
        setTextColor(ThemeEngine.palette().accent)
    }

    private fun labelText(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        tag = "info"
        textSize = 14f
        setPadding(0, dp(4), 0, dp(8))
        setTextColor(ThemeEngine.palette().textPrimary)
    }

    private fun bodyText(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        tag = "body"
        textSize = 14f
        setLineSpacing(0f, 1.55f)
        setPadding(0, dp(4), 0, dp(8))
        setTextColor(ThemeEngine.palette().textSecondary)
    }

    private fun infoRow(label: String, value: String): TextView = TextView(requireContext()).apply {
        text = "$label：$value"
        tag = "info"
        textSize = 14f
        setPadding(0, dp(4), 0, dp(8))
        setTextColor(ThemeEngine.palette().textPrimary)
    }

    private fun versionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    private fun fontLabel(font: ReaderFont): Int = when (font) {
        ReaderFont.SANS -> R.string.font_sans
        ReaderFont.SERIF -> R.string.font_serif
        ReaderFont.MONO -> R.string.font_mono
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
