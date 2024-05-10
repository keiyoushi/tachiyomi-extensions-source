package eu.kanade.tachiyomi.extension.pt.brasilhentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class BrasilHentai : ParsedHttpSource() {

    override val name = "Brasil Hentai"

    override val baseUrl = "https://brasilhentai.com"

    override val lang = "pt-BR"

    override val supportsLatest: Boolean = false

    override fun chapterFromElement(element: Element) =
        throw UnsupportedOperationException()

    override fun chapterListSelector() =
        throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.just(
            listOf(
                SChapter.create().apply {
                    name = "Capítulo único"
                    url = manga.url
                },
            ),
        )
    }

    override fun imageUrlParse(document: Document) = ""

    override fun latestUpdatesFromElement(element: Element) =
        throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) =
        throw UnsupportedOperationException()

    override fun latestUpdatesSelector() =
        throw UnsupportedOperationException()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1")!!.ownText()
        thumbnail_url = document.selectFirst(".entry-content p a img")?.absUrl("src")
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".entry-content p > img").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }
    }

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val anchor = element.selectFirst("a[title]")
        title = anchor!!.attr("title")
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(anchor.absUrl("href"))
    }

    override fun popularMangaNextPageSelector() = ".next.page-numbers"

    override fun popularMangaRequest(page: Int) = GET("baseUrl/page/$page", headers)

    override fun popularMangaSelector() = ".content-area article"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>()
            .first { it.selectedValue().isNotBlank() }
            .selectedValue()

        val url = if (category.isEmpty()) {
            "$baseUrl/page/$page".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
        } else {
            "$baseUrl/category/$category/page/$page/".toHttpUrl()
        }

        return GET(url, headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            client.newCall(GET("$baseUrl/${query.removePrefix(PREFIX_SEARCH)}", headers))
                .asObservableSuccess()
                .map { MangasPage(listOf(searchMangaFromElement(it.asJsoup())), false) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun getFilterList(): FilterList {
        CoroutineScope(Dispatchers.IO).launch { fetchGenres() }

        val filters = mutableListOf<Filter<*>>()

        if (categoryFilterOptions.isNotEmpty()) {
            filters += CategoryFilter("Categoria", categoryFilterOptions)
        } else {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Aperte 'Redefinir' para tentar mostrar as categorias"),
            )
        }

        return FilterList(filters)
    }

    private var categoryFilterOptions: Array<Pair<String, String>> = emptyArray()

    private var fetchGenresAttempts: Int = 0

    private fun fetchGenres() {
        if (fetchGenresAttempts < 3 && categoryFilterOptions.isEmpty()) {
            try {
                categoryFilterOptions += Pair("Todos", "")
                categoryFilterOptions += client.newCall(genresRequest()).execute()
                    .use { parseGenres(it.asJsoup()) }.toTypedArray()
            } catch (_: Exception) {
            } finally {
                fetchGenresAttempts++
            }
        }
    }

    private fun parseGenres(document: Document): List<Pair<String, String>> {
        return document.select("#categories-2 li a")
            .map { element ->
                val url = element.absUrl("href")
                val category = url.split("/").filter { it.isNotBlank() }.last()
                Pair(element.ownText(), category)
            }
    }

    class CategoryFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: String? = null,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
    ) {
        fun selectedValue() = vals[state].second
    }

    private fun genresRequest(): Request = GET(baseUrl, headers)

    companion object {
        val PREFIX_SEARCH: String = "slug:"
    }
}
