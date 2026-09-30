package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogAutoBackupBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 备份与恢复。
 *
 * 纯本地：备份写入本地目录（或用户选择的文件夹），恢复从本地 zip 文件读取。
 * 已移除 WebDAV 云端备份/恢复与局域网备份传输。
 */
class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_backup)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        updateAutoBackupSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(bottomBackground)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_import_old -> {
                restoreOld.launch()
                return true
            }

        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.autoBackup, PreferKey.autoBackupIntervalDays -> updateAutoBackupSummary()
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.backupPath -> preference.summary =
                value?.takeIf { it.isNotBlank() } ?: defaultBackupPathSummary()

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> showBackupPathSelector()
            PreferKey.backupContent -> backupContent()
            PreferKey.restoreIgnore -> backupIgnore()
            "backup" -> backup()
            PreferKey.autoBackup -> configureAutoBackup()
            "restore" -> restoreFromLocal()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun defaultBackupPathSummary() =
        "${getString(R.string.default_path)}\n${requireContext().externalFiles.absolutePath}"

    private fun updateAutoBackupSummary() {
        findPreference<Preference>(PreferKey.autoBackup)?.summary = if (AppConfig.autoBackup) {
            getString(R.string.auto_backup_destination_summary,
                getString(R.string.backup_local_only),
                AppConfig.autoBackupIntervalDays)
        } else getString(R.string.auto_backup_disabled)
    }

    private fun configureAutoBackup() {
        val binding = DialogAutoBackupBinding.inflate(layoutInflater)
        binding.enabled.isChecked = AppConfig.autoBackup
        binding.intervalDays.setText(AppConfig.autoBackupIntervalDays.toString())
        binding.intervalDays.doAfterTextChanged { binding.intervalDays.error = null }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.auto_backup_t)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val days = binding.intervalDays.text.toString().toIntOrNull()
                        if (days == null || days < 1) {
                            binding.intervalDays.error = getString(R.string.auto_backup_interval_invalid)
                            return@setOnClickListener
                        }
                        requireContext().defaultSharedPreferences.edit()
                            .putBoolean(PreferKey.autoBackup, binding.enabled.isChecked)
                            .putInt(PreferKey.autoBackupIntervalDays, days).apply()
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showBackupPathSelector() {
        requireContext().selector(
            titleSource = R.string.backup_path,
            items = listOf(
                getString(R.string.default_path),
                getString(R.string.select_folder),
            ),
        ) { _, index ->
            when (index) {
                0 -> AppConfig.backupPath = null
                1 -> selectBackupPath.launch()
            }
        }
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    private fun backupContent() {
        val checkedItems = BooleanArray(BackupConfig.contentKeys.size) {
            BackupConfig.contentIsEnabled(BackupConfig.contentKeys[it])
        }
        alert(R.string.backup_content) {
            multiChoiceItems(BackupConfig.contentTitles, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.contentKeys[which]] = !isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    fun backup() {
        startBackup()
    }

    private fun startBackup() {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backup(null)
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath)
            }
        }
    }

    private fun backup(backupPath: String?) {
        waitDialog.setText("备份中…")
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path)
            }
            .request()
    }

    fun restore() {
        restoreFromLocal()
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backupJob?.cancel()
        waitDialog.dismiss()
    }

}
