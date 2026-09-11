package com.moread.app.file

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed class WriteResult {
    data object Success : WriteResult()
    data class Failure(val message: String, val cause: Throwable? = null) : WriteResult()
}

/**
 * SAF 回写（方案 §4.2 / §4.4）。
 *
 * 设计取舍：
 * - SAF 没有通用原子替换语义，因此覆盖前由调用方先保存私有草稿；
 * - content URI 优先试用 `rw` + truncate，失败再回退 `wt`，尽量避免打开即截断；
 * - file:// 仅在路径可写时直接写，Android 10+ 不可写时由上层引导另存为。
 */
object DocumentWriter {

    fun canWrite(context: Context, uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "file" -> File(uri.path ?: return false).canWrite()
                "content" -> {
                    val granted = context.checkUriPermission(
                        uri,
                        Process.myPid(),
                        Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    ) == PackageManager.PERMISSION_GRANTED
                    val documentWritable = DocumentFile.fromSingleUri(context, uri)?.canWrite() != false
                    granted && documentWritable
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray): WriteResult {
        return try {
            when (uri.scheme) {
                "file" -> writeFile(File(uri.path ?: throw IOException("无效的文件路径")), bytes)
                "content" -> writeContent(context, uri, bytes)
                else -> throw IOException("不支持的 URI scheme: ${uri.scheme}")
            }
            WriteResult.Success
        } catch (t: Throwable) {
            WriteResult.Failure(t.message ?: "写入失败", t)
        }
    }

    private fun writeFile(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            try {
                out.fd.sync()
            } catch (_: Exception) {
                // 部分文件系统不支持 sync，不阻断保存。
            }
        }
    }

    private fun writeContent(context: Context, uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver

        // 优先 rw：先覆盖写入，成功后 truncate 掉旧内容尾部。
        // 直接使用 Os 写 fd，避免 FileOutputStream 包装 pfd 后双重 close 的风险。
        val rwFailure = try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("无法打开文件描述符")
            descriptor.use { pfd ->
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(pfd.fileDescriptor, bytes, offset, bytes.size - offset)
                    if (written <= 0) throw IOException("写入返回 $written")
                    offset += written
                }
                try {
                    Os.ftruncate(pfd.fileDescriptor, bytes.size.toLong())
                } catch (t: Exception) {
                    throw IOException("无法截断原文件，已回退重写", t)
                }
                try {
                    Os.fsync(pfd.fileDescriptor)
                } catch (_: Exception) {
                    // provider 可能不支持 sync，不阻断保存。
                }
            }
            return
        } catch (t: Throwable) {
            t
        }

        // 回退 wt：部分 provider 不支持 rw，或 rw 写入过程中失败；此时用完整内存字节重写。
        try {
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("无法打开输出流")
            output.use { out ->
                out.write(bytes)
                out.flush()
                if (out is FileOutputStream) {
                    try {
                        out.fd.sync()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
        } catch (fallback: Throwable) {
            throw IOException(
                "写入失败：${rwFailure.message ?: "rw 模式不可用"}；回退 wt 也失败：${fallback.message ?: "未知错误"}",
                fallback,
            )
        }
    }
}
