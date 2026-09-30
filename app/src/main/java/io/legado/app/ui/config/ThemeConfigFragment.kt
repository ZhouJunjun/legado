package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.annotation.ColorInt
import androidx.core.view.MenuProvider
import androidx.preference.Preference
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.applyTint
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream
import kotlin.math.roundToInt


@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider,
    ColorPickerDialogListener {

    private val requestCodeBgLight = 121
    private val requestCodeBgDark = 122

    /**
     * 正在等待自定义颜色的前景色键。
     *
     * 栏位前景色(cBForeground/cNBForeground)与弹框前景色(cDForeground/cNDForeground)
     * 共用同一个系统取色器, 所以用同一个待定键记录"这次是谁在选色"。
     */
    private var pendingForegroundKey: String? = null
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeBgLight -> setBgFromUri(uri, PreferKey.bgImage) {
                    upTheme(false)
                }

                requestCodeBgDark -> setBgFromUri(uri, PreferKey.bgImageN) {
                    upTheme(true)
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        if (Build.VERSION.SDK_INT < 26) {
            preferenceScreen.removePreferenceRecursively(PreferKey.launcherIcon)
        }
        if (!WallpaperTheme.isAvailable()) {
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorFollow)
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorAutoUpdate)
        }
        upPreferenceSummary(PreferKey.bgImage, getPrefString(PreferKey.bgImage))
        upPreferenceSummary(PreferKey.bgImageN, getPrefString(PreferKey.bgImageN))
        upPreferenceSummary(PreferKey.barElevation, AppConfig.elevation.toString())
        upPreferenceSummary(PreferKey.fontScale)
        // 前景色四项的副标题是"自动/深色/浅色/#色值", 无法由 XML 静态给出, 进页面时先刷一遍。
        upForegroundSummary(PreferKey.cBForeground)
        upForegroundSummary(PreferKey.cNBForeground)
        upForegroundSummary(PreferKey.cDForeground)
        upForegroundSummary(PreferKey.cNDForeground)
        findPreference<ColorPreference>(PreferKey.cBackground)?.let {
            it.onSaveColor = { color ->
                if (!ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.day_background_too_dark)
                    true
                } else {
                    false
                }
            }
        }
        findPreference<ColorPreference>(PreferKey.cNBackground)?.let {
            it.onSaveColor = { color ->
                if (ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.night_background_too_light)
                    true
                } else {
                    false
                }
            }
        }
        findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val autoUpdate = getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)
                if (!WallpaperTheme.setFollow(requireContext(), enabled, autoUpdate)) {
                    toastOnUi(R.string.wallpaper_colors_unavailable)
                    false
                } else {
                    true
                }
            }
        findPreference<SwitchPreference>(PreferKey.wallpaperColorAutoUpdate)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (getPrefBoolean(PreferKey.wallpaperColorFollow)) {
                    val updated = WallpaperTheme.setFollow(
                        requireContext(),
                        enabled = true,
                        autoUpdate = newValue as Boolean,
                    )
                    if (!updated) {
                        toastOnUi(R.string.wallpaper_colors_unavailable)
                        return@setOnPreferenceChangeListener false
                    }
                }
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        listView.setEdgeEffectColor(bottomBackground)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        // 进程被杀后重建: 取色器 dialog 会由 FragmentManager 自动恢复,
        // 但监听器不随状态保存, 需按 tag 找回重新挂上(与 ColorPreference 的既有约定一致)。
        (childFragmentManager.findFragmentByTag(FOREGROUND_PICKER_TAG) as? ColorPickerDialog)
            ?.setColorPickerDialogListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(requireContext())
                return true
            }
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.wallpaperColorFollow ->
                findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)?.isChecked =
                    getPrefBoolean(PreferKey.wallpaperColorFollow)
            PreferKey.transparentStatusBar,
            PreferKey.immNavigationBar,
            PreferKey.disablePredictiveBack -> recreateActivities()
            PreferKey.cAccent,
            PreferKey.cBackground,
            PreferKey.cBBackground,
            PreferKey.tNavBar -> {
                if (WallpaperTheme.isApplyingColors) return
                if (key != PreferKey.tNavBar) {
                    WallpaperTheme.onColorPreferenceChanged(requireContext())
                }
                upTheme(false)
            }

            PreferKey.cNAccent,
            PreferKey.cNBackground,
            PreferKey.cNBBackground,
            PreferKey.tNavBarN -> {
                if (WallpaperTheme.isApplyingColors) return
                if (key != PreferKey.tNavBarN) {
                    WallpaperTheme.onColorPreferenceChanged(requireContext())
                }
                upTheme(true)
            }

            PreferKey.bgImage,
            PreferKey.bgImageN -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            // 弹框背景色: 参与主题重建即可, 不必触发壁纸配色联动(它不是壁纸跟随项)。
            PreferKey.cDBackground -> upTheme(false)
            PreferKey.cNDBackground -> upTheme(true)

            PreferKey.cBForeground -> upForegroundSummary(PreferKey.cBForeground)
            PreferKey.cNBForeground -> upForegroundSummary(PreferKey.cNBForeground)
            PreferKey.cDForeground -> upForegroundSummary(PreferKey.cDForeground)
            PreferKey.cNDForeground -> upForegroundSummary(PreferKey.cNDForeground)
        }

    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (val key = preference.key) {
            PreferKey.barElevation -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.bar_elevation))
                .setMaxValue(32)
                .setMinValue(0)
                .setValue(AppConfig.elevation)
                .setCustomButton((R.string.btn_default_s)) {
                    AppConfig.elevation = AppConst.sysElevation
                    recreateActivities()
                }
                .show {
                    AppConfig.elevation = it
                    recreateActivities()
                }

            PreferKey.fontScale -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(
                    (AppContextWrapper.getFontScale(requireContext()) * 10)
                        .roundToInt()
                        .coerceIn(8, 16)
                )
                .setCustomButton((R.string.btn_default_s)) {
                    putPrefInt(PreferKey.fontScale, 0)
                    recreateActivities()
                }
                .show {
                    putPrefInt(PreferKey.fontScale, it)
                    recreateActivities()
                }

            PreferKey.bgImage -> selectBgAction(false)
            PreferKey.bgImageN -> selectBgAction(true)
            PreferKey.cBForeground -> selectForegroundAction(PreferKey.cBForeground, bar = true)
            PreferKey.cNBForeground -> selectForegroundAction(PreferKey.cNBForeground, bar = true)
            PreferKey.cDForeground -> selectForegroundAction(PreferKey.cDForeground, bar = false)
            PreferKey.cNDForeground -> selectForegroundAction(PreferKey.cNDForeground, bar = false)
            "themeList" -> ThemeListDialog().show(childFragmentManager, "themeList")
            PreferKey.bottomBarSkin -> startActivity<BottomBarSkinActivity>()
            "saveDayTheme",
            "saveNightTheme" -> alertSaveTheme(key)

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

            "welcomeStyle" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 前景色的四选一: 自动 / 深色 / 浅色 / 自定义。
     *
     * 栏位(顶栏底栏)与弹框共用本方法, 由 [bar] 决定文案与「自动」哨兵值:
     * - 栏位: 文案 `bar_foreground_*`, 哨兵 [PreferKey.barForegroundAuto];
     * - 弹框: 文案 `dialog_foreground_*`, 哨兵 [PreferKey.dialogForegroundAuto]。
     *
     * 「自动」写回哨兵值, 让对应的取色入口
     * ([io.legado.app.lib.theme.barForegroundColor] / [io.legado.app.lib.theme.dialogForegroundColor])
     * 走"按自身底色反推"的默认分支; 另外三项写具体颜色。
     * 选「自定义」时再弹系统取色器, 结果在 [onColorSelected] 落地。
     */
    private fun selectForegroundAction(preferKey: String, bar: Boolean) {
        val isNight = preferKey == PreferKey.cNBForeground || preferKey == PreferKey.cNDForeground
        val autoValue = if (bar) PreferKey.barForegroundAuto else PreferKey.dialogForegroundAuto
        val actions = arrayListOf(
            getString(if (bar) R.string.bar_foreground_auto else R.string.dialog_foreground_auto),
            getString(if (bar) R.string.bar_foreground_dark else R.string.dialog_foreground_dark),
            getString(if (bar) R.string.bar_foreground_light else R.string.dialog_foreground_light),
            getString(if (bar) R.string.bar_foreground_custom else R.string.dialog_foreground_custom)
        )
        context?.selector(items = actions) { _, i ->
            when (i) {
                0 -> {
                    putPrefInt(preferKey, autoValue)
                    upForegroundSummary(preferKey)
                    upTheme(isNight)
                }

                1 -> {
                    putPrefInt(preferKey, Color.BLACK)
                    upForegroundSummary(preferKey)
                    upTheme(isNight)
                }

                2 -> {
                    putPrefInt(preferKey, Color.WHITE)
                    upForegroundSummary(preferKey)
                    upTheme(isNight)
                }

                3 -> {
                    pendingForegroundKey = preferKey
                    val current = getPrefInt(preferKey, Color.BLACK)
                        .takeIf { it != autoValue } ?: Color.BLACK
                    ColorPickerDialog.newBuilder()
                        .setColor(current)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                        .create()
                        .also { dialog ->
                            dialog.setColorPickerDialogListener(this)
                            // Fragment 里不能用 Builder.show(FragmentActivity), 走项目统一入口。
                            // tag 必须与 onViewCreated 里找回实例用的 FOREGROUND_PICKER_TAG 一致。
                            dialog.show(childFragmentManager, FOREGROUND_PICKER_TAG)
                        }
                }
            }
        }
    }

    override fun onColorSelected(dialogId: Int, @ColorInt color: Int) {
        val key = pendingForegroundKey ?: return
        pendingForegroundKey = null
        putPrefInt(key, color)
        upForegroundSummary(key)
        upTheme(key == PreferKey.cNBForeground || key == PreferKey.cNDForeground)
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingForegroundKey = null
    }

    /** 刷新设置项副标题: 自动 / 深色 / 浅色 / 具体色值。栏位与弹框共用。 */
    private fun upForegroundSummary(preferKey: String) {
        val preference = findPreference<Preference>(preferKey) ?: return
        val isBar = preferKey == PreferKey.cBForeground || preferKey == PreferKey.cNBForeground
        val autoValue = if (isBar) PreferKey.barForegroundAuto else PreferKey.dialogForegroundAuto
        preference.summary = when (val color = getPrefInt(preferKey, autoValue)) {
            autoValue -> getString(
                if (isBar) R.string.bar_foreground_auto else R.string.dialog_foreground_auto
            )

            Color.BLACK -> getString(
                if (isBar) R.string.bar_foreground_dark else R.string.dialog_foreground_dark
            )

            Color.WHITE -> getString(
                if (isBar) R.string.bar_foreground_light else R.string.dialog_foreground_light
            )

            else -> String.format("#%06X", 0xFFFFFF and color)
        }
    }

    @SuppressLint("InflateParams")
    private fun alertSaveTheme(key: String) {
        alert(R.string.theme_name) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "name"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { themeName ->
                    when (key) {
                        "saveDayTheme" -> {
                            ThemeConfig.saveDayTheme(requireContext(), themeName)
                        }

                        "saveNightTheme" -> {
                            ThemeConfig.saveNightTheme(requireContext(), themeName)
                        }
                    }
                }
            }
            cancelButton()
        }
    }

    private fun selectBgAction(isNight: Boolean) {
        val bgKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurringKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        val actions = arrayListOf(
            getString(R.string.background_image_blurring),
            getString(R.string.select_image)
        )
        if (!getPrefString(bgKey).isNullOrEmpty()) {
            actions.add(getString(R.string.delete))
        }
        context?.selector(items = actions) { _, i ->
            when (i) {
                0 -> alertImageBlurring(blurringKey) {
                    upTheme(isNight)
                }

                1 -> {
                    if (isNight) {
                        selectImage.launch {
                            requestCode = requestCodeBgDark
                            mode = HandleFileContract.IMAGE
                        }
                    } else {
                        selectImage.launch {
                            requestCode = requestCodeBgLight
                            mode = HandleFileContract.IMAGE
                        }
                    }
                }

                2 -> {
                    removePref(bgKey)
                    upTheme(isNight)
                }
            }
        }
    }

    private fun alertImageBlurring(preferKey: String, success: () -> Unit) {
        alert(R.string.background_image_blurring) {
            val alertBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                getPrefInt(preferKey, 0).let {
                    seekBar.progress = it
                    textViewValue.text = it.toString()
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.seekBar.progress.let {
                    putPrefInt(preferKey, it)
                    success.invoke()
                }
            }
            cancelButton()
        }
    }

    private fun upTheme(isNightTheme: Boolean) {
        if (AppConfig.isNightTheme == isNightTheme) {
            listView.post {
                ThemeConfig.applyTheme(requireContext())
                recreateActivities()
            }
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.barElevation -> preference.summary =
                getString(R.string.bar_elevation_s, value)

            PreferKey.fontScale -> {
                val fontScale = AppContextWrapper.getFontScale(requireContext())
                preference.summary = getString(R.string.font_scale_summary, fontScale)
            }

            PreferKey.bgImage,
            PreferKey.bgImageN -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun setBgFromUri(uri: Uri, preferenceKey: String, success: () -> Unit) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                success()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    companion object {
        /** 栏位/弹框前景色共用同一个取色器, 用一个固定 tag 便于进程重建后找回实例。 */
        private const val FOREGROUND_PICKER_TAG = "foreground-color-picker"
    }

}
