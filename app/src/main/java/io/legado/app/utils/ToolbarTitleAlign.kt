package io.legado.app.utils

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.tabs.TabLayout

/**
 * 顶栏标题的**垂直对齐校正**: 让标题墨迹中心与同行图标(返回箭头 / 菜单图标)中心齐平。
 *
 * 🐞 现象(用户 2026-09-24 首次反馈, 2026-09-30 反馈「有些页面还是没对齐」):
 * 标题文字看起来比同行的图标低, 实测低约 7dp。
 *
 * 根因不在布局, 而在**「视图框居中」与「墨迹居中」不是一回事**:
 * TextView 的框含 `includeFontPadding` 字体留白, 中文字体的字框又是
 * ascent≫descent 的不对称结构。Toolbar 把「框」居中, 墨迹就整体下沉
 *     Δ = (fm.ascent + fm.descent)/2 - (fm.top + fm.bottom)/2
 * 这个量跟字体走(不同设备/字体各不相同), 所以**不能写死一个 dp 偏移**。
 *
 * 这里不依赖 Toolbar 内部的居中公式(那是 AppCompat 私有实现, 版本间会变),
 * 而是直接量两个**已经画好的**锚点做自校正:
 *   · 基准 = 导航图标(返回箭头)的中心 —— 这正是用户肉眼拿来比较的对象;
 *   · 目标 = 标题首行的墨迹中心, 由 layout 的基线与字体度量解析求出。
 * 两者之差就是 translationY。全是 UI 线程上的常量级读写, 不触发重新布局。
 *
 * 🔴 **锚点绝不能用 `toolbar.getChildAt(0)`**(2026-09-30 修掉的坑):
 * Toolbar 的子项顺序由「导航按钮什么时候被加进来」决定 —— AppCompat 的导航按钮
 * 是在 `setNavigationIcon`(由 `setDisplayHomeAsUpEnabled(true)` 触发, 即
 * `TitleBar.attachToActivity()` / Activity 挂 ActionBar 时)才创建的, 而 XML 的
 * `app:title` 在 **Toolbar 构造期**就已把标题 TextView 加进去。于是:
 *   · 运行时设标题的页面(阅读页): 导航先入 → `child0` = 返回箭头 → 原方案成立;
 *   · XML `app:title` 的页面(绝大多数 Activity / 首页 / 我的): 标题先入 →
 *     `child0` = **标题自己** → 算出 Δ≈0 → 校正**静默失效**, 这就是
 *     「有些页面没对齐」的原因;
 *   · 弹框的 Toolbar 多数没有导航图标 → `child0` 同样是标题, 且此前完全没接校正。
 * 所以改为**按视图类型在 Toolbar 里找导航按钮**(`ImageView`),
 * 找不到(顶栏确实没有图标)时退回 Toolbar 自身中心 —— 那正是同行菜单图标的居中位置。
 */
fun Toolbar.alignTitleInk() {
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, l: Int, t: Int, r: Int, b: Int,
            ol: Int, ot: Int, or: Int, ob: Int
        ) {
            if (applyTitleInkOffset()) v.removeOnLayoutChangeListener(this)
        }
    })
    applyTitleInkOffset()
}

/**
 * 与 [alignTitleInk] 同法, 但对象是 `app:contentLayout` 塞进来的 **TabLayout 的页签文字**。
 *
 * 用途: 顶栏**没有标题、只有 tab** 的页面(目录页 `activity_chapter_list.xml`)。这类页面
 * [alignTitleInk] 是 no-op, 需要显式调一次, 且要在 `setupWithViewPager` 之后 ——
 * 页签 View 是那时才创建的。监听器会一直重试到页签真正量好为止。
 */
fun Toolbar.alignTabInk() {
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, l: Int, t: Int, r: Int, b: Int,
            ol: Int, ot: Int, or: Int, ob: Int
        ) {
            if (applyTabTextInkOffset()) v.removeOnLayoutChangeListener(this)
        }
    })
    applyTabTextInkOffset()
}

/**
 * 校正顶栏里文字的墨迹居中对齐。
 *
 * 目标按优先级:
 * 1. Toolbar 自带的**标题** TextView(常规页面);
 * 2. 兜底: `app:contentLayout` 里的 **TabLayout 页签文字**(目录页: 顶栏没有标题)。
 *
 * @return true 表示这次成功写入了偏移(锚点与目标都已量好)。
 */
fun Toolbar.applyTitleInkOffset(): Boolean {
    val titleApplied = applyTitleTextInkOffset()
    val tabApplied = applyTabTextInkOffset()
    return titleApplied || tabApplied
}

/** 标题 TextView 的墨迹校正。 @return true = 写入了偏移。 */
private fun Toolbar.applyTitleTextInkOffset(): Boolean {
    val tv = findTitleTextView() ?: return false
    val layout = tv.layout ?: return false
    if (layout.lineCount == 0 || tv.height == 0) return false
    val anchorCenter = anchorIconCenter() ?: return false
    val inkCenter = tv.inkCenterIn(this) ?: return false
    tv.translationY = anchorCenter - inkCenter
    return true
}

