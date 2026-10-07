package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.CrashLog
import io.legado.app.help.DefaultData
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ResourceThemeGeneration
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.defaultSharedPreferences
import splitties.systemservices.notificationManager

class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        oldConfig = Configuration(resources.configuration)
        ResourceThemeGeneration.observeSystemNight(
            oldConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
            AppConfig.themeMode !in listOf("1", "2"),
        )
        WallpaperTheme.syncWithPreferences(this)
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        initRhino()
        CrashLog.install()
        Coroutine.async {
            createNotificationChannels()
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true)
                .autoClear(false)
            DefaultData.upVersion()
            //初始化封面
            BookCover.toString()
            //清除过期数据
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            BookHelp.clearInvalidCache()
            Backup.clearCache()
            ReadBookConfig.clearBgAndCache()
//            ThemeConfig.clearBg() //每次手动切换主题时清理多余图片
            //初始化简繁转换引擎
            when (AppConfig.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }

                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            //调整排序序号
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            ResourceThemeGeneration.observeSystemNight(
                newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
                AppConfig.themeMode !in listOf("1", "2"),
            )
            applyDayNight(this)
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(readAloudChannel)
        )
    }

    private fun initRhino() {
        RhinoScriptEngine.initialize()
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

}
