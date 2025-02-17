package eu.kanade.tachiyomi.extension.all.novelfull

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.model.Filter

object Note : Filter.Header("NOTE: Ignored if using text search!")
object Tip : Filter.Header("Click on `Reset` to clear all filters")

class SelectFilterOption(val name: String, val value: String)

abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
    val selected: String
        get() = options[state].value
}

class GenreFilter(
    options: List<SelectFilterOption>,
    default: Int,
) : SelectFilter("Genre", options, default)

fun getGenreFilterOrTip(prefs: SharedPreferences): Filter<*> {
    val genres = prefs.genresFilter
    return if (genres.isEmpty()) Tip else GenreFilter(genres, 0)
}
