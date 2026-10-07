package io.legado.app.ui.book.searchContent

import android.text.Spanned
import androidx.core.text.HtmlCompat

data class SearchResult(
    val resultCount: Int = 0,
    val resultCountWithinChapter: Int = 0,
    val resultText: String = "",
    val chapterTitle: String = "",
    val query: String = "",
    val pageSize: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val queryIndexInResult: Int = 0,
    val queryIndexInChapter: Int = 0,
    val isRegex: Boolean = false
) {
    fun getHtmlCompat(textColor: String, accentColor: String): Spanned {
        if (query.isNotBlank()) {
            if (isRegex) { // 正则表达式高亮处理
                try {
                    var match = Regex(query).find(resultText, 20)
                    if (match == null) {
                        match = Regex(query).find(resultText)
                    }
                    if (match != null) {
                        val matchedText = match.value
                        val start = match.range.first
                        val end = match.range.last + 1
                        val leftString = resultText.take(start)
                        val rightString = resultText.substring(end)
                        // 普通模式：使用颜色
                        val html = buildString {
                            append(chapterTitle.colorTextForHtml(accentColor))
                            append("<br>")
                            append(leftString.colorTextForHtml(textColor))
                            append(matchedText.colorTextForHtml(accentColor))
                            append(rightString.colorTextForHtml(textColor))
                        }
                        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        return getNormalHtml(textColor)
                    }
                } catch (e: Exception) {
                    return getNormalHtml(textColor)
                }
            } else {
                // 普通搜索高亮
                //在20个字符之后查找，因为展示内容在getResultAndQueryIndex的时候左右移动20个字符
                var queryIndexInSurrounding = resultText.indexOf(query, 20)
                if (queryIndexInSurrounding < 0) {
                    queryIndexInSurrounding = resultText.indexOf(query)
                }
                if (queryIndexInSurrounding >= 0) {
                    val leftString = resultText.take(queryIndexInSurrounding)
                    val rightString = resultText.substring(queryIndexInSurrounding + query.length)
                    // 普通模式：使用颜色
                    val html = buildString {
                        append(chapterTitle.colorTextForHtml(accentColor))
                        append("<br>")
                        append(leftString.colorTextForHtml(textColor))
                        append(query.colorTextForHtml(accentColor))
                        append(rightString.colorTextForHtml(textColor))
                    }
                    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    return getNormalHtml(textColor)
                }
            }
        } else {
            return getNormalHtml(textColor)
        }
    }

    private fun getNormalHtml(textColor: String): Spanned {
        val html = resultText.colorTextForHtml(textColor)
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun String.colorTextForHtml(textColor: String) =
        "<font color=#${textColor}>$this</font>"

}
