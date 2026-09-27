package io.legado.app.utils

object UrlUtil {

    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    /* 获取合法的文件后缀 */
    fun getSuffix(str: String, default: String? = null): String {
        val suffix = str
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        //检查截取的后缀字符是否合法 [a-zA-Z0-9]
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
            default ?: "ext"
        } else {
            suffix
        }
    }

}
