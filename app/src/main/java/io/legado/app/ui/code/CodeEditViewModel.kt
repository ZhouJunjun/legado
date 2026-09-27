package io.legado.app.ui.code

import android.app.Application
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import com.script.ScriptException
import com.script.rhino.RhinoScriptEngine
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppPattern
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import org.eclipse.tm4e.core.registry.IThemeSource
import org.jsoup.Jsoup
import splitties.init.appCtx

class CodeEditViewModel(application: Application, private val savedState: SavedStateHandle) : BaseViewModel(application) {
    private val themeFileNames = arrayOf(
        "d_monokai_dimmed",
        "d_monokai",
        "d_modern",
        "l_modern",
        "d_solarized",
        "l_solarized",
        "d_abyss",
        "l_quiet"
    )

    var initialText = ""
    @Volatile
    internal var editorDraft: SafeEditorContent? = null
    var cursorPosition = 0
    internal var language: RuntimeObjectCompletionLanguage? = null
    private var languageName = "source.js"
    private val themeRegistry: ThemeRegistry = ThemeRegistry.getInstance()
    var writable = true
    var title: String? = null
    internal var canCheckJavaScriptSyntax = false
        private set

    fun initSora() {
        //初始化sora加载
        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(appCtx.assets)
        )
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    fun initData(
        intent: Intent, success: () -> Unit
    ) {
        execute {
            val cacheKey = intent.getStringExtra("cacheKey")
            if (cacheKey != null) {
                val cacheText = CacheManager.getFromMemory(cacheKey) as? String ?: throw Exception("未获取到查看文本")
                writable = false
                initialText = cacheText
            } else if (intent.hasExtra("textFile")) {
                initialText = CodeTextTransfer.read(context, intent.getStringExtra("textFile").orEmpty())
                    ?: throw Exception("未获取到待编辑文本")
            } else {
                initialText = intent.getStringExtra("text") ?: throw Exception("未获取到待编辑文本")
            }
            if (intent.getBooleanExtra("readOnly", false)) writable = false
            if (editorDraft == null) {
                savedState.get<String>("editorDraftPath")?.let { path ->
                    val text = CodeTextTransfer.read(context, path)
                        ?: error(context.getString(R.string.code_editor_result_error))
                    editorDraft = SafeEditorContent(text, savedState["editorDraftCursor"] ?: 0, text != initialText)
                }
            }
            if (isHtmlStr(initialText)) {
                languageName = "text.html.basic"
            } else {
                intent.getStringExtra("languageName")?.let { languageName = it }
            }
            language = RuntimeObjectCompletionLanguage(
                TextMateLanguage.create(languageName, AppConfig.editAutoComplete)
            )
            cursorPosition = intent.getIntExtra("cursorPosition", 0)
            title = intent.getStringExtra("title")
            canCheckJavaScriptSyntax = intent.getBooleanExtra(
                CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX,
                false,
            )
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
        }
    }

    internal fun persistEditorDraft() {
        val draft = editorDraft ?: return
        val path = CodeTextTransfer.write(context, draft.text)
        val previous = savedState.get<String>("editorDraftPath")
        savedState["editorDraftPath"] = path
        savedState["editorDraftCursor"] = draft.cursorPosition
        CodeTextTransfer.delete(context, previous)
    }

    override fun onCleared() {
        CodeTextTransfer.delete(context, savedState.get<String>("editorDraftPath"))
        super.onCleared()
    }

    private fun isHtmlStr(text: String): Boolean {
        val trimmedText = text.trim()
        val htmlRegex = Regex("""^(?:\[[\s\d.]])?<(?:html|!DOCTYPE)""", RegexOption.IGNORE_CASE)
        return htmlRegex.containsMatchIn(trimmedText) && trimmedText.endsWith(">")
    }

