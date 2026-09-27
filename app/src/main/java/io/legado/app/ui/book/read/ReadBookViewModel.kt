package io.legado.app.ui.book.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ImageProvider
import io.legado.app.model.localBook.LocalBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * 阅读界面数据处理
 */
class ReadBookViewModel(application: Application) : BaseViewModel(application) {
    val permissionDenialLiveData = MutableLiveData<Int>()
    var isInitFinish = false
    var searchContentQuery = ""
    var searchResultList: List<SearchResult>? = null
    var searchResultIndex: Int = 0
    private var resourceRefreshCoroutine: Coroutine<*>? = null
    val resourceRefreshing = MutableLiveData(false)

    init {
        AppConfig.detectClickArea()
    }

    fun initReadBookConfig(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl")
        val book = when {
            bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
            else -> appDb.bookDao.getBook(bookUrl)
        } ?: return
        ReadBook.upReadBookConfig(book)
    }

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            ReadBook.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            val hasHighlightTarget =
                intent.hasExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)
            ReadBook.chapterChanged =
                intent.getBooleanExtra("chapterChanged", false) || hasHighlightTarget
            val bookUrl = intent.getStringExtra("bookUrl")
            val book = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
            } ?: ReadBook.book
            when {
                book != null -> initBook(book)
                else -> {
                    ReadBook.upMsg(context.getString(R.string.no_book))
                }
            }
            val index = intent.getIntExtra("index", -1)
            val chapterPos = intent.getIntExtra("chapterPos", -1)
            // 「看原文」: 从通知/迷你条进入, 定位到朗读进度(不动阅读进度)
            val openAloudPos = intent.getBooleanExtra("openAloudPos", false)
            val aloudChapterIndex = intent.getIntExtra("aloudChapterIndex", -1)
            val aloudChapterPos = intent.getIntExtra("aloudChapterPos", -1)
            val highlightLayoutTitleLength = intent.takeIf { hasHighlightTarget }
                ?.getIntExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, -1)
            val highlightAnchorText = intent.takeIf { hasHighlightTarget }
                ?.getStringExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)
            if (index >= 0 && chapterPos >= 0) { //从目录定位正文，有进度传递
                if (hasHighlightTarget) {
                    intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)
                    intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)
                    intent.removeExtra("index")
                    intent.removeExtra("chapterPos")
                }
                ReadBook.saveCurrentBookProgress() //启用恢复进度提示
                openChapter(index, chapterPos, highlightLayoutTitleLength, highlightAnchorText)
            } else if (openAloudPos && aloudChapterIndex >= 0) {
                // 从通知「看原文」进入: 跳到朗读所在位置, 阅读进度 durChapter* 不动
                intent.removeExtra("openAloudPos")
                intent.removeExtra("aloudChapterIndex")
                intent.removeExtra("aloudChapterPos")
                ReadBook.openAloudPosition(
                    aloudChapterIndex,
                    aloudChapterPos.coerceAtLeast(0),
                )
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            val msg = "初始化数据失败\n${it.localizedMessage}"
            ReadBook.upMsg(msg)
        }.onFinally {
            ReadBook.saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            ReadBook.upData(book)
        } else {
            // 换书隔离: 朗读正在读的是另一本书时, 只让阅读页脱离跟随,
            // 朗读服务保持不动, 继续静默朗读原书; 绝不能把朗读内容换成新书。
            detachAloudFollowIfReadingOtherBook(book)
            ReadBook.resetData(book)
        }
        isInitFinish = true
        if (book.isLocal && !checkLocalBookFileExist(book)) {
            return
        }
        if ((ReadBook.chapterSize == 0 || book.isLocalModified()) && !loadChapterListAwait(book)) {
            return
        }
        ReadBook.upMsg(null)
        if (!isSameBook) {
            ReadBook.loadContent(
                resetPageOffset = true,
                readPositionVersion = ReadBook.callBack?.readPositionVersion(),
            )
        } else {
            ReadBook.loadOrUpContent()
        }
        ReadBook.chapterChanged = false
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            LocalBook.getBookInputStream(book).use {}
            return true
        } catch (e: Throwable) {
            ReadBook.upMsg("打开本地书籍出错: ${e.localizedMessage}")
            if (e is SecurityException || e is FileNotFoundException) {
                permissionDenialLiveData.postValue(0)
            }
            return false
        }
    }

    /**
     * 加载目录
     */
    fun loadChapterList(book: Book) {
        execute {
            if (loadChapterListAwait(book)) {
                ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        if (book.isLocal) {
            kotlin.runCatching {
                LocalBook.getChapterList(book).let {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    book.update()
                    ReadBook.onChapterListUpdated(book)
                }
                return true
            }.onFailure {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        permissionDenialLiveData.postValue(1)
                    }

                    else -> {
                        ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                    }
                }
                return false
            }
        }
        return true
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
        pdfPageIndex: Int? = null,
        success: (() -> Unit)? = null
    ) {
        ReadBook.openChapter(
            index,
            durChapterPos,
            highlightLayoutTitleLength = highlightLayoutTitleLength,
            highlightAnchorText = highlightAnchorText,
            pdfPageIndex = pdfPageIndex,
            success = success
        )
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadBook.book
        Coroutine.async {
            book?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    fun refreshContentAll(book: Book) {
        execute {
            BookHelp.clearCache(book)
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存内容
     */
    fun saveContent(book: Book, content: String) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.saveText(book, chapter, content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    /**
     * 反转内容
     */
    fun reverseContent(book: Book) {
        val chapterIndex = ReadBook.durChapterIndex
        execute {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@execute
            if (BookHelp.reverseContent(book, chapter) &&
                ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapterIndex) {
                ReadBook.loadContent(chapterIndex, resetPageOffset = false)
            }
        }
    }

    /**
     * 内容搜索跳转
     */
    fun searchResultPositions(
        textChapter: TextChapter,
        searchResult: SearchResult
    ): Array<Int> {
        // calculate search result's pageIndex
        val pages = textChapter.pages
        val content = textChapter.getContent()
        var queryLength = searchContentQuery.length

        var index: Int
        if (searchResult.isRegex) {
            val regex = Regex(searchContentQuery)
            val matches = regex.findAll(content)
            val match = matches.elementAtOrNull(searchResult.resultCountWithinChapter)
            queryLength = match?.value?.length ?: 0
            index = match?.range?.first ?: -1
        } else {
            var count = 0
            index = content.indexOf(searchContentQuery)
            while (count != searchResult.resultCountWithinChapter) {
                index = content.indexOf(searchContentQuery, index + queryLength)
                count += 1
            }
        }
        val contentPosition = index
        var pageIndex = 0
        var length = pages[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < pages.size) {
            pageIndex += 1
            length += pages[pageIndex].text.length
        }

        // calculate search result's lineIndex
        val currentPage = pages[pageIndex]
        val curTextLines = currentPage.lines
        var lineIndex = 0
        var curLine = curTextLines[lineIndex]
        length = length - currentPage.text.length + curLine.text.length
        if (curLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
            lineIndex += 1
            curLine = curTextLines[lineIndex]
            length += curLine.text.length
            if (curLine.isParagraphEnd) length++
        }

        // charIndex
        val currentLine = currentPage.lines[lineIndex]
        var curLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) curLineLength++
        length -= curLineLength

        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        // change line
        if ((charIndex + queryLength) > curLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        // changePage
        if ((lineIndex + addLine + 1) > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        return arrayOf(pageIndex, lineIndex, charIndex, addLine, charIndex2, queryLength)
    }

    /**
     * 翻转删除重复标题
     */
    fun reverseRemoveSameTitle() {
        execute {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            BookHelp.setRemoveSameTitle(
                book, textChapter.chapter, !textChapter.sameTitleRemoved
            )
            ReadBook.loadContent(ReadBook.durChapterIndex)
        }
    }

    /**
     * 刷新图片
     */
    fun refreshImage(src: String) {
        val book = ReadBook.book ?: return
        execute {
            ImageProvider.clearImage(book, src)
        }.onSuccess {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.loadContent(false)
            }
        }
    }

    /**
     * 保存图片
     */
    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadBook.book ?: return
        execute {
            val image = BookHelp.getImage(book, src)
            FileInputStream(image).use { input ->
                if (uri.isContentScheme()) {
                    DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                        val imageDoc = DocumentUtils.createFileIfNotExist(doc, image.name)!!
                        context.contentResolver.openOutputStream(imageDoc.uri)!!.use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val dir = File(uri.path ?: uri.toString())
                    val file = FileUtils.createFileIfNotExist(dir, image.name)
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }.onError {
            context.toastOnUi("保存图片出错\n${it.localizedMessage}")
        }
    }

    /**
     * 替换规则变化
     */
    fun replaceRuleChanged() {
        execute {
            ReadBook.book?.let {
                ContentProcessor.get(it.name, it.origin).upReplaceRules()
                ReadBook.clearTextChapter()
                ReadBook.loadContent(resetPageOffset = false)
            }
        }
    }

    /**
     * 换书时保证朗读不被牵连。
     *
     * 朗读服务自持 `aloudBook` 快照; 当它正在读的书与即将打开的书不是同一本时:
     * - 让阅读页脱离跟随(进而在新书上显示行内「回到朗读位置｜从此处朗读」),
     * - 但**不停止、不重启**朗读服务, 朗读继续静默读原书。
     *
     * 服务侧另有 `allowBookSwitch = false` 的二次防线: 换书后 `loadContent` 完成
     * 触发的隐式会话重启会被拒绝, 避免朗读内容被换成新书。
     */
    private fun detachAloudFollowIfReadingOtherBook(newBook: Book) {
        if (!BaseReadAloudService.isRun) return
        val aloudBookUrl = BaseReadAloudService.aloudBookSnapshot?.bookUrl ?: return
        if (aloudBookUrl == newBook.bookUrl) return
        ReadAloud.detachReadAloudFollow()
    }

    override fun onCleared() {
        super.onCleared()
        if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.stop(context)
        }
    }

}
