package io.legado.app.ui.book.info

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.removeType
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope

internal class BookInfoNetworkLoadingCounter(
    private val onLoadingChanged: (Boolean) -> Unit,
) {
    private var activeLoads = 0

    @Synchronized
    fun begin() {
        if (activeLoads++ == 0) onLoadingChanged(true)
    }

    @Synchronized
    fun end() {
        check(activeLoads > 0) { "No active network load" }
        if (--activeLoads == 0) onLoadingChanged(false)
    }
}

class BookInfoViewModel(application: Application) : BaseViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val chapterListData = MutableLiveData<List<BookChapter>>()
    var inBookshelf = false
    val waitDialogData = MutableLiveData<Boolean>()
    val loadingData = MutableLiveData<Boolean>()
    private val networkLoadingCounter = BookInfoNetworkLoadingCounter(loadingData::postValue)
    val actionLive = MutableLiveData<String>()

    private fun <T> Coroutine<T>.trackNetworkLoading(): Coroutine<T> = apply {
        networkLoadingCounter.begin()
        invokeOnCompletion { networkLoadingCounter.end() }
    }

    fun initData(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            val bookUrl = intent.getStringExtra("bookUrl") ?: ""
            appDb.bookDao.getBook(name, author)?.let {
                inBookshelf = !it.isNotShelf
                upBook(it)
                return@execute
            }
            if (bookUrl.isNotBlank()) {
                appDb.bookDao.getBook(bookUrl)?.let {
                    inBookshelf = !it.isNotShelf
                    upBook(it)
                    return@execute
                }
            }
            throw NoStackTraceException("未找到书籍")
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun upBook(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            appDb.bookDao.getBook(name, author)?.let { book ->
                upBook(book)
            }
        }
    }

    private fun upBook(book: Book) {
        execute {
            bookData.postValue(book)
            val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
            if (chapterList.isNotEmpty()) {
                chapterListData.postValue(chapterList)
            } else {
                loadChapter(book, isFromBookInfo = true)
            }
        }
    }

    fun refreshBook(book: Book) {
        loadBookInfo(book, false)
    }

    fun loadBookInfo(
        book: Book,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope
    ) {
        LocalBook.upBookInfo(book)
        bookData.postValue(book)
        loadChapter(book)
    }

    fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope,
        isFromBookInfo: Boolean = false,
        oldBook: Book = book.copy(),
    ) {
        execute(scope) {
            LocalBook.getChapterList(book).let {
                book.update()
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*it.toTypedArray())
                ReadBook.onChapterListUpdated(book)
                bookData.postValue(book)
                chapterListData.postValue(it)
            }
        }.onError {
            chapterListData.postValue(chapterListData.value.orEmpty())
            context.toastOnUi("LoadTocError:${it.localizedMessage}")
        }
    }

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                val minOrder = appDb.bookDao.minOrder
                book.order = minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                book.update()
            }
        }
    }

    fun saveBook(
        book: Book?,
        preserveCustomCoverUrl: Boolean = true,
        success: (() -> Unit)? = null,
    ) {
        book ?: return
        execute {
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            appDb.bookDao.getBook(book.name, book.author)?.let {
                book.durChapterIndex = it.durChapterIndex
                book.durChapterPos = it.durChapterPos
                book.durChapterTitle = it.durChapterTitle
            }
            if (preserveCustomCoverUrl) {
                book.savePreservingCustomCoverUrl()
            } else {
                book.save()
            }
            if (ReadBook.book?.isSameNameAuthor(book) == true) {
                ReadBook.book = book
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun saveChapterList(success: (() -> Unit)?) {
        execute {
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun addToBookshelf(success: (() -> Unit)?) { //点击书架按钮或在加分组时触发
        execute {
            bookData.value?.let { book ->
                book.removeType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                appDb.bookDao.getBook(book.name, book.author)?.let {
                    book.durChapterIndex = it.durChapterIndex
                    book.durChapterPos = it.durChapterPos
                    book.durChapterTitle = it.durChapterTitle
                }
                if (ReadBook.book?.isSameNameAuthor(book) == true) {
                    ReadBook.book = book
                }
                book.save()
            }
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
            inBookshelf = true
        }.onSuccess {
            success?.invoke()
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun delBook(deleteOriginal: Boolean = false, success: (() -> Unit)? = null) {
        execute {
            bookData.value?.let {
                it.delete()
                inBookshelf = false
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun clearCache(book: Book) {
        execute {
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.value?.let {
            appDb.bookDao.getBook(it.bookUrl)?.let { book ->
                bookData.postValue(book)
            }
        }
    }

}
