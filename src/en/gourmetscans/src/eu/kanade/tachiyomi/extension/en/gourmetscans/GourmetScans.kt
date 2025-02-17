package eu.kanade.tachiyomi.extension.en.gourmetscans

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GourmetScans : Madara(
    "Gourmet Scans",
    "https://gourmetsupremacy.com",
    "en",
) {
    override val mangaSubString = "project"

    override val useNewChapterEndpoint = false

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        val orderByFilter = filterList.find { it is OrderByFilter } as OrderByFilter
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter

        when {
            yearFilter.state.isNotBlank() -> {
                url.addPathSegment("release-year")
                url.addPathSegment(yearFilter.state)
            }
            genreFilter?.state?.equals(0)?.not() ?: false -> {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter!!.toUriPart())
            }
            else -> {
                url.addPathSegment(mangaSubString)
            }
        }

        if (orderByFilter.toUriPart().isNotBlank()) {
            url.addQueryParameter("m_orderby", orderByFilter.toUriPart())
        }

        url.addPathSegments(searchPage(page))

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector(): String = ".page-listing-item .page-item-detail"

    override fun searchMangaNextPageSelector(): String = ".navigation-ajax > #navigation-ajax"

    // Filters

    override fun genresRequest(): Request = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        return document.select("div.row.genres ul li a")
            .orEmpty()
            .map { li ->
                Genre(
                    li.text(),
                    li.attr("href").split("/").last { it.isNotBlank() },
                )
            }
    }

    class GenreFilter(vals: List<Genre>) :
        UriPartFilter("Genre", vals.map { Pair(it.name, it.id) }.toTypedArray())

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = buildList(4) {
            add(YearFilter(intl["year_filter_title"]))
            add(
                OrderByFilter(
                    title = intl["order_by_filter_title"],
                    options = orderByFilterOptions.map { Pair(it.key, it.value) },
                    state = 0,
                ),
            )
            add(Filter.Separator())

            if (genresList.isEmpty()) {
                add(Filter.Header(intl["genre_missing_warning"]))
            } else {
                add(GenreFilter(listOf(Genre("<select>", "")) + genresList))
            }
        }

        return FilterList(filters)
    }

    // Chapters

    override fun chapterFromElement(element: Element): SChapter {
        return super.chapterFromElement(element).apply {
            url = this.url.substringBefore("?style=list")
        }
    }
}
