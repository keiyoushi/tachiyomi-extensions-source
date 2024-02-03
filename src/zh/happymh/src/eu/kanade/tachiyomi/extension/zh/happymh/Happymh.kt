package eu.kanade.tachiyomi.extension.zh.happymh

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.zh.happymh.dto.ChapterListDto
import eu.kanade.tachiyomi.extension.zh.happymh.dto.PageListResponseDto
import eu.kanade.tachiyomi.extension.zh.happymh.dto.PopularResponseDto
import eu.kanade.tachiyomi.lib.randomua.PREF_KEY_CUSTOM_UA
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Happymh : HttpSource(), ConfigurableSource {
    override val name: String = "嗨皮漫画"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://m.happymh.com"
    override val client: OkHttpClient
    private val json: Json by injectLazy()

    init {
        val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
        val oldUa = preferences.getString("userAgent", null)
        if (oldUa != null) {
            val editor = preferences.edit().remove("userAgent")
            if (oldUa.isNotBlank()) editor.putString(PREF_KEY_CUSTOM_UA, oldUa)
            editor.apply()
        }
        client = network.cloudflareClient.newBuilder()
            .setRandomUserAgent(preferences.getPrefUAType(), preferences.getPrefCustomUA())
            .build()
    }

    // Popular

    // Requires login, otherwise result is the same as latest updates
    override fun popularMangaRequest(page: Int): Request {
        val header = headersBuilder().add("referer", "$baseUrl/latest").build()
        return GET("$baseUrl/apis/c/index?pn=$page&series_status=-1&order=views", header)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<PopularResponseDto>().data

        val items = data.items.map {
            SManga.create().apply {
                title = it.name
                url = it.url
                thumbnail_url = it.cover
            }
        }
        val hasNextPage = data.isEnd.not()

        return MangasPage(items, hasNextPage)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val header = headersBuilder().add("referer", "$baseUrl/latest").build()
        return GET("$baseUrl/apis/c/index?pn=$page&series_status=-1&order=last_date", header)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = FormBody.Builder().addEncoded("searchkey", query).build()
        val header = headersBuilder().add("referer", "$baseUrl/sssearch").build()
        return POST("$baseUrl/apis/m/ssearch", header, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return MangasPage(popularMangaParse(response).mangas, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("div.mg-property > h2.mg-title")!!.text()
        thumbnail_url = document.selectFirst("div.mg-cover > mip-img")!!.attr("abs:src")
        author = document.selectFirst("div.mg-property > p.mg-sub-title:nth-of-type(2)")!!.text()
        artist = author
        genre = document.select("div.mg-property > p.mg-cate > a").eachText().joinToString(", ")
        description = document.selectFirst("div.manga-introduction > mip-showmore#showmore")!!.text()
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val comicId = response.request.url.pathSegments.last()
        val document = response.asJsoup()
        val script = document.selectFirst("mip-data > script:containsData(chapterList)")!!.data()
        return json.decodeFromString<ChapterListDto>(script).chapterList.map {
            SChapter.create().apply {
                val chapterId = it.id
                url = "/v2.0/apis/manga/read?code=$comicId&cid=$chapterId&v=v2.13"
                name = it.chapterName
            }
        }
    }

    // Pages

    override fun pageListRequest(chapter: SChapter): Request {
        val url = baseUrl + chapter.url
        // Some chapters return 403 without this header
        val header = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", url)
            .build()
        return GET(url, header)
    }

    override fun pageListParse(response: Response): List<Page> {
        return response.parseAs<PageListResponseDto>().data.scans
            // If n == 1, the image is from next chapter
            .filter { it.n == 0 }
            .mapIndexed { index, it ->
                Page(index, "", it.url)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addRandomUAPreferenceToScreen(screen)
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromStream(it.body.byteStream())
    }
}
