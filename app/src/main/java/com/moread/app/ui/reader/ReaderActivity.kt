package com.moread.app.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.R
import com.moread.app.file.DocumentLoader
import com.moread.app.file.EDIT_MAX_BYTES
import com.moread.app.file.LARGE_DOCUMENT_BYTES
import com.moread.app.file.RecentStore
import com.moread.app.log.AppLog
import com.moread.app.prefs.Prefs
import com.moread.app.reader.outline.OutlineExtractor
import com.moread.app.reader.parser.ast.DocumentBlock
import com.moread.app.reader.parser.MarkdownParser
import com.moread.app.reader.render.HtmlExporter
import com.moread.app.reader.render.LinkSheetDialog
import com.moread.app.reader.render.RenderItem
import com.moread.app.reader.render.RenderModel
import com.moread.app.reader.render.RenderPipeline
import com.moread.app.reader.render.SearchHit
import com.moread.app.reader.render.SearchIndex
import com.moread.app.reader.render.span.SpanFactory
import com.moread.app.theme.ThemeApplier
import com.moread.app.theme.ThemeEngine
import com.moread.app.ui.editor.EditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 阅读页（PRD 流程 1/2/3/4）。
 * 包含渲染区、大纲抽屉、底部工具条、文内搜索、阅读进度记忆、主题切换与分享。
 */
class ReaderActivity : AppCompatActivity(), SpanFactory.LinkClickHandler {

    private lateinit var documentUri: Uri
    private var documentName: String = "未命名文档"
    private var originalText: String = ""
    private var document: DocumentBlock? = null
    private var renderModel: RenderModel? = null
    private var searchIndex: SearchIndex? = null

    private lateinit var drawer: DrawerLayout
    private lateinit var recycler: RecyclerView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var progressView: View
    private lateinit var errorView: TextView
    private lateinit var titleView: TextView
    private lateinit var outlineList: RecyclerView
    private lateinit var outlineEmpty: TextView
    private lateinit var outlineAdapter: OutlineAdapter
    private lateinit var readerAdapter: ReaderAdapter
    private lateinit var recyclerLayout: LinearLayoutManager

    private val progressHandler = Handler(Looper.getMainLooper())
    private val searchHandler = Handler(Looper.getMainLooper())
    private val parseCancelled = AtomicBoolean(false)
    private var parseThread: Thread? = null
    private var barsVisible = true
    private var immersive = true
    private var readyForProgress = false
    private var searchHits: List<SearchHit> = emptyList()
    private var currentSearchIndex = -1
    private var currentOutlineIndex = -1
    private var savedBlockIndex = -1
    private var savedOffset = 0

