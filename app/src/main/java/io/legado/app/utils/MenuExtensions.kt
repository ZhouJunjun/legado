@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.appcompat.widget.SearchView
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.barForegroundColor
import io.legado.app.lib.theme.dialogForegroundColor
import java.lang.reflect.Method

@SuppressLint("RestrictedApi")
@Suppress("UsePropertyAccessSyntax")
fun Menu.applyTint(
    context: Context,
    theme: Theme = Theme.Auto,
    transparentBar: Boolean = false
): Menu = this.let { menu ->
    if (menu is MenuBuilder) {
        menu.setOptionalIconsVisible(true)
    }
    // 收进溢出菜单的项最终显示在**弹框**里(底色 = 弹框背景色), 所以用弹框前景色;
    // 直接显示在栏位上的项用栏位前景色。两者以前都取 R.color.primaryText,
    // 栏位前景色接上「顶栏底栏文字与图标颜色」后会串色。
    val defaultTextColor = context.dialogForegroundColor
    val tintColor = MenuExtensions.getMenuColor(
        context,
        theme,
        transparentBar = transparentBar
    )
    menu.forEach { item ->
        (item as MenuItemImpl).let { impl ->
            //overflow：展开的item
            impl.icon?.setTintMutate(
                if (impl.requiresOverflow()) defaultTextColor else tintColor
            )
            (impl.actionView as? SearchView)?.applyTint(tintColor)
        }
    }
    return menu
}

@SuppressLint("RestrictedApi")
fun Menu.applyOpenTint(context: Context, showIcon: Boolean = true) {
    //展开菜单显示图标
    if (this.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
        // 展开后这些项显示在弹框里 → 用「弹框文字与图标颜色」。
        val defaultTextColor = context.dialogForegroundColor
        kotlin.runCatching {
            var method: Method =
                this.javaClass.getDeclaredMethod("setOptionalIconsVisible", java.lang.Boolean.TYPE)
            method.isAccessible = true
            method.invoke(this, showIcon)
            if (showIcon) {
                method = this.javaClass.getDeclaredMethod("getNonActionItems")
                val menuItems = method.invoke(this)
                if (menuItems is ArrayList<*>) {
                    for (menuItem in menuItems) {
                        if (menuItem is MenuItem) {
                            menuItem.icon?.setTintMutate(defaultTextColor)
                        }
                    }
                }
            }
        }
    } else if (this.javaClass.simpleName.equals("SubMenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.dialogForegroundColor
        (this as? SubMenuBuilder)?.forEach { item: MenuItem ->
            item.icon?.setTintMutate(defaultTextColor)
        }
    }
}

fun Menu.iconItemOnLongClick(id: Int, function: (view: View) -> Unit) {
    findItem(id)?.let { item ->
        item.setActionView(R.layout.view_action_button)
        item.actionView?.run {
            contentDescription = item.title
            findViewById<ImageButton>(R.id.item).setImageDrawable(item.icon)
            setOnLongClickListener {
                function.invoke(this)
                true
            }
            setOnClickListener {
                performIdentifierAction(id, 0)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
inline fun Menu.transaction(block: (Menu) -> Unit) {
    val menuBuilder = this as? MenuBuilder
    menuBuilder?.stopDispatchingItemsChanged()
    try {
        block(this)
    } finally {
        menuBuilder?.startDispatchingItemsChanged()
    }
}

object MenuExtensions {

    /**
     * 菜单/图标的前景色 = **栏位前景色**。
     *
     * 历史坑(两层):
     * 1. 最早返回 `context.primaryTextColor` —— 按 `primaryColor` 推导
     *    (`isDarkTheme = isColorLight(primaryColor)`)。该推导成立的前提是"栏底色 == primaryColor",
     *    但顶栏底色早已改成 `bottomBackground`(浅色), 前提不成立: 默认主题 primaryColor 是
     *    棕色系(偏暗) → 返回**白色** → 栏底改亮了图标还是白的。
     * 2. 之后改成按**栏位真实底色**反推(`getPrimaryTextColor(isColorLight(barColor))`),
     *    解决了"白图标", 但**没接上用户自定义的「顶栏底栏文字与图标颜色」** ——
     *    栏位底是深蓝时反推出白图标, 而用户指定的是黄色 → 同一顶栏里
     *    "⋮ 是黄的、? 是白的"(用户 2026-09-30 反馈, bug10)。
     *
     * 现在统一走 [Context.barForegroundColor](用户指定优先, 未指定才按栏位底色反推),
     * 这是**全库菜单图标的总入口**(BaseActivity / BaseFragment 各自调 [applyTint]),
     * 改这里所有顶栏、底栏的菜单图标一起对齐。
     *
     * @param transparentBar 透明顶栏(露出页面背景色)时为 true。现在**已不影响取色** ——
     *        [Context.barForegroundColor] 内部按 [Context.topBarBackgroundColor] 自动处理透明栏;
     *        保留参数只为兼容既有调用点。
     */
    fun getMenuColor(
        context: Context,
        theme: Theme = Theme.Auto,
        requiresOverflow: Boolean = false,
        transparentBar: Boolean = false
    ): Int {
        return when (theme) {
            Theme.Dark -> context.getCompatColor(R.color.md_white_1000)
            Theme.Light -> context.getCompatColor(R.color.md_black_1000)
            // 栏位前景色: 用户指定优先, 否则按栏位真实底色(透明栏则是页面背景色)反推。
            else -> context.barForegroundColor
        }
    }

}
