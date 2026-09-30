@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import java.text.BreakIterator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

internal fun shouldRewindReadAloudToSentenceStart(
    rewindToSentenceStart: Boolean,
    toLast: Boolean
): Boolean = rewindToSentenceStart && !toLast

internal fun findReadAloudSentenceStart(text: String, visibleOffset: Int): Int {
    if (text.isEmpty()) return 0
    val limit = visibleOffset.coerceIn(0, text.length)
    val sentenceIterator = BreakIterator.getSentenceInstance().apply { setText(text) }
    val searchOffset = if (limit < text.length) limit + 1 else limit
    return sentenceIterator.preceding(searchOffset).coerceAtLeast(0)
}

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        @JvmStatic
        var chapterToStop: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"
        private val speechFollowState = SpeechFollowState()

        /** 当前朗读所在章节索引(朗读游标),脱离跟随后与显示章节可能不同; -1 表示无 */
        @JvmStatic
        @Volatile
        var readAloudChapterIndex: Int = -1
            private set

        /** 当前朗读位置在章内的字符偏移(朗读游标); -1 表示无。由 upTtsProgress 持续更新, 进程内存活。 */
        @JvmStatic
        @Volatile
        var readAloudChapterStart: Int = -1
            private set

        /** 当前朗读章节的标题(朗读快照, 与阅读进度无关)。书架迷你条据此显示, 避免误用阅读进度。 */
        @JvmStatic
        @Volatile
        var readAloudChapterTitle: String? = null
            private set

        /** 当前朗读章节的正文总长度; 用于计算朗读进度百分比。0 表示未知。 */
        @JvmStatic
        @Volatile
        var readAloudChapterLength: Int = 0
            private set

        /** 章节切换时同步刷新朗读章节快照。 */
        @JvmStatic
        internal fun updateReadAloudChapterSnapshot(title: String?, length: Int) {
            readAloudChapterTitle = title
            readAloudChapterLength = length.coerceAtLeast(0)
        }

        /**
         * 朗读服务自持的书籍快照(静态镜像)。供阅读页外的 UI(如书架迷你条)读取,
         * 避免误用可能已被换书/重置的 ReadBook.book。
         */
        @JvmStatic
        @Volatile
        var aloudBookSnapshot: Book? = null
            private set

        /**
         * 会话是否正在异步准备章节(读正文 + 排版)。
         * 该窗口内不接受新的会话启动请求, 否则会把已确定的起点覆盖掉。
         */
        @JvmStatic
        @Volatile
        var preparingSession = false
            private set

        @JvmStatic
        fun isSessionPreparing(): Boolean = preparingSession

        @JvmStatic
        internal fun updateSessionPreparing(preparing: Boolean) {
            preparingSession = preparing
        }

        @JvmStatic
        val followReadAloudPosition: Boolean
            get() = speechFollowState.followReadAloudPosition

        @JvmStatic
        fun detachReadAloudFollow() {
            speechFollowState.detachForManualNavigation()
            // 用户接管阅读位置: 还原被朗读跟随改写的 durChapterPos 并清备份,
            // 否则会话语义上的「结束还原」会把用户手动导航的位置一起抹掉。
            ReadBook.onAloudFollowDetached()
            postEvent(EventBus.READ_ALOUD_FOLLOW, speechFollowState.followReadAloudPosition)
        }

        @JvmStatic
        fun restoreReadAloudFollow() {
            speechFollowState.restoreForNewSpeechSession()
            postEvent(EventBus.READ_ALOUD_FOLLOW, speechFollowState.followReadAloudPosition)
        }

        @JvmStatic
        fun shouldSyncSpeechNavigation(): Boolean {
            return speechFollowState.shouldSyncSpeechNavigation()
        }

        @JvmStatic
        internal fun updateReadAloudChapterIndex(index: Int) {
            readAloudChapterIndex = index
        }

        @JvmStatic
        fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
            return speechFollowState.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying)
        }

        @JvmStatic
        fun nextChapterDecision(
            hasNextSpeechChapter: Boolean,
            visibleSyncMoved: Boolean
        ): SpeechFollowState.NextChapterDecision {
            return speechFollowState.nextChapterDecision(
                hasNextSpeechChapter = hasNextSpeechChapter,
                visibleSyncMoved = visibleSyncMoved
            )
        }
    }

    /**
     * 唤醒锁持有状态。
     *
     * 是否持锁由 `AppConfig.readAloudWakeLock`(实时读取) 且「正在播放」共同决定,
     * 因此运行中切换开关能立即生效 —— 关掉立刻释放, 不会出现「界面显示已关但仍在持锁耗电」。
     */
    private var wakeLockHeld = false
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0

    /**
     * 朗读服务自持的书籍快照。
     * 阅读页退出后 ReadBook.book 可能被重置/换书, 服务不能长期依赖全局单例,
     * 否则后台续播会取不到正文、也算不出章节边界。
     */
    internal var aloudBook: Book? = null
        set(value) {
            field = value
            aloudBookSnapshot = value
        }

    /** 朗读服务自持的章节总数(含模拟章节), 用于章节边界判断。 */
    internal var aloudChapterSize: Int = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private val chapterStopTimer = ChapterStopTimer()
    private var dsJob: Job? = null
    private var readAloudJob: Coroutine<*>? = null
    private val readAloudGeneration = AtomicLong()
    private var upNotificationJob: Coroutine<*>? = null
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set

    private data class PreparedReadAloud(
        val textChapter: TextChapter,
        val pageIndex: Int,
        val readAloudNumber: Int,
        val readAloudByPage: Boolean,
        val contentList: List<String>,
        val nowSpeak: Int,
        val readAloudChapterStart: Int,
        val paragraphStartPos: Int,
        val consumedToLast: Boolean
    )

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        chapterStopTimer.clear()
        chapterToStop = 0
        restoreReadAloudFollow()
        observeLiveBus()
        initBroadcastReceiver()
        initPhoneStateListener()
        setTimer(AppConfig.ttsTimer)
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
    }

    fun observeLiveBus() {
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
                // 唤醒锁实时生效: 运行中切换开关立刻 acquire/release
                PreferKey.readAloudWakeLock -> {
                    syncWakeLockState()
                }
            }
        }
    }

    /**
     * 把唤醒锁状态对齐到「偏好开关 ∩ 当前是否在播放」。
     *
     * 之所以不能只是把配置改成实时读取: `play()` 只在起播时走过一次,
     * 运行中打开开关不会有任何调用点去 acquire, 会形成「界面显示已开但实际没持锁」的假状态;
     * 关闭方向同理会造成「显示已关但仍持锁耗电」。故所有状态变化都收敛到本方法。
     */
    @SuppressLint("WakelockTimeout")
    private fun syncWakeLockState() {
        val want = AppConfig.readAloudWakeLock && isPlay()
        if (want == wakeLockHeld) return
        if (want) {
            wakeLock.acquire()
            wifiLock?.acquire()
        } else {
            releaseWakeLockInternal()
        }
        wakeLockHeld = want
    }

    /**
     * 无条件释放。注意 `wakeLock` 是 `by lazy`: 从未 acquire 过就直接 release
     * 会先触发初始化再释放, 因此必须用 `isHeld` 守卫。
     */
    private fun releaseWakeLockInternal() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private fun releaseWakeLockIfHeld() {
        releaseWakeLockInternal()
        wakeLockHeld = false
    }

    /**
     * 划掉最近任务时: 朗读中保留服务在后台继续播放(番茄式),
     * 只有非播放态才跟随基类结束服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isPlay()) {
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        readAloudGeneration.incrementAndGet()
        readAloudJob?.cancel()
        // 服务终止: 静态的准备标记必须显式复位, 否则下次启动的隐式重启会被永久拒绝
        updateSessionPreparing(false)
        // 结束前把听书进度落库(force), 之后朗读游标会被重置
        if (readAloudChapterIndex >= 0) {
            ReadBook.saveAloudProgress(
                force = true,
                overrideBook = aloudBook,
                overrideChapterIndex = readAloudChapterIndex,
                overrideChapterPos = readAloudChapterStart.coerceAtLeast(0),
                overrideChapterTitle = textChapter?.chapter?.title
            )
        }
        super.onDestroy()
        // 听书结束: 先把内存中的阅读位置还原成听书前的值(可能重新打开阅读所在章节),
        // 再复位跟随状态, 保证后续 saveRead 不会把听书位置写成阅读进度。
        ReadBook.restoreReadingPositionAfterAloud()
        restoreReadAloudFollow()
        updateReadAloudChapterIndex(-1)
        readAloudChapterStart = -1
        updateReadAloudChapterSnapshot(null, 0)
        aloudBookSnapshot = null
        releaseWakeLockIfHeld()
        isRun = false
        pause = true
        timeMinute = 0
        chapterStopTimer.clear()
        chapterToStop = 0
        postEvent(EventBus.READ_ALOUD_DS, 0)
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, 0)
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            IntentAction.play -> newReadAloud(
                play = intent.getBooleanExtra("play", true),
                pageIndex = intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                startPos = intent.getIntExtra("startPos", 0),
                rewindToSentenceStart = intent.getBooleanExtra("rewindToSentenceStart", false),
                bookUrl = intent.getStringExtra("bookUrl"),
                chapterIndex = intent.getIntExtra("chapterIndex", -1).takeIf { it >= 0 },
                chapterPos = intent.getIntExtra("chapterPos", -1).takeIf { it >= 0 },
                allowBookSwitch = intent.getBooleanExtra("allowBookSwitch", false)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.setChapterStop -> setChapterStop(intent.getIntExtra("count", 0))
            IntentAction.stop -> stopSelf()
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    /**
     * 启动/重启一次朗读会话。
     *
     * 起点语义(修复「从此处朗读」1~2s 回跳):
     * - 调用方在点击瞬间就把 `bookUrl/chapterIndex/chapterPos` 快照并随 Intent 传入;
     * - `pageIndex` 只在调用方没有给出章节锚点时作为兜底(它由 durChapterPos 反查,
     *   而 durChapterPos 会被跟随进度改写, 不能作为权威起点)。
     *
     * 换书隔离(修复「读 a 书时点开 b 书, 朗读跟着切」):
     * - 会话内朗读的书以 `aloudBook` 为准, 不随全局 `ReadBook.book` 漂移;
     * - `allowBookSwitch = false` 的隐式重启(阅读页换书后 loadContent 完成触发)
     *   会被直接拒绝, 避免把正在朗读的会话内容换成新书。
     */
    private fun newReadAloud(
        play: Boolean,
        pageIndex: Int,
        startPos: Int,
        rewindToSentenceStart: Boolean,
        bookUrl: String? = null,
        chapterIndex: Int? = null,
        chapterPos: Int? = null,
        allowBookSwitch: Boolean = false
    ) {
        val currentAloudBookUrl = aloudBook?.bookUrl
        val targetBookUrl = bookUrl ?: ReadBook.book?.bookUrl
        // 已在朗读另一本书时, 只有显式发起的会话才允许切书。
        // 注意判据是 aloudBook 而非 isRun: isRun 在上一会话结束时才复位,
        // 而换书后的隐式重启正是发生在这个窗口内。
        if (!allowBookSwitch && currentAloudBookUrl != null &&
            targetBookUrl != null && currentAloudBookUrl != targetBookUrl
        ) {
            return
        }
        val generation = readAloudGeneration.incrementAndGet()
        val toLast = this@BaseReadAloudService.toLast
        readAloudJob?.cancel()
        playStop()
        restoreReadAloudFollow()
        // 标记会话准备中: 期间拒绝隐式重启, 保证调用方快照的起点不被覆盖
        updateSessionPreparing(true)
        // 进入听书态: 备份阅读位置, 会话结束时还原, 保证朗读不污染阅读进度
        ReadBook.backupReadingPositionForAloud()
        readAloudJob = execute(executeContext = IO) {
            // 会话目标书:
            // - 隐式重启(allowBookSwitch=false): 必须沿用会话已绑定的 aloudBook,
            //   绝不跟随全局 ReadBook 漂移到新书;
            // - 显式启动(allowBookSwitch=true): 用调用方指定的书(切书由用户主动发起)。
            val book = if (allowBookSwitch) {
                targetBookUrl?.let { appDb.bookDao.getBook(it) } ?: ReadBook.book
            } else {
                aloudBook ?: targetBookUrl?.let { appDb.bookDao.getBook(it) } ?: ReadBook.book
            } ?: return@execute
            val sameBookAsVisible = book.bookUrl == ReadBook.book?.bookUrl
            val textChapter = if (sameBookAsVisible) {
                // 与阅读页同书: 复用阅读页已排版好的章节, 避免重复解析
                ReadBook.curTextChapter
            } else {
                // 跨书/阅读页已关闭: 用会话自持的书独立加载, 不依赖全局单例
                loadSpeechTextChapterAwait(book, chapterIndex ?: 0)
            } ?: return@execute
            if (!textChapter.isCompleted) return@execute
            // 保存书籍快照与章节总数: 阅读页退出后服务仍可独立续播
            aloudBook = book
            aloudChapterSize = if (sameBookAsVisible) {
                ReadBook.simulatedChapterSize.takeIf { it > 0 }
                    ?: book.simulatedChapterSizeSnapshot()
            } else {
                book.simulatedChapterSizeSnapshot()
            }
            // 起点: 章节锚点(调用时刻快照)优先, pageIndex 仅作兜底。
            // 只有锚点章节与已排版章节一致时才用它反查页, 否则沿用调用方给的 pageIndex。
            val anchorUsable = chapterPos != null &&
                    (chapterIndex == null || chapterIndex == textChapter.chapter.index)
            val resolvedPageIndex = if (anchorUsable) {
                textChapter.getPageIndexByCharIndex(chapterPos!!).takeIf { it >= 0 } ?: pageIndex
            } else {
                pageIndex
            }
            val readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            var readAloudNumber = textChapter.getReadLength(resolvedPageIndex) + startPos
            if (shouldRewindReadAloudToSentenceStart(rewindToSentenceStart, toLast)) {
                val paragraphIndex = textChapter.getParagraphNum(readAloudNumber + 1, false) - 1
                val paragraph = textChapter.paragraphs[paragraphIndex]
                val sentenceStart = findReadAloudSentenceStart(
                    paragraph.text, readAloudNumber - paragraph.chapterPosition
                )
                readAloudNumber = paragraph.chapterPosition + sentenceStart
            }
            if (toLast) readAloudNumber = textChapter.getLastParagraphPosition()
            val nowSpeak = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            val pos = readAloudNumber - textChapter.getParagraphs(readAloudByPage)[nowSpeak].chapterPosition
            val prepared = PreparedReadAloud(
                textChapter = textChapter,
                pageIndex = textChapter.getPageIndexByCharIndex(readAloudNumber),
                readAloudNumber = readAloudNumber,
                readAloudByPage = readAloudByPage,
                contentList = contentList,
                nowSpeak = nowSpeak,
                readAloudChapterStart = readAloudNumber,
                paragraphStartPos = pos,
                consumedToLast = toLast
            )
            ensureActive()
            withContext(Main.immediate) {
                if (generation != readAloudGeneration.get()) return@withContext
                this@BaseReadAloudService.pageIndex = prepared.pageIndex
                this@BaseReadAloudService.textChapter = prepared.textChapter
                this@BaseReadAloudService.readAloudNumber = prepared.readAloudNumber
                this@BaseReadAloudService.readAloudByPage = prepared.readAloudByPage
                this@BaseReadAloudService.contentList = prepared.contentList
                this@BaseReadAloudService.nowSpeak = prepared.nowSpeak
                updateReadAloudChapterIndex(prepared.textChapter.chapter.index)
                BaseReadAloudService.readAloudChapterStart = prepared.readAloudChapterStart
                updateReadAloudChapterSnapshot(
                    prepared.textChapter.chapter.title,
                    prepared.textChapter.totalReadLength
                )
                this@BaseReadAloudService.paragraphStartPos = prepared.paragraphStartPos
                if (prepared.consumedToLast) this@BaseReadAloudService.toLast = false
                if (play) play() else pageChanged = true
            }
        }.onFinally(Main) {
            // 兜底解除准备窗口。协程被取消时不执行(此时必然有更新的会话在跑,
            // 它的 updateSessionPreparing(true) 会重新接管该状态)。
            if (generation == readAloudGeneration.get()) {
                updateSessionPreparing(false)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        isRun = true
        pause = false
        syncWakeLockState()
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        pause = true
        syncWakeLockState()
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        syncWakeLockState()
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        readAloudChapterStart = progress
        // 兜底: 章节快照缺失时(如进程内被重建)从当前朗读章节补齐, 保证迷你条不会退回阅读进度。
        textChapter?.let {
            if (readAloudChapterTitle.isNullOrBlank() || readAloudChapterLength <= 0) {
                updateReadAloudChapterSnapshot(it.chapter.title, it.totalReadLength)
            }
        }
        // 听书进度独立落库到 book.config.aloud*, 不触碰阅读进度 durChapter*
        ReadBook.saveAloudProgress(
            overrideBook = aloudBook,
            overrideChapterIndex = readAloudChapterIndex,
            overrideChapterPos = progress,
            overrideChapterTitle = textChapter?.chapter?.title
        )
        postEvent(EventBus.TTS_PROGRESS, progress)
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    ReadBook.moveToPrevPage(syncReadAloudFollow = true)
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            toLast = true
            ReadBook.moveToPrevChapter(true, syncReadAloudFollow = true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(syncReadAloudFollow = true)
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            nextChapter()
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute.coerceIn(0, 180)
        chapterStopTimer.clear()
        chapterToStop = 0
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, 0)
        doDs()
    }

    private fun addTimer() {
        val next = nextSleepTimerIncrement(
            timeMinute, chapterToStop, AppConfig.sleepTimerPreferChapter
        )
        if (next.chapter > 0) setChapterStop(next.chapter) else setTimer(next.minute)
    }

    private fun setChapterStop(count: Int) {
        chapterToStop = chapterStopTimer.set(count)
        timeMinute = 0
        dsJob?.cancel()
        postEvent(EventBus.READ_ALOUD_DS, 0)
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, chapterToStop)
        upReadAloudNotification()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        if (timeMinute <= 0) return
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 朗读标题: 有状态(暂停/定时/按章停止)时 "<状态>: 书名", 无状态时只用书名。
     * 下拉通知与系统媒体控件共用同一函数, 保证两处显示一致, 且不会出现前导分隔符/空格。
     */
    private fun buildReadAloudTitle(): String {
        val bookName = aloudBook?.name ?: ReadBook.book?.name ?: ""
        val prefix = when {
            pause -> getString(R.string.read_aloud_pause)
            chapterToStop > 0 -> getString(R.string.read_aloud_timer_chapter, chapterToStop)
            timeMinute > 0 -> getString(R.string.read_aloud_timer, timeMinute)
            else -> ""
        }
        return if (prefix.isEmpty()) bookName else "$prefix: $bookName"
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    resumeReadAloud()
                } else {
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val nTitle = buildReadAloudTitle()
        var nSubtitle = textChapter?.chapter?.title ?: ReadBook.curTextChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                // 点通知 = 「看原文」: 带上标记与朗读位置, 让阅读页定位到朗读处而非阅读进度
                activityPendingIntent<ReadBookActivity>("activity") {
                    putExtra("bookUrl", aloudBook?.bookUrl ?: ReadBook.book?.bookUrl)
                    putExtra("openAloudPos", true)
                    putExtra("aloudChapterIndex", readAloudChapterIndex)
                    putExtra("aloudChapterPos", readAloudChapterStart)
                }
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
            // 显式声明「不显示进度条」: max=0 且 indeterminate=false 时系统的 hasProgress() 为 false,
            // 不会渲染进度条。
            .setProgress(0, 0, false)
        // 按钮定义（与图2 一致）：上一章、播放/暂停、停止、下一章、定时
        // 索引 0..4 依次对应上述五项; 折叠态(图3)由 MediaStyle.setShowActionsInCompactView(1,2,4)
        // 取「暂停/停止/定时」, 索引 0 与 3 刻意跳过, 使上/下一章不占折叠槽位。
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        // 只保留 MediaStyle 的折叠态索引能力, 不绑定 MediaSession
        // (不写 EXTRA_MEDIA_SESSION ⇒ 通知不应被系统识别为媒体卡片)
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 4)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        toLast = false
        resumeReadAloudInternal()
        if (shouldSyncSpeechNavigation()) {
            ReadBook.moveToPrevChapter(true, toLast = false, syncReadAloudFollow = true)
        } else {
            loadSpeechChapterOnly(speechChapterIndex() - 1)
        }
    }

    open fun nextChapter(auto: Boolean = false) {
        ReadBook.upReadTime()
        if (auto) {
            chapterStopTimer.onChapterCompleted()?.let { stopResult ->
                chapterToStop = stopResult.remaining
                postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, chapterToStop)
                if (stopResult.shouldStop) {
                    stopSelf()
                    return
                }
                upReadAloudNotification()
            }
        }
        resumeReadAloudInternal()
        val hasNextChapter = speechChapterIndex() < speechTotalChapterSize() - 1
        // 只在「朗读的书就是阅读页当前书」时才驱动可见页同步换章;
        // 换书后台续播时必须走 loadSpeechChapterOnly, 否则会把朗读章节边界
        // 施加到另一本书的阅读页上。
        val visibleSyncMoved = if (isAloudBookVisible()) {
            ReadBook.moveToNextChapter(true, syncReadAloudFollow = true)
        } else {
            false
        }
        when (nextChapterDecision(
            hasNextSpeechChapter = hasNextChapter,
            visibleSyncMoved = visibleSyncMoved
        )) {
            SpeechFollowState.NextChapterDecision.ContinueWithVisibleSync -> Unit
            SpeechFollowState.NextChapterDecision.ContinueSpeechOnly -> {
                loadSpeechChapterOnly(speechChapterIndex() + 1)
            }

            SpeechFollowState.NextChapterDecision.Stop -> stopSelf()
        }
    }

    /**
     * 朗读会话绑定的书是否就是阅读页当前显示的书。
     * 换书后为 false, 此时朗读的换章/翻页都不能再驱动阅读页。
     */
    private fun isAloudBookVisible(): Boolean {
        val aloudUrl = aloudBook?.bookUrl ?: return true
        return aloudUrl == ReadBook.book?.bookUrl
    }

    private fun speechChapterIndex(): Int {
        return textChapter?.chapter?.index ?: readAloudChapterIndex
    }

    /** 朗读可用的章节总数: 优先用服务自持快照, 阅读页销毁后仍有效。 */
    private fun speechTotalChapterSize(): Int {
        return aloudChapterSize.takeIf { it > 0 } ?: ReadBook.simulatedChapterSize
    }

    /**
     * 用会话自持的书独立加载一个章节并排版, 不依赖全局 ReadBook 单例。
     * 用于「正在朗读的书 ≠ 阅读页当前书」的场景(换书后台续播)。
     */
    private suspend fun loadSpeechTextChapterAwait(book: Book, chapterIndex: Int): TextChapter? {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        val contentProcessor = ContentProcessor.get(book)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val contents = contentProcessor.getContent(book, chapter, content, includeTitle = false)
        val textChapter = ChapterProvider.getTextChapterAsync(
            lifecycleScope,
            book,
            chapter,
            displayTitle,
            contents,
            book.simulatedChapterSizeSnapshot()
        )
        // 等待排版产出首页, 否则下面的分页/取段都会拿到空数据
        for (page in textChapter.layoutChannel) {
            if (page.index > 0) continue
        }
        return textChapter
    }

    /** 书籍的章节总数(含模拟章节), 不依赖 ReadBook 单例。 */
    private fun Book.simulatedChapterSizeSnapshot(): Int {
        return if (readSimulating()) simulatedTotalChapterNum()
        else appDb.bookChapterDao.getChapterCount(bookUrl)
    }

    private fun loadSpeechChapterOnly(chapterIndex: Int) {
        if (chapterIndex !in 0..<speechTotalChapterSize()) return
        execute(executeContext = IO) {
            val book = aloudBook ?: ReadBook.book ?: return@execute false
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@execute false
            val content = BookHelp.getContent(book, chapter)
                ?: "加载正文失败\n无内容"
            val contentProcessor = ContentProcessor.get(book)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
            val contents = contentProcessor.getContent(book, chapter, content, includeTitle = false)
            val nextTextChapter = ChapterProvider.getTextChapterAsync(
                this,
                book,
                chapter,
                displayTitle,
                contents,
                speechTotalChapterSize()
            )
            for (page in nextTextChapter.layoutChannel) {
                if (page.index > 0) continue
            }
            textChapter = nextTextChapter
            updateReadAloudChapterIndex(chapter.index)
            readAloudChapterStart = 0
            updateReadAloudChapterSnapshot(chapter.title, nextTextChapter.totalReadLength)
            pageIndex = 0
            readAloudNumber = 0
            nowSpeak = 0
            paragraphStartPos = 0
            contentList = nextTextChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            contentList.isNotEmpty()
        }.onSuccess(Main) { canContinue ->
            if (canContinue) {
                upTtsProgress(0)
                play()
            } else {
                stopSelf()
            }
        }.onError(Main) {
            stopSelf()
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        resumeReadAloud()
                    } else {
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                }
            }
        }
    }

}
