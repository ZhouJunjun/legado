package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.alpha
import androidx.core.view.forEach
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.barBorderBackground
import io.legado.app.lib.theme.barForegroundColor
import io.legado.app.lib.theme.barSecondaryForegroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.alignTitleInk
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationDescription: CharSequence
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    private val opaque: Boolean

    /**
     * 由 attrs 决定的「是否属于自动着色体系」(`themeMode == 0 && !opaque`)。
     *
     * 仅供 [usesTransparentForeground] 使用 —— 那里的语义是"这个顶栏**原本**是否
     * 依赖自动着色(从而可能露出背景)", 不能随 [automaticForeground] 的可变开关漂移。
     */
    private val automaticForegroundInit: Boolean

    /**
     * 是否由 `TitleBar` 自动着色(标题/菜单文字/菜单图标)。
     *
     * 默认 **true** —— 包含 opaque 顶栏(2026-09-30 起, 见 [applyForegroundColor])。
     * 唯一例外是**阅读菜单的沉浸模式**: 它的顶栏要跟随正文配色(而非栏位底色),
     * 由 `ReadMenu` 自己着色, 那里把这个开关置 false 让自动着色让路。
     */
    var automaticForeground: Boolean = true

    /** `app:themeMode="dark"` —— 顶栏底色是布局给的半透明深色, 前景恒用浅色。 */
    private val themeModeIsDark: Boolean
    private val titleTextColorFromAttrs: Boolean
    private val subtitleTextColorFromAttrs: Boolean

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        // 仅作为「是否属于自动着色体系」的初始判据保留(usesTransparentForeground 需要它),
        // 不再用来排除 opaque/dark 顶栏 —— 那正是 P1 前景色不刷新的根因。
        automaticForegroundInit = themeMode == 0 && !opaque
        themeModeIsDark = themeMode == 1

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        navigationDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
                ?: context.getText(R.string.back)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        titleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_titleTextColor)
        subtitleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_subtitleTextColor)

        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (titleTextColorFromAttrs) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (subtitleTextColorFromAttrs) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    if (fitStatusBar) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
            }

            if (!opaque && context.transparentNavBar) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                // 顶部栏统一用底栏背景色(取代原来的 primaryColor 彩色).
                // 下边线 1dp 实心灰(bar_border): 顶栏与内容区之间需要一条明确的分界,
                // 靠 elevation 阴影在浅色主题下几乎看不出来。
                setBackground(context.barBorderBackground(context.bottomBackground, atTop = false))
                elevation = context.elevation
            }

            stateListAnimator = null
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
        post {
            // 不再用「attrs 是否 automaticForeground」门控: opaque / themeMode=dark 的顶栏
            // 同样需要正确的前景色(见 applyForegroundColor 注释),
            // 且标题与图标的垂直对齐校正必须对所有顶栏生效。
            applyForegroundColor()
            alignTitleText()
        }
    }

    val usesTransparentForeground: Boolean
        get() = automaticForegroundInit && context.transparentNavBar &&
            background?.alpha == 0

    /**
     * 顶部栏实际底色。透明顶栏时露出的是页面背景色, 否则就是我们自己设的底栏色。
     */
    private val barBackgroundColor: Int
        get() = when {
            usesTransparentForeground -> context.backgroundColor
            else -> context.bottomBackground
        }

    /**
     * 按顶部栏实际底色反推前景色(标题/副标题/导航图标/溢出图标/搜索框/标签页)。
     *
     * 前景色现在统一走 [Context.barForegroundColor]: 用户在「主题设置 → 白天/夜间 →
     * 栏位文字与图标颜色」里指定了颜色就用它, 否则按 [barBackgroundColor] 自动反推。
     *
     * ⚠️ 这里**不再**用 `automaticForeground` 早退(2026-09-30):
     * 原实现 `if (!automaticForeground) return` 把 opaque 顶栏(`view_read_menu.xml`、
     * `activity_code_edit.xml`)和 themeMode=dark 的顶栏整段排除, 它们只能靠
     * `?attr/actionBarStyle` 的 overlay 选色 —— 而那个 overlay 是按 **primaryColor 明暗**
     * 选的(见 `BaseActivity.initTheme`), 与顶栏实际底色 `bottomBackground` 不同源,
     * 于是出现"栏底已是浅灰、文字图标还是白的"。
     *
     * themeMode=dark 的顶栏(如详情页)底色是**半透明深色**, 由布局自己给出, 应当给浅色前景 ——
     * 这恰好等于按它自身底色反推的结果, 所以一并纳入不会破坏原观感。
     * 布局里显式指定的颜色(titleTextColor/subtitleTextColor 属性)仍不会被覆盖。
     */
    fun applyForegroundColor() {
        // 沉浸模式的阅读菜单自行着色(顶栏跟随正文配色而非栏位底色), 这里让路。
        if (!automaticForeground) return
        val color = if (themeModeIsDark) {
            // 深色顶栏(详情页/音频页): 底色是布局给的半透明深色, 恒定用浅色前景。
            context.getCompatColor(R.color.md_white_1000)
        } else {
            context.barForegroundColor
        }
        if (!titleTextColorFromAttrs) {
            setTitleTextColor(color)
        }
        if (!subtitleTextColorFromAttrs) {
            setSubTitleTextColor(color)
        }
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.findViewById<SearchView>(R.id.search_view)?.applyTint(color)
        val tabUnselectedColor = context.barSecondaryForegroundColor
        toolbar.findViewById<TabLayout>(R.id.tab_layout)
            ?.setTabTextColors(tabUnselectedColor, color)
        toolbar.menu.forEach { item ->
            (item.actionView as? SearchView)?.applyTint(color)
        }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    /**
     * 顶栏标题的**垂直对齐校正**: 让标题墨迹中心与同行图标(返回箭头)中心齐平。
     *
     * 实现已抽到 [alignTitleInk](所有用原生 `Toolbar` 的弹框共用同一套逻辑,
     * 避免两处实现漂移)。这里只做转发。
     *
     * 🔴 原实现以 `toolbar.getChildAt(0)` 当锚点, 但 AppCompat 的导航按钮是在
     * `setDisplayHomeAsUpEnabled(true)` 时才创建的, 而 XML `app:title` 在构造期
     * 就已加入标题 —— 于是「XML 静态设标题」的页面(绝大多数 Activity / 首页 / 我的)
     * `child0` 拿到的是**标题自己**, 算出 Δ≈0, 校正静默失效。这既是用户 2026-09-30
     * 反馈「有些页面顶栏文字和图标没对齐」的根因, 详见 [alignTitleInk] 的注释。
     */
    fun alignTitleText() {
        toolbar.alignTitleInk()
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
        tintOverflowButton(colorFilter)
    }

    /**
     * 给三点按钮(overflow)着色。
     *
     * 它**不是** `toolbar.menu` 里的 item, 而是 `ActionMenuView` 内一个
     * `isOverflowButton == true` 的 ImageView; `toolbar.overflowIcon` 默认是 null,
     * 对着 null 设 colorFilter 是无效操作。图标实际颜色由 Toolbar 的
     * `android:theme="?attr/actionBarStyle"`(→ `AppBarOverlay.Light/Dark`)决定,
     * 而那个 overlay 是按 **`primaryColor` 明暗**选的(见 `BaseActivity.initTheme`),
     * 与顶栏实际底色(`bottomBackground`)不同源 —— 默认棕色 primary 偏暗 → 选到 Dark overlay
     * → 亮色主题下三点是**白色**, 在浅灰顶栏上看不见(用户 2026-09-22 反馈:
     * "亮主题下的设置头部文字还是白的, 三点按钮也是白的")。
     *
     * 所以这里直接按栏位前景色覆盖它。用 `post` 是因为 `ActionMenuView` 的 overflow
     * 按钮要在布局后才存在(与 `installMd3OverflowMenu` 的做法一致)。
     */
    private fun tintOverflowButton(colorFilter: PorterDuffColorFilter) {
        post {
            findOverflowButton(toolbar)?.colorFilter = colorFilter
        }
    }

    private fun findOverflowButton(view: View): ImageView? {
        if (view is ImageView) {
            val lp = view.layoutParams
            if (lp is ActionMenuView.LayoutParams && lp.isOverflowButton) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findOverflowButton(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.apply {
                    setDisplayHomeAsUpEnabled(displayHomeAsUp)
                    if (displayHomeAsUp) {
                        setHomeActionContentDescription(navigationDescription)
                    }
                }
            }
        }
    }

}
