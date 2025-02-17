package eu.kanade.tachiyomi.extension.en.manga18fx

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Evaluator
import java.text.SimpleDateFormat
import java.util.Locale

// The site isn't actually based on Madara but reproduces it very well
class Manga18fx : Madara(
    "Manga18fx",
    "https://manga18fx.com",
    "en",
    SimpleDateFormat("dd MMM yy", Locale.ENGLISH),
) {
    override val id = 3157287889751723714

    override val fetchGenres = false
    override val sendViewCount = false

    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        loadGenres(document)
        val block = document.selectFirst(Evaluator.Class("trending-block"))!!
        val mangas = block.select(Evaluator.Tag("a")).map(::mangaFromElement)
        return MangasPage(mangas, false)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        url = element.attr("href")
        title = element.attr("title")
        thumbnail_url = element.selectFirst(Evaluator.Tag("img"))!!.attr("data-src")
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        loadGenres(document)
        val mangas = document.select(Evaluator.Class("bsx-item")).map {
            mangaFromElement(it.selectFirst(Evaluator.Tag("a"))!!)
        }
        val nextButton = document.selectFirst(Evaluator.Class("next"))
        val hasNextPage = nextButton != null && nextButton.hasClass("disabled").not()
        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) =
        super.fetchSearchManga(page, query, filters).doOnNext {
            for (manga in it.mangas)
                manga.url = manga.url.removeSuffix("/")
        }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty()) {
            filters.forEach { filter ->
                if (filter is GenreFilter) {
                    return GET(filter.vals[filter.state].id, headers)
                }
            }
            return latestUpdatesRequest(page)
        }

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    override val mangaDetailsSelectorDescription = ".dsct"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val container = document.selectFirst(Evaluator.Class("row-content-chapter"))!!
        return container.children().map(::chapterFromElement)
    }

    override fun chapterDateSelector() = "span.chapter-time"

    class GenreFilter(val vals: List<Genre>) :
        Filter.Select<String>("Genre", vals.map { it.name }.toTypedArray())

    private fun loadGenres(document: Document) {
        genresList = document.select(".header-bottom li a").map {
            val href = it.attr("href")
            val url = if (href.startsWith("http")) href else "$baseUrl/$href"

            Genre(it.text(), url)
        }
    }

    private var hardCodedTypes: List<Genre> = listOf(
        Genre("Manhwa", "$baseUrl/manga-genre/manhwa"),
        Genre("Manhua", "$baseUrl/manga-genre/manhua"),
        Genre("Raw", "$baseUrl/manga-genre/raw"),
    )

    override fun getFilterList(): FilterList {
        val filters = buildList(2) {
            add(Filter.Header("Filters are ignored for text search!"))

            if (genresList.isNotEmpty()) {
                add(
                    GenreFilter(hardCodedTypes + genresList),
                )
            } else {
                add(
                    Filter.Header("Wait for mangas to load then tap Reset"),
                )
            }
        }

        return FilterList(filters)
    }
}
