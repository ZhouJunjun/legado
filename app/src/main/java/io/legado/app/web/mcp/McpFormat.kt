package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

object McpFormat {

    const val TRUNCATE_LIMIT = 100_000

    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun detectFormat(source: String): String {
        val first = source.firstOrNull { !it.isWhitespace() && it != '\uFEFF' }
        return if (first == '{' || first == '[') "json" else "js"
    }


    fun toPrettyJson(value: Any): String = prettyGson.toJson(value)

    fun prettyJson(json: String): String = prettyGson.toJson(JsonParser.parseString(json))

    fun truncate(text: String, limit: Int = TRUNCATE_LIMIT): String {
        if (text.length <= limit) return text
        return text.take(limit) + "\n…[已截断,原文 ${text.length} 字符]"
    }



}
