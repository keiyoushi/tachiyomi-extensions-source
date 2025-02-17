package eu.kanade.tachiyomi.extension.de.mangatube

import Manga
import android.annotation.SuppressLint
import eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper.ChapterWrapper
import eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper.ChaptersWrapper
import eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper.MangaWrapper
import eu.kanade.tachiyomi.extension.de.mangatube.dio.wrapper.MangasWrapper
import eu.kanade.tachiyomi.extension.de.mangatube.util.BaseResponse
import eu.kanade.tachiyomi.extension.de.mangatube.util.Genre
import eu.kanade.tachiyomi.extension.de.mangatube.util.MangaTubeHelper
import eu.kanade.tachiyomi.extension.de.mangatube.util.ResponseInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

class MangaTube : HttpSource() {

    override val name = "Manga Tube"

    override val baseUrl = "https://manga-tube.me"

    override val lang = "de"

    override val supportsLatest = true

    @SuppressLint("SimpleDateFormat")
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    private val mangas: LinkedHashMap<String, Manga> = LinkedHashMap()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        prettyPrint = true
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .addInterceptor(ResponseInterceptor())
        .build()

    override fun imageUrlParse(response: Response): String = ""

    override fun getMangaUrl(manga: SManga): String {
        if (!manga.url.startsWith(baseUrl)) {
            return "$baseUrl${manga.url}"
        }
        return manga.url
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return chapter.url
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/manga/search?page=$page&query=$query"

        return GET(url)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()

        val res: BaseResponse<List<Manga>> = json.decodeFromString(body)

        val mangaList = res.data.map { manga ->
            mangas[manga.title] = manga
            SManga.create().apply {
                title = manga.title
                url = "$baseUrl${manga.url}"
                thumbnail_url = manga.cover
                status = MangaTubeHelper.mangaStatus(manga.status)
            }
        }

        return MangasPage(mangaList, res.pagination!!.lastPage())
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/home/top-manga"

        return GET(url)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()

        val res: BaseResponse<MangasWrapper> = json.decodeFromString(body)

        val mangaList = res.data.manga.map { manga ->
            mangas[manga.title] = manga
            SManga.create().apply {
                title = manga.title
                url = "$baseUrl${manga.url}"
                thumbnail_url = manga.cover
                genre = manga.genre.map { genre -> Genre.fromId(genre)!! }
                    .joinToString(", ") { genre -> genre.displayName }
                status = MangaTubeHelper.mangaStatus(manga.status)
            }
        }

        return MangasPage(mangaList, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/home/new-manga"

        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string()

        val res: BaseResponse<MangasWrapper> = json.decodeFromString(body)

        val mangaList = res.data.manga.map { manga ->
            mangas[manga.title] = manga
            SManga.create().apply {
                title = manga.title
                url = "$baseUrl${manga.url}"
                thumbnail_url = manga.cover
                genre = manga.genre.map { genre -> Genre.fromId(genre)!! }
                    .joinToString(", ") { genre -> genre.displayName }
                status = MangaTubeHelper.mangaStatus(manga.status)
                description = manga.description
            }
        }

        return MangasPage(mangaList, false)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl/api/manga/${mangas[manga.title]!!.id}"

        return GET(url)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        val res: BaseResponse<MangaWrapper> = json.decodeFromString(body)

        val manga: Manga = res.data.manga

        mangas[manga.title] = manga

        return SManga.create().apply {
            title = manga.title
            author = manga.author.joinToString(", ") { author -> author.name }
            url = "$baseUrl${manga.url}"
            artist = manga.artist.joinToString(", ") { artist -> artist.name }
            description = manga.description
            thumbnail_url = manga.cover
            genre = manga.genre.map { genre -> Genre.fromId(genre)!! }
                .joinToString(", ") { genre -> genre.displayName }
            status = MangaTubeHelper.mangaStatus(manga.status)
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/api/manga/${mangas[manga.title]!!.slug}/chapters"

        return GET(url)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()

        val res: BaseResponse<ChaptersWrapper> = json.decodeFromString(body)

        val chapterList = res.data.chapters.map { chapter ->
            SChapter.create().apply {
                url = "$baseUrl${chapter.readerURL}"
                name = chapter.name.ifBlank { "Chapter ${chapter.number}" }
                date_upload = dateFormat.parse(chapter.publishedAt)!!.time
                chapter_number = chapter.number.toFloat()
                scanlator = chapter.volume.toString()
            }
        }

        return chapterList
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val split = chapter.url.split("/")
        val slug = split[split.size - 4]
        val id = split[split.size - 2]

        val url = "$baseUrl/api/manga/$slug/chapter/$id"

        return GET(url)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        val res: BaseResponse<ChapterWrapper> = json.decodeFromString(body)

        val mangaList = res.data.chapter.pages.map { page ->
            Page(page.index, page.url, page.altSource)
        }

        return mangaList
    }
}
