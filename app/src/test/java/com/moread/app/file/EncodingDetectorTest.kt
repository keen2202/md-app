package com.moread.app.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectorTest {
    private val chinese = "墨阅 Markdown 阅读器，中文文档零乱码。\n第二行"

    @Test fun `utf8 without bom`() {
        val r = EncodingDetector.decode(chinese.toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", r.encoding)
        assertEquals(chinese, r.text)
        assertFalse(r.uncertain)
    }

    @Test fun `utf8 with bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + chinese.toByteArray(Charsets.UTF_8)
        val r = EncodingDetector.decode(bytes)
        assertEquals("UTF-8-BOM", r.encoding)
        assertEquals(chinese, r.text)
    }

    @Test fun `gbk bytes decoded as chinese`() {
        val bytes = chinese.toByteArray(Charset.forName("GBK"))
        val r = EncodingDetector.decode(bytes)
        assertEquals("GB18030", r.encoding)
        assertEquals(chinese, r.text)
    }

    @Test fun `utf16 le with bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chinese.toByteArray(Charsets.UTF_16LE)
        val r = EncodingDetector.decode(bytes)
        assertEquals("UTF-16LE", r.encoding)
        assertEquals(chinese, r.text)
    }

    @Test fun `invalid bytes fallback utf8 and mark uncertain`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte(), 0x41)
        val r = EncodingDetector.decode(bytes)
        assertTrue(r.uncertain)
    }
}
