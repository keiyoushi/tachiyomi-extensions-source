package eu.kanade.tachiyomi.extension.all.unionmangas

import android.util.Log
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class UnionMangas(private val langOption: LanguageOption) : HttpSource() {
    override val lang = langOption.lang

    override val name: String = "Union Mangas"

    override val baseUrl: String = "https://unionmangas.xyz"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    val langApiInfix = when (lang) {
        "it" -> langOption.infix
        else -> "v3/po"
    }

    private val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "it", "pt-BR"),
        classLoader = this::class.java.classLoader!!,
    )

    private fun apiHeaders(url: String): Headers {
        val date = apiDateFormat.format(Date())
        val path = url.toUrlWithoutDomain()

        return headersBuilder()
            .add("_hash", authorization(apiSeed, domain, date))
            .add("_tranId", authorization(apiSeed, domain, date, path))
            .add("_date", date)
            .add("_domain", domain)
            .add("_path", path)
            .add("Origin", baseUrl)
            .add("Host", apiUrl.removeProtocol())
            .add("Referer", "$baseUrl/")
            .build()
    }

    private fun authorization(vararg payloads: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = payloads.joinToString("").toByteArray()
        val digest = md.digest(bytes)
        return digest
            .fold("") { str, byte -> str + "%02x".format(byte) }
            .padStart(32, '0')
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = mutableListOf<SChapter>()
        var currentPage = 0
        do {
            val chaptersDto = fetchChapterListPageable(manga, currentPage)
            chapters += chaptersDto.toSChapter(langOption)
            currentPage++
        } while (chaptersDto.hasNextPage())
        return Observable.just(chapters.reversed())
    }

    private fun fetchChapterListPageable(manga: SManga, page: Int): ChapterPageDto {
        return try {
            val maxResult = 16
            val url = "$apiUrl/api/$langApiInfix/GetChapterListFilter/${manga.slug()}/$maxResult/$page/all/ASC"
            client.newCall(GET(url, apiHeaders(url)))
                .execute()
                .parseAs<ChapterPageDto>()
        } catch (e: Exception) {
            val message = when (e) {
                is SerializationException -> intl["chapters_cannot_be_processed"]
                is IOException -> intl["unable_to_get_chapters"]
                else -> intl["chapter_not_found"]
            }
            Log.e("::fetchChapter", e.toString())
            throw Exception(message)
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.asJsoup().htmlDocumentToDto()
        return MangasPage(
            mangas = dto.toSMangaLatestUpdates(),
            hasNextPage = dto.hasNextPageToLatestUpdates(),
        )
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/${langOption.infix}/latest-releases".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response) =
        response.asJsoup().htmlDocumentToDto().toSMangaDetails()

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val password = findChapterPassword(document)
        val pageListData = document.htmlDocumentToDto().props.pageProps.pageListData
        val decodedData = CryptoAES.decrypt(pageListData!!, password)
        val pagesData = json.decodeFromString<PageDataDto>(decodedData)
        return pagesData.data.getImages(langOption.pageDelimiter).mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun findChapterPassword(document: Document): String {
        return try {
            val regxPaswdUrl = """\/pages\/%5Btype%5D\/%5Bidmanga%5D\/%5Biddetail%5D-.+\.js""".toRegex()
            val regxFindPaswd = """AES\.decrypt\(\w+,"(?<password>[^"]+)"\)""".toRegex(RegexOption.MULTILINE)
            val jsDecryptUrl = document.select("script")
                .map { it.absUrl("src") }
                .first { regxPaswdUrl.find(it) != null }
            val jsDecrypt = client.newCall(GET(jsDecryptUrl, headers)).execute().asJsoup().html()
            regxFindPaswd.find(jsDecrypt)?.groups?.get("password")!!.value.trim()
        } catch (e: Exception) {
            Log.e("::findChapterPassword", e.toString())
            throw Exception(intl["password_not_found"])
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.asJsoup().htmlDocumentToDto()
        return MangasPage(
            mangas = dto.toPopularSManga(),
            hasNextPage = dto.hasNextPageToPopularMangas(),
        )
    }

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/${langOption.infix}")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val maxResult = 6
        val url = "$apiUrl/api/$langApiInfix/searchforms/$maxResult/".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .addPathSegment("${page - 1}")
            .build()
        return GET(url, apiHeaders(url.toString()))
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(slugPrefix)) {
            val mangaUrl = query.substringAfter(slugPrefix)
            return client.newCall(GET("$baseUrl/${langOption.infix}/$mangaUrl", headers))
                .asObservableSuccess().map { response ->
                    val manga = mangaDetailsParse(response).apply {
                        url = mangaUrl
                    }
                    MangasPage(listOf(manga), false)
                }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun searchMangaParse(response: Response): MangasPage {
        val mangasDto = response.parseAs<MangaListDto>()
        return MangasPage(
            mangas = mangasDto.toSManga(langOption.infix),
            hasNextPage = mangasDto.hasNextPage(),
        )
    }

    private fun Document.htmlDocumentToDto(): UnionMangasDto {
        val jsonContent = selectFirst("script#__NEXT_DATA__")!!.html()
        return json.decodeFromString<UnionMangasDto>(jsonContent)
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    private fun String.removeProtocol() = trim().replace("https://", "")

    private fun SManga.slug() = this.url.split("/").last()

    private fun String.toUrlWithoutDomain() = trim().replace(apiUrl, "")

    companion object {
        val apiUrl = "https://api.unionmanga.xyz"
        val apiSeed = "8e0550790c94d6abc71d738959a88d209690dc86"
        val domain = "yaoi-chan.xyz"
        val slugPrefix = "slug:"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val apiDateFormat = SimpleDateFormat("EE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
    }
}
