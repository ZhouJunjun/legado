package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

internal fun selectedBackupFileNames(isEnabled: (String) -> Boolean): List<String> =
    buildList {
        if (isEnabled(BackupConfig.bookshelfContentKey)) {
            addAll(listOf("bookshelf.json", "bookGroup.json", "bookMemo.json"))
        }
        if (isEnabled(BackupConfig.annotationContentKey)) {
            addAll(listOf("bookmark.json", "highlight.json", "highlightRule.json"))
        }
        if (isEnabled(BackupConfig.ruleContentKey)) {
            addAll(
                listOf(
                    "replaceRule.json",
                    "txtTocRule.json",
                    "keyboardAssists.json",
                )
            )
        }
        if (isEnabled(BackupConfig.historyContentKey)) {
            addAll(listOf("readRecord.json", "searchHistory.json"))
        }
        if (isEnabled(BackupConfig.settingContentKey)) {
            addAll(
                listOf(
                    ReadBookConfig.configFileName,
                    ReadBookConfig.shareConfigFileName,
                    ThemeConfig.configFileName,
                    "config.xml",
                )
            )
        }
    }

/**
 * 备份
 */
object Backup {

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        return "backup${backupDate}.zip".normalizeFileName()
    }

    internal fun shouldBackup(now: Long = System.currentTimeMillis()): Boolean {
        return now - LocalConfig.lastBackup >= TimeUnit.DAYS.toMillis(AppConfig.autoBackupIntervalDays.toLong())
    }

    fun autoBack(context: Context) {
        if (!AppConfig.autoBackup || !shouldBackup()) return
        Coroutine.async {
            autoBackupLocked(context)
        }.onError {
        }
    }

    internal suspend fun autoBackupLocked(context: Context) {
        backupRestoreMutex.withLock {
            if (AppConfig.autoBackup && shouldBackup()) {
                withContext(IO) {
                    check(backup(context, AppConfig.backupPath)) {
                        "生成备份失败"
                    }
                }
            }
        }
    }

    suspend fun backupLocked(context: Context, path: String?) {
        backupRestoreMutex.withLock {
            withContext(IO) {
                check(backup(context, path)) { "生成备份失败" }
            }
        }
    }

    private suspend fun backup(
        context: Context,
        path: String?,
        contentKeys: Set<String>? = null,
    ): Boolean {
        val workingZipFile = File(zipFilePath)
        val enabledContentKeys = contentKeys?.toMutableSet()
            ?: BackupConfig.contentKeys.filterTo(hashSetOf()) {
                BackupConfig.contentIsEnabled(it)
            }
        FileUtils.delete(backupPath)
        val backupPersistedCovers = BackupConfig.persistedCoverContentKey in enabledContentKeys
        val backupOtherCovers = BackupConfig.otherCoverContentKey in enabledContentKeys
        val backupBackgrounds = BackupConfig.backgroundContentKey in enabledContentKeys
        val readConfigSnapshot = ReadBookConfig.configList.map { it.copy() }
        val shareReadConfigSnapshot = ReadBookConfig.shareConfig.copy()
        val backgroundPaths = if (backupBackgrounds) {
            arrayListOf<String>().apply {
                (readConfigSnapshot + shareReadConfigSnapshot).forEach { config ->
                    if (config.bgType == 2) add(config.bgStr)
                    if (config.bgTypeNight == 2) add(config.bgStrNight)
                    if (config.bgTypeEInk == 2) add(config.bgStrEInk)
                }
            }
        } else {
            emptyList()
        }
        val (books, memos) = appDb.runInTransaction(Callable {
            appDb.bookDao.all to appDb.bookMemoDao.all()
        })
        writeListToJson(
            books.map { book ->
                book.copy(
                    persistedCoverUrl = book.persistedCoverUrl
                        .takeIf { backupPersistedCovers },
                )
            },
            "bookshelf.json",
            backupPath,
        )
        writeListToJson(memos, "bookMemo.json", backupPath, writeEmpty = true)
        writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        writeListToJson(appDb.bookHighlightDao.all, "highlight.json", backupPath)
        writeListToJson(
            appDb.highlightRuleDao.all,
            "highlightRule.json",
            backupPath,
            writeEmpty = true
        )
        writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        writeListToJson(
            ReplacePreviewConfig.withSamples(appDb.replaceRuleDao.all),
            "replaceRule.json",
            backupPath
        )
        val includeReadRecordCovers = BackupConfig.readRecordCoverContentKey in enabledContentKeys &&
            BackupConfig.historyContentKey in enabledContentKeys
        writeListToJson(
            prepareReadRecordBackup(appDb.readRecordDao.all, appCtx.externalFiles, File(backupPath), includeReadRecordCovers),
            "readRecord.json", backupPath,
        )
        writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        currentCoroutineContext().ensureActive()
        GSON.toJson(readConfigSnapshot).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                .writeText(it)
        }
        GSON.toJson(shareReadConfigSnapshot).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                .writeText(it)
        }
        GSON.toJson(ThemeConfig.configList).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                .writeText(it)
        }
        currentCoroutineContext().ensureActive()
        val preferenceSnapshot = HashMap<String, Any?>(appCtx.defaultSharedPreferences.all)
        (preferenceSnapshot[PreferKey.coverFont] as? String)?.takeIf { it.isNotBlank() }?.let {
            if (!File(it).exists()) preferenceSnapshot[PreferKey.coverFont] = ""
        }
        writePreferenceSnapshot(appCtx, backupPath, "config") {
            putInt("readRecordSort", LocalConfig.getInt("readRecordSort", 0))
            preferenceSnapshot.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putStringSet(key, value as Set<String>)
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = ArrayList(selectedBackupFileNames(enabledContentKeys::contains))
        if (BackupConfig.settingContentKey in enabledContentKeys &&
            BackupConfig.keyIsNotIgnore(PreferKey.coverFont)
        ) {
            (preferenceSnapshot[PreferKey.coverFont] as? String)?.takeIf { it.isNotBlank() }?.let { fontPath ->
                val fontBackup = File(backupPath, BookCover.fontBackupFileName)
                val fontFile = File(fontPath)
                check(fontFile.isFile) { "Invalid cover font: $fontPath" }
                fontFile.copyTo(fontBackup, overwrite = true)
                paths.add(BookCover.fontBackupFileName)
            }
        }
        for (i in 0 until paths.size) {
            paths[i] = backupPath + File.separator + paths[i]
        }
        paths.addAll(
            prepareBackupMediaDirectories(
                appCtx.externalFiles,
                File(backupPath),
                backgroundPaths,
                backupPersistedCovers,
                backupOtherCovers,
                backupBackgrounds,
            ).map { it.absolutePath }
        )
        FileUtils.delete(workingZipFile.absolutePath)
        if (includeReadRecordCovers) {
            File(backupPath, readRecordCoverDirectory).takeIf { it.isDirectory }?.let {
                paths.add(it.absolutePath)
            }
        }
        FileUtils.delete(workingZipFile.absolutePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        try {
            if (!ZipUtils.zipFiles(paths, workingZipFile.absolutePath)) return false
            when {
                path.isNullOrBlank() -> {
                    copyBackup(workingZipFile, context.externalFiles, backupFileName)
                }

                path.isContentScheme() -> {
                    copyBackup(workingZipFile, context, path.toUri(), backupFileName)
                }

                else -> {
                    copyBackup(workingZipFile, File(path), backupFileName)
                }
            }
            currentCoroutineContext().ensureActive()
            LocalConfig.lastBackup = System.currentTimeMillis()
            return true
        } finally {
            FileUtils.delete(backupPath)
            FileUtils.delete(workingZipFile.absolutePath)
        }
    }

    private suspend fun writeListToJson(
        list: List<Any>,
        fileName: String,
        path: String,
        writeEmpty: Boolean = false
    ) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty() || writeEmpty) {
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
            } else {
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(source: File, context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(source).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(source: File, rootFile: File, fileName: String) {
        FileInputStream(source).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    fun clearCache() {
        if (!backupRestoreMutex.tryLock()) return
        try {
            FileUtils.delete(backupPath)
            FileUtils.delete(zipFilePath)
        } finally {
            backupRestoreMutex.unlock()
        }
    }
}
