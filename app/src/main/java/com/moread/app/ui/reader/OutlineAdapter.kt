package com.moread.app.ui.reader

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.reader.outline.OutlineItem
import com.moread.app.theme.ColorPalette
import com.moread.app.theme.ThemeEngine

data class OutlineRow(
    val item: OutlineItem,
    val renderItemIndex: Int,
)

/**
 * 大纲抽屉列表：H1 不缩进，每级缩进 16dp；当前章节高亮（SPEC §1.5）。
 */
class OutlineAdapter(
    private val onClick: (OutlineRow) -> Unit,
) : RecyclerView.Adapter<OutlineAdapter.Holder>() {

    private var rows: List<OutlineRow> = emptyList()
    private var palette: ColorPalette = ThemeEngine.palette()
    private var selected: Int = -1

    fun submit(newRows: List<OutlineRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun setPalette(newPalette: ColorPalette) {
        palette = newPalette
        notifyDataSetChanged()
    }

    fun currentRows(): List<OutlineRow> = rows

    fun setSelected(index: Int) {
        if (selected == index) return
        val old = selected
        selected = index
        if (old in rows.indices) notifyItemChanged(old)
        if (selected in rows.indices) notifyItemChanged(selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = TextView(parent.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 15f
            maxLines = 2
            setPadding(dp(parent.context, 20), dp(parent.context, 10), dp(parent.context, 20), dp(parent.context, 10))
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        return Holder(text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.text.text = row.item.title
        holder.text.setTextColor(if (position == selected) palette.accent else palette.textPrimary)
        holder.text.typeface = if (position == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.text.background = android.graphics.drawable.ColorDrawable(
            if (position == selected) (palette.accent and 0x00FFFFFF) or (0x26 shl 24) else android.graphics.Color.TRANSPARENT,
        )
        val extraIndent = (row.item.level - 1).coerceAtLeast(0) * dp(holder.text.context, 16)
        holder.text.setPadding(dp(holder.text.context, 20) + extraIndent, dp(holder.text.context, 10), dp(holder.text.context, 20), dp(holder.text.context, 10))
        holder.text.setOnClickListener { onClick(row) }
    }

    override fun getItemCount(): Int = rows.size

    private fun dp(context: android.content.Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
