package io.legado.app.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.compress.LibArchiveUtils
import splitties.init.appCtx
import java.io.File

/* 自动判断压缩文件后缀 然后再调用具体的实现 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    // 临时目录 下次启动自动删除
    val TEMP_PATH: String by lazy {
        appCtx.externalCache.getFile(TEMP_FOLDER_NAME).createFolderReplace().absolutePath
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromUri(archiveUri, false), path, filter)
    }

    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(Uri.parse(archivePath), path, filter)
    }

    fun deCompress(
        archiveFile: File,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromFile(archiveFile), path, filter)
    }

    fun deCompress(
        archiveDoc: DocumentFile,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromDocumentFile(archiveDoc), path, filter)
    }

    fun deCompress(
        archiveFileDoc: FileDoc,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        if (archiveFileDoc.isDir) throw IllegalArgumentException("Unexpected Folder input")
        val name = archiveFileDoc.name
        checkAchieve(name)
        val workPathFileDoc = getCacheFolderFileDoc(name, path)
        val workPath = workPathFileDoc.toString()

        return archiveFileDoc.openReadPfd().getOrThrow().use {
            LibArchiveUtils.unArchive(it, File(workPath), filter)
        }

    }

    /**
     * 解压到指定文件夹(用户可见目录): 压缩包内容放进以压缩包名命名的子目录。
     *
     * 与 [deCompress] 的区别:
     * - 目标不是缓存临时目录, 子目录名用压缩包文件名(去掉后缀)而不是 md5, 用户能一眼认出;
     * - 子目录已存在时直接复用(同名文件覆盖), 不会每解压一次就多出一个新目录。
     *
     * 底层解压只支持真实文件路径, 因此目标是 SAF 目录(content://)时,
     * 先在临时目录解压再逐个拷进目标目录。
     *
     * @return 目标子目录里解压出来的文件
     */
    fun deCompressIntoFolder(
        archiveFileDoc: FileDoc,
        folderDoc: FileDoc,
        filter: ((String) -> Boolean)? = null
    ): List<FileDoc> {
        if (!folderDoc.isDir) throw IllegalArgumentException("Unexpected non-folder input")
        checkAchieve(archiveFileDoc.name)
        val subFolderName = archiveFileDoc.name.substringBeforeLast('.')
            .ifBlank { archiveFileDoc.name }

        // 真实目录: 直接解压进去, 零拷贝
        folderDoc.asFile()?.let { folder ->
            val destDir = File(folder, subFolderName)
            if (!destDir.exists() && !destDir.mkdirs() && !destDir.isDirectory) {
                throw NoStackTraceException("无法创建解压目录: $destDir")
            }
            return archiveFileDoc.openReadPfd().getOrThrow().use {
                LibArchiveUtils.unArchive(it, destDir, filter)
            }.map { FileDoc.fromFile(it) }
        }

        // SAF 目录: 先在临时目录解压, 再拷进目标目录
        val tempFiles = deCompress(archiveFileDoc, filter = filter)
        val destDoc = DocumentUtils.createFolderIfNotExist(
            folderDoc.asDocumentFile() ?: throw NoStackTraceException("无法写入目标文件夹"),
            subFolderName
        ) ?: throw NoStackTraceException("无法创建解压目录: $subFolderName")
        return tempFiles.map { tempFile ->
            val name = tempFile.name
            val target = destDoc.findFile(name)
                ?: destDoc.createFile(FileUtils.getMimeType(name), name)
                ?: throw NoStackTraceException("无法创建文件: $name")
            val output = target.openOutputStream()
                ?: throw NoStackTraceException("无法写入文件: $name")
            output.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            FileDoc.fromDocumentFile(target)
        }
    }

    /* 遍历目录获取文件名 */
    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        getArchiveFilesName(FileDoc.fromUri(fileUri, false), filter)


    fun getArchiveFilesName(
        fileDoc: FileDoc,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        val name = fileDoc.name
        checkAchieve(name)

        return fileDoc.openReadPfd().getOrThrow().use {
            try {
                LibArchiveUtils.getFilesName(it, filter)
            } catch (e: Exception) {
                emptyList()
            }

        }


    }

    fun isArchive(name: String): Boolean {
        return archiveFileRegex.matches(name)
    }

    private fun checkAchieve(name: String) {
        if (!isArchive(name))
            throw IllegalArgumentException("Unexpected file suffix: Only 7z rar zip Accepted")
    }

    private fun getCacheFolderFileDoc(
        archiveName: String,
        workPath: String
    ): FileDoc {
        return FileDoc.fromUri(Uri.parse(workPath), true)
            .createFolderIfNotExist(MD5Utils.md5Encode16(archiveName))
    }
}