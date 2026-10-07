@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.graphics.Rect
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.main.bookshelf.AloudMiniBar
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isCreated
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.views.bottomPadding
import kotlin.coroutines.resume

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener,
    MainViewModel.CallBack {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idMy = 1
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var pagePosition = 0
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 2
    private val EXIT_INTERVAL = 2000L
    private val realPositions = arrayOf(idBookshelf, idMy)
    private val menuIdToSlot = linkedMapOf(
        R.id.menu_bookshelf to "bookshelf",
        R.id.menu_my_config to "settings",
    )
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var onUpBooksBadgeView: BadgeView? = null
    private val aloudMiniBar by lazy {
        AloudMiniBar(this, binding.aloudMiniBarContainer)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upHomePage()
        upBottomBarSkin()
        aloudMiniBar.init()
        upAloudMiniBar()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                binding.viewPagerMain.currentItem = 0
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            // 首页「用户隐私与协议」弹窗已按要求移除(2026-09-25)。
            // 协议全文仍可查看: 「我的」→「关于」→ 隐私政策(AboutFragment -> privacyPolicy.md)。
            LocalConfig.privacyPolicyOk = true
            upVersion()
            //设置回调
            viewModel.setActivityCallback(this@MainActivity)
            // 首次启动导入默认朗读引擎的 viewModel.postLoad() 已随「默认朗读引擎」一并删除
            // (2026-09-25, 百度/阿里云/Next引擎 三个默认引擎按用户要求移除)
        }
    }

    override fun onResume() {
        super.onResume()
        upAloudMiniBar()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf ->
                viewPagerMain.setCurrentItem(0, false)

            R.id.menu_my_config ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
        return false
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }

        }
    }

    private fun initView() = binding.run {
        root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val keyboardHeight = windowInsets.imeHeight
            view.bottomPadding = keyboardHeight
            if (keyboardHeight > 0) view.doOnLayout {
                currentFocus?.takeIf { it.onCheckIsTextEditor() }?.let { input ->
                    input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
                }
            }
            windowInsets
        }
        viewPagerMain.setEdgeEffectColor(bottomBackground)
        viewPagerMain.offscreenPageLimit = 3
        viewPagerMain.adapter = adapter
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        bottomNavigationView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            view.bottomPadding = height
            windowInsets.inset(0, 0, 0, height)
        }
    }

    // 原「用户隐私与协议」弹窗的实现(privacyPolicy())已整体删除(2026-09-25)。
    // 首页不再弹窗, privacyPolicyOk 在 onPostCreate 里直接置位;
    // 协议全文由「我的」→「关于」→ 隐私政策(privacyPolicy.md)承载, 不受影响。

    /**
     * 版本更新日志
     *
     * ⚠️ 2026-09-24 用户要求删除「进入 app 时自动弹出的更新日志/设置 password 等弹窗」。
     * 这里只去掉**自动弹出**的部分(更新日志 / 首次打开帮助)。
     * 2026-09-27 起「检查新版本」整条链路已随离线化删除, 不再联网。
     *
     * 保留 `LocalConfig.versionCode = appInfo.versionCode` 这一行是必要的: 它是「本版本
     * 已经走完首启流程」的标记, 去掉的话每次启动都会重新进入这个分支。
     */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        LocalConfig.versionCode = appInfo.versionCode
        // 首次打开帮助(appHelp.md)与更新日志(updateLog.md)两个自动弹窗已按用户要求去掉。
        // · 更新日志: 仍可从「关于」页进入(AboutFragment 的 update_log -> updateLog.md)。
        // · 应用帮助: ⚠️ 走的就是这里, 删掉后 assets/web/help/md/appHelp.md **暂无 UI 入口**
        //   (阅读菜单里的「帮助」是 readMenuHelp.md, 不是同一份)。若之后要补入口,
        //   在「我的」页加一项 showMdFile/getString(R.string.help) 即可, 文档本身没删。
        block.resume(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(0)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                upBottomBarSkin()
                if (it) {
                    viewPagerMain.setCurrentItem(bottomMenuCount - 1, false)
                }
            }
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_SKIN) {
            upBottomBarSkin()
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            aloudMiniBar.onAloudStateChanged(it)
            upAloudMiniBar()
        }
        observeEvent<Int>(EventBus.TTS_PROGRESS) {
            upAloudMiniBar()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) {
            upAloudMiniBar()
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
    }

    private fun upBottomMenu() {
        realPositions[0] = idBookshelf
        realPositions[1] = idMy
        bottomMenuCount = realPositions.size
        adapter.notifyDataSetChanged()
    }

    /**
     * 朗读迷你条只在书架 tab 显示(番茄式交互), 切到「我的」页则隐藏。
     */
    private fun upAloudMiniBar() {
        if (pagePosition != realPositions.indexOf(idBookshelf)) {
            aloudMiniBar.hideBar()
            return
        }
        aloudMiniBar.upState()
    }

    private fun upBottomBarSkin() {
        val skin = BottomBarSkinManager.active
        if (skin.isEmpty() || !BottomBarSkinManager.hasSkin(skin)) {
            binding.bottomNavigationView.applySkin(null, 0)
            return
        }
        val sizePx = 30.dpToPx()
        val map = HashMap<Int, StateListDrawable>()
        menuIdToSlot.forEach { (id, slot) ->
            BottomBarSkinManager.getStateDrawable(skin, slot, sizePx)?.let { map[id] = it }
        }
        binding.bottomNavigationView.applySkin(map, sizePx)
    }

    private fun upHomePage() {
        when (AppConfig.defaultHomePage) {
            "my" -> binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            binding.bottomNavigationView.menu[realPositions[position]].isChecked = true
            upAloudMiniBar()
        }

    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }

    }

    override fun openImportUi(type:Int, source: String) {
        if (type == 2) {
            showDialogFragment(ImportReplaceRuleDialog(source))
        }
    }

}
