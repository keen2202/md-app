package com.moread.app.reader.render.span

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import com.moread.app.reader.parser.ast.CodeInline
import com.moread.app.reader.parser.ast.EmphasisInline
import com.moread.app.reader.parser.ast.HardBreakInline
import com.moread.app.reader.parser.ast.HtmlInline
import com.moread.app.reader.parser.ast.ImageInline
import com.moread.app.reader.parser.ast.LinkInline
import com.moread.app.reader.parser.ast.MdInline
import com.moread.app.reader.parser.ast.SoftBreakInline
import com.moread.app.reader.parser.ast.StrikeInline
import com.moread.app.reader.parser.ast.StrongInline
import com.moread.app.reader.parser.ast.TextInline
import com.moread.app.theme.ColorPalette
import java.util.Collections

/**
 * 行内 Span 生成器（SPEC §1.3）。
 * Foreground/Background 颜色 Span 按色值缓存复用，ClickableSpan 因携带回调每次新建。
 */
object SpanFactory {

    private val foregroundPool: MutableMap<Int, ForegroundColorSpan> = Collections.synchronizedMap(HashMap())
    private val backgroundPool: MutableMap<Int, BackgroundColorSpan> = Collections.synchronizedMap(HashMap())
    private val bold = StyleSpan(Typeface.BOLD)
    private val italic = StyleSpan(Typeface.ITALIC)
    private val strike = StrikethroughSpan()

    fun interface LinkClickHandler {
        fun onLinkClick(url: String)
    }

    fun interface ImageClickHandler {
        fun onImageClick(url: String, alt: String)
    }

    fun build(
        nodes: List<MdInline>,
        palette: ColorPalette,
        linkHandler: LinkClickHandler? = null,
        imageHandler: ImageClickHandler? = null,
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        appendNodes(builder, nodes, palette, linkHandler, imageHandler)
        return builder
    }

    private fun appendNodes(
        builder: SpannableStringBuilder,
        nodes: List<MdInline>,
        palette: ColorPalette,
        linkHandler: LinkClickHandler?,
        imageHandler: ImageClickHandler?,
    ) {
        nodes.forEach { node ->
            when (node) {
                is TextInline -> builder.append(node.text)
                is StrongInline -> appendStyled(builder, node.children, palette, linkHandler, imageHandler, bold)
                is EmphasisInline -> appendStyled(builder, node.children, palette, linkHandler, imageHandler, italic)
                is StrikeInline -> appendStyled(builder, node.children, palette, linkHandler, imageHandler, strike)
                is CodeInline -> appendCode(builder, node, palette)
                is LinkInline -> appendLink(builder, node, palette, linkHandler)
                is ImageInline -> appendInlineImage(builder, node, palette, imageHandler)
                is SoftBreakInline, is HardBreakInline -> builder.append('\n')
                is HtmlInline -> builder.append(node.html) // 纯文本转义显示，TextView 不解析 HTML
            }
        }
    }

    private fun appendStyled(
        builder: SpannableStringBuilder,
        children: List<MdInline>,
        palette: ColorPalette,
        linkHandler: LinkClickHandler?,
        imageHandler: ImageClickHandler?,
        style: Any,
    ) {
        val start = builder.length
        appendNodes(builder, children, palette, linkHandler, imageHandler)
        if (builder.length > start) builder.setSpan(style, start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendCode(builder: SpannableStringBuilder, node: CodeInline, palette: ColorPalette) {
        val start = builder.length
        builder.append(node.code)
        if (node.code.isNotEmpty()) {
            builder.setSpan(background(palette.card), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(foreground(palette.codeText), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(TypefaceSpan("monospace"), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendLink(
        builder: SpannableStringBuilder,
        node: LinkInline,
        palette: ColorPalette,
        linkHandler: LinkClickHandler?,
    ) {
        val start = builder.length
        appendNodes(builder, node.children, palette, linkHandler, null)
        if (builder.length == start) builder.append(node.url)
        builder.setSpan(foreground(palette.accent), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    linkHandler?.onLinkClick(node.url)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = palette.accent
                    ds.isUnderlineText = true
                }
            },
            start,
            builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun appendInlineImage(
        builder: SpannableStringBuilder,
        node: ImageInline,
        palette: ColorPalette,
        imageHandler: ImageClickHandler?,
    ) {
        val start = builder.length
        val label = node.alt.ifEmpty { "图片" }
        builder.append(label)
        builder.setSpan(foreground(palette.accent), start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    imageHandler?.onImageClick(node.url, node.alt)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = palette.accent
                    ds.isUnderlineText = false
                }
            },
            start,
            builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    fun foreground(color: Int): ForegroundColorSpan =
        foregroundPool.getOrPut(color) { ForegroundColorSpan(color) }

    fun background(color: Int): BackgroundColorSpan =
        backgroundPool.getOrPut(color) { BackgroundColorSpan(color) }

    /** 清空颜色 Span 缓存（主题切换后非可视区惰性重绑时使用）。 */
    fun clearColorSpanCache() {
        foregroundPool.clear()
        backgroundPool.clear()
    }
}
