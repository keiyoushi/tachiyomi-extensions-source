package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class BlackoutComics : ParsedHttpSource() {

    override val name = "Blackout Comics"

    override val baseUrl = "https://blackoutcomics.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client by lazy {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), 2)
            .build()
    }

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/ranking")

    override fun popularMangaSelector() = "section > div.container div > a"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        title = element.selectFirst("p, span.text-comic")?.text() ?: "Manga"
    }

    override fun popularMangaNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recentes")

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/comics/$id"))
                .asObservableSuccess()
                .map(::searchMangaByIdParse)
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    private fun searchMangaByIdParse(response: Response): MangasPage {
        val details = mangaDetailsParse(response.use { it.asJsoup() })
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Using URLBuilder just to prevent issues with strange queries
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = null

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val row = document.selectFirst("section > div.container > div.row")!!
        thumbnail_url = row.selectFirst("img")?.absUrl("src")
        title = row.selectFirst("div.trailer-content > h2")?.text() ?: "Manga"

        with(row.selectFirst("div.trailer-content:has(h3:containsOwn(Detalhes))")!!) {
            println(outerHtml())
            artist = getInfo("Artista")
            author = getInfo("Autor")
            genre = getInfo("Genêros")
            status = when (getInfo("Status")) {
                "Completo" -> SManga.COMPLETED
                "Em Lançamento" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            description = buildString {
                // Synopsis
                row.selectFirst("h3:containsOwn(Descrição) + p")?.ownText()?.also {
                    append("$it\n\n")
                }

                row.selectFirst("h2:contains($title) + p")?.ownText()?.also {
                    // Alternative title
                    append("Título alternativo: $it\n")
                }

                // Additional info
                listOf("Editora", "Lançamento", "Scans", "Tradução", "Cleaner", "Vizualizações")
                    .forEach { item ->
                        selectFirst("p:contains($item)")
                            ?.text()
                            ?.also { append("$it\n") }
                    }
            }
        }
    }

    private fun Element.getInfo(text: String) =
        selectFirst("p:contains($text)")?.run {
            selectFirst("b")?.text() ?: ownText()
        }

    // ============================== Chapters ==============================
    override fun chapterListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun chapterFromElement(element: Element): SChapter {
        throw UnsupportedOperationException()
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        throw UnsupportedOperationException()
    }

    override fun imageUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
