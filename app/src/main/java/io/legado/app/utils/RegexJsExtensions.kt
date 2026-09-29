package io.legado.app.utils

import androidx.annotation.Keep

/**
 * 替换净化规则在 JS 中可用的 `java` 对象。
 *
 * 规则写法：`@js:java.put('key', result)` / `@js:java.get('key')`
 * 对应文档 `assets/web/help/md/jsHelp.md` 里的 `java.get(key)` / `java.put(key, value)`，
 * 以及 `updateLog.md` 承诺的「净化规则使用 js 时支持调用 java.log」。
 *
 * 离线化改造移除了 JsEncodeUtils（加解密扩展），故本类只保留
 * 变量读写、日志与简繁转换；`java.md5Encode` 等加解密方法已不再提供。
 */
@Keep
@Suppress("unused")
class RegexJsExtensions(private val name: String) {

    private val ruleData by lazy { RuleData() }

    /**
     * 输出调试日志。
     *
     * 原实现写 `AppLog.putDebug`，但离线化改造已把 AppLog（连同 LogUtils）整套移除，
     * 这里退化为控制台输出，保持「返回值即入参」的链式语义不变。
     */
    fun log(msg: Any?): Any? {
        println("RegexJsExtensions: 替换净化规则 $name 输出: $msg")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    fun t2s(text: String): String {
        return ChineseUtils.t2s(text)
    }

    fun s2t(text: String): String {
        return ChineseUtils.s2t(text)
    }

    fun get(key: String): String {
        return ruleData.getVariable(key)
    }

    fun put(key: String, value: String): String {
        ruleData.putVariable(key, value)
        return value
    }
}

/**
 * 替换规则内 JS 的变量容器（原 RuleData 的最小子集）。
 *
 * 原 `RuleData` 实现 `RuleDataInterface` 以支持「大变量落库」；
 * 净化规则是纯内存的一次性求值，不需要落库，故这里用最简实现。
 */
private class RuleData {

    private val variableMap = hashMapOf<String, String>()

    fun putVariable(key: String, value: String?): Boolean {
        return if (value == null) {
            variableMap.remove(key) != null
        } else {
            variableMap[key] = value
            true
        }
    }

    fun getVariable(key: String): String {
        return variableMap[key] ?: ""
    }
}
