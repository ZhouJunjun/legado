package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

internal fun pendingSpeechPageMoves(currentPageIndex: Int, targetPageIndex: Int): Int =
    (targetPageIndex - currentPageIndex).coerceAtLeast(0)

/** 整本读完收尾播报的 utteranceId 尾段标记(与普通朗读的 `:true/false` 区分开)。 */
private const val FINISH_FLAG = "finish"

/** 收尾播报的超时兜底: 个别 TTS 引擎不回调 onDone, 不能一直等下去。 */
private const val FINISH_SPEAK_TIMEOUT_MS = 10_000L

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var playRetryJob: Coroutine<*>? = null
    /** 「朗读列表为空」已重试过的 书+章节 键, 避免章节确实无内容时反复重试。 */
    private var emptyContentRetriedKey: String? = null
    private val playbackSessionId = AtomicLong()
    private val callbackHandler by lazy { buildMainHandler() }
    private val TAG = "TTSReadAloudService"
    /** 正在等待「整本读完」的收尾播报播完, 播完才停服务。 */
    private var stoppingAfterFinishSpeak = false
    private val finishSpeakTimeout = Runnable {
        if (stoppingAfterFinishSpeak) {
            stoppingAfterFinishSpeak = false
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callbackHandler.removeCallbacks(finishSpeakTimeout)
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        playbackSessionId.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        val sessionId = playbackSessionId.incrementAndGet()
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            // 隐式重启: 不得把正在朗读的会话切到当前打开的书。
            // 但本方法是在会话"准备中"窗口内被调用的(BaseReadAloudService 的准备协程末尾
            // 调用 play()), 此刻 isSessionPreparing() 仍为 true, 直接调用会被
            // ReadBook.readAloud() 的准备窗口守卫丢弃 —— 现象是点了朗读毫无反应。
            // 故延后到准备窗口关闭后再重试; 同一书+章节只重试一次, 避免空内容时无限循环。
            val retryKey = "${ReadBook.book?.bookUrl}#${ReadBook.durChapterIndex}"
            if (emptyContentRetriedKey != retryKey) {
                emptyContentRetriedKey = retryKey
                playRetryJob?.cancel()
                playRetryJob = execute {
                    delay(500)
                    ReadBook.readAloud(allowBookSwitch = false)
                }
            }
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        val startSpeak = nowSpeak
        val startParagraphPos = paragraphStartPos
        val speechChapter = textChapter ?: return
        val paragraphs = speechChapter.getParagraphs(readAloudByPage)
        val pageStarts = speechChapter.pages.map { it.chapterPosition }
        val queuedContent = contentList
        speakJob = execute {
            if (textToSpeech == null) throw NoStackTraceException("tts is null")
            val contentList = queuedContent
            var isAddedText = false
            for (i in startSpeak until contentList.size) {
                ensureActive()
                if (!isCurrentPlayback(sessionId)) return@execute
                val paragraphText = contentList[i]
                val paragraph = paragraphs[i]
                val firstOffset = if (i == startSpeak) startParagraphPos else 0
                val text = paragraphText.substring(firstOffset)
                if (text.matches(AppPattern.notReadAloudRegex)) continue
                var chunkStart = paragraph.chapterPosition + firstOffset
                val textEnd = chunkStart + text.length
                // Queue page boundaries ahead of playback: engines without range callbacks still
                // report the next page's real start, without an application pause or queue flush.
                val boundaries = pageStarts.filter { it > chunkStart && it < textEnd } + textEnd
                for (chunkEnd in boundaries) {
                    val chunk = paragraphText.substring(
                        chunkStart - paragraph.chapterPosition, chunkEnd - paragraph.chapterPosition
                    )
                    val result = speakCurrent(
                        sessionId, chunk,
                        if (isAddedText) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                        i, chunkStart, chunkEnd == textEnd
                    ) ?: return@execute
                    if (result == TextToSpeech.ERROR) {
                        if (!isAddedText) {
                            clearTTS()
                            initTts()
                            return@execute
                        }
                    }
                    isAddedText = true
                    chunkStart = chunkEnd
                }
            }
            if (!isAddedText && isCurrentPlayback(sessionId)) {
                playStop()
                val stoppedSessionId = playbackSessionId.get()
                delay(1000)
                if (stoppedSessionId == playbackSessionId.get()) nextChapter(auto = true)
            }
        }.onError {
        }
    }

    @Synchronized
    override fun playStop() {
        playbackSessionId.incrementAndGet()
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 整本书朗读完毕: 先播报一句「已朗读完所有内容」, 播完再停服务。
     *
     * 🔴 不能直接 stopSelf(): onDestroy → clearTTS() 会 stop+shutdown TTS,
     * 刚排队的那句话根本来不及出声。
     * 也不能只等 onDone: 个别引擎不回调 → 配了超时兜底。
     */
    @Synchronized
    override fun speakBookFinishedAndStop() {
        val tts = textToSpeech
        if (tts == null) {
            stopSelf()
            return
        }
        stoppingAfterFinishSpeak = true
        // 提高会话号, 让仍在途的旧朗读回调全部失效; 同时留一个可识别的 utteranceId。
        val sessionId = playbackSessionId.incrementAndGet()
        speakJob?.cancel()
        val result = tts.runCatching {
            speak(
                getString(R.string.read_aloud_book_finished),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "${AppConst.APP_TAG}:$sessionId:0:0:$FINISH_FLAG"
            )
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            stoppingAfterFinishSpeak = false
            stopSelf()
            return
        }
        callbackHandler.postDelayed(finishSpeakTimeout, FINISH_SPEAK_TIMEOUT_MS)
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        /**
         * 收尾播报(「已朗读完所有内容」)的 utteranceId 识别。
         *
         * 🔴 收尾播报必须从所有回调里**及早剔除**:
         * - onStart 会去读 `contentList[nowSpeak]`, 但整本读完后 contentList 已耗尽 → 越界崩溃;
         *    即便不崩, 也会把页面翻回首页、进度条归零;
         * - onError / onDone 会走 `nextParagraph()` → nextChapter → Stop → 再播一遍收尾 → 死循环。
         */
        private fun isFinishUtterance(id: String?): Boolean = id?.endsWith(":$FINISH_FLAG") == true

        override fun onStart(s: String) {
            if (isFinishUtterance(s)) return
            dispatchCurrentCallback(s) {
                val msg = "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s"
                if (textChapter != null) {
                    if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                        nextParagraph()
                    }
                    val position = utterancePosition(s)
                    moveToSpeechPage(position)
                    upTtsProgress(position)
                }
            }
        }

        override fun onDone(s: String) {
            // 收尾播报: 播完就停服务, 不参与正常的「推进到下一段」。
            if (isFinishUtterance(s)) {
                if (stoppingAfterFinishSpeak) {
                    stoppingAfterFinishSpeak = false
                    callbackHandler.removeCallbacks(finishSpeakTimeout)
                    stopSelf()
                }
                return
            }
            dispatchCurrentCallback(s) {
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (isFinishUtterance(utteranceId)) return
            dispatchCurrentCallback(utteranceId) {
                val msg =
                    "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                val position = utterancePosition(utteranceId) + start
                moveToSpeechPage(position)
                upTtsProgress(position)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (isFinishUtterance(utteranceId)) {
                // 收尾播报失败: 不走 nextParagraph(会形成重复播报死循环), 直接收工。
                if (stoppingAfterFinishSpeak) {
                    stoppingAfterFinishSpeak = false
                    callbackHandler.removeCallbacks(finishSpeakTimeout)
                    stopSelf()
                }
                return
            }
            dispatchCurrentCallback(utteranceId) {
                if (utteranceId?.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter(auto = true)
                    return
                }
                readAloudNumber = checkNotNull(textChapter).getParagraphs(readAloudByPage)[nowSpeak].chapterPosition
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (isFinishUtterance(s)) return
            dispatchCurrentCallback(s) {
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun moveToSpeechPage(position: Int): Boolean {
            val targetPageIndex = textChapter?.getPageIndexByCharIndex(position) ?: return false
            val moves = pendingSpeechPageMoves(pageIndex, targetPageIndex)
            repeat(moves) {
                pageIndex++
                ReadBook.moveToNextPage(syncReadAloudFollow = true)
            }
            return moves > 0
        }

    }

    private fun utterancePosition(id: String?): Int =
        id?.split(':', limit = 5)?.getOrNull(3)?.toIntOrNull() ?: readAloudNumber

    private fun utteranceId(sessionId: Long, index: Int, position: Int, last: Boolean): String {
        return "${AppConst.APP_TAG}:$sessionId:$index:$position:$last"
    }

    private fun speakCurrent(
        sessionId: Long,
        text: String,
        queueMode: Int,
        index: Int,
        position: Int,
        last: Boolean
    ): Int? {
        synchronized(this) {
            if (!isCurrentPlayback(sessionId)) return null
            val tts = textToSpeech ?: return TextToSpeech.ERROR
            return tts.runCatching {
                speak(text, queueMode, null, utteranceId(sessionId, index, position, last))
            }.getOrElse {
                TextToSpeech.ERROR
            }
        }
    }

    private fun isCurrentPlayback(sessionId: Long): Boolean {
        return sessionId == playbackSessionId.get()
    }

    private fun dispatchCurrentCallback(utteranceId: String?, block: () -> Unit) {
        val prefix = "${AppConst.APP_TAG}:"
        val sessionId = utteranceId
            ?.takeIf { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.substringBefore(':')
            ?.toLongOrNull()
            ?: return
        if (sessionId != playbackSessionId.get()) return
        callbackHandler.post {
            synchronized(this) {
                if (sessionId == playbackSessionId.get()) block()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
