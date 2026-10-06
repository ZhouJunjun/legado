package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.buttonSurfaceColor
import io.legado.app.lib.theme.buttonSurfacePressedColor
import io.legado.app.lib.theme.dialogForegroundColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class ThemeRadioNoButton(context: Context, attrs: AttributeSet) :
    AppCompatRadioButton(context, attrs) {

    private val isBottomBackground: Boolean
    private val panelButtonStyle: Boolean

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ThemeRadioNoButton)
        isBottomBackground =
            typedArray.getBoolean(R.styleable.ThemeRadioNoButton_isBottomBackground, false)
        panelButtonStyle =
            typedArray.getBoolean(R.styleable.ThemeRadioNoButton_panelButtonStyle, false)
        typedArray.recycle()
        initTheme()
        TooltipCompat.setTooltipText(this, text)
    }

    private fun initTheme() {
        if (isInEditMode) return
        val accentColor = context.accentColor
        val checkedTextColor = if (ColorUtils.isColorLight(accentColor)) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        when {
            panelButtonStyle -> {
                // 与 StrokeTextView 同一套"面板方形按钮"规格: 无选中时淡灰底无边框。
                // 未选中文字色走「弹框文字与图标颜色」—— 这些按钮只出现在阅读页弹框面板
                // (如界面面板的「翻页动画」), 面板底色是「弹框背景色」, 前景必须同源。
                background = Selector.shapeBuild()
                    .setCornerRadius(2.dpToPx())
                    .setDefaultBgColor(context.buttonSurfaceColor)
                    .setPressedBgColor(context.buttonSurfacePressedColor)
                    .setCheckedBgColor(accentColor)
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.dialogForegroundColor)
                        .setCheckedColor(checkedTextColor)
                        .create()
                )
            }
            isBottomBackground -> {
                val textColor = context.dialogForegroundColor
                background = Selector.shapeBuild()
                    .setCornerRadius(2.dpToPx())
                    .setStrokeWidth(2.dpToPx())
                    .setCheckedBgColor(accentColor)
                    .setCheckedStrokeColor(accentColor)
                    .setDefaultStrokeColor(textColor)
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(textColor)
                        .setCheckedColor(checkedTextColor)
                        .create()
                )
            }
            else -> {
                val defaultTextColor = context.getCompatColor(R.color.primaryText)
                background = Selector.shapeBuild()
                    .setCornerRadius(2.dpToPx())
                    .setStrokeWidth(2.dpToPx())
                    .setCheckedBgColor(accentColor)
                    .setCheckedStrokeColor(accentColor)
                    .setDefaultStrokeColor(defaultTextColor)
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(defaultTextColor)
                        .setCheckedColor(checkedTextColor)
                        .create()
                )
            }
        }

    }

}
