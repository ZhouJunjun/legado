package io.legado.app.ui.widget.text

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.R
import io.legado.app.lib.theme.*
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

@Suppress("unused")
open class StrokeTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs) {

    private var radius = 1.dpToPx()
    private val isBottomBackground: Boolean
    private val panelButtonStyle: Boolean

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.StrokeTextView)
        radius = typedArray.getDimensionPixelOffset(R.styleable.StrokeTextView_radius, radius)
        isBottomBackground =
            typedArray.getBoolean(R.styleable.StrokeTextView_isBottomBackground, false)
        panelButtonStyle =
            typedArray.getBoolean(R.styleable.StrokeTextView_panelButtonStyle, false)
        typedArray.recycle()
        upBackground()
    }

    fun setRadius(radius: Int) {
        this.radius = radius.dpToPx()
        upBackground()
    }

    private fun upBackground() {
        when {
            isInEditMode -> {
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(context.getCompatColor(R.color.secondaryText))
                    .setSelectedStrokeColor(context.getCompatColor(R.color.accent))
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.getCompatColor(R.color.secondaryText))
                        .setSelectedColor(context.getCompatColor(R.color.accent))
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
            panelButtonStyle -> {
                // 设置面板方形按钮: 淡灰底 + 无边框, 选中态用 accent 底。
                // 文字色走「弹框文字与图标颜色」—— 这些按钮只出现在阅读页的弹框面板里
                // (dialog_read_book_style / dialog_read_bg_text / dialog_read_padding),
                // 面板底色是「弹框背景色」, 前景必须跟它同源(用户 2026-09-30)。
                // 之前按栏位底色反推, 栏位底是深色时按钮文字整片发白。
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setDefaultBgColor(context.buttonSurfaceColor)
                    .setPressedBgColor(context.buttonSurfacePressedColor)
                    .setSelectedBgColor(context.accentColor)
                    .setDisabledBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.dialogForegroundColor)
                        .setSelectedColor(
                            if (ColorUtils.isColorLight(context.accentColor)) {
                                android.graphics.Color.BLACK
                            } else {
                                android.graphics.Color.WHITE
                            }
                        )
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
            isBottomBackground -> {
                // 同上: 带描边的面板文字按钮(如边距面板的 ± 数值按钮)也在弹框面板里。
                val textColor = context.dialogForegroundColor
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(textColor)
                    .setSelectedStrokeColor(context.accentColor)
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(textColor)
                        .setSelectedColor(context.accentColor)
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
            else -> {
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(ThemeStore.textColorSecondary(context))
                    .setSelectedStrokeColor(ThemeStore.accentColor(context))
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(ThemeStore.textColorSecondary(context))
                        .setSelectedColor(ThemeStore.accentColor(context))
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
        }
    }
}
