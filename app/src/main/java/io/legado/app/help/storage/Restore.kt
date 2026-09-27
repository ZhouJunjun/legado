package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.graphics.Typeface
import androidx.documentfile.provider.DocumentFile
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookMemo
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.mergeRestoredReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.HighlightStyle
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.normalizeLegacyPersistedCover
import io.legado.app.help.book.upType
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.font.installFontFile
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore {

    private const val TAG = "Restore"

    suspend fun restore(context: Context, uri: Uri): Unit = backupRestoreMutex.withLock {
        kotlin.runCatching {
            extractBackup(context, uri)
        }.onFailure {
            return
        }
        kotlin.runCatching {
            restoreUnpacked(Backup.backupPath)
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
        }
    }

    suspend fun restoreOrThrow(
        context: Context,
        uri: Uri,
    ) = backupRestoreMutex.withLock {
        val restorePath = Backup.backupPath
        extractBackup(context, uri, restorePath)
        restoreUnpacked(restorePath)
    }

    private fun extractBackup(
        context: Context,
        uri: Uri,
        targetPath: String = Backup.backupPath,
    ) {
        FileUtils.delete(targetPath)
        if (uri.isContentScheme()) {
            DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                ZipUtils.unZipToPath(it, targetPath)
            }
        } else {
            ZipUtils.unZipToPath(File(uri.path!!), targetPath)
        }
    }

    suspend fun restoreLocked(path: String) {
        backupRestoreMutex.withLock {
            restoreUnpacked(path)
        }
    }

    private suspend fun restoreUnpacked(path: String) {
        restore(path)
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

    private suspend fun restore(path: String) {
        val backupRoot = File(path)
        val restoredPreferences = readPreferenceSnapshot(appCtx, path, "config")
        val restoredBookUrls = hashSetOf<String>()
        fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                book.upType()
                book.normalizeLegacyPersistedCover()
                book.customCoverUrl = book.customCoverUrl?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
                book.persistedCoverUrl = book.persistedCoverUrl?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
            }
            it.filter { book -> book.isLocal }
                .forEach { book ->
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
            val newBooks = arrayListOf<Book>()
            val ignoreLocalBook = BackupConfig.ignoreLocalBook
            it.forEach { book ->
                if (ignoreLocalBook && book.isLocal) {
                    return@forEach
                }
                restoredBookUrls.add(book.bookUrl)
                if (appDb.bookDao.has(book.bookUrl)) {
                    try {
                        appDb.bookDao.update(book)
                    } catch (_: SQLiteConstraintException) {
                        appDb.bookDao.insert(book)
                    }
                } else {
                    newBooks.add(book)
                }
            }
            appDb.bookDao.insert(*newBooks.toTypedArray())
        }
        fileToListT<BookMemo>(path, "bookMemo.json")?.let { memos ->
            appDb.bookMemoDao.restore(memos.filter { it.bookUrl in restoredBookUrls })
        }
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            appDb.bookmarkDao.insert(*it.toTypedArray())
        }
        fileToListT<BookHighlight>(path, "highlight.json")?.let { highlights ->
            kotlin.runCatching {
                applyLegacyHighlightStyles(File(path, "highlight.json").readText(), highlights)
                applyLegacyHighlightOwners(highlights)
                appDb.bookHighlightDao.insert(*highlights.toTypedArray())
            }.onFailure {
            }
        }
        fileToListT<HighlightRule>(path, "highlightRule.json")?.let { rules ->
            kotlin.runCatching {
                appDb.highlightRuleDao.replaceAll(rules.map(HighlightRule::normalizeForRestore))
            }.onFailure {
            }
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let { groups ->
            groups.forEach { group ->
                group.cover = group.cover?.let { coverPath ->
                    remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                }
            }
            appDb.bookGroupDao.insert(*groups.toTypedArray())
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            val insertedIds = appDb.replaceRuleDao.insert(*it.toTypedArray())
            ReplacePreviewConfig.saveImportedSamples(it, insertedIds, clearMissing = true)
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            appDb.txtTocRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
            appDb.keyboardAssistsDao.insert(*it.toTypedArray())
        }
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                // Older backups omitted the device id; treat them as local records.
                val normalizedRecord = if (readRecord.deviceId.isBlank()) {
                    readRecord.copy(deviceId = androidId)
                } else {
                    readRecord
                }
                val restoredRecord = normalizedRecord.copy(coverUrl = remapReadRecordCover(
                    normalizedRecord.coverUrl?.let { remapRestoredCoverPath(it, File(path), appCtx.externalFiles) },
                    File(path), appCtx.externalFiles,
                ))
                appDb.runInTransaction {
                    val current = appDb.readRecordDao.getRecord(restoredRecord.deviceId, restoredRecord.bookName, restoredRecord.author)
                    appDb.readRecordDao.insert(mergeRestoredReadRecord(
                        current, restoredRecord, restoredRecord.deviceId == androidId,
                    ))
                }
            }
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                ReadBookConfig.initConfigs()
            }?.onFailure {
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                ReadBookConfig.initShareConfig()
            }?.onFailure {
            }
        }
        //AppWebDav.downBgs()
        restoredPreferences?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        "readRecordSort" -> (value as? Int)?.let {
                            LocalConfig.edit().putInt(key, it.coerceIn(0, 2)).apply()
                        }
                        PreferKey.readRecordCover,
                        PreferKey.readRecordCoverDark -> {
                            val coverPath = (value as? String)?.let {
                                remapRestoredCoverPath(it, File(path), appCtx.externalFiles)
                            }
                            if (coverPath == null) edit.remove(key) else edit.putString(key, coverPath)
                        }
                        PreferKey.coverFont -> {
                            val fontBackup = File(path, BookCover.fontBackupFileName)
                            val fontPath = if (value is String && value.isNotBlank() && fontBackup.isFile) {
                                fontBackup.inputStream().use { input ->
                                    installFontFile(input, BookCover.fontBackupFileName,
                                        File(appCtx.externalFiles, "font")) {
                                        runCatching { Typeface.createFromFile(it) }.isSuccess
                                    }.absolutePath
                                }
                            } else (value as? String).orEmpty().takeIf { File(it).isFile }.orEmpty()
                            edit.putString(key, fontPath)
                        }
                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                edit.putStringSet(key, value as Set<String>)
                            }
                        }
                    }
                }
            }
            if (PreferKey.bookshelfReadProgressMode !in map &&
                PreferKey.showBookshelfReadProgress in map
            ) {
                val legacyEnabled = when (val value = map[PreferKey.showBookshelfReadProgress]) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull()
                    else -> null
                }
                legacyEnabled?.let { enabled ->
                    edit.putInt(
                        PreferKey.bookshelfReadProgressMode,
                        if (enabled) {
                            BookshelfReadProgressMode.STANDARD
                        } else {
                            BookshelfReadProgressMode.HIDDEN
                        },
                    )
                }
            }
            if (!BackupConfig.ignoreReadConfig &&
                PreferKey.showReadTitleChapterNameOnly !in map
            ) {
                edit.putBoolean(PreferKey.showReadTitleChapterNameOnly, false)
            }
            if (BackupConfig.keyIsNotIgnore(PreferKey.coverTitleAdaptive) &&
                PreferKey.coverTitleAdaptive !in map
            ) {
                edit.putBoolean(PreferKey.coverTitleAdaptive, true)
            }
            if (BackupConfig.keyIsNotIgnore(PreferKey.coverCustomFontSize) &&
                PreferKey.coverCustomFontSize !in map
            ) {
                edit.putBoolean(PreferKey.coverCustomFontSize, false)
            }
            if (BackupConfig.keyIsNotIgnore(PreferKey.coverFont) && PreferKey.coverFont !in map) {
                edit.remove(PreferKey.coverFont)
            }
            if (PreferKey.myMoreItems !in map) edit.remove(PreferKey.myMoreItems)
            if (PreferKey.autoBackup !in map) edit.putBoolean(PreferKey.autoBackup, true)
            if (PreferKey.autoBackupIntervalDays !in map) edit.putInt(PreferKey.autoBackupIntervalDays, 1)
            if ("readRecordSimpleLayout" !in map) edit.putBoolean("readRecordSimpleLayout", true)
            if ("readRecordUseDays" !in map) edit.putBoolean("readRecordUseDays", false)
            if ("readRecordShowSeconds" !in map) edit.putBoolean("readRecordShowSeconds", true)
            if ("readRecordFixedCard" !in map) edit.putBoolean("readRecordFixedCard", true)
            for (key in listOf(PreferKey.readRecordCover, PreferKey.readRecordCoverDark)) {
                if (key !in map && BackupConfig.keyIsNotIgnore(key)) edit.remove(key)
            }
            if (!BackupConfig.ignoreReadConfig && PreferKey.mangaRightToLeft !in map) {
                edit.putBoolean(PreferKey.mangaRightToLeft, false)
            }
            edit.apply()
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        val coverRestoreResult =
            restoreBackupMediaDirectory(File(path), appCtx.externalFiles, "covers")
            .onFailure {
            }
        val backgroundRestoreResult = if (!BackupConfig.ignoreReadConfig) {
            restoreBackupMediaDirectory(File(path), appCtx.externalFiles, "bg")
                .onFailure {
                }
        } else {
            null
        }
        coverRestoreResult.getOrThrow()
        restoreBackupMediaDirectory(File(path), appCtx.externalFiles, readRecordCoverDirectory).getOrThrow()
        backgroundRestoreResult?.getOrThrow()
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            WallpaperTheme.syncWithPreferences(appCtx)
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                    }
                }
            } else {
            }
        } catch (e: Exception) {
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private fun applyLegacyHighlightOwners(highlights: List<BookHighlight>) {
        highlights.forEach { highlight ->
            val bookUrl = highlight.bookUrl.ifBlank {
                appDb.bookDao.getBook(highlight.bookName, highlight.bookAuthor)?.bookUrl.orEmpty()
            }
            val chapterUrl = highlight.chapterUrl.ifBlank {
                appDb.bookChapterDao.getChapter(bookUrl, highlight.chapterIndex)
                    ?.takeIf { it.title == highlight.chapterName }
                    ?.url
                    .orEmpty()
            }
            highlight.bindLegacyOwner(bookUrl, chapterUrl)
        }
    }

}

internal fun applyLegacyHighlightStyles(json: String, highlights: List<BookHighlight>) {
    val legacy = GSON.fromJsonObject<List<Map<String, Any?>>>(json).getOrNull()
    highlights.forEachIndexed { index, highlight ->
        if (highlight.style.isNullOrBlank()) {
            val raw = legacy?.getOrNull(index)
            val fill = (raw?.get("bgColor") as? Number)?.toInt() ?: 0
            val textColor = (raw?.get("textColor") as? Number)?.toInt() ?: 0
            if (fill != 0 || textColor != 0) {
                highlight.applyStyle(HighlightStyle(fill = fill, textColor = textColor))
            }
        }
    }
}
