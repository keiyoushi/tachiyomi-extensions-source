package eu.kanade.tachiyomi.extension.ja.mangamura

import eu.kanade.tachiyomi.source.model.Filter

object Note : Filter.Header("NOTE: Ignored if using text search!")

sealed class Select(
    name: String,
    val param: String,
    values: Array<String>,
) : Filter.Select<String>(name, values) {
    open val selection: String
        get() = if (state == 0) "" else state.toString()
}

class TypeFilter(
    values: Array<String> = types.keys.toTypedArray(),
) : Select("タイプ", "type", values) {
    override val selection: String
        get() = types[values[state]]!!

    companion object {
        private val types = mapOf(
            "全て" to "all",
            "Raw Manga" to "Raw Manga",
            "BLコミック" to "BLコミック",
            "TLコミック" to "TLコミック",
            "オトナコミック" to "オトナコミック",
            "女性マンガ" to "女性マンガ",
            "少女マンガ" to "少女マンガ",
            "少年マンガ" to "少年マンガ",
            "青年マンガ" to "青年マンガ",
        )
    }
}

class StatusFilter(
    values: Array<String> = statuses.keys.toTypedArray(),
) : Select("地位", "status", values) {
    override val selection: String
        get() = statuses[values[state]]!!

    companion object {
        private val statuses = mapOf(
            "全て" to "all",
            "Publishing" to "Publishing",
            "Finished" to "Finished",
        )
    }
}

class LanguageFilter(
    values: Array<String> = languages.keys.toTypedArray(),
) : Select("言語", "language", values) {
    override val selection: String
        get() = languages[values[state]]!!

    companion object {
        private val languages = mapOf(
            "全て" to "all",
            "Japanese" to "ja",
            "English" to "en",
        )
    }
}

class SortFilter(
    values: Array<String> = sort.keys.toTypedArray(),
) : Select("選別", "sort", values) {
    override val selection: String
        get() = sort[values[state]]!!

    companion object {
        private val sort = mapOf(
            "デフォルト" to "default",
            "最新の更新" to "latest-updated",
            "最も見られました" to "most-viewed",
            "Title [A-Z]" to "title-az",
            "Title [Z-A]" to "title-za",
        )
    }
}

class Genre(name: String, val id: String) : Filter.CheckBox(name)

class GenresFilter(
    values: List<Genre> = genres,
) : Filter.Group<Genre>("ジャンル", values) {
    val param = "genre[]"

    companion object {
        private val genres: List<Genre>
            get() = listOf(
                Genre("アクション", "55"),
                Genre("エッチ", "15706"),
                Genre("コメディ", "91"),
                Genre("ドラマ", "56"),
                Genre("ハーレム", "20"),
                Genre("ファンタジー", "1"),
                Genre("冒険", "54"),
                Genre("悪魔", "6820"),
                Genre("武道", "1064"),
                Genre("歴史的", "9600"),
                Genre("警察・特殊部隊", "6089"),
                Genre("車･バイク", "4329"),
                Genre("音楽", "473"),
                Genre("魔法", "1416"),
            )
    }
}
