package eu.kanade.tachiyomi.extension.all.novelfull

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

var SharedPreferences.genresFilter: List<SelectFilterOption>
    get() {
        getString("genres", "")?.let {
            if (it.isEmpty()) return emptyList()
            val genres = it.split(",")
            val all = genres.map { genre ->
                val (name, value) = genre.split(":")
                SelectFilterOption(name, value)
            }
            return all
        }
        return emptyList()
    }
    set(value) = edit().putString("genres", value.joinToString(",") { "${it.name}:${it.value}" }).apply()

var SharedPreferences.lastGenresFetch: Long
    get() = getLong("lastGenresFetch", 0)
    set(value) = edit().putLong("lastGenresFetch", value).apply()

@Synchronized
fun fetchGenres(client: OkHttpClient, url: String, headers: Headers, prefs: SharedPreferences) {
    if (prefs.genresFilter.isNotEmpty() || System.currentTimeMillis() - prefs.lastGenresFetch < 7 * 24 * 60 * 60 * 1000) {
        return
    }

    val response = client.newCall(GET(url, headers)).execute()
    if (response.isSuccessful) {
        val document = response.asJsoup()
        val genres = document.select(".dropdown-menu a[href*=category]")
            .map { element ->
                val name = element.text() ?: "[unknown]"
                val value = element.absUrl("href").toHttpUrl().queryParameter("id") ?: "[unknown]"
                SelectFilterOption(name, value)
            }
        prefs.genresFilter = genres
        prefs.lastGenresFetch = System.currentTimeMillis()
    }
}
