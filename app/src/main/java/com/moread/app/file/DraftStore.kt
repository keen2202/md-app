package com.moread.app.file

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class EditorDraft(
    val uri: Uri?,
    val displayName: String,
    val text: String,
    val meta: DocumentCodecMeta,
    val savedAt: Long,
)

/**
 * 编辑草稿（方案 §4.5）。
 *
 * 草稿写入应用私有目录，不新增任何权限，也不外发。
 * - 已有文件使用 URI 的 SHA-256 作为键；
 * - 「未命名.md」尚未选择目标 URI，使用固定键 __new__。
 */
object DraftStore {
    private const val DIR_NAME = "editor_drafts"
    private const val NEW_KEY = "__new__"
    private const val META_SUFFIX = ".json"
    private const val TEXT_SUFFIX = ".txt"

    fun keyFor(uri: Uri?): String =
        if (uri == null) NEW_KEY else sha256(uri.toString())

    @Synchronized
    fun save(
        context: Context,
        uri: Uri?,
        displayName: String,
        text: String,
        meta: DocumentCodecMeta,
    ) {
        val dir = draftDir(context).apply { mkdirs() }
        val key = keyFor(uri)
        val textFile = File(dir, "$key$TEXT_SUFFIX")
        val metaFile = File(dir, "$key$META_SUFFIX")

        // 先用临时文件写入，尽量降低进程被杀导致半截草稿的概率。
        val textTmp = File(dir, "$key$TEXT_SUFFIX.tmp")
        textTmp.writeText(text)
        if (!textTmp.renameTo(textFile)) {
            textFile.writeText(text)
            textTmp.delete()
        }

        val json = JSONObject().apply {
            put("uri", uri?.toString() ?: JSONObject.NULL)
            put("displayName", displayName)
            put("charsetName", meta.charsetName)
            put("hasBom", meta.hasBom)
            put("newline", meta.newline.name)
            put("uncertain", meta.uncertain)
            put("savedAt", System.currentTimeMillis())
        }
        val metaTmp = File(dir, "$key$META_SUFFIX.tmp")
        metaTmp.writeText(json.toString())
        if (!metaTmp.renameTo(metaFile)) {
            metaFile.writeText(json.toString())
            metaTmp.delete()
        }
    }

    @Synchronized
    fun load(context: Context, uri: Uri?): EditorDraft? {
        return try {
            val dir = draftDir(context)
            val key = keyFor(uri)
            val textFile = File(dir, "$key$TEXT_SUFFIX")
            val metaFile = File(dir, "$key$META_SUFFIX")
            if (!textFile.isFile || !metaFile.isFile) return null

            val json = JSONObject(metaFile.readText())
            val storedUri = if (json.isNull("uri")) null else Uri.parse(json.getString("uri"))
            val meta = DocumentCodecMeta(
                charsetName = json.optString("charsetName", "UTF-8"),
                hasBom = json.optBoolean("hasBom", false),
                newline = when (json.optString("newline", NewlineStyle.LF.name)) {
                    NewlineStyle.CRLF.name -> NewlineStyle.CRLF
                    else -> NewlineStyle.LF
                },
                uncertain = json.optBoolean("uncertain", false),
            )
            EditorDraft(
                uri = storedUri,
                displayName = json.optString("displayName", "未命名.md"),
                text = textFile.readText(),
                meta = meta,
                savedAt = json.optLong("savedAt", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear(context: Context, uri: Uri?) {
        try {
            val dir = draftDir(context)
            val key = keyFor(uri)
            File(dir, "$key$TEXT_SUFFIX").delete()
            File(dir, "$key$META_SUFFIX").delete()
            File(dir, "$key$TEXT_SUFFIX.tmp").delete()
            File(dir, "$key$META_SUFFIX.tmp").delete()
        } catch (_: Exception) {
            // 清理失败不影响主流程。
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        try {
            draftDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // ignore
        }
    }

    @Synchronized
    fun hasDraft(context: Context, uri: Uri?): Boolean {
        val dir = draftDir(context)
        val key = keyFor(uri)
        return File(dir, "$key$TEXT_SUFFIX").isFile && File(dir, "$key$META_SUFFIX").isFile
    }

    private fun draftDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }
}
