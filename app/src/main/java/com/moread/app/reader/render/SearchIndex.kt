package com.moread.app.reader.render

import com.moread.app.reader.parser.ast.CodeBlock
import com.moread.app.reader.parser.ast.HeadingBlock
import com.moread.app.reader.parser.ast.ImageBlock
import com.moread.app.reader.parser.ast.InlineNodes
import com.moread.app.reader.parser.ast.MdBlock
import com.moread.app.reader.parser.ast.ParagraphBlock
import com.moread.app.reader.parser.ast.TableBlock
import java.util.Locale

data class SearchHit(
    val itemIndex: Int,
    val start: Int,
    val end: Int,
)

/**
 * 文内搜索索引（F-08/T15）。
 * 基于渲染 item 的纯文本拼接建立偏移区间；搜索词按字面匹配（大小写不敏感），
 * 正则元字符不会产生正则语义。
 */
class SearchIndex(private val model: RenderModel) {

    private val itemTexts: List<String> = model.items.map { item ->
        buildString {
            item.blocks.forEachIndexed { index, block ->
                if (index > 0) append(RenderItem.SEPARATOR)
                append(searchTextOf(block))
            }
        }
    }

    fun search(query: String): List<SearchHit> {
        if (query.isEmpty()) return emptyList()
        val lowerQuery = query.lowercase(Locale.ROOT)
        val hits = ArrayList<SearchHit>()
        itemTexts.forEachIndexed { itemIndex, text ->
            if (text.isEmpty() || text.length < lowerQuery.length) return@forEachIndexed
            val lowerText = text.lowercase(Locale.ROOT)
            var from = 0
            var guard = 0
            while (from <= text.length - lowerQuery.length) {
                if (guard++ > text.length + 8) break
                val found = lowerText.indexOf(lowerQuery, from)
                if (found < 0) break
                hits.add(SearchHit(itemIndex, found, found + lowerQuery.length))
                from = found + 1
            }
        }
        return hits
    }

    private fun searchTextOf(block: MdBlock): String = when (block) {
        is ParagraphBlock -> InlineNodes.plainText(block.inline)
        is HeadingBlock -> InlineNodes.plainText(block.inline)
        is CodeBlock -> block.code
        is TableBlock -> block.rawText
        is ImageBlock -> block.image.alt
        else -> block.rawText
    }
}
