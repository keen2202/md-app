package com.moread.app.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.MainActivity
import com.moread.app.R
import com.moread.app.file.RecentDoc
import com.moread.app.file.RecentStore
import com.moread.app.theme.ThemeApplier
import com.moread.app.theme.ThemeEngine
import com.moread.app.ui.editor.EditorActivity
import com.moread.app.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：最近文件列表 + 首次启动空状态 + SAF 打开入口（PRD 流程 1）。
 */
class HomeFragment : Fragment() {

    private lateinit var adapter: RecentAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var root: View
    private lateinit var titleView: TextView
    private lateinit var openButton: Button
    private lateinit var openFileButton: ImageButton
    private lateinit var newButton: ImageButton
    private lateinit var settingsButton: ImageButton

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    context?.contentResolver?.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // 部分 provider 不支持持久授权；本次读取仍可继续。
                }
                startActivity(ReaderActivity.intent(requireContext(), uri))
            }
        }

    private val themeListener = object : ThemeEngine.ThemeListener {
        override fun onThemeChanged(event: ThemeEngine.ThemeChangeEvent) {
            val view = view ?: return
            if (!isAdded) return
            if (event.animated) {
                ThemeApplier.animate(view, event.oldPalette, event.newPalette) {
                    if (!isAdded) return@animate
                    applyColors()
                    adapter.setPalette(event.newPalette)
                }
            } else {
                applyColors()
                adapter.setPalette(event.newPalette)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_home, container, false)
        recycler = root.findViewById(R.id.recent_list)
        emptyView = root.findViewById(R.id.home_empty)
        titleView = root.findViewById(R.id.home_title)
        openButton = root.findViewById(R.id.home_open_button)
        openFileButton = root.findViewById(R.id.home_open_file)
        newButton = root.findViewById(R.id.home_new)
        settingsButton = root.findViewById(R.id.home_settings)

        adapter = RecentAdapter(
            onOpen = { doc -> openRecent(doc) },
            onLongClick = { doc -> confirmRemove(doc) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        root.findViewById<View>(R.id.home_top_bar).setOnClickListener { }
        openButton.setOnClickListener { openDocument.launch(arrayOf("text/*")) }
        openFileButton.setOnClickListener { openDocument.launch(arrayOf("text/*")) }
        newButton.setOnClickListener { startActivity(EditorActivity.newDocumentIntent(requireContext())) }
        settingsButton.setOnClickListener { (activity as? MainActivity)?.showSettings() }
        applyColors()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeEngine.addListener(themeListener)
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    override fun onDestroyView() {
        ThemeEngine.removeListener(themeListener)
        super.onDestroyView()
    }

    private fun refreshRecent() {
        viewLifecycleOwner.lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) {
                val all = RecentStore.all(requireContext().applicationContext)
                all.map { doc ->
                    val missing = doc.missing || !RecentStore.exists(requireContext().applicationContext, doc)
                    if (missing && !doc.missing) RecentStore.markMissing(requireContext().applicationContext, doc.uri)
                    doc.copy(missing = missing)
                }
            }
            adapter.submit(docs)
            emptyView.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (docs.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openRecent(doc: RecentDoc) {
        if (doc.missing) {
            Toast.makeText(requireContext(), R.string.file_not_exists, Toast.LENGTH_SHORT).show()
            confirmRemove(doc)
            return
        }
        startActivity(ReaderActivity.intent(requireContext(), doc.uri, doc.name))
    }

    private fun confirmRemove(doc: RecentDoc) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_record)
            .setMessage(R.string.remove_record_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                RecentStore.remove(requireContext(), doc.uri)
                refreshRecent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyColors() {
        val palette = ThemeEngine.palette()
        ThemeApplier.applyWindow(requireActivity(), palette)
        root.setBackgroundColor(palette.background)
        root.findViewById<View>(R.id.home_top_bar).setBackgroundColor(palette.background)
        titleView.setTextColor(palette.textPrimary)
        emptyView.findViewById<TextView>(R.id.home_empty_title).setTextColor(palette.textPrimary)
        emptyView.findViewById<TextView>(R.id.home_empty_subtitle).setTextColor(palette.textSecondary)
        openButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        openFileButton.imageTintList = ColorStateList.valueOf(palette.textPrimary)
        newButton.imageTintList = ColorStateList.valueOf(palette.textPrimary)
        settingsButton.imageTintList = ColorStateList.valueOf(palette.textPrimary)
    }

}
