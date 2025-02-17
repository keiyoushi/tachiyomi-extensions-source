package eu.kanade.tachiyomi.extension.id.pojokmanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class PojokManga : Madara("Pojok Manga", "https://pojokmanga.info", "id", SimpleDateFormat("MMM dd, yyyy", Locale.US)) {

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(10, 2, TimeUnit.SECONDS)
        .build()

    override val useNewChapterEndpoint = true

    override val mangaSubString = "komik"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/${searchPage(page)}".toHttpUrl().newBuilder()
        url.addQueryParameter("s", query)
        url.addQueryParameter("post_type", "wp-manga")
        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("author", filter.state)
                    }
                }
                is ArtistFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("artist", filter.state)
                    }
                }
                is YearFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("release", filter.state)
                    }
                }
                is StatusFilter -> {
                    filter.state.forEach {
                        if (it.state) {
                            url.addQueryParameter("status[]", it.id)
                        }
                    }
                }
                is OrderByFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("m_orderby", filter.toUriPart())
                    }
                }
                is AdultContentFilter -> {
                    url.addQueryParameter("adult", filter.toUriPart())
                }
                is GenreConditionFilter -> {
                    url.addQueryParameter("op", filter.toUriPart())
                }
                is GenreList -> {
                    filter.state
                        .filter { it.state }
                        .let { list ->
                            if (list.isNotEmpty()) { list.forEach { genre -> url.addQueryParameter("genre[]", genre.id) } }
                        }
                }
                is ProjectFilter -> {
                    if (filter.toUriPart() == "project-filter-on") {
                        url = "$baseUrl/project/page/$page".toHttpUrl().newBuilder()
                    }
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = "div.c-tabs-item__content, div.page-item-detail"

    override val mangaDetailsSelectorTag = "#toNotBeUsed"

    private class ProjectFilter : UriPartFilter(
        "Filter Project",
        arrayOf(
            Pair("Show all manga", ""),
            Pair("Show only project manga", "project-filter-on"),
        ),
    )

    override fun getFilterList(): FilterList {
        val filters = super.getFilterList().toMutableList()

        filters += listOf(
            Filter.Separator(),
            Filter.Header("NOTE: cant be used with other filter!"),
            Filter.Header("$name Project List page"),
            ProjectFilter(),
        )

        return FilterList(filters)
    }
}
