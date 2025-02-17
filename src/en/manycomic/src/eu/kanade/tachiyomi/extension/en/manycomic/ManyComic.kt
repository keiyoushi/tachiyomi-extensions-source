package eu.kanade.tachiyomi.extension.en.manycomic

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document

class ManyComic : Madara("ManyComic", "https://manycomic.com", "en") {
    override val mangaSubString = "comic"

    override fun popularMangaNextPageSelector() = ".wp-pagenavi .next"

    override fun oldXhrChaptersRequest(mangaId: String): Request {
        val form = FormBody.Builder()
            .add("action", "ajax_chap")
            .add("post_id", mangaId)
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, form)
    }

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()

        if (query.isNotBlank()) {
            url.addPathSegments(searchPage(page))
            url.addQueryParameter("s", query)
            url.addQueryParameter("post_type", "wp-manga")
        } else if (genreFilter != null && genreFilter.state != 0) {
            url.addPathSegment("comic-genre")
            url.addPathSegment(genreFilter.toUriPart())
            url.addPathSegments(searchPage(page))
        } else {
            url.addPathSegment(mangaSubString)
            url.addPathSegments(searchPage(page))
        }

        val orderFilter = filters.filterIsInstance<OrderByFilter>().firstOrNull()
        if (orderFilter != null) {
            url.addQueryParameter("m_orderby", orderFilter.toUriPart())
        }

        return GET(url.build(), headers)
    }

    override fun parseGenres(document: Document): List<Genre> {
        return document.selectFirst(".manga-genres-class-name div.genres")
            ?.select("li>a")
            .orEmpty()
            .map { a ->
                Genre(
                    name = a.ownText(),
                    id = a.attr("abs:href")
                        .removeSuffix("/")
                        .substringAfterLast("/"),
                )
            }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters: MutableList<Filter<out Any>> = mutableListOf(
            OrderByFilter(
                title = intl["order_by_filter_title"],
                options = orderByFilterOptions.toList(),
                state = 0,
            ),
        )

        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                Filter.Header("Genre filter is ignored when searching by title"),
                GenreFilter(
                    title = intl["genre_filter_title"],
                    options = listOf(Genre("<All>", "")) + genresList,
                    state = 0,
                ),
            )
        } else {
            listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_missing_warning"]),
            )
        }

        return FilterList(filters)
    }

    override val orderByFilterOptions: Map<String, String> = mapOf(
        intl["order_by_filter_latest"] to "latest",
        intl["order_by_filter_az"] to "alphabet",
        intl["order_by_filter_rating"] to "rating",
        intl["order_by_filter_trending"] to "trending",
        intl["order_by_filter_views"] to "views",
        intl["order_by_filter_new"] to "new-manga",
    )

    private class GenreFilter(title: String, options: List<Genre>, state: Int = 0) :
        UriPartFilter(title, options.map { Pair(it.name, it.id) }.toTypedArray(), state)
}
