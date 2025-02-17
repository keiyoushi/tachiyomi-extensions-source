package eu.kanade.tachiyomi.extension.en.disasterscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class DisasterScans : ParsedHttpSource() {

    override val name = "Disaster Scans"
    override val lang = "en"
    override val versionId = 3
    override val baseUrl = "https://disasterscans.com"
    override val supportsLatest = true

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val json by injectLazy<Json>()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/home", headers)

    override fun latestUpdatesRequest(page: Int) =
        popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/comics", headers)

    override fun popularMangaSelector(): String = "div:has(span:contains(POPULAR)) + section a:has(img)"

    override fun latestUpdatesSelector(): String = "div:has(span:contains(LATEST)) + section a:has(img)"

    override fun searchMangaSelector(): String = ".grid a"

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element).apply {
        title = element.selectFirst("h5")?.text().toString()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element).apply {
        title = element.parent()?.selectFirst("div a")?.text().toString()
    }

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element).apply {
        title = element.selectFirst("h1")?.text().toString()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val response = client.newCall(searchMangaRequest(page, query, filters)).execute()
        val mangaList = response.asJsoup().select(searchMangaSelector())
            .map { searchMangaFromElement(it) }
            .filter { it.title.lowercase().contains(query.lowercase()) }
        return Observable.just(MangasPage(mangaList, false))
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        author = document.selectFirst("span:contains(Author) + span")?.text()

        val infoBlock = document.selectFirst("section div div")

        if (infoBlock != null) {
            val infoBlockChildren = infoBlock.children()

            infoBlockChildren[0].selectFirst("h1")?.text()?.let { title = it }
            description = infoBlockChildren[2].text()

            with(infoBlockChildren[1].select("span")) {
                status = when (this.removeAt(0)?.text()?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
                genre = this.joinToString(", ") { it.text() }
            }
        }
    }

    @Serializable
    class ChapterDTO(val chapterID: Int, val ChapterNumber: String, val ChapterName: String, val chapterDate: String)
    private val chapterDataRegex = Regex("""\\"chapters\\":(\[.*]),\\"param\\":\\"(\S+)\\"\}""")

    override fun chapterListParse(response: Response): List<SChapter> {
        val (chapterData, mangaId) = chapterDataRegex.find(response.body.string())
            ?.destructured ?: return emptyList()

        return json.decodeFromString<List<ChapterDTO>>(chapterData.replace("\\", "")).map { chapter ->
            SChapter.create().apply {
                name = "Chapter ${chapter.ChapterNumber} - ${chapter.ChapterName}"
                setUrlWithoutDomain(
                    baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("comics")
                        addPathSegment(mangaId)
                        addPathSegment("${chapter.chapterID}-chapter-${chapter.ChapterNumber}")
                    }.build().toString(),
                )

                date_upload = try {
                    dateFormat.parse(chapter.chapterDate)?.time ?: 0
                } catch (_: Exception) {
                    0
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> =
        document.select("section img").mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("src")) }

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaNextPageSelector(): String? = null
    override fun imageUrlParse(document: Document): String = ""
    override fun chapterListSelector(): String = ""
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()
}
