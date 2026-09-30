package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceGroup
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

class MyFragment() : BaseFragment(R.layout.fragment_my_config), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        val fragmentTag = "prefFragment"
        var preferenceFragment = childFragmentManager.findFragmentByTag(fragmentTag)
        if (preferenceFragment == null) preferenceFragment = MyPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.pre_fragment, preferenceFragment, fragmentTag).commit()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_help -> showHelp("appHelp")
            R.id.menu_customize_my -> (childFragmentManager.findFragmentByTag("prefFragment")
                as? MyPreferenceFragment)?.showCustomization()
        }
    }

    /**
     * 配置
     */
    class MyPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val isMore: Boolean
            get() = activity?.intent?.getStringExtra("configTag") == ConfigTag.MY_MORE
        private lateinit var customization: MultiSelectListPreference

        fun showCustomization() = onDisplayPreferenceDialog(customization)

        private fun applyVisibility(group: PreferenceGroup = preferenceScreen) {
            val moreItems = customization.values
            repeat(group.preferenceCount) { index ->
                val preference = group.getPreference(index)
                if (preference is PreferenceGroup) {
                    applyVisibility(preference)
                    preference.isVisible = (0 until preference.preferenceCount)
                        .any { preference.getPreference(it).isVisible }
                } else {
                    preference.isVisible = when (preference.key) {
                        PreferKey.myMoreItems -> false
                        "myMore", "exit" -> !isMore
                        else -> (preference.key in moreItems) == isMore
                    }
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_main)
            if (isMore) activity?.setTitle(R.string.reader_menu_more)
            val available = mutableListOf<Preference>()
            fun collect(group: PreferenceGroup) {
                repeat(group.preferenceCount) { index ->
                    val preference = group.getPreference(index)
                    if (preference is PreferenceGroup) collect(preference)
                    else if (preference.key !in setOf("exit", "myMore")) available.add(preference)
                }
            }
            collect(preferenceScreen)
            customization = MultiSelectListPreference(requireContext()).apply {
                key = PreferKey.myMoreItems
                title = getString(R.string.customize_my)
                dialogTitle = getString(R.string.my_more_items)
                entries = available.map { it.title }.toTypedArray()
                entryValues = available.map { it.key }.toTypedArray()
                setDefaultValue(emptySet<String>())
                isVisible = false
            }
            preferenceScreen.addPreference(customization)
            applyVisibility()
            findPreference<NameListPreference>(PreferKey.themeMode)?.let {
                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { ThemeConfig.applyDayNight(requireContext()) }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(bottomBackground)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            customization.values = preferenceManager.sharedPreferences?.getStringSet(
                PreferKey.myMoreItems, emptySet<String>()
            ).orEmpty()
            applyVisibility()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.myMoreItems -> {
                    customization.values = sharedPreferences?.getStringSet(
                        key, emptySet<String>()
                    ).orEmpty()
                    applyVisibility()
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "myMore" -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.MY_MORE) }
                "replaceManage" -> startActivity<ReplaceRuleActivity>()
                "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
                "bookmark" -> startActivity<AllBookmarkActivity>()
                "setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.OTHER_CONFIG)
                }

                "backupRestore" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                }

                "theme_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                }

                "fileManage" -> startActivity<FileManageActivity>()
                "readRecord" -> startActivity<ReadRecordActivity>()
                "about" -> startActivity<AboutActivity>()
                "exit" -> activity?.finish()
            }
            return super.onPreferenceTreeClick(preference)
        }


    }
}
