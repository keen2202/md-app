package com.moread.app.file

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 本地图片相对路径解析（SPEC §1.3/§2.3）。
 *
 * 安全边界：
 * - 仅接受文档同目录及子目录的相对路径；
 * - 拒绝 http(s)/data 等绝对地址（应用零网络权限，天然不加载在线图床）；
 * - 拒绝任何 `..` 路径越级，杜绝读取文档目录之外的文件。
 */
object ImageResolver {

    fun resolve(context: Context, documentUri: Uri, relativePath: String): Uri? {
        val parts = normalizeParts(relativePath) ?: return null
        return when (documentUri.scheme) {
            "file" -> resolveFile(documentUri, parts)
            "content" -> resolveContent(context, documentUri, parts)
            else -> null
        }
    }

    /** 纯路径解析入口（不依赖 Android Context/Uri，便于 JVM 单测）。 */
    fun resolveFilePath(documentPath: String, relativePath: String): String? {
        val parts = normalizeParts(relativePath) ?: return null
        return resolveFilePathParts(documentPath, parts)
    }

    private fun normalizeParts(relativePath: String): List<String>? {
        if (relativePath.isBlank()) return null
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://") ||
            relativePath.startsWith("data:") || relativePath.startsWith("file://") ||
            relativePath.startsWith("/")
        ) return null
        val parts = relativePath.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts
    }

    private fun resolveFile(documentUri: Uri, parts: List<String>): Uri? {
        val path = resolveFilePathParts(documentUri.path ?: return null, parts) ?: return null
        return Uri.fromFile(File(path))
    }

    private fun resolveFilePathParts(documentPath: String, parts: List<String>): String? {
        val documentFile = File(documentPath)
        val parent = documentFile.parentFile ?: return null
        var current = parent
        for (part in parts) {
            current = File(current, part)
            val canonical = current.canonicalFile
            if (!canonical.path.startsWith(parent.canonicalPath + File.separator)) return null
        }
        return if (current.isFile) current.canonicalPath else null
    }

    private fun resolveContent(context: Context, documentUri: Uri, parts: List<String>): Uri? {
        // 策略 1：DocumentFile 树解析（需要文档 URI 可追溯到树）。
        try {
            val document = DocumentFile.fromSingleUri(context, documentUri) ?: return null
            var base: DocumentFile? = document.parentFile
            if (base == null) {
                base = deriveParentTree(context, documentUri)?.let { DocumentFile.fromTreeUri(context, it) }
            }
            if (base != null) {
                var current: DocumentFile? = base
                for (part in parts) {
                    current = current?.findFile(part) ?: return null
                }
                return current?.uri ?: return null
            }
        } catch (_: Exception) {
            // 继续尝试 provider 文档 ID 规则。
        }

        // 策略 2：外部存储 provider 的 primary:path 文档 ID 兄弟路径。
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(documentUri)
            val separator = when {
                docId.contains('/') -> '/'
                docId.contains(':') -> ':'
                else -> return null
            }
            val parentId = docId.substringBeforeLast(separator)
            if (parentId.isBlank()) return null
            val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(documentUri.authority, parentId)
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            var current: DocumentFile? = tree
            val baseName = docId.substringAfterLast(separator)
            current = current?.findFile(baseName) ?: return null
            current = current.parentFile ?: current
            for (part in parts) current = current?.findFile(part) ?: return null
            return current.uri
        } catch (_: Exception) {
            return null
        }
    }

    private fun deriveParentTree(context: Context, documentUri: Uri): Uri? {
        return try {
            val docId = android.provider.DocumentsContract.getDocumentId(documentUri)
            if (docId.startsWith("primary:") || docId.contains('/') || docId.contains(':')) {
                val parentId = docId.substringBeforeLast(':', docId).takeIf { it.isNotBlank() }
                    ?: docId.substringBeforeLast('/', docId)
                if (parentId.isBlank() || parentId == docId) null
                else android.provider.DocumentsContract.buildTreeDocumentUri(documentUri.authority, parentId)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
