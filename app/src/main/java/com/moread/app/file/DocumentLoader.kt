package com.moread.app.file

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** 20MB 硬上限（SPEC §2.3）。 */
const val MAX_DOCUMENT_BYTES = 20L * 1024L * 1024L

/** 大文件提示阈值（PRD §5.3）。 */
const val LARGE_DOCUMENT_BYTES = 5L * 1024L * 1024L

/** 一期编辑上限：原始字节 ≤ 1MB；超过后仅阅读（方案 §4.3）。 */
const val EDIT_MAX_BYTES = 1L * 1024L * 1024L

data class LoadedDocument(
    val uri: Uri,
    val displayName: String,
    val text: String,
    val encoding: String,
    val encodingUncertain: Boolean,
    val sizeBytes: Long,
)

class DocumentTooLargeException : IOException("文件超过 20MB，无法打开")

class DocumentOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * SAF/文件流式读取（SPEC §1.7）。
 * 仅读取用户显式授权的单个 URI；流式读取并按进度回调，避免一次性大数组峰值。
 */
object DocumentLoader {

    fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "未命名文档"
        var size = -1L
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            if (cursor != null) {
                cursor.use {
                    if (it.moveToFirst()) {
                        val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
                        if (sizeIdx >= 0 && !it.isNull(sizeIdx)) size = it.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
            // 元数据查询失败不阻断读取。
        }
        if (name == "未命名文档" && uri.scheme == "file") {
            name = File(uri.path ?: "").name.ifBlank { "未命名文档" }
        }
        return name to size
    }

    fun load(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): LoadedDocument {
        val (name, _) = queryMetadata(context, uri)
        val bytes = loadBytes(context, uri, onProgress)
        val decoded = EncodingDetector.decode(bytes)
        return LoadedDocument(
            uri = uri,
            displayName = name,
            text = decoded.text,
            encoding = decoded.encoding,
            encodingUncertain = decoded.uncertain,
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * 读取原始字节，供编辑能力保存时做编码保持。
     * 仍遵守 20MB 阅读硬上限；超过 1MB 的编辑上限由 EditorActivity 判断。
     */
    fun loadBytes(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        val (_, knownSize) = queryMetadata(context, uri)
        if (knownSize > MAX_DOCUMENT_BYTES) throw DocumentTooLargeException()

        val bytes = try {
            readBytes(context, uri, knownSize, onProgress)
        } catch (e: DocumentTooLargeException) {
            throw e
        } catch (e: Exception) {
            throw DocumentOpenException("读取文档失败: ${e.message ?: "未知错误"}", e)
        }
        if (bytes.size > MAX_DOCUMENT_BYTES) throw DocumentTooLargeException()
        return bytes
    }

    private fun readBytes(
        context: Context,
        uri: Uri,
        knownSize: Long,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        val input: InputStream = when (uri.scheme) {
            "file" -> FileInputStream(File(uri.path ?: throw DocumentOpenException("无效的文件路径")))
            "content" -> context.contentResolver.openInputStream(uri)
                ?: throw DocumentOpenException("无法打开内容 URI")
            else -> throw DocumentOpenException("不支持的 URI scheme: ${uri.scheme}")
        }

        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE.coerceAtLeast(16 * 1024))
            val out = ByteArrayOutputStream()
            var total = 0L
            var lastProgress = -1
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DOCUMENT_BYTES) throw DocumentTooLargeException()
                out.write(buffer, 0, read)
                if (knownSize > 0) {
                    val percent = (total * 100 / knownSize).toInt()
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent / 100f)
                    }
                }
            }
            return out.toByteArray()
        }
    }
}
