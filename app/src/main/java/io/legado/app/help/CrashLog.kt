package io.legado.app.help

import android.net.Uri
import android.os.Process
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.isFileScheme
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常日志。
 *
 * 落盘位置：`<书籍保存目录>/logs/{yyyyMMdd}.log`；
 * 未设置书籍目录、或该书目录不是本地文件（SAF 的 content://）时，
 * 退回应用私有目录 `files/logs/`。
 *
 * 全程 runCatching：写日志失败绝不影响原有的崩溃流程
 * （仍然把异常交给上一个 handler，没有则结束进程）。
 */
object CrashLog {

    private const val LOG_DIR = "logs"

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-M-d H:m:s", Locale.getDefault())
    private val lock = Any()

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun write(thread: Thread, throwable: Throwable) {
        val now = Date()
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        val header = runCatching { timeFormat.format(now) }.getOrElse { now.toString() }
        val line = "$header [${thread.name}] " +
            "${throwable.javaClass.name}: ${throwable.message}\n$stackTrace\n\n"
        val file = logFile(now)
        synchronized(lock) {
            FileOutputStream(file, /* append = */ true).use { it.write(line.toByteArray()) }
        }
    }

    /**
     * 取当天的日志文件，必要时创建 logs 目录。
     */
    private fun logFile(now: Date): File {
        val dir = File(baseDir(), LOG_DIR)
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        return File(dir, "${dateFormat.format(now)}.log")
    }

    /**
     * 优先用书籍保存目录（用户可见），否则退回应用私有目录。
     */
    private fun baseDir(): File {
        val bookDir = runCatching {
            AppConfig.defaultBookTreeUri
                ?.let { Uri.parse(it) }
                ?.takeIf { it.isFileScheme() }
                ?.path
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
        }.getOrNull()
        return bookDir ?: appCtx.filesDir
    }
}
