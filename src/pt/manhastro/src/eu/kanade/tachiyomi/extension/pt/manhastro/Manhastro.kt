package eu.kanade.tachiyomi.extension.pt.manhastro

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Manhastro : HttpSource() {

    // Moved from Madara
    override val versionId = 2

    override val name = "Manhastro"

    override val lang = "pt-BR"

    override val baseUrl = "https://manhastro.com"

    private val apiUrl = "https://api.manhastro.com"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .ignoreAllSSLErrors()
        .build()

    // ============================= Popular ==================================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$apiUrl/rank/diario", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<List<MangaDto>>()

        val mangas = dto.map {
            SManga.create().apply {
                title = it.titulo
                thumbnail_url = "https://" + it.imagem
                setUrlWithoutDomain("/dados/${it.manga_id}")
                description = it.descricao.trim()
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================= Latest ===================================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$apiUrl/lancamentos", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<List<MangaDto>>()

        val mangas = dto.map {
            SManga.create().apply {
                title = it.titulo
                thumbnail_url = "https://" + it.imagem
                setUrlWithoutDomain("/dados/${it.manga_id}")
                description = it.descricao.trim()
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================= Search ===================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/dados".toHttpUrl().newBuilder()
            .addQueryParameter("titulo", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<MangaResponseDto>()
        val query = response.request.url.queryParameter("titulo")?.lowercase() ?: ""

        val mangas = dto.data.filter { it.titulo.lowercase().contains(query) }
            .map {
                SManga.create().apply {
                    title = it.titulo
                    thumbnail_url = "https://" + it.imagem
                    setUrlWithoutDomain("/dados/${it.manga_id}")
                    description = it.descricao.trim()
                }
            }

        return MangasPage(mangas, false)
    }

    // ============================= Details ==================================

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = "$apiUrl${manga.url}".toHttpUrl().pathSegments.last()
        val url = "$apiUrl/dados".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", mangaId)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val dto = response.parseAs<MangaResponseDto>()

        val mangaId = response.request.url.queryParameter("manga_id")?.toIntOrNull()
            ?: throw Exception("ID do mangá não encontrado na URL")

        val manga = dto.data.find { it.manga_id == mangaId }

        if (manga != null) {
            title = manga.titulo
            thumbnail_url = "https://" + manga.imagem
            description = manga.descricao.trim()
            genre = manga.getGeneros().joinToString(", ")
        } else {
            throw Exception("Mangá não encontrado")
        }
    }

    // ============================= Chapters =================================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = "$apiUrl${manga.url}".toHttpUrl().pathSegments.last()
        val url = "$apiUrl/dados".toHttpUrl().newBuilder()
            .addPathSegment(mangaId)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapterNumberRegex = """\d+(\.\d+)?""".toRegex()

        return response.parseAs<CapituloDto>().capitulos.map {
            SChapter.create().apply {
                name = it.capitulo_nome
                it.capitulo_nome?.let { nome ->
                    chapter_number = chapterNumberRegex
                        .find(nome)
                        ?.value
                        ?.toFloatOrNull() ?: 0f
                }
                setUrlWithoutDomain("/dados/${it.capitulo_id}")
                date_upload = it.capitulo_data.toDate()
            }
        }.sortedWith(
            compareByDescending<SChapter> { it.chapter_number.toInt() }
                .thenByDescending { it.chapter_number },
        )
    }

    // ============================= Pages ====================================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = "$apiUrl${chapter.url}".toHttpUrl().pathSegments.last()
        return GET("$apiUrl/paginas/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = json.decodeFromString<PaginaDto>(response.body!!.string())

        return result.paginas.entries
            .sortedBy { it.key.toInt() }
            .mapIndexed { index, entry ->
                Page(index = index, imageUrl = entry.value)
            }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ============================= Utilities ====================================

    private inline fun <reified T> Response.parseAs(): T = use {
        return json.decodeFromStream(body.byteStream())
    }

    private fun String.toDate() =
        try { dateFormat.parse(this)!!.time } catch (_: Exception) { 0L }

    companion object {
        @SuppressLint("SimpleDateFormat")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
