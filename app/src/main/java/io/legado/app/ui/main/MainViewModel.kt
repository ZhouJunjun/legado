package io.legado.app.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.LocalBookChangeScanner
import io.legado.app.help.book.LocalBookTocReloader
import io.legado.app.help.config.AppConfig
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

    /** 上一次「书架文件变更扫描」的时刻（[SystemClock.elapsedRealtime]），用于节流。 */
    private var lastLocalScanAt = 0L
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
     *
     * 实际重解析走 [LocalBookTocReloader]：它按 bookUrl 加锁，
     * 避免和「打开 App 时的变更扫描」并发把章节表写坏。
     */
    private suspend fun updateToc(request: TocUpdateRequestToken) {
        val bookUrl = request.bookUrl
        try {
            val book = appDb.bookDao.getBook(bookUrl) ?: return
            currentCoroutineContext().ensureActive()
            LocalBookTocReloader.reload(book)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
        } finally {
            tocUpdateRequests.finish(request, bookUrl)
        }
    }
    /**
     * 打开 App / 从后台恢复时，检查书架上本地书的文件是否变了，变了就后台重解析。
     *
     * - 全程在 IO 线程，只有最后「有书真的变了」才回主线程发事件；
     * - 绝大多数情况下第一轮 stat 就全部跳过，不产生可感知开销；
     * - 已有一轮在跑时直接忽略（见 [LocalBookChangeScanner.scan]）。
     *
     * 这里再加一层时间节流：进出阅读页、旋转屏幕都会走一遍 onResume，
     * 没有节流的话短时间内会反复 stat 整个书架（SAF 下每本都是一次 binder 调用）。
     */
    fun scanLocalBookChanges() {
        val now = SystemClock.elapsedRealtime()
        if (lastLocalScanAt != 0L && now - lastLocalScanAt < LOCAL_SCAN_INTERVAL_MS) return
        lastLocalScanAt = now
        execute {
            LocalBookChangeScanner.scan { changedBookUrls ->
                changedBookUrls.forEach { postEvent(EventBus.UP_BOOKSHELF, it) }
            }
        }
    }

    /**
     * 用户在其它页面（详情页「更新目录」、阅读页刷新）改过某本书的目录后，
     * 重置节流，让下一次回前台能立刻按新状态复核，而不是被 30 秒节流挡掉。
     */
    fun invalidateLocalScanThrottle() {
        lastLocalScanAt = 0L
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

    private companion object {
        /**
         * 两次「书架文件变更扫描」的最小间隔。
         * 书架扫描要逐本 stat 源文件（SAF 下每本是一次 binder 调用），
         * 进出阅读页/转屏都会触发 onResume，不给节流会白跑很多次。
         * 30 秒足够覆盖「用户去文件管理器换了书再切回来」的场景。
         */
        const val LOCAL_SCAN_INTERVAL_MS = 30_000L
    }

}
