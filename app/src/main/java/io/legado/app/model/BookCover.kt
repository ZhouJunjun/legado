package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable

data class CoverFontSizes(
    val titleLarge: Int,
    val titleSmall: Int,
    val authorLarge: Int,
    val authorSmall: Int,
)

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    const val fontBackupFileName = "coverFont.ttf"

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set
    var drawBookNameHorizontal = false
        private set
    var adaptiveTitleSize = true
        private set
    var keepPunctuation = false
        private set
    @Volatile
    var fontSizes: CoverFontSizes? = null
        private set
    var fontTypeface: Typeface? = null
        private set
    var fontCacheKey: String = ""
        private set
    lateinit var defaultDrawable: Drawable
        private set


    init {
        upDefaultCover()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun upDefaultCover() {
        var path: String?
        val isNightTheme = AppConfig.isNightTheme
        if (isNightTheme) {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowNameN, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthorN, true)
            path = appCtx.getPrefString(PreferKey.defaultCoverDark)
        } else {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowName, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
            path = appCtx.getPrefString(PreferKey.defaultCover)
        }
        drawBookNameHorizontal = appCtx.getPrefBoolean(PreferKey.coverHorizontal, false)
        adaptiveTitleSize = appCtx.getPrefBoolean(PreferKey.coverTitleAdaptive, true)
        keepPunctuation = appCtx.getPrefBoolean(PreferKey.coverKeepPunctuation, false)
        val fontFile = appCtx.getPrefString(PreferKey.coverFont)?.takeIf { it.isNotBlank() }?.let(::File)
        val fontKey = fontFile?.let { "${it.path}:${it.length()}:${it.lastModified()}" }.orEmpty()
        if (fontCacheKey != fontKey) {
            fontTypeface = fontFile?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            fontCacheKey = fontKey
        }
        fontSizes = if (appCtx.getPrefBoolean(PreferKey.coverCustomFontSize, false)) {
            CoverFontSizes(
                appCtx.getPrefInt(PreferKey.coverTitleLargeSize, 100).coerceIn(50, 200),
                appCtx.getPrefInt(PreferKey.coverTitleSmallSize, 100).coerceIn(50, 200),
                appCtx.getPrefInt(PreferKey.coverAuthorLargeSize, 100).coerceIn(50, 200),
                appCtx.getPrefInt(PreferKey.coverAuthorSmallSize, 100).coerceIn(50, 200),
            )
        } else null
        defaultDrawable = runCatching {
            BitmapUtils.decodeBitmap(path!!, 600, 900)!!.toDrawable(appCtx.resources)
        }.getOrDefault(appCtx.resources.getDrawable(R.drawable.image_cover_default, null))
    }

    /**
     * 加载封面
     */
    fun load(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return ImageLoader.load(context, defaultDrawable)
                .centerCrop()
        }
        var options = RequestOptions()
        var builder = ImageLoader.load(context, path)
            .apply(options)
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.placeholder(defaultDrawable)
            .error(defaultDrawable)
            .centerCrop()
    }

    /**
     * 加载漫画图片
     */
    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions()
        return ImageLoader.load(context, path)
            .apply(options)
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File> {
        var options = RequestOptions()
        return ImageLoader.loadFile(context, path).apply(options)
    }

    /**
     * 加载模糊封面
     */
    fun loadBlur(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<Drawable> {
        val loadBlur = ImageLoader.load(context, defaultDrawable)
            .transform(BlurTransformation(25), CenterCrop())
        if (AppConfig.useDefaultCover) {
            return loadBlur
        }
        var options = RequestOptions()
        return ImageLoader.load(context, path)
            .apply(options)
            .transform(BlurTransformation(25), CenterCrop())
            .transition(DrawableTransitionOptions.withCrossFade(1500))
            .thumbnail(loadBlur)
    }

}
