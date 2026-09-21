package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.Status
import io.legado.app.databinding.ViewAloudMiniBarBinding
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.startActivity
import io.legado.app.ui.book.read.ReadBookActivity
import kotlin.math.roundToInt

/**
 * 书架页朗读迷你条。
 *
 * 朗读在后台继续时, 书架底部显示迷你条:
 * - 显示书名 + 当前朗读章节 + 本章朗读进度
 * - 播放/暂停、停止
 * - 点击迷你条 = 「看原文」, 回到阅读页并定位到朗读位置(不改阅读进度)
 *
 * 注意: 文案一律取自朗读游标快照(BaseReadAloudService.readAloud*),
 * 绝不能用 ReadBook.curTextChapter / durChapter* ——那两条是阅读进度,
 * 用户手动翻页后会让迷你条显示错误的章节。
 */
class AloudMiniBar(
    private val context: Context,
    private val binding: ViewAloudMiniBarBinding,
) {

    /** 是否处于「显示」语义(朗读服务在运行) */
    val isShowing: Boolean
        get() = binding.root.visibility == View.VISIBLE

    fun init() {
        binding.root.setOnClickListener { openReaderAtAloudPosition() }
        binding.ivAloudMiniPlay.setOnClickListener {
            if (BaseReadAloudService.pause) {
                ReadAloud.resume(context)
            } else {
                ReadAloud.pause(context)
            }
            upState()
        }
        binding.ivAloudMiniClose.setOnClickListener {
            ReadAloud.stop(context)
            upState()
        }
    }

    /**
     * 刷新迷你条可见性与文案。朗读服务未运行或未点击状态变化时隐藏。
     */
    @MainThread
    fun upState() {
        if (!BaseReadAloudService.isRun) {
            hideBar()
            return
        }
        // 书名优先取朗读服务自持快照: 阅读页退出后 ReadBook.book 可能已换书/被重置。
        val book = aloudBook() ?: ReadBook.book ?: return hideBar()
        binding.root.visibility = View.VISIBLE
        binding.tvAloudMiniTitle.text = book.name
        binding.tvAloudMiniSubtitle.text = aloudChapterTitle()
        val percent = aloudPercent()
        binding.tvAloudMiniPercent.isVisible = percent != null
        binding.tvAloudMiniPercent.text = percent?.let { "$it%" }.orEmpty()
        binding.ivAloudMiniPlay.setImageResource(
            if (BaseReadAloudService.pause) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp
        )
        binding.ivAloudMiniPlay.contentDescription =
            context.getString(if (BaseReadAloudService.pause) R.string.resume else R.string.pause)
    }

    /**
     * 朗读服务自持的书籍快照(与 ReadBook.book 可能不同: 用户可能已切书)。
     */
    private fun aloudBook() = BaseReadAloudService.aloudBookSnapshot ?: ReadBook.book

    /**
     * 副标题 = 朗读章节名, 取自朗读游标快照。
     * 绝不使用 ReadBook.curTextChapter —— 那是阅读进度, 用户手动翻页后会显示错误章节。
     */
    private fun aloudChapterTitle(): String =
        BaseReadAloudService.readAloudChapterTitle
            ?.takeIf { it.isNotBlank() }
            ?: ReadBook.book?.config?.aloudChapterTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.read_aloud_t)

    /**
     * 本章朗读进度百分比。位置未知或章节长度未知时返回 null(不显示, 避免显示 0% 误导)。
     */
    private fun aloudPercent(): Int? {
        val pos = BaseReadAloudService.readAloudChapterStart
        val length = BaseReadAloudService.readAloudChapterLength
        if (pos < 0 || length <= 0) return null
        return (pos * 100f / length).roundToInt().coerceIn(0, 100)
    }

    @MainThread
    fun onAloudStateChanged(status: Int) {
        if (status == Status.STOP) hideBar() else upState()
    }

    /** 隐藏迷你条(朗读停止, 或切到了「我的」tab)。 */
    @MainThread
    fun hideBar() {
        binding.root.visibility = View.GONE
    }

    /**
     * 「看原文」: 回到阅读页并定位到朗读位置, 不修改阅读进度。
     */
    private fun openReaderAtAloudPosition() {
        val book = aloudBook() ?: return
        context.startActivity<ReadBookActivity> {
            putExtra("bookUrl", book.bookUrl)
            putExtra("openAloudPos", true)
            putExtra("aloudChapterIndex", ReadAloud.readAloudChapterIndex)
            putExtra("aloudChapterPos", ReadAloud.readAloudChapterStart)
        }
    }
}
