package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.mergeFilteredOrder
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Semaphore

internal data class PersistentCoverResult(
    val saved: Int,
    val skipped: Int,
    val failed: Int,
)

class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    var groupId: Long = -1L
    var groupName: String? = null
    val batchPersistCoverState = MutableLiveData<Boolean>()
    val batchPersistCoverProcess = MutableLiveData<String>()
    internal var batchPersistCoverCoroutine: Coroutine<*>? = null
    private val coverOperationSemaphore = Semaphore(1)
    private var coverOperationId = 0

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.updatePreservingCustomCoverUrl(*book)
        }
    }

    fun updateBookOrder(books: List<Book>, resetAll: Boolean) {
        execute {
            if (resetAll) {
                appDb.runInTransaction {
                    val reordered = mergeFilteredOrder(
                        appDb.bookDao.allShelfByOrder,
                        books,
                    ) { it.bookUrl }
                    reordered.forEachIndexed { index, book -> book.order = index + 1 }
                    appDb.bookDao.updateOrder(reordered)
                }
            } else {
                appDb.bookDao.updateOrder(books)
            }
        }
    }

    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        execute {
            books.forEach { it.saveReadRecordSnapshot() }
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }
    }

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    private fun beginCoverOperation(): Int {
        val operationId = ++coverOperationId
        batchPersistCoverCoroutine?.cancel()
        batchPersistCoverState.postValue(false)
        return operationId
    }

}
