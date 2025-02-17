package eu.kanade.tachiyomi.extension.ru.nudemoon

import android.app.Application
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random

class Nudemoon : ParsedHttpSource(), ConfigurableSource {

    override val name = "Nude-Moon"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val dateParseRu = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
    private val dateParseSlash = SimpleDateFormat("d/MM/yyyy", Locale("ru"))

    private val cookieManager by lazy { CookieManager.getInstance() }

    private val userAgentRandomizer = "${Random.nextInt().absoluteValue}"
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G980F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.$userAgentRandomizer Mobile Safari/537.36")
        .add("Referer", baseUrl)

    init {
        cookieManager.setCookie(baseUrl, "nm_mobile=1; Domain=" + baseUrl.split("//")[1])
    }

    private val cookiesHeader by lazy {
        val cookies = mutableMapOf<String, String>()
        cookies["NMfYa"] = "1"
        cookies["nm_mobile"] = "1"
        buildCookies(cookies)
    }

    private fun buildCookies(cookies: Map<String, String>) =
        cookies.entries.joinToString(separator = "; ", postfix = ";") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    override val client = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val newReq = chain
                .request()
                .newBuilder()
                .addHeader("Cookie", cookiesHeader)
                .addHeader("Referer", baseUrl)
                .build()

            chain.proceed(newReq)
        }.build()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/all_manga?views&rowstart=${30 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/all_manga?date&rowstart=${30 * (page - 1)}", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Search by query on this site works really badly, i don't even sure of the need to implement it
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search?stext=${URLEncoder.encode(query, "CP1251")}&rowstart=${30 * (page - 1)}"
        } else {
            var genres = ""
            var order = ""
            for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                if (filter is GenreList) {
                    filter.state.forEach { f ->
                        if (f.state) {
                            genres += f.id + '+'
                        }
                    }
                }
            }

            if (genres.isNotEmpty()) {
                for (filter in filters) {
                    if (filter is OrderBy) {
                        // The site has no ascending order
                        order = arrayOf("&date", "&views", "&like")[filter.state!!.index]
                    }
                }
                "$baseUrl/tags/${genres.dropLast(1)}$order&rowstart=${30 * (page - 1)}"
            } else {
                for (filter in filters) {
                    if (filter is OrderBy) {
                        // The site has no ascending order
                        order = arrayOf(
                            "all_manga?date",
                            "all_manga?views",
                            "all_manga?like",
                        )[filter.state!!.index]
                    }
                }
                "$baseUrl/$order&rowstart=${30 * (page - 1)}"
            }
        }
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "table.news_pic2"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.thumbnail_url = element.select("a img").attr("abs:src")
        element.select("a:has(h2)").let {
            manga.title = it.text().substringBefore(" / ").substringBefore(" №")
            manga.setUrlWithoutDomain(it.attr("href"))
        }

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.small:contains(>)"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.select("table.news_pic2").first()!!
        manga.title = document.select("h1").first()!!.text().substringBefore(" / ").substringBefore(" №")
        manga.author = infoElement.select("a[href*=mangaka]").text()
        manga.genre = infoElement.select("div.tag-links a").joinToString { it.text() }
        manga.description = document.select(".description").text()
        manga.thumbnail_url = document.selectFirst("meta[property=og:image]")!!.attr("abs:content")

        return manga
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListSelector() = popularMangaSelector()

    override fun chapterListParse(response: Response): List<SChapter> = mutableListOf<SChapter>().apply {
        val document = response.asJsoup()

        val allPageElement = document.select("td.button a:contains(Все главы)")

        if (allPageElement.isEmpty()) {
            add(chapterFromSinglePage(document, response.request.url))
        } else {
            var pageListDocument: Document
            val pageListLink = allPageElement.attr("href")
            client.newCall(
                GET(baseUrl + pageListLink, headers),
            ).execute().run {
                if (!isSuccessful) {
                    close()
                    throw Exception("HTTP error $code")
                }
                pageListDocument = this.asJsoup()
            }
            if (pageListDocument.select(chapterListSelector()).isEmpty()) {
                add(chapterFromSinglePage(document, response.request.url))
            } else {
                pageListDocument.select(chapterListSelector())
                    .forEach {
                        add(chapterFromElement(it))
                    }
            }
        }
    }

    private fun chapterFromSinglePage(document: Document, responseUrl: HttpUrl): SChapter = SChapter.create().apply {
        val chapterName = document.select("table td.bg_style1 h1").text()
        val chapterUrl = responseUrl.toString()
        setUrlWithoutDomain(chapterUrl)
        if (url.contains(baseUrl)) {
            url = url.replace(baseUrl, "")
        }
        name = "$chapterName Сингл"
        scanlator = document.select("table.news_pic2 a[href*=perevod]").text()
        date_upload = document.select("table.news_pic2:has(a[href*=perevod]) span.small2:not(:contains(тыс))").first()!!.text().replace("Май", "Мая").let {
            try {
                dateParseRu.parse(it)?.time ?: 0L
            } catch (e: Exception) {
                0
            }
        }
        chapter_number = 0F
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val nameAndUrl = element.select("tr[valign=top] a:has(h2)")
        name = nameAndUrl.select("h2").text()
        setUrlWithoutDomain(nameAndUrl.attr("abs:href"))
        if (url.contains(baseUrl)) {
            url = url.replace(baseUrl, "")
        }
        val informBlock = element.select("tr[valign=top] td[align=left]")
        scanlator = informBlock.select("a[href*=perevod]").text()
        date_upload = informBlock.select("span.small2")
            .text().replace("Май", "Мая").let { textDate ->
                try {
                    dateParseRu.parse(textDate)?.time ?: 0L
                } catch (e: Exception) {
                    0
                }
            }
        chapter_number = name.substringAfter("№").substringBefore(" ").toFloatOrNull() ?: -1f
    }

    override fun pageListParse(response: Response): List<Page> = mutableListOf<Page>().apply {
        response.asJsoup().select("div.gallery-item img").mapIndexed { index, img ->
            add(Page(index, imageUrl = img.attr("abs:data-src")))
        }
        if (size == 0 && cookieManager.getCookie(baseUrl).contains("fusion_user").not()) {
            throw Exception("Страницы не найдены. Возможно необходима авторизация в WebView")
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException()

    private class Genre(name: String, val id: String = name.replace(' ', '_')) : Filter.CheckBox(name.replaceFirstChar { it.uppercaseChar() })
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Тэги", genres)
    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Дата", "Просмотры", "Лайки"),
        Selection(1, false),
    )

    override fun getFilterList() = FilterList(
        OrderBy(),
        GenreList(getGenreList()),
    )

    private fun getGenreList() = listOf(
        Genre("анал"),
        Genre("без цензуры"),
        Genre("беременные"),
        Genre("близняшки"),
        Genre("большие груди"),
        Genre("в бассейне"),
        Genre("в больнице"),
        Genre("в ванной"),
        Genre("в общественном месте"),
        Genre("в первый раз"),
        Genre("в транспорте"),
        Genre("в туалете"),
        Genre("гарем"),
        Genre("гипноз"),
        Genre("горничные"),
        Genre("горячий источник"),
        Genre("групповой секс"),
        Genre("драма"),
        Genre("запредельное"),
        Genre("золотой дождь"),
        Genre("зрелые женщины"),
        Genre("идолы"),
        Genre("извращение"),
        Genre("измена"),
        Genre("имеют парня"),
        Genre("клизма"),
        Genre("колготки"),
        Genre("комиксы"),
        Genre("комиксы 3D"),
        Genre("косплей"),
        Genre("мастурбация"),
        Genre("мерзкий мужик"),
        Genre("много спермы"),
        Genre("молоко"),
        Genre("монстры"),
        Genre("на камеру"),
        Genre("на природе"),
        Genre("обычный секс"),
        Genre("огромный член"),
        Genre("пляж"),
        Genre("подглядывание"),
        Genre("принуждение"),
        Genre("продажность"),
        Genre("пьяные"),
        Genre("рабыни"),
        Genre("романтика"),
        Genre("с ушками"),
        Genre("секс игрушки"),
        Genre("спящие"),
        Genre("страпон"),
        Genre("студенты"),
        Genre("суккуб"),
        Genre("тентакли"),
        Genre("толстушки"),
        Genre("трапы"),
        Genre("ужасы"),
        Genre("униформа"),
        Genre("учитель и ученик"),
        Genre("фемдом"),
        Genre("фетиш"),
        Genre("фурри"),
        Genre("футанари"),
        Genre("футфетиш"),
        Genre("фэнтези"),
        Genre("цветная"),
        Genre("чикан"),
        Genre("чулки"),
        Genre("шимейл"),
        Genre("эксгибиционизм"),
        Genre("юмор"),
        Genre("юри"),
        Genre("ahegao"),
        Genre("BDSM"),
        Genre("ganguro"),
        Genre("gender bender"),
        Genre("megane"),
        Genre("mind break"),
        Genre("monstergirl"),
        Genre("netorare"),
        Genre("nipple penetration"),
        Genre("titsfuck"),
        Genre("x-ray"),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = DOMAIN_TITLE
            setDefaultValue(DOMAIN_DEFAULT)
            dialogTitle = DOMAIN_TITLE
            dialogMessage = "Default URL:\n\t$DOMAIN_DEFAULT"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Для смены домена необходимо перезапустить приложение с полной остановкой.", Toast.LENGTH_LONG).show()
                true
            }
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(DOMAIN_PREF, DOMAIN_DEFAULT)!!

    init {
        preferences.getString(DEFAULT_DOMAIN_PREF, null).let { defaultBaseUrl ->
            if (defaultBaseUrl != DOMAIN_DEFAULT) {
                preferences.edit()
                    .putString(DOMAIN_PREF, DOMAIN_DEFAULT)
                    .putString(DEFAULT_DOMAIN_PREF, DOMAIN_DEFAULT)
                    .apply()
            }
        }
    }

    companion object {
        private const val DOMAIN_PREF = "Домен"
        private const val DEFAULT_DOMAIN_PREF = "pref_default_domain"
        private const val DOMAIN_TITLE = "Домен"
        private const val DOMAIN_DEFAULT = "https://nude-moon.org"
    }
}
