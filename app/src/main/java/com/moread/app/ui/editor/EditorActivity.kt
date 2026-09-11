package com.moread.app.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moread.app.R
import com.moread.app.editor.EditCommandResult
import com.moread.app.editor.MarkdownEditCommands
import com.moread.app.file.DocumentCodecMeta
import com.moread.app.file.DocumentLoader
import com.moread.app.file.DocumentTextCodec
import com.moread.app.file.DocumentTooLargeException
import com.moread.app.file.DocumentWriter
import com.moread.app.file.DraftStore
import com.moread.app.file.EDIT_MAX_BYTES
import com.moread.app.file.EditorDraft
import com.moread.app.file.EncodeResult
import com.moread.app.file.RecentStore
import com.moread.app.file.WriteResult
import com.moread.app.log.AppLog
import com.moread.app.reader.parser.MarkdownParser
import com.moread.app.reader.render.LinkSheetDialog
import com.moread.app.reader.render.RenderModel
import com.moread.app.reader.render.RenderPipeline
import com.moread.app.reader.render.span.SpanFactory
import com.moread.app.theme.ThemeApplier
import com.moread.app.theme.ThemeEngine
import com.moread.app.ui.reader.ReaderAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Markdown 编辑页（v1.1.0 一期）。
 *
 * 设计原则：
 * - 源码即所见：编辑区为纯文本，不隐藏 Markdown 标记；
 * - 预览复用现有原生渲染链路（MarkdownParser → RenderPipeline → ReaderAdapter）；
 * - 保存前先写应用私有草稿，SAF 写入失败不丢内容；
 * - 不改动阅读页只读链路，保存成功后由阅读页自行重载。
 */
class EditorActivity : AppCompatActivity(), SpanFactory.LinkClickHandler {

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var editText: EditText
    private lateinit var previewRecycler: RecyclerView
    private lateinit var previewEmpty: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var previewButton: TextView
    private lateinit var saveAsButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var formatBar: HorizontalScrollView
    private lateinit var formatContainer: LinearLayout

    private var documentUri: Uri? = null
    private var documentName: String = "未命名.md"
    private var originalText: String = ""
    private var originalBytes: ByteArray? = null
    private var meta: DocumentCodecMeta = DocumentCodecMeta.DEFAULT_UTF8
    private var isNewDocument = false
    private var dirty = false
    private var loading = false
    private var saving = false
    private var forceSaveAs = false
    private var saveThenFinish = false
    private var pendingCreateFinish = false
    private var pendingSaveAsUtf8 = false
    private var suppressTextWatcher = false
    private var previewMode = false
    private var previewJob: Job? = null
    private var previewAdapter: ReaderAdapter? = null
    private var previewAdapterUri: Uri? = null
    private var renderModel: RenderModel? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val previewParser = MarkdownParser()
    private val formatButtons = ArrayList<TextView>()
    private val draftRunnable = Runnable { persistDraftNow() }

    private val themeListener = object : ThemeEngine.ThemeListener {
        override fun onThemeChanged(event: ThemeEngine.ThemeChangeEvent) {
            if (isFinishing) return
            applyColors()
        }
    }

