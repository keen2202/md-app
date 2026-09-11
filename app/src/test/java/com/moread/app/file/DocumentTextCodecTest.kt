package com.moread.app.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class DocumentTextCodecTest {

    @Test
    fun decodeUtf8_lf_andEncodeRoundTrip() {
        val source = "hello\nworld\n"
        val decoded = DocumentTextCodec.decode(source.toByteArray(StandardCharsets.UTF_8))

        assertEquals("hello\nworld\n", decoded.text)
        assertEquals("UTF-8", decoded.meta.charsetName)
        assertEquals(NewlineStyle.LF, decoded.meta.newline)
        assertTrue(!decoded.meta.uncertain)

        val encoded = DocumentTextCodec.encode(decoded.text, decoded.meta)
        assertTrue(encoded is EncodeResult.Success)
        assertArrayEquals(source.toByteArray(StandardCharsets.UTF_8), (encoded as EncodeResult.Success).bytes)
    }

    @Test
    fun decodeAndEncode_preserveCrlf() {
        val source = "a\r\nb\r\nc"
        val decoded = DocumentTextCodec.decode(source.toByteArray(StandardCharsets.UTF_8))

        assertEquals("a\nb\nc", decoded.text)
        assertEquals(NewlineStyle.CRLF, decoded.meta.newline)

        val encoded = DocumentTextCodec.encode(decoded.text, decoded.meta) as EncodeResult.Success
        assertEquals(source, String(encoded.bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun decodeAndEncode_preserveUtf8Bom() {
        val source = "标题".toByteArray(StandardCharsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + source

        val decoded = DocumentTextCodec.decode(withBom)
        assertEquals("标题", decoded.text)
        assertEquals("UTF-8-BOM", decoded.meta.charsetName)
        assertTrue(decoded.meta.hasBom)

        val encoded = DocumentTextCodec.encode(decoded.text, decoded.meta) as EncodeResult.Success
        assertArrayEquals(withBom, encoded.bytes)
    }

    @Test
    fun utf16Le_roundTrip() {
        val text = "标题\n第二行"
        val source = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charset.forName("UTF-16LE"))

        val decoded = DocumentTextCodec.decode(source)
        assertEquals(text, decoded.text)
        assertEquals("UTF-16LE", decoded.meta.charsetName)

        val encoded = DocumentTextCodec.encode(decoded.text, decoded.meta) as EncodeResult.Success
        assertArrayEquals(source, encoded.bytes)
    }

    @Test
    fun gb18030_roundTrip() {
        val source = "中文测试 GB18030".toByteArray(Charset.forName("GB18030"))
        val decoded = DocumentTextCodec.decode(source)

        assertEquals("中文测试 GB18030", decoded.text)
        assertEquals("GB18030", decoded.meta.charsetName)

        val encoded = DocumentTextCodec.encode(decoded.text, decoded.meta) as EncodeResult.Success
        assertArrayEquals(source, encoded.bytes)
    }

    @Test
    fun detectNewline_prefersCrlfWhenMajority() {
        assertEquals(NewlineStyle.CRLF, DocumentTextCodec.detectNewline("a\r\nb\r\nc\nd"))
        assertEquals(NewlineStyle.LF, DocumentTextCodec.detectNewline("a\nb\nc\r\nd"))
    }

    @Test
    fun utf8Fallback_isNotOverwritable() {
        val meta = DocumentCodecMeta(
            charsetName = "UTF-8-FALLBACK",
            hasBom = false,
            newline = NewlineStyle.LF,
            uncertain = true,
        )
        val result = DocumentTextCodec.encode("text", meta)
        assertTrue(result is EncodeResult.Unsupported)
    }

    @Test
    fun gb18030_unpairedSurrogate_reportsUnmappable() {
        val meta = DocumentCodecMeta(
            charsetName = "GB18030",
            hasBom = false,
            newline = NewlineStyle.LF,
            uncertain = false,
        )
        val result = DocumentTextCodec.encode("\uD800", meta)
        assertTrue(result is EncodeResult.Unmappable)
    }
}
