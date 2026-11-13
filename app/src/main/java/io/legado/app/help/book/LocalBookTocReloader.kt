package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 本地书籍「重新解析目录」的唯一入口。
 *
 * 为什么要单独抽一层：同一本书可能被两个地方同时要求重解析 ——
 * ① 用户手动「更新目录」（[io.legado.app.ui.main.MainViewModel.upToc]）
 * ② 打开 App / 回到前台时的书架变更扫描（[LocalBookChangeScanner]）
 *
 * 原来的 `delByBook + insert` 不是事务，两边并发进来会把章节表写成交错的半份数据
 * （章节数对得上但内容是两代目录混在一起的）。这里按 bookUrl 加互斥锁，
 * 同一本书永远只有一个解析流程在跑；不同书之间互不阻塞。
 */
object LocalBookTocReloader {

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockOf(bookUrl: String): Mutex = locks.getOrPut(bookUrl) { Mutex() }

    /**
     * 重新解析 [book] 的目录并落库，返回写进库的那一份（调用方别再用手上的旧快照）。
     *
     * 两处必须小心，都是「整行写」带来的：
     *
     * 1. **解析期间进度可能变了**：解析要几百毫秒，这期间阅读页可能刚翻页并把
     *    `durChapterIndex/Pos` 存进库。所以解析完要**重新取一次库里的最新记录**，
     *    只把目录相关字段搬过去，进度用库里的新值 —— 否则用户翻到第 10 章，
     *    回来发现停在第 3 章。
     * 2. **解析器缓存不认文件时间**：Epub/Umd/Mobi/Pdf 的 `CloseableCache` 只按
     *    bookUrl 命中，文件换了也返回旧句柄、旧目录。必须显式清掉，否则会出现
     *    「扫描说已重载、打开还是旧内容」。
     *
     * 失败时抛异常（文件不存在 / 权限丢失 / 目录为空等），由调用方决定怎么处理。
     */
    suspend fun reload(book: Book): Book {
        return lockOf(book.bookUrl).withLock {
            val bookUrl = book.bookUrl
            // 解析目标：优先用库里的记录（getChapterList 会就地改它的目录字段）
            val parseTarget = appDb.bookDao.getBook(bookUrl) ?: book

            // 清解析器缓存 → 重新解析 → 覆盖章节表，三步在同一个块里，
            // 保证「旧缓存已清、新目录已入」之间不会被别的路径插进来
            val toc = LocalBook.withParserCacheInvalidated(parseTarget) {
                val list = LocalBook.getChapterList(parseTarget)
                appDb.bookChapterDao.delByBook(bookUrl)
                appDb.bookChapterDao.insert(*list.toTypedArray())
                list
            }

            // 正文缓存也得清：缓存文件名 key 是「originName + index + 标题」，
            // 文件内容变了而标题没变时 key 不变 ⇒ 不清会一直读旧正文。
            // 放在解析成功之后：解析失败时不动缓存，用户至少还能看之前缓下来的内容。
            runCatching { BookHelp.clearCache(parseTarget) }

            // 🔴 防「未来时间戳」死循环：getChapterList 把 latestChapterTime 设成
            // System.currentTimeMillis()，而扫描的判据是「文件 mtime > latestChapterTime」。
            // 若源文件 mtime 在未来（从别处拷贝时保留了原时间戳、或设备时钟偏差），
            // 当前时间永远小于它 ⇒ 每回一次前台就重解析一遍。这里把水位抬到文件时间。
            val fileModified = LocalBook.getLastModified(parseTarget).getOrDefault(0L)
            if (fileModified > parseTarget.latestChapterTime) {
                parseTarget.latestChapterTime = fileModified
            }

            // 再取一次，拿到解析期间可能被改写的最新阅读进度
            val fresh = appDb.bookDao.getBook(bookUrl) ?: parseTarget
            val saved = if (fresh === parseTarget) {
                parseTarget
            } else {
                mergeTocInto(fresh, parseTarget, toc)
            }
            saved.update()
            ReadBook.onChapterListUpdated(saved)
            saved
        }
    }

    /**
     * 把 [tocSource] 上的目录字段合并到 [target]，**不动 target 的阅读进度**。
     *
     * 章节名：位置没变就沿用 [tocSource] 里那份（它经过替换规则处理过）；
     * 位置被夹到新目录范围内（文件变短）才按新索引重取。
     */
    private fun mergeTocInto(
        target: Book,
        tocSource: Book,
        toc: List<BookChapter>,
    ): Book {
        target.totalChapterNum = tocSource.totalChapterNum
        target.latestChapterTime = tocSource.latestChapterTime
        target.latestChapterTitle = tocSource.latestChapterTitle
        target.wordCount = tocSource.wordCount
        target.charset = tocSource.charset
        target.tocUrl = tocSource.tocUrl
        if (toc.isNotEmpty()) {
            val maxIndex = max(0, toc.size - 1)
            val index = target.durChapterIndex.coerceIn(0, maxIndex)
            if (index != target.durChapterIndex) {
                target.durChapterIndex = index
                target.durChapterTitle = toc[index].title
                target.durChapterPos = 0
            }
        }
        return target
    }
}
