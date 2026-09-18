package io.legado.app.ui.video

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.VideoPlay
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.toastOnUi

class VideoPlayerViewModel(application: Application) : BaseViewModel(application) {
    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            VideoPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun upSource(success: (() -> Unit)? = null) {
        val source = VideoPlay.source
        if (source is BookSource) {
            VideoPlay.source = appDb.bookSourceDao.getBookSource(source.getKey())?.also {
                success?.invoke()
            }
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = VideoPlay.source ?: return
        val book = VideoPlay.book ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.getTag()}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }
}