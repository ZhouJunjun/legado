package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.widget.TooltipCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.borderedDialogBackground
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlin.math.roundToInt


class ReadAloudDialog : BaseDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack,
    SleepTimerDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)

    override fun onStart() {
        super.onStart()
        val activity = activity as? ReadBookActivity ?: return
        // 面板叠在主菜单之上时需要给底栏让位, 所以窗口整体上移;
        // 高度**不封顶** —— 朗读面板内容很长, 完整展开(超出屏幕时由窗口自行裁切,
        // 而不是塞进 ScrollView 让用户滚), 用户要的是一次看全。
        val stack = activity.dialogStackMode
        dialog?.window?.run {
            // 🔴 必须加 NOT_TOUCH_MODAL —— Dialog 窗口默认是**触摸模态**的。
            //
            // 不加这个 flag 时, 窗口会吞掉**全屏**所有指针事件(官方文档原文:
            // "Otherwise it will consume all pointer events itself, regardless of
            // whether they are inside of the window")。而本面板被 attr.y 上抬后,
            // 底栏(ll_bottom_bg)整块都落在面板窗口**之外**, 于是底栏上的
            // 【目录】【朗读】【界面】【设置】全部点不动, 表现为「点了没有任何反映」
            // (用户 2026-09-23 反馈)。顺带「点面板外收起」(走 Activity 窗口的
            // vw_menu_bg)也会被一起吞掉。
            // 加上之后: 面板窗口内的事件仍归面板, 窗口之外的点击透传给后面的
            // ReadMenu 窗口 —— 底栏可以正常点, 点正文区域也能照常收起。
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 🔴 关掉窗口自身的浮层投影(2026-09-24 修)。
            //
            // 平台 Dialog 主题带 windowElevation(= @dimen/floating_window_z), 会由
            // WindowManager 在窗口四周画一层柔和阴影。本面板经 attr.y 上抬一个底栏
            // 高度后, 窗口底边正好压在「目录/朗读/界面/设置」那一行之上, 于是这层
            // 阴影直接投到了按钮行上 —— 表现为「紧贴面板底边、向下单调变浅」的一条
            // 灰带(用户实测: y=674→719 共 45 行, RGB 227→246, 水平均匀且纯灰阶),
            // 收起面板则整条消失。
            //
            // 上一轮只把面板下移了一个进度行高, 空档是被盖住了, 但阴影并没有消失,
            // 只是从「投在进度行空档上」变成了「投在按钮行上」。面板本身是紧贴底栏
            // 的实体栏位、不需要悬浮感, 置 0 才是根治。与正文的分界仍由顶部那根
            // 1dp 实线(borderedDialogBackground)保证, 不依赖投影。
            setElevation(0f)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            // Gravity.BOTTOM 下 yAdj 取正值窗口向上抬 —— 抬出底栏高度后,
            // 面板底边正好落在底栏上沿, 形成「面板压在主菜单之上」的观感。
            attr.y = if (stack) activity.panelOffset() else 0
            attributes = attr
        }
        // 第二道保险: 若系统/OEM 主题把 windowCloseOnTouchOutside 设成了 true
        // (三星 One UI 的对话框主题就带这个属性), 面板窗口之外的点击会走
        // Dialog.onTouchEvent → shouldCloseOnTouch → cancel() → onCancel →
        // onMenuPanelCancelled → runMenuOut —— 表现成「点一下就莫名把整个菜单收起来」。
        // 这里显式关掉: 收起只走「点正文空白处」或「系统返回」两条路。
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
        // 面板自我关闭(点【停止朗读】等)时, 让底栏高亮与 hasPanel 一并复位 ——
        // 否则主菜单还开着却高亮着【朗读】, 且系统返回会被多吞一次。
        (activity as? ReadBookActivity)?.onPanelDialogDismissed(ReadMenu.PANEL_ALOUD)
    }

    /**
     * 点面板外 / 系统返回触发的取消: 连主菜单一起收起(用户要求「收起上述所有」)。
     *
     * 不能用 onDismiss 代替 —— 面板内部的按钮(【回到朗读位置】等)也走 dismiss,
     * 那些场景主菜单应当保留, 只有用户主动取消才整套收起。
     */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        (activity as? ReadBookActivity)?.onMenuPanelCancelled()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
            // 顶部 1dp 实心灰线(无圆角): 与正文分隔开(原为纯色底, 弹出后与下方内容糊在一起)。
            rootView.background = requireContext().borderedDialogBackground
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(textColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            cbTtsFollowSys.setTextColor(textColor)
            ivEngine.setColorFilter(textColor)
            tvEngineName.setTextColor(textColor)
            ivEngineArrow.setColorFilter(textColor)
            ivAloudBook.setColorFilter(textColor)
            tvAloudBookName.setTextColor(textColor)
            tvAloudBookChapter.setTextColor(textColor)
            tvAloudBookPercent.setTextColor(textColor)
            tvAloudBackToSpeech.setTextColor(textColor)
            tvAloudReadFromHere.setTextColor(textColor)
            tvAloudBackgroundPlay.setTextColor(textColor)
        }
        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        upPlayState()
        upEngineName()
        upStopText()
        upAloudBookInfo()
        upAloudPositionActions()
        cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        upTtsSpeechRateEnabled(!cbTtsFollowSys.isChecked)
        upSeekTimer()
    }

    /**
     * 顶部「正在朗读」信息行: 书名 + 朗读章节 + 本章进度百分比(纯展示)。
     *
     * 数据一律取自朗读服务自持快照, 与书架迷你条同源 —— 不使用 ReadBook.book /
     * curTextChapter, 否则用户换书后这里会显示成当前打开的书, 而不是正在朗读的书。
     */
    private fun upAloudBookInfo() = binding.run {
        if (!BaseReadAloudService.isRun) {
            llAloudBook.visible(false)
            vAloudBookDivider.visible(false)
            return@run
        }
        val book = BaseReadAloudService.aloudBookSnapshot ?: ReadBook.book
        if (book == null) {
            llAloudBook.visible(false)
            vAloudBookDivider.visible(false)
            return@run
        }
        llAloudBook.visible(true)
        // 分隔线跟随本行显隐, 避免出现「没有内容却有横线」的孤立分隔线。
        vAloudBookDivider.visible(true)
        tvAloudBookName.text = book.name
        tvAloudBookChapter.text = BaseReadAloudService.readAloudChapterTitle
            ?.takeIf { it.isNotBlank() }
            ?: book.config.aloudChapterTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.read_aloud_t)
        val pos = BaseReadAloudService.readAloudChapterStart
        val length = BaseReadAloudService.readAloudChapterLength
        tvAloudBookPercent.visible(pos >= 0 && length > 0)
        if (pos >= 0 && length > 0) {
            val percent = (pos * 100f / length).roundToInt().coerceIn(0, 100)
            tvAloudBookPercent.text = "$percent%"
        }
    }

    /**
     * 第二行操作区: 【回到朗读位置】|【从此处朗读】(从阅读页迁入)。
     *
     * 两个按钮始终占位在这第二行(布局稳定, 不因状态跳动)。
     * 仅「朗读服务未运行」时整行隐藏 —— 此时两个动作都没有意义。
     * 处于跟随态时【回到朗读位置】是空操作(阅读页本身就在朗读处), 置灰并禁用,
     * 既保留了用户对「这两个动作存在」的认知, 又不会被误点。
     */
    private fun upAloudPositionActions() = binding.run {
        if (!BaseReadAloudService.isRun) {
            llAloudPositionActions.visible(false)
            // 第 2 行隐藏时, 它下面的分隔线也没必要留一条孤立的横线。
            vAloudActionsDivider.visible(false)
            return@run
        }
        llAloudPositionActions.visible(true)
        vAloudActionsDivider.visible(true)
        val canBackToSpeech = !ReadAloud.followReadAloudPosition
        tvAloudBackToSpeech.isEnabled = canBackToSpeech
        tvAloudBackToSpeech.alpha = if (canBackToSpeech) 1f else 0.4f
    }

    private fun initEvent() = binding.run {
        // 朗读设置(原面板底栏【设置】按钮拆掉后的新入口): 整行点按仍是切换引擎,
        // 所以另起一个图标承接, 不让同一处点击承担两种语义。
        ivAloudSettings.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        tvAloudBackToSpeech.setOnClickListener {
            // 跟随态下该动作无意义(阅读页已在朗读处); 置灰之外再拦一道, 防止误点。
            if (ReadAloud.followReadAloudPosition) return@setOnClickListener
            callBack?.backToSpeakingPosition()
            dismissAllowingStateLoss()
        }
        tvAloudReadFromHere.setOnClickListener {
            callBack?.readAloudFromVisiblePage()
            dismissAllowingStateLoss()
        }
        // 后台播放: 退出阅读页, 朗读交给前台服务继续(通知栏可控)。
        // 即原底栏【后台】按钮, 语义未变, 只是入口从左起第 2 行第 3 格。
        tvAloudBackgroundPlay.setOnClickListener {
            callBack?.finish()
        }
        llEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        tvPre.setOnClickListener {
            if (ReadAloud.followReadAloudPosition) {
                ReadAloud.prevChapter(requireContext())
            } else {
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            }
        }
        tvNext.setOnClickListener {
            if (ReadAloud.followReadAloudPosition) {
                ReadAloud.nextChapter(requireContext())
            } else {
                ReadBook.moveToNextChapter(upContent = true)
            }
        }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        ivTtsSpeechReduce.setOnClickListener {
            seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate - 1
            AppConfig.ttsSpeechRate -= 1
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate + 1
            AppConfig.ttsSpeechRate += 1
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.progress
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            showDialogFragment(
                SleepTimerDialog.newInstance(
                    BaseReadAloudService.timeMinute,
                    BaseReadAloudService.chapterToStop,
                )
            )
        }
        //设置保存的默认值
        seekTtsSpeechRate.progress = AppConfig.ttsSpeechRate
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.ttsSpeechRate = seekBar.progress
                upTtsSpeechRate()
            }
        })
        seekTimer.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) upTimerText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadAloud.setTimer(requireContext(), seekTimer.progress)
            }
        })
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(AppConfig.ttsSpeechRate)
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
        TooltipCompat.setTooltipText(
            binding.ivPlayPause,
            binding.ivPlayPause.contentDescription,
        )
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.ivPlayPause.setColorFilter(textColor)
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            binding.seekTimer.progress = when {
                BaseReadAloudService.timeMinute > 0 -> BaseReadAloudService.timeMinute
                BaseReadAloudService.chapterToStop > 0 -> 0
                else -> AppConfig.ttsTimer
            }
        }
    }

    private fun upStopText() {
        binding.tvTimer.text = when {
            BaseReadAloudService.chapterToStop > 0 -> getString(
                R.string.sleep_timer_chapters,
                BaseReadAloudService.chapterToStop,
            )

            BaseReadAloudService.timeMinute > 0 -> getString(
                R.string.timer_m,
                BaseReadAloudService.timeMinute,
            )

            else -> getString(R.string.set_timer)
        }
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = ((value + 5) / 10f).toString()
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun upEngineName() {
        val engineName = ReadAloud.getEngineName(requireContext())
        binding.tvEngineName.text = engineName
        binding.llEngine.contentDescription = "${getString(R.string.speak_engine)}: $engineName"
    }

    override fun upSpeakEngineSummary() {
        upEngineName()
    }

    override fun onSleepTimerMinute(minute: Int) {
        ReadAloud.setTimer(requireContext(), minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        ReadAloud.setChapterStop(requireContext(), count)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            upPlayState()
            upAloudBookInfo()
            upAloudPositionActions()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) {
            upAloudPositionActions()
        }
        observeEvent<Int>(EventBus.TTS_PROGRESS) {
            upAloudBookInfo()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            binding.seekTimer.progress = it
            upStopText()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_STOP) {
            if (it > 0) binding.seekTimer.progress = 0
            upStopText()
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun backToSpeakingPosition()

        /** 以当前可见页第一句为新起点重启朗读(操作行已从阅读页迁到本对话框)。 */
        fun readAloudFromVisiblePage()
        fun finish()
    }
}
