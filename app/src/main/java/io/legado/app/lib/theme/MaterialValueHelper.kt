@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = ThemeStore.backgroundColor(this)

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = getPrimaryTextColor(isDarkTheme)

@ColorInt
fun Context.getToolbarTextColor(transparentBar: Boolean): Int {
    val barColor = toolbarBackgroundColor(transparentBar, primaryColor, backgroundColor)
    return getPrimaryTextColor(ColorUtils.isColorLight(barColor))
}

/**
 * 顶部栏**实际底色**: 透明顶栏露出的是页面背景色, 否则是与底栏同源的 [bottomBackground]。
 *
 * 顶栏底色已从原来的 `primaryColor`(彩色) 改为底栏色, 所以任何"顶栏前景/状态栏图标该用深色
 * 还是浅色"的判断, 都必须以这个值为准 —— 用 `primaryColor` 推导会得出相反结论
 * (默认棕色 primary 偏暗 → 判成"深底"→ 给白色图标, 而顶栏其实是浅灰 → 图标看不见)。
 */
@ColorInt
fun Context.topBarBackgroundColor(): Int {
    return if (transparentNavBar) backgroundColor else bottomBackground
}

/**
 * 顶部栏底色是浅色吗 —— 状态栏图标/顶栏前景应否用深色。
 *
 * 状态栏区域在视觉上属于顶栏(顶栏会 `fitStatusBar` 撑到状态栏底下), 所以状态栏图标的
 * 明暗必须跟着顶栏底色走, 而不是跟着 `primaryColor` 走(用户 2026-09-21 反馈:
 * "换主题时也要根据首尾切换状态栏图标的颜色才对")。
 */
val Context.isTopBarLight: Boolean
    get() = ColorUtils.isColorLight(topBarBackgroundColor())

@ColorInt
internal fun toolbarBackgroundColor(
    transparentBar: Boolean,
    @ColorInt primaryColor: Int,
    @ColorInt backgroundColor: Int
): Int = if (transparentBar) backgroundColor else primaryColor

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = getSecondaryTextColor(isDarkTheme)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(isDarkTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(isDarkTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = ThemeStore.backgroundColor(requireContext())

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = requireContext().getPrimaryTextColor(isDarkTheme)

val Fragment.secondaryTextColor: Int
    get() = requireContext().getSecondaryTextColor(isDarkTheme)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(isDarkTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(isDarkTheme)

val Context.buttonDisabledColor: Int
    get() = if (isDarkTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = ColorUtils.isColorLight(ThemeStore.primaryColor(this))

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

/**
 * 栏位贴边分隔线颜色(顶栏下边线 / 底栏上边线 / 半屏面板上边线)。
 *
 * 亮色主题 #898989, 暗色主题 #5A5A5A —— 由 values/values-night 的 `bar_border` 决定,
 * 随系统夜间模式自动切换。
 */
@get:ColorInt
val Context.barBorderColor: Int
    get() = ContextCompat.getColor(this, R.color.bar_border)

/**
 * 栏位背景: 纯底色 + **单边** 1dp 实线。
 *
 * 只画一条边是因为需求里的线都是贴边的("头个下边框 / 尾的上边框 / 面板上边框"),
 * 用 `GradientDrawable.setStroke` 会四边全画, 不是想要的效果 —— 所以用 [LayerDrawable]
 * 叠一层实心色块, 再用 `setLayerHeight` + `setLayerGravity` 把它压到指定边。
 *
 * @param bgColor 栏位实际底色, 由调用方给出(通常是 `context.bottomBackground`),
 *                这样用户换肤后线与底色仍能对齐, 不会出现"底变了线不变"。
 * @param atTop true = 线在顶部(底栏/半屏面板); false = 线在底部(顶栏)。
 */
fun Context.barBorderBackground(@ColorInt bgColor: Int, atTop: Boolean): Drawable {
    val fill = ColorDrawable(bgColor)
    val line = ColorDrawable(barBorderColor)
    val layers = LayerDrawable(arrayOf(fill, line))
    // 填充层(0)不设尺寸 → 铺满 bounds; 线层(1)压到 1dp 高并贴到指定边。
    layers.setLayerHeight(1, 1.dpToPx())
    layers.setLayerGravity(1, if (atTop) Gravity.TOP else Gravity.BOTTOM)
    return layers
}

/**
 * 设置面板里方形小按钮的**常态底色**: 协调的淡灰, 无边框。
 *
 * 做法是在栏位贴边线色 [barBorderColor](#898989 / #5A5A5A) 上挂一个低 alpha, 让它叠在
 * 真实的栏位底色上 —— 这样浅色面板得到比底略深的灰、深色面板得到比底略亮的灰, 换肤后
 * 自动协调, 不需要分主题硬编码两套颜色。
 */
@get:ColorInt
val Context.buttonSurfaceColor: Int
    get() = ColorUtils.withAlpha(barBorderColor, BUTTON_SURFACE_ALPHA)

/** 上面这个底色的按下态(更实一点, 作为点击反馈)。 */
@get:ColorInt
val Context.buttonSurfacePressedColor: Int
    get() = ColorUtils.withAlpha(barBorderColor, BUTTON_SURFACE_PRESSED_ALPHA)

private const val BUTTON_SURFACE_ALPHA = 0.16f
private const val BUTTON_SURFACE_PRESSED_ALPHA = 0.3f

val Context.filletBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.cornerRadius = 3f.dpToPx()
        background.setColor(backgroundColor)
        return background
    }

/**
 * 弹出窗口背景(长按菜单 / 溢出菜单 / 下拉候选框): 纯底色 + 一圈 1dp 实线, **无圆角**。
 *
 * 规格与 `bg_popup_menu.xml`、`borderedDialogBackground` 保持一致:
 * 底色取 `bottomBackground`(与顶栏/底栏/半屏面板同源), 边框用 `bar_border`
 * (亮 #898989 / 暗 #5A5A5A)。用户明确不要圆角。
 *
 * 注意: 这里必须用 `setStroke` 画**一圈**线 —— 弹出面板是浮在内容之上的独立矩形,
 * 四面都需要边界; 而贴边的栏位线用 `barBorderBackground` 只画单边。
 */
val Context.popupBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.setColor(bottomBackground)
        background.setStroke(1.dpToPx(), barBorderColor)
        return background
    }

/**
 * 底部弹出的半屏设置面板背景: 纯底色 + 顶部一条 1dp 实心灰线(无圆角)。
 *
 * 规格来源: 用户明确要求"半屏面板的上边框设置成 1px, 颜色 #898989",
 * 并且"上一轮加的圆角去掉, 不喜欢圆角"。
 * 所以这里不再用 `cornerRadii`, 也不再是"描边一圈"——
 * 只有贴屏顶那一条边有线, 底部两角保持直角, 与阅读页设置面板的分割线风格一致。
 */
val Context.borderedDialogBackground: Drawable
    get() = barBorderBackground(bottomBackground, atTop = true)
