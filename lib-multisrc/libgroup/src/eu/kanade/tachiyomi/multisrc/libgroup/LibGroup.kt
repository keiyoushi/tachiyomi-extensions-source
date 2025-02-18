package eu.kanade.tachiyomi.multisrc.libgroup

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class LibGroup(
    override val name: String,
    override val baseUrl: String,
    final override val lang: String,
) : ConfigurableSource, HttpSource() {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
            .migrateOldImageServer()
    }

    override val supportsLatest = true

    private var bearerToken: String? = null

    private var userId: Int? = null

    abstract val siteId: Int // Important in api calls

    private val apiDomain: String = preferences.getString(API_DOMAIN_PREF, API_DOMAIN_DEFAULT).toString()

    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(3)
            .rateLimitHost(apiDomain.toHttpUrl(), 1)
            .rateLimitHost(baseUrl.toHttpUrl(), 1)
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(::checkForToken)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 419) {
                    throw IOException("HTTP error ${response.code}. Проверьте сайт. Для завершения авторизации необходимо перезапустить приложение с полной остановкой.")
                }
                if (response.code == 404) {
                    throw IOException("HTTP error ${response.code}. Проверьте сайт. Попробуйте авторизоваться через WebView\uD83C\uDF0E︎ и обновите список. Для завершения авторизации может потребоваться перезапустить приложение с полной остановкой.")
                }
                return@addInterceptor response
            }
            .build()
    }

    override fun headersBuilder() = Headers.Builder().apply {
        add("Accept", "text/html,application/json,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        add("Referer", baseUrl)
        add("Site-Id", siteId.toString())
    }

    private fun imageHeader() = Headers.Builder().apply {
        add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        add("Referer", baseUrl)
    }.build()

    private var _constants: Constants? = null
    private fun getConstants(): Constants? {
        if (_constants == null) {
            try {
                _constants = client.newCall(
                    GET("$apiDomain/api/constants?fields[]=genres&fields[]=tags&fields[]=types&fields[]=scanlateStatus&fields[]=status&fields[]=format&fields[]=ageRestriction&fields[]=imageServers", headers),
                ).execute().parseAs<Data<Constants>>().data
                return _constants
            } catch (ex: Exception) {
                Log.d("LibGroup", "Error getting constants: $ex")
            }
        }
        return _constants
    }

    private fun checkForToken(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
        val url = chain.request().url.toString()
        if (url.contains(apiDomain) && !url.contains("/api/auth/me")) {
            if (bearerToken.isNullOrBlank()) {
                val token = loadToken()
                if (token != null) {
                    bearerToken = token.getToken()
                    userId = token.getUserId()
                } else {
                    bearerToken = "none"
                }
            }
            if (bearerToken != "none") {
                req.apply {
                    addHeader("Authorization", bearerToken.orEmpty())
                }
            }
        }
        return chain.proceed(req.build())
    }

    @SuppressLint("ApplySharedPref")
    private fun loadToken(): AuthToken? {
        try {
            var token = preferences.getString(TOKEN_STORE, "")!!.parseAs<AuthToken>()
            if (token.isExpired() || !isUserTokenValid(token.getToken())) {
                val refreshedToken: AuthToken? = refreshToken()
                if (refreshedToken != null) {
                    val str = json.encodeToString(refreshedToken)
                    preferences.edit().putString(TOKEN_STORE, str).commit()
                    token = refreshedToken
                }
            }
            return token
        } catch (ex: SerializationException) {
            val refreshedToken: AuthToken? = refreshToken()
            if (refreshedToken != null) {
                val str = json.encodeToString(refreshedToken)
                preferences.edit().putString(TOKEN_STORE, str).commit()
                return refreshedToken
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("NAME_SHADOWING")
    private fun refreshToken(): AuthToken? {
        val latch = CountDownLatch(1)
        var returnValue: AuthToken? = null
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(Injekt.get<Application>())
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val view = view!!
                    val script = "javascript:localStorage['auth']"
                    view.evaluateJavascript(script) {
                        view.stopLoading()
                        view.destroy()
                        if (!it.isNullOrBlank() && it != "null") {
                            val str: String = if (it.first() == '"' && it.last() == '"') {
                                it.substringAfter("\"").substringBeforeLast("\"")
                                    .replace("\\", "")
                            } else {
                                it.replace("\\", "")
                            }
                            str.parseAs<AuthToken>().let { auth ->
                                if (auth.isValid()) {
                                    returnValue = auth
                                }
                            }
                        }
                        latch.countDown()
                    }
                }
            }
            webView.loadUrl(baseUrl)
        }
        latch.await(20, TimeUnit.SECONDS)

        return returnValue
    }

    private fun isUserTokenValid(token: String): Boolean {
        val headers = Headers.Builder().apply {
            add("Accept", "application/json")
            add("Authorization", token)
        }.build()
        client.newCall(GET("$apiDomain/api/auth/me", headers)).execute().also { response ->
            return when (response.code) {
                401 -> throw Exception("Попробуйте авторизоваться через WebView\uD83C\uDF0E\uFE0E. Для завершения авторизации может потребоваться перезапустить приложение с полной остановкой.")
                else -> true
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/ru/manga${manga.url}"
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiDomain/api/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiDomain/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("site_id[]", siteId.toString())
            .addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<MangasPageDto>()
        val popularMangas = data.mapToSManga(isEng())
        if (popularMangas.isNotEmpty()) {
            return MangasPage(popularMangas, data.meta.hasNextPage)
        }
        return MangasPage(emptyList(), false)
    }

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        // throw exception if old url
        if (!manga.url.contains("--")) throw Exception(urlChangedError(name))

        val url = "$apiDomain/api/manga${manga.url}".toHttpUrl().newBuilder()
            .addQueryParameter("fields[]", "eng_name")
            .addQueryParameter("fields[]", "otherNames")
            .addQueryParameter("fields[]", "summary")
            .addQueryParameter("fields[]", "rate")
            .addQueryParameter("fields[]", "genres")
            .addQueryParameter("fields[]", "tags")
            .addQueryParameter("fields[]", "teams")
            .addQueryParameter("fields[]", "authors")
            .addQueryParameter("fields[]", "publisher")
            .addQueryParameter("fields[]", "userRating")
            .addQueryParameter("fields[]", "manga_status_id")
            .addQueryParameter("fields[]", "status_id")
            .addQueryParameter("fields[]", "artists")

        return GET(url.build(), headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<Data<Manga>>().data.toSManga(isEng())

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservable().doOnNext { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) throw Exception("HTTP error ${response.code}. Для просмотра 18+ контента необходима авторизация через WebView\uD83C\uDF0E︎") else throw Exception("HTTP error ${response.code}")
                }
            }
            .map { response ->
                mangaDetailsParse(response)
            }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        // throw exception if old url
        if (!manga.url.contains("--")) throw Exception(urlChangedError(name))

        return GET("$apiDomain/api/manga${manga.url}/chapters", headers)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slugUrl = chapter.url.substringAfter("/").substringBefore("/")
        val volume = chapter.url.substringAfter("volume=").substringBefore("&")
        val number = chapter.url.substringAfter("number=").substringBefore("&")
        val branchId = chapter.url.substringAfter("branch_id=", "").substringBefore("&")
        val branchStr = if (branchId.isNotBlank()) "&bid=$branchId" else ""
        val userStr = if (userId != null) "&ui=$userId" else ""

        return "$baseUrl/ru/$slugUrl/read/v$volume/c$number?$branchStr$userStr"
    }

    private fun getDefaultBranch(id: String): List<Branch> =
        client.newCall(GET("$apiDomain/api/branches/$id", headers)).execute().parseAs<Data<List<Branch>>>().data

    override fun chapterListParse(response: Response): List<SChapter> {
        val slugUrl = response.request.url.toString().substringAfter("manga/").substringBefore("/chapters")
        val chaptersData = response.parseAs<Data<List<Chapter>>>()
            .also { if (it.data.isEmpty()) throw Exception("Нет глав") }
        val sortingList = preferences.getString(SORTING_PREF, "ms_mixing")
        val defaultBranchId = if (sortingList == "ms_mixing" && chaptersData.data.getBranchCount() > 1) {
            runCatching { getDefaultBranch(slugUrl.substringBefore("-")).first().id }.getOrNull()
        } else {
            null
        }

        return chaptersData.data.flatMap { chapter ->
            when {
                chapter.branchesCount > 1 && sortingList == "ms_mixing" -> {
                    listOf(
                        chapter.branches
                            .firstOrNull { it.branchId == defaultBranchId }
                            ?.let { chapter.toSChapter(slugUrl, defaultBranchId, isScanUser()) }
                            ?: chapter.toSChapter(slugUrl, chapter.branches.first().branchId, isScanUser()),
                    )
                }
                chapter.branchesCount > 1 && sortingList == "ms_combining" -> {
                    chapter.branches.map { branch ->
                        chapter.toSChapter(slugUrl, branch.branchId, isScanUser())
                    }
                }
                else -> listOf(chapter.toSChapter(slugUrl, isScanUser = isScanUser()))
            }
        }.reversed()
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        if (manga.status == SManga.LICENSED) {
            throw Exception("Лицензировано - Нет глав")
        }
        return client.newCall(chapterListRequest(manga))
            .asObservable().doOnNext { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) throw Exception("HTTP error ${response.code}. Для просмотра 18+ контента необходима авторизация через WebView\uD83C\uDF0E︎") else throw Exception("HTTP error ${response.code}")
                }
            }
            .map { response ->
                chapterListParse(response)
            }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        // throw exception if old url
        if (!chapter.url.contains("--")) throw Exception(urlChangedError(name))

        return GET("$apiDomain/api/manga${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapter = response.parseAs<Data<Pages>>().data.toPageList().toMutableList()
        chapter.sortBy { it.index }
        return chapter
    }

    private fun checkImage(url: String): Boolean {
        val getUrlHead = Request.Builder().url(url).head().headers(imageHeader()).build()
        val response = client.newCall(getUrlHead).execute()
        return response.isSuccessful && (response.header("content-length", "0")?.toInt()!! > 600)
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        if (page.imageUrl != null) {
            return Observable.just(page.imageUrl)
        }
        if (isServer() == "auto") {
            for (serverApi in IMG_SERVERS.slice(1 until IMG_SERVERS.size)) {
                val server = getConstants()?.getServer(serverApi, siteId)?.url
                val imageUrl = "$server${page.url}"
                if (checkImage(imageUrl)) {
                    return Observable.just(imageUrl)
                }
            }
        }
        val server = getConstants()?.getServer(isServer(), siteId)?.url ?: throw Exception("Ошибка получения сервера изображений")
        return Observable.just("$server${page.url}")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, imageHeader())
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH).substringBefore("/").substringBefore("?")
            client.newCall(GET("$apiDomain/api/manga/$realQuery", headers))
                .asObservableSuccess()
                .map { response ->
                    val details = response.parseAs<Data<MangaShort>>().data.toSManga(isEng())
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

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiDomain/api/manga".toHttpUrl().newBuilder()
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", siteId.toString())
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is CategoryList -> filter.state.forEach { category ->
                    if (category.state) {
                        url.addQueryParameter("types[]", category.id)
                    }
                }
                is FormatList -> filter.state.forEach { format ->
                    if (format.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (format.isIncluded()) "format[]" else "format_exclude[]", format.id)
                    }
                }
                is StatusList -> filter.state.forEach { status ->
                    if (status.state) {
                        url.addQueryParameter("scanlate_status[]", status.id)
                    }
                }
                is StatusTitleList -> filter.state.forEach { title ->
                    if (title.state) {
                        url.addQueryParameter("status[]", title.id)
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (genre.isIncluded()) "genres[]" else "genres_exclude[]", genre.id)
                    }
                }
                is OrderBy -> {
                    if (filter.state!!.index == 0) {
                        url.addQueryParameter("sort_type", if (filter.state!!.ascending) "asc" else "desc")
                        return@forEach
                    }
                    val orderArray = arrayOf("", "rate_avg", "name", "rus_name", "views", "releaseDate", "created_at", "last_chapter_at", "chap_count")
                    url.addQueryParameter("sort_type", if (filter.state!!.ascending) "asc" else "desc")
                    url.addQueryParameter("sort_by", orderArray[filter.state!!.index])
                    if (orderArray[filter.state!!.index] == "rate") {
                        url.addQueryParameter("rate_min", "50")
                    }
                }
                is MyList -> filter.state.forEach { favorite ->
                    if (favorite.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (favorite.isIncluded()) "bookmarks[]" else "bookmarks_exclude[]", favorite.id)
                    }
                }
                is RequireChapters -> {
                    if (filter.state == 0) {
                        url.setQueryParameter("chap_count_min", "1")
                    }
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state) {
                        url.addQueryParameter("caution[]", age.id)
                    }
                }
                is TagList -> filter.state.forEach { tag ->
                    if (tag.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (tag.isIncluded()) "tags[]" else "tags_exclude[]", tag.id)
                    }
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Filters
    private class SearchFilter(name: String, val id: String) : Filter.TriState(name)
    private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

    private class CategoryList(categories: List<CheckFilter>) : Filter.Group<CheckFilter>("Тип", categories)
    private class FormatList(formats: List<SearchFilter>) : Filter.Group<SearchFilter>("Формат выпуска", formats)
    private class GenreList(genres: List<SearchFilter>) : Filter.Group<SearchFilter>("Жанры", genres)
    private class TagList(tags: List<SearchFilter>) : Filter.Group<SearchFilter>("Теги", tags)
    private class StatusList(statuses: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус перевода", statuses)
    private class StatusTitleList(titles: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус тайтла", titles)
    private class AgeList(ages: List<CheckFilter>) : Filter.Group<CheckFilter>("Возрастное ограничение", ages)
    private class MyList(favorites: List<SearchFilter>) : Filter.Group<SearchFilter>("Мои списки", favorites)

    override fun getFilterList(): FilterList {
        launchIO { getConstants() }

        val filters = mutableListOf<Filter<*>>()
        filters += listOf(
            OrderBy(),
        )

        filters += if (_constants != null) {
            listOf(
                CategoryList(getConstants()!!.getCategories(siteId).map { CheckFilter(it.label, it.id.toString()) }),
                FormatList(getConstants()!!.getFormats(siteId).map { SearchFilter(it.name, it.id.toString()) }),
                GenreList(getConstants()!!.getGenres(siteId).map { SearchFilter(it.name, it.id.toString()) }),
                TagList(getConstants()!!.getTags(siteId).map { SearchFilter(it.name, it.id.toString()) }),
                StatusList(getConstants()!!.getScanlateStatuses(siteId).map { CheckFilter(it.label, it.id.toString()) }),
                StatusTitleList(getConstants()!!.getTitleStatuses(siteId).map { CheckFilter(it.label, it.id.toString()) }),
                AgeList(getConstants()!!.getAgeRestrictions(siteId).map { CheckFilter(it.label, it.id.toString()) }),
            )
        } else {
            listOf(
                Filter.Header("Нажмите «Сбросить», чтобы попытаться отобразить дополнительные фильтры."),
            )
        }

        filters += listOf(
            MyList(getMyList()),
            RequireChapters(),
        )

        return FilterList(filters)
    }

    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Популярность", "Рейтинг", "Имя (A-Z)", "Имя (А-Я)", "Просмотры", "Дата релиза", "Дате добавления", "Дате обновления", "Кол-во глав"),
        Selection(0, false),
    )

    private fun getMyList() = listOf(
        SearchFilter("Читаю", "1"),
        SearchFilter("В планах", "2"),
        SearchFilter("Брошено", "3"),
        SearchFilter("Прочитано", "4"),
        SearchFilter("Любимые", "5"),
    )

    private class RequireChapters : Filter.Select<String>(
        "Только проекты с главами",
        arrayOf("Да", "Все"),
    )

    // Utils
    private inline fun <reified T> String.parseAs(): T = json.decodeFromString(this)

    private inline fun <reified T> Response.parseAs(): T = body.string().parseAs()

    private fun urlChangedError(sourceName: String): String =
        "URL серии изменился. Перенесите/мигрируйте с $sourceName " +
            "на $sourceName, чтобы список глав обновился."

    private val scope = CoroutineScope(Dispatchers.IO)
    private fun launchIO(block: () -> Unit) = scope.launch { block() }

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
        private const val SERVER_PREF = "MangaLibImageServer"
        private val IMG_SERVERS = arrayOf("auto", "main", "secondary", "compress")

        private const val SORTING_PREF = "MangaLibSorting"
        private const val SORTING_PREF_TITLE = "Способ выбора переводчиков"

        private const val IS_SCAN_USER = "ScanlatorUsername"
        private const val IS_SCAN_USER_TITLE = "Альтернативный переводчик"

        private const val TRANSLATORS_TITLE = "Чёрный список переводчиков\n(для красоты через «/» или с новой строки)"
        private const val TRANSLATORS_DEFAULT = ""

        private const val LANGUAGE_PREF = "MangaLibTitleLanguage"
        private const val LANGUAGE_PREF_TITLE = "Выбор языка на обложке"

        private const val API_DOMAIN_PREF = "MangaLibApiDomain"
        private const val API_DOMAIN_TITLE = "Выбор домена API"
        private const val API_DOMAIN_DEFAULT = "https://api.imglib.info"

        private const val TOKEN_STORE = "TokenStore"

        val simpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US) }
    }

    private fun isServer(): String = preferences.getString(SERVER_PREF, "compress")!!
    private fun isEng(): String = preferences.getString(LANGUAGE_PREF, "eng")!!
    private fun groupTranslates(): String = preferences.getString(TRANSLATORS_TITLE, TRANSLATORS_DEFAULT)!!
    private fun isScanUser(): Boolean = preferences.getBoolean(IS_SCAN_USER, false)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = "Сервер изображений"
            entries = arrayOf("Автовыбор", "Первый", "Второй", "Сжатия")
            entryValues = IMG_SERVERS
            summary = "%s \n\n" +
                "По умолчанию в приложении и на сайте «Сжатия» - самый стабильный и быстрый. \n\n" +
                "«Автовыбор» - проходит по всем серверам и показывает только загруженную картинку. \nМожет происходить медленно. \n\n" +
                "ⓘВыбор другого сервера помогает при ошибках и медленной загрузки изображений глав."
            setDefaultValue("compress")
            setOnPreferenceChangeListener { _, newValue ->
                val warning = "Для смены сервера: Настройки -> Дополнительно -> Очистить кэш глав"
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                true
            }
        }

        val sortingPref = ListPreference(screen.context).apply {
            key = SORTING_PREF
            title = SORTING_PREF_TITLE
            entries = arrayOf(
                "Полный список (без повторных переводов)",
                "Все переводы (друг за другом)",
            )
            entryValues = arrayOf("ms_mixing", "ms_combining")
            summary = "%s"
            setDefaultValue("ms_mixing")
        }
        val scanlatorUsername = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = IS_SCAN_USER
            title = IS_SCAN_USER_TITLE
            summary = "Отображает Ник переводчика если Группа не указана явно."
            setDefaultValue(false)
        }
        val titleLanguagePref = ListPreference(screen.context).apply {
            key = LANGUAGE_PREF
            title = LANGUAGE_PREF_TITLE
            entries = arrayOf("Английский", "Русский")
            entryValues = arrayOf("eng", "rus")
            summary = "%s"
            setDefaultValue("eng")
            setOnPreferenceChangeListener { _, _ ->
                val warning = "Если язык обложки не изменился очистите базу данных в приложении (Настройки -> Дополнительно -> Очистить базу данных)"
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                true
            }
        }

        val domainApiPref = ListPreference(screen.context).apply {
            key = API_DOMAIN_PREF
            title = API_DOMAIN_TITLE
            entries = arrayOf("Официальное приложение (api.imglib.info)", "Основной (api.lib.social)", "Резервный (api.mangalib.me)", "Резервный 2 (api2.mangalib.me)")
            entryValues = arrayOf(API_DOMAIN_DEFAULT, "https://api.lib.social", "https://api.mangalib.me", "https://api2.mangalib.me")
            summary = "%s" +
                "\n\nВыбор домена API, используемого для работы приложения." +
                "\n\nПо умолчанию «Официальное приложение»" +
                "\n\nⓘВы не увидите его нигде глазами, но источник должен начать работать стибильнее."
            setDefaultValue(API_DOMAIN_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val warning = "Для смены домена необходимо перезапустить приложение с полной остановкой."
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                true
            }
        }

        screen.addPreference(serverPref)
        screen.addPreference(sortingPref)
        screen.addPreference(screen.editTextPreference(TRANSLATORS_TITLE, TRANSLATORS_DEFAULT, groupTranslates()))
        screen.addPreference(scanlatorUsername)
        screen.addPreference(titleLanguagePref)
        screen.addPreference(domainApiPref)
    }
    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.replace("/", "\n")
            this.setDefaultValue(default)
            dialogTitle = title
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(context, "Для обновления списка необходимо перезапустить приложение с полной остановкой.", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    // api changed id of servers, remap SERVER_PREF old("fourth") to new("secondary")
    private fun SharedPreferences.migrateOldImageServer(): SharedPreferences {
        if (getString(SERVER_PREF, "main") != "fourth") return this
        edit().putString(SERVER_PREF, "secondary").apply()
        return this
    }
}
