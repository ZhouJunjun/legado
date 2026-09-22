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
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
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
    private val automaticForeground: Boolean
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
        automaticForeground = themeMode == 0 && !opaque

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

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (!opaque && context.transparentNavBar) {
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
        if (automaticForeground) {
            post { applyForegroundColor() }
        }
    }

    val usesTransparentForeground: Boolean
        get() = automaticForeground && context.transparentNavBar &&
            !AppConfig.isEInkMode && background?.alpha == 0

    /**
     * 顶部栏实际底色。透明顶栏时露出的是页面背景色, 否则就是我们自己设的底栏色。
     */
    private val barBackgroundColor: Int
        get() = when {
            AppConfig.isEInkMode -> context.backgroundColor
            usesTransparentForeground -> context.backgroundColor
            else -> context.bottomBackground
        }

    /**
     * 按顶部栏实际底色反推前景色(标题/副标题/导航图标/溢出图标/搜索框/标签页)。
     *
     * 只处理 `automaticForeground`(themeMode=0 且非 opaque)的顶栏:
     * - 透明顶栏: 底色是页面背景色, 取页面背景反推(与旧行为一致);
     * - 不透明顶栏: 底色现在取自 bottomBackground, 与主题 overlay 的判据(primaryColor 明暗)
     *   不再同源, 自定义主题下可能出现深底深字, 所以也按实际底色重算。
     *
     * themeMode="dark"(如音频播放/详情页)和 opaque 的顶栏不走这里 —— 它们由布局/代码
     * 自己指定颜色(白色文字配半透明深底), 保持原样。
     * 布局里显式指定的颜色(titleTextColor/subtitleTextColor 属性)不会被覆盖。
     */
    fun applyForegroundColor() {
        if (!automaticForeground) return
        val color = context.getPrimaryTextColor(
            ColorUtils.isColorLight(barBackgroundColor)
        )
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
        val tabUnselectedColor = context.getCompatColor(
            if (ColorUtils.isColorLight(barBackgroundColor)) {
                R.color.md_light_secondary
            } else {
                R.color.md_dark_secondary
            }
        )
        toolbar.findViewById<TabLayout>(R.id.tab_layout)
            ?.setTabTextColors(tabUnselectedColor, color)
        toolbar.menu.forEach { item ->
            (item.actionView as? SearchView)?.applyTint(color)
        }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
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
