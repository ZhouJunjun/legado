package io.legado.app.model

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.updateSnapshot
import io.legado.app.data.entities.saveWithCover
import io.legado.app.help.AppWebDav
import io.legado.app.help.HighlightAnchor
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentSaveToken
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.read.page.findPdfPagePosition
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.HighlightSpacing
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

internal fun resolveHighlightChapterPosition(
    rawPosition: Int,
    sourceTitleLength: Int,
    currentTitleLength: Int
): Int {
    val currentLength = currentTitleLength.coerceAtLeast(0)
    val sourceLength = sourceTitleLength.takeIf { it >= 0 } ?: currentLength
    return (rawPosition - sourceLength).coerceAtLeast(0) + currentLength
}

// ponytail: fixed 64-character anchor; use contextual matching if sources rewrite larger spans.
private const val REFRESH_POSITION_ANCHOR_LENGTH = 64

/** 听书进度落库节流间隔(毫秒)，避免朗读时每句话都写库。 */
private const val ALOUD_PROGRESS_SAVE_INTERVAL = 10_000L

internal fun resolveLayoutBodyPosition(source: String, position: Int, target: String): Int? {
    if (source == target) return position.coerceIn(0, target.length)
    val sourceParagraphs = source.split('\n')
    val targetParagraphs = target.split('\n')
    // Indentation contributes to chapterPosition. Match the entire body before using
    // paragraph order, so repeated sentences stay in their original paragraph.
    if (sourceParagraphs.size != targetParagraphs.size || sourceParagraphs.indices.any {
            sourceParagraphs[it].trimStart() != targetParagraphs[it].trimStart()
        }) return null
    var sourceStart = 0
    var targetStart = 0
    for (index in sourceParagraphs.indices) {
        val old = sourceParagraphs[index]
        val new = targetParagraphs[index]
        if (position <= sourceStart + old.length) {
            val oldIndent = old.length - old.trimStart().length
            val newIndent = new.length - new.trimStart().length
            val offset = (position - sourceStart - oldIndent).coerceAtLeast(0)
            return targetStart + (newIndent + offset).coerceAtMost(new.length)
        }
        sourceStart += old.length + 1
        targetStart += new.length + 1
    }
    return target.length
}

