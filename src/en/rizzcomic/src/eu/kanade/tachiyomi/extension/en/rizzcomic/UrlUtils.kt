package eu.kanade.tachiyomi.extension.en.rizzcomic

import okhttp3.HttpUrl.Companion.toHttpUrl

object UrlUtils {
    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('0'..'9')
        return List(length) { charPool.random() }.joinToString("")
    }

    fun generateSeriesLink(seriesId: Int): String {
        return buildString {
            append(randomString(4))
            append("s")
            append(randomString(4))
            append(seriesId.toString().padStart(5, '0'))
            append(randomString(12))
        }
    }

    fun generateChapterLink(seriesId: Int, chapterId: Int): String {
        return buildString {
            append(randomString(4))
            append("c")
            append(randomString(4))
            append(seriesId.toString().padStart(5, '0'))
            append(randomString(4))
            append(chapterId.toString().padStart(6, '0'))
            append(randomString(4))
        }
    }

    fun extractSeriesId(url: String): Int? {
        val regex = """^[a-z0-9]{4}s[a-z0-9]{4}(\d{5})[a-z0-9]+$""".toRegex()
        val path = url.toHttpUrl().pathSegments.last()
        return regex.find(path)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun extractChapterIds(url: String): Pair<Int, Int>? {
        val regex = """^[a-z0-9]{4}c[a-z0-9]{4}(\d{5})[a-z0-9]{4}(\d{6})[a-z0-9]+$""".toRegex()
        val path = url.toHttpUrl().pathSegments.last()
        return regex.find(path)?.let { matchResult ->
            val seriesId = matchResult.groupValues[1].toIntOrNull()
            val chapterId = matchResult.groupValues[2].toIntOrNull()
            if (seriesId != null && chapterId != null) {
                Pair(seriesId, chapterId)
            } else {
                null
            }
        }
    }
}
