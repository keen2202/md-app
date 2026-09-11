package com.moread.app.file

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 换行风格；编辑会话内部统一使用 LF，保存时按原文恢复。 */
enum class NewlineStyle { LF, CRLF }

/**
 * 编辑需要的文本元数据。
 * [charsetName] 取值与 [EncodingDetector.decode] 的 encoding 字段对齐：
 * UTF-8 / UTF-8-BOM / UTF-16LE / UTF-16BE / GB18030 / UTF-8-FALLBACK 等。
 */
data class DocumentCodecMeta(
    val charsetName: String,
    val hasBom: Boolean,
    val newline: NewlineStyle,
    val uncertain: Boolean,
) {
    companion object {
        val DEFAULT_UTF8 = DocumentCodecMeta(
            charsetName = "UTF-8",
            hasBom = false,
            newline = NewlineStyle.LF,
            uncertain = false,
        )
    }
}

data class DecodedDocument(
    val text: String,
    val meta: DocumentCodecMeta,
)

sealed class EncodeResult {
    data class Success(val bytes: ByteArray, val meta: DocumentCodecMeta) : EncodeResult()

    /** 原编码无法表示编辑后的字符（严格编码失败）。 */
    data class Unmappable(val charsetName: String, val cause: Throwable? = null) : EncodeResult()

    /** 编码不可靠或未知，调用方应改为 UTF-8 另存为。 */
    data class Unsupported(val charsetName: String) : EncodeResult()
}

/**
 * 编辑专用文本编码器（方案 §4.3）。
 *
 * 与阅读解码保持一致：编码识别复用 [EncodingDetector]；
 * 新增职责是保存时按原文保持编码、BOM 与换行，避免 GB18030 / UTF-16 等文档
 * 因为「编辑一次就被写成 UTF-8」而发生额外变化。
 */
object DocumentTextCodec {

    fun decode(bytes: ByteArray): DecodedDocument {
        val decoded = EncodingDetector.decode(bytes)
        val newline = detectNewline(decoded.text)
        return DecodedDocument(
            text = normalizeNewlines(decoded.text),
            meta = metaOf(decoded.encoding, decoded.uncertain, newline),
        )
    }

    /**
     * 将编辑文本编码回 [meta] 对应的格式。
     * [text] 允许使用 CRLF；内部会先归一为 LF，再按 [meta.newline] 输出。
     */
    fun encode(text: String, meta: DocumentCodecMeta): EncodeResult {
        val normalized = normalizeNewlines(text)
        val alignedNewline = when (meta.newline) {
            NewlineStyle.LF -> normalized
            NewlineStyle.CRLF -> normalized.replace("\n", "\r\n")
        }
        return when (meta.charsetName.uppercase(Locale.ROOT)) {
            "UTF-8" -> EncodeResult.Success(
                bytes = alignedNewline.toByteArray(StandardCharsets.UTF_8),
                meta = meta.copy(hasBom = false, uncertain = false),
            )

            "UTF-8-BOM" -> EncodeResult.Success(
                bytes = UTF8_BOM + alignedNewline.toByteArray(StandardCharsets.UTF_8),
                meta = meta.copy(hasBom = true, uncertain = false),
            )

            "UTF-16LE" -> EncodeResult.Success(
                bytes = UTF16LE_BOM + alignedNewline.toByteArray(Charset.forName("UTF-16LE")),
                meta = meta.copy(hasBom = true, uncertain = false),
            )

            "UTF-16BE" -> EncodeResult.Success(
                bytes = UTF16BE_BOM + alignedNewline.toByteArray(Charset.forName("UTF-16BE")),
                meta = meta.copy(hasBom = true, uncertain = false),
            )

            "GB18030", "GBK" -> tryEncodeStrict(alignedNewline, Charset.forName("GB18030"), meta)

            "UTF-8-FALLBACK" -> EncodeResult.Unsupported(meta.charsetName)

            else -> EncodeResult.Unsupported(meta.charsetName)
        }
    }

    /** 编辑器内部统一换行风格。 */
    fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun metaOf(encodingName: String, uncertain: Boolean, newline: NewlineStyle): DocumentCodecMeta {
        val name = encodingName.uppercase(Locale.ROOT)
        return when {
            name == "UTF-8-BOM" -> DocumentCodecMeta("UTF-8-BOM", hasBom = true, newline, uncertain = false)
            name == "UTF-16LE" -> DocumentCodecMeta("UTF-16LE", hasBom = true, newline, uncertain = false)
            name == "UTF-16BE" -> DocumentCodecMeta("UTF-16BE", hasBom = true, newline, uncertain = false)
            name == "GB18030" || name == "GBK" -> DocumentCodecMeta("GB18030", hasBom = false, newline, uncertain = false)
            name == "UTF-8" -> DocumentCodecMeta("UTF-8", hasBom = false, newline, uncertain = uncertain)
            else -> DocumentCodecMeta(name, hasBom = false, newline, uncertain = true)
        }
    }

    private fun tryEncodeStrict(text: String, charset: Charset, meta: DocumentCodecMeta): EncodeResult {
        return try {
            EncodeResult.Success(
                bytes = encodeStrict(text, charset),
                meta = meta.copy(charsetName = charset.name(), hasBom = false, uncertain = false),
            )
        } catch (t: CharacterCodingException) {
            EncodeResult.Unmappable(charset.name(), t)
        }
    }

    private fun encodeStrict(text: String, charset: Charset): ByteArray {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    /** 换行检测基于规范化前原文；CRLF 多于 LF 时按 CRLF 保存。 */
    fun detectNewline(rawText: String): NewlineStyle {
        var lf = 0
        var crlf = 0
        var i = 0
        while (i < rawText.length) {
            if (rawText[i] == '\n') {
                if (i > 0 && rawText[i - 1] == '\r') crlf++ else lf++
            }
            i++
        }
        return if (crlf > lf) NewlineStyle.CRLF else NewlineStyle.LF
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
