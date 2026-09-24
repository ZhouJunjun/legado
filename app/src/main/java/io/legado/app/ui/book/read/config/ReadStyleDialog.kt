package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import androidx.core.view.get
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadBookStyleBinding
import io.legado.app.databinding.ItemReadStyleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.borderedDialogBackground
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getIndexById
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onLongClick

class ReadStyleDialog : BaseDialogFragment(R.layout.dialog_read_book_style),
    FontSelectDialog.CallBack {

    private val binding by viewBinding(DialogReadBookStyleBinding::bind)
    private val callBack get() = activity as? ReadBookActivity
    private lateinit var styleAdapter: StyleAdapter
    private var updatingPageAnim = false

    override fun onStart() {
        super.onStart()
        val activity = activity as? ReadBookActivity ?: return
        // 与朗读面板同一套叠层规则: 叠在主菜单之上时给底栏让位。
        // 高度同样**不封顶**: 界面面板很长, 完整展开而不是滚动。
        val stack = activity.dialogStackMode
        dialog?.window?.run {
            // 🔴 与朗读面板同一坑: Dialog 窗口默认触摸模态, 会吞掉全屏所有指针事件。
            // 面板被 attr.y 上抬后底栏整块落在窗口之外 → 底栏四个按钮全部点不动
            // (用户 2026-09-23 反馈「点主菜单的目录/界面没有任何反映」)。
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            // yAdj 取正值把窗口向上抬, 抬出底栏高度 → 面板底边落在底栏上沿。
            attr.y = if (stack) activity.panelOffset() else 0
            attributes = attr
        }
        // 第二道保险(同朗读面板): 屏蔽 OEM 主题可能带上的「点外即取消」,
        // 避免面板窗口之外的点击触发 cancel → onMenuPanelCancelled → 整个菜单被收起。
        dialog?.setCanceledOnTouchOutside(false)
    }

    /**
     * 点面板外 / 系统返回触发的取消: 连主菜单一起收起(用户要求「收起上述所有」)。
     *
     * 不能用 onDismiss 代替: 面板内部的按钮(如【边距】)也是 dismiss —— 那些场景
     * 主菜单应当保留, 只有用户主动取消才整套收起。
     */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        (activity as? ReadBookActivity)?.onMenuPanelCancelled()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initViewEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
        // 面板自我关闭(点【边距】等会 dismiss 再去开别的 dialog)时复位面板状态,
        // 避免主菜单仍高亮【界面】且系统返回被多吞一次。
        (activity as? ReadBookActivity)?.onPanelDialogDismissed(ReadMenu.PANEL_STYLE)
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        // 顶部 1dp 实心灰线(无圆角), 与正文区分开
        rootView.background = requireContext().borderedDialogBackground
        tvPageAnim.setTextColor(textColor)
        tvBgTs.setTextColor(textColor)
        tvShareLayout.setTextColor(textColor)
        dsbTextSize.valueFormat = {
            (it + 5).toString()
        }
        dsbTextLetterSpacing.valueFormat = {
            ((it - 50) / 100f).toString()
        }
        dsbLineSize.valueFormat = { lineSpacingDisplayValue(it) }
        dsbParagraphSpacing.valueFormat = { (it / 10f).toString() }
        styleAdapter = StyleAdapter()
        // 样式色块自动换行(用户 2026-09-23 要求, 替代原来的左右滑动)。
        // ROW + WRAP 缺一不可: FlexboxLayoutManager 默认 flexWrap = NOWRAP,
        // 不显式设 WRAP 就还是单行横滑。
        rvStyle.layoutManager = FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
        rvStyle.adapter = styleAdapter
        styleAdapter.addFooterView {
            ItemReadStyleBinding.inflate(layoutInflater, it, false).apply {
                ivStyle.setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
                ivStyle.setText(null)
                ivStyle.setColorFilter(textColor)
                ivStyle.borderColor = textColor
                ivStyle.setImageResource(R.drawable.ic_add)
                root.setOnClickListener {
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
        }
    }

    private fun initData() {
        binding.cbShareLayout.isChecked = ReadBookConfig.shareLayout
        upView()
        styleAdapter.setItems(ReadBookConfig.configList)
    }

    private fun initViewEvent() = binding.run {
        chineseConverter.onChanged {
            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        textFontWeightConverter.onChanged {
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
        }
        tvTextFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        tvTextIndent.setOnClickListener {
            context?.selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
            }
        }
        tvPadding.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showPaddingConfig()
        }
        tvTip.setOnClickListener {
            TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
        }
        rgPageAnim.setOnCheckedChangeListener { _, checkedId ->
            if (updatingPageAnim) return@setOnCheckedChangeListener
            ReadBook.book?.setPageAnim(-1)
            ReadBookConfig.pageAnim = binding.rgPageAnim.getIndexById(checkedId)
            callBack?.upPageAnim()
            ReadBook.loadContent(false)
        }
        cbShareLayout.onCheckedChangeListener = { _, isChecked ->
            val oldPageAnim = ReadBook.pageAnim()
            ReadBookConfig.shareLayout = isChecked
            if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        }
        dsbTextSize.onChanged = {
            ReadBookConfig.textSize = it + 5
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextLetterSpacing.onChanged = {
            ReadBookConfig.letterSpacing = (it - 50) / 100f
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbLineSize.onChanged = {
            ReadBookConfig.lineSpacingExtra = lineSpacingFromProgress(it)
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbParagraphSpacing.onChanged = {
            ReadBookConfig.paragraphSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            val oldPageAnim = ReadBook.pageAnim()
            ReadBookConfig.styleSelect = index
            if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
            upView()
            styleAdapter.notifyItemChanged(oldIndex)
            styleAdapter.notifyItemChanged(index)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        dismissAllowingStateLoss()
        changeBgTextConfig(index)
        callBack?.showBgTextConfig()
        return true
    }

    private fun upView() = binding.run {
        textFontWeightConverter.upUi(ReadBookConfig.textBold)
        // Reflect the selected preset without replaying radio-button change callbacks.
        // Those callbacks can apply new padding while the old scroll page is still bound.
        updatingPageAnim = true
        try {
            ReadBook.pageAnim().let {
                if (it >= 0 && it < rgPageAnim.childCount) {
                    rgPageAnim.check(rgPageAnim[it].id)
                }
            }
        } finally {
            updatingPageAnim = false
        }
        ReadBookConfig.let {
            dsbTextSize.progress = it.textSize - 5
            dsbTextLetterSpacing.progress = (it.letterSpacing * 100).toInt() + 50
            dsbLineSize.progress = lineSpacingToProgress(it.lineSpacingExtra)
            dsbParagraphSpacing.progress = it.paragraphSpacing
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    inner class StyleAdapter :
        RecyclerAdapter<ReadBookConfig.Config, ItemReadStyleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemReadStyleBinding {
            return ItemReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadStyleBinding,
            item: ReadBookConfig.Config,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                ivStyle.setText(item.name.ifBlank { "文字" })
                ivStyle.setTextColor(item.curTextColor())
                ivStyle.setImageDrawable(item.curBgDrawable(100, 150))
                if (ReadBookConfig.styleSelect == holder.layoutPosition) {
                    ivStyle.borderColor = accentColor
                    ivStyle.setTextBold(true)
                } else {
                    ivStyle.borderColor = item.curTextColor()
                    ivStyle.setTextBold(false)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStyleBinding) {
            binding.apply {
                ivStyle.setOnClickListener {
                    if (ivStyle.isInView) {
                        changeBgTextConfig(holder.layoutPosition)
                    }
                }
                ivStyle.onLongClick(ivStyle.isInView) {
                    if (ivStyle.isInView) {
                        showBgTextConfig(holder.layoutPosition)
                    }
                }
            }
        }

    }
}

private const val LINE_SPACING_CONFIG_MIN = -10
private const val LINE_SPACING_CONFIG_MAX = 40
private const val LINE_SPACING_PROGRESS_OFFSET = 10

internal fun lineSpacingToProgress(value: Int): Int {
    return (value + LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(0, LINE_SPACING_CONFIG_MAX - LINE_SPACING_CONFIG_MIN)
}

internal fun lineSpacingFromProgress(progress: Int): Int {
    return (progress - LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(LINE_SPACING_CONFIG_MIN, LINE_SPACING_CONFIG_MAX)
}

internal fun lineSpacingDisplayValue(progress: Int): String {
    return ((lineSpacingFromProgress(progress) - 10) / 10f).toString()
}
