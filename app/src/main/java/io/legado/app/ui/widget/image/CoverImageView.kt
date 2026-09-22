package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.CoverFontSizes
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import android.view.ViewOutlineProvider
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HORIZONTAL_TITLE_MAX_LINES = 4

/** 无封面书籍的封面底色(自上而下渐变), 上端 */
private const val NAME_COVER_BACKGROUND_TOP_COLOR = "#FEF7E5"

/** 无封面书籍的封面底色(自上而下渐变), 下端 */
private const val NAME_COVER_BACKGROUND_BOTTOM_COLOR = "#FDE6E0"

/** 无封面书籍的封面文字色(纯色), 不再做描边 */
private const val NAME_COVER_TEXT_COLOR = "#95735E"

private val nameCoverTextColor: Int by lazy { Color.parseColor(NAME_COVER_TEXT_COLOR) }

private val nameCoverGradientColors: IntArray by lazy {
    intArrayOf(
        Color.parseColor(NAME_COVER_BACKGROUND_TOP_COLOR),
        Color.parseColor(NAME_COVER_BACKGROUND_BOTTOM_COLOR),
    )
}

/** 纯色封面绘制时让底色铺满整个封面(文字不越出), 避免露出底下被盖掉的占位图 */
private const val NAME_COVER_EDGE_EPSILON = 2f

/** 无封面书籍的兜底 drawable: 用于「关闭显示书名」时仍保持同款渐变封面而非空白 */
private val nameCoverColorDrawable: GradientDrawable by lazy {
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, nameCoverGradientColors)
}

/**
 * 纵向封面的书名基准字号比例(相对封面宽度)。
 * 上游原值为 1/7 ≈ 0.1429, 现在**整体放大一号**(1.2 倍 ≈ 1/5.833);
 * 配合「不再因标题过长而缩字号」改动, 保证长标题也是大字。
 */
private const val NAME_COVER_TITLE_TEXT_SIZE_RATIO = 0.1714f

/** 书名版面的起点(相对封面宽/高), 尽量靠左上以换取更多折列空间 */
private const val NAME_COVER_TITLE_START_X_RATIO = 0.12f
private const val NAME_COVER_TITLE_START_Y_RATIO = 0.12f

/**
 * 书名最大占用的版面高度比例。超出则**自动折成更多纵向列**而不是缩小字号;
 * 列横排都放不下时, 末尾字改用省略号「…」表示(见 foldVerticalName)。
 */
private const val NAME_COVER_TITLE_AREA_HEIGHT_RATIO = 0.78f

/** 相邻两列的水平间距(相对字高)。越小则同样宽度能塞下越多列 */
private const val NAME_COVER_COLUMN_GAP_RATIO = 0.12f

/** 放不下时末尾使用的省略号 */
private const val NAME_COVER_ELLIPSIS = "…"

/** 无封面封面的外框线宽(dp), 取「浅浅的」观感 */
private const val NAME_COVER_BORDER_WIDTH_DP = 1

/** 外框圆角(px), 与 onSizeChanged 里 outline 的 12f 保持一致 */
private const val NAME_COVER_BORDER_CORNER_RADIUS = 12f

internal fun normalizeCoverText(value: String?, keepPunctuation: Boolean): String? =
    value?.let { text ->
        if (keepPunctuation) text.trim() else text.replace(AppPattern.bdRegex, "").trim()
    }

