package io.legado.app.api

internal enum class ReaderProviderRequestCode {
    SaveBook,
    GetBookshelf,
    RefreshToc,
    GetChapterList,
    GetBookContent,
    GetBookCover,
    SaveBookProgress,
}

internal data class ReaderProviderRoute(
    val path: String,
    val requestCode: ReaderProviderRequestCode,
)

internal object ReaderProviderRoutes {

    val all = listOf(
        ReaderProviderRoute("book/insert", ReaderProviderRequestCode.SaveBook),
        ReaderProviderRoute("books/query", ReaderProviderRequestCode.GetBookshelf),
        ReaderProviderRoute("book/refreshToc/query", ReaderProviderRequestCode.RefreshToc),
        ReaderProviderRoute("book/chapter/query", ReaderProviderRequestCode.GetChapterList),
        ReaderProviderRoute("book/content/query", ReaderProviderRequestCode.GetBookContent),
        ReaderProviderRoute("book/cover/query", ReaderProviderRequestCode.GetBookCover),
    )

    private val requestByPath = all.associate { it.path to it.requestCode }

    init {
        require(requestByPath.size == all.size) { "ReaderProvider paths must be unique" }
        require(all.map { it.requestCode }.distinct().size == all.size) {
            "ReaderProvider request codes must be unique"
        }
    }

    fun requestForPath(path: String): ReaderProviderRequestCode? = requestByPath[path]

    fun requestForMatcherCode(code: Int): ReaderProviderRequestCode? =
        ReaderProviderRequestCode.entries.getOrNull(code)
}

internal fun dispatchReaderProviderDelete(
    requestCode: ReaderProviderRequestCode,
    selection: String?,
) {
    error("Unexpected delete request: ${requestCode.name}")
}

internal suspend fun dispatchReaderProviderInsert(
    requestCode: ReaderProviderRequestCode,
    postData: String?,
    valuesPresent: Boolean = true,
    saveBook: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveBookProgress: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
) {
    when (requestCode) {
        ReaderProviderRequestCode.SaveBook -> if (valuesPresent) saveBook(postData)
        ReaderProviderRequestCode.SaveBookProgress -> if (valuesPresent) saveBookProgress(postData)
        else -> unexpectedReaderProviderRequest(requestCode)
    }
}

internal fun <T> dispatchReaderProviderQuery(
    requestCode: ReaderProviderRequestCode,
    parameters: Map<String, List<String>>,
    getBookshelf: () -> T = { unexpectedReaderProviderRequest(requestCode) },
    getBookContent: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    refreshToc: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getChapterList: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getBookCover: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
): T {
    return when (requestCode) {
        ReaderProviderRequestCode.GetBookshelf -> getBookshelf()
        ReaderProviderRequestCode.GetBookContent -> getBookContent(parameters)
        ReaderProviderRequestCode.RefreshToc -> refreshToc(parameters)
        ReaderProviderRequestCode.GetChapterList -> getChapterList(parameters)
        ReaderProviderRequestCode.GetBookCover -> getBookCover(parameters)
        else -> unexpectedReaderProviderRequest(requestCode)
    }
}

private fun unexpectedReaderProviderRequest(requestCode: ReaderProviderRequestCode): Nothing {
    error("Unexpected ReaderProvider request: ${requestCode.name}")
}
