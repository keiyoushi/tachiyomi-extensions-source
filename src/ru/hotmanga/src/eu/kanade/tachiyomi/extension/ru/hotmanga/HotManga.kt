package eu.kanade.tachiyomi.extension.ru.hotmanga

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.ru.hotmanga.dto.MangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HotManga : ConfigurableSource, HttpSource() {

    override val id = 2073023199372375753

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val baseOrig: String = "https://hotmanga.me"

    private val baseMirr: String = "https://xn--80aaaklzpjd4c4a.xn--p1ai" // https://мангахентай.рф

    private val baseMirrSecond: String = "https://xn--80aaalhzvfe9b4a.xn--80asehdb" // https://хентайманга.онлайн

    private val baseMirrThird: String = "https://xn--80aanrbklcdf5b7a.xn--p1ai" // https://хентайонлайн.рф

    private val apiPath = "/api"

    private val domain: String? = preferences.getString(DOMAIN_PREF, baseOrig)

    private val paidSymbol = "\uD83D\uDD12"

    override val baseUrl = domain.toString()

    override val lang = "ru"

    override val name = "HotManga"

    override val supportsLatest = true

    private val apiPathsMap = mapOf(
        baseOrig to apiPath,
        baseMirr to "/api-frontend",
        baseMirrSecond to apiPath,
        baseMirrThird to apiPath,
    )

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Host", baseUrl.replace("https://", ""))

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun popularMangaRequest(page: Int): Request {
        val pageF = page - 1
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/catalog?orderBy=-likes&page=$pageF"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val values = json.parseToJsonElement(response.body.string()).jsonArray
        val mangaListDto = mutableListOf<MangaDto>()
        for (item in values) {
            try {
                mangaListDto.add(json.decodeFromJsonElement<MangaDto>(item))
            } catch (e: Exception) {
                Log.i("HotManga", e.toString())
            }
        }
        var hasNextPage = true
        if (mangaListDto.isEmpty()) {
            hasNextPage = false
        }
        val mangas = mutableListOf<SManga>()
        for (mangaItem in mangaListDto) {
            val element = mangaItem.toSManga()
            mangas.add(element)
        }
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String {
        return baseUrl + cleanMangaUrlFromBookIdParameter(manga.url)
    }

    private fun MangaDto.toSManga(): SManga =
        SManga.create().apply {
            title = titleEn ?: slug
            url = "/manga/$slug?bookId=$id" // TODO Use HttpUrlBuilder to escape arguments properly.
            // Original host does not work for some locations. Cloudflare protection. Need to change domain.
            // Parameters w and q need to be calculated.
            thumbnail_url = "$baseMirrThird/_next/image?url=$baseMirrThird$imageHigh&w=768&q=75"
            description = desc?.trim()
        }

    override fun chapterListParse(response: Response): List<SChapter> = throw NotImplementedError("Unused")

    override fun imageUrlParse(response: Response): String = throw NotImplementedError("Unused")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val pageF = page - 1
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/catalog?orderBy=-id&page=$pageF"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = throw NotImplementedError("Unused")

    override fun pageListParse(response: Response): List<Page> = throw NotImplementedError("Unused")

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // TODO Add filters, use page param, investigate limit param
        val apiPathVal = apiPathsMap[baseUrl]
        val apiString = "$apiPathVal/books/search?filter[query]=$query&limit=24"
        return GET("${baseUrl}$apiString", headers)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = mutableListOf<SChapter>()
        val mangaUrl = manga.url
        val urlObj = mangaUrl.toHttpUrlOrNull()
        val bookId = urlObj?.queryParameter("bookId")
        val apiPathVal = apiPathsMap[baseUrl]
        val urlBase = "$baseUrl$apiPathVal/chapters/with-branches?filter%5BbookId%5D=$bookId"
        val request = GET(urlBase.toHttpUrl(), headers)
        val body = client.newCall(request).execute().body.string()
        val values = json.parseToJsonElement(body).jsonArray
        for (item in values) {
            // TODO Use a DTO instead of this.
            val number = item.jsonObject["number"].toString().replace("\"", "").toFloat()
            val createdAt = item.jsonObject["createdAt"]?.jsonPrimitive?.content
            val tom = item.jsonObject["tom"]?.jsonPrimitive?.content
            val id = item.jsonObject["id"].toString()
            val chapterBranches = item.jsonObject["chapterBranches"]?.jsonArray
            var branchId = "0"
            var isSubscription = false
            if (chapterBranches != null) {
                branchId = chapterBranches[0].jsonObject["branchId"].toString()
                isSubscription =
                    chapterBranches[0].jsonObject["isSubscription"]?.jsonPrimitive?.content.toBoolean()
            }
            val cleanUrl = cleanMangaUrlFromBookIdParameter(mangaUrl)
            val chapterUrl = "$cleanUrl/ch$id?branchId=$branchId"
            val parseDate = parseDate(createdAt)
            var chapterName = "$tom. Глава $number"
            if (isSubscription) {
                chapterName += paidSymbol
            }
            // TODO Use setUrlWithoutDomain() to allow for easier domain swapping in the future.
            val sChapter = SChapter.create().apply {
                url = chapterUrl
                name = chapterName
                date_upload = parseDate
                chapter_number = number
            }
            chapters.add(sChapter)
        }
        return Observable.just(chapters)
    }

    private val simpleDateFormat by lazy {
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US,
        )
    }

    private fun parseDate(date: String?): Long {
        date ?: return Date().time
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val list = mutableListOf<Page>()
        val pageUrl = "$baseUrl${chapter.url}"
        val chapterPage = client.newCall(GET(pageUrl.toHttpUrl(), headers)).execute().asJsoup()
        val elements = chapterPage.select("div.relative")
        for (elem in elements) {
            val imgElem = elem.select("img")
            val imgSrc = imgElem.attr("src")
            if (imgSrc.isNotEmpty()) {
                list.add(Page(list.size, "", imgSrc))
            }
        }
        return Observable.just(list)
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    private fun cleanMangaUrlFromBookIdParameter(url: String) = url.split("?")[0]

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "Выбор домена"
            entries = arrayOf("Основной (hotmanga.me)", "Зеркало (мангахентай.рф)", "Зеркало 2 (хентайманга.онлайн)", "Зеркало 3 (хентайонлайн.рф)")
            entryValues = arrayOf(baseOrig, baseMirr, baseMirrSecond, baseMirrThird)
            summary = "%s"
            setDefaultValue(baseOrig)
            setOnPreferenceChangeListener { _, newValue ->
                val warning =
                    "Для смены домена необходимо перезапустить приложение с полной остановкой."
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val DOMAIN_PREF = "HMMangaDomain"
    }
}
