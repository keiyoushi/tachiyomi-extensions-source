package eu.kanade.tachiyomi.multisrc.grouple

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.DecimalFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

abstract class GroupLe(
    override val name: String,
    override val baseUrl: String,
    final override val lang: String,
) : ConfigurableSource, ParsedHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString().contains(baseUrl) && (
                originalRequest.url.toString()
                    .contains("internal/redirect") or (response.code == 301)
                )
            ) {
                if (originalRequest.url.toString().contains("/list?")) {
                    throw IOException("Смените домен: Поисковик > Расширения > $name > ⚙\uFE0F")
                }
                throw IOException(
                    "URL серии изменился. Перенесите/мигрируйте с $name " +
                        "на $name (или смежный с GroupLe), чтобы список глав обновился",
                )
            }
            response
        }
        .build()

    private var uagent = preferences.getString(UAGENT_TITLE, UAGENT_DEFAULT)!!

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", uagent)
        add("Referer", baseUrl)
    }

    override fun popularMangaSelector() = "div.tile"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=rate&offset=${70 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=updated&offset=${70 * (page - 1)}", headers)

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url =
            element.select("img.lazy").first()?.attr("data-original")?.replace("_p.", ".")
        element.select("h3 > a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.nextLink"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url =
            "$baseUrl/search/advancedResults?offset=${50 * (page - 1)}".toHttpUrl()
                .newBuilder()
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return GET(url.toString().replace("=%3D", "="), headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select(".expandable").first()!!
        val rawCategory = infoElement.select("span.elem_category").text()
        val category = rawCategory.ifEmpty {
            "манга"
        }

        val ratingValue =
            infoElement.select(".rating-block").attr("data-score").toFloat() * 2
        val ratingValueOver =
            infoElement.select(".info-icon").attr("data-content").substringBeforeLast("/5</b><br/>")
                .substringAfterLast(": <b>").replace(",", ".").toFloat() * 2
        val ratingVotes =
            infoElement.select(".col-sm-6 .user-rating meta[itemprop=\"ratingCount\"]")
                .attr("content")
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }
        val rawAgeStop = when (
            val rawAgeValue =
                infoElement.select(".elem_limitation .element-link").first()?.text() ?: ""
        ) {
            "NC-17" -> "18+"
            "R18+" -> "18+"
            "R" -> "16+"
            "G" -> "16+"
            "PG" -> "16+"
            "PG-13" -> "12+"
            else -> rawAgeValue
        }
        val manga = SManga.create()
        manga.title = document.select(".names > .name").text()
        manga.author = infoElement.select("span.elem_author").first()?.text() ?: infoElement.select(
            "span.elem_screenwriter",
        ).first()?.text()
        manga.artist = infoElement.select("span.elem_illustrator").first()?.text()
        manga.genre = (
            "$category, $rawAgeStop, " + infoElement.select("p:contains(Жанры:) a, p:contains(Теги:) a")
                .joinToString { it.text() }
            ).split(", ")
            .filter { it.isNotEmpty() }.joinToString { it.trim().lowercase() }
        val altName = if (infoElement.select(".another-names").isNotEmpty()) {
            "Альтернативные названия:\n" + infoElement.select(".another-names").text() + "\n\n"
        } else {
            ""
        }
        manga.description =
            "$ratingStar $ratingValue[ⓘ$ratingValueOver] (голосов: $ratingVotes)\n$altName" + document.select(
            "div#tab-description  .manga-description",
        ).text()
        manga.status = when {
            (
                document.html()
                    .contains("Запрещена публикация произведения по копирайту") || document.html()
                    .contains("ЗАПРЕЩЕНА К ПУБЛИКАЦИИ НА ТЕРРИТОРИИ РФ!")
                ) && document.select("div.chapters").isEmpty() -> SManga.LICENSED
            infoElement.html().contains("<b>Сингл") -> SManga.COMPLETED
            else ->
                when (infoElement.selectFirst("span.badge:contains(выпуск)")?.text()) {
                    "выпуск продолжается" -> SManga.ONGOING
                    "выпуск начат" -> SManga.ONGOING
                    "выпуск завершён" -> if (infoElement.selectFirst("span.badge:contains(переведено)")?.text()?.isNotEmpty() == true) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
                    "выпуск приостановлен" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
        }
        manga.thumbnail_url = infoElement.select("img").attr("data-full")
        return manga
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return if (manga.status != SManga.LICENSED) {
            client.newCall(chapterListRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    chapterListParse(response, manga)
                }
        } else {
            Observable.error(java.lang.Exception("Лицензировано - Нет глав"))
        }
    }

    protected open fun getChapterSearchParams(document: Document): String {
        val scriptContent = document.selectFirst("script:containsData(user_hash)")?.data()
        val userHash = scriptContent?.let { USER_HASH_REGEX.find(it)?.groupValues?.get(1) }
        return userHash?.let { "?d=$it&mtr=true" } ?: "?mtr=true"
    }

    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val document = response.asJsoup()

        if (document.select(".user-avatar").isEmpty() &&
            document.title().run { contains("AllHentai") || contains("MintManga") || contains("МинтМанга") }
        ) {
            throw Exception("Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E")
        }

        val chapterSearchParams = getChapterSearchParams(document)

        return document.select(chapterListSelector()).map { chapterFromElement(it, manga, chapterSearchParams) }
    }

    override fun chapterListSelector() =
        "tr.item-row:has(td > a):has(td.date:not(.text-info))"

    private fun chapterFromElement(element: Element, manga: SManga, chapterSearchParams: String): SChapter {
        val urlElement = element.select("a.chapter-link").first()!!
        val chapterInf = element.select("td.item-title").first()!!
        val urlText = urlElement.text()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href") + chapterSearchParams)

        val translatorElement = urlElement.attr("title")

        chapter.scanlator = if (translatorElement.isNotBlank()) {
            translatorElement
                .replace("(Переводчик),", "&")
                .removeSuffix(" (Переводчик)")
        } else {
            ""
        }

        chapter.name = urlText.removeSuffix(" новое").trim()
        if (manga.title.length > 25) {
            for (word in manga.title.split(' ')) {
                chapter.name = chapter.name.removePrefix(word).trim()
            }
        }
        val dots = chapter.name.indexOf("…")
        val numbers = chapter.name.findAnyOf(IntRange(0, 9).map { it.toString() })?.first ?: 0

        if (dots in 0 until numbers) {
            chapter.name = chapter.name.substringAfter("…").trim()
        }

        chapter.chapter_number = chapterInf.attr("data-num").toFloat() / 10

        chapter.date_upload = element.select("td.d-none").last()?.text()?.let {
            if (it.isEmpty()) {
                0L
            } else {
                try {
                    SimpleDateFormat("dd.MM.yy", Locale.US).parse(it)?.time ?: 0L
                } catch (e: ParseException) {
                    SimpleDateFormat("dd/MM/yy", Locale.US).parse(it)?.time ?: 0L
                }
            }
        } ?: 0
        return chapter
    }

    override fun chapterFromElement(element: Element): SChapter {
        throw UnsupportedOperationException()
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val extra = Regex("""\s*([0-9]+\sЭкстра)\s*""")
        val single = Regex("""\s*Сингл\s*""")
        when {
            extra.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Экстра").trim().isEmpty()) {
                    chapter.name = chapter.name.replaceFirst(
                        " ",
                        " - " + DecimalFormat("#,###.##").format(chapter.chapter_number)
                            .replace(",", ".") + " ",
                    )
                }
            }

            single.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Сингл").trim().isEmpty()) {
                    chapter.name = DecimalFormat("#,###.##").format(chapter.chapter_number)
                        .replace(",", ".") + " " + chapter.name
                }
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val html = document.html()

        if (document.select(".user-avatar").isEmpty() &&
            document.title().run { contains("AllHentai") || contains("MintManga") || contains("МинтМанга") }

        ) {
            throw Exception("Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E")
        }

        val readerMark = when {
            html.contains("rm_h.readerDoInit([") -> "rm_h.readerDoInit(["
            html.contains("rm_h.readerInit([") -> "rm_h.readerInit(["
            !response.request.url.toString().contains(baseUrl) -> {
                throw Exception("Не удалось загрузить главу. Url: ${response.request.url}")
            }
            else -> {
                if (html.contains("hprocessed") || html.contains("purchase-form")) {
                    throw Exception("Эта глава платная. Используйте сайт, чтобы купить и прочитать ее.")
                }
                throw Exception("Дизайн сайта обновлен, для дальнейшей работы необходимо обновление дополнения")
            }
        }

        val beginIndex = html.indexOf(readerMark)
        val endIndex = html.indexOf(");", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)

        val p = Pattern.compile("'.*?','.*?',\".*?\"")
        val m = p.matcher(trimmedHtml)

        val pages = mutableListOf<Page>()

        var i = 0
        while (m.find()) {
            val urlParts = m.group().replace("[\"\']+".toRegex(), "").split(',')
            var url = if (urlParts[1].isEmpty() && urlParts[2].startsWith("/static/")) {
                baseUrl + urlParts[2]
            } else {
                if (urlParts[1].endsWith("/manga/")) {
                    urlParts[0] + urlParts[2]
                } else {
                    urlParts[1] + urlParts[0] + urlParts[2]
                }
            }
            if (!url.contains("://")) {
                url = "https:$url"
            }
            if (url.contains("one-way.work")) {
                // domain that does not need a token
                url = url.substringBefore("?")
            }
            pages.add(Page(i++, "", url.replace("//resh", "//h")))
        }
        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
            add("Referer", baseUrl)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl/$id", headers)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$realQuery"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(UAGENT_TITLE, UAGENT_DEFAULT, uagent))
    }

    private fun androidx.preference.PreferenceScreen.editTextPreference(
        title: String,
        default: String,
        value: String,
    ): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value
            this.setDefaultValue(default)
            dialogTitle = title
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(
                        context,
                        "Для смены User-Agent необходимо перезапустить приложение с полной остановкой.",
                        Toast.LENGTH_LONG,
                    ).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }

    companion object {
        private const val UAGENT_TITLE = "User-Agent(для некоторых стран)"
        private const val UAGENT_DEFAULT = "arora"
        const val PREFIX_SLUG_SEARCH = "slug:"
        private val USER_HASH_REGEX = "user_hash.+'(.+)'".toRegex()
    }
}
