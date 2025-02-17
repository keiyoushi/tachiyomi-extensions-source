package eu.kanade.tachiyomi.extension.es.leermanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class LeerManga : Madara(
    "LeerManga",
    "https://leermanga.net",
    "es",
) {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override val supportsLatest = false

    override val mangaSubString = "biblioteca"

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString?page=$page", headers)

    override val popularMangaUrlSelector = ".page-item-detail a"

    override fun popularMangaNextPageSelector() = ".pagination li a[rel=next]"

    override fun popularMangaParse(response: Response): MangasPage {
        if (genresList.isEmpty()) {
            val document = Jsoup.parse(response.peekBody(Long.MAX_VALUE).string())
            genresList = parseGenres(document)
        }
        return super.popularMangaParse(response)
    }

    override val filterNonMangaItems = false

    // =============================== Search ===============================

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/$mangaSubString".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreGroup -> {
                    val selected = filter.selected()
                    if (selected.isBlank() || query.isNotBlank()) {
                        return@forEach
                    }
                    url = selected.toHttpUrl().newBuilder()
                }
                else -> {}
            }
        }

        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =============================== Pages ===============================

    override val pageListParseSelector = "#images_chapter img"

    // =============================== Filters ===============================

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()
        if (genresList.isNotEmpty()) {
            filters += listOf(
                GenreGroup(
                    displayName = intl["genre_filter_title"],
                    genres = genresList,
                ),
            )
        } else {
            filters += Filter.Header(intl["genre_missing_warning"])
        }
        return FilterList(filters)
    }

    override fun parseGenres(document: Document): List<Genre> {
        return mutableListOf<Genre>().apply {
            this += Genre("Todos", "")
            this += document.select(".genres__collapse li a")
                .map { a ->
                    Genre(
                        a.text(),
                        a.absUrl("href"),
                    )
                }
        }
    }

    class GenreGroup(displayName: String, private val genres: List<Genre>, state: Int = 0) :
        Filter.Select<String>(displayName, genres.map { it.name }.toTypedArray(), state) {
        fun selected() = genres[state].id
    }
}
