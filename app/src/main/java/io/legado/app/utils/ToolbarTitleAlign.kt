package io.legado.app.utils

import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar

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

/** @return true 表示这次成功写入了偏移(锚点与标题都已量好)。 */
fun Toolbar.applyTitleInkOffset(): Boolean {
    val tv = findTitleTextView() ?: return false
    val layout = tv.layout ?: return false
    if (layout.lineCount == 0 || tv.height == 0) return false
    val anchorCenter = anchorIconCenter() ?: return false
    // 墨迹中心(相对 Toolbar) = 标题距 Toolbar 顶的偏移 + 上内边距 + 首行基线 + 半字高
    // 用 layout 的基线而不是「框高/2」, 这样即使 TextView 带上下 padding 或多行也不会算错。
    val fm = tv.paint.fontMetrics
    val baseline = tv.totalPaddingTop + layout.getLineBaseline(0).toFloat()
    val inkCenter = tv.top + baseline + (fm.ascent + fm.descent) / 2f
    tv.translationY = anchorCenter - inkCenter
    return true
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
