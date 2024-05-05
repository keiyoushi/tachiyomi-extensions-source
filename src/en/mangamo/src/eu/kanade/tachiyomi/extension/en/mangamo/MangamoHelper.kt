package eu.kanade.tachiyomi.extension.en.mangamo

import eu.kanade.tachiyomi.extension.en.mangamo.dto.ChapterDto
import eu.kanade.tachiyomi.extension.en.mangamo.dto.DocumentDto
import eu.kanade.tachiyomi.extension.en.mangamo.dto.DocumentDtoInternal
import eu.kanade.tachiyomi.extension.en.mangamo.dto.DocumentSerializer
import eu.kanade.tachiyomi.extension.en.mangamo.dto.SeriesDto
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

class MangamoHelper(headers: Headers) {

    companion object {

        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false

            serializersModule = SerializersModule {
                contextual(DocumentDto::class) { DocumentSerializer(DocumentDtoInternal.serializer(it[0])) }
            }
        }

        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> String.parseJson(): T {
            return when (T::class) {
                DocumentDto::class -> json.decodeFromString<T>(
                    DocumentSerializer(serializer<T>() as KSerializer<out DocumentDto<out Any?>>) as KSerializer<T>,
                    this,
                )
                else -> json.decodeFromString<T>(this)
            }
        }
    }

    val jsonHeaders = headers.newBuilder()
        .set("Content-Type", "application/json")
        .build()

    private fun getCatalogUrl(series: SeriesDto): String {
        val lowercaseHyphenated = series.nameLowercase!!.replace(' ', '-')
        return "${MangamoConstants.MANGAMO_WEBSITE}/catalog/$lowercaseHyphenated"
    }

    fun getSeriesUrl(series: SeriesDto): String {
        return getCatalogUrl(series).toHttpUrl().newBuilder()
            .setQueryParameter(MangamoConstants.SERIES_QUERY_PARAM, series.id.toString())
            .build()
            .toString()
    }

    fun getChapterUrl(chapter: ChapterDto): String {
        return MangamoConstants.MANGAMO_WEBSITE.toHttpUrl().newBuilder()
            .setQueryParameter(MangamoConstants.SERIES_QUERY_PARAM, chapter.seriesId.toString())
            .setQueryParameter(MangamoConstants.CHAPTER_QUERY_PARAM, chapter.id.toString())
            .build()
            .toString()
    }

    fun getSeriesStatus(series: SeriesDto): Int =
        when (series.releaseStatusTag) {
            "Ongoing" -> SManga.ONGOING
            "series-complete" -> SManga.COMPLETED
            "Completed" -> SManga.COMPLETED
            "Paused" -> SManga.ON_HIATUS
            else ->
                if (series.ongoing == true) {
                    SManga.ONGOING
                } else {
                    SManga.UNKNOWN
                }
        }
}
