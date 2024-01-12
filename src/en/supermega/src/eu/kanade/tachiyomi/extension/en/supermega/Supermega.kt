package eu.kanade.tachiyomi.extension.en.supermega

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Supermega : ParsedHttpSource() {

    override val name = "SUPER MEGA"

    override val baseUrl = "https://www.supermegacomics.com"

    override val lang = "en"

    override val supportsLatest = false

    override val client = getUnsafeOkHttpClient()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val manga = SManga.create()
        manga.setUrlWithoutDomain("/")
        manga.title = "SUPER MEGA"
        manga.artist = "JohnnySmash"
        manga.author = "JohnnySmash"
        manga.status = SManga.ONGOING
        manga.description = ""
        manga.thumbnail_url = "https://www.supermegacomics.com/runningman_inverted.PNG"

        return Observable.just(MangasPage(arrayListOf(manga), false))
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = fetchPopularManga(1)
        .map { it.mangas.first().apply { initialized = true } }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val latestComicNumber = client.newCall(GET(baseUrl)).execute().asJsoup().select("[name='bigbuttonprevious']").first()!!.parent()!!.attr("href").substringAfter("?i=").toInt()+1
        Observable.just(latestComicNumber.let{ IntRange(1, it) }.map {
            SChapter.create().apply {
                name = it.toString()
                chapter_number = it.toFloat()
                setUrlWithoutDomain("?i=$it")
            }
        })
    }

    override fun pageListParse(document: Document) =
        document.select("img[border='4']")
            .mapIndexed { i, element ->
                Page(i, "", element.attr("src"))
            }

    // idk if this is needed i just copied the megatokyo extension lul
    // certificate wasn't trusted for some reason so trusted all certificates
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            },
        )

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }.build()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = throw Exception("Not used")

    override fun imageUrlRequest(page: Page) = GET(page.url)

    override fun imageUrlParse(document: Document) = throw Exception("Not used")

    override fun popularMangaSelector(): String = throw Exception("Not used")

    override fun searchMangaFromElement(element: Element): SManga = throw Exception("Not used")

    override fun searchMangaNextPageSelector(): String = throw Exception("Not used")

    override fun searchMangaSelector(): String = throw Exception("Not used")

    override fun popularMangaRequest(page: Int): Request = throw Exception("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw Exception("Not used")

    override fun popularMangaNextPageSelector(): String = throw Exception("Not used")

    override fun popularMangaFromElement(element: Element): SManga = throw Exception("Not used")

    override fun mangaDetailsParse(document: Document): SManga = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SManga = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

}