    // ------------------------------------------------------------------ 权限/系统选择器

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val target = result.data?.data
                if (target != null) {
                    persistReadWritePermission(target)
                    val text = editText.text?.toString().orEmpty()
                    val targetMeta = if (pendingSaveAsUtf8 || meta.uncertain) {
                        DocumentCodecMeta.DEFAULT_UTF8.copy(newline = meta.newline)
                    } else {
                        meta
                    }
                    when (val encoded = DocumentTextCodec.encode(text, targetMeta)) {
                        is EncodeResult.Success -> {
                            if (encoded.bytes.size > EDIT_MAX_BYTES) {
                                pendingCreateFinish = false
                                showEditorLimitDialog()
                            } else {
                                performSave(target, text, encoded.bytes, encoded.meta, pendingCreateFinish)
                            }
                        }
                        else -> {
                            pendingCreateFinish = false
                            saveThenFinish = false
                            Toast.makeText(
                                this,
                                R.string.editor_encoding_uncertain_toast,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pendingCreateFinish = false
                    saveThenFinish = false
                }
            } else {
                pendingCreateFinish = false
                saveThenFinish = false
            }
        }

    private val reauthorizeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val target = result.data?.data ?: return@registerForActivityResult
            persistReadWritePermission(target)
            if (documentUri != null && target.toString() == documentUri.toString()) {
                lifecycleScope.launch {
                    val writable = withContext(Dispatchers.IO) {
                        DocumentWriter.canWrite(applicationContext, target)
                    }
                    forceSaveAs = !writable || meta.uncertain
                    Toast.makeText(
                        this@EditorActivity,
                        if (forceSaveAs) R.string.reauthorize_mismatch else R.string.reauthorize_success,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(this, R.string.reauthorize_mismatch, Toast.LENGTH_LONG).show()
            }
        }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeApplier.applyWindow(this, ThemeEngine.palette())
        setContentView(R.layout.activity_editor)
        bindViews()
        setupTopBar()
        setupEditor()
        setupFormatActions()
        ThemeEngine.addListener(themeListener)

        val uri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        isNewDocument = uri == null || intent.getBooleanExtra(EXTRA_NEW, false)
        documentName = intent.getStringExtra(EXTRA_NAME)
            ?: if (isNewDocument) getString(R.string.new_file_name) else "未命名文档"

        if (isNewDocument) {
            loadNewDocument()
        } else {
            documentUri = uri
            loadExistingDocument(uri!!)
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.editor_root)
        topBar = findViewById(R.id.editor_top_bar)
        titleView = findViewById(R.id.editor_title)
        editText = findViewById(R.id.editor_text)
        previewRecycler = findViewById(R.id.editor_preview_recycler)
        previewEmpty = findViewById(R.id.editor_preview_empty)
        loadingView = findViewById(R.id.editor_loading)
        errorView = findViewById(R.id.editor_error)
        previewButton = findViewById(R.id.editor_preview_button)
        saveAsButton = findViewById(R.id.editor_save_as_button)
        saveButton = findViewById(R.id.editor_save_button)
        formatBar = findViewById(R.id.editor_format_bar)
        formatContainer = findViewById(R.id.editor_format_actions)
        previewRecycler.layoutManager = LinearLayoutManager(this)
        applyColors()
    }

    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.editor_back).setOnClickListener { requestFinish() }
        previewButton.setOnClickListener { togglePreview() }
        saveAsButton.setOnClickListener { startSaveAs() }
        saveButton.setOnClickListener { save() }
    }

    private fun setupEditor() {
        editText.typeface = Typeface.MONOSPACE
        editText.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editText.setHorizontallyScrolling(false)
        editText.doAfterTextChanged { editable ->
            if (suppressTextWatcher) return@doAfterTextChanged
            val text = editable?.toString().orEmpty()
            val nowDirty = text != originalText
            if (nowDirty != dirty) {
                dirty = nowDirty
                updateTitle()
            }
            scheduleDraft()
            if (previewMode) schedulePreview(immediate = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeEngine.onSystemConfigurationChanged(newConfig)
        applyColors()
    }

    override fun onPause() {
        persistDraftNow()
        super.onPause()
    }

    override fun onStop() {
        persistDraftNow()
        super.onStop()
    }

    override fun onBackPressed() {
        if (saving) return
        requestFinish()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        previewJob?.cancel()
        ThemeEngine.removeListener(themeListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 加载

    private fun loadNewDocument() {
        documentUri = null
        meta = DocumentCodecMeta.DEFAULT_UTF8
        originalText = ""
        originalBytes = null
        forceSaveAs = true
        showLoading(false)
        setEditorTextSafely("")
        dirty = false
        updateTitle()
        updateSaveUi()

        val draft = DraftStore.load(applicationContext, null)
        if (draft != null && draft.text.isNotEmpty()) {
            showDraftDialog(draft)
        } else {
            DraftStore.clear(applicationContext, null)
        }
        uiHandler.postDelayed({ if (!isFinishing) showKeyboard() }, 160)
    }

    private fun loadExistingDocument(uri: Uri) {
        showLoading(true)
        lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val (name, size) = DocumentLoader.queryMetadata(applicationContext, uri)
                    if (size > EDIT_MAX_BYTES) throw EditTooLargeException()
                    val bytes = DocumentLoader.loadBytes(applicationContext, uri)
                    if (bytes.size > EDIT_MAX_BYTES) throw EditTooLargeException()
                    val decoded = DocumentTextCodec.decode(bytes)
                    LoadedEdit(
                        name = name,
                        text = decoded.text,
                        bytes = bytes,
                        meta = decoded.meta,
                        draft = DraftStore.load(applicationContext, uri),
                        writable = DocumentWriter.canWrite(applicationContext, uri),
                    )
                }
            } catch (e: EditTooLargeException) {
                showLoading(false)
                Toast.makeText(this@EditorActivity, R.string.editor_too_large, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            } catch (e: DocumentTooLargeException) {
                showLoading(false)
                Toast.makeText(this@EditorActivity, R.string.file_too_large, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            } catch (e: Exception) {
                AppLog.e("Editor", "load failed uri=$uri", e)
                showLoading(false)
                Toast.makeText(this@EditorActivity, R.string.open_error, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            documentName = loaded.name.ifBlank { documentName }
            originalText = loaded.text
            originalBytes = loaded.bytes
            meta = loaded.meta
            setEditorTextSafely(loaded.text)
            dirty = false
            updateTitle()
            updateSaveUi()
            showLoading(false)

            val draft = loaded.draft
            if (draft != null && draft.text != loaded.text) {
                showDraftDialog(draft) {
                    showPostLoadNotices(loaded.writable)
                    uiHandler.postDelayed({ if (!isFinishing) showKeyboard() }, 160)
                }
            } else {
                if (draft != null) DraftStore.clear(applicationContext, uri)
                showPostLoadNotices(loaded.writable)
                uiHandler.postDelayed({ if (!isFinishing) showKeyboard() }, 160)
            }
        }
    }

    private fun showPostLoadNotices(writable: Boolean) {
        if (!writable) {
            forceSaveAs = true
            showReadOnlyDialog()
        } else if (meta.uncertain) {
            forceSaveAs = true
            Toast.makeText(
                this,
                R.string.editor_encoding_uncertain_toast,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private data class LoadedEdit(
        val name: String,
        val text: String,
        val bytes: ByteArray,
        val meta: DocumentCodecMeta,
        val draft: EditorDraft?,
        val writable: Boolean,
    )

    private class EditTooLargeException : IOException("文档超过编辑上限")

    private fun setEditorTextSafely(text: String) {
        suppressTextWatcher = true
        editText.setText(text)
        editText.setSelection(0)
        suppressTextWatcher = false
    }

    private fun showDraftDialog(draft: EditorDraft, afterDismiss: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(R.string.draft_found_title)
            .setMessage(R.string.draft_found_message)
            .setPositiveButton(R.string.restore_draft) { _, _ ->
                suppressTextWatcher = true
                editText.setText(draft.text)
                editText.setSelection(draft.text.length.coerceAtMost(editText.length()))
                suppressTextWatcher = false
                dirty = editText.text?.toString().orEmpty() != originalText
                updateTitle()
                updateSaveUi()
            }
            .setNegativeButton(R.string.discard_draft) { _, _ ->
                DraftStore.clear(applicationContext, documentUri)
            }
            .setOnDismissListener { afterDismiss?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun showReadOnlyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_read_only_title)
            .setMessage(R.string.editor_read_only_message)
            .setPositiveButton(R.string.reauthorize) { _, _ -> launchReauthorize() }
            .setNeutralButton(R.string.continue_read_only) { _, _ -> forceSaveAs = true }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun launchReauthorize() {
        val uri = documentUri ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        reauthorizeLauncher.launch(intent)
    }

    private fun persistReadWritePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // ignore
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                // 部分 provider 不支持持久写授权；本次会话仍可继续尝试写入。
            }
        }
    }

    // ------------------------------------------------------------------ 保存

    private fun save() {
        if (saving || loading) return
        val text = editText.text?.toString().orEmpty()

        if (forceSaveAs || documentUri == null || meta.uncertain) {
            pendingSaveAsUtf8 = computeSaveAsUtf8(text)
            if (!ensureSaveAsSizeAllowed(text)) return
            pendingCreateFinish = saveThenFinish
            launchCreateDocument()
            return
        }

        when (val encoded = DocumentTextCodec.encode(text, meta)) {
            is EncodeResult.Success -> {
                if (encoded.bytes.size > EDIT_MAX_BYTES) {
                    showEditorLimitDialog()
                } else {
                    performSave(documentUri!!, text, encoded.bytes, encoded.meta, saveThenFinish)
                }
            }
            is EncodeResult.Unmappable -> showSaveAsUtf8Dialog()
            is EncodeResult.Unsupported -> showSaveAsUtf8Dialog()
        }
    }

    private fun startSaveAs() {
        if (saving || loading) return
        val text = editText.text?.toString().orEmpty()
        pendingSaveAsUtf8 = computeSaveAsUtf8(text)
        if (!ensureSaveAsSizeAllowed(text)) return
        pendingCreateFinish = false
        launchCreateDocument()
    }

    /** 当前原文编码若无法表示编辑后的文本，另存为应自动降级为 UTF-8。 */
    private fun computeSaveAsUtf8(text: String): Boolean {
        if (meta.uncertain) return true
        return DocumentTextCodec.encode(text, meta) !is EncodeResult.Success
    }

    /** 「另存为」/新建首次保存前先检查编码后大小，避免选出位置后又因超限留下空文件。 */
    private fun ensureSaveAsSizeAllowed(text: String): Boolean {
        val candidate = if (pendingSaveAsUtf8 || meta.uncertain) {
            DocumentCodecMeta.DEFAULT_UTF8.copy(newline = meta.newline)
        } else {
            meta
        }
        val encoded = DocumentTextCodec.encode(text, candidate)
        val tooLarge = when (encoded) {
            is EncodeResult.Success -> encoded.bytes.size > EDIT_MAX_BYTES
            else -> {
                val utf8 = DocumentTextCodec.encode(
                    text,
                    DocumentCodecMeta.DEFAULT_UTF8.copy(newline = meta.newline),
                )
                utf8 is EncodeResult.Success && utf8.bytes.size > EDIT_MAX_BYTES
            }
        }
        if (tooLarge) {
            showEditorLimitDialog()
        }
        return !tooLarge
    }

    private fun launchCreateDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, suggestedFileName())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        createDocumentLauncher.launch(intent)
    }

    private fun performSave(
        target: Uri,
        text: String,
        bytes: ByteArray,
        savedMeta: DocumentCodecMeta,
        finishAfter: Boolean,
    ) {
        if (!::editText.isInitialized) return
        saving = true
        updateSaveUi()
        val oldUri = documentUri
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 先把当前内容写成应用私有草稿；即使随后 SAF 写入失败，内容也不会丢。
                DraftStore.save(applicationContext, oldUri, documentName, text, meta)
                val writeResult = DocumentWriter.write(applicationContext, target, bytes)
                val resolvedName = if (writeResult is WriteResult.Success) {
                    runCatching { DocumentLoader.queryMetadata(applicationContext, target).first }
                        .getOrDefault(documentName)
                } else {
                    documentName
                }
                Pair(writeResult, resolvedName)
            }
            saving = false
            updateSaveUi()
            val writeResult = result.first
            when (writeResult) {
                is WriteResult.Success -> {
                    DraftStore.clear(applicationContext, oldUri)
                    if (target.toString() != oldUri?.toString()) {
                        DraftStore.clear(applicationContext, target)
                    }
                    documentUri = target
                    meta = savedMeta
                    originalText = text
                    originalBytes = bytes
                    documentName = result.second.ifBlank { documentName }
                    forceSaveAs = false
                    pendingSaveAsUtf8 = false
                    pendingCreateFinish = false
                    dirty = false
                    updateTitle()
                    updateSaveUi()
                    if (previewAdapterUri != target) {
                        previewAdapter = null
                        previewAdapterUri = null
                    }
                    try {
                        RecentStore.recordOpened(applicationContext, target, documentName)
                    } catch (_: Exception) {
                        // 最近记录失败不影响保存结果。
                    }
                    Toast.makeText(this@EditorActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    // 无论是否立即返回都设置结果，保证用户随后手动返回时阅读页会重载。
                    setResult(Activity.RESULT_OK, Intent().setData(target))
                    if (finishAfter) {
                        finish()
                    } else if (previewMode) {
                        buildPreview(immediate = true)
                    }
                }
                is WriteResult.Failure -> {
                    pendingCreateFinish = false
                    showSaveFailedDialog(writeResult.message)
                }
            }
        }
    }

    private fun showSaveFailedDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.save_failed)
            .setMessage("${getString(R.string.save_failed_draft)}\n\n$message")
            .setPositiveButton(R.string.retry) { _, _ -> save() }
            .setNeutralButton(R.string.save_as) { _, _ ->
                val currentText = editText.text?.toString().orEmpty()
                pendingSaveAsUtf8 = computeSaveAsUtf8(currentText)
                pendingCreateFinish = saveThenFinish
                if (ensureSaveAsSizeAllowed(currentText)) {
                    launchCreateDocument()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> saveThenFinish = false }
            .setOnCancelListener { saveThenFinish = false }
            .show()
    }

    private fun showSaveAsUtf8Dialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.encoding_unmappable_title)
            .setMessage(R.string.encoding_unmappable_message)
            .setPositiveButton(R.string.save_as_utf8) { _, _ ->
                pendingSaveAsUtf8 = true
                if (!ensureSaveAsSizeAllowed(editText.text?.toString().orEmpty())) {
                    return@setPositiveButton
                }
                pendingCreateFinish = saveThenFinish
                launchCreateDocument()
            }
            .setNegativeButton(R.string.back_to_edit) { _, _ ->
                saveThenFinish = false
            }
            .setOnCancelListener { saveThenFinish = false }
            .show()
    }

    private fun showEditorLimitDialog() {
        saveThenFinish = false
        pendingCreateFinish = false
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_limit_title)
            .setMessage(R.string.editor_limit_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun suggestedFileName(): String {
        val base = documentName.ifBlank { getString(R.string.new_file_name) }
        return when {
            base.endsWith(".md", ignoreCase = true) ||
                base.endsWith(".markdown", ignoreCase = true) -> base
            base.contains('.') -> base.substringBeforeLast('.') + ".md"
            else -> "$base.md"
        }
    }

    private fun requestFinish() {
        if (saving) return
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_changes)
            .setMessage(R.string.unsaved_changes_message)
            .setPositiveButton(R.string.save_and_exit) { _, _ ->
                saveThenFinish = true
                save()
            }
            .setNeutralButton(R.string.discard_changes) { _, _ -> discardAndFinish() }
            .setNegativeButton(R.string.continue_editing, null)
            .show()
    }

    private fun discardAndFinish() {
        DraftStore.clear(applicationContext, documentUri)
        dirty = false
        saveThenFinish = false
        finish()
    }

    // ------------------------------------------------------------------ 预览

    private fun togglePreview() {
        previewMode = !previewMode
        if (previewMode) {
            previewButton.text = getString(R.string.edit_mode)
            editText.visibility = View.GONE
            previewRecycler.visibility = View.VISIBLE
            formatBar.visibility = View.GONE
            hideKeyboard()
            buildPreview(immediate = true)
        } else {
            previewButton.text = getString(R.string.preview)
            editText.visibility = View.VISIBLE
            previewRecycler.visibility = View.GONE
            previewEmpty.visibility = View.GONE
            formatBar.visibility = View.VISIBLE
            editText.requestFocus()
            showKeyboard()
        }
    }

    private fun schedulePreview(immediate: Boolean) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            if (!immediate) delay(300)
            val text = editText.text?.toString().orEmpty()
            if (text.isBlank()) {
                renderModel = null
                previewAdapter?.submitItems(emptyList())
                previewEmpty.text = getString(R.string.editor_preview_empty)
                previewEmpty.visibility = View.VISIBLE
                previewRecycler.visibility = View.GONE
                return@launch
            }
            try {
                val model = withContext(Dispatchers.Default) {
                    val root = previewParser.parse(text)
                    RenderPipeline().build(root)
                }
                renderModel = model
                ensurePreviewAdapter()
                previewAdapter?.submitItems(model.items)
                previewEmpty.text = getString(R.string.editor_preview_empty)
                previewEmpty.visibility = if (model.items.isEmpty()) View.VISIBLE else View.GONE
                previewRecycler.visibility = if (model.items.isEmpty()) View.GONE else View.VISIBLE
            } catch (t: Throwable) {
                AppLog.e("Editor", "preview failed", t)
                previewEmpty.visibility = View.VISIBLE
                previewEmpty.text = getString(R.string.doc_parse_failed)
                previewRecycler.visibility = View.GONE
            }
        }
    }

    private fun buildPreview(immediate: Boolean) {
        schedulePreview(immediate)
    }

    private fun ensurePreviewAdapter() {
        val baseUri = documentUri ?: Uri.EMPTY
        if (previewAdapter == null || previewAdapterUri != baseUri) {
            previewAdapter = ReaderAdapter(this, baseUri, this).also {
                it.setPalette(ThemeEngine.palette())
                previewRecycler.adapter = it
            }
            previewAdapterUri = baseUri
        }
    }

    override fun onLinkClick(url: String) {
        LinkSheetDialog.show(this, url)
    }

    // ------------------------------------------------------------------ 格式化工具栏

    private data class FormatAction(
        val label: String,
        val description: String,
        val command: (String, Int, Int) -> EditCommandResult,
    )

    private fun setupFormatActions() {
        val actions = listOf(
            FormatAction("B", getString(R.string.format_bold), MarkdownEditCommands::bold),
            FormatAction("I", getString(R.string.format_italic), MarkdownEditCommands::italic),
            FormatAction("H2", getString(R.string.format_heading)) { t, s, e ->
                MarkdownEditCommands.heading(t, s, e, 2)
            },
            FormatAction("❝", getString(R.string.format_quote), MarkdownEditCommands::quote),
            FormatAction("•", getString(R.string.format_unordered_list), MarkdownEditCommands::unorderedList),
            FormatAction("1.", getString(R.string.format_ordered_list), MarkdownEditCommands::orderedList),
            FormatAction("`", getString(R.string.format_inline_code), MarkdownEditCommands::inlineCode),
            FormatAction("```", getString(R.string.format_code_block), MarkdownEditCommands::fencedCode),
            FormatAction("🔗", getString(R.string.format_link), MarkdownEditCommands::link),
            FormatAction("―", getString(R.string.format_hr), MarkdownEditCommands::horizontalRule),
        )
        actions.forEach { action ->
            val button = TextView(this).apply {
                text = action.label
                contentDescription = action.description
                textSize = 15f
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = false
                isClickable = true
                setOnClickListener { applyFormat(action.command) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            }
            formatButtons.add(button)
            formatContainer.addView(button)
        }
        applyColors()
    }

    private fun applyFormat(command: (String, Int, Int) -> EditCommandResult) {
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val orderedStart = minOf(start, end)
        val orderedEnd = maxOf(start, end)
        val result = command(editable.toString(), orderedStart, orderedEnd)
        editable.replace(0, editable.length, result.text)
        val safeStart = result.selectionStart.coerceIn(0, result.text.length)
        val safeEnd = result.selectionEnd.coerceIn(safeStart, result.text.length)
        editText.setSelection(safeStart, safeEnd)
        editText.requestFocus()
    }

    // ------------------------------------------------------------------ 草稿

    private fun scheduleDraft() {
        uiHandler.removeCallbacks(draftRunnable)
        uiHandler.postDelayed(draftRunnable, 3000)
    }

    private fun persistDraftNow() {
        if (saving || !dirty) return
        val uri = documentUri
        val name = documentName
        val text = editText.text?.toString().orEmpty()
        val draftMeta = meta
        Thread {
            try {
                DraftStore.save(applicationContext, uri, name, text, draftMeta)
            } catch (_: Exception) {
                // 草稿写入失败不阻塞编辑。
            }
        }.start()
    }

    // ------------------------------------------------------------------ 主题/UI

    private fun updateTitle() {
        titleView.text = if (dirty) "• $documentName" else documentName
    }

    private fun updateSaveUi() {
        saveButton.isEnabled = !saving && !loading
        saveAsButton.isEnabled = !saving && !loading
        previewButton.isEnabled = !saving && !loading
        saveButton.text = if (saving) getString(R.string.saving) else getString(R.string.save)
    }

    private fun showLoading(show: Boolean) {
        loading = show
        loadingView.visibility = if (show) View.VISIBLE else View.GONE
        errorView.visibility = View.GONE
        editText.isEnabled = !show
        if (!previewMode) {
            formatBar.visibility = if (show) View.GONE else View.VISIBLE
        }
        updateSaveUi()
    }

    private fun applyColors() {
        val palette = ThemeEngine.palette()
        ThemeApplier.applyWindow(this, palette)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        formatBar.setBackgroundColor(palette.card)
        titleView.setTextColor(palette.textPrimary)
        previewButton.setTextColor(palette.textPrimary)
        saveAsButton.setTextColor(palette.textPrimary)
        saveButton.setTextColor(palette.accent)
        editText.setTextColor(palette.textPrimary)
        editText.setHintTextColor(palette.textSecondary)
        previewEmpty.setTextColor(palette.textSecondary)
        errorView.setTextColor(palette.textSecondary)
        findViewById<ImageButton>(R.id.editor_back).imageTintList =
            android.content.res.ColorStateList.valueOf(palette.textPrimary)
        previewAdapter?.setPalette(palette)
        formatButtons.forEach { button ->
            button.setTextColor(palette.textPrimary)
            button.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(palette.card)
                setStroke(dp(1), palette.divider)
            }
        }
    }

    private fun showKeyboard() {
        uiHandler.postDelayed({
            if (isFinishing || previewMode) return@postDelayed
            editText.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_NEW = "extra_new"

        fun intent(context: Context, uri: Uri, name: String? = null): Intent =
            Intent(context, EditorActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_NAME, name)

        fun newDocumentIntent(context: Context): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_NEW, true)
    }
}
