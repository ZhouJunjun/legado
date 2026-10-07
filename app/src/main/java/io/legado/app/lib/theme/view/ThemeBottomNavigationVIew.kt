package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.barBorderBackground
import io.legado.app.lib.theme.barForegroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.dpToPx

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    private val themeIconTint: ColorStateList

    /** menu id -> 默认矢量图标; applySkin(null) 时据此还原 */
    private val defaultIcons = mapOf(
        R.id.menu_bookshelf to R.drawable.ic_bottom_books,
        R.id.menu_my_config to R.drawable.ic_bottom_person,
    )

    init {
        val transparentNavBar = context.transparentNavBar
        val bgColor = if (transparentNavBar) context.backgroundColor else context.bottomBackground
        if (transparentNavBar) {
            setBackgroundColor(Color.TRANSPARENT)
        } else {
            // 上边线 1dp 实心灰: 底栏是屏幕最底部的一条独立栏位, 与上方内容需要明确分界。
            background = context.barBorderBackground(bgColor, atTop = true)
            elevation = context.elevation
        }
        // 未选中项 = 「顶栏底栏文字与图标颜色」(用户 2026-09-30 bug9: 底栏非选中图标应用该颜色)。
        // 原来取 getSecondaryTextColor(按栏位底色反推的灰), 既没接上用户自定义色,
        // 栏位底是深色时还会反推出白色 —— 与旁边的选中态(accent)完全脱节。
        val textColor = context.barForegroundColor
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
        themeIconTint = colorStateList
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        if (transparentNavBar) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = Color.TRANSPARENT.toDrawable()
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    /**
     * 应用/取消底栏图集。
     * @param iconMap menu id -> StateListDrawable; 传 null 表示恢复默认主题图标
     * @param iconSizePx 有皮肤时的图标尺寸(像素)
     */
    fun applySkin(iconMap: Map<Int, StateListDrawable>?, iconSizePx: Int) {
        if (iconMap == null) {
            itemIconTintList = themeIconTint
            itemIconSize = 24.dpToPx()
            defaultIcons.forEach { (id, res) ->
                menu.findItem(id)?.icon = defaultIcon(res, tinted = false)
            }
        } else {
            itemIconTintList = null
            itemIconSize = iconSizePx
            defaultIcons.keys.forEach { id ->
                menu.findItem(id)?.icon =
                    iconMap[id] ?: defaultIcon(defaultIcons.getValue(id), tinted = true)
            }
        }
    }

    private fun defaultIcon(resId: Int, tinted: Boolean) =
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            if (tinted) DrawableCompat.setTintList(this, themeIconTint)
        }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