/**
 * 页签(TabLayout)文字的墨迹校正 —— 让每个页签文字的**墨迹中心**与导航图标中心齐平。
 *
 * 🐞 现象(用户 2026-09-30, bug8): 目录页「目录 / 书签 / 标注」三个 tab 的文字比同行
 * 的返回箭头、搜索、⋮ 都低。根因与标题那处**完全同类**: TabLayout 也是把页签文字的
 * **视图框**居中, 而中文墨迹在字框里偏下(ascent≫descent), 于是"框对齐了、墨迹没对齐"。
 *
 * 之所以必须单独写一段: 目录页的 Toolbar **没有标题**(contentLayout 放的是 TabLayout),
 * `findTitleTextView()` 返回 null ⇒ 原来的校正对整个页面 no-op。
 *
 * @return true 表示至少成功对齐了一个页签。
 */
private fun Toolbar.applyTabTextInkOffset(): Boolean {
    val tabLayout = findTabLayout() ?: return false
    val anchorCenter = anchorIconCenter() ?: return false
    var applied = false
    for (index in 0 until tabLayout.tabCount) {
        val tabView = tabLayout.getTabAt(index)?.view as? ViewGroup ?: continue
        val tv = findTabTextView(tabView) ?: continue
        val layout = tv.layout ?: continue
        if (layout.lineCount == 0 || tv.height == 0) continue
        val inkCenter = tv.inkCenterIn(this) ?: continue
        tv.translationY = anchorCenter - inkCenter
        applied = true
    }
    return applied
}

/** 找 `app:contentLayout` 塞进 Toolbar 的 TabLayout(目录页顶栏)。 */
private fun Toolbar.findTabLayout(): TabLayout? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is TabLayout) return child
    }
    return null
}

/**
 * 在页签视图里找**文字**控件。
 *
 * 页签(内部 TabView)是个 LinearLayout: 有图标时先放 ImageView, 文字是其中的 TextView。
 * 这里取第一个可见的 TextView, 天然跳过图标与"图标+文字"布局里的图标项。
 */
private fun findTabTextView(tabView: ViewGroup): TextView? {
    for (i in 0 until tabView.childCount) {
        val child = tabView.getChildAt(i)
        if (child is TextView && child.visibility == View.VISIBLE) return child
    }
    return null
}

/**
 * 文字**墨迹中心**(垂直)相对 [ancestor] 顶部的 y 值。
 *
 * 用 `top` 逐级累加定位, 而不是 `getLocationInWindow` —— `top` 是**布局位置**, 不含已写入的
 * `translationY`, 所以每次 layout 重算都得到同一结果, 不会"越校越偏"(幂等)。
 *
 * @return null 表示这个 View 不是 [ancestor] 的后代(还没挂上 / 已 detach), 或还没量好。
 */
private fun TextView.inkCenterIn(ancestor: View): Float? {
    var offset = 0
    var v: View = this
    while (v !== ancestor) {
        val parent = v.parent as? View ?: return null
        offset += v.top
        v = parent
    }
    val layout = layout ?: return null
    if (layout.lineCount == 0) return null
    val fm = paint.fontMetrics
    return offset + totalPaddingTop + layout.getLineBaseline(0) +
        (fm.ascent + fm.descent) / 2f
}

/**
 * 对齐基准的垂直中心(相对 Toolbar):
 * 优先导航图标(返回箭头) —— 用户肉眼比较的对象就是它;
 * 顶栏没有图标时退回 Toolbar 自身中心。
 */
private fun Toolbar.anchorIconCenter(): Float? {
    val nav = findNavigationView()
    if (nav != null && nav.width > 0 && nav.height > 0) {
        return nav.top + nav.height / 2f
    }
    if (height > 0) return height / 2f
    return null
}

/**
 * 找导航按钮: AppCompat 的导航按钮是 `AppCompatImageButton`, 属 `ImageView`。
 * 返回 Toolbar 内的第一个 `ImageView`(子项顺序可能是 [标题, 导航, …] 或 [导航, 标题, …],
 * 两种情况下的第一个 ImageView 都是导航按钮)。
 *
 * 排除「某个菜单项展开成的 ActionView」——例如搜索框、TabLayout:
 * 它们当前都不是 `ImageView`(SearchView / TabLayout 根节点), 但一旦将来被包进
 * 带图标的 ViewGroup, 就可能被误判成导航按钮。这里直接跳过 ActionView 包裹,
 * 只认 Toolbar **自己的**直接子项。
 */
private fun Toolbar.findNavigationView(): ImageView? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        // 导航按钮是 Toolbar 的**直接**子项; ActionMenuView 内的菜单图标父项是 ActionMenuView,
        // layoutParams 是 ActionMenuView.LayoutParams ⇒ 据此排除。
        val parentLp = (child.parent as? View)?.layoutParams
        if (child is ImageView && parentLp !is ActionMenuView.LayoutParams) return child
    }
    return null
}

/** 找标题 TextView(Toolbar 自己 new 出来的 `AppCompatTextView`, 通常是第一个)。 */
private fun Toolbar.findTitleTextView(): AppCompatTextView? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is AppCompatTextView) return child
    }
    return null
}
