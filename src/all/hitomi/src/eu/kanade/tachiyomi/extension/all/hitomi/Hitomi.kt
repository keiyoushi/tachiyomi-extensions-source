package eu.kanade.tachiyomi.extension.all.hitomi

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

@OptIn(ExperimentalUnsignedTypes::class)
class Hitomi(
    override val lang: String,
    private val nozomiLang: String,
) : ConfigurableSource, HttpSource() {

    override val name = "Hitomi"

    private val domain = "hitomi.la"

    override val baseUrl = "https://$domain"

    private val ltnUrl = "https://ltn.$domain"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var iconified = preferences.getBoolean(PREF_TAG_GENDER_ICON, false)

    override fun headersBuilder() = super.headersBuilder()
        .set("referer", "$baseUrl/")
        .set("origin", baseUrl)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking {
            val entries = getGalleryIDsFromNozomi("popular", "year", nozomiLang, page.nextPageRange())
                .toMangaList()

            MangasPage(entries, entries.size >= 24)
        }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking {
            val entries = getGalleryIDsFromNozomi(null, "index", nozomiLang, page.nextPageRange())
                .toMangaList()

            MangasPage(entries, entries.size >= 24)
        }
    }

    private lateinit var searchResponse: List<Int>

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        runBlocking {
            if (page == 1) {
                searchResponse = hitomiSearch(
                    query.trim(),
                    filters,
                    nozomiLang,
                ).toList()
            }

            val end = min(page * 25, searchResponse.size)
            val entries = searchResponse.subList((page - 1) * 25, end)
                .toMangaList()
            MangasPage(entries, end < searchResponse.size)
        }
    }

    override fun getFilterList() = getFilters()

    private fun Int.nextPageRange(): LongRange {
        val byteOffset = ((this - 1) * 25) * 4L
        return byteOffset.until(byteOffset + 100)
    }

    private suspend fun getRangedResponse(url: String, range: LongRange?): ByteArray {
        val rangeHeaders = when (range) {
            null -> headers
            else -> headersBuilder()
                .set("Range", "bytes=${range.first}-${range.last}")
                .build()
        }

        return client.newCall(GET(url, rangeHeaders)).awaitSuccess().use { it.body.bytes() }
    }

    private suspend fun hitomiSearch(
        query: String,
        filters: FilterList,
        language: String = "all",
    ): Set<Int> =
        coroutineScope {
            var sortBy: Pair<String?, String> = Pair(null, "index")
            var random = false

            var terms = query
                .trim()
                .replace(Regex("""^\?"""), "")
                .lowercase()
                .split(Regex("\\s+"))
                .map {
                    it.replace('_', ' ')
                }

            filters.forEach {
                when (it) {
                    is SelectFilter -> {
                        sortBy = Pair(it.getArea(), it.getValue())
                        random = (it.vals[it.state].first == "Random")
                    }

                    is TypeFilter -> {
                        val (activeFilter, inactiveFilters) = it.state.partition { stIt -> stIt.state }
                        terms = terms + when {
                            inactiveFilters.size < 5 -> inactiveFilters.map { fil -> "-type:${fil.value}" }
                            inactiveFilters.size == 5 -> listOf("type:${activeFilter[0].value}")
                            else -> listOf("type: none")
                        }
                    }

                    is TextFilter -> {
                        if (it.state.isNotEmpty()) {
                            val tagType = it.tagType().split(" ")[0].lowercase().removeSuffix(if (it.tagType() != "Series") "s" else "") + ":"
                            terms = terms + it.state.split(",").map { tag ->
                                val trimmed = tag.trim()
                                (if (trimmed.startsWith("-")) "-" else "") + tagType + trimmed.lowercase().removePrefix("-")
                            }
                        }
                    }
                    else -> {}
                }
            }

            val positiveTerms = LinkedList<String>()
            val negativeTerms = LinkedList<String>()

            for (term in terms) {
                if (term.startsWith("-")) {
                    negativeTerms.push(term.removePrefix("-"))
                } else if (term.isNotBlank()) {
                    positiveTerms.push(term)
                }
            }

            val positiveResults = positiveTerms.map {
                async {
                    runCatching {
                        getGalleryIDsForQuery(it, language)
                    }.getOrDefault(emptySet())
                }
            }

            val negativeResults = negativeTerms.map {
                async {
                    runCatching {
                        getGalleryIDsForQuery(it, language)
                    }.getOrDefault(emptySet())
                }
            }

            var results = when {
                positiveTerms.isEmpty() -> getGalleryIDsFromNozomi(sortBy.first, sortBy.second, language)
                else -> emptySet()
            }.toMutableSet()

            fun filterPositive(newResults: Set<Int>) {
                when {
                    results.isEmpty() -> results.addAll(newResults)
                    else -> results.retainAll(newResults)
                }
            }

            fun filterNegative(newResults: Set<Int>) {
                results.removeAll(newResults)
            }

            // positive results
            positiveResults.forEach {
                filterPositive(it.await())
            }

            // negative results
            negativeResults.forEach {
                filterNegative(it.await())
            }

            if (random) results = results.shuffle()
            results
        }

    private fun <T> MutableSet<T>.shuffle(): MutableSet<T> {
        val list = this.toMutableList()
        for (i in list.size - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            list[i] = list[j].also { list[j] = list[i] }
        }
        return list.toMutableSet()
    }

    // search.js
    private suspend fun getGalleryIDsForQuery(
        query: String,
        language: String = "all",
    ): Set<Int> {
        query.replace("_", " ").let {
            if (it.indexOf(':') > -1) {
                val sides = it.split(":")
                val ns = sides[0]
                var tag = sides[1]

                var area: String? = ns
                var lang = language
                when (ns) {
                    "female", "male" -> {
                        area = "tag"
                        tag = it
                    }

                    "tag", "artist", "group", "series", "type", "character" -> {
                        area = ns
                        tag = sides[1]
                    }

                    "language" -> {
                        area = null
                        lang = tag
                        tag = "index"
                    }
                }

                return getGalleryIDsFromNozomi(area, tag, lang)
            }

            val key = hashTerm(it)
            val node = getGalleryNodeAtAddress(0)
            val data = bSearch(key, node) ?: return emptySet()

            return getGalleryIDsFromData(data)
        }
    }

    private suspend fun getGalleryIDsFromData(data: Pair<Long, Int>): Set<Int> {
        val url = "$ltnUrl/galleriesindex/galleries.$galleriesIndexVersion.data"
        val (offset, length) = data
        require(length in 1..100000000) {
            "Length $length is too long"
        }

        val inbuf = getRangedResponse(url, offset.until(offset + length))

        val galleryIDs = mutableSetOf<Int>()

        val buffer =
            ByteBuffer
                .wrap(inbuf)
                .order(ByteOrder.BIG_ENDIAN)

        val numberOfGalleryIDs = buffer.int

        val expectedLength = numberOfGalleryIDs * 4 + 4

        require(numberOfGalleryIDs in 1..10000000) {
            "number_of_galleryids $numberOfGalleryIDs is too long"
        }
        require(inbuf.size == expectedLength) {
            "inbuf.byteLength ${inbuf.size} != expected_length $expectedLength"
        }

        for (i in 0.until(numberOfGalleryIDs))
            galleryIDs.add(buffer.int)

        return galleryIDs
    }

    private tailrec suspend fun bSearch(
        key: UByteArray,
        node: Node,
    ): Pair<Long, Int>? {
        fun compareArrayBuffers(
            dv1: UByteArray,
            dv2: UByteArray,
        ): Int {
            val top = min(dv1.size, dv2.size)

            for (i in 0.until(top)) {
                if (dv1[i] < dv2[i]) {
                    return -1
                } else if (dv1[i] > dv2[i]) {
                    return 1
                }
            }

            return 0
        }

        fun locateKey(
            key: UByteArray,
            node: Node,
        ): Pair<Boolean, Int> {
            for (i in node.keys.indices) {
                val cmpResult = compareArrayBuffers(key, node.keys[i])

                if (cmpResult <= 0) {
                    return Pair(cmpResult == 0, i)
                }
            }

            return Pair(false, node.keys.size)
        }

        fun isLeaf(node: Node): Boolean {
            for (subnode in node.subNodeAddresses)
                if (subnode != 0L) {
                    return false
                }

            return true
        }

        if (node.keys.isEmpty()) {
            return null
        }

        val (there, where) = locateKey(key, node)
        if (there) {
            return node.datas[where]
        } else if (isLeaf(node)) {
            return null
        }

        val nextNode = getGalleryNodeAtAddress(node.subNodeAddresses[where])
        return bSearch(key, nextNode)
    }

    private suspend fun getGalleryIDsFromNozomi(
        area: String?,
        tag: String,
        language: String,
        range: LongRange? = null,
    ): Set<Int> {
        val nozomiAddress = when (area) {
            null -> "$ltnUrl/$tag-$language.nozomi"
            else -> "$ltnUrl/$area/$tag-$language.nozomi"
        }

        val bytes = getRangedResponse(nozomiAddress, range)
        val nozomi = mutableSetOf<Int>()

        val arrayBuffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)

        while (arrayBuffer.hasRemaining())
            nozomi.add(arrayBuffer.int)

        return nozomi
    }

    private val galleriesIndexVersion by lazy {
        client.newCall(
            GET("$ltnUrl/galleriesindex/version?_=${System.currentTimeMillis()}", headers),
        ).execute().use { it.body.string() }
    }

    private data class Node(
        val keys: List<UByteArray>,
        val datas: List<Pair<Long, Int>>,
        val subNodeAddresses: List<Long>,
    )

    private fun decodeNode(data: ByteArray): Node {
        val buffer = ByteBuffer
            .wrap(data)
            .order(ByteOrder.BIG_ENDIAN)

        val uData = data.toUByteArray()

        val numberOfKeys = buffer.int
        val keys = ArrayList<UByteArray>()

        for (i in 0.until(numberOfKeys)) {
            val keySize = buffer.int

            if (keySize == 0 || keySize > 32) {
                throw Exception("fatal: !keySize || keySize > 32")
            }

            keys.add(uData.sliceArray(buffer.position().until(buffer.position() + keySize)))
            buffer.position(buffer.position() + keySize)
        }

        val numberOfDatas = buffer.int
        val datas = ArrayList<Pair<Long, Int>>()

        for (i in 0.until(numberOfDatas)) {
            val offset = buffer.long
            val length = buffer.int

            datas.add(Pair(offset, length))
        }

        val numberOfSubNodeAddresses = 16 + 1
        val subNodeAddresses = ArrayList<Long>()

        for (i in 0.until(numberOfSubNodeAddresses)) {
            val subNodeAddress = buffer.long
            subNodeAddresses.add(subNodeAddress)
        }

        return Node(keys, datas, subNodeAddresses)
    }

    private suspend fun getGalleryNodeAtAddress(address: Long): Node {
        val url = "$ltnUrl/galleriesindex/galleries.$galleriesIndexVersion.index"

        val nodedata = getRangedResponse(url, address.until(address + 464))

        return decodeNode(nodedata)
    }

    private fun hashTerm(term: String): UByteArray {
        return sha256(term.toByteArray()).copyOfRange(0, 4).toUByteArray()
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private suspend fun Collection<Int>.toMangaList() = coroutineScope {
        map { id ->
            async {
                runCatching {
                    client.newCall(GET("$ltnUrl/galleries/$id.js", headers))
                        .awaitSuccess()
                        .parseScriptAs<Gallery>()
                        .toSManga()
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun Gallery.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = galleryurl
        author = groups?.joinToString { it.formatted }
        artist = artists?.joinToString { it.formatted }
        genre = tags?.joinToString { it.getFormatted(iconified) }
        thumbnail_url = files.first().let {
            val hash = it.hash
            val imageId = imageIdFromHash(hash)
            val subDomain = 'a' + subdomainOffset(imageId)

            "https://${subDomain}tn.$domain/webpbigtn/${thumbPathFromHash(hash)}/$hash.webp"
        }
        description = buildString {
            parodys?.joinToString { it.formatted }?.let {
                append("Series: ", it, "\n")
            }
            characters?.joinToString { it.formatted }?.let {
                append("Characters: ", it, "\n\n")
            }
            append("Type: ", type, "\n")
            append("Pages: ", files.size, "\n")
            append("Language: ", language ?: "N/A")
        }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url
            .substringAfterLast("-")
            .substringBefore(".")

        return GET("$ltnUrl/galleries/$id.js", headers)
    }

    override fun mangaDetailsParse(response: Response) = runBlocking {
        response.parseScriptAs<Gallery>().toSManga()
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url
            .substringAfterLast("-")
            .substringBefore(".")

        return GET("$ltnUrl/galleries/$id.js#${manga.url}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val gallery = response.parseScriptAs<Gallery>()
        val mangaUrl = response.request.url.fragment!!

        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                url = mangaUrl
                scanlator = gallery.type
                date_upload = runCatching {
                    dateFormat.parse(gallery.date.substringBeforeLast("-"))!!.time
                }.getOrDefault(0L)
            },
        )
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url
            .substringAfterLast("-")
            .substringBefore(".")

        return GET("$ltnUrl/galleries/$id.js", headers)
    }

    override fun pageListParse(response: Response) = runBlocking {
        val gallery = response.parseScriptAs<Gallery>()

        gallery.files.mapIndexed { idx, img ->
            val hash = img.hash
            val commonId = commonImageId()
            val imageId = imageIdFromHash(hash)
            val subDomain = 'a' + subdomainOffset(imageId)

            Page(
                idx,
                "$baseUrl/reader/$id.html",
                "https://${subDomain}a.$domain/webp/$commonId$imageId/$hash.webp",
            )
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    private inline fun <reified T> Response.parseScriptAs(): T =
        parseAs<T> { it.substringAfter("var galleryinfo = ") }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { body -> body }): T {
        val body = use { it.body.string() }
        val transformed = transform(body)

        return json.decodeFromString(transformed)
    }

    private suspend fun Call.awaitSuccess() =
        await().also {
            require(it.isSuccessful) {
                it.close()
                "HTTP error ${it.code}"
            }
        }

    // ------------------ gg.js ------------------
    private var scriptLastRetrieval: Long? = null
    private val mutex = Mutex()
    private var subdomainOffsetDefault = 0
    private val subdomainOffsetMap = mutableMapOf<Int, Int>()
    private var commonImageId = ""

    private suspend fun refreshScript() = mutex.withLock {
        if (scriptLastRetrieval == null || (scriptLastRetrieval!! + 60000) < System.currentTimeMillis()) {
            val ggScript = client.newCall(
                GET("$ltnUrl/gg.js?_=${System.currentTimeMillis()}", headers),
            ).awaitSuccess().use { it.body.string() }

            subdomainOffsetDefault = Regex("var o = (\\d)").find(ggScript)!!.groupValues[1].toInt()
            val o = Regex("o = (\\d); break;").find(ggScript)!!.groupValues[1].toInt()

            subdomainOffsetMap.clear()
            Regex("case (\\d+):").findAll(ggScript).forEach {
                val case = it.groupValues[1].toInt()
                subdomainOffsetMap[case] = o
            }

            commonImageId = Regex("b: '(.+)'").find(ggScript)!!.groupValues[1]

            scriptLastRetrieval = System.currentTimeMillis()
        }
    }

    // m <-- gg.js
    private suspend fun subdomainOffset(imageId: Int): Int {
        refreshScript()
        return subdomainOffsetMap[imageId] ?: subdomainOffsetDefault
    }

    // b <-- gg.js
    private suspend fun commonImageId(): String {
        refreshScript()
        return commonImageId
    }

    // s <-- gg.js
    private fun imageIdFromHash(hash: String): Int {
        val match = Regex("(..)(.)$").find(hash)
        return match!!.groupValues.let { it[2] + it[1] }.toInt(16)
    }

    // real_full_path_from_hash <-- common.js
    private fun thumbPathFromHash(hash: String): String {
        return hash.replace(Regex("""^.*(..)(.)$"""), "$2/$1")
    }

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TAG_GENDER_ICON
            title = "Show gender as text or icon in tags (requires refresh)"
            summaryOff = "Show gender as text"
            summaryOn = "Show gender as icon"

            setOnPreferenceChangeListener { _, newValue ->
                iconified = newValue == true
                true
            }
        }.also(screen::addPreference)
    }
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
    companion object {
        private const val PREF_TAG_GENDER_ICON = "pref_tag_gender_icon"
    }
}
