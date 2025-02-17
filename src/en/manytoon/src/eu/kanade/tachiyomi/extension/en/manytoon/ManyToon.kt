package eu.kanade.tachiyomi.extension.en.manytoon

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document

class ManyToon : Madara("ManyToon", "https://manytoon.com", "en") {

    override val mangaSubString = "comic"

    override val useNewChapterEndpoint = false
    override val sendViewCount = false

    override fun oldXhrChaptersRequest(mangaId: String): Request {
        val form = FormBody.Builder()
            .add("action", "ajax_chap")
            .add("post_id", mangaId)
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, form)
    }

    override fun popularMangaRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = true)
        } else {
            GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=trending", headers)
        }

    override fun latestUpdatesRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            GET("$baseUrl/home/${searchPage(page)}", headers)
        }

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            var alr = false
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")

            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> {
                        if (filter.state.isNotBlank() && !alr) {
                            addPathSegments("manga-author/${filter.state.replace(" ", "-")}")
                            alr = true
                        }
                    }
                    is ArtistFilter -> {
                        if (filter.state.isNotBlank() && !alr) {
                            addQueryParameter("artist", filter.state.replace(" ", "-"))
                            alr = true
                        }
                    }
                    is YearFilter -> {
                        if (filter.state.isNotBlank() && !alr) {
                            addPathSegments("comic-release/${filter.state}")
                            alr = true
                        }
                    }
                    is OrderByFilter -> {
                        addQueryParameter("m_orderby", filter.toUriPart())
                    }
                    is GenreConditionFilter -> {
                        val name = filter.toUriPart()
                        if (name != "all" && !alr) {
                            addPathSegments("manga-genre/$name")
                            alr = true
                        }
                    }
                    else -> {}
                }
            }

            addPathSegments(searchPage(page))
        }
        return GET(url.build(), headers)
    }

    override val orderByFilterOptions: Map<String, String> = mapOf(
        intl["order_by_filter_az"] to "alphabet",
        intl["order_by_filter_rating"] to "rating",
        intl["order_by_filter_trending"] to "trending",
        intl["order_by_filter_views"] to "views",
        intl["order_by_filter_new"] to "new-manga",
    )

    override fun parseGenres(document: Document): List<Genre> {
        return document.selectFirst("div.genres")
            ?.select("a")
            .orEmpty()
            .map { a ->
                Genre(
                    a.ownText(),
                    a.attr("href").substringBeforeLast("/").substringAfterLast("/"),
                )
            }.let {
                listOf(Genre("All", "all")) + it
            }
    }

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = mutableListOf(
            Filter.Header("All filters except the orderby filter are incompatible with each other"),
            AuthorFilter(intl["author_filter_title"]),
            ArtistFilter(intl["artist_filter_title"]),
            YearFilter(intl["year_filter_title"]),
            OrderByFilter(
                title = intl["order_by_filter_title"],
                options = orderByFilterOptions.toList(),
            ),
        )

        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                GenreConditionFilter(
                    title = intl["genre_filter_title"],
                    options = genresList.map { it.name to it.id },
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

    override fun searchMangaSelector() = "div.page-item-detail"
    override fun popularMangaNextPageSelector() = "a.next"
}
