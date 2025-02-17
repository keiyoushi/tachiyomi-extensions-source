package eu.kanade.tachiyomi.extension.pt.yugenmangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class YugenMangas : HttpSource() {

    override val name = "Yugen Mangás"

    override val baseUrl = "https://yugenmangasbr.voblog.xyz"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2, TimeUnit.SECONDS)
        .build()

    override val versionId = 2

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    val apiHeaders by lazy { apiHeadersBuilder().build() }

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Origin", baseUrl)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "no-cors")
        .add("Sec-Fetch-Site", "same-site")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$BASE_API/widgets/sort_and_filter/".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .build()

        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<PageDto<MangaDto>>()
        val mangaList = dto.results.map { it.toSManga() }
        return MangasPage(mangaList, hasNextPage = dto.hasNext())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$BASE_API/widgets/home/updates/", apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<LatestUpdatesDto>()
        val mangaList = dto.series.map { it.toSManga() }
        return MangasPage(mangaList, hasNextPage = false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val payload = json.encodeToString(SearchDto(query)).toRequestBody(JSON_MEDIA_TYPE)
        return POST("$BASE_API/widgets/search/", apiHeaders, payload)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val code = manga.url.substringAfterLast("/")
        val payload = json.encodeToString(SeriesDto(code)).toRequestBody(JSON_MEDIA_TYPE)
        return POST("$BASE_API/series/detail/series/", apiHeaders, payload)
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        return response.parseAs<MangaDetailsDto>().toSManga()
    }

    private fun chapterListRequest(manga: SManga, page: Int): Request {
        val code = manga.url.substringAfterLast("/")
        val payload = json.encodeToString(SeriesDto(code)).toRequestBody(JSON_MEDIA_TYPE)
        return POST("$BASE_API/series/chapters/get-series-chapters/?page=$page", apiHeaders, payload)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        var page = 1
        val chapters = mutableListOf<SChapter>()
        do {
            val response = client.newCall(chapterListRequest(manga, page++)).execute()
            val series = response.getSeriesCode()
            val chapterContainer = response.parseAs<ChapterContainerDto>()
            chapters += chapterContainer.toSChapter(series.code)
        } while (chapterContainer.next != null)

        return Observable.just(chapters)
    }

    private fun Response.getSeriesCode(): SeriesDto =
        this.request.body!!.parseAs<SeriesDto>()

    override fun pageListRequest(chapter: SChapter): Request {
        val code = chapter.url.substringAfterLast("/")
        val key = "wKwPtVJhLSHoMJAiTAVnnggEils1zgHF"
        val payload = json.encodeToString(SeriesWithKeyDto(code, key)).toRequestBody(JSON_MEDIA_TYPE)
        return POST("$BASE_API/chapters/chapter-info/", apiHeaders, payload)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val series = response.request.body!!.parseAs<SeriesDto>()
        return response.parseAs<ChapterContainerDto>().toSChapter(series.code)
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun pageListParse(response: Response): List<Page> {
        return response.parseAs<PageListDto>().images.mapIndexed { index, imageUrl ->
            Page(index, baseUrl, "$BASE_MEDIA/$imageUrl")
        }
    }

    override fun imageUrlParse(response: Response) = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromString(it.body.string())
    }

    private inline fun <reified T> RequestBody.parseAs(): T {
        val jsonString = Buffer().also { writeTo(it) }.readUtf8()
        return json.decodeFromString(jsonString)
    }

    companion object {
        private const val BASE_API = "https://api.yugenweb.com/api"
        private const val BASE_MEDIA = "https://media.yugenweb.com"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
