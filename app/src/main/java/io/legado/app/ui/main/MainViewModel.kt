package io.legado.app.ui.main

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.min

class MainViewModel(application: Application) : BaseViewModel(application) {
    private var threadCount = AppConfig.threadCount
    private var poolSize = min(threadCount, AppConst.MAX_THREAD)
    private var upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    private val tocUpdateRequests = TocUpdateRequests()
    val onUpBooksLiveData = MutableLiveData<Int>()
    private var upTocJob: Job? = null
    private var upTocJobGeneration = 0L
    val booksListRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 30)
    }
    val booksGridRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 100)
    }
    var callback: CallBack? = null
    fun setActivityCallback(callback: CallBack) {
        this.callback = callback
    }

    init {
        deleteNotShelfBook()
    }

    override fun onCleared() {
        tocUpdateRequests.cancelAll()
        super.onCleared()
        upTocPool.close()
    }

    fun upPool() {
        threadCount = AppConfig.threadCount
        if (upTocJob?.isActive == true) {
            return
        }
        val newPoolSize = min(threadCount, AppConst.MAX_THREAD)
        if (poolSize == newPoolSize) {
            return
        }
        poolSize = newPoolSize
        upTocPool.close()
        upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    }

    fun isUpdate(bookUrl: String): Boolean {
        return tocUpdateRequests.isRunning(bookUrl)
    }

    fun upAllBookToc() {
        execute {
            addToWaitUp(appDb.bookDao.hasUpdateBooks, AppConfig.onlyUpdateRead)
        }
    }

    fun upToc(
        books: List<Book>,
        onlyUpdateRead: Boolean,
        policy: TocUpdatePolicy = TocUpdatePolicy.ALLOW_PRE_DOWNLOAD,
        refreshBookInfo: Boolean = false,
    ) {
        execute(context = upTocPool) {
            addToWaitUp(
                filterBooksForTocUpdate(books),
                onlyUpdateRead,
                policy,
                refreshBookInfo,
            )
        }
    }

    @Synchronized
    private fun addToWaitUp(
        books: List<Book>,
        onlyUpdateRead: Boolean,
        policy: TocUpdatePolicy = TocUpdatePolicy.ALLOW_PRE_DOWNLOAD,
        refreshBookInfo: Boolean = false,
    ) {
        books.forEach { book ->
            if (onlyUpdateRead && book.getUnreadChapterNum() > 0) return@forEach
            tocUpdateRequests.enqueue(book.bookUrl, policy, refreshBookInfo)
        }
        if (upTocJob == null && tocUpdateRequests.hasQueued()) {
            startUpTocJob()
        }
    }

    @Synchronized
    private fun startUpTocJob() {
        if (upTocJob != null || !tocUpdateRequests.hasQueued()) return
        upPool()
        postUpBooksLiveData()
        val generation = ++upTocJobGeneration
        val job = viewModelScope.launch(
            context = upTocPool,
            start = CoroutineStart.LAZY,
        ) {
            flow {
                while (true) {
                    emit(tocUpdateRequests.poll() ?: break)
                }
            }.onEachParallel(threadCount) { request ->
                postEvent(EventBus.UP_BOOKSHELF, request.bookUrl)
                updateToc(request)
            }.onEach { request ->
                postEvent(EventBus.UP_BOOKSHELF, request.bookUrl)
                postUpBooksLiveData()
            }.onCompletion { cause ->
                completeUpTocJob(generation, cause)
            }.catch {
            }.collect()
        }
        upTocJob = job
        job.start()
    }

    @Synchronized
    private fun completeUpTocJob(generation: Long, cause: Throwable?) {
        if (generation != upTocJobGeneration) return
        upTocJob = null
        if (cause != null) {
            tocUpdateRequests.cancelAll()
            postUpBooksLiveData()
            return
        }
        if (tocUpdateRequests.hasQueued()) {
            startUpTocJob()
            return
        }
    }

    /**
     * 重新解析本地书籍的目录。
     * 纯本地阅读器：内容与目录均来自本地文件，不再联网获取。
     */
    private suspend fun updateToc(request: TocUpdateRequestToken) {
        val bookUrl = request.bookUrl
        try {
            val book = appDb.bookDao.getBook(bookUrl) ?: return
            currentCoroutineContext().ensureActive()
            val toc = LocalBook.getChapterList(book)
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            book.update()
            ReadBook.onChapterListUpdated(book)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
        } finally {
            tocUpdateRequests.finish(request, bookUrl)
        }
    }

    fun postUpBooksLiveData(reset: Boolean = false) {
        if (AppConfig.showWaitUpCount) {
            onUpBooksLiveData.postValue(tocUpdateRequests.pendingCount())
        } else if (reset) {
            onUpBooksLiveData.postValue(0)
        }
    }

    private fun deleteNotShelfBook() {
        execute {
            appDb.bookDao.getNotShelfBooks().forEach { it.saveReadRecordSnapshot() }
            appDb.bookDao.deleteNotShelfBook()
        }
    }

    interface CallBack {
        fun openImportUi(type: Int, source: String)
    }

}
