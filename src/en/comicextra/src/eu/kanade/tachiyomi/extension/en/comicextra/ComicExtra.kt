package eu.kanade.tachiyomi.extension.en.comicextra

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class ComicExtra : ParsedHttpSource() {

    override val name = "ComicExtra"

    override val baseUrl = "https://azcomix.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularMangaSelector() = "div.eg-box"

    override fun latestUpdatesSelector() = "ul.line-list"

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular-comics?page=$page", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/comic-updates?page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/advanced-search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("key", query)
            if (page > 1) addQueryParameter("page", page.toString())
            filters.forEach { filter ->
                when (filter) {
                    is GenreGroupFilter -> {
                        with(filter) {
                            addQueryParameter("wg", included.joinToString("%20"))
                            addQueryParameter("wog", excluded.joinToString("%20"))
                        }
                    }
                    is StatusFilter -> addQueryParameter("status", filter.selected)
                    else -> {}
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a.eg-image").attr("href"))
        title = element.select("div.egb-right a").text()
        thumbnail_url = element.select("img").attr("src")
    }

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a.big-link").attr("href"))
        title = element.select(".big-link").first()?.text().toString()
        thumbnail_url = fetchThumbnailURL(element.select("a.big-link").attr("href"))
    }

    private fun fetchThumbnailURL(url: String) = client.newCall(GET(url, headers)).execute().asJsoup().select("div.anime-image > img").attr("src")

    override fun popularMangaNextPageSelector() = "div.general-nav > a:contains(Next)"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaSelector() = "div.dl-box"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a.dlb-image").attr("href"))
        title = element.select("div.dlb-right a.dlb-title").text()
        thumbnail_url = element.select("a.dlb-image img").attr("src")
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").text()
            thumbnail_url = document.select("div.anime-image > img").attr("src")
            status = parseStatus(document.select(".status a").text())
            author = document.select("td:contains(Author:) + td").text()
            description = document.select("div.detail-desc-content p").text()
            genre = document.select("ul.anime-genres > li + li").joinToString { it.text() }
        }
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("Completed") -> SManga.COMPLETED
        element.contains("Ongoing") -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = ArrayList<SChapter>()

        document.select(chapterListSelector()).forEach {
            chapters.add(chapterFromElement(it))
        }

        return chapters
    }

    override fun chapterListSelector() = "ul.basic-list li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlEl = element.select("a")
        val dateEl = element.select("span")

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlEl.attr("href").replace(" ", "%20"))
        chapter.name = urlEl.text()
        chapter.date_upload = dateParse(dateEl.text())
        return chapter
    }

    private fun dateParse(dateAsString: String): Long {
        val date: Date? = SimpleDateFormat("MM/dd/yy", Locale.ENGLISH).parse(dateAsString)

        return date?.time ?: 0L
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url + "/full", headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        document.select("div.chapter-container img").forEachIndexed { i, img ->
            pages.add(Page(i, "", img.attr("abs:src")))
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = FilterList(
        StatusFilter(getStatusList, 0),
        GenreGroupFilter(getGenreList()),
    )

    class SelectFilterOption(val name: String, val value: String)
    class TriStateFilterOption(val value: String, name: String, default: Int = 0) : Filter.TriState(name, default)

    abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
        val selected: String
            get() = options[state].value
    }

    abstract class TriStateGroupFilter(name: String, options: List<TriStateFilterOption>) : Filter.Group<TriStateFilterOption>(name, options) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.value }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.value }
    }

    class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
    class GenreGroupFilter(options: List<TriStateFilterOption>) : TriStateGroupFilter("Genre", options)

    private val getStatusList = listOf(
        SelectFilterOption("All", ""),
        SelectFilterOption("Ongoing", "ONG"),
        SelectFilterOption("Completed", "CMP"),
    )

    private fun getGenreList() = listOf(
        TriStateFilterOption("Action", "Action"),
        TriStateFilterOption("Adventure", "Adventure"),
        TriStateFilterOption("Anthology", "Anthology"),
        TriStateFilterOption("Anthropomorphic", "Anthropomorphic"),
        TriStateFilterOption("Biography", "Biography"),
        TriStateFilterOption("Children", "Children"),
        TriStateFilterOption("Comedy", "Comedy"),
        TriStateFilterOption("Crime", "Crime"),
        TriStateFilterOption("Cyborgs", "Cyborgs"),
        TriStateFilterOption("DC Comics", "DC Comics"),
        TriStateFilterOption("Dark Horse", "Dark Horse"),
        TriStateFilterOption("Demons", "Demons"),
        TriStateFilterOption("Drama", "Drama"),
        TriStateFilterOption("Family", "Family"),
        TriStateFilterOption("Fantasy", "Fantasy"),
        TriStateFilterOption("Fighting", "Fighting"),
        TriStateFilterOption("Gore", "Gore"),
        TriStateFilterOption("Graphic Novels", "Graphic Novels"),
        TriStateFilterOption("Historical", "Historical"),
        TriStateFilterOption("Horror", "Horror"),
        TriStateFilterOption("Leading Ladies", "Leading Ladies"),
        TriStateFilterOption("LEOMACS", "a><span-class=-comic"),
        TriStateFilterOption("Literature", "Literature"),
        TriStateFilterOption("Magic", "Magic"),
        TriStateFilterOption("Manga", "Manga"),
        TriStateFilterOption("Martial Arts", "Martial Arts"),
        TriStateFilterOption("Marvel", "Marvel"),
        TriStateFilterOption("Mature", "Mature"),
        TriStateFilterOption("Mecha", "Mecha"),
        TriStateFilterOption("Military", "Military"),
        TriStateFilterOption("Movie Cinematic Link", "Movie Cinematic Link"),
        TriStateFilterOption("Mystery", "Mystery"),
        TriStateFilterOption("Mythology", "Mythology"),
        TriStateFilterOption("Personal", "Personal"),
        TriStateFilterOption("Political", "Political"),
        TriStateFilterOption("Post-Apocalyptic", "Post-Apocalyptic"),
        TriStateFilterOption("Psychological", "Psychological"),
        TriStateFilterOption("Pulp", "Pulp"),
        TriStateFilterOption("Robots", "Robots"),
        TriStateFilterOption("Romance", "Romance"),
        TriStateFilterOption("Sci-Fi", "Sci-Fi"),
        TriStateFilterOption("Science Fiction", "Science Fiction"),
        TriStateFilterOption("Slice of Life", "Slice of Life"),
        TriStateFilterOption("Sports", "Sports"),
        TriStateFilterOption("Spy", "Spy"),
        TriStateFilterOption("Superhero", "Superhero"),
        TriStateFilterOption("Supernatural", "Supernatural"),
        TriStateFilterOption("Suspense", "Suspense"),
        TriStateFilterOption("Thriller", "Thriller"),
        TriStateFilterOption("Tragedy", "Tragedy"),
        TriStateFilterOption("Vampires", "Vampires"),
        TriStateFilterOption("Vertigo", "Vertigo"),
        TriStateFilterOption("Video Games", "Video Games"),
        TriStateFilterOption("War", "War"),
        TriStateFilterOption("Western", "Western"),
        TriStateFilterOption("Zombies", "Zombies"),
    )
}