    fun loadTextMateThemes(index: Int) {
        val theme = themeFileNames.getOrElse(index) { "d_monokai" }
        val themeModel = themeRegistry.findThemeByFileName(theme)
        if (themeModel == null) {
            val themeAssetsPath = "textmate/$theme.json"
            val themeSource = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath),
                themeAssetsPath,
                null
            )
            themeRegistry.loadTheme(ThemeModel(themeSource, theme).apply {
                isDark = theme.startsWith("d_")
            })
        } else {
            themeRegistry.setTheme(themeModel)
        }
    }

    fun formatCode(editor: CodeEditor) {
        val source = editor.text.toString()
        execute {
            val text = source
            if (languageName.contains("markdown")) {
                context.toastOnUi("markdown不需要格式化")
                return@execute text
            }
            val isHtml = languageName.contains("html")
            if (isHtml) {
                return@execute formatCodeHtml(text)
            }
            formatRuleExpression(text) { formatJsCode(it) }?.let {
                return@execute it
            }
            var result = ""
            var start = 0
            val indexS = text.indexOf("<js>")
            if (indexS >= 0) {
                if (indexS > 0) {
                    result += text.substring(start, indexS).trim()
                }
                val indexE = text.indexOf("</js>", indexS)
                val jsCode = text.substring(indexS + 4, indexE)
                result += "<js>\n"
                result += formatJsCode(jsCode)
                result += "\n</js>"
                start = indexE + 5
            }
            val indexS2 = text.indexOf("@js:")
            if (indexS2 >= 0) {
                if (indexS2 > start) {
                    result += text.substring(start, indexS2).trim()
                }
                val jsCode = text.substring(indexS2 + 4)
                result += "@js:\n"
                result += formatJsCode(jsCode)
                start = text.length
            } else {
                val indexS2 = text.indexOf("@webjs:")
                if (indexS2 >= 0) {
                    if (indexS2 > start) {
                        result += text.substring(start, indexS2).trim()
                    }
                    val jsCode = text.substring(indexS2 + 7)
                    result += "@webjs:\n"
                    result += formatJsCode(jsCode)
                    start = text.length
                }
            }
            if (start == 0) {
                result += formatJsCode(text)
                start = text.length
            }
            if (text.length > start) {
                result += text.substring(start).trim()
            }
            result
        }.onSuccess { formatted ->
            if (formatted != null && formatted != source && editor.text.toString() == source) {
                editor.text.replace(0, editor.text.length, formatted)
            }
        }.onError {
        }
    }

    fun checkJavaScriptSyntax(editor: CodeEditor) {
        val source = editor.text.toString()
        executeLazy {
            RhinoScriptEngine.compile(source)
        }.onSuccess {
            if (editor.text.toString() == source) {
                context.toastOnUi(R.string.javascript_syntax_correct)
            }
        }.onError { error ->
            if (editor.text.toString() != source) return@onError
            (error as? ScriptException)?.takeIf { it.lineNumber > 0 }?.let {
                val index = scriptSourceIndex(source, it.lineNumber, it.columnNumber)
                val position = editor.cursor.indexer.getCharPosition(index)
                editor.setSelection(position.line, position.column, true)
                editor.requestFocus()
            }
        }.start()
    }

    /**
     * 轻量 JS 缩进格式化：按大括号层级重排缩进，丢弃空行（不改动代码语义）。
     *
     * 原实现借后台 WebView 执行 js_beautify，后台 WebView 已随书源逻辑一并剥离，
     * 这里改为纯本地实现，保留「格式化」入口的基本可用性。
     */
    private fun formatJsCode(jsCode: String): String {
        val out = StringBuilder()
        var indent = 0
        var inBlockComment = false
        jsCode.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            var opens = 0
            var closes = 0
            var quote: Char? = null
            var escaped = false
            var lineComment = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inBlockComment) {
                    if (c == '*' && i + 1 < line.length && line[i + 1] == '/') {
                        inBlockComment = false
                        i++
                    }
                } else if (lineComment) {
                    // 行内注释：忽略本行后续字符
                } else if (quote != null) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == quote) {
                        quote = null
                    }
                } else when {
                    c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        lineComment = true
                        i++
                    }

                    c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                        inBlockComment = true
                        i++
                    }

                    c == '\'' || c == '"' || c == '`' -> quote = c
                    c == '{' -> opens++
                    c == '}' -> closes++
                }
                i++
            }
            val net = opens - closes
            if (net < 0) indent = (indent + net).coerceAtLeast(0)
            out.append("    ".repeat(indent)).append(line).append('\n')
            if (net > 0) indent += net
        }
        return out.toString().trimEnd()
    }

    private fun formatCodeHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.outputSettings()
            .indentAmount(4)
            .prettyPrint(true)
        return doc.outerHtml()
    }

}

internal suspend fun formatRuleExpression(
    text: String,
    formatter: suspend (String) -> String?
): String? {
    val matcher = AppPattern.EXP_PATTERN.matcher(text.trim())
    if (!matcher.matches()) return null
    val body = matcher.group(1).trim()
    val formattedBody = if (body.isEmpty()) body else formatter(body) ?: body
    return "{{$formattedBody}}"
}

internal fun scriptSourceIndex(source: String, lineNumber: Int, columnNumber: Int): Int {
    if (lineNumber <= 0) return 0
    var lineStart = 0
    repeat(lineNumber - 1) {
        val lineEnd = source.indexOf('\n', lineStart)
        if (lineEnd < 0) return source.length
        lineStart = lineEnd + 1
    }
    val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
    return (lineStart + (columnNumber - 1).coerceAtLeast(0)).coerceAtMost(lineEnd)
}