    private val progressSaveRunnable = Runnable { saveProgressNow() }

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            if (!::documentUri.isInitialized) return@registerForActivityResult
            val newUri = result.data?.data
            if (newUri != null && newUri.toString() != documentUri.toString()) {
                documentUri = newUri
                documentName = getString(R.string.new_file_name)
                titleView.text = documentName
            }
            reloadDocument()
        }

    private val themeListener = object : ThemeEngine.ThemeListener {
        override fun onThemeChanged(event: ThemeEngine.ThemeChangeEvent) {
            if (isFinishing) return
            if (event.animated) {
                ThemeApplier.animate(drawer, event.oldPalette, event.newPalette) {
                    if (!isFinishing) {
                        applyReaderColors()
                        readerAdapter.setPalette(event.newPalette)
                        outlineAdapter.setPalette(event.newPalette)
                    }
                }
            } else {
                applyReaderColors()
                readerAdapter.setPalette(event.newPalette)
                outlineAdapter.setPalette(event.newPalette)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Reader", "onCreate start, intentData=${intent.data?.scheme ?: "null"}, extraName=${intent.getStringExtra(EXTRA_NAME)}")
        ThemeApplier.applyWindow(this, ThemeEngine.palette())
        setContentView(R.layout.activity_reader)
        bindViews()

        val uri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) {
            AppLog.e("Reader", "invalid uri: $uri")
            showError(getString(R.string.open_error))
            return
        }
        documentUri = uri
        documentName = intent.getStringExtra(EXTRA_NAME) ?: "未命名文档"
        immersive = Prefs.immersiveReader()
        AppLog.i("Reader", "uri scheme=${uri.scheme}, name=$documentName")

        setupRecycler()
        setupBars()
        setupSearch()
        setupImmersive()
        applyReaderColors()
        ThemeEngine.addListener(themeListener)
        loadDocument()
    }

    private fun bindViews() {
        drawer = findViewById(R.id.reader_drawer)
        recycler = findViewById(R.id.reader_recycler)
        topBar = findViewById(R.id.reader_top_bar)
        bottomBar = findViewById(R.id.reader_bottom_bar)
        searchBar = findViewById(R.id.reader_search_bar)
        searchInput = findViewById(R.id.search_input)
        searchCount = findViewById(R.id.search_count)
        progressView = findViewById(R.id.reader_progress)
        errorView = findViewById(R.id.reader_error)
        titleView = findViewById(R.id.reader_title)
        outlineList = findViewById(R.id.outline_list)
        outlineEmpty = findViewById(R.id.outline_empty)

        val panel = drawer.findViewById<View>(R.id.outline_panel)
        val panelParams = panel.layoutParams
        panelParams.width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        panel.layoutParams = panelParams
    }

    private fun setupRecycler() {
        recyclerLayout = LinearLayoutManager(this)
        recycler.layoutManager = recyclerLayout
        readerAdapter = ReaderAdapter(this, documentUri, this)
        recycler.adapter = readerAdapter

        outlineAdapter = OutlineAdapter { row -> onOutlineClicked(row) }
        outlineList.layoutManager = LinearLayoutManager(this)
        outlineList.adapter = outlineAdapter

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(view, dx, dy)
                if (dy > 8) setBarsVisible(false)
                if (dy < -8) setBarsVisible(true)
                updateCurrentSection()
                scheduleProgressSave()
            }
        })

        val gesture = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val h = recycler.height
                    if (h > 0 && e.y > h * 0.25f && e.y < h * 0.75f) {
                        setBarsVisible(!barsVisible)
                        return true
                    }
                    return false
                }
            },
        )
        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gesture.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupBars() {
        findViewById<ImageButton>(R.id.reader_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.reader_outline).setOnClickListener { drawer.openDrawer(GravityCompat.END) }
        findViewById<ImageButton>(R.id.reader_edit).setOnClickListener { openEditor() }
        findViewById<ImageButton>(R.id.reader_theme).setOnClickListener {
            ThemeEngine.toggleDayNight()
        }
        findViewById<ImageButton>(R.id.reader_font).setOnClickListener {
            ThemeEngine.setFontScaleIndex((ThemeEngine.fontScaleIndex + 1) % ThemeEngine.FONT_SIZES.size)
            Toast.makeText(this, "${getString(R.string.font_size)}：${ThemeEngine.fontSizeSp}sp", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.reader_search).setOnClickListener { toggleSearchBar() }
        findViewById<ImageButton>(R.id.reader_share).setOnClickListener { showShareDialog() }
        findViewById<ImageButton>(R.id.reader_immersive).setOnClickListener {
            immersive = !immersive
            Prefs.setImmersiveReader(immersive)
            applySystemBars()
        }
    }

    private fun setupSearch() {
        findViewById<ImageButton>(R.id.search_close).setOnClickListener {
            closeSearch()
        }
        findViewById<ImageButton>(R.id.search_prev).setOnClickListener { moveSearch(-1) }
        findViewById<ImageButton>(R.id.search_next).setOnClickListener { moveSearch(1) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({ performSearch(s?.toString().orEmpty()) }, 220)
            }
        })
    }

    private fun setupImmersive() {
        applySystemBars()
    }

    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        val controller = WindowInsetsControllerCompat(window, drawer)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ 加载/解析/渲染

    private fun loadDocument() {
        progressView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        AppLog.i("Reader", "loadDocument start")
        lifecycleScope.launch {
            val loadStart = SystemClock.elapsedRealtime()
            val result = try {
                val metadata = withContext(Dispatchers.IO) {
                    DocumentLoader.queryMetadata(applicationContext, documentUri)
                }
                AppLog.i("Reader", "metadata query done, size=${metadata.second}, name=${metadata.first}")
                if (metadata.second > LARGE_DOCUMENT_BYTES) {
                    Toast.makeText(this@ReaderActivity, R.string.large_file_hint, Toast.LENGTH_LONG).show()
                }
                withContext(Dispatchers.IO) {
                    DocumentLoader.load(applicationContext, documentUri)
                }
            } catch (e: Exception) {
                AppLog.e("Reader", "loadDocument failed, scheme=${documentUri.scheme}", e)
                val message = when {
                    e is com.moread.app.file.DocumentTooLargeException -> getString(R.string.file_too_large)
                    else -> getString(R.string.open_error)
                }
                showError(message)
                return@launch
            }
            AppLog.i(
                "Reader",
                "loadDocument done: bytes=${result.sizeBytes}, encoding=${result.encoding}, " +
                    "uncertain=${result.encodingUncertain}, textLen=${result.text.length}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - loadStart}",
            )
            documentName = result.displayName
            originalText = result.text
            titleView.text = documentName
            if (result.encodingUncertain) {
                Toast.makeText(this@ReaderActivity, R.string.encoding_maybe_bad, Toast.LENGTH_LONG).show()
            }
            try {
                RecentStore.recordOpened(applicationContext, documentUri, documentName)
            } catch (_: Exception) {
                // 记录失败不影响阅读。
            }
            renderDocument(result.text)
        }
    }

    private fun openEditor() {
        if (!::documentUri.isInitialized || document == null) return
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                DocumentLoader.queryMetadata(applicationContext, documentUri).second
            }
            if (size > EDIT_MAX_BYTES) {
                Toast.makeText(this@ReaderActivity, R.string.editor_too_large, Toast.LENGTH_LONG).show()
                return@launch
            }
            editLauncher.launch(EditorActivity.intent(this@ReaderActivity, documentUri, documentName))
        }
    }

    private fun reloadDocument() {
        if (!::documentUri.isInitialized) return
        parseCancelled.set(true)
        readerAdapter.submitItems(emptyList())
        outlineAdapter.submit(emptyList())
        outlineEmpty.visibility = View.GONE
        outlineList.visibility = View.GONE
        document = null
        renderModel = null
        searchIndex = null
        originalText = ""
        readyForProgress = false
        currentOutlineIndex = -1
        searchHits = emptyList()
        currentSearchIndex = -1
        readerAdapter.clearSearch()
        if (searchBar.visibility == View.VISIBLE) closeSearch()
        loadDocument()
    }

    private fun renderDocument(text: String) {
        parseCancelled.set(false)
        progressView.visibility = View.VISIBLE
        AppLog.i("Reader", "renderDocument start, textLen=${text.length}")
        val parser = MarkdownParser()
        val pipeline = RenderPipeline()
        val mainHandler = Handler(Looper.getMainLooper())
        val pending = Collections.synchronizedList(ArrayList<RenderItem>())
        var lastFlush = 0L

        fun flushPending() {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) return
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            if (parseCancelled.get()) return
            try {
                if (readerAdapter.itemCount == 0) readerAdapter.submitItems(batch) else readerAdapter.appendItems(batch)
            } catch (t: Throwable) {
                AppLog.e("Reader", "flushPending failed, batch=${batch.size}, itemCount=${readerAdapter.itemCount}", t)
                throw t
            }
        }

        parseThread?.interrupt()
        parseThread = thread(name = "moread-markdown-parse") {
            var root: DocumentBlock? = null
            var parseFailed = false
            val parseStart = SystemClock.elapsedRealtime()
            try {
                root = parser.parseStreaming(
                    text = text,
                    shouldContinue = { !parseCancelled.get() },
                    onBlock = { block ->
                        val batch = pipeline.buildBlocks(listOf(block))
                        if (batch.isNotEmpty()) {
                            synchronized(pending) { pending.addAll(batch) }
                            val now = SystemClock.uptimeMillis()
                            if (now - lastFlush >= 16 || pending.size >= 48) {
                                lastFlush = now
                                mainHandler.post { flushPending() }
                            }
                        }
                    },
                    onRecovered = { t ->
                        AppLog.e("Parser", "commonmark parse degraded to plain text, textLen=${text.length}", t)
                    },
                )
            } catch (t: Throwable) {
                // 解析器自身容错；此处防御，避免畸形 Markdown 导致进程闪退。
                AppLog.e("Parser", "parseStreaming failed, textLen=${text.length}", t)
                parseFailed = true
            }
            val parsed = root
            AppLog.i(
                "Parser",
                "parseStreaming done: ok=${parsed != null}, blocks=${parsed?.blocks?.size ?: -1}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - parseStart}",
            )
            mainHandler.post {
                if (!parseCancelled.get() && !isFinishing) {
                    if (parsed != null) {
                        onParseFinished(parsed)
                    } else if (parseFailed) {
                        showError(getString(R.string.open_error))
                    }
                }
            }
        }
    }

    private fun onParseFinished(root: DocumentBlock) {
        val buildStart = SystemClock.elapsedRealtime()
        AppLog.i("Reader", "onParseFinished start, blocks=${root.blocks.size}")
        try {
            document = root
            val model = RenderPipeline().build(root)
            renderModel = model
            readerAdapter.submitItems(model.items)
            searchIndex = SearchIndex(model)

            val outline = OutlineExtractor.extract(root)
            outlineAdapter.submit(outline.map { OutlineRow(it, model.itemIndexForBlock(it.blockIndex)) })
            outlineEmpty.visibility = if (outline.isEmpty()) View.VISIBLE else View.GONE
            outlineList.visibility = if (outline.isEmpty()) View.GONE else View.VISIBLE

            AppLog.i(
                "Reader",
                "onParseFinished done: items=${model.items.size}, outline=${outline.size}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - buildStart}",
            )
        } catch (t: Throwable) {
            AppLog.e("Reader", "onParseFinished failed, blocks=${root.blocks.size}", t)
            showError(getString(R.string.open_error))
            return
        }

        progressView.visibility = View.GONE
        recycler.post {
            restoreProgressIfAny()
            readyForProgress = true
            updateCurrentSection()
        }
    }

    private fun restoreProgressIfAny() {
        val model = renderModel ?: return
        val doc = RecentStore.all(applicationContext).firstOrNull { it.uri == documentUri } ?: return
        if (doc.progressBlockIndex >= 0) {
            val itemIndex = model.itemIndexForBlock(doc.progressBlockIndex)
            recyclerLayout.scrollToPositionWithOffset(itemIndex, doc.progressOffset)
            savedBlockIndex = doc.progressBlockIndex
            savedOffset = doc.progressOffset
        }
    }

    // ------------------------------------------------------------------ 进度记忆

    private fun scheduleProgressSave() {
        if (!readyForProgress) return
        progressHandler.removeCallbacks(progressSaveRunnable)
        progressHandler.postDelayed(progressSaveRunnable, 500)
    }

    private fun saveProgressNow() {
        val model = renderModel ?: return
        if (!readyForProgress) return
        val position = recyclerLayout.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val item = model.items.getOrNull(position) ?: return
        val view = recyclerLayout.findViewByPosition(position)
        val offset = if (view != null) view.top - recycler.paddingTop else 0
        savedBlockIndex = item.primaryBlock.blockIndex
        savedOffset = offset
        RecentStore.updateProgress(applicationContext, documentUri, savedBlockIndex, savedOffset)
    }

    // ------------------------------------------------------------------ 大纲

    private fun onOutlineClicked(row: OutlineRow) {
        drawer.closeDrawer(GravityCompat.END)
        scrollToItem(row.renderItemIndex, flash = true)
    }

    private fun scrollToItem(itemIndex: Int, flash: Boolean) {
        val model = renderModel ?: return
        if (itemIndex !in model.items.indices) return
        val smooth = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            override fun computeScrollVectorForPosition(targetPosition: Int): android.graphics.PointF? =
                recyclerLayout.computeScrollVectorForPosition(targetPosition) ?: android.graphics.PointF(0f, 1f)

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int,
            ): Int = boxStart - viewStart
        }
        smooth.targetPosition = itemIndex
        recyclerLayout.startSmoothScroll(smooth)
        recycler.postDelayed({
            if (!isFinishing && flash) {
                val position = recyclerLayout.findFirstVisibleItemPosition()
                if (position != RecyclerView.NO_POSITION) readerAdapter.flashPosition(position)
            }
        }, 320)
    }

    private fun updateCurrentSection() {
        val model = renderModel ?: return
        val outlineRows = outlineAdapter.currentRows()
        if (outlineRows.isEmpty()) return
        val position = recyclerLayout.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val firstBlock = model.items.getOrNull(position)?.primaryBlock?.blockIndex ?: return
        var selected = 0
        for (i in outlineRows.indices) {
            if (outlineRows[i].item.blockIndex <= firstBlock) selected = i else break
        }
        if (selected != currentOutlineIndex) {
            currentOutlineIndex = selected
            outlineAdapter.setSelected(selected)
        }
    }

    // ------------------------------------------------------------------ 搜索

    private fun toggleSearchBar() {
        if (searchBar.visibility == View.VISIBLE) closeSearch() else openSearch()
    }

    private fun openSearch() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchBar.visibility = View.GONE
        searchHandler.removeCallbacksAndMessages(null)
        searchInput.setText("")
        searchHits = emptyList()
        currentSearchIndex = -1
        readerAdapter.clearSearch()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun performSearch(query: String) {
        val index = searchIndex ?: return
        if (query.isEmpty()) {
            searchHits = emptyList()
            currentSearchIndex = -1
            searchCount.text = getString(R.string.search_result_format, 0, 0)
            readerAdapter.clearSearch()
            return
        }
        searchHits = index.search(query)
        if (searchHits.isEmpty()) {
            currentSearchIndex = -1
            searchCount.text = getString(R.string.search_no_result)
            readerAdapter.clearSearch()
            return
        }
        currentSearchIndex = 0
        updateSearchUi()
        goToSearchHit(0, smooth = true)
    }

    private fun moveSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        currentSearchIndex = (currentSearchIndex + delta + searchHits.size) % searchHits.size
        updateSearchUi()
        goToSearchHit(currentSearchIndex, smooth = true)
    }

    private fun updateSearchUi() {
        searchCount.text = getString(R.string.search_result_format, currentSearchIndex + 1, searchHits.size)
        readerAdapter.setSearchResults(searchHits, searchHits.getOrNull(currentSearchIndex))
    }

    private fun goToSearchHit(index: Int, smooth: Boolean) {
        val hit = searchHits.getOrNull(index) ?: return
        val model = renderModel ?: return
        if (hit.itemIndex !in model.items.indices) return
        if (smooth) {
            scrollToItem(hit.itemIndex, flash = false)
        } else {
            recyclerLayout.scrollToPositionWithOffset(hit.itemIndex, 0)
        }
    }

    // ------------------------------------------------------------------ 分享/导出

    private fun showShareDialog() {
        if (document == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.share)
            .setItems(arrayOf(getString(R.string.share_markdown), getString(R.string.share_html))) { _, which ->
                if (which == 0) shareMarkdown() else shareHtml()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareMarkdown() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, originalText)
            putExtra(Intent.EXTRA_SUBJECT, documentName)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareHtml() {
        val root = document ?: return
        val html = HtmlExporter.renderStyled(root, ThemeEngine.palette())
        try {
            val shareDir = File(cacheDir, "share").apply { mkdirs() }
            shareDir.listFiles()?.forEach { it.delete() } // 仅保留最新临时文件
            val file = File(shareDir, "moread-${System.currentTimeMillis()}.html")
            file.writeText(html)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, documentName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_html)))
            // 分享面板返回后延时清理；避免误删接收方仍在读取的文件。
            progressHandler.postDelayed({ file.delete() }, 5 * 60 * 1000L)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 主题/工具条

    private fun applyReaderColors() {
        val palette = ThemeEngine.palette()
        ThemeApplier.applyWindow(this, palette)
        drawer.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        bottomBar.setBackgroundColor(palette.background)
        searchBar.setBackgroundColor(palette.card)
        drawer.findViewById<View>(R.id.outline_panel).setBackgroundColor(palette.background)
        titleView.setTextColor(palette.textPrimary)
        searchInput.setTextColor(palette.textPrimary)
        searchInput.setHintTextColor(palette.textSecondary)
        searchCount.setTextColor(palette.textSecondary)
        outlineEmpty.setTextColor(palette.textSecondary)
        listOf(R.id.reader_back, R.id.reader_edit, R.id.reader_share, R.id.reader_immersive, R.id.reader_outline, R.id.reader_theme, R.id.reader_font, R.id.reader_search, R.id.search_close, R.id.search_prev, R.id.search_next).forEach {
            findViewById<ImageButton>(it).imageTintList = ColorStateList.valueOf(palette.textPrimary)
        }
        readerAdapter.setPalette(palette)
        outlineAdapter.setPalette(palette)
    }

    private fun setBarsVisible(visible: Boolean) {
        if (barsVisible == visible) return
        barsVisible = visible
        val topDistance = -topBar.height.toFloat()
        val bottomDistance = bottomBar.height.toFloat()
        topBar.animate().translationY(if (visible) 0f else topDistance).setDuration(220).start()
        bottomBar.animate().translationY(if (visible) 0f else bottomDistance).setDuration(220).start()
    }

    override fun onLinkClick(url: String) {
        LinkSheetDialog.show(this, url)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeEngine.onSystemConfigurationChanged(newConfig)
        applySystemBars()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        saveProgressNow()
        super.onPause()
    }

    override fun onDestroy() {
        AppLog.i("Reader", "onDestroy, name=$documentName")
        parseCancelled.set(true)
        progressHandler.removeCallbacksAndMessages(null)
        searchHandler.removeCallbacksAndMessages(null)
        ThemeEngine.removeListener(themeListener)
        super.onDestroy()
    }

    private fun showError(message: String) {
        AppLog.e("Reader", "showError: $message")
        progressView.visibility = View.GONE
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_NAME = "extra_name"

        fun intent(context: Context, uri: Uri, name: String? = null): Intent =
            Intent(context, ReaderActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_NAME, name)
    }
}
