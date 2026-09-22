package io.legado.app.lib.theme.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonSurfaceColor
import io.legado.app.lib.theme.buttonSurfacePressedColor
import io.legado.app.lib.theme.getPrimaryTextColor
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
                val isLight = ColorUtils.isColorLight(context.bottomBackground)
                background = Selector.shapeBuild()
                    .setCornerRadius(2.dpToPx())
                    .setDefaultBgColor(context.buttonSurfaceColor)
                    .setPressedBgColor(context.buttonSurfacePressedColor)
                    .setCheckedBgColor(accentColor)
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.getPrimaryTextColor(isLight))
                        .setCheckedColor(checkedTextColor)
                        .create()
                )
            }
            isBottomBackground -> {
                val isLight = ColorUtils.isColorLight(context.bottomBackground)
                val textColor = context.getPrimaryTextColor(isLight)
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
