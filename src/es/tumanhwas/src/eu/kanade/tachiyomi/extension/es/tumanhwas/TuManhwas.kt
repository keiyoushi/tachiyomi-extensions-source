package eu.kanade.tachiyomi.extension.es.tumanhwas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class TuManhwas : ParsedHttpSource() {
    override val name: String = "TuManhwas"

    override val baseUrl: String = "https://tumanhwas.com"

    override val lang: String = "es"

    override val supportsLatest: Boolean = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst(".tt")!!.text()
        thumbnail_url = element.selectFirst("img")?.imgSrc
        setUrlWithoutDomain(element.absUrl("href"))
    }

    override fun popularMangaNextPageSelector() = ".page-link[rel='next']"

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.bs div.bsx a"

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(URL_SEARCH_PREFIX).not()) {
            return super.fetchSearchManga(page, query, filters)
        }

        val manga = SManga.create().apply {
            url = "/manga/${query.substringAfter(URL_SEARCH_PREFIX)}"
        }

        return fetchMangaDetails(manga)
            .asObservable().map {
                MangasPage(listOf(it), false)
            }
    }

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", "$page")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element).apply {
        val chapterNumber = "-${url.substringAfterLast("-")}"
        url = url.replace("news", "manga")
            .removeSuffix(chapterNumber)
    }

    override fun latestUpdatesNextPageSelector() = null

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = ".bixbox.seriesearch:has(h1) a"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst(".main-info")!!.let {
            title = it.selectFirst("h1")!!.text()
            thumbnail_url = it.selectFirst("img")?.imgSrc
            description = it.selectFirst(".summary p")?.text()
            genre = it.select(".genres-container a")
                .map { it.text() }
                .joinToString()
            setUrlWithoutDomain(document.location())
        }
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst(".chapternum")!!.text()
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun chapterListSelector() = "#chapterlist a"

    override fun pageListParse(document: Document): List<Page> {
        return document.select("#chapter_imgs img").mapIndexed { index, element ->
            Page(index, document.location(), imageUrl = element.imgSrc)
        }
    }

    override fun imageUrlParse(document: Document) = ""

    private val Element.imgSrc: String get() = attr("abs:data-src").ifEmpty { attr("abs:src") }

    companion object {
        const val URL_SEARCH_PREFIX = "slug:"
    }
}
