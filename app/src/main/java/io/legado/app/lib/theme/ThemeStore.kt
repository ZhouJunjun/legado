package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.AttrRes
import androidx.annotation.CheckResult
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.utils.ColorUtils
import splitties.init.appCtx
import androidx.core.graphics.toColorInt
import androidx.core.content.edit

/**
 * @author Aidan Follestad (afollestad), Karim Abou Zeid (kabouzeid)
 */
@Suppress("unused")
class ThemeStore @SuppressLint("CommitPrefEdits")
private constructor(private val mContext: Context) {

    private val mEditor = prefs(mContext).edit()

    fun primaryColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, color)
        if (autoGeneratePrimaryDark(mContext))
            primaryColorDark(ColorUtils.darkenColor(color))
        return this
    }

    fun primaryColorRes(@ColorRes colorRes: Int): ThemeStore {
        return primaryColor(ContextCompat.getColor(mContext, colorRes))
    }

    fun primaryColorAttr(@AttrRes colorAttr: Int): ThemeStore {
        return primaryColor(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun primaryColorDark(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR_DARK, color)
        return this
    }

    fun primaryColorDarkRes(@ColorRes colorRes: Int): ThemeStore {
        return primaryColorDark(ContextCompat.getColor(mContext, colorRes))
    }

    fun primaryColorDarkAttr(@AttrRes colorAttr: Int): ThemeStore {
        return primaryColorDark(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun accentColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, color)
        return this
    }

    fun accentColorRes(@ColorRes colorRes: Int): ThemeStore {
        return accentColor(ContextCompat.getColor(mContext, colorRes))
    }

    fun accentColorAttr(@AttrRes colorAttr: Int): ThemeStore {
        return accentColor(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun statusBarColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, color)
        return this
    }

    fun statusBarColorRes(@ColorRes colorRes: Int): ThemeStore {
        return statusBarColor(ContextCompat.getColor(mContext, colorRes))
    }

    fun statusBarColorAttr(@AttrRes colorAttr: Int): ThemeStore {
        return statusBarColor(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun navigationBarColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, color)
        return this
    }

    fun navigationBarColorRes(@ColorRes colorRes: Int): ThemeStore {
        return navigationBarColor(ContextCompat.getColor(mContext, colorRes))
    }

    fun navigationBarColorAttr(@AttrRes colorAttr: Int): ThemeStore {
        return navigationBarColor(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun textColorPrimary(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY, color)
        return this
    }

    fun textColorPrimaryRes(@ColorRes colorRes: Int): ThemeStore {
        return textColorPrimary(ContextCompat.getColor(mContext, colorRes))
    }

    fun textColorPrimaryAttr(@AttrRes colorAttr: Int): ThemeStore {
        return textColorPrimary(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun textColorPrimaryInverse(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY_INVERSE, color)
        return this
    }

    fun textColorPrimaryInverseRes(@ColorRes colorRes: Int): ThemeStore {
        return textColorPrimaryInverse(ContextCompat.getColor(mContext, colorRes))
    }

    fun textColorPrimaryInverseAttr(@AttrRes colorAttr: Int): ThemeStore {
        return textColorPrimaryInverse(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun textColorSecondary(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY, color)
        return this
    }

    fun textColorSecondaryRes(@ColorRes colorRes: Int): ThemeStore {
        return textColorSecondary(ContextCompat.getColor(mContext, colorRes))
    }

    fun textColorSecondaryAttr(@AttrRes colorAttr: Int): ThemeStore {
        return textColorSecondary(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun textColorSecondaryInverse(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY_INVERSE, color)
        return this
    }

    fun textColorSecondaryInverseRes(@ColorRes colorRes: Int): ThemeStore {
        return textColorSecondaryInverse(ContextCompat.getColor(mContext, colorRes))
    }

    fun textColorSecondaryInverseAttr(@AttrRes colorAttr: Int): ThemeStore {
        return textColorSecondaryInverse(ThemeUtils.resolveColor(mContext, colorAttr))
    }

    fun backgroundColor(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, color)
        return this
    }

    fun bottomBackground(@ColorInt color: Int): ThemeStore {
        mEditor.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, color)
        return this
    }

    fun transparentNavBar(transparent: Boolean): ThemeStore {
        mEditor.putBoolean(ThemeStorePrefKeys.KEY_TRANSPARENT_NAV_BAR, transparent)
        return this
    }

    fun autoGeneratePrimaryDark(autoGenerate: Boolean): ThemeStore {
        mEditor.putBoolean(ThemeStorePrefKeys.KEY_AUTO_GENERATE_PRIMARYDARK, autoGenerate)
        return this
    }

    // Commit method

    fun apply() {
        mEditor.putLong(ThemeStorePrefKeys.VALUES_CHANGED, System.currentTimeMillis())
            .putBoolean(ThemeStorePrefKeys.IS_CONFIGURED_KEY, true)
            .apply()
        accentColor = accentColor()
    }

    companion object {

        var accentColor = accentColor()

        fun editTheme(context: Context): ThemeStore {
            return ThemeStore(context)
        }

        // Static getters

        @CheckResult
        internal fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(
                ThemeStorePrefKeys.CONFIG_PREFS_KEY_DEFAULT,
                Context.MODE_PRIVATE
            )
        }

        fun markChanged(context: Context) {
            ThemeStore(context).apply()
        }

        @CheckResult
        @ColorInt
        fun primaryColor(context: Context = appCtx): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_PRIMARY_COLOR,
                ThemeUtils.resolveColor(
                    context,
                    androidx.appcompat.R.attr.colorPrimary,
                    "#455A64".toColorInt()
                )
            )
        }

        @CheckResult
        @ColorInt
        fun primaryColorDark(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_PRIMARY_COLOR_DARK,
                ThemeUtils.resolveColor(
                    context,
                    androidx.appcompat.R.attr.colorPrimaryDark,
                    "#37474F".toColorInt()
                )
            )
        }

        @CheckResult
        @ColorInt
        fun accentColor(context: Context = appCtx): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_ACCENT_COLOR,
                ThemeUtils.resolveColor(
                    context,
                    androidx.appcompat.R.attr.colorAccent,
                    "#263238".toColorInt()
                )
            )
        }

        @CheckResult
        @ColorInt
        fun statusBarColor(context: Context, transparent: Boolean): Int {
            return if (transparent) {
                prefs(context).getInt(
                    ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR,
                    primaryColor(context)
                )
            } else {
                prefs(context).getInt(
                    ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR,
                    primaryColorDark(context)
                )
            }
        }

        @CheckResult
        @ColorInt
        fun navigationBarColor(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR,
                bottomBackground(context)
            )
        }

        @CheckResult
        @ColorInt
        fun textColorPrimary(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY,
                ThemeUtils.resolveColor(context, android.R.attr.textColorPrimary)
            )
        }

        @CheckResult
        @ColorInt
        fun textColorPrimaryInverse(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_PRIMARY_INVERSE,
                ThemeUtils.resolveColor(context, android.R.attr.textColorPrimaryInverse)
            )
        }

        @CheckResult
        @ColorInt
        fun textColorSecondary(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY,
                ThemeUtils.resolveColor(context, android.R.attr.textColorSecondary)
            )
        }

        @CheckResult
        @ColorInt
        fun textColorSecondaryInverse(context: Context): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_TEXT_COLOR_SECONDARY_INVERSE,
                ThemeUtils.resolveColor(context, android.R.attr.textColorSecondaryInverse)
            )
        }

        @CheckResult
        @ColorInt
        fun backgroundColor(context: Context = appCtx): Int {
            return prefs(context).getInt(
                ThemeStorePrefKeys.KEY_BACKGROUND_COLOR,
                ThemeUtils.resolveColor(context, android.R.attr.colorBackground)
            )
        }

        /**
         * 旧版本「栏位颜色」的默认值 md_grey_200 = #EEEEEE。
         *
         * 它是一个**固定亮色值**(没有 values-night 变体), 且被 [io.legado.app.lib.prefs.ColorPreference]
         * 的 `onSetInitialValue` 无条件持久化过 —— 用户只要打开过一次主题设置页, 该值就会盖掉
         * `bar_background` 的新默认值, 于是亮色主题下栏位一直是 #EEEEEE(与弹出面板/半屏面板的
         * #F6F6F6 不一致), 暗色主题下栏位还会是浅灰。
         * 见 [normalizeBottomBackground]。
         */
        internal val LEGACY_BOTTOM_BACKGROUND = 0xFFEEEEEE.toInt()

        /**
         * 把「恰好等于旧默认值」的残留 pref 归一到 [fallback]。
         *
         * 只归一旧默认值本身, 用户自定义的其它颜色原样保留。
         *
         * [fallback] 必须由调用方显式给出(而不是在这里取 `R.color.bar_background`):
         * `ThemeConfig.applyTheme` 跑在 `initNightMode()` **之前**, 此时 resources 的 uiMode 还是
         * 上一个主题的值, 在这里取带 night 变体的资源会拿到错误颜色并被写进 pref。
         */
        @CheckResult
        @ColorInt
        internal fun normalizeBottomBackground(
            @ColorInt color: Int,
            @ColorInt fallback: Int
        ): Int {
            return if (color == LEGACY_BOTTOM_BACKGROUND) fallback else color
        }

        @CheckResult
        @ColorInt
        fun bottomBackground(context: Context = appCtx): Int {
            // 默认取 bar_background: 亮色 #F6F6F6 / 暗色 #303030。
            // 顶栏、底栏、书架朗读迷你条、阅读菜单头尾、半屏设置面板**共用**这一个值,
            // 所以改这里就能让这些栏位整体统一成同一底色(用户 2026-09-21 的规格)。
            // 注意用 ContextCompat.getColor 而非 ThemeUtils.resolveColor —— 后者收的是 attr 资源。
            // 这里是**渲染期**调用, uiMode 已是当前主题, 所以取带 night 变体的资源是正确的。
            val fallback = ContextCompat.getColor(context, R.color.bar_background)
            val color = prefs(context).getInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, fallback)
            return normalizeBottomBackground(color, fallback)
        }

        @CheckResult
        fun coloredStatusBar(context: Context): Boolean {
            return prefs(context).getBoolean(
                ThemeStorePrefKeys.KEY_APPLY_PRIMARYDARK_STATUSBAR,
                true
            )
        }

        @CheckResult
        fun transparentNavBar(context: Context): Boolean {
            return prefs(context).getBoolean(ThemeStorePrefKeys.KEY_TRANSPARENT_NAV_BAR, false)
        }

        @CheckResult
        fun coloredNavigationBar(context: Context): Boolean {
            return prefs(context).getBoolean(ThemeStorePrefKeys.KEY_APPLY_PRIMARY_NAVBAR, false)
        }

        @CheckResult
        fun autoGeneratePrimaryDark(context: Context): Boolean {
            return prefs(context).getBoolean(ThemeStorePrefKeys.KEY_AUTO_GENERATE_PRIMARYDARK, true)
        }

        @CheckResult
        fun isConfigured(context: Context): Boolean {
            return prefs(context).getBoolean(ThemeStorePrefKeys.IS_CONFIGURED_KEY, false)
        }

        @SuppressLint("CommitPrefEdits")
        fun isConfigured(context: Context, version: Int): Boolean {
            val prefs = prefs(context)
            val lastVersion = prefs.getInt(ThemeStorePrefKeys.IS_CONFIGURED_VERSION_KEY, -1)
            if (version > lastVersion) {
                prefs.edit { putInt(ThemeStorePrefKeys.IS_CONFIGURED_VERSION_KEY, version) }
                return false
            }
            return true
        }
    }
}
