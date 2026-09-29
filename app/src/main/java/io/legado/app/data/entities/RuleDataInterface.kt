package io.legado.app.data.entities

/**
 * 带变量存储的规则数据契约。
 *
 * 原先位于 `io.legado.app.model.analyzeRule`，离线化改造移除 AnalyzeRule 体系时一并被删，
 * 但 `BaseBook` / `BookChapter` 仍然实现它，因此搬到本包
 * （两者都在 `data.entities`，可直接同包引用，无需 import）。
 *
 * 与基线的差异：基线把 `putBigVariable` / `getBigVariable` 声明为抽象方法，
 * 由 `BookChapter` 覆写并委托 `RuleBigDataHelp` 落到书源相关表。书源子系统整体移除后
 * 该表已不存在、上述覆写也随之一并删除，故这里改为内存默认实现（不落库）。
 */
interface RuleDataInterface {

    val variableMap: HashMap<String, String>

    fun putVariable(key: String, value: String?): Boolean {
        val keyExist = variableMap.contains(key)
        return when {
            value == null -> {
                variableMap.remove(key)
                putBigVariable(key, null)
                keyExist
            }

            value.length < 10000 -> {
                putBigVariable(key, null)
                variableMap[key] = value
                true
            }

            else -> {
                variableMap.remove(key)
                putBigVariable(key, value)
                keyExist
            }
        }
    }

    /**
     * 变量超长时的落库实现。
     *
     * 原版由 RuleBigDataHelp 落到书源相关表；离线化把书源子系统整体移除后
     * 该表已不存在，且 BaseBook / BookChapter 均未覆写此方法，
     * 因此给出默认实现：不落库，变量统一保留在内存 variableMap 中。
     */
    fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
    }

    fun getVariable(key: String): String {
        return variableMap[key] ?: getBigVariable(key) ?: ""
    }

    fun getBigVariable(key: String): String? {
        return variableMap[key]
    }
}
