package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.dialogForegroundColor


class NameListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {

    private val isBottomBackground: Boolean

    init {
        layoutResource = R.layout.view_preference
        widgetLayoutResource = R.layout.item_fillet_text
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.Preference)
        isBottomBackground = typedArray.getBoolean(R.styleable.Preference_isBottomBackground, false)
        typedArray.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val v = Preference.bindView<TextView>(
            context, holder, icon, title, summary, widgetLayoutResource,
            R.id.text_view, isBottomBackground = isBottomBackground
        )
        if (v is TextView) {
            v.text = entry
            if (isBottomBackground) {
                // 与 Preference.bindView 同一口径: 半屏面板里的右侧取值文字走
                // 「弹框文字与图标颜色」(用户 2026-09-30), 不再按栏位底色反推。
                v.setTextColor(context.dialogForegroundColor)
            }
        }
        super.onBindViewHolder(holder)
    }

}