internal fun coverBitmapCacheKey(
    name: String,
    author: String?,
    width: Int,
    height: Int,
    horizontal: Boolean,
    drawAuthor: Boolean,
    backgroundColor: Int,
    accentColor: Int,
    adaptiveTitle: Boolean = true,
    fontSizes: CoverFontSizes? = null,
    fontCacheKey: String = "",
): String = buildString {
    append(name.length).append(':').append(name)
    append('|')
    if (author == null) {
        append("-1:")
    } else {
        append(author.length).append(':').append(author)
    }
    append('|').append(width).append('x').append(height)
    append('|').append(if (horizontal) 'h' else 'v')
    append('|').append(if (drawAuthor) 'a' else 'n')
    append('|').append(if (adaptiveTitle) 's' else 'f')
    append('|').append(backgroundColor).append(',').append(accentColor)
    if (fontSizes != null) append('|').append(fontSizes)
    append('|').append(fontCacheKey.length).append(':').append(fontCacheKey)
}

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    companion object {
        private val nameBitmapCache by lazy { LruCache<String, Bitmap>(33) }
        private val needNameBitmap by lazy { LruCache<String, Boolean>(99) }
    }
    private var currentJob: Job? = null
    @Volatile
    private var currentNameBitmap: Pair<String, Bitmap>? = null
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)
    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null
    private var sourceName: String? = null
    private var sourceAuthor: String? = null
    private var normalizedKeepPunctuation = BookCover.keepPunctuation
    private var nameHeight = 0f
    private var authorHeight = 0f

    /** 当前已应用的外框色, 0 表示无外框; 用于避免每帧重建 foreground */
    private var borderColor = 0
    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width >= 0) {
                params.height = width * 4 / 3
            } else {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = measuredWidth * 4 / 3
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            currentJob?.cancel()
            currentNameBitmap = null
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, w, h, 12f)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateNormalizedText()
        val useNameCover = AppConfig.useDefaultCover || needNameBitmap[bitmapPath.toString()] == true
        updateNameCoverBorder(useNameCover)
        val drawBookName = BookCover.drawBookName
        val drawBookAuthor = BookCover.drawBookAuthor
        if (!drawBookName) return
        val currentName = this.name ?: return
        if (useNameCover) {
            val currentAuthor = this.author
            // 无封面书籍统一使用固定配色: 渐变底 #FEF7E5→#FDE6E0、文字 #95735E 且无描边,
            // 不再跟随主题的 backgroundColor / accentColor。
            // backgroundColor 仅作为位图缓存键的一部分, 实际底色由 nameCoverGradientColors 画。
            val backgroundColor = nameCoverGradientColors.first()
            val accentColor = nameCoverTextColor
            val fontSizes = BookCover.fontSizes
            val fontTypeface = BookCover.fontTypeface
            val fontCacheKey = BookCover.fontCacheKey
            val cacheKey = coverBitmapCacheKey(
                currentName,
                currentAuthor,
                width,
                height,
                BookCover.drawBookNameHorizontal,
                drawBookAuthor,
                backgroundColor,
                accentColor,
                BookCover.adaptiveTitleSize,
                fontSizes,
                fontCacheKey,
            )
            val cacheBitmap = getNameBitmap(cacheKey)
            if (cacheBitmap != null) {
                canvas.drawBitmap(cacheBitmap, 0f, 0f, null)
                return
            }
            drawNameAuthor(currentName, currentAuthor, backgroundColor, accentColor, false,
                fontSizes, fontTypeface, fontCacheKey)
        }
    }

    /**
     * 无封面书籍的封面外框: 一个**圆角描边**的透明前景层。
     *
     * 用 [android.view.View.setForeground] 而不是往 Bitmap 上画边框, 两个原因:
     * - 本控件 `clipToOutline = true` + `setRoundRect(..., 12f)`, 前景层会被自动裁成圆角,
     *   边框天然贴合; 画在 Bitmap 里则会被裁掉四角露出直角边。
     * - 前景层不参与位图缓存键, 换主题时不必重算 Bitmap。
     *
     * 颜色取「设置 → 阅读 → 分隔线颜色」(`ReadTipConfig.tipDividerColor`),
     * 语义即其名称里的 `-1 / 0 / 其它` 三态。
     * 有真实封面的书籍不加框 —— 用户要的是「无封面书籍」的纯色封面更协调。
     */
    private fun updateNameCoverBorder(useNameCover: Boolean) {
        if (!useNameCover) {
            if (borderColor != 0) {
                foreground = null
                borderColor = 0
            }
            return
        }
        val color = tipBorderColor()
        if (color == borderColor) return
        borderColor = color
        foreground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = NAME_COVER_BORDER_CORNER_RADIUS
            setStroke(NAME_COVER_BORDER_WIDTH_DP.dpToPx(), color)
        }
    }

    /** 读取「分隔线颜色」设置, 与 `PageView.upStyle()` 的取值逻辑保持一致 */
    private fun tipBorderColor(): Int = when (val color = ReadTipConfig.tipDividerColor) {
        -1 -> ContextCompat.getColor(context, R.color.divider)
        0 -> ReadBookConfig.durConfig.curTextColor()
        else -> color
    }

    private fun getNameBitmap(cacheKey: String): Bitmap? {
        val currentBitmap = currentNameBitmap
        if (currentBitmap?.first == cacheKey) return currentBitmap.second
        return nameBitmapCache[cacheKey]?.also {
            currentNameBitmap = cacheKey to it
        }
    }

    private fun updateNormalizedText() {
        val keepPunctuation = BookCover.keepPunctuation
        if (keepPunctuation == normalizedKeepPunctuation) return
        val currentName = normalizeCoverText(sourceName, keepPunctuation)
        val currentAuthor = normalizeCoverText(sourceAuthor, keepPunctuation)
        if (name != currentName || author != currentAuthor) {
            currentNameBitmap = null
        }
        name = currentName
        author = currentAuthor
        normalizedKeepPunctuation = keepPunctuation
    }

    private fun drawNameAuthor(
        name: String,
        author: String?,
        backgroundColor: Int = nameCoverGradientColors.first(),
        accentColor: Int = nameCoverTextColor,
        asyncAwait: Boolean = true,
        fontSizes: CoverFontSizes? = BookCover.fontSizes,
        fontTypeface: Typeface? = BookCover.fontTypeface,
        fontCacheKey: String = BookCover.fontCacheKey,
    ) {
        generateCoverAsync(
            name,
            author,
            backgroundColor,
            accentColor,
            BookCover.drawBookNameHorizontal,
            BookCover.drawBookAuthor,
            BookCover.adaptiveTitleSize,
            asyncAwait,
            fontSizes,
            fontTypeface,
            fontCacheKey,
        )
    }
    private fun generateCoverAsync(
        name: String,
        author: String?,
        backgroundColor: Int,
        accentColor: Int,
        horizontal: Boolean,
        drawAuthor: Boolean,
        adaptiveTitle: Boolean,
        asyncAwait: Boolean,
        fontSizes: CoverFontSizes?,
        fontTypeface: Typeface?,
        fontCacheKey: String,
    ) {
        currentJob?.cancel()
        val requestedBitmapPath = bitmapPath
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                if (asyncAwait) {
                    withTimeoutOrNull(1200) {
                        triggerChannel.receive()
                    }
                    ensureActive()
                }
                if (width == 0) {
                    var attempts = 0
                    do {
                        delay(1L)
                        attempts++
                    } while (width == 0 && attempts < 2000)
                }
                ensureActive()
                val renderWidth = width
                val renderHeight = height
                if (renderWidth <= 0 || renderHeight <= 0) return@launch
                val cacheKey = coverBitmapCacheKey(
                    name,
                    author,
                    renderWidth,
                    renderHeight,
                    horizontal,
                    drawAuthor,
                    backgroundColor,
                    accentColor,
                    adaptiveTitle,
                    fontSizes,
                    fontCacheKey,
                )
                if (getNameBitmap(cacheKey) != null) {
                    postInvalidate()
                    return@launch
                }
                val bitmap = generateCoverBitmap(
                    name,
                    author,
                    renderWidth,
                    renderHeight,
                    horizontal,
                    drawAuthor,
                    backgroundColor,
                    accentColor,
                    adaptiveTitle,
                    fontSizes,
                    fontTypeface,
                )
                ensureActive()
                needNameBitmap.put(requestedBitmapPath.toString(), true)
                nameBitmapCache.put(cacheKey, bitmap)
                currentNameBitmap = cacheKey to bitmap
                postInvalidate()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateCoverBitmap(
        name: String?,
        author: String?,
        renderWidth: Int,
        renderHeight: Int,
        horizontal: Boolean,
        drawAuthor: Boolean,
        backgroundColor: Int,
        accentColor: Int,
        adaptiveTitle: Boolean,
        fontSizes: CoverFontSizes?,
        fontTypeface: Typeface?,
    ): Bitmap {
        val viewWidth = renderWidth.toFloat()
        val viewHeight = renderHeight.toFloat()
        val bitmap = createBitmap(renderWidth, renderHeight)
        val bitmapCanvas = Canvas(bitmap)
        // 底色: 整张封面铺满自上而下的渐变, 保证不露出下方被盖掉的占位图
        val backgroundPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, viewHeight,
                nameCoverGradientColors,
                null,
                Shader.TileMode.CLAMP,
            )
        }
        bitmapCanvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)
        val startX = viewWidth * NAME_COVER_TITLE_START_X_RATIO
        val startY = viewHeight * NAME_COVER_TITLE_START_Y_RATIO
        if (horizontal) {
            drawHorizontalTextCover(
                bitmapCanvas,
                name,
                author,
                accentColor,
                drawAuthor,
                viewWidth,
                viewHeight,
                adaptiveTitle,
                fontSizes,
                fontTypeface,
            )
            return bitmap
        }
        val namePaint = TextPaint().apply {
            typeface = fontTypeface ?: Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        name?.toStringArray()?.let { name ->
            // 字号只由「书名大字号」决定, **不因为标题长而缩小**;
            // 版面不足时折出更多纵向列, 仍放不下则末尾以「…」省略。
            val textSize = viewWidth * NAME_COVER_TITLE_TEXT_SIZE_RATIO *
                (fontSizes?.titleLarge?.let { it / 100f } ?: 1f)
            val layout = foldVerticalName(name.size, namePaint, textSize, viewWidth, viewHeight)
            namePaint.textSize = textSize
            namePaint.color = accentColor
            namePaint.style = Paint.Style.FILL
            val lastColumn = layout.columns.lastIndex
            var index = 0
            for (column in layout.columns.indices) {
                val columnX = startX + layout.stepX * column
                var columnY = startY
                repeat(layout.columns[column]) { row ->
                    val isLastChar = layout.ellipsized && column == lastColumn &&
                        row == layout.columns[column] - 1
                    bitmapCanvas.drawText(
                        if (isLastChar) NAME_COVER_ELLIPSIS else name[index].toString(),
                        columnX,
                        columnY,
                        namePaint,
                    )
                    index++
                    columnY += layout.charHeight
                }
            }
        }
        if (!drawAuthor){
            return bitmap
        }
        val authorPaint = TextPaint(namePaint).apply {
            typeface = fontTypeface ?: Typeface.DEFAULT
        }
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            fontSizes?.let {
                authorPaint.textSize *= it.authorLarge / 100f
                if (author.size * authorPaint.textHeight > viewHeight * 0.65f) {
                    authorPaint.textSize = viewWidth / 10 * it.authorSmall / 100f
                }
            }
            startX = (renderWidth * 0.8f).coerceAtMost(viewWidth - NAME_COVER_EDGE_EPSILON)
            var startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            author.forEach {
                authorPaint.color = accentColor
                authorPaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95) {
                    return@let
                }
            }
        }
        return bitmap
    }

    private fun drawHorizontalTextCover(
        canvas: Canvas,
        name: String?,
        author: String?,
        accentColor: Int,
        drawAuthor: Boolean,
        viewWidth: Float,
        viewHeight: Float,
        adaptiveTitle: Boolean,
        fontSizes: CoverFontSizes?,
        fontTypeface: Typeface?,
    ) {
        val basePaint = TextPaint().apply {
            isAntiAlias = true
            typeface = fontTypeface ?: Typeface.DEFAULT_BOLD
        }
        name?.takeIf { it.isNotEmpty() }?.let { title ->
            val titleWidth = (viewWidth * 0.78f).toInt().coerceAtLeast(1)
            val titlePaint = TextPaint(basePaint).apply {
                textAlign = Paint.Align.LEFT
                textSize = viewWidth / 7
                fontSizes?.let { textSize *= it.titleLarge / 100f }
            }
            var titleLayout = horizontalTitleLayout(title, titlePaint, titleWidth)
            if (titleLayout.lineCount > 1 || titlePaint.measureText(title) > titleWidth) {
                val firstLineEnd = titleLayout.getLineEnd(0)
                titlePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                    ?: (viewWidth / 9)
                val displayTitle = if (adaptiveTitle) title else SpannableString(title).apply {
                    val ratio = fontSizes?.let { it.titleLarge.toFloat() / it.titleSmall } ?: (9f / 7f)
                    setSpan(RelativeSizeSpan(ratio), 0, firstLineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                titleLayout = horizontalTitleLayout(displayTitle, titlePaint, titleWidth)
            }
            val titleX = (viewWidth - titleWidth) / 2f
            val titleY = viewHeight * 0.1f
            canvas.save()
            canvas.translate(titleX, titleY)
            titlePaint.color = accentColor
            titlePaint.style = Paint.Style.FILL
            titleLayout.draw(canvas)
            canvas.restore()
        }

        if (!drawAuthor) return
        author?.takeIf { it.isNotEmpty() }?.let { authorText ->
            val authorWidth = viewWidth * 0.65f
            val authorPaint = TextPaint(basePaint).apply {
                typeface = fontTypeface ?: Typeface.DEFAULT
                textAlign = Paint.Align.RIGHT
                textSize = viewWidth / 10
                fontSizes?.let { textSize *= it.authorLarge / 100f }
            }
            val smallSize = fontSizes?.let { viewWidth / 10 * it.authorSmall / 100f } ?: (viewWidth / 16)
            if (fontSizes != null && authorPaint.measureText(authorText) > authorWidth &&
                smallSize > authorPaint.textSize
            ) {
                authorPaint.textSize = smallSize
            }
            while (authorPaint.textSize > smallSize &&
                authorPaint.measureText(authorText) > authorWidth
            ) {
                authorPaint.textSize = if (fontSizes == null) authorPaint.textSize - 0.5f
                    else maxOf(smallSize, authorPaint.textSize - 0.5f)
            }
            val displayAuthor = TextUtils.ellipsize(
                authorText,
                authorPaint,
                authorWidth,
                TextUtils.TruncateAt.END
            ).toString()
            val authorX = (viewWidth * 0.9f).coerceAtMost(viewWidth - NAME_COVER_EDGE_EPSILON)
            val authorY = viewHeight * 0.92f
            authorPaint.color = accentColor
            authorPaint.style = Paint.Style.FILL
            canvas.drawText(displayAuthor, authorX, authorY, authorPaint)
        }
    }

    /** 纵向书名排版结果 */
    private class VerticalNameLayout(
        val charHeight: Float,
        val stepX: Float,
        /** 每列字数, 从第一列开始 */
        val columns: IntArray,
        /** 是否因版面不足而省略了末尾文字 */
        val ellipsized: Boolean,
    )

    /**
     * 把书名的 [total] 个字折成若干纵向列。
     *
     * **字号恒定**(由「书名大字号」决定), 版面不足时按以下顺序退让:
     * ① 折出更多纵向列 → ② 扩到封面宽度允许的最大列数 → ③ 仍放不下则末尾用「…」省略。
     * 这样长标题也是大字, 不会因为书名长就被缩成小字。
     *
     * 字高用 [paint] 实测 —— `textHeight` 与字体相关, 不能按固定比例硬算。
     * 方法会改写 [paint] 的 textSize, 由调用方随后重新赋值。
     */
    private fun foldVerticalName(
        total: Int,
        paint: TextPaint,
        textSize: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): VerticalNameLayout {
        val startX = viewWidth * NAME_COVER_TITLE_START_X_RATIO
        val areaHeight = viewHeight * NAME_COVER_TITLE_AREA_HEIGHT_RATIO
        paint.textSize = textSize
        val charHeight = paint.textHeight.coerceAtLeast(1f)
        val stepX = textSize + charHeight * NAME_COVER_COLUMN_GAP_RATIO
        val perColumn = (areaHeight / charHeight).toInt().coerceAtLeast(1)
        // 列数上限 n 满足: startX + (n-1)*stepX + textSize/2 <= viewWidth
        val availableWidth = viewWidth - startX - textSize / 2f - NAME_COVER_EDGE_EPSILON
        val columnLimit = ((availableWidth / stepX).toInt() + 1).coerceAtLeast(1)

        if (total <= perColumn) {
            return VerticalNameLayout(charHeight, stepX, intArrayOf(total), ellipsized = false)
        }
        // 最少需要多少列才能放下(ceilDiv)
        val neededColumns = (total + perColumn - 1) / perColumn
        if (neededColumns <= columnLimit) {
            val base = total / neededColumns
            val extra = total % neededColumns
            return VerticalNameLayout(
                charHeight, stepX,
                IntArray(neededColumns) { base + if (it < extra) 1 else 0 },
                ellipsized = false,
            )
        }
        // 连宽度允许的最大列数都装不下: 填满容量, 末尾用省略号表示还有内容
        return VerticalNameLayout(
            charHeight, stepX,
            IntArray(columnLimit) { perColumn },
            ellipsized = true,
        )
    }

    private fun horizontalTitleLayout(
        title: CharSequence,
        paint: TextPaint,
        width: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(title, 0, title.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .setMaxLines(HORIZONTAL_TITLE_MAX_LINES)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    fun setHeight(height: Int) {
        val width = height * 3 / 4
        minimumWidth = width
    }

    private val glideListener by lazy {
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                triggerChannel.trySend(Unit)
                needNameBitmap.put(bitmapPath.toString(), true)
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                currentJob?.cancel()
                currentJob = null
                needNameBitmap.remove(bitmapPath.toString())
                invalidate()
                return false
            }

        }
    }

    fun load(
        searchBook: SearchBook,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null
    ) {
        load(searchBook.coverUrl, searchBook.name, searchBook.author, loadOnlyWifi, searchBook.origin, fragment, lifecycle)
    }

    fun load(
        book: Book,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        load(
            book.getDisplayCover(),
            book.name,
            book.author,
            loadOnlyWifi,
            book.getCoverSourceOrigin(),
            fragment,
            lifecycle,
            onLoadFinish
        )
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        currentJob?.cancel()
        currentJob = null
        triggerChannel.tryReceive()
        sourceName = name
        sourceAuthor = author
        normalizedKeepPunctuation = BookCover.keepPunctuation
        val currentAuthor = normalizeCoverText(author, BookCover.keepPunctuation)
        val currentName = normalizeCoverText(name, BookCover.keepPunctuation)
        val currentPath = path?.takeIf { it.isNotBlank() }
        if (this.name != currentName || this.author != currentAuthor) {
            currentNameBitmap = null
        }
        this.author = currentAuthor
        this.name = currentName
        this.bitmapPath = currentPath
        if (AppConfig.useDefaultCover) {
            ImageLoader.load(context, BookCover.defaultDrawable)
                .centerCrop()
                .into(this)
        } else {
            if (currentPath == null) {
                // 无封面书籍: 不再加载默认占位图, 直接由 onDraw 绘制纯色封面,
                // 否则占位图会在文字之外的区域露出来。
                needNameBitmap.put(currentPath.toString(), true)
                setImageDrawable(nameCoverColorDrawable)
                invalidate()
                onLoadFinish?.invoke()
                return
            }
            if (BookCover.drawBookName && currentName != null) {
                drawNameAuthor(currentName, currentAuthor, asyncAwait = true)
            }
            var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            var builder = if (fragment != null && lifecycle != null) {
                ImageLoader.load(fragment, lifecycle, currentPath)
            } else {
                ImageLoader.load(context, currentPath)//Glide自动识别http://,content://和file://
            }
            builder = builder.apply(options)
                .placeholder(BookCover.defaultDrawable)
                .error(BookCover.defaultDrawable)
                .listener(glideListener)
            if (onLoadFinish != null) {
                builder = builder.addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }
                })
            }
            builder
                .centerCrop()
                .into(this)
        }
    }

    override fun onDetachedFromWindow() {
        currentJob?.cancel()
        currentJob = null
        super.onDetachedFromWindow()
    }

}
