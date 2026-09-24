package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ViewReadMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.barBorderBackground
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConstraintModify
import io.legado.app.utils.activity
import io.legado.app.utils.applyTint
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.modifyBegin
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import splitties.views.onLongClick
import androidx.core.graphics.toColorInt
import io.legado.app.constant.BookType
import io.legado.app.utils.buildMainHandler

/**
 * 阅读界面菜单
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val chapterNameTextSize = binding.tvChapterName.textSize
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false

    /**
     * 当前叠在主菜单之上的面板。
     *
     * 面板不再收起主菜单(旧行为: 点【朗读】→ runMenuOut → 主菜单消失, 底栏换成
     * 【目录/主菜单/后台/设置】)。现在底栏 4 个按钮恒为【目录/朗读/界面/设置】,
     * 面板以 dialog 形式叠在底栏之上, 切换面板时主菜单全程不动 —— 用户要的
     * 「面板切换」心智模型。
     */
    private var curPanel = PANEL_NONE

    /** 是否正有面板叠在主菜单之上。 */
    val hasPanel: Boolean get() = curPanel != PANEL_NONE

    /**
     * 面板要向上抬的偏移量 —— 把整段底栏(进度行 + 4 个按钮)完整让出来。
     *
     * 推导(全部用屏幕坐标, 不依赖窗口内边距的归属):
     *   · 面板是独立窗口, 其窗口底边默认落在**导航栏之上**(这正是原来朗读面板贴底时
     *     不会盖住导航栏的原因), 即 windowBottom = 底栏背景(ll_bottom_bg)的底边。
     *   · Gravity.BOTTOM 下 yAdj 取正值把窗口向上抬: windowBottom = 底边 - yAdj。
     *   · 要让面板底边落在底栏上沿(ll_bottom_bg.top) ⇒ yAdj = 进度行 + 按钮行。
     *
     * 这两行在面板出现时只置 INVISIBLE(仍占位), 所以高度稳定, 不会随开关而变。
     */
    fun panelOffset(): Int =
        binding.llChapterProgress.height + binding.llBottomButtons.height

    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
    private var bgColor: Int = if (immersiveMenu) {
        kotlin.runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    private var textColor: Int = if (immersiveMenu) {
        ReadBookConfig.durConfig.curTextColor()
    } else {
        context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    }

    private var bottomBackgroundList: ColorStateList = Selector.colorBuild()
        .setDefaultColor(bgColor)
        .setPressedColor(ColorUtils.darkenColor(bgColor))
        .create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private val showBrightnessView
        get() = context.getPrefBoolean(
            PreferKey.showBrightnessView,
            true
        )
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            binding.tvSourceAction.isGone = ReadBook.isLocalBook
            ReadBook.bookSource?.let {
                if (it.customButton) {
                    binding.tvCustomBtn.visibility = VISIBLE
                }
            }
            callBack.upSystemUiVisibility()
            binding.llBrightness.visible(showBrightnessView)
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
            callBack.upSystemUiVisibility()
            if (!LocalConfig.readMenuHelpVersionIsLast) {
                callBack.showHelp()
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        initView()
        upBrightnessState()
        bindEvent()
    }

    private fun initView(reset: Boolean = false) = binding.run {
        if (AppConfig.isNightTheme) {
            fabNightTheme.setImageResource(R.drawable.ic_daytime)
        } else {
            fabNightTheme.setImageResource(R.drawable.ic_brightness)
        }
        initAnimation()
        tvCustomBtn.setColorFilter(context.accentColor)
        if (immersiveMenu) {
            val lightTextColor = ColorUtils.withAlpha(ColorUtils.lightenColor(textColor), 0.75f)
            titleBar.setTextColor(textColor)
            // 下边线 1dp 实心灰: 顶栏与正文之间需要明确分界(沉浸模式下正文就贴在栏下方)。
            titleBar.background = context.barBorderBackground(bgColor, atTop = false)
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(lightTextColor)
            tvChapterUrl.setTextColor(lightTextColor)
        } else {
            // 非沉浸式: 顶部栏与底栏同色(原来是 primaryColor 彩色, 与底栏割裂)。
            // 文字色按实际底色反推, 自定义主题下也保证对比度。
            //
            // 这里**不能**只在 reset 时执行。该 TitleBar 在布局里带 `app:opaque="true"`,
            // 于是 TitleBar.automaticForeground 为 false, `applyForegroundColor()` 直接
            // return —— 头部标题文字与右侧三点按钮(overflow 图标)的着色**只能靠这里**。
            // 首次显示时 reset=false, 原先若放在 `else if (reset)` 分支里就会整段跳过:
            // Toolbar 的 `android:theme="?attr/actionBarStyle"` 会按 **primaryColor 明暗**
            // 选 AppBarOverlay.Light/Dark(见 BaseActivity.initTheme), 与顶栏实际底色
            // (bottomBackground)不同源 —— 默认棕色 primary 偏暗 → 选到 Dark overlay
            // → 亮色主题下标题文字与三点按钮发白(用户 2026-09-22 反馈)。
            val bgColor = context.bottomBackground
            val textColor = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
            titleBar.setTextColor(textColor)
            titleBar.background = context.barBorderBackground(bgColor, atTop = false)
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(textColor)
            tvChapterUrl.setTextColor(textColor)
        }
        val brightnessBackground = GradientDrawable()
        brightnessBackground.cornerRadius = 5F.dpToPx()
        brightnessBackground.setColor(ColorUtils.adjustAlpha(bgColor, 0.5f))
        llBrightness.background = brightnessBackground
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            // 阅读菜单尾(底部设置栏)上边线 1dp 实心灰, 与顶栏的下边线呼应。
            llBottomBg.background = context.barBorderBackground(bgColor, atTop = true)
        }
        fabSearch.backgroundTintList = bottomBackgroundList
        fabSearch.setColorFilter(textColor)
        fabAutoPage.backgroundTintList = bottomBackgroundList
        fabAutoPage.setColorFilter(textColor)
        fabReplaceRule.backgroundTintList = bottomBackgroundList
        fabReplaceRule.setColorFilter(textColor)
        fabNightTheme.backgroundTintList = bottomBackgroundList
        fabNightTheme.setColorFilter(textColor)
        val chapterTextColor = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setDisabledColor(ColorUtils.withAlpha(textColor, 0.4f))
            .create()
        tvPre.setTextColor(chapterTextColor)
        tvNext.setTextColor(chapterTextColor)
        ivCatalog.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCatalog.setTextColor(textColor)
        ivReadAloud.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvReadAloud.setTextColor(textColor)
        ivFont.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvFont.setTextColor(textColor)
        ivSetting.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvSetting.setTextColor(textColor)
        ivMemo.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvMemo.setTextColor(textColor)
        vwBrightnessPosAdjust.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        seekBrightness.applyTint(context.accentColor)
        llBrightness.setOnClickListener(null)
        seekBrightness.post {
            seekBrightness.progress = AppConfig.readBrightness
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        updateTitleAdditionLayout()
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        applyNavigationBarPadding()
    }

    fun reset() {
        upColorConfig()
        initView(true)
        // initView 会把底栏 4 个按钮统一刷成普通文字色, 因此重置后必须重新上选中态。
        // 触发路径真实存在: 在「界面」面板里换配色方案/字号 → postEvent(UPDATE_READ_ACTION_BAR)
        // → 这里 reset() —— 少了这一句, 面板还开着但底栏高亮会凭空消失。
        upSelectedState()
    }

    /**
     * 菜单 inflate 完成后重新给顶栏着色。
     *
     * 必须**不分沉浸/非沉浸都执行**: 三点按钮(overflow)是 `ActionMenuView` 在菜单 inflate
     * 时创建的, `ReadMenu` 构造期(`initView`)它还不存在, 那一次着色落空。这里由
     * `ReadBookActivity.onCompatCreateOptionsMenu` 调用, 是三点按钮的第一个可靠着色点。
     */
    fun refreshMenuColorFilter() {
        binding.titleBar.setColorFilter(textColor)
    }

    private fun upColorConfig() {
        bgColor = if (immersiveMenu) {
            kotlin.runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
        bottomBackgroundList = Selector.colorBuild()
            .setDefaultColor(bgColor)
            .setPressedColor(ColorUtils.darkenColor(bgColor))
            .create()
    }

    fun upBrightnessState() {
        if (brightnessAuto()) {
            binding.ivBrightnessAuto.setColorFilter(context.accentColor)
            binding.seekBrightness.isEnabled = false
        } else {
            binding.ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            binding.seekBrightness.isEnabled = true
        }
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    /**
     * 系统亮度监听，在高阳光亮度时启用
     */
    private var contentObserver: ContentObserver? = null
    /**
     * 设置屏幕亮度
     */
    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /**
     * 获取系统亮度值
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        val showMemo = context.getPrefBoolean(PreferKey.showBookMemo, false)
        binding.llMemo.isVisible = showMemo
        binding.memoSpacer.isVisible = showMemo
        // FAB 行/章节进度行的**布局初始值是 invisible**(占位不塌缩, 见 upPanelRows 注释),
        // 而唯一会恢复它们的 applyPanel() 在 curPanel == PANEL_NONE 时 early-return ——
        // 不在这里补一刀, 首次打开菜单这两行就永远不显示(要开一次面板再关掉才出现)。
        upPanelRows()
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        // 面板还叠在上面时, 收起菜单必须连带关掉面板 —— 否则面板会悬在空无一物的正文上。
        // 走 clearPanel() 而不是直接关 dialog: 让 CallBack.onMenuPanelChange(NONE) 收尾,
        // 底栏选中的高亮也一并复位。
        clearPanel()
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    /**
     * 打开 / 切换 / 关闭面板(用户 2026-09-23 规范)。
     *
     * · 传 [panel] 与当前面板**不同** → 直接切过去(界面 ↔ 朗读)。
     * · 传 [panel] 与当前面板**相同** → **取消选中并收起面板**, 回到主菜单默认态
     *   (顶栏 + 4 个 FAB + 章节进度行 + 底栏, 无任何选中)。
     *   —— 用户规范: 「如果当前已经是界面/朗读面板, 去掉选中状态, 返回 1.1」。
     *
     * 主菜单在这一步**不动**: 只负责 FAB 行/进度行的显隐(给面板腾出干净的底栏)
     * 与底栏高亮, 面板 dialog 的弹/关交给 [CallBack.onMenuPanelChange]。
     */
    fun togglePanel(panel: Int) {
        // 已是当前面板 → 取消选中, 回到默认态(1.1)。
        // 必须放在最前: 菜单已收起时 curPanel 已是 PANEL_NONE, 不会命中这里。
        if (curPanel == panel) {
            clearPanel()
            return
        }
        openPanel(panel)
    }

    /**
     * 只负责「打开 / 切到」[panel], **不做「再点一次收起」**。
     *
     * 长按【朗读】用它: 该手势的既有语义是「只开面板(即使朗读服务没在跑)」,
     * 与短按的「起读 + 开面板」区分开。若已在该面板上则不动 —— 长按一个已经
     * 打开的面板再把它关掉, 不符合这个手势的语义。
     */
    fun openPanel(panel: Int) {
        if (curPanel == panel) return
        if (!isVisible || isMenuOutAnimating) {
            // 菜单已收/正在动画: 先让菜单进场, 再叠面板。
            // 菜单进场的动画要走一帧, 所以第二步再 post 一次, 避开动画中途改状态。
            post {
                runMenuIn()
                post { applyPanel(panel) }
            }
            return
        }
        applyPanel(panel)
    }

    /** 点正文之类的地方收起面板, 但保留主菜单(与旧行为一致: 底栏还在)。 */
    fun closePanel() = clearPanel()

    /** 收起面板: 主菜单保持不动, 仅恢复 FAB/进度行的显隐与底栏高亮。 */
    fun clearPanel() {
        if (curPanel != PANEL_NONE) applyPanel(PANEL_NONE)
    }

    private fun applyPanel(panel: Int) {
        if (curPanel == panel) return
        curPanel = panel
        upPanelRows()
        upSelectedState()
        callBack.onMenuPanelChange(panel)
    }

    /**
     * 面板 dialog 已消失(含面板内部动作自我关闭, 如【停止朗读】)。
     *
     * 此时若菜单还开着, 只清面板状态、保留主菜单 —— 高亮随之复位, [hasPanel] 也回 false,
     * 否则系统返回会被多吞一次(见 ReadBookActivity 的返回回调)。
     *
     * **必须带 [panel] 参数比对**: dialog 的 dismiss 是异步投递的, 面板→面板切换时
     * 旧面板的 dispatchDismiss 排在新面板展示之后才执行; 若无条件清状态, 刚叠上来的
     * 新面板高亮与 [hasPanel] 会被旧面板的回调抹掉(表现为高亮闪一下就没)。
     */
    fun onPanelDialogDismissed(panel: Int) {
        if (curPanel == panel) clearPanel()
    }

    /**
     * 面板出现时藏掉 FAB 行与章节进度行。
     *
     * 置 INVISIBLE 而不是 GONE: 二者被隐藏后「底栏」在屏幕上的绝对位置不变,
     * 而面板正是贴到底栏上沿的 —— 位置一变就会露缝或压住底栏。
     */
    private fun upPanelRows() = binding.run {
        // 置 INVISIBLE 而不是 GONE: 二者被隐藏后「底栏」在屏幕上的绝对位置不变,
        // 面板贴的是底栏上沿, 位置一变就会露出缝隙或压到底栏。
        val show = curPanel == PANEL_NONE
        llFloatingButton.visible(show)
        llChapterProgress.visible(show)
    }

    /**
     * 底栏选中态着色: 当前面板对应的按钮用主题色(accentColor)。
     *
     * 只动图标与文字色, 不改背景 —— 面板叠上来之后底栏仍是原来的底栏,
     * 换背景会显得像换了套控件。
     */
    private fun upSelectedState() = binding.run {
        val accent = context.accentColor
        val normal = textColor
        fun up(
            iv: android.widget.ImageView,
            tv: android.widget.TextView,
            selected: Boolean
        ) {
            val color = if (selected) accent else normal
            iv.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            tv.setTextColor(color)
        }
        up(ivCatalog, tvCatalog, curPanel == PANEL_CATALOG)
        up(ivReadAloud, tvReadAloud, curPanel == PANEL_ALOUD)
        up(ivFont, tvFont, curPanel == PANEL_STYLE)
        up(ivSetting, tvSetting, curPanel == PANEL_SETTING)
    }

    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(tvChapterUrl.text.toString().substringBefore(",{"))
            } else {
                Coroutine.async {
                    context.startActivity<WebViewActivity> {
                        val url = tvChapterUrl.text.toString()
                        val bookSource = ReadBook.bookSource
                        putExtra("title", tvChapterName.text)
                        putExtra("url", url)
                        putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                        putExtra("sourceName", bookSource?.bookSourceName)
                        putExtra("sourceType", bookSource?.getSourceType())
                    }
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (ReadBook.isLocalBook) {
                return@OnLongClickListener true
            }
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            val hasLogin = ReadBook.bookSource?.hasLogin() == true
            val canPay = hasLogin
                    && ReadBook.curTextChapter?.isVip == true
                    && ReadBook.curTextChapter?.isPay != true
            popupActionMenu(context) {
                item(context.getString(R.string.login), "login", hasLogin)
                item(context.getString(R.string.chapter_pay), "chapterPay", canPay)
                item(context.getString(R.string.disable_book_source), "disableSource")
            }.show(tvSourceAction) { action ->
                when (action) {
                    "login" -> callBack.showLogin()
                    "chapterPay" -> callBack.payAction()
                    "disableSource" -> callBack.disableSource()
                }
            }
        }
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        vwBrightnessPosAdjust.setOnClickListener {
            AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
            upBrightnessVwPos()
        }
        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> ReadBook.skipToPage(seekBar.progress)
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        //搜索
        fabSearch.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(null)
            }
        }

        //自动翻页
        fabAutoPage.setOnClickListener {
            runMenuOut {
                callBack.autoPage()
            }
        }

        //替换
        fabReplaceRule.setOnClickListener { callBack.openReplaceRule() }

        //夜间模式
        fabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(context)
        }

        //上一章
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

        //下一章
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }

        //目录
        llCatalog.setOnClickListener {
            // 用户要求: 目录保留原有逻辑 —— 整页跳转, 主菜单收起。
            runMenuOut {
                callBack.openChapterList()
            }
        }

        //朗读
        llReadAloud.setOnClickListener {
            // 用户规范 1.3: 已在朗读面板 → **只取消选中**, 返回主菜单默认态(1.1)。
            // 这里必须先拦住 —— 否则会先被下面的「一键起读」重新拉起朗读,
            // 变成「点了没收起反而又起读一遍」。
            if (curPanel == PANEL_ALOUD) {
                clearPanel()
                return@setOnClickListener
            }
            // 朗读面板叠在主菜单之上, 所以不走 runMenuOut —— 主菜单全程不收起。
            // 服务没在跑时沿用「一键起读」: 先起读, 面板同时叠出来(内容也随之可用)。
            if (!BaseReadAloudService.isRun) {
                callBack.onClickReadAloud()
            }
            openPanel(PANEL_ALOUD)
        }
        llReadAloud.onLongClick {
            // 长按=只开面板(即使服务没在跑), 语义与短按区分保持不变。
            // 用 openPanel 而非 togglePanel: 长按一个已打开的面板不该把它关掉。
            openPanel(PANEL_ALOUD)
        }
        //界面
        llFont.setOnClickListener {
            // 用户规范 1.2: 已是界面面板 → 取消选中并收起, 返回默认态(1.1);
            // 否则打开/切到界面面板。togglePanel 已覆盖这两种情形。
            togglePanel(PANEL_STYLE)
        }

        //设置
        llSetting.setOnClickListener {
            // 用户要求: 设置保留原有逻辑 —— 半屏设置面板弹出, 主菜单收起。
            runMenuOut {
                callBack.showMoreSetting()
            }
        }
        llMemo.setOnClickListener {
            runMenuOut {
                callBack.showBookMemo()
            }
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                binding.tvChapterUrl.text = it.chapter.getAbsoluteURL()
            } else {
                binding.tvChapterUrl.text = null
                binding.tvChapterUrl.gone()
            }
            updateTitleAdditionLayout()
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    private fun updateTitleAdditionLayout() = binding.run {
        val chapterNameOnly = AppConfig.showReadTitleChapterNameOnly
        val scaledDensity = resources.displayMetrics.scaledDensity
        val hasChapterUrl = !tvChapterUrl.text.isNullOrBlank()
        tvChapterName.gravity = Gravity.CENTER_VERTICAL
        tvChapterUrl.gravity = Gravity.CENTER_VERTICAL
        tvChapterName.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            chapterNameTextSize + if (chapterNameOnly) 2f * scaledDensity else 0f
        )
        tvChapterUrl.alpha = if (chapterNameOnly && hasChapterUrl) 0f else 1f
        if (hasChapterUrl) {
            tvChapterUrl.visible()
        } else {
            tvChapterUrl.gone()
        }
        ConstraintSet().apply {
            clone(titleBarAddition)
            val bottomTarget = if (tvChapterUrl.isGone) {
                R.id.tv_chapter_name
            } else {
                R.id.tv_chapter_url
            }
            connect(R.id.tv_custom_btn, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            connect(R.id.tv_source_action, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            applyTo(titleBarAddition)
        }
        tvChapterName.translationY = 0f
        if (chapterNameOnly && tvChapterName.isVisible) {
            titleBarAddition.doOnLayout {
                tvChapterName.translationY =
                    (titleBarAddition.height - tvChapterName.height) / 2f - tvChapterName.top
            }
        }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    ReadBook.curTextChapter?.let {
                        max = it.pageSize.minus(1)
                        progress = ReadBook.durPageIndex
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(textColor)
    }

    private fun upBrightnessVwPos() {
        if (AppConfig.brightnessVwPos) {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.LEFT)
                .rightToRightOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        } else {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.RIGHT)
                .leftToLeftOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        }
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showBookMemo()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()

        /** 面板切换(叠在主菜单之上的朗读/界面面板)。[panel] 见 [PANEL_NONE] 等常量。 */
        fun onMenuPanelChange(panel: Int)
    }

    companion object {
        const val PANEL_NONE = 0
        const val PANEL_ALOUD = 1
        const val PANEL_STYLE = 2

        /** 仅在「原逻辑」里被引用, 保留常量以免新增分支时误用裸数字。 */
        const val PANEL_CATALOG = 3
        const val PANEL_SETTING = 4
    }

}
