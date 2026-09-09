package com.moread.app.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.R
import com.moread.app.file.ImageResolver
import com.moread.app.log.AppLog
import com.moread.app.reader.highlight.Highlighter
import com.moread.app.reader.highlight.TokenType
import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.ColumnAlign
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.ImageBlock
import com.moread.app.reader.parser.ast.InlineNodes
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TableBlock
import com.moread.app.reader.parser.ast.TableCell
import com.moread.app.reader.render.ImageViewerDialog
import com.moread.app.reader.render.ItemKind
import com.moread.app.reader.render.RenderItem
import com.moread.app.reader.render.SearchHit
import com.moread.app.reader.render.TableColumnSizer
import com.moread.app.reader.render.span.SpanFactory
import com.moread.app.theme.ThemeEngine

/**
 * AST → RecyclerView 渲染适配器（SPEC §1.3）。
 * 可视区懒高亮代码；图片按屏宽采样解码；搜索高亮与大纲闪烁在此落地。
 */
class ReaderAdapter(
    private val context: Context,
    private val documentUri: Uri,
    private val linkHandler: SpanFactory.LinkClickHandler,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val highlighter = Highlighter()
    private var items: List<RenderItem> = emptyList()
    private var palette = ThemeEngine.palette()
    private var searchHitsByItem: Map<Int, List<SearchHit>> = emptyMap()
    private var currentSearchHit: SearchHit? = null
    private var flashPosition: Int = -1

    init {
        setHasStableIds(false)
    }

    fun submitItems(newItems: List<RenderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun appendItems(newItems: List<RenderItem>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items = items + newItems
        notifyItemRangeInserted(start, newItems.size)
    }

    fun setPalette(newPalette: com.moread.app.theme.ColorPalette) {
        palette = newPalette
        notifyItemRangeChanged(0, items.size.coerceAtLeast(1), PAYLOAD_PALETTE)
    }

    fun setSearchResults(hits: List<SearchHit>, current: SearchHit?) {
        searchHitsByItem = hits.groupBy { it.itemIndex }
        currentSearchHit = current
        notifyDataSetChanged()
    }

    fun clearSearch() {
        searchHitsByItem = emptyMap()
        currentSearchHit = null
        notifyDataSetChanged()
    }

    fun flashPosition(position: Int) {
        flashPosition = position
        notifyItemChanged(position, PAYLOAD_FLASH)
    }

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        val quote = item.quoteDepth > 0
        return when (item.kind) {
            ItemKind.HEADING -> if (quote) TYPE_HEADING_QUOTE else TYPE_HEADING
            ItemKind.PARAGRAPH -> if (quote) TYPE_PARAGRAPH_QUOTE else TYPE_PARAGRAPH
            ItemKind.CODE -> TYPE_CODE
            ItemKind.TABLE -> TYPE_TABLE
            ItemKind.IMAGE -> TYPE_IMAGE
            ItemKind.HR -> TYPE_HR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADING -> TextHolder(
                inflater.inflate(R.layout.item_md_text, parent, false),
                R.id.md_text,
            )
            TYPE_HEADING_QUOTE -> QuoteHolder(inflater.inflate(R.layout.item_md_quote, parent, false))
            TYPE_PARAGRAPH -> TextHolder(
                inflater.inflate(R.layout.item_md_text, parent, false),
                R.id.md_text,
            )
            TYPE_PARAGRAPH_QUOTE -> QuoteHolder(inflater.inflate(R.layout.item_md_quote, parent, false))
            TYPE_CODE -> CodeHolder(inflater.inflate(R.layout.item_md_code, parent, false))
            TYPE_TABLE -> TableHolder(inflater.inflate(R.layout.item_md_table, parent, false))
            TYPE_IMAGE -> ImageHolder(inflater.inflate(R.layout.item_md_image, parent, false))
            else -> HrHolder(inflater.inflate(R.layout.item_md_hr, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bind(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        bind(holder, position, payloads)
    }

    private fun bind(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        val item = items[position]
        try {
            when (holder) {
                is TextHolder -> bindText(holder, item, position, heading = false)
                is QuoteHolder -> bindText(holder, item, position, heading = false)
                is CodeHolder -> bindCode(holder, item, position)
                is TableHolder -> bindTable(holder, item, position)
                is ImageHolder -> bindImage(holder, item, position)
                is HrHolder -> holder.line.setBackgroundColor(palette.divider)
            }
        } catch (t: Throwable) {
            AppLog.e(
                "ReaderAdapter",
                "bind failed position=$position, kind=${item.kind}, blocks=${item.blocks.size}, " +
                    "indent=${item.indent}, quoteDepth=${item.quoteDepth}",
                t,
            )
            throw t
        }
    }

    // ------------------------------------------------------------------ 文本

    private fun bindText(
        holder: TextHolder,
        item: RenderItem,
        position: Int,
        heading: Boolean,
    ) {
        val textView = holder.textView
        val marker = if (item.marker.isNullOrEmpty()) null else item.marker
        val builder = SpannableStringBuilder()
        item.blocks.forEachIndexed { index, block ->
            if (index > 0) builder.append(RenderItem.SEPARATOR)
            if (index == 0 && marker != null) {
                val start = builder.length
                builder.append(marker)
                builder.setSpan(SpanFactory.foreground(palette.textSecondary), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when (block) {
                is ParagraphBlock -> builder.append(SpanFactory.build(block.inline, palette, linkHandler, imageHandler = { url, alt -> onImageClick(url, alt) }))
                is HeadingBlock -> builder.append(SpanFactory.build(block.inline, palette, linkHandler, imageHandler = { url, alt -> onImageClick(url, alt) }))
                else -> builder.append(block.rawText)
            }
        }
        applySearchHighlights(builder, position, marker?.length ?: 0)

        textView.text = builder
        textView.setTextColor(palette.textPrimary)
        textView.setLineSpacing(0f, ThemeEngine.lineSpacing)
        val baseSize = if (item.kind == ItemKind.HEADING) headingSize(item.primaryBlock as? HeadingBlock) else ThemeEngine.fontSizeSp
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize)
        textView.typeface = when (ThemeEngine.readerFont) {
            com.moread.app.theme.ReaderFont.SANS -> Typeface.SANS_SERIF
            com.moread.app.theme.ReaderFont.SERIF -> Typeface.SERIF
            com.moread.app.theme.ReaderFont.MONO -> Typeface.MONOSPACE
        }
        if (item.kind == ItemKind.HEADING) textView.setTypeface(Typeface.create(textView.typeface, Typeface.BOLD))
        val extraIndent = item.indent * dp(16)
        textView.setPadding(dp(10) + extraIndent, textView.paddingTop, dp(10), textView.paddingBottom)

        if (position == flashPosition && item.kind == ItemKind.HEADING) {
            flashHeading(textView)
            flashPosition = -1
        }
    }

    private fun headingSize(block: HeadingBlock?): Float = when (block?.level ?: 1) {
        1 -> 24f
        2 -> 21f
        3 -> 19f
        4 -> 17f
        5 -> 16f
        else -> 15f
    }

    private fun applySearchHighlights(builder: SpannableStringBuilder, position: Int, markerShift: Int) {
        val hits = searchHitsByItem[position] ?: return
        hits.forEach { hit ->
            val start = (hit.start + markerShift).coerceIn(0, builder.length)
            val end = (hit.end + markerShift).coerceIn(start, builder.length)
            if (start < end) {
                val current = currentSearchHit?.let { it.itemIndex == hit.itemIndex && it.start == hit.start && it.end == hit.end } == true
                val alpha = if (current) 0x66 else 0x30
                val color = ColorUtils.setAlphaComponent(palette.accent, alpha)
                builder.setSpan(SpanFactory.background(color), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun flashHeading(textView: TextView) {
        val startColor = ColorUtils.setAlphaComponent(palette.accent, 130)
        val anim = android.animation.ValueAnimator.ofArgb(startColor, Color.TRANSPARENT)
        anim.duration = 800
        anim.addUpdateListener { textView.setBackgroundColor(it.animatedValue as Int) }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                textView.setBackgroundColor(Color.TRANSPARENT)
            }
        })
        anim.start()
    }

    // ------------------------------------------------------------------ 代码

    private fun bindCode(holder: CodeHolder, item: RenderItem, position: Int) {
        val block = item.primaryBlock as? CodeBlock ?: return
        val code = block.code
        holder.textView.textSize = 14f
        holder.textView.typeface = Typeface.MONOSPACE
        holder.textView.setLineSpacing(0f, 1.5f)
        val drawable = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.card)
        }
        holder.textView.background = drawable
        holder.textView.setTextColor(palette.codePlain)

        val builder = SpannableStringBuilder(code)
        val tokens = highlighter.highlight(code, block.language)
        tokens.forEach { token ->
            val color = when (token.type) {
                TokenType.KEYWORD -> palette.codeKeyword
                TokenType.STRING -> palette.codeString
                TokenType.COMMENT -> palette.codeComment
                TokenType.NUMBER -> palette.codeNumber
                TokenType.TYPE -> palette.codeType
                TokenType.FUNCTION -> palette.codeFunction
                TokenType.PLAIN -> palette.codePlain
            }
            if (token.start < token.end && token.end <= builder.length) {
                builder.setSpan(SpanFactory.foreground(color), token.start, token.end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        applySearchHighlights(builder, position, 0)
        holder.textView.text = builder

        val extraIndent = item.indent * dp(16)
        (holder.textView.parent as? View)?.setPadding(extraIndent, 0, 0, 0)
    }

    // ------------------------------------------------------------------ 表格

    /**
     * 表格渲染（SPEC §1.4：块内横向滚动、列宽按内容自适应）。
     *
     * 对齐要点（历史 bug：表头与表体、单元格之间列线不齐）：
     * 1. 先量出每个单元格的**内容自然宽度**，由 [TableColumnSizer] 按列取最大值，
     *    得到一组**所有行共用**的列宽 → 同一列的表头与表体必然对齐；
     * 2. 单元格宽度固定为列宽（px），不再用 `width=0 + weight=1`：权重方案下每行按
     *    「行宽 − 本行内容宽度」分配剩余空间，各行内容宽度不同 → 同一列在不同行宽度不同，
     *    这正是表头与表体、单元格之间列线不齐的根因；
     * 3. 行宽 = 各列宽之和，同一行内用 minHeight 取齐 → 长短单元格等高，边框不错位；
     * 4. 列宽以最小宽度为下限，内容宽时压缩换行、内容窄时铺满；只有各列都压到下限
     *    仍放不下时才横向滚动（列多或超长不可断内容）。
     */
    private fun bindTable(holder: TableHolder, item: RenderItem, position: Int) {
        val block = item.primaryBlock as? TableBlock ?: return
        val container = holder.container
        container.removeAllViews()
        val hasSearch = searchHitsByItem.containsKey(position)
        container.setBackgroundColor(if (hasSearch) ColorUtils.setAlphaComponent(palette.accent, 48) else Color.TRANSPARENT)
        val extraIndent = item.indent * dp(16)
        (holder.itemView as? HorizontalScrollView)?.setPadding(extraIndent, 0, 0, 0)

        val rows: List<Pair<List<TableCell>, Boolean>> =
            listOf(block.headers to true) + block.rows.map { it to false }
        val columnCount = rows.maxOfOrNull { it.first.size } ?: 0
        if (columnCount == 0) return

        // 1) 建视图，并量出每个单元格在单行排版下的内容自然宽度（含内边距）。
        val cellViews: List<List<TextView>> = rows.map { (cells, header) ->
            cells.map { cell -> createCellView(cell, header) }
        }
        val naturalWidths: List<List<Int>> = cellViews.map { row ->
            row.map { cell -> cell.naturalWidth() }
        }

        // 2) 一组列宽供所有行共用 → 表头与表体、行与行之间列线对齐。
        val columnWidths = TableColumnSizer.columnWidths(
            naturalWidths = naturalWidths,
            availableWidth = tableAvailableWidth(holder, extraIndent),
            minColumnWidth = dp(TableColumnSizer.MIN_COLUMN_WIDTH_DP),
        )
        val tableWidth = columnWidths.sum()

        // 3) 按列宽铺行：单元格宽度固定，行内等高。
        cellViews.forEach { cells ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            var rowHeight = 0
            cells.forEachIndexed { column, cell ->
                val width = columnWidths.getOrElse(column) { columnWidths.last() }
                rowHeight = maxOf(rowHeight, cell.heightAtWidth(width))
                row.addView(cell, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            cells.forEach { it.minHeight = rowHeight }
            container.addView(row, LinearLayout.LayoutParams(tableWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 表格单元格：内边距、字号、对齐、表头加粗与网格边框统一在此设置。 */
    private fun createCellView(cell: TableCell, header: Boolean): TextView {
        val view = TextView(context)
        view.setPadding(dp(10), dp(8), dp(10), dp(8))
        view.setTextColor(palette.textPrimary)
        view.typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.textSize = 14f
        view.text = SpanFactory.build(
            cell.inline,
            palette,
            linkHandler,
            imageHandler = { url, alt -> onImageClick(url, alt) },
        )
        view.gravity = when (cell.align) {
            ColumnAlign.CENTER -> Gravity.CENTER
            ColumnAlign.RIGHT -> Gravity.END
            else -> Gravity.START
        }
        // 表头铺底色、表体透明，配合 1px 分隔线：列边界可见，列宽是否正确一眼可辨。
        view.background = GradientDrawable().apply {
            setColor(if (header) ColorUtils.setAlphaComponent(palette.card, HEADER_FILL_ALPHA) else Color.TRANSPARENT)
            setStroke(maxOf(dp(1), 1), palette.divider)
        }
        return view
    }

    /** 单元格在单行排版下的内容自然宽度（px）。 */
    private fun TextView.naturalWidth(): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return measuredWidth
    }

    /** 单元格在指定列宽下换行后的高度（px），用于行内取齐。 */
    private fun TextView.heightAtWidth(width: Int): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return measuredHeight
    }

    /**
     * 表格可用宽度（px）：优先用 RecyclerView 的真实内容宽度（分屏/平板同样正确），
     * 首帧布局未完成时退回屏幕宽度估算。
     * 需扣除：RecyclerView 左右内边距 + 表格外层 HorizontalScrollView 的 10dp 外边距 + 列表缩进。
     */
    private fun tableAvailableWidth(holder: TableHolder, extraIndent: Int): Int {
        val parent = holder.itemView.parent as? View
        val parentPadding = if (parent == null) 0 else parent.paddingLeft + parent.paddingRight
        val measured = if (parent != null && parent.width > 0) {
            parent.width - parentPadding
        } else {
            context.resources.displayMetrics.widthPixels - parentPadding
        }
        return (measured - dp(TABLE_OUTER_MARGIN_DP) - extraIndent).coerceAtLeast(dp(96))
    }

    // ------------------------------------------------------------------ 图片

    private fun bindImage(holder: ImageHolder, item: RenderItem, position: Int) {
        val image = when (val block = item.primaryBlock) {
            is ImageBlock -> block.image
            is ParagraphBlock -> block.soleImage ?: return
            else -> return
        }
        holder.imageView.setImageDrawable(null)
        holder.placeholder.visibility = View.VISIBLE
        holder.placeholder.text = context.getString(R.string.doc_loading)
        val resolved = ImageResolver.resolve(context, documentUri, image.url)
        if (resolved == null) {
            holder.placeholder.text = context.getString(R.string.image_missing)
            holder.imageView.setOnClickListener(null)
            return
        }
        val tag = "img-${position}-${image.url.hashCode()}"
        holder.imageView.tag = tag
        holder.imageView.setOnClickListener { ImageViewerDialog.show(context, resolved) }
        holder.placeholder.text = context.getString(R.string.doc_loading)

        val screenWidth = context.resources.displayMetrics.widthPixels
        Thread {
            val bitmap = decodeSampled(resolved, maxOf(screenWidth, 800), 2048)
            holder.itemView.post {
                if (holder.imageView.tag == tag) {
                    if (bitmap != null) {
                        holder.imageView.setImageBitmap(bitmap)
                        holder.placeholder.visibility = View.GONE
                    } else {
                        holder.placeholder.text = context.getString(R.string.image_load_failed)
                    }
                } else {
                    bitmap?.recycle()
                }
            }
        }.start()
    }

    private fun decodeSampled(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun onImageClick(url: String, alt: String) {
        val resolved = ImageResolver.resolve(context, documentUri, url)
        if (resolved != null) ImageViewerDialog.show(context, resolved)
    }

    // ------------------------------------------------------------------ ViewHolder

    /**
     * 文本类 ViewHolder 基类。
     *
     * [textViewId] 必须由调用方传入**自身布局**里的 TextView id：
     * 属性初始化发生在父类构造器内，若在基类里写死 `R.id.md_text`，
     * 引用块布局（item_md_quote.xml 无 md_text）会在此处 findViewById 得到 null，
     * Kotlin 平台类型空检查随即抛 NPE；而该异常发生在 onCreateViewHolder
     * （不受 [bind] 的 try/catch 保护），会直接让阅读页闪退。
     */
    open inner class TextHolder(
        view: View,
        @IdRes textViewId: Int,
    ) : RecyclerView.ViewHolder(view) {
        open val textView: TextView = requireNotNull(view.findViewById<TextView>(textViewId)) {
            "ViewHolder 布局缺少必需控件：id=0x${Integer.toHexString(textViewId)}"
        }
    }

    inner class QuoteHolder(view: View) : TextHolder(view, R.id.md_quote_text) {
        val bar: View = view.findViewById(R.id.md_quote_bar)
    }

    inner class CodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.md_code_text)
    }

    inner class TableHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.md_table_container)
    }

    inner class ImageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.md_image)
        val placeholder: TextView = view.findViewById(R.id.md_image_placeholder)
    }

    inner class HrHolder(view: View) : RecyclerView.ViewHolder(view) {
        val line: View = view
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun getItemCount(): Int = items.size

    companion object {
        private const val TYPE_HEADING = 0
        private const val TYPE_HEADING_QUOTE = 1
        private const val TYPE_PARAGRAPH = 2
        private const val TYPE_PARAGRAPH_QUOTE = 3
        private const val TYPE_CODE = 4
        private const val TYPE_TABLE = 5
        private const val TYPE_IMAGE = 6
        private const val TYPE_HR = 7
        private const val PAYLOAD_PALETTE = "palette"
        private const val PAYLOAD_FLASH = "flash"

        /** 表头底色不透明度（0–255）：留一点透明度，文内搜索高亮能透出来。 */
        private const val HEADER_FILL_ALPHA = 0xE6

        /** item_md_table.xml 中 HorizontalScrollView 的左右外边距合计（10dp × 2）。 */
        private const val TABLE_OUTER_MARGIN_DP = 20
    }
}
