package com.moread.app.file

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DecodeResult(
    val text: String,
    val encoding: String,
    val uncertain: Boolean,
)

/**
 * 编码检测（SPEC §1.7、F-10/T12）。
 * 优先级：BOM（UTF-8/UTF-16 LE/BE）→ UTF-8 严格解码 → GBK/GB18030 启发式 → UTF-8 宽容兜底。
 */
object EncodingDetector {

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult("", "UTF-8", false)

        // 1. BOM 优先
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            return DecodeResult(text, "UTF-8-BOM", false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return DecodeResult(String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE")), "UTF-16BE", false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return DecodeResult(String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE")), "UTF-16LE", false)
        }

        // 2. UTF-8 严格解码
        try {
            val text = decodeStrict(bytes, StandardCharsets.UTF_8)
            return DecodeResult(text, "UTF-8", false)
        } catch (_: CharacterCodingException) {
            // fall through
        }

        // 3. GBK/GB18030 双字节分布启发式（中文文档零乱码）
        if (looksLikeGbk(bytes)) {
            return DecodeResult(String(bytes, Charset.forName("GB18030")), "GB18030", false)
        }

        // 4. 兜底：UTF-8 宽容模式 + 上层 Toast「编码可能异常」
        val text = String(bytes, StandardCharsets.UTF_8)
        return DecodeResult(text, "UTF-8-FALLBACK", true)
    }

    fun decodeStrict(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun looksLikeGbk(bytes: ByteArray): Boolean {
        var pairs = 0
        var invalid = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i++
                b in 0x81..0xFE && i + 1 < bytes.size -> {
                    val next = bytes[i + 1].toInt() and 0xFF
                    if (next in 0x40..0xFE && next != 0x7F) {
                        pairs++
                    } else {
                        invalid++
                    }
                    i += 2
                }
                else -> {
                    invalid++
                    i++
                }
            }
        }
        val nonAsciiPairs = pairs
        if (nonAsciiPairs == 0) return false
        val total = pairs + invalid
        return total > 0 && pairs.toFloat() / total >= 0.82f
    }
}
