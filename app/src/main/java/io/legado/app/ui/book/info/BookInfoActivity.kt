package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.textclassifier.TextClassifier
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.book.addType
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.readProgress
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.barForegroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.BookCover
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(toolBarTheme = Theme.Dark, showOpenMenuIcon = false),
    GroupSelectDialog.CallBack {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            readFromChapter(
                index = it[0] as Int,
                pos = it[1] as Int,
                changed = it[2] as Boolean,
                volumeIndex = it[3] as Int,
                chapterInVolumeIndex = it[4] as Int,
                highlightLayoutTitleLength =
                    (it[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int)
                        .takeUnless { titleLength ->
                            titleLength == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                        },
                highlightAnchorText =
                    (it[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String)
                        .takeIf(String::isNotEmpty),
            )
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private var editMenuItem: MenuItem? = null
    private val book get() = viewModel.getBook(false)

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()
    private var isIntroTextViewAttached = false
    private var introContent: String? = null
    private var introExpanded = false
    private var introCanCollapse = false
    private var introRenderGeneration = 0
    private var introRenderJob: Job? = null
    private val introTextViewDelegate = lazy {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view.onTextLayoutChanged = {
            updateIntroOverflow()
        }
        view
    }
    private val introTextView by introTextViewDelegate

    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()  //8是为了文字对齐额外的右边距
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                // 在线书源的可点击按钮已随书源子系统移除，保留占位以兼容 <usehtml> 正文渲染
            }
        })
    }

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.refreshLayout?.setColorSchemeColors(accentColor)
        binding.refreshProgressBar.secondColor = accentColor
        binding.arcView?.setBgColor(backgroundColor)
        binding.llInfo.setBackgroundColor(backgroundColor)
        binding.ivCoverC.setCardBackgroundColor(backgroundColor)
        binding.flAction.setBackgroundColor(bottomBackground)
        binding.vwBg.applyNavigationBarPadding()
        // 底部操作栏是**栏位**("加入书架"按钮就在这条栏上): 前景色走
        // 「顶栏底栏文字与图标颜色」(用户 2026-09-30: 再检查同类所有顶栏、底栏的图标颜色)。
        binding.tvShelf.setTextColor(barForegroundColor)
        binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) {
            upLoading(viewModel.loadingData.value == true, it)
        }
        viewModel.loadingData.observe(this) { isLoading ->
            binding.refreshProgressBar.isAutoLoading = isLoading
            if (isLoading) {
                upLoading(true)
            } else {
                viewModel.chapterListData.value?.let { upLoading(false, it) }
            }
        }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
        initViewEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() ?: true
        menu.findItem(R.id.menu_split_long_chapter)?.isVisible =
            viewModel.bookData.value?.isLocalTxt ?: false
        menu.findItem(R.id.menu_delete_alert)?.isChecked =
            LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_edit -> {
                viewModel.getBook()?.let {
                    infoEditResult.launch {
                        putExtra("bookUrl", it.bookUrl)
                    }
                }
            }

            R.id.menu_share_it -> {
                viewModel.getBook()?.let {
                    val bookJson = GSON.toJson(it)
                    val shareStr = "${it.bookUrl}#$bookJson"
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra(Intent.EXTRA_TEXT, shareStr)
                    intent.type = "text/plain"
                    startActivity(Intent.createChooser(intent, it.name))
                }
            }

            R.id.menu_refresh -> {
                refreshBook()
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_clear_cache -> viewModel.getBook()?.let {
                viewModel.clearCache(it)
            }

            R.id.menu_split_long_chapter -> {
                upLoading(true)
                viewModel.getBook()?.let {
                    it.setSplitLongChapter(!item.isChecked)
                    viewModel.loadBookInfo(it, false)
                }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isIntroTextViewAttached && ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === introTextView && introTextView.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun showBook(book: Book) = binding.run {
        showCover(book)
        tvName.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        showBookIntro(book)
        llToc.visible()
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro.isNullOrBlank()) {
            introContent = null
            introExpanded = false
            introCanCollapse = false
            introRenderGeneration++
            introRenderJob?.cancel()
            binding.tvIntroContainer.removeAllViews()
            isIntroTextViewAttached = false
            binding.tvIntroContainer.gone()
            updateIntroToggle()
            return
        }
        if (intro != introContent) {
            introContent = intro
            introExpanded = true
            introCanCollapse = false
        }
        val renderGeneration = ++introRenderGeneration
        introRenderJob?.cancel()
        binding.tvIntroContainer.visible()
        val tvIntro = prepareIntroTextView()
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                null,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it))
                },
                imgOnClickListener = {
                }
            )
            scheduleIntroOverflowCheck(renderGeneration)
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val mark = intro.substring(4, lastIndex)
            tvIntro.text = null
            introRenderJob = lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@BookInfoActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                if (renderGeneration != introRenderGeneration) return@launch
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source))
                    }
                )
                scheduleIntroOverflowCheck(renderGeneration)
            }
        } else {
            setPlainBookIntro(tvIntro, intro)
            scheduleIntroOverflowCheck(renderGeneration)
        }
    }

    private fun setPlainBookIntro(textView: ScrollTextView, intro: String) {
        val ranges = introIndentRanges(intro)
        if (ranges.isEmpty()) {
            textView.text = intro
            return
        }
        val indentWidth = textView.paint.measureText("　　").roundToInt().coerceAtLeast(1)
        textView.text = SpannableString(intro).apply {
            ranges.forEach { range ->
                setSpan(
                    LeadingMarginSpan.Standard(indentWidth, 0),
                    range.start,
                    range.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun prepareIntroTextView(): ScrollTextView {
        val tvIntro = introTextView
        if (!isIntroTextViewAttached) {
            binding.tvIntroContainer.removeAllViews()
            tvIntro.text = null
            binding.tvIntroContainer.addView(tvIntro)
            isIntroTextViewAttached = true
        }
        applyIntroCollapseState(tvIntro)
        return tvIntro
    }

    private fun applyIntroCollapseState(tvIntro: ScrollTextView = introTextView) {
        tvIntro.internalScrollEnabled = false
        tvIntro.maxLines = if (introExpanded) Int.MAX_VALUE else BookIntroCollapse.COLLAPSED_LINES
        tvIntro.maxMeasuredHeight = if (introExpanded) {
            null
        } else {
            collapsedIntroHeight(tvIntro)
        }
        tvIntro.ellipsize = if (introExpanded) null else TextUtils.TruncateAt.END
        if (!introExpanded) {
            tvIntro.scrollTo(0, 0)
        }
        updateIntroToggle()
        tvIntro.requestLayout()
    }

    private fun collapsedIntroHeight(tvIntro: ScrollTextView): Int =
        tvIntro.lineHeight * BookIntroCollapse.COLLAPSED_LINES +
            tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom

    private fun scheduleIntroOverflowCheck(renderGeneration: Int) {
        introTextView.post {
            if (renderGeneration == introRenderGeneration &&
                lifecycle.currentState != Lifecycle.State.DESTROYED
            ) {
                updateIntroOverflow()
            }
        }
    }

    private fun updateIntroOverflow() {
        if (!isIntroTextViewAttached) return
        val tvIntro = introTextView
        val textLayout = tvIntro.layout ?: return
        val lineCount = textLayout.lineCount
        if (lineCount <= 0) return
        val lastLine = lineCount - 1
        val collapsedHeight = collapsedIntroHeight(tvIntro)
        val canCollapse = tvIntro.isMeasuredHeightLimited || BookIntroCollapse.hasOverflow(
            expanded = introExpanded,
            lineCount = lineCount,
            lastLineEllipsisCount = textLayout.getEllipsisCount(lastLine),
            lastLineEnd = textLayout.getLineEnd(lastLine),
            textLength = tvIntro.text.length,
            contentHeight = textLayout.height +
                tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom,
            collapsedContentHeight = collapsedHeight,
        )
        if (introCanCollapse != canCollapse) {
            introCanCollapse = canCollapse
            updateIntroToggle()
        }
    }

    private fun updateIntroToggle() = binding.tvIntroToggle.run {
        if (!introCanCollapse || !isIntroTextViewAttached) {
            gone()
            return@run
        }
        setText(
            if (introExpanded) {
                R.string.book_intro_collapse
            } else {
                R.string.book_intro_expand
            }
        )
        visible()
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = try {
                        FileDoc.fromUri(book.getLocalUri(), false).size
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        0L
                    }
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                lbKind.setLabels(kinds)
            }
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false) {
            if (!AppConfig.isEInkMode) {
                BookCover.loadBlur(
                    this,
                    book.getDisplayCover(),
                    false,
                    book.getCoverSourceOrigin()
                )
                    .into(binding.bgBook)
            }
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                binding.tvToc.text = getString(
                    R.string.toc_s,
                    getString(R.string.error_load_toc)
                )
            }

            else -> {
                book?.let {
                    val tocTitle = resolveBookInfoTocTitle(
                        it.durChapterTitle,
                        it.durChapterIndex,
                        chapterList,
                    ) ?: getString(R.string.no_last_chapter)
                    val readStatus = resolveBookInfoReadProgress(it)?.let { percent ->
                        getString(R.string.read_y, "$percent%")
                    }
                    binding.tvToc.text = getString(
                        R.string.toc_s,
                        listOfNotNull(tocTitle, readStatus).joinToString("  ·  "),
                    )
                }
            }
        }
    }

    private fun upTvBookshelf() {
        if (viewModel.inBookshelf) {
            binding.tvShelf.text = getString(R.string.remove_from_bookshelf)
        } else {
            binding.tvShelf.text = getString(R.string.add_to_bookshelf)
        }
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            if (it.isNullOrEmpty()) {
                binding.tvGroup.text = getString(R.string.group_s, getString(R.string.no_group))
            } else {
                binding.tvGroup.text = getString(R.string.group_s, it)
            }
        }
    }

    private fun initViewEvent() = binding.run {
        tvIntroToggle.setOnClickListener {
            if (!introCanCollapse || !isIntroTextViewAttached) return@setOnClickListener
            introExpanded = !introExpanded
            applyIntroCollapseState()
            scheduleIntroOverflowCheck(introRenderGeneration)
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path, isBook = true))
            }
            true
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { book ->
                readBook(book)
            }
        }
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else {
                    viewModel.addToBookshelf {
                        upTvBookshelf()
                    }
                }
            }
        }
        tvTocView.setOnClickListener {
            if (viewModel.chapterListData.value.isNullOrEmpty()) {
                toastOnUi(R.string.chapter_list_empty)
                return@setOnClickListener
            }
            val book = viewModel.getBook(false)
            if (book == null) {
                toastOnUi(R.string.book_not_exist)
                return@setOnClickListener
            }
            if (!viewModel.inBookshelf) {
                viewModel.saveBook(book) { //点击目录会保存book
                    viewModel.saveChapterList {
                        openChapterList()
                    }
                }
            } else {
                openChapterList()
            }
        }
        tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        refreshLayout?.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var deleteOriginalCheckBox: CheckBox? = null
                    if (book.isLocal) {
                        deleteOriginalCheckBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(deleteOriginalCheckBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        if (deleteOriginalCheckBox != null) {
                            LocalConfig.deleteBookOriginal = deleteOriginalCheckBox.isChecked
                        }
                        finishDeleteBook(book, LocalConfig.deleteBookOriginal)
                    }
                    noButton()
                }
            } else {
                finishDeleteBook(book, LocalConfig.deleteBookOriginal)
            }
        }
    }

    private fun finishDeleteBook(book: Book, deleteOriginal: Boolean) {
        viewModel.delBook(deleteOriginal) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun readFromChapter(
        index: Int,
        pos: Int,
        changed: Boolean,
        volumeIndex: Int,
        chapterInVolumeIndex: Int,
        highlightLayoutTitleLength: Int?,
        highlightAnchorText: String?,
    ) {
        viewModel.getBook(false)?.let { book ->
            val deferHighlightPosition = highlightLayoutTitleLength != null &&
                !book.isAudio && !book.isVideo &&
                (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
            if (!deferHighlightPosition) {
                book.durChapterIndex = index
                book.durChapterPos = pos
                book.durVolumeIndex = volumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
            }
            chapterChanged = changed
            if (!viewModel.inBookshelf) {
                book.addType(BookType.notShelf)
                lifecycleScope.launch {
                    withContext(IO) {
                        book.save()
                    }
                    viewModel.saveChapterList {
                        startReadActivity(
                            book,
                            index.takeIf { deferHighlightPosition },
                            pos.takeIf { deferHighlightPosition },
                            highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                            highlightAnchorText.takeIf { deferHighlightPosition }
                        )
                    }
                }
            } else {
                lifecycleScope.launch {
                    withContext(IO) {
                        book.update()
                    }
                    startReadActivity(
                        book,
                        index.takeIf { deferHighlightPosition },
                        pos.takeIf { deferHighlightPosition },
                        highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                        highlightAnchorText.takeIf { deferHighlightPosition }
                    )
                }
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(
        book: Book,
        highlightIndex: Int? = null,
        highlightChapterPos: Int? = null,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
    ) {
        readBookResult.launch(
            Intent(
                this,
                ReadBookActivity::class.java
            ).apply {
                putExtra("bookUrl", book.bookUrl)
                putExtra("inBookshelf", viewModel.inBookshelf)
                putExtra("chapterChanged", chapterChanged)
                if (highlightIndex != null &&
                    highlightChapterPos != null &&
                    highlightLayoutTitleLength != null
                ) {
                    putExtra("index", highlightIndex)
                    putExtra("chapterPos", highlightChapterPos)
                    putExtra(
                        TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                        highlightLayoutTitleLength
                    )
                    highlightAnchorText?.let {
                        putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it)
                    }
                }
            }
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

    override fun onDestroy() {
        introRenderJob?.cancel()
        if (introTextViewDelegate.isInitialized()) {
            introTextView.onTextLayoutChanged = null
        }
        super.onDestroy()
    }

}

internal fun resolveBookInfoTocTitle(
    storedTitle: String?,
    currentIndex: Int,
    chapters: List<BookChapter>,
): String? {
    return storedTitle?.takeIf { it.isNotBlank() }
        ?: (chapters.getOrNull(currentIndex) ?: chapters.lastOrNull())
            ?.getDisplayTitle(chineseConvert = false)
            ?.takeIf { it.isNotBlank() }
}

internal fun resolveBookInfoReadProgress(book: Book): Int? {
    if (book.totalChapterNum <= 1) return null
    return book.readProgress()?.let { (it * 100).roundToInt() }
}
