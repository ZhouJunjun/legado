package io.legado.app.help.source

import androidx.collection.LruCache
import io.legado.app.utils.InfoMap

/**
 * 书源发现规则（exploreUrl）脚本运行期的 infoMap 载体。
 *
 * 原先挂在 ExploreAdapter 的伴生对象上；探索 UI 删除后迁到此处，
 * 供 [io.legado.app.model.webBook.WebBook] 与 [BookSourceExtensions] 继续使用。
 */
object ExploreInfoStore {
    val exploreInfoMapList = LruCache<String, InfoMap>(99)
}
