package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打开 App / 从后台恢复时，检查书架上本地书的源文件是否变了，变了就重新解析。
 *
 * 判定口径（任务书原话）：**加载时间 < 源文件更新时间（或创建时间）** ⇒ 需要重新加载。
 *   这里的「加载时间」取 `Book.latestChapterTime`（每次成功解析目录都会刷成当时时间），
 *   所以等价于「文件比上次解析时新」，不会每开一次 App 就重解析一遍。
 *
 * 性能约束（任务书要求「后台运行，不要造成打开或恢复 app 时卡顿」）：
 *   - **不碰 UI 线程**：整个扫描跑在 [Dispatchers.IO]，只在最后回主线程发一个事件。
 *   - **打开时不做重活**：先做极轻量的 stat 判定（只查 lastModified），
 *     只有文件确实变了的书才进入真正的解析流程；绝大多数情况下是 0 本。
 *   - **全局单实例**：上一轮没跑完时再次触发（快速切前后台）直接忽略，不排队堆积。
 *   - **逐本容错**：一本解析失败不影响其余，也不会让整轮中断。
 *
 * 已知局限：部分 SAF 提供方（个别网盘 / 某些 ROM 的文件管理器）返回的 lastModified
 * 恒为 0 或恒为创建时间。拿不到可靠时间就**不乱判** —— 宁可漏判也不误重载，
 * 因为误判会让用户每次回前台都被重排目录、丢掉当前章节定位。
 */
object LocalBookChangeScanner {

    /** 一轮扫描里同时解析的书籍数量上限。目录解析是 CPU + IO 混合活，压住峰值。 */
    private const val PARALLEL = 2

    private val running = AtomicBoolean(false)

    /**
     * 启动一轮扫描。已经在跑就直接返回（不排队）—— 快速切前后台时没必要排两次。
     *
     * @param onBooksChanged 只有书真的被重解析了才回调，参数是变化的 bookUrl 列表。
     *                       回调跑在主线程，可以直接用来刷新书架。
     */
    suspend fun scan(onBooksChanged: (List<String>) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            val changed = withContext(Dispatchers.IO) { scanInternal() }
            if (changed.isNotEmpty()) {
                withContext(Dispatchers.Main) { onBooksChanged(changed) }
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * @return 真正被重新解析的 bookUrl 列表（可为空）
     */
    private suspend fun scanInternal(): List<String> {
        // 书架书按 order 排好取出来；只关心本地书（离线版里其实就是全部）
        val localBooks = appDb.bookDao.allShelfByOrder.filter { it.isLocal }
        if (localBooks.isEmpty()) return emptyList()

        // 第一轮：轻量判定，只取文件元数据，不打开文件流。
        // 判据直接复用 [isLocalModified]（= 文件 lastModified > 上次解析时间），
        // 和阅读页打开时的「要不要重新解析目录」用同一套逻辑，避免两处口径打架。
        val candidates = localBooks.filter { book ->
            runCatching { book.isLocalModified() }.getOrDefault(false)
        }
        if (candidates.isEmpty()) return emptyList()

        // 第二轮：真正重解析，按 PARALLEL 分批并发
        val changed = mutableListOf<String>()
        candidates.chunked(PARALLEL).forEach { batch ->
            val results = coroutineScope {
                batch.map { book ->
                    async(Dispatchers.IO) {
                        // 单本失败（文件被删 / 权限丢失 / 目录解析不出内容）跳过即可，
                        // 阅读页打开时还有自己的错误提示，这里不必刷屏。
                        runCatching { LocalBookTocReloader.reload(book) }
                            .fold(onSuccess = { book.bookUrl }, onFailure = { null })
                    }
                }.awaitAll()
            }
            changed += results.filterNotNull()
        }
        return changed
    }
}
