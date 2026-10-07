package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.view.forEach
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.dialogForegroundColor
import io.legado.app.utils.alignTitleInk
import io.legado.app.utils.disableAutoFill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext


abstract class BaseDialogFragment(
    @LayoutRes layoutID: Int,
    private val adaptationSoftKeyboard: Boolean = false
) : DialogFragment(layoutID) {

    private var onDismissListener: OnDismissListener? = null
    private var showRequested = false

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.decorView?.disableAutoFill()
        if (adaptationSoftKeyboard) {
            dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            //不加这个android 5.0对话框顶部会有空白
            setStyle(STYLE_NO_TITLE, 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (adaptationSoftKeyboard) {
            view.findViewById<View>(R.id.vw_bg)?.setOnClickListener(null)
            view.setOnClickListener { dismiss() }
        } else {
            // 弹框根背景走「弹框背景色」(独立于栏位底色), 用户 2026-09-30 的规格;
            // 未设置时 ThemeStore 会回落到栏位底色, 升级后观感不变。
            view.setBackgroundColor(ThemeStore.dialogBackground())
        }
        applyDialogForeground(view)
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
    }

    /**
     * 弹框内自带 Toolbar(标题栏)的前景色 —— 标题/副标题/返回箭头/溢出图标。
     *
     * 用户 2026-09-30 要求「弹框文字与图标颜色」对**所有弹框**生效
     * (legado.md L80/L83: 亮色默认 #898989)。放在基类统一处理:
     *   · 逐个弹框去改要动 20+ 个文件, 且新增弹框容易漏;
     *   · 子类通常只设置 toolBar 的**背景色**, 前景色在这里统一兜底不会互相覆盖。
     *
     * 用 [View.post] 延后到子类 `onFragmentCreated` 之后再取色 —— 子类可能在该回调里
     * `setNavigationOnClickListener` / `inflateMenu`, 图标是那时才挂上去的。
     */
    private fun applyDialogForeground(view: View) {
        view.post {
            val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.tool_bar)
                ?: return@post
            val color = view.context.dialogForegroundColor
            toolbar.setTitleTextColor(color)
            toolbar.setSubtitleTextColor(color)
            val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            toolbar.navigationIcon?.colorFilter = colorFilter
            toolbar.overflowIcon?.colorFilter = colorFilter
            toolbar.menu.forEach { item ->
                item.icon?.colorFilter = colorFilter
            }
            // 标题墨迹与同行图标垂直对齐。弹框用的是**原生 Toolbar**(不是 TitleBar),
            // 不接这里的话完全拿不到校正 —— 用户 2026-09-30 反馈「有些页面顶栏文字
            // 和图标没对齐」的一部分就是这些弹框。放在同一个 post 里取, 保证子类
            // 已经设好标题、图标也已 inflate。
            toolbar.alignTitleInk()
        }
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun show(manager: FragmentManager, tag: String?) {
        if (showRequested || isAdded) return
        kotlin.runCatching {
            // Guard queued requests without removing a fragment and clearing its tag.
            showRequested = true
            super.show(manager, tag)
        }.onFailure {
            showRequested = false
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        showRequested = false
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
        // Destroying the dialog view for recreation also calls onDismiss.
        if (arguments?.getBoolean("finishOnDismiss") == true && host?.isChangingConfigurations == false) {
            host.finish()
        }
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }

    fun findParentTextInputLayout(view: View): TextInputLayout? {
        var parent = view.parent
        while (parent != null && parent !is TextInputLayout) {
            parent = parent.parent
        }
        return parent
    }
}
