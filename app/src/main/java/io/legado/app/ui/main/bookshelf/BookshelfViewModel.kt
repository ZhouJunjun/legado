package io.legado.app.ui.main.bookshelf

import android.app.Application
import io.legado.app.base.BaseViewModel

/**
 * 书架不需要额外的数据操作：书籍全部来自本地文件导入，
 * 原先的「添加网址 / 导入书单 / 导出书单」均依赖书源联网检索，已一并删除。
 */
class BookshelfViewModel(application: Application) : BaseViewModel(application)
