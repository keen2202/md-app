package com.moread.app.file

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moread.app.prefs.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentDoc(
    val uri: Uri,
    val name: String,
    val lastOpened: Long,
    val progressBlockIndex: Int = -1,
    val progressOffset: Int = 0,
    val missing: Boolean = false,
)

/**
 * 最近文件与阅读进度存储（SPEC §1.7）。
 * JSON 序列化写入应用私有 SharedPreferences；上限 50 条，按最近打开时间 LRU 淘汰。
 */
object RecentStore {
    private const val KEY_RECENT = "recent_docs"
    private const val MAX_ITEMS = 50

    fun all(context: Context): List<RecentDoc> {
        Prefs.init(context.applicationContext)
        val sp = context.applicationContext.getSharedPreferences("moread_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                RecentDoc(
                    uri = Uri.parse(obj.getString("uri")),
                    name = obj.optString("name", "未命名文档"),
                    lastOpened = obj.optLong("lastOpened", 0L),
                    progressBlockIndex = obj.optInt("progressBlockIndex", -1),
                    progressOffset = obj.optInt("progressOffset", 0),
                    missing = obj.optBoolean("missing", false),
                )
            }.sortedByDescending { it.lastOpened }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 打开成功时调用：插入/刷新记录并持久化 URI 读授权。 */
    fun recordOpened(context: Context, uri: Uri, name: String): RecentDoc {
        val list = all(context).toMutableList()
        list.removeAll { it.uri == uri }
        val doc = RecentDoc(uri, name, System.currentTimeMillis(), missing = false)
        list.add(0, doc)
        save(context, list.take(MAX_ITEMS))
        return doc
    }

    fun updateProgress(context: Context, uri: Uri, blockIndex: Int, offset: Int) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        val old = list[idx]
        list[idx] = old.copy(progressBlockIndex = blockIndex, progressOffset = offset)
        save(context, list)
    }

    fun markMissing(context: Context, uri: Uri) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        list[idx] = list[idx].copy(missing = true)
        save(context, list)
    }

    fun remove(context: Context, uri: Uri) {
        val list = all(context).filterNot { it.uri == uri }
        save(context, list)
    }

    fun clear(context: Context) {
        val sp = context.applicationContext.getSharedPreferences("moread_prefs", Context.MODE_PRIVATE)
        sp.edit().remove(KEY_RECENT).apply()
    }

    /** 授权失效/文件被移动删除时置为「文件不存在」（PRD §5.3）。 */
    fun exists(context: Context, doc: RecentDoc): Boolean = try {
        when (doc.uri.scheme) {
            "file" -> File(doc.uri.path ?: "").exists()
            "content" -> DocumentFile.fromSingleUri(context, doc.uri)?.exists() ?: false
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun save(context: Context, list: List<RecentDoc>) {
        val array = JSONArray()
        list.forEach { doc ->
            val obj = JSONObject()
            obj.put("uri", doc.uri.toString())
            obj.put("name", doc.name)
            obj.put("lastOpened", doc.lastOpened)
            obj.put("progressBlockIndex", doc.progressBlockIndex)
            obj.put("progressOffset", doc.progressOffset)
            obj.put("missing", doc.missing)
            array.put(obj)
        }
        val sp = context.applicationContext.getSharedPreferences("moread_prefs", Context.MODE_PRIVATE)
        sp.edit().putString(KEY_RECENT, array.toString()).apply()
    }
}
