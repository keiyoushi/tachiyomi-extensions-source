package eu.kanade.tachiyomi.extension.all.akuma

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException

class Akuma(
    override val lang: String,
    private val akumaLang: String,
) : ParsedHttpSource() {

    override val name = "Akuma"

    override val baseUrl = "https://akuma.moe"

    override val supportsLatest = false

    private var nextHash: String? = null

    private var storedToken: String? = null

    private val ddosGuardIntercept = DDosGuardInterceptor(network.client)

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(ddosGuardIntercept)
        .addInterceptor(::tokenInterceptor)
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun tokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method == "POST" && request.header("X-CSRF-TOKEN") == null) {
            val modifiedRequest = request.newBuilder()
                .addHeader("X-Requested-With", "XMLHttpRequest")

            val token = getToken()
            val response = chain.proceed(
                modifiedRequest
                    .addHeader("X-CSRF-TOKEN", token)
                    .build(),
            )

            if (!response.isSuccessful && response.code == 419) {
                response.close()
                storedToken = null // reset the token
                val newToken = getToken()
                return chain.proceed(
                    modifiedRequest
                        .addHeader("X-CSRF-TOKEN", newToken)
                        .build(),
                )
            }

            return response
        }

        return chain.proceed(request)
    }

    private fun getToken(): String {
        if (storedToken.isNullOrEmpty()) {
            val request = GET(baseUrl, headers)
            val response = client.newCall(request).execute()

            val document = response.asJsoup()
            val token = document.select("head meta[name*=csrf-token]")
                .attr("content")

            if (token.isEmpty()) {
                throw IOException("Unable to find CSRF token")
            }

            storedToken = token
        }

        return storedToken!!
    }

    override fun popularMangaRequest(page: Int): Request {
        val payload = FormBody.Builder()
            .add("view", "3")
            .build()

        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()

        if (page == 1) {
            nextHash = null
        } else {
            url.addQueryParameter("cursor", nextHash)
        }
        if (lang != "all") {
            // append like `q=language:english$`
            url.addQueryParameter("q", "language:$akumaLang$")
        }

        return POST(url.toString(), headers, payload)
    }

    override fun popularMangaSelector() = ".post-loop li"
    override fun popularMangaNextPageSelector() = ".page-item a[rel*=next]"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        if (document.text().contains("Max keywords of 3 exceeded.")) {
            throw Exception("Login required for more than 3 filters")
        } else if (document.text().contains("Max keywords of 8 exceeded.")) throw Exception("Only max of 8 filters are allowed")

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }

        val nextUrl = document.select(popularMangaNextPageSelector()).first()?.attr("href")

        nextHash = nextUrl?.toHttpUrlOrNull()?.queryParameter("cursor")

        return MangasPage(mangas, !nextHash.isNullOrEmpty())
    }

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.select("a").attr("href"))
            title = element.select(".overlay-title").text()
            thumbnail_url = element.select("img").attr("abs:src")
        }
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID)) {
            val url = "/g/${query.substringAfter(PREFIX_ID)}"
            val manga = SManga.create().apply { this.url = url }
            fetchMangaDetails(manga).map {
                MangasPage(listOf(it.apply { this.url = url }), false)
            }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val request = popularMangaRequest(page)

        val finalQuery: MutableList<String> = mutableListOf(query)

        if (lang != "all") {
            finalQuery.add("language: $akumaLang$")
        }
        filters.forEach { filter ->
            when (filter) {
                is TextFilter -> {
                    if (filter.state.isNotEmpty()) {
                        finalQuery.addAll(
                            filter.state.split(",").map {
                                (if (it.trim().startsWith("-")) "-" else "") + "${filter.tag}:\"${it.trim().replace("-", "")}\""
                            },
                        )
                    }
                }
                is OptionFilter -> {
                    if (filter.state > 0) finalQuery.add("opt:${filter.getValue()}")
                }
                is CategoryFilter -> {
                    filter.state.forEach {
                        when {
                            it.isIncluded() -> finalQuery.add("category:\"${it.name}\"")
                            it.isExcluded() -> finalQuery.add("-category:\"${it.name}\"")
                        }
                    }
                }
                else -> {}
            }
        }

        val url = request.url.newBuilder()
            .setQueryParameter("q", finalQuery.joinToString(" "))
            .build()

        return request.newBuilder()
            .url(url)
            .build()
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document) = with(document) {
        SManga.create().apply {
            title = select(".entry-title").text()
            thumbnail_url = select(".img-thumbnail").attr("abs:src")

            author = select(".group~.value").eachText().joinToString()
            artist = select(".artist~.value").eachText().joinToString()

            val characters = select(".character~.value").eachText()
            val parodies = select(".parody~.value").eachText()
            val males = select(".male~.value")
                .map { "${it.text()} ♂" }
            val females = select(".female~.value")
                .map { "${it.text()} ♀" }
            val others = select(".other~.value")
                .map { "${it.text()} ◊" }
            // show all in tags for quickly searching

            genre = (males + females + others).joinToString()
            description = buildString {
                append(
                    "Full English and Japanese title: \n",
                    select(".entry-title").text(),
                    "\n",
                    select(".entry-title+span").text(),
                    "\n\n",
                )

                // translated should show up in the description
                append("Language: ", select(".language~.value").text(), "\n")
                append("Pages: ", select(".pages .value").text(), "\n")
                append("Upload Date: ", select(".date .value>time").text(), "\n")
                append("Categories: ", selectFirst(".info-list .value")?.text() ?: "Unknown", "\n\n")

                // show followings for easy to reference
                parodies.takeIf { it.isNotEmpty() }?.let { append("Parodies: ", parodies.joinToString(), "\n") }
                characters.takeIf { it.isNotEmpty() }?.let { append("Characters: ", characters.joinToString(), "\n") }
            }
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.UNKNOWN
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.just(
            listOf(
                SChapter.create().apply {
                    url = "${manga.url}/1"
                    name = "Chapter"
                },
            ),
        )
    }

    override fun pageListParse(document: Document): List<Page> {
        val totalPages = document.select(".nav-select option").last()
            ?.attr("value")?.toIntOrNull() ?: return emptyList()

        val url = document.location().substringBeforeLast("/")

        val pageList = mutableListOf<Page>()

        for (i in 1..totalPages) {
            pageList.add(Page(i, "$url/$i"))
        }

        return pageList
    }

    override fun imageUrlParse(document: Document): String {
        return document.select(".entry-content img").attr("abs:src")
    }

    override fun getFilterList(): FilterList = getFilters()

    companion object {
        const val PREFIX_ID = "id:"
    }

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()
    override fun chapterListSelector() = throw UnsupportedOperationException()
}
