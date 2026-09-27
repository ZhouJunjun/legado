package io.legado.app.ui.book.toc


import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.update
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocalTxt
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TocViewModel(application: Application) : BaseViewModel(application) {
    var bookUrl: String = ""
    var bookData = MutableLiveData<Book>()
    var chapterListCallBack: ChapterListCallBack? = null
    var bookMarkCallBack: BookmarkCallBack? = null
    var highlightCallBack: HighlightCallBack? = null
    var searchKey: String? = null

    fun initBook(bookUrl: String) {
        this.bookUrl = bookUrl
        execute {
            appDb.bookDao.getBook(bookUrl)?.let {
                bookData.postValue(it)
            }
        }
    }

    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) {
        execute {
            book.update()
            // 必须先删旧章节再重新分段：否则 getChapterList() 内部的 getWordCount()
            // 会从 chapters 表读到上一次留下的旧字数，把它当成新值写回，错误数字被固化。
            appDb.bookChapterDao.delByBook(book.bookUrl)
            LocalBook.getChapterList(book).let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
                book.update()
                ReadBook.onChapterListUpdated(book)
                bookData.postValue(book)
            }
        }.onSuccess {
            complete.invoke(null)
        }.onError {
            complete.invoke(it)
        }
    }

    /**
     * 自愈历史上被固化的错误章字数。
     *
     * chapters 表里的章字数是「上一次分段」的产物。历史版本里 getWordCount() 会无条件用它
     * 覆盖 analyze() 刚算出的正确值，而 upBookTocRule() 又是「先算后删」，于是错误数字被
     * 当成新值反复写回、永久固化 —— 典型表现就是目录里某一章显示成"全书剩余内容"的字数。
     * 又因为章节文件名是「序号 + 标题MD5」，重新分段时新旧章节一一对应，所以光靠重新分段
     * 也修不好，只能在打开目录时自检一次并重建。
     *
     * 判定依据: 本地 TXT 中超过 TextFile.maxLengthWithToc(102400 字节) 的章节一定会被拆成
     * 子章，而任何编码下「字节数 >= 字符数」，因此字符数超过 102400 的章节，其字数必定是
     * 历史脏数据。
     */
    suspend fun repairLocalChapterWordCount(book: Book) {
        if (!book.isLocalTxt || !book.getSplitLongChapter()) return
        val stored = withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        }
        if (stored.isEmpty()) return
        if (stored.none { parseStoredWordCount(it.wordCount) > localChapterWordLimit }) return
        // 同一本书本次运行只重建一次，避免无法修复时反复做无用功。
        if (!repairedBookUrls.add(book.bookUrl)) return
        try {
            withContext(Dispatchers.IO) {
                book.update()
                appDb.bookChapterDao.delByBook(book.bookUrl)
                LocalBook.getChapterList(book).let { chapters ->
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    book.update()
                    ReadBook.onChapterListUpdated(book)
                }
            }
            bookData.postValue(book)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 重建失败(例如源文件已被移走)时把原章节放回去，避免目录变空。
            // 先清掉可能已写入的新章节，避免新旧两套章节同时留在目录里。
            runCatching {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*stored.toTypedArray())
            }
        }
    }

    private val repairedBookUrls = hashSetOf<String>()

    /** TextFile.maxLengthWithToc = 102400 字节; 任何编码下字节数 >= 字符数。 */
    private val localChapterWordLimit = 102400

    /** 把 "1.2万字" / "3456字" 还原成整数, 解析不出来返回 0。 */
    private fun parseStoredWordCount(value: String?): Int {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val cut = text.indexOfFirst { it == '万' || it == '字' }
        val head = (if (cut < 0) text else text.substring(0, cut)).replace(',', '.').trim()
        val number = head.toDoubleOrNull() ?: return 0
        return if (text.contains('万')) (number * 10000).toInt() else number.toInt()
    }

    fun reverseToc(success: (book: Book) -> Unit) {
        execute {
            bookData.value?.apply {
                if (isPdf || isEpub) {
                    setReverseToc(!getReverseToc())
                    listOf(ReadBook.book)
                        .filter { it?.bookUrl == bookUrl }
                        .forEach { it?.setReverseToc(getReverseToc()) }
                    appDb.bookDao.updateReverseToc(bookUrl, getReverseToc())
                    return@apply
                }
                // Keep source parsing and index-based reading/cache identities unchanged.
                setReverseTocDisplay(!getReverseTocDisplay())
                listOf(ReadBook.book)
                    .filter { it?.bookUrl == bookUrl }
                    .forEach { it?.setReverseTocDisplay(getReverseTocDisplay()) }
                appDb.bookDao.updateReverseTocDisplay(bookUrl, getReverseTocDisplay())
            }
        }.onSuccess {
            it?.let(success)
        }
    }

    fun setTocExpanded(expanded: Boolean) {
        val book = bookData.value ?: return
        book.setTocExpanded(expanded)
        updateActiveReaderBooks(book.bookUrl, expanded)
        chapterListCallBack?.upChapterList(
            searchKey,
            resetCollapse = true,
            replaceAll = true,
        )
        globalExecutor.execute {
            runCatching {
                appDb.bookDao.updateTocExpanded(book.bookUrl, expanded)
            }.onFailure {
            }
        }
    }

    private fun updateActiveReaderBooks(bookUrl: String, expanded: Boolean) {
        listOf(ReadBook.book)
            .filter { it?.bookUrl == bookUrl }
            .forEach { it?.setTocExpanded(expanded) }
    }

    fun startChapterListSearch(newText: String?) {
        chapterListCallBack?.upChapterList(newText)
    }

    fun startBookmarkSearch(newText: String?) {
        bookMarkCallBack?.upBookmark(newText)
    }

    fun startHighlightSearch(newText: String?) {
        highlightCallBack?.upHighlight(newText)
    }

    fun upChapterListAdapter() {
        chapterListCallBack?.upAdapter()
    }

    fun saveBookmark(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.json"
            val doc = FileDoc.fromUri(treeUri, true)
            doc.createFileIfNotExist(fileName).writeText(
                GSON.toJson(
                    appDb.bookmarkDao.getByBook(book.name, book.author)
                )
            )
        }.onError {
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    fun saveBookmarkMd(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.md"
            val treeDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = treeDoc.createFileIfNotExist(fileName)
                .openOutputStream()
                .getOrThrow()
            fileDoc.use { outputStream ->
                outputStream.write("## ${book.name} ${book.author}\n\n".toByteArray())
                appDb.bookmarkDao.getByBook(book.name, book.author).forEach {
                    outputStream.write("#### ${it.chapterName}\n\n".toByteArray())
                    outputStream.write("###### 原文\n ${it.bookText}\n\n".toByteArray())
                    outputStream.write("###### 摘要\n ${it.content}\n\n".toByteArray())
                }
            }
        }.onError {
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    interface ChapterListCallBack {
        fun upChapterList(
            searchKey: String?,
            resetCollapse: Boolean = false,
            replaceAll: Boolean = false,
        )

        fun clearDisplayTitle()

        fun upAdapter()
    }

    interface BookmarkCallBack {
        fun upBookmark(searchKey: String?)
    }

    interface HighlightCallBack {
        fun upHighlight(searchKey: String?)
    }
}
