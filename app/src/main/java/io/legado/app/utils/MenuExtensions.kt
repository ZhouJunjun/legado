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
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
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
    val defaultTextColor = context.getCompatColor(R.color.primaryText)
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
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
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
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
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
     * 菜单/图标的前景色。
     *
     * 原来非透明分支返回 `context.primaryTextColor` —— 那是**按 primaryColor 推导**的
     * (`isDarkTheme = isColorLight(primaryColor)`)。该推导成立的前提是"栏底色 == primaryColor",
     * 但顶栏底色早已改成 `bottomBackground`(浅色), 前提不成立:
     * 默认主题 primaryColor 是棕色系(偏暗) → 它返回 **白色 #FFFFFFFF**,
     * 于是栏底改亮了、图标和文字却还是白的(用户反馈的现象)。
     *
     * 现在改成按**栏位真实底色**判定深浅, 与 [io.legado.app.ui.widget.TitleBar.applyForegroundColor]
     * 的判据统一, 两个入口不会再互相覆盖出白字。
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
            else -> {
                // 栏位实际底色: 透明栏露出的是页面背景, 否则是底栏色。
                val barColor =
                    if (transparentBar) context.backgroundColor else context.bottomBackground
                context.getPrimaryTextColor(ColorUtils.isColorLight(barColor))
            }
        }
    }

}