internal fun resolveReplacePreviewPosition(
    sourceText: String,
    sourceTitleLength: Int,
    sourcePosition: Int,
    previewText: String,
    previewTitleLength: Int,
): Int {
    val sourceTitle = sourceTitleLength.coerceAtLeast(0)
    val previewTitle = previewTitleLength.coerceAtLeast(0)
    val sourceBody = sourceText.drop(sourceTitle)
    val previewBody = previewText.drop(previewTitle)
    val sourceBodyPosition = (sourcePosition - sourceTitle).coerceIn(0, sourceBody.length)
    val anchor = sourceBody.drop(sourceBodyPosition).take(REFRESH_POSITION_ANCHOR_LENGTH)
    val previewBodyPosition = (if (anchor.isEmpty()) {
        sourceBodyPosition.coerceAtMost(previewBody.length)
    } else {
        HighlightAnchor.jumpPos(previewBody, sourceBodyPosition, anchor)
    }).coerceIn(0, previewBody.length)
    return previewTitle + previewBodyPosition
}


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {

    data class ReplacePreview(
        val sourceChapter: TextChapter,
        val previewChapter: TextChapter,
        val sourcePosition: Int,
        val sourceProgressPosition: Int,
        val chapterPosition: Int,
        val bookUrl: String,
        val chapterIndex: Int,
    )

    private data class ManualReplaceRules(
        val title: List<ReplaceRule>,
        val content: List<ReplaceRule>,
    ) {
        val enabled get() = title.isNotEmpty() || content.isNotEmpty()
    }

    var book: Book? = null
    var callBack: CallBack? = null
    var highlights: List<BookHighlight> = emptyList()
        private set
    @Volatile
    private var highlightsVersion = 0L
    var highlightRules: List<HighlightRule> = emptyList()
        private set
    private var highlightRulesVersion = 0L
    private var highlightRulesBookUrl: String? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecordLock = Any()
    private var readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    private var pendingHighlightJump: PendingHighlightJump? = null
    private var pendingHighlightAnchor: PendingHighlightAnchor? = null
    private data class PendingPdfJump(val bookUrl: String, val chapterIndex: Int, val pageIndex: Int)
    private var pendingPdfJump: PendingPdfJump? = null
    var readStartTime: Long = System.currentTimeMillis()

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    fun resetData(book: Book) {
        val positionAnchor = pendingHighlightAnchor
        releaseAndCancel()
        // 换书: 清掉上一本书遗留的朗读定位豁免与阅读位置备份(同 upData)。
        speechSelfPositioningChapter = -1
        aloudReadingBackup = null
        synchronized(readRecordLock) {
            ReadBook.book = book
            resetReadRecord(book)
        }
        loadHighlights(book)
        loadHighlightRules(book)
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        upWebBook(book)
        clearTextChapter()
        pendingHighlightAnchor = positionAnchor?.takeIf {
            it.waitForLayout &&
                it.bookUrl == book.bookUrl &&
                it.chapterIndex == book.durChapterIndex &&
                it.rawPosition == book.durChapterPos
        }
        callBack?.upContent()
        callBack?.upMenuView()
        callBack?.upPageAnim()
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    private fun manualReplaceRules(book: Book): ManualReplaceRules? {
        if (!AppConfig.manualReplaceRule) return null
        val ids = book.config.manualReplaceRuleIds
        val rules = if (ids.isEmpty()) {
            emptyList()
        } else {
            appDb.replaceRuleDao.findByIds(*ids.toLongArray())
        }
        return ManualReplaceRules(
            title = rules.filter { it.scopeTitle },
            content = rules.filter { it.scopeContent },
        )
    }

    internal fun processChapterContent(book: Book, chapter: BookChapter, content: String) =
        ContentProcessor.get(book).let { processor ->
            val manualRules = manualReplaceRules(book)
            val title = chapter.getDisplayTitle(
                manualRules?.title ?: processor.getTitleReplaceRules(),
                manualRules?.enabled ?: book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook(),
            )
            title to processor.getContent(
                book, chapter, content, includeTitle = false,
                replaceEnabledOverride = manualRules?.enabled,
                titleReplaceRulesOverride = manualRules?.title,
                contentReplaceRulesOverride = manualRules?.content,
            )
        }

    fun loadHighlights(book: Book) {
        invalidateHighlightSpacing()
        highlights = appDb.bookHighlightDao.getByBook(book.bookUrl)
        highlightsVersion++
    }

    fun loadHighlightRules(book: Book) {
        invalidateHighlightRuleMatches()
        highlightRules = appDb.highlightRuleDao.findEnabledByBook(book.name, book.origin)
        highlightRulesBookUrl = book.bookUrl
        highlightRulesVersion++
    }

    fun upHighlightRules() {
        book?.let { loadHighlightRules(it) }
        callBack?.upContent(resetPageOffset = false)
    }

    fun ruleMatchesOfChapter(textChapter: TextChapter): List<HighlightRuleMatcher.RuleMatch> {
        val currentBook = book ?: return emptyList()
        if (highlightRules.isEmpty() || !textChapter.isCompleted) return emptyList()
        if (!textChapter.isForBook(currentBook) || !isActiveTextChapter(textChapter)) {
            return emptyList()
        }
        val version = highlightRulesVersion
        val bookUrl = currentBook.bookUrl
        if (textChapter.highlightRuleMatchesVersion == version &&
            textChapter.highlightRuleMatchesBookUrl == bookUrl
        ) {
            return textChapter.highlightRuleMatches ?: emptyList()
        }
        if (textChapter.highlightRuleMatchesJob?.isActive == true) return emptyList()
        val rules = highlightRules.map {
            HighlightRuleMatcher.Rule(
                it.id,
                it.pattern,
                it.isRegex,
                it.styleObj(),
                it.timeoutMillisecond,
                applyToTitle = it.applyToTitle,
                applyToBody = it.applyToBody
            )
        }
        val chapterBookUrl = textChapter.chapter.bookUrl
        val chapterIndex = textChapter.chapter.index
        lateinit var job: Job
        job = launch(Default, start = CoroutineStart.LAZY) {
            val matchResult = HighlightRuleMatcher.matchDetailed(
                chapterText(textChapter),
                rules,
                shouldContinue = { job.isActive },
                titleLength = textChapter.layoutTitleLength
            )
            withContext(Main) {
                if (highlightRulesVersion != version ||
                    highlightRulesBookUrl != bookUrl ||
                    book?.bookUrl != bookUrl ||
                    textChapter.chapter.bookUrl != chapterBookUrl ||
                    textChapter.chapter.index != chapterIndex ||
                    !textChapter.isCompleted ||
                    !isActiveTextChapter(textChapter) ||
                    textChapter.highlightRuleMatchesJob !== job
                ) return@withContext
                textChapter.highlightRuleMatches = if (matchResult.completed) {
                    matchResult.matches
                } else {
                    emptyList()
                }
                textChapter.highlightRuleMatchesVersion = version
                textChapter.highlightRuleMatchesBookUrl = bookUrl
                callBack?.upContent(resetPageOffset = false)
            }
        }
        textChapter.highlightRuleMatchesJob = job
        job.invokeOnCompletion {
            if (textChapter.highlightRuleMatchesJob === job) {
                textChapter.highlightRuleMatchesJob = null
            }
        }
        job.start()
        return emptyList()
    }

    private fun chapterText(textChapter: TextChapter): String {
        textChapter.highlightText?.let { return it }
        val cacheResult = textChapter.isCompleted
        val text = HighlightTextBuilder.build(
            textChapter.pages.flatMap { page ->
                page.lines.map { line ->
                    HighlightTextBuilder.LineInput(line.text, line.isParagraphEnd)
                }
            }
        )
        if (cacheResult) textChapter.highlightText = text
        return text
    }

    private fun isActiveTextChapter(textChapter: TextChapter): Boolean {
        return prevTextChapter === textChapter ||
            curTextChapter === textChapter ||
            nextTextChapter === textChapter
    }

    private fun observeHighlightRuleLayout(textChapter: TextChapter) {
        textChapter.setProgressListener(object : LayoutProgressListener {
            override fun onLayoutCompleted() {
                launch { ruleMatchesOfChapter(textChapter) }
            }
        })
        if (textChapter.isCompleted) ruleMatchesOfChapter(textChapter)
    }

    private fun invalidateHighlightRuleMatches() {
        invalidateHighlightSpacing()
        prevTextChapter?.invalidateHighlightRuleMatches()
        curTextChapter?.invalidateHighlightRuleMatches()
        nextTextChapter?.invalidateHighlightRuleMatches()
    }

    private fun invalidateHighlightSpacing() {
        listOfNotNull(prevTextChapter, curTextChapter, nextTextChapter).forEach {
            it.highlightSpacingJob?.cancel()
            it.highlightSpacingJob = null
            it.highlightSpacingRequest = null
        }
    }

    fun highlightRangesOfChapter(chapter: TextChapter): List<HighlightMatcher.Range> {
        val rules = ruleMatchesOfChapter(chapter).map {
            HighlightMatcher.Range(it.start, it.end, it.style, it.applyToTitle, it.applyToBody)
        }
        val titleLength = chapter.layoutTitleLength
        val manual = if (titleLength >= 0) {
            anchoredHighlightsOfChapter(chapter, titleLength).map { (highlight, anchor) ->
                HighlightMatcher.Range(anchor.start + titleLength, anchor.end + titleLength,
                    highlight.styleObj())
            }
        } else emptyList()
        return rules + manual
    }

    private fun highlightLayoutState() = listOf(
        ReadBookConfig.config.copy(), ReadBookConfig.useZhLayout,
        ReadBookConfig.textFullJustify, ReadBookConfig.hangingPunctuation,
        ReadBookConfig.punctuationCompress, AppConfig.adaptSpecialStyle,
        book?.getPageAnim(), book?.getImageStyle(),
        listOf(ChapterProvider.titlePaint, ChapterProvider.titleNumberPaint,
            ChapterProvider.contentPaint).map {
            listOf(it, it.textSize, it.textScaleX, it.textSkewX, it.letterSpacing,
                it.typeface, it.color, it.flags, it.fontFeatureSettings)
        },
        ChapterProvider.viewWidth, ChapterProvider.viewHeight, ChapterProvider.doublePage,
        ChapterProvider.visibleWidth, ChapterProvider.visibleHeight,
        ChapterProvider.paddingLeft, ChapterProvider.paddingTop, ChapterProvider.paddingRight,
        ChapterProvider.paddingBottom,
        ChapterProvider.lineSpacingExtra, ChapterProvider.titleLineSpacingExtra,
        ChapterProvider.paragraphSpacing, ChapterProvider.titleTopSpacing,
        ChapterProvider.titleBottomSpacing, ChapterProvider.indentCharWidth,
    )

    /** Whether styles can be applied to the currently visible, coherent layout. */
    fun upHighlightSpacing(chapter: TextChapter, ranges: List<HighlightMatcher.Range>): Boolean {
        val currentBook = book ?: return true
        if (!chapter.isCompleted || chapter.isTransient || !chapter.isForBook(currentBook) ||
            !isActiveTextChapter(chapter)
        ) return true
        if (chapter.highlightSpacingJob?.isActive == true ||
            (chapter.highlightRuleMatchesJob?.isActive == true &&
                chapter.highlightRuleMatchesVersion != highlightRulesVersion)
        ) return false
        if (chapter.highlightSpacing.isEmpty && ranges.none {
                it.style.changesTextMetrics ||
                    it.style.fill != 0 && it.style.resolvedFillShape == HighlightStyle.FillShape.PILL
            }) return true
        // Always measure from the original advances; measuring the replacement compounds padding.
        val base = chapter.highlightSpacingBase ?: chapter
        // Completed lines receive review columns on Main. Snapshot their advances here.
        val spacing = HighlightSpacing.resolve(base, ranges)
        if (spacing == chapter.highlightSpacing) return true
        val manualVersion = highlightsVersion
        val ruleVersion = highlightRulesVersion
        val bookUrl = currentBook.bookUrl
        val chapterUrl = chapter.chapter.url
        val chapterIndex = chapter.chapter.index
        val contentToken = BookHelp.contentSaveToken(currentBook, chapter.chapter)
        val layoutState = highlightLayoutState()
        lateinit var job: Job
        fun isCurrent() = book === currentBook && book?.bookUrl == bookUrl &&
            chapter.chapter.url == chapterUrl && chapter.chapter.index == chapterIndex &&
            isActiveTextChapter(chapter) && chapter.highlightSpacingJob === job &&
            highlightsVersion == manualVersion && highlightRulesVersion == ruleVersion &&
            BookHelp.isContentSaveCurrent(contentToken)
        job = launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                if (!isCurrent() || layoutState != highlightLayoutState()) return@launch
                chapter.highlightSpacingRequest = spacing
                val replacement = if (spacing.isEmpty) base else coroutineScope {
                    val result = base.layoutWithHighlightSpacing(this, spacing)
                        ?: return@coroutineScope null
                    for (page in result.layoutChannel) {
                        ensureActive()
                        if (!isCurrent()) throw CancellationException("Highlight layout was superseded")
                    }
                    result
                } ?: return@launch
                val sameText = withContext(Default) { chapterText(base) == chapterText(replacement) }
                if (!isCurrent()) return@launch
                if (layoutState != highlightLayoutState()) {
                    retry = true
                    return@launch
                }
                check(sameText && base.layoutTitleLength == replacement.layoutTitleLength) {
                    "Highlight spacing changed canonical chapter text"
                }
                if (!replacement.isCompleted) return@launch
                // Review counts can arrive while layout runs without changing highlight versions.
                if (HighlightSpacing.resolve(base, ranges) != spacing) {
                    retry = true
                    return@launch
                }
                replacement.highlightRuleMatches = chapter.highlightRuleMatches
                replacement.highlightRuleMatchesVersion = chapter.highlightRuleMatchesVersion
                replacement.highlightRuleMatchesBookUrl = chapter.highlightRuleMatchesBookUrl
                replacement.manualHighlightAnchors = chapter.manualHighlightAnchors
                replacement.manualHighlightAnchorsVersion = chapter.manualHighlightAnchorsVersion
                replacement.manualHighlightAnchorsTitleLength = chapter.manualHighlightAnchorsTitleLength
                // Publish a complete chapter on Main. Keep the latest durChapterPos, including
                // any user navigation that happened while the replacement was being laid out.
                if (prevTextChapter === chapter) prevTextChapter = replacement
                if (curTextChapter === chapter) curTextChapter = replacement
                if (nextTextChapter === chapter) nextTextChapter = replacement
                callBack?.upContent(chapterIndex - durChapterIndex, resetPageOffset = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("Highlight spacing layout failed", error)
            } finally {
                if (chapter.highlightSpacingJob === job) {
                    chapter.highlightSpacingJob = null
                    chapter.highlightSpacingRequest = null
                    if (retry) upHighlightSpacing(chapter, highlightRangesOfChapter(chapter))
                }
            }
        }
        chapter.highlightSpacingJob = job
        job.start()
        return false
    }

    fun highlightsOfChapter(
        chapter: TextChapter,
        layoutTitleLength: Int? = null
    ): List<BookHighlight> {
        val currentBook = book ?: return emptyList()
        val bookChapter = chapter.chapter
        val legacyBound = highlights.filter {
            it.bindLegacyChapter(currentBook, bookChapter, chapter.title)
        }
        if (legacyBound.isNotEmpty()) {
            val legacyTimes = legacyBound.map { it.time }
            executor.execute {
                appDb.bookHighlightDao.bindChapterUrl(legacyTimes, bookChapter.url)
            }
        }
        val chapterHighlights = highlights
            .filter { it.isForChapter(currentBook, bookChapter) }
            .sortedWith(compareBy(BookHighlight::chapterPos, BookHighlight::time))
        val titleLength = layoutTitleLength ?: return chapterHighlights
        val pinned = chapterHighlights.filter { it.pinLayoutTitleLength(titleLength) }
        if (pinned.isNotEmpty()) {
            executor.execute {
                appDb.bookHighlightDao.pinLayoutTitleLength(
                    currentBook.bookUrl,
                    bookChapter.url,
                    titleLength
                )
            }
        }
        return chapterHighlights
    }

    fun anchoredHighlightsOfChapter(
        chapter: TextChapter,
        layoutTitleLength: Int
    ): List<Pair<BookHighlight, HighlightAnchor.Anchor>> {
        val version = highlightsVersion
        if (chapter.isCompleted &&
            chapter.manualHighlightAnchorsVersion == version &&
            chapter.manualHighlightAnchorsTitleLength == layoutTitleLength
        ) {
            return chapter.manualHighlightAnchors.orEmpty()
        }
        val chapterHighlights = highlightsOfChapter(chapter, layoutTitleLength)
        if (!chapter.isCompleted) {
            return chapterHighlights.map { highlight ->
                highlight to HighlightAnchor.Anchor(
                    highlight.bodyStart(layoutTitleLength),
                    highlight.bodyEnd(layoutTitleLength)
                )
            }
        }
        val anchors = if (chapterHighlights.isEmpty()) {
            emptyList()
        } else {
            val bodyText = chapterText(chapter).drop(layoutTitleLength)
            chapterHighlights.mapNotNull { highlight ->
                HighlightAnchor.reanchor(
                    bodyText,
                    highlight.bodyStart(layoutTitleLength),
                    highlight.bodyEnd(layoutTitleLength),
                    highlight.bookText
                )?.let { highlight to it }
            }
        }
        if (highlightsVersion == version) {
            chapter.manualHighlightAnchors = anchors
            chapter.manualHighlightAnchorsTitleLength = layoutTitleLength
            chapter.manualHighlightAnchorsVersion = version
        }
        return anchors
    }

    fun addHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.insert(highlight)
        if (!highlight.isForBook(book)) return
        highlights = (highlights.filterNot { it.time == highlight.time } + highlight)
            .sortedWith(
                compareBy(BookHighlight::chapterIndex, BookHighlight::chapterPos, BookHighlight::time)
            )
        highlightsVersion++
        invalidateHighlightSpacing()
        callBack?.upContent(resetPageOffset = false)
    }

    fun updateHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.update(highlight)
        if (!highlight.isForBook(book)) return
        highlights = highlights.map { if (it.time == highlight.time) highlight else it }
        highlightsVersion++
        invalidateHighlightSpacing()
        callBack?.upContent(resetPageOffset = false)
    }

    fun removeHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.delete(highlight)
        if (!highlight.isForBook(book)) return
        highlights = highlights.filter { it.time != highlight.time }
        highlightsVersion++
        invalidateHighlightSpacing()
        callBack?.upContent(resetPageOffset = false)
    }

    fun saveLastHighlightStyle(style: HighlightStyle) {
        appCtx.putPrefString(PreferKey.highlightLastStyle, GSON.toJson(style.normalized()))
    }

    fun upData(book: Book) {
        releaseAndCancel()
        // 换书/重载: 上一本书遗留的朗读定位豁免与阅读位置备份都失效了。
        // 若不清, 新书的章节索引可能恰好命中旧豁免, 让该次加载被误判为「朗读自身定位」
        // 从而跳过朗读会话重启; 备份同理, 会把上一本书的阅读位置还原到新书上。
        speechSelfPositioningChapter = -1
        // 例外: 朗读跟随中重开「正在朗读的这本书」(后台朗读后回到阅读页)。
        // 此时内存里的 durChapterIndex/durChapterPos 是朗读游标, 而 DB 行里的
        // durChapter* 是阅读进度(跟随期间 saveRead 被门控, 不随朗读推进)。
        // 若照常清掉备份并用 DB 值覆盖游标, 后续加载会因为章节被清空而触发一次
        // 隐式朗读重启, 且起点取的是这份旧值 —— 表现为朗读进度跳回开始朗读的地方。
        val followAloudSameBook = BaseReadAloudService.isRun &&
                ReadAloud.followReadAloudPosition &&
                BaseReadAloudService.aloudBookSnapshot?.bookUrl == book.bookUrl
        aloudReadingBackup = if (followAloudSameBook) {
            // 备份重建为 DB 里的真实阅读位置: 跟随期间它是唯一未被朗读游标污染的
            // 阅读进度来源, 会话结束时据此还原阅读进度。
            Triple(book.bookUrl, book.durChapterIndex, book.durChapterPos)
        } else {
            null
        }
        synchronized(readRecordLock) {
            if (readRecord.bookName != book.name || readRecord.author != book.author) {
                upReadTime()
                resetReadRecord(book)
            }
            ReadBook.book = book
        }
        loadHighlights(book)
        loadHighlightRules(book)
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (followAloudSameBook) {
            // 朗读跟随中重开本书: 内存游标是朗读位置, 阅读进度只存在于 DB 行里。
            // 绝不能像普通重载那样用 DB 值覆盖游标并清空章节 —— 那会让后续加载
            // 触发一次隐式朗读重启, 而重启起点正是这份旧值,
            // 表现为「朗读进度跳回开始朗读的地方」。
            // 服务静态游标比内存更权威(它随朗读推进持续更新), 优先采用。
            val aloudChapterIndex = BaseReadAloudService.readAloudChapterIndex
            val aloudChapterStart = BaseReadAloudService.readAloudChapterStart
            if (aloudChapterIndex >= 0 && aloudChapterStart >= 0) {
                if (durChapterIndex != aloudChapterIndex) {
                    durChapterIndex = aloudChapterIndex
                    durChapterPos = aloudChapterStart
                    clearTextChapter()
                    // 跨章才会真正重载章节, 也只有这时需要豁免:
                    // 把本次加载标记为「朗读自身定位」, 由 curPageChanged() 消费后
                    // 跳过朗读会话重启 —— 正在运行的会话已持有正确起点, 重启只会用
                    // 跟随期间被改写的游标覆盖它。阅读页只做可见位置镜像。
                    // 标记按章节索引匹配, 未被消费时会被后续 openChapter 改写/清除, 不会长期残留。
                    speechSelfPositioningChapter = aloudChapterIndex
                } else {
                    // 同章: 不重载、不产生 curPageChanged, 只把可见位置对齐到朗读处。
                    durChapterPos = aloudChapterStart
                }
            }
        } else {
            if (durChapterIndex != book.durChapterIndex) {
                durChapterIndex = book.durChapterIndex
                durChapterPos = book.durChapterPos
                clearTextChapter()
            }
            speechSelfPositioningChapter = -1
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        upWebBook(book)
        callBack?.upMenuView()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.detachReadAloudFollow()
        }
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            saveRead()
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        pendingHighlightJump = null
        pendingHighlightAnchor = null
        pendingPdfJump = null
        invalidateHighlightRuleMatches()
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    fun preserveCurrentPositionForRefresh() {
        pendingHighlightAnchor = currentPositionAnchor()
    }

    fun resourceImageSources(indexes: IntRange): Set<String> =
        listOfNotNull(prevTextChapter, curTextChapter, nextTextChapter)
            .filter { it.chapter.index in indexes }
            .flatMap { chapter -> chapter.pages.flatMap { it.lines }.flatMap { it.columns } }
            .filterIsInstance<ImageColumn>().map { it.src }.toSet()

    @Synchronized
    fun clearResourceChapters(indexes: IntRange) {
        if (durChapterIndex in indexes && curTextChapter != null) preserveCurrentPositionForRefresh()
        preDownloadTask?.cancel()
        listOfNotNull(prevTextChapter, curTextChapter, nextTextChapter)
            .filter { it.chapter.index in indexes }.forEach {
                it.cancelLayout()
                it.invalidateHighlightRuleMatches()
            }
        indexes.forEach { index ->
            chapterLoadingJobs.remove(index)?.cancel()
            loadingChapters.remove(index)
            downloadedChapters.remove(index)
            downloadFailChapters.remove(index)
        }
        if (prevTextChapter?.chapter?.index?.let { it in indexes } == true) prevTextChapter = null
        if (curTextChapter?.chapter?.index?.let { it in indexes } == true) curTextChapter = null
        if (nextTextChapter?.chapter?.index?.let { it in indexes } == true) nextTextChapter = null
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    private fun resetReadRecord(book: Book) {
        readRecord = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)
            ?: ReadRecord(deviceId = AppConst.androidId, bookName = book.name, author = book.author)
    }

    fun upReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        val (record, currentBook, elapsed) = synchronized(readRecordLock) {
            val currentBook = book?.copy() ?: return
            // Book details may fill in an author on the existing Book instance.
            if (readRecord.bookName != currentBook.name || readRecord.author != currentBook.author) {
                resetReadRecord(currentBook)
            }
            val now = System.currentTimeMillis()
            val elapsed = (now - readStartTime).coerceAtLeast(0)
            readRecord.readTime += elapsed
            readStartTime = now
            readRecord.lastRead = now
            readRecord.updateSnapshot(currentBook, durChapterIndex, durChapterPos)
            Triple(readRecord.copy(), currentBook, elapsed)
        }
        executor.execute {
            record.saveWithCover(currentBook, elapsed)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    private fun prepareReadAloudPageNavigation(syncReadAloudFollow: Boolean): Boolean {
        val restartReadAloud = ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
            isReadAloudRunning = BaseReadAloudService.isRun,
            speechDrivenNavigation = syncReadAloudFollow,
            followManualPageTurns = AppConfig.readAloudFollowManualPage,
            followingReadAloudPosition = ReadAloud.followReadAloudPosition
        )
        if (BaseReadAloudService.isRun && !syncReadAloudFollow && !restartReadAloud) {
            ReadAloud.detachReadAloudFollow()
        }
        return restartReadAloud
    }

    fun moveToNextPage(syncReadAloudFollow: Boolean = false): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(syncReadAloudFollow: Boolean = false): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex < simulatedChapterSize - 1) {
            val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter?.invalidateHighlightRuleMatches()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(
                syncReadAloudFollow = syncReadAloudFollow,
                restartReadAloudFromVisiblePage = restartReadAloud
            )
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (BaseReadAloudService.isRun && !syncReadAloudFollow) {
            ReadAloud.detachReadAloudFollow()
        }
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter?.invalidateHighlightRuleMatches()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(syncReadAloudFollow = syncReadAloudFollow)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true,
        syncReadAloudFollow: Boolean = false
    ): Boolean {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return false
        }
        if (durChapterIndex > 0) {
            val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter?.invalidateHighlightRuleMatches()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged(
                syncReadAloudFollow = syncReadAloudFollow,
                restartReadAloudFromVisiblePage = restartReadAloud
            )
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        if (BaseReadAloudService.isRun) {
            ReadAloud.detachReadAloudFollow()
        }
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
        saveRead(true)
    }

    fun setPageIndex(index: Int, syncReadAloudFollow: Boolean = false) {
        if (syncReadAloudFollow && !BaseReadAloudService.shouldSyncSpeechNavigation()) {
            return
        }
        val restartReadAloud = prepareReadAloudPageNavigation(syncReadAloudFollow)
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        saveRead(true)
        curPageChanged(
            pageChanged = true,
            syncReadAloudFollow = syncReadAloudFollow,
            restartReadAloudFromVisiblePage = restartReadAloud
        )
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    /**
     * 「朗读自身定位」豁免的章节索引; -1 表示无豁免。
     *
     * 用于「回到朗读位置」「看原文」这类**由朗读位置驱动**的章节定位: 打开前通常不处于
     * 跟随态, 但加载完成前后 `restoreAloudFollowOnVisiblePage()` 可能把跟随态恢复回来,
     * 于是 `curPageChanged()` 判定 `shouldSyncSpeechNavigation()` 为真而**再起一次朗读会话** ——
     * 新会话开头会 `cancel + playStop` 掉正在播放的那一路, 用户看到的就是
     * 「回到朗读位置时朗读被中断, 并从阅读页重读」。
     *
     * 该标记在 `openChapter(speechInitiated = true)` 时置为要打开的章节索引,
     * 由 `curPageChanged()` 在该章节加载完成时一次性消费, 保证这次加载只负责把阅读页
     * 挪到朗读位置, 不产生任何新的朗读会话。用章节索引而非布尔值, 是为了避免被
     * 加载期间的其他 `curPageChanged()` 调用抢先消费掉。
     */
    private var speechSelfPositioningChapter = -1

    /**
     * 打开章节。
     *
     * @param speechInitiated 本次打开是否由朗读位置驱动(回到朗读位置/看原文)。
     *   为 true 时: 保留朗读跟随态、备份并保持阅读进度、且加载完成不重启朗读会话。
     */
    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
        pdfPageIndex: Int? = null,
        speechInitiated: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        if (speechInitiated) {
            // 朗读自身定位: 保留跟随态(不 detach), 并标记本次加载不得重启朗读会话。
            // 章节不存在时立即清标记, 避免残留到后续无关加载上。
            speechSelfPositioningChapter = if (index < chapterSize) index else -1
            // 备份/跟随只对「已有会话」有意义: 无会话时可没有进度需要还原,
            // 也不存在跟随态可切。此处若强行备份, 反而会被 saveRead() 的
            // 「无会话即还原」兜底逻辑立即消费掉, 干扰随后的起读快照。
            if (BaseReadAloudService.isRun) {
                backupReadingPositionForAloud(force = true)
                // 立刻恢复到跟随态: 从「改写 durChapterIndex/Pos」到「加载完成」的整个窗口内,
                // saveRead() 都会被跟随门控拦住, 朗读位置不可能趁机落库成阅读进度。
                ReadAloud.restoreReadAloudFollow()
            }
        } else {
            // 普通定位(用户翻目录/跳转): 本次加载不享受豁免。
            speechSelfPositioningChapter = -1
            if (BaseReadAloudService.isRun) {
                ReadAloud.detachReadAloudFollow()
            }
        }
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
            pendingHighlightJump = highlightLayoutTitleLength?.let { sourceTitleLength ->
                book?.let {
                    PendingHighlightJump(
                        it.bookUrl,
                        index,
                        durChapterPos,
                        sourceTitleLength
                    )
                }
            }
            pendingHighlightAnchor = highlightAnchorText?.takeIf(String::isNotEmpty)?.let {
                book?.let { currentBook ->
                    PendingHighlightAnchor(
                        currentBook.bookUrl,
                        index,
                        durChapterPos,
                        highlightLayoutTitleLength ?: -1,
                        it
                    )
                }
            }
            pendingPdfJump = pdfPageIndex?.takeIf { it >= 0 && it / PdfFile.PAGE_SIZE == index }?.let { page ->
                book?.takeIf { it.isPdf }?.let { PendingPdfJump(it.bookUrl, index, page) }
            }
            // 朗读自身定位: 这里改的是「可见位置」, 阅读进度已在进入前备份,
            // 不能落库, 否则用户的阅读进度会被朗读位置顶掉。
            if (pendingHighlightJump == null && !speechInitiated) {
                saveRead()
            }
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 「看原文」: 把阅读页定位到朗读位置, 但**不修改阅读进度**。
     * 用于从通知/书架迷你条跳回朗读所在位置查看原文。
     */
    fun openAloudPosition(chapterIndex: Int, chapterPos: Int, success: (() -> Unit)? = null) {
        if (chapterIndex !in 0..<simulatedChapterSize) return
        val sameChapter = curTextChapter?.chapter?.index == chapterIndex
        if (sameChapter) {
            // 同章: 只挪动可见位置并挂上朗读高亮, 不落库。
            // 先把阅读位置备份并进入跟随态, 使 saveRead() 在此窗口内被门控拦住,
            // 保证 durChapterPos 的这次临时改写不会落库成阅读进度。
            backupReadingPositionForAloud(force = true)
            ReadAloud.restoreReadAloudFollow()
            durChapterPos = chapterPos
            curTextChapter?.let {
                val pageIndex = it.getPageIndexByCharIndex(chapterPos)
                val aloudSpanStart = chapterPos - it.getReadLength(pageIndex)
                it.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
            }
            callBack?.upContent()
            success?.invoke()
            return
        }
        // 跨章: 走 openChapter 定位, 但标记为「朗读自身定位」——
        // 该路径会备份阅读位置且不落库、不重启朗读会话, 因此无需在这里手工存取读数。
        openChapter(chapterIndex, chapterPos, speechInitiated = true, success = {
            ReadAloud.restoreReadAloudFollow()
            upTextChapterAloudSpan(chapterPos)
            success?.invoke()
        })
    }

    /**
     * 在已加载章节上绘制朗读高亮(不落库)。
     */
    private fun upTextChapterAloudSpan(chapterStart: Int) {
        if (chapterStart < 0) return
        val textChapter = curTextChapter ?: return
        val pageIndex = textChapter.getPageIndexByCharIndex(chapterStart)
        if (pageIndex < 0) return
        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
        textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
        callBack?.upContent()
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(
        pageChanged: Boolean = false,
        syncReadAloudFollow: Boolean = false,
        restartReadAloudFromVisiblePage: Boolean = false,
        updateReadAloud: Boolean = true
    ) {
        callBack?.pageChanged()
        // 朗读自身定位(回到朗读位置/看原文)的豁免: 这次加载只负责把阅读页挪到朗读位置,
        // 绝不能重启朗读会话 —— 否则正在播放的那一路会被新会话的 cancel + playStop 掐断,
        // 表现为「回到朗读位置时朗读被中断并从页首重读」。
        // 用章节索引匹配后一次性清除, 避免被加载期间的无关 curPageChanged() 提前消费。
        val speechPositioning = speechSelfPositioningChapter >= 0 &&
                speechSelfPositioningChapter == curTextChapter?.chapter?.index
        if (speechPositioning) {
            speechSelfPositioningChapter = -1
        }
        curTextChapter?.let {
            if (!speechPositioning && updateReadAloud && BaseReadAloudService.isRun && it.isCompleted) {
                if (!syncReadAloudFollow) {
                    if (!restartReadAloudFromVisiblePage) {
                        ReadAloud.detachReadAloudFollow()
                        return@let
                    }
                }
                if (restartReadAloudFromVisiblePage) {
                    // 手动翻页策略下的随页重启: 起点就是当前可见页, 是用户主动行为,
                    // 允许切书(用户点朗读时可能已换了书)。
                    readAloud(!BaseReadAloudService.pause, allowBookSwitch = true)
                } else {
                    val scrollPageAnim = pageAnim() == 3
                    if (scrollPageAnim && pageChanged) {
                        ReadAloud.pause(appCtx)
                    } else {
                        // 隐式重启(loadContent 完成/翻页触发): 不得把朗读内容换成
                        // 当前这本书 —— 换书后台续播时必须继续读原书。
                        readAloud(!BaseReadAloudService.pause, allowBookSwitch = false)
                    }
                }
            }
        }
        upReadTime()
        preDownload()
    }

    /**
     * 朗读
     *
     * 起点一律在调用时刻快照(书 + 章 + 章内字符位)并随会话传递, 服务端以它为权威起点,
     * 不再事后从 `durChapterPos` 反查 —— 该值会被进度跟随改写, 不能当权威起点用。
     *
     * @param allowBookSwitch 是否允许把正在朗读的会话切到当前书。
     *   显式点击(朗读按钮/从此处朗读)为 true; 隐式的随页重启为 false,
     *   避免换书后阅读页加载完成时把朗读内容换成新书。
     * @param anchorChapterPos 只给本次朗读用的章内起点。传入时以它为准, 但**不写回**
     *   `durChapterPos` —— 「从此处朗读」的起点属于朗读进度, 不应改动阅读进度。
     */
    fun readAloud(
        play: Boolean = true,
        startPos: Int = 0,
        rewindToSentenceStart: Boolean = false,
        allowBookSwitch: Boolean = true,
        anchorChapterPos: Int? = null
    ) {
        val book = book ?: return
        val textChapter = curTextChapter ?: return
        if (!textChapter.isCompleted) return
        // 隐式重启(随页/加载完成触发)在会话准备窗口内必须丢弃:
        // 否则会用尚未更新的 durChapterPos 覆盖掉刚确定的起点,
        // 表现为「从此处朗读」1~2 秒后跳回原先进度。
        // 显式点击不受限, 用户可以随时改主意。
        if (!allowBookSwitch && BaseReadAloudService.isSessionPreparing()) return
        ReadAloud.play(
            appCtx,
            play,
            pageIndex = durPageIndex,
            startPos = startPos,
            rewindToSentenceStart = rewindToSentenceStart,
            bookUrl = book.bookUrl,
            chapterIndex = durChapterIndex,
            chapterPos = anchorChapterPos ?: durChapterPos,
            allowBookSwitch = allowBookSwitch
        )
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    /**
     * 是否排版到了当前阅读位置
     */
    val isLayoutAvailable inline get() = durPageIndex >= 0

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        loadContent(
            durChapterIndex,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
            success = { success?.invoke() },
        )
        loadContent(
            durChapterIndex + 1,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
        )
        loadContent(
            durChapterIndex - 1,
            resetPageOffset = resetPageOffset,
            readPositionVersion = readPositionVersion,
        )
    }

    fun loadOrUpContent(success: (() -> Unit)? = null) {
        if (curTextChapter == null) {
            loadContent(durChapterIndex) {
                success?.invoke()
            }
        } else {
            callBack?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    suspend fun buildReplacePreview(sourcePosition: Int): ReplacePreview? {
        val currentBook = book ?: return null
        val sourceChapter = curTextChapter?.takeIf {
            it.isCompleted &&
                it.chapter.index == durChapterIndex &&
                it.chapter.bookUrl == currentBook.bookUrl
        } ?: return null
        val sourceProgressPosition = durChapterPos
        val chapterIndex = durChapterIndex
        return withContext(IO) {
            val chapter = sourceChapter.chapter
            val rawContent = BookHelp.getContent(currentBook, chapter)
                ?: return@withContext null
            val processor = ContentProcessor.get(currentBook)
            val manualRules = manualReplaceRules(currentBook)
            val replaceEnabled = if (manualRules != null) {
                false
            } else {
                !currentBook.getUseReplaceRule()
            }
            val titleRules = manualRules?.let { emptyList<ReplaceRule>() }
                ?: processor.getTitleReplaceRules()
            val displayTitle = chapter.getDisplayTitle(
                titleRules,
                useReplace = replaceEnabled,
                replaceBook = currentBook.toReplaceBook(),
            )
            val contents = processor.getContent(
                currentBook,
                chapter,
                rawContent,
                includeTitle = false,
                replaceEnabledOverride = replaceEnabled,
                titleReplaceRulesOverride = manualRules?.let { emptyList<ReplaceRule>() },
                contentReplaceRulesOverride = manualRules?.let { emptyList<ReplaceRule>() },
            )
            val previewChapter = ChapterProvider.getTextChapterAsync(
                this,
                currentBook,
                chapter,
                displayTitle,
                contents,
                simulatedChapterSize,
                saveChapterData = false,
            )
            try {
                previewChapter.layoutChannel.receiveAsFlow().collect()
                ensureActive()
                val sourceText = chapterText(sourceChapter)
                val previewText = chapterText(previewChapter)
                ReplacePreview(
                    sourceChapter = sourceChapter,
                    previewChapter = previewChapter,
                    sourcePosition = sourcePosition,
                    sourceProgressPosition = sourceProgressPosition,
                    chapterPosition = resolveReplacePreviewPosition(
                        sourceText = sourceText,
                        sourceTitleLength = sourceChapter.layoutTitleLength,
                        sourcePosition = sourcePosition,
                        previewText = previewText,
                        previewTitleLength = previewChapter.layoutTitleLength,
                    ),
                    bookUrl = currentBook.bookUrl,
                    chapterIndex = chapterIndex,
                )
            } catch (e: CancellationException) {
                previewChapter.cancelLayout()
                throw e
            }
        }
    }

    fun isCurrentReplacePreview(preview: ReplacePreview): Boolean {
        return book?.bookUrl == preview.bookUrl &&
            durChapterIndex == preview.chapterIndex &&
            durChapterPos == preview.sourceProgressPosition &&
            curTextChapter === preview.sourceChapter
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        val requestBook = book ?: return
        Coroutine.async {
            val book = requestBook
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            val contentToken = BookHelp.contentSaveToken(book, chapter)
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        readPositionVersion = readPositionVersion,
                        contentToken = contentToken,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset,
                    readPositionVersion = readPositionVersion,
                    success = success,
                )
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) = withContext(IO) {
        val book = book ?: return@withContext
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@withContext
        val contentToken = BookHelp.contentSaveToken(book, chapter)
        if (addLoading(index)) {
            try {
                val content = BookHelp.getContent(book, chapter) ?: downloadAwait(chapter)
                contentLoadFinishAwait(
                    book,
                    chapter,
                    content,
                    upContent,
                    resetPageOffset,
                    readPositionVersion,
                    contentToken,
                )
                if (BookHelp.isContentSaveCurrent(contentToken)) success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.localizedMessage}")
            } finally {
                synchronized(this@ReadBook) {
                    if (ReadBook.book?.bookUrl == book.bookUrl && BookHelp.isContentSaveCurrent(contentToken)) {
                        removeLoading(index)
                    }
                }
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        readPositionVersion: Long? = null,
        success: (() -> Unit)? = null,
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(
                scope,
                chapter,
                semaphore,
                resetPageOffset = resetPageOffset,
                readPositionVersion = readPositionVersion,
                success = success,
            )
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                resetPageOffset = resetPageOffset,
                readPositionVersion = readPositionVersion,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = book!!
        val bookSource = bookSource
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        readPositionVersion: Long? = null,
        contentToken: ContentSaveToken = BookHelp.contentSaveToken(book, chapter),
        success: (() -> Unit)? = null,
    ) {
        if (this.book?.bookUrl != book.bookUrl || !BookHelp.isContentSaveCurrent(contentToken)) return
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        // Restoring visual follow during layout must not create a new speech session.
        val updateReadAloud = BaseReadAloudService.shouldSyncSpeechNavigation()
        val shouldResetPageOffset = resetPageOffset &&
            shouldApplyReadPositionReset(readPositionVersion)
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            ensureContentCurrent(book, contentToken)
            val (displayTitle, contents) = processChapterContent(book, chapter, content)
            ensureActive()
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize,
                hasBodyContent = contents.textList.isNotEmpty() &&
                        !content.isContentLoadFailurePlaceholder(),
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        ensureActive()
                        curTextChapter?.invalidateHighlightRuleMatches()
                        curTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        ensureContentCurrent(book, contentToken)
                        val index = page.index
                        val positionReady = resolvePendingPdfJump(book, textChapter, page) &&
                            resolvePendingHighlightJump(book, textChapter)
                        if (positionReady && !available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(
                                    offset,
                                    shouldResetPageOffset,
                                    readPositionVersion = readPositionVersion,
                                )
                            }
                            available = true
                        }
                        if (positionReady && upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    ensureContentCurrent(book, contentToken)
                    finishPendingPdfJump(book, textChapter)
                    val restoredAnchor = resolvePendingHighlightAnchor(book, textChapter)
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            restoredAnchor || (!available && shouldResetPageOffset),
                            readPositionVersion = readPositionVersion,
                        )
                    }
                    curPageChanged(
                        syncReadAloudFollow = BaseReadAloudService.shouldSyncSpeechNavigation(),
                        updateReadAloud = updateReadAloud
                    )
                    callBack?.contentLoadFinish()
                }

                -1 -> prevChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        ensureActive()
                        prevTextChapter?.invalidateHighlightRuleMatches()
                        prevTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect { ensureContentCurrent(book, contentToken) }
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                }

                1 -> nextChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        ensureActive()
                        nextTextChapter?.invalidateHighlightRuleMatches()
                        nextTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    for (page in textChapter.layoutChannel) {
                        ensureContentCurrent(book, contentToken)
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) {
                            callBack?.upContent(
                                offset,
                                shouldResetPageOffset,
                                readPositionVersion = readPositionVersion,
                            )
                        }
                    }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            if (BookHelp.isContentSaveCurrent(contentToken)) success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        readPositionVersion: Long? = null,
        contentToken: ContentSaveToken = BookHelp.contentSaveToken(book, chapter),
    ) {
        synchronized(this) {
            if (this.book?.bookUrl != book.bookUrl || !BookHelp.isContentSaveCurrent(contentToken)) return
            removeLoading(chapter.index)
            if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) return
        }
        // Restoring visual follow during layout must not create a new speech session.
        val updateReadAloud = BaseReadAloudService.shouldSyncSpeechNavigation()
        val shouldResetPageOffset = resetPageOffset &&
            shouldApplyReadPositionReset(readPositionVersion)
        kotlin.runCatching {
            val (displayTitle, contents) = processChapterContent(book, chapter, content)
            val textChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize,
                hasBodyContent = contents.textList.isNotEmpty() &&
                        !content.isContentLoadFailurePlaceholder(),
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        curTextChapter?.cancelLayout()
                        curTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        ensureContentCurrent(book, contentToken)
                        val index = page.index
                        val positionReady = resolvePendingPdfJump(book, textChapter, page) &&
                            resolvePendingHighlightJump(book, textChapter)
                        if (positionReady && !available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(
                                    offset,
                                    shouldResetPageOffset,
                                    readPositionVersion = readPositionVersion,
                                )
                            }
                            available = true
                        }
                        if (positionReady && upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    ensureContentCurrent(book, contentToken)
                    finishPendingPdfJump(book, textChapter)
                    val restoredAnchor = resolvePendingHighlightAnchor(book, textChapter)
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            restoredAnchor || (!available && shouldResetPageOffset),
                            readPositionVersion = readPositionVersion,
                        )
                    }
                    curPageChanged(
                        syncReadAloudFollow = BaseReadAloudService.shouldSyncSpeechNavigation(),
                        updateReadAloud = updateReadAloud
                    )
                    callBack?.contentLoadFinish()
                }

                -1 -> {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        prevTextChapter?.cancelLayout()
                        prevTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect { ensureContentCurrent(book, contentToken) }
                    if (upContent) {
                        callBack?.upContent(
                            offset,
                            shouldResetPageOffset,
                            readPositionVersion = readPositionVersion,
                        )
                    }
                }

                1 -> {
                    withContext(Main) {
                        ensureContentCurrent(book, contentToken)
                        nextTextChapter?.cancelLayout()
                        nextTextChapter = textChapter
                        observeHighlightRuleLayout(textChapter)
                    }
                    for (page in textChapter.layoutChannel) {
                        ensureContentCurrent(book, contentToken)
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) {
                            callBack?.upContent(
                                offset,
                                shouldResetPageOffset,
                                readPositionVersion = readPositionVersion,
                            )
                        }
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    private fun ensureContentCurrent(book: Book, token: ContentSaveToken) {
        if (this.book?.bookUrl != book.bookUrl || !BookHelp.isContentSaveCurrent(token)) {
            throw CancellationException("Chapter resources were refreshed")
        }
    }

    /**
     * 预下载时，章节已完，更新目录
     */
    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    book.update()
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
        saveRead()
    }

    private var lastAloudProgressSavedAt = 0L

    /** 听书会话开始时备份的阅读位置(bookUrl, 章索引, 章内偏移), 会话结束时原样还原。 */
    private var aloudReadingBackup: Triple<String, Int, Int>? = null

    /**
     * 进入听书态前调用: 备份当前阅读位置。
     * 只在尚无备份(本会话首次)时记录, 避免朗读跟随期间被已污染的 durChapterPos 覆盖。
     *
     * @param force 忽略已有备份, 强制以当前值重建。
     *   「回到朗读位置」这类朗读自身定位会在打开章节**之前**调用一次, 此刻
     *   `durChapterIndex/durChapterPos` 仍确切是用户的阅读位置 —— 用它覆盖可能已过期
     *   或已被清空的历史备份, 会话结束时才能正确还原阅读进度。
     */
    fun backupReadingPositionForAloud(force: Boolean = false) {
        val bookUrl = book?.bookUrl ?: return
        // 跟随朗读期间内存位置会被朗读游标镜像改写(重开阅读页时更是直接被对齐到朗读处),
        // 此时当前值不再代表阅读进度。已存在的同书备份(会话开始时的真实阅读位置、
        // 或重开时从 DB 行重建的阅读进度)比当前值可信, 即便是 force 请求也不能覆盖它,
        // 否则会话结束时会用朗读位置还原阅读进度。
        // 反向也成立: 用户手动导航会先 detach 并清空备份, 那时备份为 null,
        // force 能正常按当前值重建 —— 「回到朗读位置」「从此处朗读」的语义不受影响。
        if (aloudReadingBackup?.first == bookUrl &&
            BaseReadAloudService.isRun && ReadAloud.followReadAloudPosition
        ) return
        if (!force && aloudReadingBackup != null) return
        aloudReadingBackup = Triple(bookUrl, durChapterIndex, durChapterPos)
    }

    /**
     * 用户手动导航时调用: 阅读位置重新归用户所有, 此后不再做会话结束还原,
     * 否则用户手动翻到的位置会被还原逻辑连同后续落库一起抹掉。
     */
    fun clearAloudReadingBackup() {
        aloudReadingBackup = null
    }

    /**
     * 听书会话结束时调用: 把内存中的阅读位置还原成听书前的值。
     * 朗读跟随期间会临时改写 durChapterPos/durChapterIndex(用于页面高亮跟随), 若不还原,
     * 后续任意一次 saveRead() 都会把听书位置误存成阅读进度。
     */
    fun restoreReadingPositionAfterAloud() {
        val backup = aloudReadingBackup ?: return
        aloudReadingBackup = null
        val (bookUrl, chapterIndex, chapterPos) = backup
        // 用户已切到别的书: 不还原, 避免污染当前书
        val currentBookUrl = book?.bookUrl ?: return
        if (currentBookUrl != bookUrl) return
        if (curTextChapter?.chapter?.index == chapterIndex) {
            // 同章: 只还原章内偏移, 不打断当前排版
            durChapterIndex = chapterIndex
            durChapterPos = chapterPos
            callBack?.upContent(resetPageOffset = false)
        } else if (callBack != null) {
            // 章节也变了且阅读页还在: 定位回阅读所在章节(此时朗读已结束, 落库即阅读进度)
            openChapter(chapterIndex, chapterPos, upContent = true)
        } else {
            durChapterIndex = chapterIndex
            durChapterPos = chapterPos
        }
    }

    /**
     * 用户手动导航(脱离朗读跟随)时调用。
     */
    fun onAloudFollowDetached() {
        clearAloudReadingBackup()
    }

    fun saveRead(pageChanged: Boolean = false) {
        // 朗读驱动的推进(跟随朗读中由服务发起的翻页/换章)不落库阅读进度,
        // 阅读进度保持用户上次手动阅读的位置; 听书进度由朗读服务写入
        // book.config.aloud*, 两条进度完全分离。
        //
        // 判据用「是否正在跟随朗读」而非单纯的「服务是否运行」: 用户手动翻页时会
        // detach 脱离跟随, 此时即便朗读仍在播放, 阅读进度也应照常保存。
        if (BaseReadAloudService.isRun && ReadAloud.followReadAloudPosition) return
        // 兜底: 服务被系统回收等未走 onDestroy 的情况, 补一次阅读位置还原
        if (!BaseReadAloudService.isRun) restoreReadingPositionAfterAloud()
        if (pendingPdfJump?.let { it.bookUrl == book?.bookUrl && it.chapterIndex == durChapterIndex } == true) return
        if (hasPendingHighlightJump()) return
        val book = book ?: return
        // The shared writer may still be queued when the reader switches books or pages.
        val durChapterIndex = durChapterIndex
        val durChapterPos = durChapterPos
        val bookSource = bookSource
        val durTime = System.currentTimeMillis()
        executor.execute {
            kotlin.runCatching {
                book.lastCheckCount = 0
                book.durChapterTime = durTime
                val chapterChanged = book.durChapterIndex != durChapterIndex
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                        book.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                            book.getUseReplaceRule(),
                            replaceBook = book.toReplaceBook()
                        )
                        SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it, durTime.toString())
                    }
                }
                book.update()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 保存听书(朗读)进度到 book.config.aloud*，与阅读进度完全分离。
     * 节流（默认 10 秒），换章时通过 force=true 立即落库。
     * @param overrideBook 朗读服务自持的书籍快照；阅读页销毁后仍可正确落库。
     * @param overrideChapterIndex/overrideChapterPos 朗读游标(优先于阅读游标)。
     */
    fun saveAloudProgress(
        force: Boolean = false,
        overrideBook: Book? = null,
        overrideChapterIndex: Int? = null,
        overrideChapterPos: Int? = null,
        overrideChapterTitle: String? = null
    ) {
        val targetBook = overrideBook ?: book ?: return
        val chapterIndex = overrideChapterIndex ?: durChapterIndex
        val chapterPos = overrideChapterPos ?: durChapterPos
        if (chapterIndex < 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAloudProgressSavedAt < ALOUD_PROGRESS_SAVE_INTERVAL) return
        lastAloudProgressSavedAt = now
        val chapterTitle = overrideChapterTitle
            ?: curTextChapter?.chapter?.title
            ?: targetBook.durChapterTitle
        executor.execute {
            kotlin.runCatching {
                val config = targetBook.config
                config.aloudChapterIndex = chapterIndex
                config.aloudChapterPos = chapterPos
                config.aloudChapterTitle = chapterTitle
                config.aloudUpdatedAt = now
                appDb.bookDao.updateReadConfigJson(targetBook.bookUrl, GSON.toJson(config))
            }.onFailure {
                AppLog.put("保存听书进度出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //书源支持批量正文时先整批预取,没取到的章节走下面的单章流程兜底
                bookSource?.takeIf { it.supportContentBatch() }?.let { source ->
                    preDownloadBatch(source)
                }
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    /**
     * 批量预下载。
     * 按书源声明的最大批量数量分批,书源没回存的章节留给单章流程兜底。
     */
    private suspend fun preDownloadBatch(bookSource: BookSource) {
        val book = book ?: return
        val batchSize = bookSource.contentBatchSize()
        if (batchSize <= 1) return
        val maxChapterIndex = min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
        val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
        val indexes = (durChapterIndex.plus(2)..maxChapterIndex) +
            (durChapterIndex.minus(2) downTo minChapterIndex)
        val pending = indexes.mapNotNull { index ->
            if (index < 0 || index > chapterSize - 1) return@mapNotNull null
            if (downloadedChapters.contains(index)) return@mapNotNull null
            if ((downloadFailChapters[index] ?: 0) >= 3) return@mapNotNull null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
                ?: return@mapNotNull null
            if (chapter.isVolume || BookHelp.hasContent(book, chapter)) {
                downloadedChapters.add(index)
                return@mapNotNull null
            }
            chapter
        }
        if (pending.size < 2) return
        val cacheBook = CacheBook.getOrCreate(bookSource, book)
        pending.chunked(batchSize).forEach { batch ->
            if (batch.size < 2) return@forEach
            currentCoroutineContext().ensureActive()
            cacheBook.downloadBatchAwait(batch)
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            val positionAnchor = if (callBack == null) currentPositionAnchor() else null
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null) {
                clearTextChapter()
                pendingHighlightAnchor = positionAnchor?.takeIf {
                    it.bookUrl == newBook.bookUrl && it.chapterIndex == durChapterIndex
                }
            } else if (loadContent) {
                loadContent(
                    resetPageOffset = true,
                    readPositionVersion = callBack?.readPositionVersion(),
                )
            }
        }
    }

    private fun shouldApplyReadPositionReset(readPositionVersion: Long?): Boolean {
        return readPositionVersion == null ||
            callBack?.isReadPositionVersionCurrent(readPositionVersion) != false
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    private fun resolvePendingPdfJump(layoutBook: Book, textChapter: TextChapter, page: TextPage): Boolean {
        val pending = pendingPdfJump ?: return true
        if (curTextChapter !== textChapter) return false
        if (pending.bookUrl != layoutBook.bookUrl || pending.chapterIndex != textChapter.chapter.index ||
            pending.chapterIndex != durChapterIndex) {
            pendingPdfJump = null
            return true
        }
        val position = findPdfPagePosition(page, pending.pageIndex) ?: return false
        durChapterPos = position
        pendingPdfJump = null
        saveRead()
        return true
    }

    private fun finishPendingPdfJump(layoutBook: Book, textChapter: TextChapter) {
        val pending = pendingPdfJump ?: return
        if (curTextChapter === textChapter && pending.bookUrl == layoutBook.bookUrl &&
            pending.chapterIndex == textChapter.chapter.index) {
            pendingPdfJump = null
            AppLog.put("PDF 目录目标页未能完成排版：${pending.pageIndex + 1}")
        }
    }

    private fun resolvePendingHighlightJump(
        layoutBook: Book,
        textChapter: TextChapter
    ): Boolean {
        if (curTextChapter !== textChapter) return false
        val pending = pendingHighlightJump
            ?: return pendingHighlightAnchor?.waitForLayout != true
        if (pending.bookUrl != layoutBook.bookUrl ||
            pending.chapterIndex != durChapterIndex ||
            pending.chapterIndex != textChapter.chapter.index ||
            pending.rawPosition != durChapterPos
        ) {
            pendingHighlightJump = null
            return true
        }
        val currentTitleLength = textChapter.layoutTitleLength
        if (currentTitleLength < 0) return false
        durChapterPos = resolveHighlightChapterPosition(
            pending.rawPosition,
            pending.sourceTitleLength,
            currentTitleLength
        )
        pendingHighlightJump = null
        saveRead()
        return pendingHighlightAnchor?.waitForLayout != true
    }

    private fun hasPendingHighlightJump(): Boolean {
        val pending = pendingHighlightJump ?: return false
        if (pending.bookUrl == book?.bookUrl &&
            pending.chapterIndex == durChapterIndex &&
            pending.rawPosition == durChapterPos
        ) {
            return true
        }
        pendingHighlightJump = null
        return false
    }

    private fun resolvePendingHighlightAnchor(
        layoutBook: Book,
        textChapter: TextChapter
    ): Boolean {
        val pending = pendingHighlightAnchor ?: return false
        if (curTextChapter !== textChapter) return false
        if (pending.bookUrl != layoutBook.bookUrl ||
            pending.chapterIndex != durChapterIndex ||
            pending.chapterIndex != textChapter.chapter.index
        ) {
            pendingHighlightAnchor = null
            return false
        }
        val currentTitleLength = textChapter.layoutTitleLength.takeIf { it >= 0 } ?: return false
        val expectedPosition = resolveHighlightChapterPosition(
            pending.rawPosition,
            pending.sourceTitleLength,
            currentTitleLength
        )
        pendingHighlightAnchor = null
        val bodyText = chapterText(textChapter).drop(currentTitleLength)
        if (durChapterPos == pending.rawPosition) {
            val layoutPosition = pending.layoutBodyText?.let {
                resolveLayoutBodyPosition(
                    it, pending.rawPosition - pending.sourceTitleLength, bodyText
                )
            }
            if (layoutPosition != null) {
                durChapterPos = if (pending.rawPosition < pending.sourceTitleLength) {
                    pending.rawPosition.coerceIn(0, currentTitleLength)
                } else {
                    currentTitleLength + layoutPosition
                }
                saveRead()
                return true
            }
        }
        if (durChapterPos != expectedPosition) return false
        val bodyPosition = (expectedPosition - currentTitleLength).coerceAtLeast(0)
        durChapterPos = currentTitleLength +
            HighlightAnchor.jumpPos(bodyText, bodyPosition, pending.bookText)
        saveRead()
        return true
    }

    private fun currentPositionAnchor(): PendingHighlightAnchor? {
        val currentBook = book ?: return null
        val textChapter = curTextChapter?.takeIf {
            it.isCompleted &&
                it.chapter.index == durChapterIndex &&
                it.chapter.bookUrl == currentBook.bookUrl
        } ?: return null
        val titleLength = textChapter.layoutTitleLength.takeIf { it >= 0 } ?: return null
        val bodyText = chapterText(textChapter).drop(titleLength)
        val bodyPosition = (durChapterPos - titleLength).coerceAtLeast(0)
        val anchorText = bodyText.drop(bodyPosition).take(REFRESH_POSITION_ANCHOR_LENGTH)
        if (anchorText.isEmpty()) return null
        return PendingHighlightAnchor(
            currentBook.bookUrl,
            durChapterIndex,
            durChapterPos,
            titleLength,
            anchorText,
            waitForLayout = true,
            layoutBodyText = bodyText,
        )
    }

    private data class PendingHighlightJump(
        val bookUrl: String,
        val chapterIndex: Int,
        val rawPosition: Int,
        val sourceTitleLength: Int
    )

    private data class PendingHighlightAnchor(
        val bookUrl: String,
        val chapterIndex: Int,
        val rawPosition: Int,
        val sourceTitleLength: Int,
        val bookText: String,
        val waitForLayout: Boolean = false,
        val layoutBodyText: String? = null,
    )

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        invalidateHighlightRuleMatches()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            readPositionVersion: Long? = null,
            success: (() -> Unit)? = null
        )

        fun readPositionVersion(): Long? = null

        fun isReadPositionVersionCurrent(version: Long): Boolean = true

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            readPositionVersion: Long? = null,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}

internal fun String.isContentLoadFailurePlaceholder(): Boolean =
    startsWith("获取正文失败\n") || startsWith("加载正文失败\n")

internal fun BookHighlight.isForBook(book: Book?): Boolean {
    return book != null && bookUrl == book.bookUrl
}

internal fun BookHighlight.isForChapter(book: Book?, chapter: BookChapter): Boolean {
    return isForBook(book) && book?.bookUrl == chapter.bookUrl && chapterUrl == chapter.url
}

internal fun BookHighlight.bindLegacyChapter(
    book: Book?,
    chapter: BookChapter,
    displayTitle: String = chapter.title
): Boolean {
    if (!isForBook(book) || chapterUrl.isNotBlank()) return false
    if (book?.bookUrl != chapter.bookUrl) return false
    if (chapterIndex != chapter.index) return false
    if (chapterName != chapter.title && chapterName != displayTitle) return false
    chapterUrl = chapter.url
    return true
}

internal fun TextChapter.isForBook(book: Book?): Boolean {
    return book != null && chapter.bookUrl == book.bookUrl
}
