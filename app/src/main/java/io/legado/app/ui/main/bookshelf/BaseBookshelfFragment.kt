package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.ViewBookshelfHeaderBinding
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.gone
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null

    private var shelfHeaderBinding: ViewBookshelfHeaderBinding? = null
    private var continueBook: Book? = null
    private var shelfHeaderFlowJob: Job? = null

    override fun onDestroyView() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        shelfHeaderBinding = null
        continueBook = null
        super.onDestroyView()
    }

    fun bindShelfHeader(header: ViewBookshelfHeaderBinding) {
        shelfHeaderBinding = header
        header.continueReading.setOnClickListener {
            continueBook?.let { startActivityForBook(it) }
        }
        header.continueReading.setOnLongClickListener {
            continueBook?.let { book ->
                startActivity(
                    Intent(requireContext(), BookInfoActivity::class.java).apply {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                )
                true
            } ?: false
        }
        subscribeShelfHeaderRefresh()
    }

    private fun subscribeShelfHeaderRefresh() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        continueBook = null
        val header = shelfHeaderBinding ?: return
        val showRecentReading = AppConfig.showBookshelfRecentReading
        val showBookshelfStats = AppConfig.showBookshelfStats
        header.tvShelfStats.visibility = if (showBookshelfStats) View.VISIBLE else View.GONE
        header.continueReading.gone()
        header.root.visibility = if (showBookshelfStats) View.VISIBLE else View.GONE
        if (!showRecentReading && !showBookshelfStats) return

        shelfHeaderFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowShelfBookCount()
                .flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .catch { Unit }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { bookCount ->
                    val currentHeader = shelfHeaderBinding ?: return@collect
                    val (book, readingCount) = withContext(Dispatchers.IO) {
                        val book = if (showRecentReading) {
                            appDb.bookDao.lastReadBookOnShelf
                        } else {
                            null
                        }
                        val readingCount = if (showBookshelfStats) {
                            appDb.bookDao.readingCount
                        } else {
                            0
                        }
                        book to readingCount
                    }
                    if (shelfHeaderBinding !== currentHeader) return@collect
                    continueBook = book
                    if (showBookshelfStats) {
                        currentHeader.tvShelfStats.text =
                            getString(R.string.bookshelf_stats, bookCount, readingCount)
                    }
                    currentHeader.root.visibility =
                        if (showBookshelfStats || book != null) View.VISIBLE else View.GONE
                    if (book == null) {
                        currentHeader.continueReading.gone()
                        return@collect
                    }
                    currentHeader.continueReading.visibility = View.VISIBLE
                    currentHeader.tvContinueName.text = book.name
                    currentHeader.tvContinueChapter.text = book.durChapterTitle
                        .takeIf { it?.isNotBlank() == true }
                        ?: getString(R.string.read_not_started)
                    currentHeader.tvContinuePercent.text =
                        "${((book.readProgress() ?: 0f) * 100).roundToInt().coerceIn(0, 100)}%"
                }
        }
    }

    abstract fun gotoTop()

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_update_toc -> activityViewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var showBookname = AppConfig.showBookname
            var readProgressMode = AppConfig.bookshelfReadProgressMode
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        if (showBookname !in rgbLayout.indices) {
                            showBookname = 0
                            AppConfig.showBookname = 0
                        }
                        if (readProgressMode !in 0..<spReadProgress.count) {
                            readProgressMode = 1
                            AppConfig.bookshelfReadProgressMode = readProgressMode
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        spReadProgress.setSelection(readProgressMode)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        swShowRecentReading.isChecked = AppConfig.showBookshelfRecentReading
                        swShowBookshelfStats.isChecked = AppConfig.showBookshelfStats
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgbLayout.checkByIndex(showBookname)
                        if (bookshelfLayout < 2) {
                            bookNameChoice.visibility = View.GONE
                        }
                        rgLayout.setOnCheckedChangeListener { group, checkedId ->
                            val index = group.getCheckedIndex()
                            bookNameChoice.visibility = if (index > 1) View.VISIBLE else View.GONE
                        }
                        rgSort.checkByIndex(bookshelfSort)
                        margin.progress = AppConfig.bookshelfMargin
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (showBookname != rgbLayout.getCheckedIndex()) {
                        AppConfig.showBookname = rgbLayout.getCheckedIndex()
                        recreate = true
                    }
                    if (AppConfig.bookshelfMargin != margin.progress) {
                        AppConfig.bookshelfMargin = margin.progress
                        recreate = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (readProgressMode != spReadProgress.selectedItemPosition) {
                        AppConfig.bookshelfReadProgressMode = spReadProgress.selectedItemPosition
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showBookshelfRecentReading != swShowRecentReading.isChecked) {
                        AppConfig.showBookshelfRecentReading = swShowRecentReading.isChecked
                        recreate = true
                    }
                    if (AppConfig.showBookshelfStats != swShowBookshelfStats.isChecked) {
                        AppConfig.showBookshelfStats = swShowBookshelfStats.isChecked
                        recreate = true
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout < 2) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }


}
